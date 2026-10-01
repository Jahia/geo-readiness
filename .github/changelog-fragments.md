# Changelog fragments

The customer-facing changelog of this module is assembled from the fragment files in this
folder. Do not hand-edit `CHANGELOG.md` at the repository root: the
`Chachalog - Prepare Changelog` workflow aggregates these fragments into it and opens a
"prepare next release" pull request that also bumps `.chachalog/.version`.

Add one fragment per user-facing pull request. Name the file after the change it describes, the
way the released ones were named: `robots-dollar-anchor.md`, `unused-imports.md`,
`accessibility-aaa.md`. The only rule the name has to keep is that two pull requests in flight at
once must not pick the same one, and a name that describes its own change rarely collides. A random
name works too, and is what `chachalog`'s own tooling generates, but nothing here requires one.

The name is never read by anybody but us: the release pull request consumes the file and deletes it,
and the text inside is what reaches the changelog.

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

`.version` holds the last released version, and is maintained by the release pull request
chachalog opens.

This guide lives in `.github/` rather than in `.chachalog/`, because chachalog treats **every**
markdown file in that folder as a fragment: a README kept there is consumed and deleted by the
first release that runs.
