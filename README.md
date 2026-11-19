# Photo-Locker Windows

**Windows 10 x64 desktop edition** of Photo-Locker: an offline-first private photo and video vault.

## Implemented
- Streaming AES-256-GCM file encryption core.
- Password-protected vault key: PBKDF2-HMAC-SHA256 (310,000 iterations), random salt, AES-GCM wrapping.
- Password setup, unlock, lock, and migration from legacy Windows DPAPI key blob.
- Encrypted media import to UUID destinations; original source files remain unchanged.
- SQLite metadata database for media and albums (metadata is currently plaintext and is a security limitation).
- Home, Gallery metadata list, Albums, Security, Backup placeholder, Settings placeholder.
- Windows MSI/EXE packaging workflow and CI tests.

## Vault data location and uninstall behavior
Vault data is stored separately from the installation directory:
`%LOCALAPPDATA%\\Photo-Locker-Windows\\vault\\`
This includes `vault.key.dpapi`, `vault.db`, and `media\\`. Uninstalling the application is intended to leave vault data in place. Do not manually delete this folder, and make a backup before reinstalling Windows or cleaning AppData. This behavior must be verified against the final installer/uninstaller on Windows.

## Security status
**Alpha — not production ready.** Metadata SQLite is not encrypted. Secure thumbnails, photo preview, video playback, backup/restore, inactivity auto-lock, attempt throttling, crash-safe cleanup, and full Windows runtime/security validation remain unfinished. Lock on app exit is implemented, but JVM memory zeroization is best-effort only.

## Technology
- Kotlin + Compose Desktop
- Gradle Kotlin DSL, JDK 17
- JNA for Windows DPAPI
- AES/GCM/NoPadding
- SQLite JDBC

## Build
Use JDK 17 and Gradle 8.14. CI runs unit tests and packages Windows MSI/EXE.

## Roadmap
1. Secure Gallery with encrypted thumbnail lifecycle.
2. Photo viewer with controlled temporary plaintext lifecycle.
3. Video playback with secure temporary file cleanup.
4. Album membership and media management.
5. Encrypt metadata database.
6. Auto-lock after inactivity and password attempt throttling.
7. Backup/restore with integrity verification and recovery.
8. Windows runtime, installer/uninstaller, upgrade/uninstall data-preservation tests.
9. Final security audit before public release.
