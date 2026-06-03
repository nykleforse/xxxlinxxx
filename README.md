# X-link

Encrypted P2P voice + messaging Android app. Photo+password-derived account identity, WebRTC voice/text DataChannels, Firebase signaling for offline message queue + call setup.

## Quick start

1. Clone, open in Android Studio (Hedgehog or newer, AGP 8.3+).
2. Drop your own `app/google-services.json` (Firebase project config).
3. Add to `local.properties`:
   ```
   turn.username=<your TURN user>
   turn.password=<your TURN secret>
   ```
4. Build → install on Android 6.0+ (`minSdk = 23`).

## Repo layout

```
.
├── app/
│   ├── src/main/java/com/example/
│   │   ├── xxxlinkxxxclaude/        MainActivity (UI + Firestore + WebRTC orchestration)
│   │   └── p2pcodec2/               BackupWorker, BetaLogger, CallForegroundService,
│   │                                Codec2Bridge, XxxFirebaseMessagingService
│   ├── src/main/cpp/                Codec2 JNI bridge (CMake)
│   └── src/main/res/                Layouts, drawables, values
├── functions/                       Firebase Cloud Functions (FCM + bindLocalId)
├── firestore.rules                  Firestore security rules
├── firebase.json                    Firebase deploy config
├── build.gradle.kts                 Project-level Gradle
└── README.md
```

## Architecture

- **Auth.** v2 derivation: `PBKDF2(password, salt=photoBytes, 600k iterations) → master`.
  `localId = first 4 bytes of SHA256("localid-v2:" || master)`. EC keypair from
  `SHA256("crypto-v2:" || master)`. Neither photo nor password leaves the device.
  Firebase Auth (anonymous) issues a `uid` per install; the `bindLocalId` Cloud
  Function maps `uid ↔ localId` after ECDSA signature verification, up to 3 devices
  per account with LRU eviction.

- **Messages.** ECIES (ephemeral EC + AES-256-GCM). Live path: WebRTC DataChannel.
  Offline path: Firestore `/messages`. Delivery + read receipts in `/receipts`.

- **Calls.** WebRTC PeerConnection with Firestore signaling (`/calls/{id}` +
  `/calls/{id}/candidates`). Voice via Opus (COMFY mode) or Codec2 (BASE / Xtream).
  Foreground service + partial wake lock keep the call alive when backgrounded.

- **Photos.** Separate WebRTC PeerConnection per transfer (`/transfers/{tid}`).
  AES-GCM encrypted whole, chunked over a DataChannel. Watchdog timeouts,
  busy-reject signalling, whitelist consent for unknown senders.

- **Updates.** GitHub Releases as the distribution channel. App polls `/releases`
  and offers in-app install of the newest semver prerelease.

## Firestore rules

`firestore.rules` carries a v2 schema with a grace-period fallback: clients that
have a `localId` custom claim (set by `bindLocalId`) are subject to strict
participant checks; clients without the claim still pass the v1 format-only
checks so v1.14.x → v1.15 migration doesn't break older installs.

Deploy:
```bash
firebase deploy --only firestore:rules
firebase deploy --only functions:bindLocalId
```

## Branches & releases

- `main`: active development. Every push lands a `v1.x.y-beta` GitHub prerelease
  with the APK attached.
- `develop`: mirror of `main`. Kept in sync via force-push so either name shows
  the latest code.
- Older `feature/*` branches were merged or abandoned long ago; they remain on the
  remote for history only.

Release notes live in the corresponding [GitHub Release](https://github.com/nykleforse/xxxlinxxx/releases),
not in checked-in `.txt` files.

## Beta diagnostics

Beta builds capture `logcat` to `filesDir/beta_logs/log.txt` (2 MB rotating, 1
prior copy). Drawer → **Share beta log** exports via the Android share sheet for
bug reports. Production builds skip the capture entirely.

## License

Proprietary. Not for redistribution.
