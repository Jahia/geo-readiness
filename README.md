# GEO Readiness

**Can AI crawlers actually read this page?**

A Jahia jContent UI extension (OSGi/Maven, React 18, Webpack + Module Federation) that adds a
**GEO readiness** action on pages. It answers a question nothing in jContent can answer today:
when GPTBot, ClaudeBot or PerplexityBot ask for this page, what do they actually get?

This is Wave 1 of the GEO work. It needs **nothing** from any external vendor.

## Why it cannot be done in the browser

`page-audit` renders a page in an iframe using the editor's own session. That is exactly the
wrong lens for this question. The editor is logged in, runs JavaScript, and is never challenged
by the firewall. A crawler is none of those things.

So the check runs **server side**: one fetch per bot user agent, no cookies, no session, and it
reads the **initial HTML** rather than a rendered DOM. A page whose content only appears after
JavaScript runs looks perfect to an editor and empty to a crawler.

## What it reports

For each of nine user agents, starting with a normal browser as the control:

| | |
|---|---|
| **Status** | The HTTP code the crawler receives. A 403 is a firewall or bot-protection rule, not a content problem. |
| **Time and size** | How long the fetch took and how much came back. |
| **Words** | Words of real text in the initial HTML, scripts and styles stripped. |
| **Initial HTML** | Whether H1, meta description, canonical and structured data are present before JavaScript runs, plus the link count. |

It also reads six signals that cost no extra request, because the HTML is already in memory: the
declared page language and its hreflang alternates, the sub-heading outline, alt-text coverage,
which schema types are declared rather than merely how many, and whether a modification date is
published.

Then a verdict in plain language: every crawler can read it, some are blocked, some receive far
less text than a browser, or, worst of all, even the browser receives an empty shell. That last
case is a page whose content only exists after JavaScript runs or after a client-side redirect. It
returns 200 to everyone, so a status-only check would call it healthy. It is the case this module
was built to catch.

A page that has never been published says so, rather than showing an error. There is no public
URL to test until it is live.

## The score

A count of checks, never a rating out of 100. Seventeen checks in three groups, each reading one
fact already in the report and each shown next to that fact, so an editor can disagree with a
specific line rather than with a number they cannot see inside. Three severities: critical means
an AI crawler cannot read the page at all, important means it can but materially less well, and
advisory is worth doing rather than worth blocking on.

One rule is deliberately absent. Being disallowed in `robots.txt` is **not** counted as a failure,
because refusing a crawler is a legitimate decision. What is counted is a disagreement between the
policy and what the server actually does, since one of the two is then wrong and somebody owns the
fix.

## Generating llms.txt

The module can build an `llms.txt` from the site's own published page tree, and this is the only
part of it that writes.

Generation is deterministic. Site title, site description, then one section per part of the page
tree, each page listed with its title, its public URL and its own `jcr:description`. No model call
and no external service, so the same site produces the same file every time and an editor can
predict the output. Pages hidden from the navigation are left out, descriptions are never invented,
and pages that exist only in the editing workspace are ignored because `llms.txt` describes the
public site.

The write is never silent. Generate shows the text next to what is currently stored, the apply
button needs a second click to confirm, and what gets written is exactly the text on screen, so
hand-edits made before applying are kept rather than regenerated over. Applying adds the
`jmix:llms` mixin if the site lacks it, sets `j:llms`, and publishes, because the community `llms`
module serves that property from the live workspace.

## Controlling AI crawlers in robots.txt

The same generate, review, apply loop, but for the crawler policy. Each of the fifteen AI crawlers
gets an allow or block choice, and the module merges those choices into the site's existing
`robots.txt` rather than replacing it.

The two choices are not symmetric, on purpose:

- **Block** replaces that crawler's rules with a site-wide `Disallow: /`. Destructive, and meant
  to be.
- **Allow** only undoes a site-wide refusal. A crawler carrying path rules such as
  `Disallow: /docs/` already allows the crawler, just not everywhere, and flattening that to
  `Allow: /` would throw away the operator's work.

Everything the module was not asked about is left byte for byte as it was found: the wildcard
group, comments, `Sitemap:` lines, crawl delays, and any group naming an agent it does not manage.
A crawler that shares a group with others is moved into its own group so the others keep their
rules. Merging is stable, so applying the same choices twice produces the same file and no
phantom diff.

Both files show a highlighted line diff before anything is overwritten, and the apply button needs
a second click to confirm. Re-running the check afterwards shows the policy change in the report,
which is the fastest way to prove the edit did what was intended.

## Build and deploy

Requires Java 17 and Maven 3.6+. Node 22 and Yarn 1 are downloaded by the build itself
(`frontend-maven-plugin`), so nothing else needs to be installed. `yarn.lock` is committed and must
stay committed: without it a fresh resolution pulls in packages that need a newer Node than the one
pinned in the pom.

```bash
mvn clean install
# then deploy target/geo-readiness-<version>.jar via the module manager, or:
curl -s --user root:root --form bundle=@target/geo-readiness-1.0.0-SNAPSHOT.jar \
     --form start=true http://localhost:8080/modules/api/bundles
```

Enable the module on the target site (Administration > Modules). Both entry points guard with
`requireModuleInstalledOnSite`, so neither appears otherwise.

## Two entry points, two scopes

| Where | Scope | What it does |
|---|---|---|
| **Page drawer**, the GEO readiness action on `jnt:page` and `jmix:mainResource` | One page | Runs the crawler check, scores the page, reports what robots.txt and llms.txt say about it |
| **Additional > SEO > GEO readiness** | The whole site | Edits robots.txt and llms.txt |

The split follows the data. A page drawer that edited `robots.txt` was changing the same site-wide
file whichever page happened to be open, which is the wrong scope and made the drawer crowded. The
drawer now reports on those files and points at the settings page; the settings page owns the
edits, and sits beside Robots.txt and Sitemap where a site administrator already looks.

## Configuration

Edit `digital-factory-data/karaf/etc/org.jahia.se.modules.georeadiness.cfg` at runtime. Changes
apply immediately, no redeploy.

| Key | Default | What it does |
|---|---|---|
| `FETCH_TIMEOUT_MS` | 8000 | Per-fetch timeout. Nine agents are tried, so the worst case is nine times this. |
| `MAX_BODY_BYTES` | 1500000 | How much of a page to read. |
| `RATE_MAX_CALLS` / `RATE_WINDOW_MS` | 20 / 600000 | Per-user rate limit. Each check makes several outbound requests. |
| `CRAWLER_AGENTS` | *(blank)* | Override the agent list as `name\|user-agent` pairs. Blank uses the built-in list. |
| `PUBLIC_BASE_URL` | *(blank)* | Force the base URL when the site's server name is not resolvable from inside the container, e.g. `http://localhost:8080`. |

## Architecture notes

- `CrawlerCheckServlet`, OSGi whiteboard alias `/geo-readiness/crawler-check`, reachable at
  `/modules/geo-readiness/crawler-check`. Same pattern as `page-audit`'s `AiReviewServlet`.
- `GeoReadinessConfigService`, `configurationPid org.jahia.se.modules.georeadiness`, with
  `@Activate` and `@Modified` for live reload.
- Hardened like page-audit: authenticated users only, the caller must be able to read the node
  in `live`, JSON content type required so a cross-site form post cannot reach it, per-user rate
  limit, and generic errors that do not leak upstream detail.
- **The tested URL is built by Jahia, not guessed.** `node.getUrl()` gives the render URL and
  `UrlRewriteService.rewriteOutbound()` turns it into what the rendered page itself prints for a
  visitor: vanity URL when one resolves on that host, `/cms/render` and site key dropped when the
  server-name rules allow it. One trap: an OSGi servlet request reports `/modules` as its context
  path and the rewriter prepends it, so the servlet swaps that for the real webapp context path.
- Redirects are **not** followed. A 301 is reported with its `Location`, because that is what the
  crawler sees.
- Results cache in `localStorage` per page and language, schema-versioned. Bump `CACHE_SCHEMA`
  whenever the report shape changes.
- React stays on 18. It is a Module Federation singleton shared with jContent.
- The pom declares **no dependencies**. The `jahia-modules` parent provides the whole compile
  classpath: jahia-impl, the servlet API, org.json, the OSGi annotations and slf4j.

## Known gaps

- **Vanity URLs are host-dependent by Jahia design.** The rewriter only emits a vanity URL when the
  request's server name resolves to the page's site. On a local instance where many sites share
  `localhost`, only the default site gets vanity URLs, and the report shows the `/sites/<key>/...`
  form instead, which is the form that actually works on that host. On a production host with a
  proper server name the vanity URL is used. Verified on 8.2.3.2: site path form resolves with 200,
  vanity form is emitted only when the host matches.
- **`llms-full.txt` cannot exist on this stack.** The community `llms` module declares one mixin
  with one property, `jmix:llms` / `j:llms`, and one servlet. There is no second property and no
  second route, so no amount of editing will produce an `llms-full.txt`. The report says the file
  is absent, which is true but reads as a to-do; it is really unsupported by the installed module.
- **The robots.txt merge is line-based, not a full RFC 9309 rewrite.** It understands groups,
  `Allow`, `Disallow` and shared `User-agent` lines, which is what the AI-crawler decisions need.
  It does not reorder, deduplicate or tidy a file, because a robots.txt an operator wrote by hand
  should come back recognisable.
- **robots.txt matching is a best effort, not RFC 9309.** Crawlers disagree on the details. We
  follow what the major ones do: consecutive `User-agent` lines share a group, the most specific
  matching group wins, the longest matching rule wins within it, `Allow` beats `Disallow` on a
  tie, and `*` and `$` are honoured. The matched rule is always shown so a human can check the
  reasoning rather than trust a bare yes or no.
- The check fetches a page even when robots.txt disallows it, on purpose. Knowing that a
  disallowed page is nonetheless reachable is a finding, not an accident. It also means this
  module is not itself a well-behaved crawler, which is fine for a handful of requests against a
  site you own.
