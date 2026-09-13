# Changelog fragments

The customer-facing changelog of this module is assembled from the fragment files in this
folder. Do not hand-edit `CHANGELOG.md` at the repository root: the
`Chachalog - Prepare Changelog` workflow aggregates these fragments into it and opens a
"prepare next release" pull request that also bumps `.chachalog/.version`.

Add one fragment per user-facing pull request, in a file with a **random** name:

```markdown
---
# Allowed version bumps: patch, minor, major
geo-readiness: minor
---

Added a per-language readiness score so a site owner can see which locales AI crawlers can read.
```

- The package name is the Maven `artifactId`: `geo-readiness`.
- The bump matches the version line in development, read from the `-SNAPSHOT` in `pom.xml`
  (`1.2.0-SNAPSHOT` → `minor`, `1.2.1-SNAPSHOT` → `patch`, a breaking change → `major`).
- A fragment is only needed when the change is visible to a user. Pure refactors, tests, CI
  and docs need none — the `check-changelog` gate already exempts `.github/**`, `tests/**`
  and `**/*.md`.
- Write the note per `.github/instructions/changelog.instructions.md`: one past-tense sentence
  under 120 characters, outcomes rather than internals, no class or method names.

`.version` holds the last released version. It is machine-maintained after the first
chachalog release; it is seeded here at `1.1.0`, the version of the most recent GitHub release.
