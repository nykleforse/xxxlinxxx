# Project Agents

This project uses three coordination agents and six specialist agents for feature work:

- Liaison: talks directly with the user, keeps requests clear, and suggests practical ideas.
- Director: breaks a request into concrete tasks, assigns owners, gathers completed work, and checks that the parts fit together.
- Overseer: reviews each completed task against the original requirement, project constraints, and verification evidence before it is accepted.
- Android UI Agent: handles Android screens, view binding, and visible app state.
- Firebase Agent: handles Firestore rules, schemas, Cloud Functions, and FCM payloads.
- WebRTC P2P Agent: handles peer connections, DataChannels, calls, and direct transfer protocols.
- Security & Privacy Agent: checks encryption, key handling, local storage, and data exposure.
- Build & Regression Agent: runs or simulates focused verification and catches build regressions.
- Versioning Agent: maintains English and Russian version history files.

Use these agent definitions for all non-trivial changes in this repository:

- `.agents/mandatory-flow.md`
- `.agents/user-facing-rules.md`
- `.agents/liaison.md`
- `.agents/director.md`
- `.agents/overseer.md`
- `.agents/android-ui.md`
- `.agents/firebase.md`
- `.agents/webrtc-p2p.md`
- `.agents/security-privacy.md`
- `.agents/build-regression.md`
- `.agents/versioning.md`
- `.agents/workflow.md`

## Mandatory Startup Rule

For every non-trivial user request, start with the Liaison agent.

Required flow:

1. Liaison receives and clarifies the request.
2. Liaison sends the clarified request to Director.
3. Director breaks the work into tasks and assigns owners.
4. Completed work goes to Overseer.
5. Overseer accepts it or sends it back for rework.
6. Accepted work returns to Director for integration.
7. Liaison reports the result to the user.

Do not skip Liaison or Director unless the request is a tiny direct question or typo-only edit. Full details are in `.agents/mandatory-flow.md`.

## Project Context

This is an Android messenger with:

- Kotlin Android UI in `app/src/main/java/com/example/p2pcodec2/MainActivity.kt`.
- Firebase Firestore and FCM integration.
- WebRTC peer connections and DataChannels.
- A native Codec2 bridge under `app/src/main/cpp`.
- Firebase Functions under `functions/`.
- Firestore rules in `firestore.rules`.

## Shared Rules

- Director must call only the specialist agents needed by the touched subsystem.
- Prefer targeted file reads and `rg` checks over broad project reads.
- Do not burden the user with internal Firebase/Gradle/SDK/deployment details unless explicitly asked; follow `.agents/user-facing-rules.md`.
- Never create, rebuild, sign, or export an APK unless the user explicitly asks for an APK in the current request.
- When the user explicitly asks for an APK, update `en_Version.txt` and `rus_Version.txt` before building it: add the correct next version chapter and include all new or fixed user-visible changes, with later-version items italicized.
- Keep changes compatible with low-bandwidth messaging.
- Do not store direct P2P media on Firebase unless explicitly requested.
- For Firestore schema changes, update `firestore.rules` and related Cloud Functions if needed.
- For Android UI changes, update `activity_main.xml` and `MainActivity.kt` together.
- For version history changes, update both `en_Version.txt` and `rus_Version.txt`; later-version new items must be italicized.
- Prefer focused, small patches over broad rewrites.
- Verify with `.\gradlew.bat :app:compileDebugKotlin` when Java/JDK is available.
- If local verification is blocked by missing `JAVA_HOME` or another environment issue, state that clearly.
