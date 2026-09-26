# Mobile Agent V1 implementation status

V1 only. V2 remains out of scope until the user explicitly accepts all V1 core tests on rooted and non-rooted devices and authorizes V2.

## Rules

- Preserve the existing chat, providers, workspace, MCP and speech capabilities.
- One APK for rooted and non-rooted Android devices; detect capabilities instead of guessing by device model.
- Prefer Android APIs and accessibility; use vision only when necessary.
- Verify actions, bound retries and budgets, and make stopping available.
- Never perform final payment authentication automatically.
- Source, uncommitted changes, user downloads and permanently retained files are protected from automatic cleanup.
- Commit and push only explicitly reviewed files. No credentials, signing keys, local configuration, build caches or APK binaries in Git.
- Create stable milestone tags only after their build and basic tests pass. Device acceptance is recorded separately.
- Report branch, commit, push, tag, rollback point and APK at handoff.

## Progress

- [x] Read V1 requirements and upstream architecture.
- [x] Create user-owned Fork and clone it locally.
- [x] Push baseline backup branch and annotated tag.
- [x] Create and track `feature/mobile-agent-v1`.
- [x] Initialize the pinned color utilities submodule.
- [x] Provision project build dependencies and record the unmodified baseline configuration failure (missing Firebase configuration).
- [x] M1 implementation and host validation: capability detection, explicit root request and device capability panel. Real-device acceptance remains pending.
- [ ] M2: accessibility observation/actions, compressed UI tree and foreground coordination.
- [ ] M3: controlled root actions, screenshots, action verification, stop and loop/budget protection.
- [ ] Shopping comparison, discount calculation and payment guard.
- [ ] Web research, downloads, source metadata and hash validation.
- [ ] Short-video understanding and research integration.
- [ ] Coding environment checks and build/install/test workflow.
- [ ] Storage budgets, retention, low-space guard and diagnostic export.
- [ ] Root and non-root real-device V1 acceptance.

## Current checkpoint

Baseline: `mobile-agent-v1-baseline` (`8b696c0cfc301754689c0bb04e965fe60af6277c`).

Built M1 source checkpoint: `mobile-agent-v1-m1-capabilities` (`7c5f2491d6329380a7c240ea2161341a7e6188b3`), pushed to origin. This is not the user-accepted V1 stable tag.

M1 capability detection and the settings page are implemented, compiled and covered by 17 passing targeted Gradle unit tests. Root is requested only by an explicit button, uses a fixed read-only UID command, and is never inferred from the device model or the Workspace PRoot environment. Unimplemented phone controls are shown as unavailable to this milestone.

The fork can build without private Firebase credentials; analytics and crash uploads are disabled by default. An upstream-style telemetry build requires explicit `-PenableFirebase=true` and the developer's own ignored `app/google-services.json`.

The project-local Gradle 9.6.0, SDK 37.0 / 37.2, CMake 3.22.1 and locked web dependencies are provisioned. The original web component and M1 Android APK build successfully. After an initial cached-compiler check, all 17 targeted tests also passed under the repository's Kotlin 2.4.10 / Core 1.19.0 Gradle build, including real subprocess pipe timeout/cancellation. APK signature, version and default disabled Firebase collection flags were verified. No real-device test has passed yet; the complete V1 is not ready for acceptance.

Acceptance devices supplied by the user:

- Non-root: OPPO Find X6 Pro.
- Root: OnePlus Ace 5 Pro.
- Android versions and installed Root manager remain to be read from the devices during testing.

See `V1_BASELINE_REPORT.md` for the source baseline.
