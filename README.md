# X-link

P2P encrypted voice + messaging for Android. Calls travel over WebRTC; messages travel over Firestore with E2E ECIES so the server only sees ciphertext. Identity is derived from a private photo + password — no email, no phone number, no username.

**Current build:** `v1.15.9-beta` · minSdk 23 · targetSdk 34

---

## What it does

- **Voice calls.** WebRTC PeerConnection with TURN relay fallback. Audio is compressed with Codec2 (3200 / 2400 / 1400 bps profiles, JNI bridge) over an SCTP DataChannel.
- **1:1 messages.** ECIES envelope: ephemeral EC P-256 → ECDH → AES-256-GCM. Server stores opaque blobs only.
- **Group chats.** Per-message fan-out; admin actions; auto-discovery via shared key material.
- **Reply-to.** Long-press a message, reply with a quoted preview.
- **Photo transfer.** Out-of-band peer-to-peer with optional account-password binding (hash never leaves the device).
- **Search.** Inline pill-style search across chats and contacts.
- **Auto-update.** App polls GitHub Releases on launch, prompts to install the highest-semver APK.
- **Beta logger.** Local-only logcat capture at `filesDir/beta_logs/log.txt` (2 MB rotating) for diagnostics.

---

## Security model

| Layer | Mechanism |
|------|-----------|
| Identity | EC P-256 keypair derived from `PBKDF2-HMAC-SHA256(photo ‖ password, salt=localId, 600 000 iter)` |
| Server bind | Firebase Auth anonymous UID → callable `bindLocalId` proves ownership with ECDSA over `localId‖uid‖ts` (60 s window, replay-protected) |
| Device cap | LRU-evicted at 3 devices per `localId`; evicted UID loses its custom claim |
| Messages | ECIES (ephemeral EC + AES-256-GCM); ciphertext-only on Firestore |
| Keys at rest | `EncryptedSharedPreferences` wrapped by Android Keystore master key |
| PIN / biometric | Constant-time compare; BiometricPrompt gate on cold start |
| Backup | KDF v3 — same PBKDF2 profile as identity, never reused |
| Push | FCM data-only messages; payload contains no sender content |

**Known gaps** are tracked in `memory/security_findings.md` — a 13-item audit ranked CRITICAL → MEDIUM.

---

## Stack

- **Android:** Kotlin, ViewBinding, ConstraintLayout, Material 3 components, custom drawer-style dark theme
- **Voice:** `io.github.webrtc-sdk:android` + Codec2 via CMake/NDK (`arm64-v8a`, `armeabi-v7a`)
- **Crypto:** JCE (`KeyAgreement`, `Cipher/GCM`, `MessageDigest`) + `androidx.security:security-crypto`
- **Backend:** Firebase Auth, Firestore, Cloud Functions (Node 20), FCM
- **QR:** ZXing core + `journeyapps:zxing-android-embedded`
- **Build:** Gradle KTS, AGP 8.x, JDK 17, Kotlin 1.9.23

---

## Layout

```
app/                                 Android module
├── src/main/java/
│   ├── com/example/p2pcodec2/         FCM, foreground service, Codec2 bridge
│   └── com/example/xxxlinkxxxclaude/  MainActivity + UI flows
├── src/main/cpp/                      codec2 native + JNI shim
└── src/main/res/                      layouts, drawables, raw sounds
functions/                          Cloud Functions (bindLocalId, FCM dispatch)
firestore.rules                     Per-collection access with grace-period auth fallback
memory/                             Project notes consumed by Claude agents
.agents/                            Multi-agent coordination framework + registry
```

---

## Build

```bash
# Debug install
./gradlew assembleDebug installDebug

# Release APK (signed with the project's debug key)
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Drop `app/google-services.json` from your Firebase project. Add TURN credentials to `local.properties`:

```properties
turn.username=…
turn.password=…
```

These are baked into `BuildConfig` — replace with backend-issued short-lived creds before any production release.

---

## Functions deploy

```bash
cd functions
npm install
firebase deploy --only functions
firebase deploy --only firestore:rules
```

---

## Versioning + releases

- `main` — stable line + beta cuts
- `develop` — kept in sync with `main`
- Tags: `vMAJOR.MINOR.PATCH[-beta]`
- Every tag has a corresponding GitHub Release with a single `xxxlink-<ver>.apk` asset; the in-app updater picks the highest semver

---

## Status

Pre-1.0 beta. Schema, wire format, and KDF parameters may still change between minor versions; expect re-binding to be required after major-version upgrades.
