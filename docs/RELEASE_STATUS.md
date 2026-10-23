# Release status: 7.0.1 engineering candidate

Version `7.0.1` / `70001`, application ID `com.dentalpro.clinic`, Room schema `17`.

The user requested this build and explicitly authorized a new signing identity for a fresh installation. The release guard remains enabled: packaging requires external signing configuration and `RELEASE_APPROVED=true`. The private key is excluded from Git; its private backup must be retained for every future update.

## Verified

- Local accounting, synchronization, media, numeric parsing and Room 16→17 migration regression tests pass. Final test count and APK verification are recorded below after the final build.
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

Final signed build and verification pending.
