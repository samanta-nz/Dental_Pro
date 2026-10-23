package com.example

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil.ImageLoader
import coil.Coil
import androidx.fragment.app.FragmentActivity
import com.example.ui.DentalViewModel
import com.example.ui.screens.MainDentalScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : FragmentActivity() {
  private val viewModel: DentalViewModel by viewModels()

  override fun onStart() {
    super.onStart()
    viewModel.onAppForegrounded()
  }

  /** برداشت کد OAuth (dental://app?code=...) از Deep Link. */
  private fun handleDeepLink(intent: android.content.Intent?) {
    try {
      val uri = intent?.data ?: return
      if (uri.scheme != "dental" || uri.host != "app") return
      val code = uri.getQueryParameter("code")
      if (!code.isNullOrBlank()) viewModel.handleOAuthCallback(code)
    } catch (e: Exception) {
      // deep link بی‌اهمیت — نادیده گرفته می‌شود
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    handleDeepLink(intent)
    if (intent.getBooleanExtra("EXTRA_OPEN_APP_UPDATE", false)) viewModel.checkForAppUpdate(notify = false)
  }

  override fun onStop() {
    viewModel.onAppBackgrounded()
    super.onStop()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // اسپلشِ استاندارد باید قبل از super.onCreate نصب شود. تا وقتی داده آماده
    // نشده (isBootReady) روی صفحه می‌ماند، پس کاربر پنجرهٔ خالی نمی‌بیند.
    val splash = installSplashScreen()
    splash.setKeepOnScreenCondition { !viewModel.isBootReady.value }

    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleDeepLink(intent)

    // کارهای غیرضروری برای اولین فریم به بعد از رسمِ UI موکول می‌شوند تا
    // onCreate سبک بماند و اولین فریم زودتر بیاید.
    window.decorView.post {
      // Coil با شتاب سخت‌افزاری (allowHardware=true پیش‌فرض) تا اسکرول لیست‌های
      // دارای عکس روان‌تر و مصرف حافظه کمتر شود.
      Coil.setImageLoader(ImageLoader.Builder(applicationContext).build())

      // یادآورها + سینکِ دوره‌ای روی WorkManager (idempotent با KEEP) + آپدیت ویجت.
      com.example.reminders.ReminderCheckWorker.schedule(applicationContext)
      com.example.sync.SyncWorker.schedule(applicationContext)
      com.example.util.AppUpdateWorker.schedule(applicationContext)
      com.example.widget.AppointmentsWidgetProvider.requestUpdate(applicationContext)
    }

    setContent {
      val settings by viewModel.settings.collectAsStateWithLifecycle()

      val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
      ) { /* no-op: reminders simply won't show if declined */ }

      LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }

      MyApplicationTheme(themeId = settings.themeName, darkTheme = settings.isDarkMode, fontScaleIndex = settings.globalFontScaleIndex) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background
        ) {
          val isUnlocked by viewModel.isAppUnlocked.collectAsStateWithLifecycle()
          val initialBankSms = remember { intent?.getStringExtra("EXTRA_OPEN_BANK_SMS") }
          val initialOpenAppUpdate = remember { intent?.getBooleanExtra("EXTRA_OPEN_APP_UPDATE", false) ?: false }
          if (isUnlocked) {
            MainDentalScreen(viewModel = viewModel, initialBankSms = initialBankSms, initialOpenAppUpdate = initialOpenAppUpdate)
          } else {
            AuthScreen(
              viewModel = viewModel,
              onUnlocked = { viewModel.isAppUnlocked.value = true }
            )
          }

        }
      }
    }
  }
}
