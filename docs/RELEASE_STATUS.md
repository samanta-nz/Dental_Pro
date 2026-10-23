# Release status: 7.0.1 engineering candidate

Version `7.0.1` / `70001`, application ID `com.dentalpro.clinic`, Room schema `17`.

The user requested this build and explicitly authorized a new signing identity for a fresh installation. The release guard remains enabled: packaging requires external signing configuration and `RELEASE_APPROVED=true`. The private key is excluded from Git; its private backup must be retained for every future update.

## Verified

- Local accounting, synchronization, media, numeric parsing and Room 16→17 migration regression tests pass. Final test count and APK verification are recorded below.
- Android lint passes with no errors; existing warnings remain.
- Edge-function source mock tests pass, including authentication and deletion isolation.
- Production SQL rollback-only regression checks pass for financial insert/edit/retry/delete, cheque state transitions, bank/ledger projection and deletion tombstones.
- Supabase migrations corresponding to `0016`–`0021` were applied. All 29 synchronized models have the required remote columns.
- Original source records and three bank balances were preserved and compared with the private pre-change snapshot. All 14 Storage objects remain. Anonymous access to clinical objects is blocked.
- Release packaging without explicit approval fails before packaging.
- SQLCipher was upgraded for 16 KB page support; native library alignment is checked on the delivered APK.

## Device checks still required

This build is available for user acceptance testing. Google OAuth callback on a physical phone, installation/update installer flow, clean-device backup restoration, printer behavior, accessibility and independent clinical decision-support validation have not been completed in this environment. The broader production checklist remains in [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md); this engineering build is not a claim that those separate sign-offs occurred.

One pre-existing radiograph record points to a missing Storage object. Its record was preserved. Export that image from the old phone if available **before uninstalling**. The server snapshot contains rows and Storage metadata, not binary copies of the images.

The initial APK is distributed manually because its signing key is new. Future versions must use this same key and can be published with `scripts/publish-release.py`; the updater validates HTTPS, SHA-256, byte length, package, version and signing identity, then uses Android's installation confirmation. No placeholder release has been announced on Supabase.

See [the Persian release report](RELEASE_7.0.1_FA.md) and [Supabase setup](SUPABASE_SETUP.md).

## Final artifact

Signed release build completed successfully. The delivered APK is `DentalPro-7.0.1-release.apk` (35,438,828 bytes).

- Unit tests: **87 passed, 0 failures, 0 errors, 0 skipped**.
- Android lint: **0 errors**, 205 warnings.
- APK signature verified and matched the new private signing key.
- Package/version checked: `com.dentalpro.clinic`, `7.0.1` / `70001`; not debuggable.
- ZIP alignment and all five ARM64 native libraries passed 16 KB alignment checks.
- APK SHA-256: `4c7449ef7e4d02b408555525454d8a96838942653754ecaed8ed8c8a76708f96`.
- Signing certificate SHA-256: `a733266c0c7cf78db6ea99286e103385e52a76ebab3858fb91824a259988ce84`.

GitHub automation is in [PR #1](https://github.com/samanta-nz/Dental_Pro/pull/1). It runs CI and publishes a signed release automatically when a version change reaches `main`. Signing secrets must be registered through repository Settings because the connected tool cannot manage Secrets. See [GitHub release instructions](GITHUB_RELEASE_FA.md). The private key and private Secrets bundle are excluded from Git.
