# Agent Workflow

This workflow connects the Liaison, Director, and Overseer agents to the project.

The startup rule in `.agents/mandatory-flow.md` is mandatory. For every non-trivial request, begin with Liaison before Director or implementation work.

## Lifecycle

1. User Intake

   The Liaison talks with the user, keeps the request concise, asks only necessary questions, and turns the request into a clear goal.

2. Director Intake

   The Director reads the user request and turns it into a task list with owners, expected files, risks, and verification steps.

3. Assignment

   The Director assigns implementation work to the appropriate specialist agent or developer. To save tokens, the Director calls only specialists whose subsystem is touched.

   If the user requested an APK, Director must assign Versioning Agent before Build & Regression Agent. The APK build can start only after both version files have the correct next chapter and list all new or fixed user-visible changes.

4. Implementation

   The assigned agent implements only its scoped task and reports:

   - changed files;
   - behavior added or changed;
   - verification performed;
   - known gaps or blockers.

5. Review

   The Build & Regression Agent verifies code changes when useful, then the Overseer reviews the result. If the task is incomplete, unsafe, incompatible, or unverified, the Overseer sends it back with concrete corrections.

6. Integration

   When the Overseer accepts a task, it goes back to the Director. The Director checks all accepted tasks together for conflicts, missing schema/UI updates, and end-to-end behavior.

7. Final Acceptance

   The Director accepts the combined work only when it satisfies the original request and the project still fits together.

8. User Report

   The Liaison reports the result back to the user briefly: what changed, what was checked, and what remains blocked.

## Handoff Format

Every agent handoff should use this compact format:

```text
Task:
Owner:
Files:
Result:
Verification:
Risks:
Decision:
```

`Decision` must be one of:

- `needs-work`
- `accepted-by-overseer`
- `accepted-by-director`

## Required Checks

## Specialist Routing

- Android UI Agent: layout, binding, visible state, notification UX.
- Firebase Agent: Firestore rules, collections, Cloud Functions, FCM payloads.
- WebRTC P2P Agent: PeerConnection, DataChannel, calls, direct file/photo transfer.
- Security & Privacy Agent: encryption, keys, local files, sensitive data exposure.
- Build & Regression Agent: compile checks, binding/resource searches, regression smoke checks.
- Versioning Agent: English/Russian version history and italicized new-version entries.

Director should skip specialists that are not relevant to the task.

For Android/Kotlin changes:

- Does the code compile in principle: imports, binding IDs, type names, coroutine/thread usage?
- Does the UI element exist for every `binding.*` reference?
- Does lifecycle cleanup cancel listeners/jobs?

For Firebase changes:

- Do client writes match `firestore.rules`?
- Do Cloud Functions and Android clients agree on collection and field names?
- Is sensitive content avoided in server-side payloads?

For WebRTC/DataChannel changes:

- Does the feature handle closed channels?
- Are large payloads chunked?
- Does it avoid blocking DataChannel callbacks with disk or network work?

For native/Codec2 changes:

- Is the ABI/build configuration respected?
- Are native resources released?
- Are changes isolated from unrelated codec sources?
