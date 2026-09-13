<!--
Title: a Conventional Commit — type(optional-scope): imperative, lowercase summary
Types: feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert.
The type drives the release-notes category (.github/release.yml); `!` or the
`breaking-change` label marks a breaking change.

Changelog: the customer-facing note is a fragment file in .chachalog/, not this body
and not CHANGELOG.md. Add one whenever the change is visible to a user.
Delete any section below that does not apply.
-->

## Summary

<!-- What this PR does, in a line or two. -->

## Why

<!-- The motivation, or the problem being solved. Answers "why should this change exist". -->

## Changes

<!-- The notable changes, as bullets. Name the meaningful ones; never dump the diff. -->

-

## Validation

<!-- How this was verified. Name a command a reader can run in this repository. -->

- [ ] `mvn clean install` passes
- [ ] Deployed to a local Jahia and exercised in jContent
- [ ] Unit and/or integration tests added or updated

I have considered the following implications of this change:

- [ ] Security (authentication, authorization, data fetching, servlet input handling)
- [ ] Performance
- [ ] Migration / upgrade from the previous version
- [ ] Code maintainability
- [ ] Breaking changes are documented, and `jahia-depends` was reviewed

## Documentation

<!-- Did the README, the in-app help or the docs/ folder need updating? -->

- [ ] Inline documentation
- [ ] README / docs
- [ ] User-facing documentation

## ADR

<!-- None, or a link to the added/updated architecture decision record. -->

None.
