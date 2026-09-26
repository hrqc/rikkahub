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
- [x] M2 implementation and host validation: bounded accessibility observation/actions, session-bound chat tools, control panel and foreground coordination. Device evidence is recorded separately.
- [x] First M3 controls implemented in the M2 internal build: bounded Root inputs, API 34+ target-window screenshots, result observation, STOP and budgets. This is not full M3 or V1 acceptance.
- [x] M2.2: natural chat proposals and local target confirmation, automatic opening and execution for explicit current requests, chat pause/resume/STOP, and explicit-token isolation from ordinary chat/Web requests. 150 targeted JVM tests pass; OnePlus real-model chat tests cover ordinary answers, automatic click, exact Chinese multi-step input, ambiguous confirmation and chat STOP. See `M2_CHAT_CONTROL.md` and `M2_MODEL_TEST_REPORT.md`.
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

Previous built source checkpoint: `mobile-agent-v1-m1.1-cleanup` (`8c2863489a77e290970ed34225376b6e20e69bb3`). The user requested removal of upstream updates, repository promotion and community/donation entries. Current-project source and license attribution remain available. See `M1_1_BUILD_REPORT.md` for evidence and limits.

The M2 internal build is `2.5.4-mobile-agent-v1-m2` / versionCode 191, source `3ae621ca70e44fa4c7e956f0ad4ebe1a4d79a340`. Its targeted JVM regression set has 68 passing tests (zero failures/errors/skips); both main and instrumentation APKs compile. The connected OnePlus passed all three synthetic device tests: accessibility actions/STOP, Root actions/notification STOP, and a local target-window screenshot. This is not evidence of OPPO compatibility or a model-driven real-world task. The control panel is available from the chat attachment/more menu. A separate preparation mode creates a local authorized session without calling a model. Root enhancement and screenshots are separate opt-ins. Actual device results and source/artifact provenance are recorded in `M2_BUILD_REPORT.md`; `M2_TEST_PLAN.md` distinguishes host policy evidence from device acceptance.

M2.1 source checkpoint `365ebd39ead542e3be70260c68658b9c7cd9a170`, tag `mobile-agent-v1-m2.1-model-internal`, remains a rollback point for the standalone panel. Current M2.2 chat build is versionCode 193 / `2.5.4-mobile-agent-v1-m2.2-chat`; it preserves the advanced panel and does not alter V2 scope.

Phone Tools are bound to conversation, assistant, session and epoch; STOP and resume invalidate old calls. The app restricts a session to one selected target, 30 actions, 90 observations and five minutes. The model cannot start or resume authorization itself: a new current user request or explicit chat confirmation is required. Sensitive or incompletely inspected pages are handed to the user. This does not guarantee recognition of every sensitive page or every simultaneous human action.

Provider, request, tool and notification paths were updated to avoid logging phone contents and inputs. HTTP diagnostics now preserve metadata without request/response headers, bodies, URL paths or query strings. Authorized tool results still enter the local chat and, when the user starts a model task, its model context. No model API calls are part of the synthetic device smoke tests.

Acceptance devices supplied by the user:

- Non-root: OPPO Find X6 Pro.
- Root: OnePlus Ace 5 Pro.
- Connected OnePlus: PKR110, Android 16 / API 36, SukiSU Ultra v4.0.0 (manager 40114). Before manual manager authorization, the app could not find runnable `su`. After the user allowed this app, its explicit probe confirmed UID 0. Passive refresh did not rerun the probe; restarting the process reset Root to unverified, and explicit revalidation succeeded. Denial/pending-request cancellation/timeout coverage remains separate from these successful device checks.
- OPPO Android version and real-device tests remain pending.

See `V1_BASELINE_REPORT.md` for the source baseline.
