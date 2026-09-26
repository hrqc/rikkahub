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

Baseline source and Git recovery references verified. Original application build is pending local dependency provisioning; no successful build or device test is claimed.

V1 work and validation are tracked in `docs/mobile-agent/IMPLEMENTATION_STATUS.md`.
