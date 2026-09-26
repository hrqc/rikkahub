# M1 device capabilities acceptance

This milestone tests capability reporting and an explicit, read-only Root probe. It does not yet automate other applications. Passing M1 does not constitute V1 acceptance.

## Build defaults

The fork does not require a private Firebase configuration. Analytics and crash uploads are disabled by default. To deliberately enable them for a developer-owned Firebase project, supply the ignored `app/google-services.json` and build with `-PenableFirebase=true`. Do not commit that file or any signing keys.

## Both devices

1. Open Settings → Extensions → Mobile Agent / Device capabilities.
2. Confirm manufacturer, model, Android version and API level match system settings.
3. Opening the page, pressing Refresh, returning from settings and restarting the app must not automatically request Root or accessibility permissions.
4. Confirm the page distinguishes Workspace PRoot from Android device Root. Phone controls not included in M1 must be marked not implemented.
5. Existing chat, provider settings and the Workspace list must still open normally.

## OPPO Find X6 Pro (non-root)

Press the explicit Root button. If `su` is absent, show `ROOT_UNAVAILABLE` without crashing. Ordinary execution errors must remain `UNKNOWN`, not be mislabeled as refusal. The rest of the app remains usable.

## OnePlus Ace 5 Pro (root)

First inspect the actual Root manager. SukiSU Ultra can hide the `su` entry from an app that is not in its allowlist, so no permission dialog and `ROOT_UNAVAILABLE` are valid before manual authorization. The user must enable superuser access for `me.rerere.rikkahub.debug` in the manager, then retry the app's explicit probe. Manager renaming and spoofed boot properties must not be used to infer that Root is absent. See the [SukiSU su compatibility implementation](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/main/kernel/feature/sucompat.c).

- Grant the Root manager request: only a successful command returning UID 0 may show `ROOT_GRANTED`.
- Revoke/deny in the Root manager and retry: an explicit refusal must show `ROOT_DENIED`; if the manager hides `su`, `ROOT_UNAVAILABLE` is expected instead. Ambiguous errors remain `UNKNOWN`.
- Leave the request pending: timeout must return `UNKNOWN` and end the probe.
- Cancel and navigate away while the request is pending: no late success or stuck progress indicator; returning allows a fresh request.
- Restart the app: the page starts unverified and does not trust a persisted Root grant. Revalidation is explicit.

Record actual Android version, Root manager/version, result and any screenshot or logs. The Root manager owns its permission dialog; cancelling the app's probe does not revoke previously granted Root permission.

An allowlist-only manager may not expose a pending permission dialog. Do not claim physical timeout/cancellation coverage unless a request actually remained pending; retain the host subprocess tests as separate evidence.

## Automated checks

Run `:app:testDebugUnitTest --tests "me.rerere.rikkahub.data.mobileagent.*"` and `:app:assembleDebug`. Tests cover exact UID validation, refusal classification, passive refresh, concurrent requests, cancellation, timeout, output limits and process cleanup. Host JVM checks cannot replace the two device runs.
