# Photo-Locker Windows

**Windows 10 x64 desktop edition** of Photo-Locker: an offline-first private photo and video vault.

## Implemented
- Streaming AES-256-GCM file encryption core.
- Password-protected vault key: PBKDF2-HMAC-SHA256 (310,000 iterations), random salt, AES-GCM wrapping.
- Password setup, unlock, lock, and migration from legacy Windows DPAPI key blob.
- Encrypted media import to UUID destinations; original source files remain unchanged.
- SQLite metadata database: filenames, MIME types, encrypted-relative paths, and album names are encrypted with AES-GCM. IDs, sizes, timestamps, relationships, and schema remain visible.
- Gallery with encrypted thumbnails and temporary decrypted photo viewer (cleanup is best-effort).
- Album create/open/rename/delete, membership assignment, and remove-from-album controls.\n- Five-minute inactivity auto-lock resets on click and keyboard events (Windows runtime validation pending).\n- Vault destination folder chooser at vault creation; custom folder/drive selection is persisted in AppData.
- Windows MSI/EXE packaging workflow and CI tests.

## Vault data location and uninstall behavior
Vault data defaults to `%LOCALAPPDATA%\\Photo-Locker-Windows\\vault\\`, or a user-selected folder/drive chosen when creating a vault. The custom location pointer is stored under AppData.
The vault folder includes `vault.key.dpapi`, `vault.db`, encrypted media, and encrypted thumbnails. Uninstalling the application is intended to leave vault data in place. Do not manually delete this folder, and make a backup before reinstalling Windows or cleaning AppData. This behavior must be verified against the final installer/uninstaller on Windows.

## Security status
**Alpha — not production ready.** Secure thumbnails are stored encrypted; photo viewing uses temporary plaintext files that are deleted best-effort and closed when the vault locks. Metadata fields and album names are encrypted in SQLite; IDs, file sizes, import timestamps, relations, and schema structure remain visible. Existing plaintext metadata is migrated on vault initialization. Backup/restore now uses a separate password to encrypt the entire archive, plus SHA-256 file manifest and path validation; Windows runtime validation remains pending. Video playback, attempt throttling, crash-safe cleanup, keyboard/pointer auto-lock runtime validation, custom-drive uninstall/upgrade preservation tests, and full Windows security validation remain unfinished. JVM memory zeroization and temporary-file cleanup cannot be guaranteed. Lock on app exit is implemented, but JVM memory zeroization is best-effort only.

## Technology
- Kotlin + Compose Desktop
- Gradle Kotlin DSL, JDK 17
- JNA for Windows DPAPI
- AES/GCM/NoPadding
- SQLite JDBC

## Build
Use JDK 17 and Gradle 8.14. CI runs unit tests and packages Windows MSI/EXE.

## Roadmap
1. Validate encrypted thumbnail, viewer, and temporary-file cleanup on Windows.
2. Video playback with secure temporary file lifecycle.
3. Protect remaining database structural metadata / evaluate full database encryption.
4. Validate backup/restore and recovery on Windows.
5. Password attempt throttling.
6. Validate auto-lock on pointer and keyboard activity.
7. Installer, upgrade, uninstall, and data-preservation tests for default and alternate-drive vaults.
8. Final security audit before public release.
