# GEO Readiness

**Can AI crawlers actually read this site?**

A Jahia jContent extension that answers a question nothing else in the back office can: when
GPTBot, ClaudeBot or PerplexityBot ask for a page, what do they actually get?

It needs nothing from any external vendor.

## Why it cannot be done in the browser

The obvious approach is to render the page and look at it. That is the wrong instrument. The
editor's browser is authenticated, runs JavaScript, and is never challenged by the firewall. A
crawler is none of those things.

So every question is answered server-side: one fetch per crawler user agent, no cookies, no
session, redirects not followed, and the **initial HTML** is read rather than a rendered DOM. A
page whose content only appears after JavaScript runs looks perfect to an editor and empty to a
crawler.

`page-audit` audits what an editor sees. This audits what a crawler receives.

## Two entry points

| Where | Scope | What it answers |
|---|---|---|
| **GEO readiness** action on a page | One page | What does each of fifteen AI crawlers receive, and what is wrong with it |
| **Additional > SEO > GEO readiness** | The whole site | Where is the site weak, which template is to blame, and what do the two site files say |

The split follows the data. `robots.txt` and `llms.txt` belong to the site, so editing them from a
page drawer changed the same file whichever page happened to be open. The drawer reports on them;
the settings page owns them.

## The page drawer

Fetches the published page sixteen times: once as a normal browser for a control, then once as
each AI crawler. For every one it reports the status, the time, the word count, and what the
initial HTML contains before any JavaScript runs.

Then the interesting part, which is the disagreements:

- **Blocked but allowed.** `robots.txt` permits the crawler and the server refuses it. Usually a
  firewall rule nobody remembers adding.
- **Reachable but disallowed.** The file forbids the crawler and the server serves it anyway.
- **Thin.** A crawler receives far less text than the browser did, which points at rendering that
  depends on JavaScript.
- **Invisible.** A visitor with no account cannot read the page at all, so no crawler ever will.
  Some servers answer a gated page with a login form and a 200, which looks like success.

A page that has never been published says so, rather than showing an error.

## The site settings page

Four tabs under **Additional > SEO**, beside Robots.txt and Sitemap.

**Site score.** Every published page scored on a schedule you set, one fetch each. Shows the
overall figure and the movement since the last run, a breakdown by section, the pages with
findings, and the schedule itself: a cron expression built from dropdowns, an optional scope, and
an enable toggle. A running scan appears in the administration job list.

**Findings roll up to the template that produced them.** A check is blamed on the template only
when it fails on nearly every page that template renders. Three failures out of four hundred is
three authors; four hundred out of four hundred is the template. That distinction is why this is a
platform feature rather than a page tool: nothing outside Jahia knows which template rendered a
page.

**robots.txt.** Each of fifteen AI crawlers, what it does, and what blocking it would cost. Seven
answer questions, so refusing one takes the site out of that assistant's replies. Seven only train
models, so refusing those costs no visibility at all. Toggling writes into the existing file
without disturbing anything it was not asked about.

**llms.txt.** Generated from the published page tree. Deterministic: no model call and no external
service, so the same site produces the same file every time. Pages a visitor cannot read, and pages
marked `noindex`, are left out.

**Invisible content.** Published pages no crawler can ever read. Pages closed on purpose are listed
apart from pages closed by accident, because a members area is not a defect.

## The score

A count of checks, not a rating out of 100. Eighteen checks in three groups, each reading one
observed fact and showing that fact beside the verdict, so an editor can disagree with a specific
line rather than with a number they cannot see inside.

Being disallowed in `robots.txt` is **not** counted as a failure: refusing a crawler is a
legitimate decision. A disagreement between the policy and the server is, because one of the two is
then wrong.

The site scan evaluates 17 of the 18. Comparing a crawler's response against a browser's needs a
second fetch of the same page, so that one stays in the drawer. Both report the same failures.

Full reference: [`docs/checks.md`](docs/checks.md).

## Nothing is overwritten unseen

Generating `llms.txt` and setting crawler rules are the only things this module writes. Both show a
highlighted line diff against what is stored, both need a second confirming click, and what gets
written is exactly the text on screen, so hand edits made before applying survive.

## Build and deploy

Requires Java 17 and Maven 3.6+. Node 22 and Yarn are downloaded by the build itself, so nothing
else needs installing. `yarn.lock` is committed and must stay committed.

```bash
mvn clean install
curl -s --user root:root --form bundle=@target/geo-readiness-1.0.0-SNAPSHOT.jar \
     --form start=true http://localhost:8080/modules/api/bundles
```

Enable the module on the target site. Both entry points guard with
`requireModuleInstalledOnSite`, so neither appears otherwise.

Before claiming anything works, read [`test-fixtures/README.md`](test-fixtures/README.md): a
zero-dependency server that simulates the failure cases a healthy local Jahia cannot produce.
`test-fixtures/probe.py` is the same logic as the servlet, runnable from a machine that can
actually reach the site, for telling whether the module is wrong or the site really is like that.

## Configuration

`digital-factory-data/karaf/etc/org.jahia.se.modules.georeadiness.cfg`, applied immediately with no
redeploy.

| Key | Default | What it does |
|---|---|---|
| `FETCH_TIMEOUT_MS` | 8000 | Per-fetch timeout. Sixteen agents run three at a time, so the drawer's worst case is about six times this. |
| `MAX_BODY_BYTES` | 1500000 | How much of a page to read. |
| `RATE_MAX_CALLS` / `RATE_WINDOW_MS` | 20 / 600000 | Per-user limit on the expensive actions. Reading a stored result is never limited. |
| `CRAWLER_AGENTS` | *(blank)* | Override the agent list as `name|user-agent` pairs. Blank uses the built-in fifteen. |
| `PUBLIC_BASE_URL` | *(blank)* | Force the base URL when the site's server name is not resolvable from inside the container. |

## Known gaps

- **The site scan is capped at 2000 pages per run** and is synchronous when triggered by the
  button. A larger site needs the schedule.
- **One language per scheduled run.** A multilingual site needs one schedule per language.
- **`llms-full.txt` cannot exist on this stack.** The community `llms` module declares one property
  and one route, so the report calls the file absent when it is really unsupported.
- **Vanity URLs are host-dependent by Jahia design.** The rewriter emits one only when the
  request's server name resolves to the page's site, so on a box where several sites share
  `localhost` the report falls back to the `/sites/<key>/...` form, which is the form that actually
  works there.
- **The robots.txt merge is line-based**, not a full RFC 9309 rewrite. It does not reorder,
  deduplicate or tidy, so a hand-written file comes back recognisable.
- **The check fetches a page even when robots.txt disallows it**, deliberately. Knowing that a
  disallowed page is nonetheless reachable is a finding.

## Documentation

[`docs/`](docs/README.md) — the check reference, the architecture, and the backlog.
[`.agents/README.md`](.agents/README.md) — invariants and traps, read this before changing code.
