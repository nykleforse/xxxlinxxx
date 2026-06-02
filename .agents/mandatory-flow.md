# Mandatory Agent Flow

This file is the project's required agent startup protocol.

## Rule

For every non-trivial user request, start with the Liaison agent.

The required flow is:

1. Liaison receives the user request.
2. Liaison states the goal briefly and identifies missing context, risks, or useful ideas.
3. Liaison sends the clarified request to Director.
4. Director breaks the work into tasks and assigns owners.
5. Implementers complete scoped tasks.
6. Overseer reviews each completed task.
7. If work is incomplete, Overseer sends it back for rework.
8. If work is acceptable, Overseer sends it to Director.
9. Director integrates accepted tasks and checks that they work together.
10. If integration exposes a problem, Director sends the relevant task back for rework.
11. When the combined result is accepted, Liaison reports back to the user.

## Tiny Request Exception

The flow may be skipped only for tiny direct requests, such as:

- explaining a single line or file;
- answering a simple factual question about the already-open code;
- showing a command output;
- making a trivial typo-only edit.

If a request changes behavior, touches multiple files, affects Firebase/WebRTC/native code, or needs verification, it is not tiny.

## Required Opening Note

For non-trivial tasks, begin work with a short note in this shape:

```text
Liaison: понял цель: ...
Передаю Director: ...
```

Then follow `.agents/workflow.md`.

## Enforcement

- Do not start implementation before Liaison has shaped the request.
- Do not mark work done until Overseer has accepted the relevant task.
- Do not report final success until Director has checked integration.
- If verification is blocked, Liaison must state the practical blocker briefly, without low-level internals unless asked.
- Do not create, rebuild, sign, or export an APK unless the user explicitly asks for an APK in the current request.
- If the user explicitly asks for an APK, update `en_Version.txt` and `rus_Version.txt` first with the correct next version number and all new or fixed user-visible changes included in that APK.
- Follow `.agents/user-facing-rules.md` for all user-facing messages.
