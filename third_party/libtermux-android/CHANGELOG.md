# Changelog

All notable changes to LibTermux Android will be documented here.

## [1.0.0] - 2026-04-01

### Added
- `LibTermux` singleton entry-point with Kotlin DSL config (`termuxConfig {}`)
- `TermuxConfig` with Java Builder & Kotlin DSL support
- `VirtualFileSystem` — isolated Linux directory structure in app private storage
- `BootstrapInstaller` — auto-detects latest Termux bootstrap from GitHub API, downloads & installs with progress Flow
- `CommandExecutor` — run shell commands with coroutine-based API
- `CommandExecutor.executeStreaming()` — real-time output via Flow<OutputLine>
- `CommandExecutor.executePython()` / `executeNode()` — language-specific runners
- `SessionManager` — manage multiple named terminal sessions
- `TermuxBridge` — high-level developer API (run, python, node, bash, ruby, perl, php, etc.)
- `PackageManager` — pkg install/uninstall/search/list wrapper + pip + npm + gem
- `TermuxBackgroundService` — foreground service for background execution
- `TerminalView` — drop-in terminal UI widget with PTY support
- JNI layer: PTY support, process forking, symlink helpers
- Full unit test suite
- GitHub Actions CI/CD (lint + test + build + release + publish)
- Sample application demonstrating all features

### Fixed
- Bootstrap download 404: now auto-resolves latest release tag from GitHub API
- Symlink processing: now correctly uses extracted SYMLINKS.txt instead of raw ZIP
- Download/extraction progress now properly emitted to UI
- Zip-slip vulnerability fixed in extractZip
- Added User-Agent header to all HTTP requests
