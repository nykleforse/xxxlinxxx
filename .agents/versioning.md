# Versioning Agent

## Purpose

The Versioning Agent owns the project version history files and keeps release notes consistent in English and Russian.

## Scope

Primary files:

- `en_Version.txt`
- `rus_Version.txt`
- `AGENTS.md`
- `.agents/`

Use this agent when a task touches:

- version history;
- release notes;
- changelog wording;
- current capability lists;
- new version chapters.
- APK creation or release-style build requests.

## Rules

- Always update both `en_Version.txt` and `rus_Version.txt`.
- Do not rename version files unless the user explicitly asks.
- Keep old version chapters unchanged except for typo fixes.
- The first version chapter may contain the full current capability list.
- Every later version chapter must list only new functions added in that version.
- In every later version chapter, each new function must be italicized with Markdown-style asterisks:
  - English: `- *New feature.*`
  - Russian: `- *Новая функция.*`
- Add new chapters below existing chapters.
- Use the same GitHub release tag in both files.
- The version source is GitHub Releases: https://github.com/nykleforse/xxxlinxxx/releases.
- For beta work, use the newest prerelease tag unless the user names another tag.
- Do not invent calendar-based versions for APK work.
- If the user gives no version number and the task is not tied to a GitHub release, ask Liaison to clarify.
- Before any APK is built, add a new version chapter to both files unless the current request already added one for the exact same change set.
- For APK requests, use the GitHub release tag naming scheme, for example `v1.12`, and keep Android `versionName` aligned with it.
- APK release notes must include every new or fixed user-visible change since the previous version chapter.
- Do not allow Build & Regression Agent to create the APK until these version entries are present and checked.

## Chapter Templates

English first/full chapter:

```text
Version v1.12

What the messenger can do now:

- Existing capability.
```

English later chapter:

```text
Version v1.13

New features:

- *New feature.*
```

Russian first/full chapter:

```text
Версия v1.12

Что мессенджер умеет сейчас:

- Текущая возможность.
```

Russian later chapter:

```text
Версия v1.13

Новые функции:

- *Новая функция.*
```

## Checks

- Both files contain the new version chapter.
- Later-version bullet points are italicized.
- English and Russian chapters describe the same features.
- No old version content was accidentally duplicated into a later version.

## Handoff

```text
Task:
Files:
Version:
New items:
Checks:
Risks:
Decision:
```



