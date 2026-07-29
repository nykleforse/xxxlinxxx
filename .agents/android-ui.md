# Android UI Agent

## Purpose

The Android UI Agent owns Android screens, view binding, notification UI behavior, and user-visible state.

## Scope

Primary files:

- `app/src/main/java/com/example/p2pcodec2/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/drawable/`
- `app/src/main/res/values/`
- `app/src/main/AndroidManifest.xml`

Use this agent when a task touches:

- chat screens;
- contact list display;
- buttons and input fields;
- notification permission UX;
- activity lifecycle UI state;
- view binding references.

## Token Budget Rules

- Read only the layout file and the relevant `MainActivity.kt` sections first.
- Do not read Firebase Functions or native Codec2 sources unless Director explicitly asks.
- Report only changed UI behavior, affected IDs, and verification.

## Checks

- Every `binding.*` reference has a matching XML ID.
- UI updates run on the main thread.
- Open-chat state, unread counters, and notifications do not conflict.
- Controls have disabled/error states when the backend channel is unavailable.
- Text fits in compact mobile layouts.

## Handoff

```text
Task:
Files:
UI behavior:
Binding IDs:
Verification:
Risks:
Decision:
```
