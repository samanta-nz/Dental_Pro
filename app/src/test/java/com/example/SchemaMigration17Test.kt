package com.example

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.security.SecureSecretStore
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SchemaMigration17Test {
    @Test fun version16MigratesWithoutLosingRowsAndBackfillsStableLedgerIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration17-test"
        context.deleteDatabase(name)
        val schema = JsonParser.parseString(File("schemas/com.example.data.AppDatabase/16.json").readText()).asJsonObject.getAsJsonObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile!!.mkdirs() }, null).use { sqlite ->
            for (table in schema.getAsJsonArray("entities")) {
                val entity = table.asJsonObject
                val tableName = entity.get("tableName").asString
                sqlite.execSQL(entity.get("createSql").asString.replace("\u0024{TABLE_NAME}", tableName))
                entity.getAsJsonArray("indices")?.forEach { index -> sqlite.execSQL(index.asJsonObject.get("createSql").asString.replace("\u0024{TABLE_NAME}", tableName)) }
                if (tableName in setOf("patients", "payments", "financial_ledger")) {
                    val values = ContentValues()
                    for (field in entity.getAsJsonArray("fields")) {
                        val f = field.asJsonObject
                        if (f.get("notNull")?.asBoolean != true) continue
                        if (f.get("affinity").asString == "TEXT") values.put(f.get("columnName").asString, "") else values.put(f.get("columnName").asString, 0L)
                    }
                    values.put("id", 1L); values.put("syncId", "old-$tableName")
                    if (tableName == "financial_ledger") { values.put("sourceType", "PATIENT_PAYMENT"); values.put("sourceId", 1L) }
                    sqlite.insertOrThrow(tableName, null, values)
                }
            }
            sqlite.version = 16
        }
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            db.openHelper.readableDatabase.query("select count(*) from patients").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.openHelper.readableDatabase.query("select sourceSyncId from financial_ledger").use { it.moveToFirst(); assertEquals("old-payments", it.getString(0)) }
            assertEquals(17, db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun logoutSecretCleanupRetainsTheDatabaseKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("dental_secure_secrets", Context.MODE_PRIVATE)
        prefs.edit().putString("enc_v1_database_passphrase_v1", "encrypted-db-key").putString("enc_v1_supabase_session", "encrypted-session").commit()
        SecureSecretStore.clearAll(context)
        assertEquals("encrypted-db-key", prefs.getString("enc_v1_database_passphrase_v1", null))
        assertFalse(prefs.contains("enc_v1_supabase_session"))
        prefs.edit().clear().commit()
    }
}
