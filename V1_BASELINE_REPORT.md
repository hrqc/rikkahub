# Mobile Agent V1 baseline

## Source and recovery

- Baseline commit: `8b696c0cfc301754689c0bb04e965fe60af6277c`
- Origin: `https://github.com/hrqc/rikkahub.git`
- Upstream (read only): `https://github.com/rikkahub/rikkahub.git`
- Development branch: `feature/mobile-agent-v1`
- Remote backup branch: `backup/pre-mobile-agent-v1`
- Remote annotated tag: `mobile-agent-v1-baseline`
- Worktree was clean before development.
- Submodule `material3/material-color-utilities`: `6fd88eb3e95ba1d457842e2a2bf847d06b3a018a`

The backup branch, baseline tag and development branch were pushed successfully before editing source files. Recover by creating a new branch from the baseline tag; do not reset or overwrite existing work.

## Existing application

- Kotlin / Jetpack Compose Android client, version 2.5.4 (189).
- Model providers and tool abstraction: `ai`.
- Chat/tool orchestration: `ChatService`, `ChatToolFactory`, `GenerationLoop`.
- Workspace file editing and PRoot command execution already exist. PRoot is not Android device root.
- Search, documents, speech, MCP, skills and conversation storage remain in place.
- App minimum SDK 26, target SDK 37, compile SDK 37 minor API 2.
- Gradle 9.6.0, AGP 9.4.0, Kotlin 2.4.10, Gradle daemon JetBrains JDK 21.
- Native workspace component needs CMake 3.22.1 and an Android NDK.
- Android build also builds `web-ui` using pnpm.
- Upstream Google Services/Firebase configuration is not included in the public repository.

## Validation status

Baseline source and Git recovery references verified. In the unchanged baseline worktree, `:app:processDebugGoogleServices` was actually executed and failed because the public repository does not contain `google-services.json`. This is a baseline configuration failure, not a Mobile Agent regression. No successful original APK build or device test is claimed.

Local build preparation completed with Gradle 9.6.0 (official archive SHA-256 `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`), Android Studio JBR 21.0.10, SDK platforms 37.0 / 37.2 and CMake 3.22.1. All added tools and dependency caches are project-local. Existing accepted SDK licenses were reused; no new terms were automatically accepted.

For this Windows machine, an external local Gradle init script selects already-installed NDK 29.0.14206865 and Build Tools 37.0.0 instead of AGP's defaults (NDK 28.2.13676358 and Build Tools 36.0.0). Automatic SDK downloads are disabled. This toolchain deviation must be retained with the APK validation record; it is not an exact reproduction of the default upstream toolchain.

The fork defaults to disabled Firebase analytics/crash collection and does not require the private configuration file. Explicit opt-in is documented in the M1 test plan. The M1 Android APK build and 17 targeted Gradle unit tests passed; see `docs/mobile-agent/M1_BUILD_REPORT.md`. Real-device acceptance remains pending.

V1 work and validation are tracked in `docs/mobile-agent/IMPLEMENTATION_STATUS.md`.
