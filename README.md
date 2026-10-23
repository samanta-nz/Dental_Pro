# DentalPro Android — 7.0.1

Google-only sign-in, an encrypted Room database, and owner-isolated Supabase sync for clinic records and media.

This is an engineering release candidate. Device-level Google OAuth, Android installation, clinical workflows and physical backup restoration still require acceptance testing; see [release status](docs/RELEASE_STATUS.md) and [7.0.1 changes](docs/RELEASE_7.0.1_FA.md).

## Build

JDK 17, Android SDK 36 and Build Tools are required. The official Gradle 9.3.1 wrapper and its distribution checksum are checked in.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

A release APK requires a private `keystore.properties`, `VERSION_CODE=70001`, `VERSION_NAME=7.0.1`, and an explicit build authorization:

```sh
./gradlew assembleRelease -PRELEASE_APPROVED=true
```

Signing material, local configuration, credentials and generated deliverables are ignored by Git. The new 7.0.1 signing key was explicitly requested for a fresh installation. Preserve that key securely: subsequent in-app updates must use the same signing identity.

## Authentication and backend

The app supports Google OAuth with PKCE. Tokens and the verifier use Android Keystore-backed encrypted storage. A configured local PIN still protects device access. The previous email/SMS login screens are removed; legacy OTP and phone-lookup Edge Functions return `GOOGLE_SIGN_IN_REQUIRED`.

Use the same Google account to restore existing clinic data. Database setup and publishing are documented in [SUPABASE_SETUP.md](docs/SUPABASE_SETUP.md). The Supabase publishable key is public by design; service-role credentials must never enter the app or Git.

Clinical buckets are private. Existing object paths are preserved. Uploads use `{owner}/{category}/{sha256}.{ext}`; an operation is acknowledged only after its referenced files upload successfully. Shared content-addressed objects are retained when an individual record is removed.

## Accounting and sync

Amounts are integer tomans. Pending and bounced cheques do not settle receivables. Confirmed clearing moves cash; cheque endorsement settles the patient/recipient account without depositing cash into a bank. A clinic draw reduces the linked clinic bank balance. Bank balances derive from an opening baseline plus source movements; the server serializes updates and derives its ledger from stable source IDs.

Failed deliveries remain in the durable outbox. Pulls preserve pending local edits. Deletion tombstones handle empty collections. Foreground/manual sync and the 15-minute background worker send and receive data. Logout retains unsent changes by refusing destructive cleanup until delivery succeeds.

## In-app updates

Account → App updates provides manual checks and a Wi-Fi auto-download option. WorkManager checks every six hours. Downloads must match the published SHA-256, size, package, version code and installed signing certificate. Android asks the user to confirm installation.

Publish a future signed APK with `scripts/publish-release.py` from a trusted environment, or upload it to `app_releases` and enter equivalent metadata through the Supabase dashboard. No incomplete or placeholder release is announced automatically.

## Exports

Cards and printable reports share `UnifiedImageGenerator`, `ExportDocKind` and `AppFonts`. See [export rendering](docs/EXPORT_RENDERING.md) and [fonts](docs/FONTS.md).
