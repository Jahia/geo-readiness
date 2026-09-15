# CI migration notes — geo-readiness

This file records everything the CI alignment needs that could **not** be done inside
`.github/`, plus the repository settings a GitHub admin has to provide. The workflows
themselves are already in place; the items below are what stands between them and a green
first run.

Reference: `Jahia/jahia-modules-action` (the shared composite actions, pinned by their `@v2`
major alias) and the org baseline managed in `Jahia/.github`.

---

## 1. What was added under `.github/` and `.chachalog/`

| File | Role | Managed by |
|---|---|---|
| `.github/workflows/on-code-change.yml` | PR gate: module signature, static analysis, build, SonarQube | this repo |
| `.github/workflows/on-merge.yml` | main: signature, build, SBOM to Dependency-Track, SNAPSHOT publish | this repo |
| `.github/workflows/on-release.yml` | release path: `release` → `update-signature` → `release-publication` → SBOM | this repo |
| `.github/workflows/schedule-sonar.yml` | full branch analysis + OWASP Dependency-Check, Mon/Wed/Fri 04:00 | this repo |
| `.github/workflows/chachalog-comment-pr.yml` | chachalog bot comment on the PR | **org-managed, DO NOT EDIT** |
| `.github/workflows/chachalog-prepare-changelog.yml` | aggregates fragments, opens the release-prep PR | **org-managed, DO NOT EDIT** |
| `.github/workflows/delivery-pr-chores.yml` | PR title lint, changelog check, doc guidelines, AI review | **org-managed, DO NOT EDIT** |
| `.github/workflows/delivery-issue-chores.yml` | issue chores | **org-managed, DO NOT EDIT** |
| `.github/maven.settings.xml` | every action defaults `mvn_settings_filepath` to this path | this repo |
| `.github/release.yml` | GitHub release-notes categories | **org-managed, DO NOT EDIT** |
| `.github/dependabot.yml` | alerts without PRs | this repo |
| `.github/instructions/changelog.instructions.md` | changelog style guide read by the AI reviewer | **org-managed, DO NOT EDIT** |
| `.github/pull_request_template.md` | PR body baseline | this repo |
| `.chachalog/config.mjs`, `.chachalog/.version`, `.chachalog/README.md` | changelog fragment mechanism | this repo |

`.github/workflows/build.yml` (hand-rolled checkout + `setup-java` + `mvn clean install` +
`upload-artifact`) was **deleted**. Everything it did is covered by `on-code-change.yml` and
`on-merge.yml`, and leaving it would have run the Maven build twice on every push.

---

## 2. Changes still required OUTSIDE `.github/`

These were **not applied** (other processes hold those files). Apply them as written.

### 2.1 `CHANGELOG.md` — hand-maintained → chachalog-generated

The repository carries the custom property `Changelog=true` (verified via
`GET /repos/Jahia/geo-readiness/properties/values`), which means it is **not** whitelisted out
of chachalog: the `Changelog` check on every PR will require a `.chachalog/*.md` fragment for
any change that touches something other than `.github/**`, `tests/**` or `**/*.md`.

`CHANGELOG.md` is **not deleted** — chachalog writes into it. But it must stop being
hand-edited, and its shape must match what chachalog emits (`# <artifactId> Changelog`, then
one `## <version>` section per release, as in `Jahia/graphql-core` and `Jahia/html-filtering`).

```diff
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -1,11 +1,7 @@
-# Changelog
-
-All notable changes to GEO Readiness are documented here. The format is based on
-[Keep a Changelog](https://keepachangelog.com/); this project follows semantic-ish versioning
-aligned with the Jahia module version.
-
-## [Unreleased]
-
-## [1.1.0] - 2026-09-13
+# geo-readiness Changelog
+
+<!-- Generated from the fragments in .chachalog/. Do not edit by hand. -->
+
+## 1.1.0
```

And the one remaining dated heading further down:

```diff
@@ -45,1 +45,1 @@
-## [1.0.0] - 2026-09-11
+## 1.0.0
```

There is no `[1.1.0]: https://…` link-reference footer to remove — checked, the file has none.

From then on: no hand edits. One fragment per user-facing PR, per `.chachalog/README.md`.

### 2.2 `package.json` — add a `lint` script so static analysis can lint

`static-analysis` runs `yarn run lint --max-warnings 1` in the module (`./`) **and** in the
tests folder (`tests/`) whenever each has a `package.json`. Neither has a `lint` script today
(`tests/package.json` has `e2e:ci`, `e2e:debug`, `typecheck`, `verify`), so both steps would
fail on a missing-script exit code rather than on a finding. The workflow therefore passes
`skip_lint_modules: true` / `skip_lint_tests: true`. Both are load-bearing, not defensive.
That is a stop-gap: the JavaScript half of this module (`src/javascript/**`, React 18 +
Module Federation) and the Cypress suite are not linted at all today.

```diff
--- a/package.json
+++ b/package.json
@@
   "scripts": {
     "build": "yarn webpack",
     "webpack": "webpack",
     "build:production": "yarn build --mode=production",
-    "clean": "rimraf src/main/resources/javascript/apps"
+    "clean": "rimraf src/main/resources/javascript/apps",
+    "lint": "eslint src/javascript --ext .js,.jsx"
   },
```

plus `eslint` and its React plugins in `devDependencies`, and an `eslint.config.js` (flat
config) or `.eslintrc.json` at the repo root. The devDependencies block is being edited by
another process right now, so no line-anchored hunk is given for it — add the three entries in
alphabetical position.

The same applies to `tests/package.json`, which needs its own `lint` script (or the Cypress
suite stays unlinted and only `skip_lint_tests` is dropped once it has one):

```diff
--- a/tests/package.json
+++ b/tests/package.json
@@
     "typecheck": "tsc --noEmit",
-    "verify": "cypress verify"
+    "verify": "cypress verify",
+    "lint": "eslint cypress --ext .ts"
   },
```

Once `yarn lint` is clean in each folder, drop the matching `skip_lint_modules` /
`skip_lint_tests` line from `.github/workflows/on-code-change.yml`.

**Unverified:** the exact ESLint version/config Jahia standardises on for a React 18 UI
extension. `@jahia/eslint-config` is not a dependency of this module and was not inspected.
Treat the block above as a shape, not a pin.

### 2.3 Release tags must use the underscore form

`on-release.yml` fires on a **pre-release**, and `jahia-modules-action/release` derives the
Maven version from the tag with `tr '_' '.'` then `cut -d'-' -f1`. A tag `v1.2.0` yields the
literal version `v1.2.0` and the release fails.

- Existing tags in this repo: `v1.0.0`, `v1.1.0` (hand-made, outside the Jahia release path).
- Required form, as used by every Jahia module (`sitemap`: `5_5_0`, `html-filtering`: `3_0_0`):
  **`1_2_0`**.

So the next release is created in the GitHub UI as tag `1_2_0`, with *Set as a pre-release*
ticked — that tick is the trigger. Leave the old `v*` tags alone; do not retag.

### 2.4 `pom.xml` — nothing required, two things worth knowing

- **No change is needed.** The `scm` block, the `bundle` packaging, the `org.jahia.modules:jahia-modules:8.2.0.0`
  parent and the inherited `distributionManagement` (`jahia-snapshots` / `jahia-releases` on
  `devtools.jahia.com`) are all what the release and publish actions expect.
- **Module signature is a no-op here, by design.** `update-signature` only signs a project whose
  `groupId` *or* parent `groupId` is `org.jahia.modules`. This module's parent groupId is
  `org.jahia.modules`, so the action does enter the signing branch — but it then `sed`s a
  `<Jahia-Signature>` / `<jahia-module-signature>` element that this pom does not contain, finds
  no diff, and commits nothing. It also only runs at all when the commit message carries
  `[ci sign]` (or `force_signature: true`, which `on-release.yml` sets). Net effect: harmless.
  It is kept in the workflows because it is the Jahia baseline and because the module's own
  `groupId` is `org.jahia.se.modules`, which is **not** subject to the runtime signature check
  that uninstalls unsigned `org.jahia.modules` bundles.

  If a real signature is ever wanted, add the property the action rewrites:

  ```diff
       <properties>
           <jahia-depends>default</jahia-depends>
           <yarn.arguments>build:production</yarn.arguments>
  +        <jahia-module-signature />
       </properties>
  ```

  and the signing step needs a Nexus account with read access to
  `https://devtools.jahia.com/nexus/content/repositories/jahia-internal-releases`
  (that is where `keymaker-cli` is pulled from).

### 2.5 Integration tests — WIRED. This section is kept for its reasoning only.

**Superseded.** Both missing files now exist and the job is in
`.github/workflows/on-code-change.yml`. Everything below describes why it was once deferred; do
not act on it.

The original reasoning was sound and the blockers are gone:

- `tests/docker-compose.yml` — present, tests container named `cypress`, behind the `test`
  profile so the action can bring the rest of the stack up first
- `tests/provisioning-manifest-build.yml` — present, and it installs what the suite needs:
  geo-readiness's own module dependencies (`robots`, `llms`) plus the template set chain
  `createSite` requires

What the first real run found is worth recording, because none of it was visible by reading and
all of it had accumulated while nothing executed the suite: an unpublished `jahia_image` tag, a
missing `ci.*`/`env.*`/`set-env.sh` script layer, a manifest that declared no module dependencies
when there are four, `cypress-wait-until` neither declared nor imported, a template set at a
version that does not exist, and that template set installed without the chain it needs. The
provisioning API answers HTTP 200 whether or not an entry installed, which is why several of
those stayed invisible.

That is the argument for the job below: a suite nothing runs does not stay still.

Note the Cypress suite must stay out of the `build` action's test-module path. That action's
JavaScript branch (`build-step-javascript`) runs `yarn build` and expects a deployable `.tgz`,
which a Cypress suite is not. Leave `tests_module_type` at its `mvn` default: it then looks for
`tests/jahia-module/pom.xml`, does not find one, and skips — which is what `Jahia/sitemap` and
`Jahia/html-filtering` do with their own `tests/` Cypress suites. The suite is built inside the
Docker Compose image instead.

Once the two files exist, paste this job into `.github/workflows/on-code-change.yml` (inputs
verified against
`Jahia/jahia-modules-action/.github/workflows/reusable-integration-tests.yml@v2`):

```yaml
  integration-tests:
    name: Integration Tests
    needs: build
    secrets: inherit
    uses: Jahia/jahia-modules-action/.github/workflows/reusable-integration-tests.yml@v2
    with:
      module_id: geo-readiness
      module_branch: ${{ github.ref }}
      jahia_image: ghcr.io/jahia/jahia-ee-dev:8-SNAPSHOT
      provisioning_manifest: provisioning-manifest-build.yml
      artifact_prefix: geo-readiness
      should_skip_testrail: true
      pagerduty_skip_notification: true
      instance_type: ubuntu-latest
      mvn_java_version: '11'
```

`instance_type` defaults to `self-hosted`; set it to `ubuntu-latest` unless this repo is
entitled to the Jahia self-hosted runner pool. `mvn_java_version` must match the JDK the
`tests/` Maven project targets, if that project is a Maven one.

### 2.6 Optional: OWASP suppressions

`schedule-sonar.yml` runs OWASP Dependency-Check with `-DfailBuildOnCVSS=7`. If a false positive
blocks it, the action automatically picks up `.owasp/custom-suppressions.xml` at the repo root
(on top of Jahia's shared suppression file). Create that file only when a suppression is needed.

### 2.7 Optional: README pointer to the fragment workflow

Contributors will keep editing `CHANGELOG.md` unless told not to. Suggested addition, e.g. just
before the end of `README.md`:

```diff
+## Changelog
+
+The changelog is generated. Do not edit `CHANGELOG.md`: add a fragment under `.chachalog/`
+in the same pull request as your change — see `.chachalog/README.md`.
```

---

## 3. Secrets and organization settings a repo admin must provide

This repository is **private**. Organization secrets and variables are only visible to a private
repository when the org-level secret's repository access list includes it. Every item below has
to be confirmed as accessible to `Jahia/geo-readiness`, not merely to exist in the org.

| Name | Kind | Used by | Consequence if missing |
|---|---|---|---|
| `NEXUS_USERNAME` / `NEXUS_PASSWORD` | secret | build, publish, release, release-publication, update-signature | Maven cannot resolve Jahia artifacts, publish and release fail |
| `GH_PACKAGES_USERNAME` / `GH_PACKAGES_TOKEN` | secret | every job using the `ghcr.io/jahia/jahia-docker-mvn-cache` container | job fails before the first step (image pull denied) |
| `SONAR_URL` / `SONAR_TOKEN` | secret | `sonar-analysis` (both workflows) | Sonar step fails; both inputs are declared `required: true` |
| `NVD_APIKEY` | secret | `schedule-sonar.yml` | optional — without it Dependency-Check falls back to the bulk datafeed, slower but functional |
| `DEPENDENCYTRACK_APIKEY` | secret | `sbom` jobs | SBOM upload fails |
| `DEPENDENCYTRACK_HOSTNAME` | **organization variable** (`vars.`) | `sbom` jobs | SBOM upload targets an empty host |
| `GH_API_TOKEN` | secret | `release` | release job fails |
| `GH_SSH_PRIVATE_KEY_JAHIACI` | secret | `on-release.yml` checkout + ssh-agent | the release commits cannot bypass branch protection on `main` |

Also required, at repository level:

1. **Actions → General → Workflow permissions**: the chachalog and delivery workflows declare
   their own `permissions:` blocks (`contents: write`, `pull-requests: write`, `id-token: write`),
   so the repo must allow workflows to request them, and *Allow GitHub Actions to create and
   approve pull requests* must be on — `chachalog-prepare-changelog` opens the release-prep PR.
2. **Custom property `Changelog`**: already `true`. Verified. Leave it; setting it to `false`
   would disable the whole fragment mechanism.
3. **Branch protection on `main`** — required status checks, using the job *names* as they appear:
   `Build Module`, `Static Analysis (linting, vulns)`, `Sonar Analysis`, `Update module signature`,
   plus `Lint PR Title` and `Changelog` from the delivery chores workflow.
4. **SonarQube project**: the project key SonarQube derives is the repo slug
   (`Jahia/geo-readiness`). It must exist on the Sonar server, with the `jahia-sonarqube` GitHub
   app installed on this repository so the Quality Gate posts back as a check.
5. **Add the repo to the global file sync** so the DO-NOT-EDIT files stay current: append
   `- Jahia/geo-readiness@main` to the repositories list in
   `Jahia/.github/.github/product-file-sync-config.yml` (or
   `other-repos-file-sync-config.yml` if this module is not owned by the Product team — that one
   syncs a smaller set and would *not* keep the chachalog workflows up to date).
6. **Before making the repository public**: run the `jahia-repo-config` checklist (license file,
   topics, README, branch rulesets, the product-lifecycle open-source approval issue). Nothing in
   `.github/` blocks the switch; note only that `pull_request_target` in
   `chachalog-comment-pr.yml` becomes reachable by fork PRs once public — that is the standard
   Jahia configuration and the reusable workflow is written for it.

---

## 4. What was verified, and what was not

**Verified by reading the source, not assumed:**

- Every `with:` key in every workflow was cross-checked against the real `action.yml` of the
  action it is passed to (`build`, `static-analysis`, `sonar-analysis`, `update-signature`,
  `publish`, `release`, `release-publication`, `sbom-processing`) — zero unknown inputs.
- Every workflow file parses as YAML (`yaml.safe_load`), and `.github/maven.settings.xml` parses
  as XML.
- The four container image tags considered exist on ghcr (`11-jdk-noble-mvn-loaded`,
  `17-jdk-noble-mvn-loaded`, `11-jdk-resolute-mvn-loaded`, `17-jdk-resolute-mvn-loaded`) —
  checked by requesting their manifests.
- The module compiles to Java 11 bytecode (class file major version 55), because
  `org.jahia:jahia-parent:8.2.0.0` pins `<source>11</source>` / `<target>11</target>`. The
  workflows use the **JDK 17** image, matching the JDK the deleted `build.yml` used and the one
  the module is built with locally; the 11 image would work equally well.
- `sonar-analysis`'s `java_version` input defaults to `11`; it is overridden to `17` here because
  the action invokes `sonar-maven-plugin` 5.6.x, which needs a Java 17 runtime.
- `update-signature`'s groupId gate, and the fact that this pom has no signature element
  (section 2.4).
- The chachalog mechanism: `chachalog-default-config` creates a config at runtime and does not
  persist it; `changelog-is-whitelisted` opts a repo out only when `Changelog` is `false`;
  `delivery/check-changelog` exempts `.github/**`, `tests/**`, `docker-tests/**`, `**/*.md`.
  `.chachalog/config.mjs` + `.chachalog/.version` are committed here because that is what the
  Maven modules that have adopted it do (`graphql-core` `.version` = `3.9.0` against pom
  `3.11.0-SNAPSHOT`; `html-filtering` `.version` = `3.0.0` against `3.1.0-SNAPSHOT`) — the file
  holds the **last released** version, hence `1.1.0` here against pom `1.2.0-SNAPSHOT`.
- `build-step-javascript` (the `build` action's JavaScript test-module branch) runs `yarn build`
  and moves a `.tgz` into `target/` — which is why the `tests/` Cypress suite must not be routed
  through it (section 2.5).
- Release tag form, from the actual release lists of `Jahia/sitemap` and `Jahia/html-filtering`.
- Reference workflows read in full: `Jahia/sitemap`, `Jahia/graphql-core`, `Jahia/html-filtering`,
  `Jahia/jahia-authentication`.

**Not verified — flagged rather than guessed:**

- **No workflow was executed.** Nothing here has had a live run; the first PR is the real test.
- **Secret availability.** Whether each org secret above is shared with this private repo could
  not be read (secret values and access lists are not readable with the token in use).
- **Nexus permissions for `org.jahia.se.modules`.** `publish` deploys to `jahia-snapshots` and
  the release stages to `staging-repository`. Whether the CI Nexus account may write that
  groupId — most Jahia modules are `org.jahia.modules` — was not confirmed. If the first merge to
  `main` fails on a 401/403 in the `Publish module` job, this is why.
- **`release-publication` staging match.** It selects the staging repository whose description
  matches `<module_id>:<version>`, i.e. `geo-readiness:1.2.0`. That description is produced by
  the Nexus staging plugin from the artifactId; it should match, but it was not observed.
- **SonarQube project existence** and the `jahia-sonarqube` app installation on this repo.
- **Dependency-Track project** for this module.
- **`audit-ci --skip-dev --critical`** was not run against this dependency tree; a pre-existing
  critical advisory in a production dependency would make `Static Analysis` red on the first PR.
- **The ESLint setup in section 2.2** — version, plugin set and config file shape are a
  suggestion, not a Jahia-standard pin.
- **`release-publication` runs `sudo apt-get install -y jq`** inside the mvn-cache container.
  `Jahia/sitemap` does exactly this and its releases succeed, so it is assumed fine here; it was
  not independently checked that `sudo` exists in the `17-jdk-noble` variant.
