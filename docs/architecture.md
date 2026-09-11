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
| `SitemapCheck` | Resolves every `sitemap.xml` entry back to a node and compares it with what is actually published, as guest, across every site language. |
| `ScanStore` | Reads and writes the scan on the site node. |

### `servlet/`

| Alias | Scope | Writes? |
|---|---|---|
| `/geo-readiness/crawler-check` | One page | No |
| `/geo-readiness/site-scan` | One site | Only the scan record |
| `/geo-readiness/site-files` | One site | **Yes**, robots.txt and llms.txt |

All three: authenticated callers only, `application/json` required so a cross-site form post cannot
reach them, per-user rate limiting on the expensive actions, and generic errors that leak no
upstream detail.

### `scheduler/`

`SiteScanJob` is a Quartz `BackgroundJob`; `ScanScheduler` installs one cron trigger per site and
language. Quartz persists triggers, so a schedule survives a restart without re-registration.
`isProcessingServer()` keeps a cluster from scanning the same site on every node.

### `util/`

`PublicUrls` builds a page's public address by asking Jahia rather than guessing: `node.getUrl()`
then the outbound URL rewriter, which yields the vanity URL where one resolves. `MockHttp` supplies
a request and response as dynamic proxies, because the rewriter needs them and a scheduled job has
neither.

## Storage

One hidden `nt:unstructured` node per site, `geo-readiness`, carrying `jmix:nolive` so it never
publishes. A child node per language holds the run record and results.

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
