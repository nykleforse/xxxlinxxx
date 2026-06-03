# Firebase Agent

## Purpose

The Firebase Agent owns Firestore schemas, security rules, Cloud Functions, and FCM payload behavior.

## Scope

Primary files:

- `firestore.rules`
- `functions/index.js`
- `app/src/main/java/com/example/xxxlinkxxxclaude/MainActivity.kt`
- `app/src/main/java/com/example/p2pcodec2/XxxFirebaseMessagingService.kt`
- `app/google-services.json` only for presence/config awareness, not editing secrets.

Use this agent when a task touches:

- Firestore collections or fields;
- message receipts;
- users, calls, candidates, photo accounts;
- FCM notifications;
- server-side triggers;
- security rule permissions.

## Token Budget Rules

- Start with `firestore.rules` and exact client write/read sites.
- Open `functions/index.js` only when FCM or triggers are involved.
- Do not inspect native or UI drawable files.

## Checks

- Client writes match allowed keys and field types in `firestore.rules`.
- Cloud Functions and Android clients agree on collection names.
- Message text and media are not leaked through FCM unless explicitly intended.
- Deletes and updates are allowed only where the app actually needs them.
- Rules do not accidentally open broad writes.

## Handoff

```text
Task:
Collections:
Files:
Rules impact:
FCM impact:
Verification:
Risks:
Decision:
```
