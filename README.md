# Photo-Locker Windows

**Windows 10 x64 desktop edition** of Photo-Locker: an offline-first private photo and video vault.

## Security foundation (implemented)
- Streaming AES-256-GCM file encryption core with 12-byte random nonce and 128-bit authentication tag.
- Versioned encrypted file header authenticated as GCM AAD.
- 256-bit vault key generation using SecureRandom.
- Windows DPAPI current-user protection/unprotection for vault keys.
- DPAPI-wrapped key persistence with atomic file write.
- Unit tests for crypto round-trip, nonce randomization, and ciphertext tampering.

## Important status
Security modules are committed but **not yet integrated into the UI or a complete vault lifecycle**. Do not store personal media in this early build. DPAPI, Windows file ACLs, key lifecycle, cache handling, crash recovery, and backup interoperability require further review and testing on Windows 10.

## Technology
- Kotlin + Compose Desktop
- Gradle Kotlin DSL
- JNA for native Windows DPAPI
- AES/GCM/NoPadding
- SQLite JDBC metadata database (media/albums schema and import records)

## Build
Use JDK 17 and Gradle 8.14. CI runs tests and packages Windows MSI/EXE.

## Roadmap
1. Desktop shell and CI build
2. Security core and DPAPI key protection (foundation implemented)
3. PIN/password and vault unlock lifecycle
4. Encrypted media import and database
5. Basic metadata Gallery and album management UI (implemented; preview/viewer/playback still pending)
6. Android-compatible portable backup/restore
7. Security tests, Windows 10 validation, and installer release
