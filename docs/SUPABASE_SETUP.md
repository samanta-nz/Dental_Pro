# Supabase setup for DentalPro 7.0.1

Project: DentalPro (`geifrkzmiutpkbwejdms`). Client URL and publishable key were verified through the connected Supabase account. Never put a service-role key in the app or repository.

## Existing project

The connected project has already received the 7.0.1 migrations corresponding to local files `0016` through `0021`. They add missing app fields, private media policies, stable financial/transfer identities, source-derived bank/ledger projections, durable deletion tombstones and amount constraints.

A pre-change snapshot is stored in the unexposed, access-restricted schema `dentalpro_recovery.release_snapshots` with `release = '7.0.1-before'`. It contains rows from public tables and Storage metadata. It is not a backup of image bytes. Existing binary objects were neither moved nor deleted.

The original source rows were compared against that snapshot after migration. Ten patients, 24 payments, all three existing bank balances and 14 clinical objects were preserved. Historical ledger projections remain as `SUPERSEDED`; 27 current projections derive from the 27 financial source rows. Two existing cheque-to-party transfers now have stable destination IDs.

One pre-existing RADIOGRAPH document references a missing Storage object. Its record is retained. Recover that image from the old device/export if available before uninstalling the old app.

Local SQLite migration notes live in `docs/ROOM_SCHEMA_HISTORY.sql`; the current exported Room schema is `app/schemas/com.example.data.AppDatabase/17.json`. Neither is PostgreSQL setup SQL.

## Fresh database

The Supabase Auth/Storage schemas and roles must already exist. Regenerate and apply the combined app schema:

```sh
python scripts/build-supabase-schema.py
```

Run `supabase/dentalpro_all_in_one.sql` through the SQL editor. The generator excludes the historical 7.0.0 forced-update announcement and the obsolete global local-ID ledger index. For an existing installation, prefer individually tracked migrations and inspect data before applying changes.

## Google OAuth

In Authentication → Providers, enable Google with the project's Google OAuth client credentials. Google must allow the Supabase callback URL:

`https://geifrkzmiutpkbwejdms.supabase.co/auth/v1/callback`

The Supabase redirect allow-list must include `dental://app`. The app uses a generated PKCE verifier stored securely until the code exchange succeeds. No Google client secret belongs in the APK. New installations sign in with the same Google account that owns their clinic rows.

The app exposes Google as its only login option. Legacy OTP and phone lookup endpoints are retired. External provider settings remain managed in the Supabase dashboard; removing a button does not itself change the Auth service's provider configuration.

## Storage

- `patient_media`: private patient photos, signatures, clinical documents and cheque images.
- `clinic_assets`: private clinic/staff assets and invoice images.
- `app_releases`: public APK downloads; upload is administrative.

Clinical read/write policies verify owner/path identity. Existing `{owner}/{uuid}.jpg` objects remain readable by their owner. New uploads use `{owner}/{category}/{sha256}.{ext}`. Equal content can be referenced by multiple documents, so deleting a record does not delete its binary object. Any future garbage collector must prove there are no remaining references before deletion.

## Edge Functions

Deployed for this release with JWT verification enabled:

- `send-otp`, `verify-otp`, `phone-to-email`: retired; return HTTP 410 for application requests.
- `extract-cheque-data`: requires a real authenticated user; provider secrets stay on the server.
- `delete-account`: validates the caller, removes their files through Storage API, removes their rows via owner-scoped RPC, then removes their Auth user through Auth Admin API. It also removes the caller's retained snapshot rows. It must only be invoked after the app's explicit deletion confirmation.

The Node test suite uses mocked network responses and never deletes a real account:

```sh
node supabase/functions/tests/auth-and-deletion.test.mjs
```

## Publish future updates

Build the APK with the 7.0.1 signing key. From a trusted publishing environment, inject `SUPABASE_SERVICE_ROLE_KEY` and run:

```sh
python scripts/publish-release.py path/to/app-release.apk --code 70002 --name 7.0.2 --notes release-notes.txt
```

The script uploads first and publishes `version_code`, `version_name`, `apk_url`, `apk_sha256`, `apk_size_bytes`, changelog and optional mandatory status afterward. Alternatively use the dashboard with the same fields. Merely uploading a file does not announce it.

The current release's APK must be built and verified before any announcement. The first installation with the newly requested signing key is manual; future releases signed with that key can use the in-app installer.
