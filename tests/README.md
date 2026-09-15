# geo-readiness — Cypress end-to-end suite

Tests the three servlets and the two jContent entry points against a **live** Jahia. The suite owns
everything it touches: each spec creates its own site, its own users and its own role grants in
`before()`, and deletes all of it in `after()`. Nothing here reads or writes shared state, so a
repeated run is stable and two specs cannot interfere.

Conventions follow the `jahia-cypress-testing` skill; scenario coverage follows
`jahia-test-coverage-standards`. The stack is shaped for `Jahia/jahia-modules-action`'s
integration-tests workflow — see the root-file section at the end, which closes out
`.github/MIGRATION-NOTES.md` §2.5.

---

## Running it

This harness has the same shape as the other Jahia modules' (`securitytxt`,
`privateappstore`, `OSGi-modules-samples`): the `ci.*` and `env.*` scripts are thin
wrappers that resolve the pinned `@jahia/cypress` from `package.json` and delegate to
its CLI, and `set-env.sh` exports `.env` into the shell.

### Everything in Docker, the way CI runs it

```bash
cd tests
bash ci.build.sh      # build the tests image, stage ../target/*-SNAPSHOT.jar into ./artifacts
bash ci.startup.sh    # boot Jahia + cypress, run the suite, exit with its status
```

Re-run `ci.build.sh` after **any** change under `tests/`, or the change never reaches the
container and you debug a stale image.

### Local node, the day-to-day loop

Boot Jahia once, then run specs from your own machine:

```bash
cd tests
yarn install
./ci.startup.sh notests    # Jahia only, no specs
./env.run.sh               # provision the instance + run the suite once, headless

source set-env.sh          # REQUIRED, and again in every new terminal
yarn e2e:debug             # interactive runner
```

`source set-env.sh` is what puts `JAHIA_URL`, the super user credentials and the rest into
the shell; Cypress reads them from there, so a fresh terminal has none until you re-source
it.

### Against an instance you already have

Every value in `.env.example` is written `${NAME:-default}`, so anything already exported
wins and you can override one for a single run without editing the file:

```bash
JAHIA_URL=http://localhost:8080 yarn e2e:ci
JAHIA_IMAGE=ghcr.io/jahia/jahia-ee-dev:8.2.4.0 ./ci.startup.sh
```

> **The image matters.** `JAHIA_IMAGE` now defaults to
> `ghcr.io/jahia/jahia-ee-dev:8-SNAPSHOT`, which is what the sibling harnesses use and what
> is actually pullable. The previous default, `jahia/jahia-dev:8.2.4.0`, is not published
> and fails with `pull access denied for jahia/jahia-dev`. Being an EE image it wants a
> licence in `JAHIA_LICENSE`.

Node **20.19 or newer** is required: `@jahia/cypress` 8.x pulls `@faker-js/faker` v10, which refuses
to install on anything older. The failure looks like a repository problem and is not one.

`e2e:ci` runs in Chrome, which must therefore be installed locally. Drop `--browser chrome` from the
script if you want Electron instead.

### What has to be on the Jahia first

1. **The module under test, deployed and ACTIVE.** Build at the repository root and deploy:

   ```bash
   mvn -f .. clean install
   curl -s --user root:<pwd> --form bundle=@../target/geo-readiness-*.jar \
        --form start=true http://localhost:8080/modules/api/bundles
   ```

   The suite states this precondition for itself: a run-level hook posts to
   `/modules/geo-readiness/crawler-check` as an anonymous caller and requires a `401`. If the module
   is not mounted Jahia answers `404`, and the hook fails by name instead of letting a dozen specs
   fail with confusing diffs.

2. **A template set for `createSite`.** The suite defaults to `dx-base-demo-templates`. If the image
   ships something else, either apply `provisioning-manifest-build.yml` or point the suite at what is
   there: `CYPRESS_GEO_TEMPLATE_SET=<your-template-set> yarn e2e:ci`.

A disposable instance is in `docker-compose.yml`. The Cypress service sits behind the `test` profile,
so a plain `up` starts Jahia alone — which is what you want while writing specs:

```bash
cp .env.example .env          # edit JAHIA_IMAGE / SUPER_USER_PASSWORD
docker compose up -d
curl -s --fail -u root:root1234 -X POST \
     -H "Content-Type: application/yaml" \
     --data-binary @provisioning-manifest-build.yml \
     http://localhost:8080/modules/api/provisioning
```

### Configuration the suite applies to the module

`configureModuleForTests()` writes two keys into `org.jahia.se.modules.georeadiness` through the
provisioning API before each suite:

| Key | Value | Why |
|---|---|---|
| `PUBLIC_BASE_URL` | `http://localhost:8080`, or `CYPRESS_GEO_PUBLIC_BASE_URL` | The crawler check fetches the site's public URL **from inside the JVM**. Without this it resolves the site's server name, which is `localhost` for the browser and not necessarily for the container — and then reports every crawler as unreachable, a red that says nothing about the product. |
| `RATE_MAX_CALLS` | `10000` | The per-user crawler-check limit defaults to 20 per ten minutes. The validation spec spends that as root, and a second run inside the same window meets `429` on a test asserting `400`. This is not hiding the limiter: `site-scan`'s own limit is a constant in the servlet rather than configuration, and `rate-limit.spec.ts` measures it directly. |

---

## Layout

```
tests/
├── cypress/
│   ├── e2e/
│   │   ├── authorization.spec.ts        # the permission boundary, both directions
│   │   ├── happy-path.spec.ts           # primary flow, through the endpoints and through jContent
│   │   ├── rate-limit.spec.ts           # the 30-call window and its carve-out
│   │   ├── report.spec.ts               # the written-report endpoint, around the paid call it never makes
│   │   ├── request-validation.spec.ts   # malformed body, missing path, wrong content type
│   │   └── scope-and-language.spec.ts   # path / scope / language boundaries
│   ├── fixtures/graphql/hasPermission.graphql
│   ├── plugins/index.ts                 # node-side setup
│   └── support/
│       ├── auth.ts                      # who the next call acts as
│       ├── commands.ts                  # registration only
│       ├── e2e.ts                       # run-level precondition
│       ├── fixtures.ts                  # site + users + grants lifecycle
│       ├── geo.ts                       # the three endpoints and the one way to call them
│       └── ui.ts                        # reaching the drawer and the dashboard in jContent
├── Dockerfile                           # the `cypress` service the CI action builds and runs
├── docker-compose.yml
├── .env.example                         # sourced by @jahia/cypress's set-env.sh
├── provisioning-manifest-build.yml      # PR lane: external deps only
├── provisioning-manifest-snapshot.yml   # nightly lane: everything from mvn: coordinates
├── reporter-config.json                 # mochawesome + JUnit XML
├── cypress.config.ts
├── package.json
└── tsconfig.json
```

There is **no `tests/jahia-module/`**, deliberately. The module under test is a UI extension with no
node types of its own, and the suite needs only a site with a page tree and a published home page. An
OSGi test module would be scaffolding with nothing in it — and `.github/MIGRATION-NOTES.md` §2.5 is
right that `tests_module_type` must stay at its `mvn` default so the build action looks for
`tests/jahia-module/pom.xml`, does not find one, and skips. If a future spec needs a custom node type
or a server-side fixture, that is where it goes.

---

## What is covered

Against the scenario checklist in `jahia-test-coverage-standards`:

**Happy path.** A user holding `publish` runs `runScan` and gets a stored result back; reads it again
through `scanStatus`; opens the dashboard from jContent's site settings > SEO and drives a real scan
to a rendered summary; opens the drawer on a page and gets the tested URL and a per-crawler verdict.
The UI half is not optional — the standard's hard floor is that a feature has at least one Cypress
scenario going through the real UI, because that is what proves it works for an editor.

**Authorization, both directions.** This is the largest part of the suite, and deliberately so. The
module's gate is `publish` and **not** `publication-start` — a decision `SiteScope` documents as
having been tried and reverted, because a stock contributor holds `publication-start` and this screen
rewrites robots.txt and llms.txt for the whole site. So:

| | crawler-check | site-scan (every action) | site-files (every action) |
|---|---|---|---|
| guest | 401 | 401 | 401 |
| contributor + privileged | **200** | 403 | 403 |
| publish holder | 200 | 200 | 200 |

The contributor's `200` on the drawer is what makes the `403`s mean something: without it, every
denial would be equally consistent with a user who has no rights at all. For the same reason the
suite asserts its own premise before it asserts anything else — as each user, through GraphQL, that
the contributor holds `jcr:modifyProperties` and `publication-start` and does **not** hold `publish`.
A silently failed role grant would otherwise leave every denial passing and the suite measuring
nothing. And after the refused `applyRobots`, the suite reads robots.txt back as root and asserts the
text was never written: a refusal is a claim about effect, not only about status.

Both endpoints are walked action by action rather than sampled. The permission gate sits before the
`switch`, so an action added below it is covered the day it is added — and one added above it fails
here.

**Edge cases.** A `scope` naming a subtree of the site, and the site itself, are accepted. Refused:
`/`, `/sites`, another site, a page in another site, and the two relative forms that climb out
(`/sites/<key>/..` and `/sites/<key>/../<other>`). The second site is keyed `geoscopex` on purpose:
its path has the site under test's path as a **string** prefix, which is the case a `startsWith`
check without a trailing slash lets through. A `language` of `..`, `../../x`, `../../../etc`,
`en/../..`, empty, blank, one letter or a trailing separator is refused with `400`; `en`, `fr-BE` and
`pt_BR` are accepted, because Jahia uses both the tag and the node-name spelling. Every refusal is
paired with the identical call minus the hostile value succeeding, so none of them can pass vacuously.

**Error states.** Per endpoint: a truncated JSON body, a plain-text body, an empty body, a missing
path, an empty path, a path outside `/sites/`, a form-encoded content type, `text/plain`, no content
type at all, and `application/json;charset=UTF-8` accepted. Plus the ordering claim — a body that
would fail parsing, sent under `text/plain`, must be reported as a content-type problem, which is
what proves nothing is parsed before the caller and the type are settled. Unknown and missing actions
on both dispatching endpoints.

Every refusal asserts the error **message** as well as the status. All four validation failures
answer `400`, so a status-only assertion cannot tell "refused for the reason I meant" from "refused
for some other reason" — which is how a validation test quietly becomes a tautology.

### Not covered, and why

- **The scheduled scan actually firing.** `saveSchedule` is exercised, and the suite asserts the
  scheduler and the stored config agree. Waiting for a Quartz trigger to fire is time-dependent and
  would be a flaky test rather than a slow one. Per §4 of the coverage standard this is a documented
  exception, not an oversight.
- **The generated content of `llms.txt` and `robots.txt`.** `LlmsGenerator` and `RobotsEditor` are
  pure functions over a page tree and a text file. They triage to unit tests, which this repository
  does not yet have — see the root-file section below.
- **Reading the two files back over HTTP.** `applyRobots` writes `j:robots` via `jmix:robots` and
  publishes. Serving `/robots.txt` from that property is the community robots module's job, not this
  module's, so asserting it here would be testing a dependency. Note that `applyLlms` / `applyRobots`
  need `jmix:llms` / `jmix:robots` to be registered at all; if those community modules are absent on
  the target instance the two apply actions answer `500`, and the authorization assertions (which are
  `403` and never reach the write) still hold.

---

## Findings from writing the suite

None of these are test problems. Each is reported rather than worked around.

1. **`@jahia/cypress` 8.5.0 has undeclared dependencies.** `dist/support/apollo/links.js` requires
   `cross-fetch` and `dist/support/contextReporter.js` requires `mochawesome/addContext`, neither of
   which the package declares. `@apollo/client` 3.14 no longer pulls `cross-fetch` in, so the support
   file fails to bundle with `Cannot find module 'cross-fetch'`. Both are pinned in this suite's
   `package.json` with a comment; the real fix is a PR against `Jahia/jahia-cypress`, after which both
   can be dropped here. (`mocha` is also pinned — `mochawesome` needs
   `mocha/lib/reporters/base` at load time and Cypress does not expose its own copy to reporters.)

2. **`applySiteFile` answers `200` with an error body when the content is empty.** Every other
   refusal in the three servlets carries a matching status. The suite asserts the behaviour as it is
   (`request-validation.spec.ts`, "applying an empty site file"), so that fixing it fails loudly here
   rather than drifting. `400` would be the consistent answer.

3. **Neither the dashboard nor the drawer carries a test selector.** There is no `data-sel-role` or
   `data-testid` anywhere in `src/javascript/`, so the UI spec navigates by visible label — which
   couples it to English and to jContent's own nav wording. The proposed change is below.

---

## Confirm these on the first run against a live Jahia

Everything here was verified by compilation and by resolving every helper the suite calls. What could
not be verified without an instance is listed honestly:

1. **jContent's own nav labels.** `support/ui.ts` walks `Additional` → `SEO` → `GEO readiness`. The
   first two come from jContent's resource bundle and have been renamed between versions; they are
   overridable with `CYPRESS_JCONTENT_ADDITIONAL_LABEL` and `CYPRESS_JCONTENT_SEO_LABEL`. The third is
   this module's own `geo-readiness:dashboard.navLabel` and is deliberately **not** overridable —
   asserting it is part of the requirement.

2. **The drawer's action selector.** `[data-sel-role="geoReadiness"]`, from jContent's convention of
   rendering a registered action with a `data-sel-role` taken from its registry key. Overridable with
   `CYPRESS_GEO_ACTION_SELECTOR` while it is being confirmed.

3. **The image tag in `.env.example` / the CI job.** `jahia/jahia-dev:8.2.4.0` for local work and
   `ghcr.io/jahia/jahia-ee-dev:8-SNAPSHOT` in the pasted job are both placeholders. Use whatever tag
   the team's other modules test against, and note the EE image needs `JAHIA_LICENSE`.

4. **The exact role that carries `publish`.** The fixture grants `editor-in-chief` + `privileged`.
   If a future Jahia moves `publish` out of that role, the premise test says so in one line rather
   than letting the happy path fail obscurely — that is what it is there for.

One test needs reading rather than just running: **"does not offer the dashboard to a contributor"**.
Its liveness is the preceding test — the same navigation, taken by a user who *does* hold `publish`,
ends on the label. A red therefore means one of two things, and they are opposite: either the entry
is shown to a contributor, which is a real defect, or the contributor cannot reach the SEO section at
all, in which case the security property holds and the navigation in that test needs rewriting. Check
which by logging in as the contributor by hand and opening Additional > SEO.

---

## Root-file changes this harness needs

Nothing outside `tests/` was touched. Apply these at the repository root.

### 1. Wire the integration-tests job — DONE

The job is in `.github/workflows/on-code-change.yml`, and `.github/MIGRATION-NOTES.md` §2.5 has
been marked superseded. The rest of this section is kept because it explains the shape the action
expects, which is worth knowing if the job ever needs changing.



`.github/MIGRATION-NOTES.md` §2.5 holds a ready-to-paste job and says it was not added because
`tests/docker-compose.yml` and `tests/provisioning-manifest-build.yml` did not exist. **They now do**,
in the shape that action expects: the tests service is named `cypress`, it sits behind the `test`
profile so the action can bring the rest of the stack up first, and the suite writes JUnit XML to
`results/xml_reports` (which is what `tests_report_path` collects as `artifacts/results/xml_reports`).

So the block that currently reads:

```
  # Integration tests are not wired yet. A tests/ Cypress suite exists, but the reusable
  # workflow also needs tests/docker-compose.yml and a provisioning manifest, and neither is
  # in the repository. The ready-to-paste job is in .github/MIGRATION-NOTES.md section 2.5.
```

can be replaced by the job itself:

```diff
--- a/.github/workflows/on-code-change.yml
+++ b/.github/workflows/on-code-change.yml
@@
-  # Integration tests are not wired yet. A tests/ Cypress suite exists, but the reusable
-  # workflow also needs tests/docker-compose.yml and a provisioning manifest, and neither is
-  # in the repository. The ready-to-paste job is in .github/MIGRATION-NOTES.md section 2.5.
+  integration-tests:
+    name: Integration Tests
+    needs: build
+    secrets: inherit
+    uses: Jahia/jahia-modules-action/.github/workflows/reusable-integration-tests.yml@v2
+    with:
+      module_id: geo-readiness
+      module_branch: ${{ github.ref }}
+      jahia_image: ghcr.io/jahia/jahia-ee-dev:8-SNAPSHOT
+      provisioning_manifest: provisioning-manifest-build.yml
+      artifact_prefix: geo-readiness
+      should_skip_testrail: true
+      pagerduty_skip_notification: true
+      instance_type: ubuntu-latest
+      mvn_java_version: '17'
```

Two judgement calls left to whoever applies it: `instance_type` (`ubuntu-latest` unless this repo is
entitled to the self-hosted pool), and the `jahia_image` tag. `mvn_java_version` is irrelevant here —
there is no Maven project under `tests/` — so it can be left at its default or set to `17` for
consistency with the build.

`.github/MIGRATION-NOTES.md` §2.5 should be updated to say the two missing files now exist, or the
next reader will re-derive the same conclusion from stale notes.

### 2. Dependency updates for `tests/` (recommended)

`.github/dependabot.yml` watches `/` only, so this suite's `package.json` is invisible to it — the
same silent failure the testing skill describes for Renovate. A bump PR touching the root lockfile
alone reads as complete while the suite keeps resolving the old `@jahia/cypress`.

```diff
--- a/.github/dependabot.yml
+++ b/.github/dependabot.yml
@@
   - package-ecosystem: "npm"
     directory: "/"
     schedule:
       interval: "daily"
     allow:
       - dependency-type: "production"
     open-pull-requests-limit: 0
+  - package-ecosystem: "npm"
+    directory: "/tests"
+    schedule:
+      interval: "daily"
+    open-pull-requests-limit: 0
```

### 3. Test selectors on the two entry points (recommended)

The UI spec navigates by English label because there is nothing else to hold onto. Two attributes make
it durable and locale-independent:

```diff
--- a/src/javascript/GeoReadiness/dashboard/GeoDashboard.jsx
+++ b/src/javascript/GeoReadiness/dashboard/GeoDashboard.jsx
@@
         <LayoutContent
             hasPadding
+            data-sel-role="geo-readiness-dashboard"
             className={styles.layout}
```

```diff
--- a/src/javascript/GeoReadiness/GeoReadinessDrawer.jsx
+++ b/src/javascript/GeoReadiness/GeoReadinessDrawer.jsx
@@
-        <aside className={styles.drawer} aria-label={t('drawer.title')}>
+        <aside className={styles.drawer} data-sel-role="geo-readiness-drawer" aria-label={t('drawer.title')}>
```

Once those exist, `support/ui.ts` can assert on them instead of on `GEO readiness`, and the
`CYPRESS_GEO_ACTION_SELECTOR` escape hatch can go.

### 4. `applySiteFile` status code (product fix, finding 2 above)

```diff
--- a/src/main/java/org/jahia/se/modules/georeadiness/servlet/SiteFilesServlet.java
+++ b/src/main/java/org/jahia/se/modules/georeadiness/servlet/SiteFilesServlet.java
@@ case "applyLlms":
-                    writeJson(resp, HttpServletResponse.SC_OK, applySiteFile(...));
+                    // empty content is a bad request, not a successful no-op
```

The clean shape is for `applySiteFile` to throw or return a status alongside its body so the switch
can answer `400`. Whichever form is chosen, `request-validation.spec.ts` → "applying an empty site
file" must be updated in the same commit; it currently pins the `200` so the change cannot happen
silently.

### 5. No Maven change is required

`geo-readiness` is a single bundle; the suite is its own npm project and deliberately not part of the
reactor. A Maven profile would only wrap `yarn e2e:ci` and would still need a Jahia already running,
which Maven does not provide. The CI job above is the right place for it.

### 6. Lint and unit tests, tracked separately

- `.github/MIGRATION-NOTES.md` §2.2 asks for a `lint` script in `tests/package.json` so
  `skip_lint_tests: true` can be dropped from `on-code-change.yml`. It is not added here: the notes
  themselves record that the ESLint config Jahia standardises on was not verified, and guessing it
  would produce a script that fails for the wrong reason. Add it together with the root one.
- `LlmsGenerator`, `RobotsEditor`, `RobotsRules` and `SiteScorer` are pure logic over text and a page
  tree — §2 of the coverage standard triages them to unit tests, and the repository has none. Per §4
  that gap should be filed as a GitHub issue naming what is missing, rather than left implicit.
