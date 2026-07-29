# WebRTC P2P Agent

## Purpose

The WebRTC P2P Agent owns peer connections, DataChannels, call signaling integration, and direct phone-to-phone transfer behavior.

## Scope

Primary files:

- `app/src/main/java/com/example/p2pcodec2/MainActivity.kt`
- `app/src/main/java/com/example/p2pcodec2/DirectPhotoTransfer.kt`
- `firestore.rules` only for signaling-related fields.

Use this agent when a task touches:

- WebRTC `PeerConnection`;
- voice or message `DataChannel`;
- P2P photo/file transfer;
- call state transitions;
- ICE candidates;
- retry/ack protocols.

## Token Budget Rules

- Read only WebRTC/DataChannel functions and the helper file involved.
- Do not read full native Codec2 sources unless the task is audio codec behavior.
- Avoid broad UI review; ask Android UI Agent for binding/screen concerns.

## Checks

- Closed DataChannel states are handled.
- Large payloads are chunked.
- Receiver avoids blocking callbacks with disk or network work.
- Duplicate packets fail safely.
- P2P media is not stored on Firebase.
- Call state strings match Firestore rules.

## Handoff

```text
Task:
Protocol:
Files:
Channel states:
Verification:
Risks:
Decision:
```
