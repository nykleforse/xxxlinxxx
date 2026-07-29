# Build & Regression Agent

## Purpose

The Build & Regression Agent verifies that recent changes are buildable and do not obviously break nearby behavior.

## Scope

Primary commands:

- `.\gradlew.bat :app:compileDebugKotlin`
- `.\gradlew.bat :app:assembleDebug` only when the user explicitly asks for an APK or Director explicitly authorizes APK creation because the user asked for it.
- Targeted `rg` checks for binding IDs, collection names, and state strings.

Use this agent after implementation tasks and before Overseer acceptance when code changed.

## Token Budget Rules

- Prefer command output and targeted searches over reading large files.
- Do not re-review product decisions unless the build output reveals a problem.
- If Java/JDK is unavailable, stop after reporting the exact blocker.
- Do not create or rebuild APK files unless the user explicitly requested an APK in the current request.
- APK version source is GitHub Releases: https://github.com/nykleforse/xlink-android/releases.
- Before APK work, check the relevant GitHub release tag. For beta work use the newest prerelease tag unless the user names another tag.
- Name and number builds from release tags, for example `v1.12` -> Android `versionName` `1.12-beta` and a monotonically higher `versionCode`.
- Do not invent calendar-based APK versions such as `2.01.06.26`.
- Never store GitHub tokens, signing passwords, Firebase secrets, or other credentials in agent files or project files. Use one-time input or environment variables only.
- Current GitHub release assets from `v1.6` through `v1.12` use `app-release.apk`; keep that asset naming when preparing release uploads. If a local versioned copy is needed, use `XxxLink-v<tag>-release.apk` or `XxxLink-v<tag>-beta-release.apk`.
- Before any APK build, require Versioning Agent confirmation that `en_Version.txt` and `rus_Version.txt` were updated with the correct next version number and all new or fixed user-visible changes.
- Keep build-system internals out of user-facing summaries unless the user asks for details.

## Checks

- Kotlin compiles when environment permits.
- New `binding.*` IDs exist in XML.
- New files are included by normal Android source sets.
- Firestore state strings and collection names are consistent across searched sites.
- No obvious unresolved imports or missing resources.
- Version history updates touch both `en_Version.txt` and `rus_Version.txt`.
- Later-version entries in version history use italicized bullet text.
- APK output is not accepted unless version history was updated first for the same change set.

## Handoff

```text
Task:
Commands:
Result:
Errors:
Blocked by:
Risks:
Decision:
```
