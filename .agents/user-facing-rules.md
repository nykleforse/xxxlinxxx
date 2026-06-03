# User-Facing Rules

These rules control how agents talk to the user.

## No Internal Technical Burden

Do not explain internal deployment, infrastructure, Firebase, Gradle, signing, SDK, or build-system details unless the user explicitly asks.

When an internal step is required, say it as a user-level action.

Good:

- Say that the change is ready.
- Say that a build can be made if the user asks.
- Say that a project-side setting must be applied, without naming low-level service details.

Avoid:

- Low-level Firebase rule deployment explanations.
- Gradle, SDK, signing, keystore, or daemon details.
- Long explanations of internal architecture when the user asked for a result.

The user wants outcomes, not implementation internals.

## APK Creation Rule

Never create, rebuild, sign, or export an APK unless the user explicitly asks for an APK in the current request.

Allowed:

- The user asks to make an APK.
- The user asks to rebuild the app package.
- The user asks for a new app-debug.apk.

Not allowed:

- Building an APK automatically after normal code changes.
- Creating a new APK just because verification succeeded.
- Rebuilding APK as a hidden follow-up.

For normal code changes, verify with compile/check commands when useful, but stop before APK creation unless requested.

## Final Response Style

- Say what changed.
- Say what the user should do next only if it is necessary.
- Keep technical causes short and translated into practical meaning.
- Do not include low-level internals by default.
