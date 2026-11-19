# Photo-Locker Windows

**Windows 10 x64 desktop edition** of Photo-Locker: an offline-first private photo and video vault.

## Planned security model
- Media encrypted at rest with AES-256-GCM.
- Vault master key protected with Windows DPAPI (Windows user scope).
- PIN/password authentication and automatic lock.
- Private thumbnails/cache with cleanup on lock/exit.
- Portable `.plb` backup compatibility with Android Photo-Locker (compatibility must be verified against Android implementation and test fixtures before release).
- Local SQLite metadata; no account or cloud required.

## Technology
- Kotlin + Compose Desktop
- Gradle Kotlin DSL
- SQLite (planned)
- Windows DPAPI integration (planned)

## Status
Project foundation is being established. **Not production-ready**; security, encryption, backup interoperability, crash handling, and Windows 10 testing remain required.

## Build
Use JDK 17+ and run:

```bash
./gradlew run
./gradlew packageDistributionForCurrentOS
```

On Windows, package the installer from a Windows runner. See GitHub Actions.

## Roadmap
1. Desktop shell and CI build
2. Security core and DPAPI key protection
3. Encrypted media import and database
4. Gallery, albums, viewer, and video playback
5. Android-compatible portable backup/restore
6. Security tests and Windows installer
