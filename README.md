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

Beside that, the drawer answers what only the whole site can answer, read from the last scan so it
costs nothing per page:

- **Where this page stands.** Its score next to the site average and its own section's average,
  because sixteen of eighteen means nothing without a reference point.
- **Who links here**, from navigation and from page content counted apart.
- **Whether it is in the sitemap and in `llms.txt`**, and for `llms.txt` whether regenerating would
  add it or the generator deliberately leaves it out.
- **Which languages it is missing**, and any translation written but never published.
- **Its addresses**, when a vanity URL is filed under a language the site does not serve, or the
  page answers on several at once.
- **Its structured data**, generated from its content type with a copy button, listing what the
  content model cannot fill and flagging any value that would contradict the visible page.

## The site settings page

Ten panels under **Additional > SEO**, grouped into four. The groups are the same three the drawer
sorts its checks into, so there is one vocabulary rather than two, with the site score standing
outside them as the summary of all three.

| Group | Panels |
|---|---|
| **Overview** | Site score |
| **Can a crawler reach it** | Invisible content · Internal links · Addresses |
| **Is what arrives usable** | Structured data · Languages · Freshness |
| **Site-level files** | Sitemap vs reality · robots.txt · llms.txt |

Everything except the site scan is a repository query, so most panels answer immediately rather
than waiting for a walk of the site.

Where a number has a shape worth seeing, the panel draws it: a meter under the site score, bars per
section, a histogram of content age with a newest-to-oldest range per type, coverage beside score
per language, one stacked bar for structured-data coverage, and the pages with findings as a
matrix - pages down, failing checks across - so a check every page fails reads as a solid column,
which is the template's doing rather than the authors'. All plain HTML and CSS in Moonstone's own
colors - no chart library - with a tooltip on hover and on keyboard focus.

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

**Sitemap vs reality.** Every `sitemap.xml` entry resolved back to a node instead of fetched, so a
site of any size costs no requests to check: pages the map never mentions, entries resolving to
nothing, dates that contradict the content, pages advertised while their own markup says `noindex`,
and pages listed at an address that redirects.

**Internal links.** A crawler reports what it found; it cannot report what it missed. The link
graph is read from the rendered HTML of every page the scan already fetched, so a menu built from
the page tree, a listing, a rich text link and a configured button all count alike. Navigation and
content links are counted apart: being in a menu that lists everything is not the same as somebody
choosing to link to you.

**Addresses.** Every address a page answers on, from the vanity URL service. Aliases filed under a
language the site does not serve return a 404 to everybody; one page on several live addresses
splits the signal; a page with aliases and no canonical leaves engines guessing.

**Structured data.** schema.org derived from the content type rather than inferred from the words
on a page. Map each type once and every item of it produces JSON-LD. Required properties the model
cannot fill are named, never invented, and a generated value that contradicts the page is reported
rather than emitted. Nothing is written into a page: the drawer shows the snippet and a person
places it.

**Languages.** Coverage and score for each language the site declares, side by side, so a strong
language cannot average out a weak one. A language nobody has scanned reports as *not measured*,
never as zero.

**Freshness.** How old everything published actually is, grouped by content type and by section
rather than ranked, so a legal notice is not flagged next to a news article. A group is flagged
only when its *newest* item is past a threshold you set.

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
curl -s --user root:root --form bundle=@target/geo-readiness-*.jar \
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
- **The link graph and the freshness picture judge one language per scan**, since only that
  language's pages were fetched. A multilingual site needs a scan per language to see all of it.
- **The sitemap comparison resolves rather than fetches**, so it cannot see a redirect inside the
  sitemap's own entries.
- **Structured data is generated, never injected.** Deliberate, but it means somebody has to place
  the snippet in a template for it to reach a visitor.
- **The robots.txt merge is line-based**, not a full RFC 9309 rewrite. It does not reorder,
  deduplicate or tidy, so a hand-written file comes back recognisable.
- **The check fetches a page even when robots.txt disallows it**, deliberately. Knowing that a
  disallowed page is nonetheless reachable is a finding.

## Documentation

[`docs/`](docs/README.md) — the check reference, the architecture, and the backlog.
[`.agents/README.md`](.agents/README.md) — invariants and traps, read this before changing code.
