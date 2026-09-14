# How it works

A map of the module for somebody about to change it. The invariants and the traps are in
[`../.agents/README.md`](../.agents/README.md); this file is the shape.

## The one decision everything else follows from

**A crawler is not a browser, and the editor's browser is the worst possible instrument for asking
what a crawler sees.** It is authenticated, it runs JavaScript, and no firewall ever challenges it.

So every question is answered server-side: fetch the published URL with no cookies and no session,
send the crawler's own user agent, do not follow redirects, and read the **initial HTML** rather
than a rendered DOM. `page-audit` audits what an editor sees. This module audits what a crawler
receives. Different question, different mechanism, and the two must not be merged.

## Two surfaces, two fetch strategies

|  | Page drawer | Site scan |
|---|---|---|
| Scope | One page | Every published page |
| Fetches | 16, one per crawler plus a control | 1 per page |
| Runs | On demand | Cron, or a button |
| Answers | How does each crawler differ on this page | Where is the site weak |

The asymmetry is arithmetic, not taste. Sixteen fetches across 50,000 pages is 800,000 requests and
about fifteen hours. One per page is about an hour.

The cost is exactly one check: comparing a crawler's response against a browser's needs two
fetches, so it is dropped from the site score rather than allowed to pass for free. A
JavaScript-only page is still caught, by the word-count check.

## Classes

### `check/`

| Class | Does |
|---|---|
| `AiCrawlers` | The one list of crawlers: name, user agent, robots token, and whether it answers questions or trains models. Both the fetcher and the robots checker read it, so they cannot drift apart. |
| `PageFetch` | One fetch, and everything readable from the HTML that comes back. Shared by both surfaces so a "fetch" means the same thing in each. |
| `GeoScore` | The 18 checks, over a report. The site scan builds a report shaped like the drawer's and runs this, so one page can never score differently in two places. |
| `RobotsRules` | A robots.txt parser: longest match wins, `Allow` beats `Disallow` on a tie, `*` and `$` honoured, consecutive `User-agent` lines share a group. |
| `RobotsEditor` | Merges allow/block decisions into an existing file without disturbing anything it was not asked about. |
| `SiteFilesChecker` | Fetches and describes `robots.txt`, `llms.txt` and `llms-full.txt`. |
| `LlmsGenerator` | Builds an `llms.txt` from the published page tree. Deterministic, no model call. |
| `GuestVisibility` | Which published pages a visitor with no account cannot read, and why. |
| `SiteScorer` | The site walk: list, fetch once, score, aggregate. |
| `TemplateRollup` | Groups findings by template and decides which are the template's fault. |
| `PublishedMap` | Everything a guest can reach, keyed by public path, across every site language. Shared, because getting it wrong is not visible in the results. |
| `LinkGraph` | Inbound links per page, read from rendered HTML and topped up with repository references. Navigation counted apart from content. |
| `SchemaMap` | Node type to schema.org type, the properties each type needs, and where each can be sourced from. |
| `StructuredData` | Generates JSON-LD per node and coverage per site; reports gaps and contradictions rather than filling them. |
| `Languages` | Translation coverage per language, joined to the per-language scores; unmeasured kept distinct from zero. |
| `Freshness` | Ages everything published, bucketed and grouped by type and section, against a configurable threshold. |
| `LlmsFreshness` | Whether the served llms.txt still matches what generating would produce. |
| `VanityUrls` | Every address a page answers on, checked against the site's languages and the canonical tag. |
| `SitemapCheck` | Resolves every `sitemap.xml` entry back to a node and compares it with what is actually published, as guest, across every site language. |
| `ScanStore` | Reads and writes the scan on the site node. |

### `charts/`

| File | Role |
|---|---|
| `Charts.jsx` | `Meter`, `BarList`, `StackedBar`, `Histogram`, `PairedBars`, `FailureMatrix` - the six forms the panels use, in HTML/CSS with a shared hover/focus tooltip. No library. `Histogram` is the only vertical form: counts over a time axis, on a track with a definite height, because a percentage resolves against nothing else. |
| `Charts.module.css` | Tokens mapped from Moonstone, tone classes scoped under `.chart` so they beat every mark's default, mark specs (thin bar, rounded data-end, 2px surface gaps, surface ring on markers). |

### `javascript/util/`

| File | Role |
|---|---|
| `Paged.jsx` | Pages any findings list with Moonstone's `TablePagination`. Full up to the page size (25 by default); the control appears from ten rows so a denser view can be chosen; past the page size the range is stated above the rows. |
| `jcontentUrl.js` | jContent's own address for a node, routing `/contents/...` to the content-folders section so a link to a content item resolves instead of landing on nothing. |

### `servlet/`

| Alias | Scope | Writes? |
|---|---|---|
| `/geo-readiness/crawler-check` | One page | No |
| `/geo-readiness/site-scan` | One site | Only the scan record |
| `/geo-readiness/site-files` | One site | **Yes**, robots.txt and llms.txt |
| `/geo-readiness/report` | One site | Only the stored report |

All three: authenticated callers only, `application/json` required so a cross-site form post cannot
reach them, per-user rate limiting on the expensive actions, and generic errors that leak no
upstream detail.

`site-scan` and `site-files` additionally require the permission the dashboard route declares in the
front end, so the screen and the server state the same rule; `crawler-check` requires a read of the
node in the editing workspace, which is the floor the drawer actually opens at.

### `ai/`

The provider layer for the written report, mirroring automatic-content-tags so a reader of one
module recognises the other: `LlmProvider` (one chat completion), `LlmSettings` (credentials and
model, `toString` masks the key), `Completion` (text, whether it was cut, token counts),
`AnthropicProvider`, and `OpenAiProvider` and `DeepSeekProvider` over a shared
`OpenAiCompatibleProvider`. DeepSeek's V4 models reason by default, which eats the output budget and
loosens the JSON-only instruction, so that provider switches thinking off. `HttpJson` is the JDK
client; the endpoints it reaches come from configuration, never from a request.

`check/GeoReport` owns the rest: the digest built from the stored state, the prompt, the call, and
the parse into a fixed shape where every string is clipped, every enum normalised, every list
capped, and a page reference kept only if the digest listed that page.

### `scheduler/`

`SiteScanJob` is a Quartz `BackgroundJob`; `ScanScheduler` installs one cron trigger per site and
language. `isProcessingServer()` keeps a cluster from scanning the same site on every node.

A trigger names a job class from this bundle, so leaving one in place across a refresh would point
it at a class the framework has replaced. `ScanLifecycle` removes them when the bundle stops and
reinstalls them from the repository when it starts, which is what keeps a redeploy from either
stranding a trigger or discarding an editor's schedule. The schedule itself lives in the store,
never only in Quartz.

A run is owned: the store records the account that saved the schedule, and each run asks again
whether that account still holds the permission. A trigger nobody is entitled to removes itself.

### `util/`

| File | Role |
|---|---|
| `PublicUrls` | A page's public address by asking Jahia rather than guessing: `node.getUrl()` then the outbound URL rewriter, which yields the vanity URL where one resolves. The base host comes from `PUBLIC_BASE_URL` or the site's own server name; a request may contribute scheme and port, and only once it is already addressing that host. |
| `MockHttp` | A request and response as dynamic proxies, because the rewriter needs them and a scheduled job has neither. |
| `SiteScope` | Which site a request may act on, and where inside it. Resolves in the caller's session and compares the resolved path, because a request carries three values shaped like a path and JCR reads a relative one the way a filesystem does. Also the language pattern, since that value becomes a node name. |
| `FetchGuard` | What may be fetched: an http(s) address on the same origin as the base. The sitemap index is fetched content naming further urls, so its children go through this. |

## Storage

One hidden `nt:unstructured` node per site, `geo-readiness`, carrying `jmix:nolive` so it never
publishes. A child node per language holds the run record and results, and the language is checked
against a pattern before it is used as that node's name. The written report is stored there too,
one per language, as the parsed shape rather than the model's raw answer.

No CND ships. That is deliberate: a rejected content type does not merely fail, it breaks Content
Editor across the instance until the module is removed. Nothing here needs a typed model.

**Aggregate plus failures, never a row per page.** A per-page record measures 86 bytes, so every
page of a 50,000 page site across three languages would be about 12 MB against 2.5 MB for the
failures alone. The dashboard's four questions — overall score, score by section, worst pages,
movement since the last run — are all answerable from the smaller shape. The previous aggregate is
kept, which is the whole of "movement".

## The write path

Only `site-files` writes, and never silently. Generate produces the text, the interface shows a
line diff against what is stored, the apply button needs a second confirming click, and what gets
written is exactly the text on screen. An editor can hand-edit a generated file and keep their
edits.

Reporting is safe to run against anything. Writing is not. Keep the two visibly apart.
