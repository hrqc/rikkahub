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
- [x] M1.1: disable upstream update checks/downloads and remove upstream promotional entry points; 22 targeted tests passed and installed on the connected OnePlus.
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

The project-local Gradle 9.6.0, SDK 37.0 / 37.2, CMake 3.22.1 and locked web dependencies are provisioned. The original web component and Android APK build successfully. M1.1 passes 22 targeted tests, including real subprocess pipe timeout/cancellation and disabled upstream update requests. APK signature, version and default disabled Firebase collection flags were verified. OnePlus installation, startup, cleaned settings/about pages and the initial capability panel have passed a device smoke check. The complete V1 is not ready for acceptance.

Latest built source checkpoint: `mobile-agent-v1-m1.1-cleanup` (`8c2863489a77e290970ed34225376b6e20e69bb3`). The user requested removal of upstream updates, repository promotion and community/donation entries. Current-project source and license attribution remain available. See `M1_1_BUILD_REPORT.md` for evidence and limits.

Acceptance devices supplied by the user:

- Non-root: OPPO Find X6 Pro.
- Root: OnePlus Ace 5 Pro.
- Connected OnePlus: PKR110, Android 16 / API 36, SukiSU Ultra v4.0.0 (manager 40114). Before manual manager authorization, the app could not find runnable `su`. After the user allowed this app, its explicit probe confirmed UID 0. Passive refresh did not rerun the probe; restarting the process reset Root to unverified, and explicit revalidation succeeded. Denial/pending-request cancellation/timeout coverage remains separate from these successful device checks.
- OPPO Android version and real-device tests remain pending.

See `V1_BASELINE_REPORT.md` for the source baseline.
