# Google Play Store — release checklist

Working doc for promoting X-link from GitHub-only beta to Play Store publication. Tick boxes as we go.

## Phase 0 — account setup

- [ ] Buy Google Play Developer account ($25, one-time)
- [ ] Verify identity (passport/ID) — takes 1–3 days
- [ ] Decide account type: personal vs organization
  - [ ] If organization: acquire D-U-N-S number, prove business registration
- [ ] Create Play Console project for `X-link`
- [ ] Add internal team members (if any)

## Phase 1 — package identity

- [ ] Pick a real package id (currently `com.example.xxxlinkxxx`, Google rejects `com.example.*`)
  - [ ] Decide owner domain (e.g. `com.<yourdomain>.xlink`)
  - [ ] Rename `applicationId` in `app/build.gradle.kts`
  - [ ] Rename `namespace` accordingly
  - [ ] Rename `package` in AndroidManifest (or rely on namespace if all manifest entries are relative)
  - [ ] Update Kotlin package paths under `app/src/main/java/.../`
  - [ ] Update `FileProvider` authority in manifest + code (`$packageName.fileprovider`)
  - [ ] Update `google-services.json` in Firebase (add new package id alongside old, or migrate)
  - [ ] Re-run `firebase deploy` if functions reference the package id

## Phase 2 — signing

- [ ] Generate release keystore (`keytool -genkeypair -v -keystore xlink-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias xlink`)
- [ ] Store keystore in a safe location off the repo (1Password / encrypted drive)
- [ ] Add `xlink-release.jks` and its passwords to `local.properties` (do NOT commit)
- [ ] Add a `release` signingConfig in `app/build.gradle.kts` that reads from `local.properties`
- [ ] Enroll in **Play App Signing**: upload key stays local, Google manages signing key
- [ ] Verify `./gradlew bundleRelease` produces a signed AAB

## Phase 3 — build configuration

- [ ] Bump `targetSdk` 34 → 35 (Android 15 — required for new apps in 2025)
- [ ] Bump `compileSdk` to match
- [ ] Re-enable R8 (`isMinifyEnabled = true`)
  - [ ] Add WebRTC keep-rules to `proguard-rules.pro` (this is what broke v1.14.22 originally — fix now)
  - [ ] Verify a release build does not crash in `org.webrtc.NativeLibrary$DefaultLoader.load`
- [ ] `isShrinkResources = true` after R8 works
- [ ] Confirm `arm64-v8a` + `armeabi-v7a` are the only ABIs (no x86_64 needed for phones)
- [ ] Set `bundle.language.enableSplit = true` etc. for AAB splits (defaults are fine)
- [ ] Verify `./gradlew bundleRelease` succeeds and outputs a single `.aab`

## Phase 4 — code hygiene before publication

- [ ] Hide BetaLogger behind `BuildConfig.DEBUG` (right now it triggers on any `versionName` containing `beta`)
- [ ] Strip debug `Log.d(TAG, …)` calls from hot paths (or keep them — R8 strips most automatically)
- [ ] **TURN credentials**: replace `BuildConfig.TURN_USERNAME` / `_PASSWORD` with a callable that issues short-lived creds. Hardcoded creds in the APK are decompilable.
- [ ] **GitHub PAT**: confirm no token strings leaked into APK resources, code, or assets (only in our build scripts on disk; should be fine)
- [ ] **Hardcoded Firebase project id** — public anyway, but verify rules cover anonymous abuse paths
- [ ] Audit `firestore.rules` once more: `transfers/{id}` should also cap collection size per sender to prevent DOS
- [ ] Crash reporting? Decide: Firebase Crashlytics or skip
- [ ] Localization: at minimum English + Russian (current UI mixes both)

## Phase 5 — Play Console store listing

- [ ] App name (≤30 chars): `X-link` or with subtitle
- [ ] Short description (≤80 chars)
- [ ] Full description (≤4000 chars)
- [ ] Icon: 512×512 PNG (we have 1024×1024 source — downscale)
- [ ] Feature graphic: 1024×500 PNG
- [ ] Phone screenshots: 2–8, 16:9 or 9:16, 320–3840 px on each side
- [ ] Tablet screenshots (optional but recommended)
- [ ] Promo video (optional, YouTube link)
- [ ] Application category: Communication (most likely)
- [ ] Contact email (required, public)
- [ ] Support URL / website
- [ ] Privacy policy URL (REQUIRED — see Phase 6)

## Phase 6 — legal + policy

- [ ] Write privacy policy covering:
  - [ ] What's collected: `localId` (anonymous hash), FCM token, photo hash (server-side derivation)
  - [ ] What's NOT collected: photo, password, message plaintext (all E2E)
  - [ ] Firebase as processor (Auth + Firestore + Functions + FCM + GCS)
  - [ ] Retention: messages stored encrypted until delivered; transfers expire
  - [ ] User rights (delete account, export data)
- [ ] Host policy at a stable URL (GitHub Pages: `https://nykleforse.github.io/xlink-android/privacy/`)
- [ ] Fill **Data safety form** in Play Console:
  - [ ] Collected data: anonymous user id, FCM token, IP (Firebase)
  - [ ] No data sold
  - [ ] Encryption in transit ✓, encryption at rest ✓ (E2E)
  - [ ] User can request deletion (mechanism: how?)
- [ ] Content rating (IARC questionnaire) — likely PEGI 3 / ESRB Everyone since no objectionable content
- [ ] Permissions justification:
  - [ ] `CAMERA` — QR scanning + photo (not currently used for camera capture, only picker — may need to remove this perm)
  - [ ] `RECORD_AUDIO` — voice calls
  - [ ] `READ_EXTERNAL_STORAGE` (via media picker — implicit)
  - [ ] `POST_NOTIFICATIONS` — call + message alerts
  - [ ] `USE_FULL_SCREEN_INTENT` — incoming call (requires Play Console exception form on Android 14+)
  - [ ] `FOREGROUND_SERVICE_MICROPHONE` — backgrounded calls
- [ ] Decide on government-customer / financial / health declarations (none apply)
- [ ] Confirm app does not require Google account or violates spam policies

## Phase 7 — releases + testing

- [ ] Upload first AAB to **Internal testing** track (instant, up to 100 testers)
  - [ ] Verify install, login, send message, place call, send photo, backup, restore
- [ ] Promote to **Closed testing** (alpha)
  - [ ] Recruit ≥20 active testers
  - [ ] Hold ≥14 calendar days before promoting (Google's 2023 personal-account requirement)
- [ ] Optionally **Open testing** (beta) to widen the funnel
- [ ] Promote to **Production**

## Phase 8 — post-launch

- [ ] Monitor Play Console **Pre-launch report** (auto-runs on real devices)
- [ ] Monitor Android Vitals (ANRs, crashes, slow renders, wakelocks)
- [ ] Set up in-app review prompt (`com.google.android.play:review`)
- [ ] Auto-updater inside the app currently pulls from GitHub Releases — decide:
  - [ ] Keep both? GitHub for betas, Play for stable.
  - [ ] Disable in-app update path on Play builds (Play forbids alt distribution within a Play app)
- [ ] Plan a deprecation timeline for the GitHub-distributed APKs

## Phase 9 — money (if applicable)

- [ ] Free app — nothing to do
- [ ] Paid app — need Merchant account, tax info
- [ ] IAP — wire `com.android.billingclient:billing` and define products

---

**Current blockers (need decisions from you, not work):**
- [ ] Real package id (need a domain)
- [ ] Privacy-policy hosting (GitHub Pages OK?)
- [ ] TURN credentials backend (cheapest: another Cloud Function that issues short-lived creds against a static secret)
- [ ] Crash reporting yes/no
- [ ] Localization scope (en/ru only?)
