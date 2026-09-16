# GEO Readiness - agent harness

Wave 1 of the GEO work. **Track 2 OSGi UI extension**: Maven bundle, React 18, Webpack +
Module Federation, `@jahia/ui-extender`. Modelled on `page-audit`, which is the reference
module for this pattern. Read that module's `.agents/README.md` too, most of its traps apply.

## Invariants

- React **18** only. It is a Module Federation singleton shared with jContent. Never import
  React 19 APIs.
- Build with `mvn clean install` (Java 17). Never run `yarn webpack --watch` from an agent.
- Webpack output goes to `src/main/resources/javascript/apps/`, cleaned by `maven-clean-plugin`.
- Bundle symbolic name is `geo-readiness`. The action's `requireModuleInstalledOnSite: ['geo-readiness']`
  depends on it. Do not rename one without the other.
- Translations are bundled JSON at `src/main/resources/javascript/locales/{en,fr}.json`, registered
  synchronously in `init.js`. **Every new string needs both EN and FR.**
- **Build wiring that a fresh clone gets wrong.** `frontend-maven-plugin` must be declared in the
  pom, the parent's `yarn.arguments` alone runs nothing. Node is pinned to 22 because an unpinned
  resolution pulls `graphql@17`, which refuses Node 18. `@jahia/webpack-config` has no `index.js`:
  require `@jahia/webpack-config/getModuleFederationConfig` directly. `react-redux` and `redux`
  are host-provided and go in the Module Federation `shared` block as `{singleton, import:false}`.
  `CopyWebpackPlugin` must copy `package.json` into `javascript/apps/`, app-shell finds the remote
  through its `jahia.remotes` key. Check the jar has `javascript/apps/remoteEntry.js` before deploying.
- The pom declares **no dependencies**. The parent provides jahia-impl, servlet API, org.json,
  OSGi annotations and slf4j. Adding a dependency means embedding it, which needs
  `Embed-Dependency` in the bundle plugin.
- The `.cfg` first line must be exactly `# default configuration - won't be overridden`. Without
  it, every redeploy resets operator edits.

## Why the check is server side, not in the browser

This is the whole point of the module and it must not be "simplified" later. A browser cannot
set a `User-Agent`, and the editor's session makes the answer meaningless anyway. `page-audit`
audits what the *editor* sees. This module audits what a *crawler* sees. Different question,
different mechanism.

Concretely, the servlet must keep:
- no cookies and no session on the outbound fetch
- `setInstanceFollowRedirects(false)`, because a crawler sees the 301
- analysis of the **raw HTML string**, never a parsed and scripted DOM

## Traps

- **Public URL: never guess the shape, ask Jahia.** `/<lang><site-relative-path>.html` is a 404 on
  every non-default site, and every agent then happily reads the 404 page. The servlet does
  `node.getUrl()` then `UrlRewriteService.rewriteOutbound(url, req, resp)` (Spring bean
  `UrlRewriteService` via `SpringContextSingleton`). That is exactly what a rendered link looks
  like, vanity URL included when the host resolves to the site.
- **OSGi servlet requests report `/modules` as context path.** The rewriter prepends it faithfully,
  giving `/modules/sites/x/home.html`. `fixContextPath()` swaps it for `Jahia.getContextPath()`.
  Keep that call if the rewriter is ever touched.
- **The `robots` module (3.0.0) and jcontent cannot both run on a cold origin.** robots shares
  `@apollo/react-hooks` without `@apollo/react-common`, wins the equal-version election by `uniqueName`,
  and every legacy `useQuery` at boot throws `Invariant Violation: 1` -> white jcontent (seen the first
  time the UI was opened on `digitall.local.com`). It also owns `/robots.txt` (RobotsServlet + urlrewrite),
  so stopping it removes the fixture, and a static `webapps/ROOT/robots.txt` is not an immediate fallback: the module's
  urlrewrite rule (`/robots.txt` -> `/modules/robots/robots`) survives the bundle stop, so the path 404s until
  the next Jahia restart with robots still stopped. Options: rebuild robots with `@apollo/react-common` shared, or start
  robots only after jcontent has loaded on a warm origin. Same defect in `llms` 1.0.0, but its name sorts
  below personal-api-tokens so it does not win.
- **Test through the site's own host, or set `PUBLIC_BASE_URL`.** `robots.txt` is served from the
  site's `j:robots` property and resolved by server name, so `http://localhost:8080/robots.txt` is
  the *default* site's file (404 on this box), while `http://digitall.local.com:8080/robots.txt` is
  digitall's. `baseUrl()` follows the same logic: request host equals the site's `j:serverName` ->
  reuse the request's scheme and port; request host differs and the site has a real server name ->
  `https://<serverName>`, which on a dev box is a `ConnectException` for every agent. That is
  the edit-host-vs-public-host production case, not a bug. Add `127.0.0.1 <serverName>` to
  `/etc/hosts` and open jContent through that host. The file is `robots.txt`, plural.
- **Vanity URLs only resolve when the request host maps to the site.** With many sites on
  `localhost`, digitall's vanity URL was live in JCR but the rewriter (correctly) kept the
  `/sites/digitall/...` form, because `/geo-about` on that host returns 400. Not a bug. Test vanity
  behaviour on a site whose `j:serverName` matches the host.
- **A 200 with one word is the worst result, not a pass.** `mysoprahr` home answers 200 to every
  agent with a 3 kB shell and a single word. The ratio test never fires because the control is
  empty too. `MIN_CONTROL_WORDS` in `CrawlerTab` produces the "empty shell" verdict for that. Do
  not remove it to make a demo look green.
- The node is resolved in the **live** workspace on purpose. Absent from live means never
  published, which the UI reports as a state, not an error.
- **One crawler list, `AiCrawlers`.** The servlet used to fetch eight agents while the robots
  checker evaluated fifteen tokens, so seven crawlers had a policy nobody had verified. Both sides
  now read the same registry. Adding a crawler means adding it there, and nowhere else.
- Sixteen fetches at up to 8s each would be over two minutes sequentially, so they run three at a
  time and are consumed in order. Do not raise the concurrency to make it faster: parallel fetches
  from one server look like an attack to some WAFs. If it needs to be faster, cap concurrency at
  two or three, do not remove the timeout.
- `analyse()` strips `script`, `style`, `noscript` and `template` before counting words. Without
  that, a JS-heavy page looks content-rich because of its inline scripts.
- The control agent must stay **first** in the map. `controlWords` is taken from the first agent
  that returns 200, and every "thin content" comparison is made against it.
- Rate limiting is per user key with a sliding window, copied from page-audit. Each *check* is
  sixteen outbound requests, so the limit is deliberately lower than page-audit's.

- **`isdescendantnode()` excludes the node itself.** `SiteScorer.publishedPages` scoped to a path
  an editor typed skipped that page, so scope `/sites/x/home` silently scanned everything except
  home, and a scope naming a leaf page reported `0 / 0 pages` with status `done`. The query needs
  `issamenode()` as well. Any scope feature written with `isdescendantnode` alone has this bug.
- **A `JCRNodeWrapper` is useless once its session closes.** `SitemapCheck` first stored nodes
  collected inside a guest-session callback and read them afterwards. `isNodeType()` threw, the
  catch returned `false`, and the check silently reported zero stale dates and zero `noindex`
  entries - a pass that looked like good news. Do the reads inside the callback and return a record
  of plain values, never the node.
- **Prove a check detects disagreement, not just that it reports agreement.** The sitemap
  comparison reached a clean 378/378 and every drawer page answered `missing: false`, which proves
  nothing: a check hardcoded to `false` scores identically. Publishing a page the current sitemap
  cannot know about is what proved it. Note the cleanup order below.
- **Deleting a published page: order matters, and `markNodeForDeletion` is not the mutation name.**
  It is `markForDeletion`. Calling `publish` before marking republishes the page instead of
  publishing its deletion, and `deleteNode` then removes it from `default` only - leaving a node
  that exists in `live` alone, reachable at its URL, with no default node left for `unpublish` to
  act on. Recovery is a system live session in the Groovy console. That console needs the
  `toolAccessToken` hidden field from a freshly fetched page, posted with the script in `script`
  and `runScript=true`; without the token it silently runs the sample script instead.

- **jContent has two sections, and a content node sent to the pages section lands nowhere.**
  `/sites/<key>/contents/...` belongs under `content-folders`, everything else under `pages`. The
  sitemap findings are mostly `jmix:mainResource` content on a site whose articles are not pages,
  so a link builder that only knows `pages` produces mostly broken links. `search-and-replace`'s
  `buildParentLink` is the reference implementation.
- **A per-page finding taken from a site-wide scan must match on language too.** The stored scan
  covers every language, so the same node appears once per URL. Matching on `jcrPath` alone showed
  the French page's stale date on the English page. Verified with a page stale in French only: it
  must stay silent in English.

- **"200 and it contains a tag" does not mean the file is what you asked for.** `SitemapCheck`
  accepted any 200 containing `<`, so an HTML page answering `/sitemap.xml` - a proxy catch-all, a
  vanity URL, an error page served with a success code - would parse to zero entries and report
  **every published page as missing**, in the dashboard and in every page drawer. Require
  `<urlset` or `<sitemapindex`. The llms.txt check already had the equivalent guard; this one did
  not. Verified against the real bytes: Jahia's own HTML error page passes the old predicate and
  fails the new one, while both real sitemap formats still pass.
- **Absence has more than one cause, and they need different messages.** Nothing serving the
  address, something serving it that is not a sitemap, and not being able to reach it at all are
  three different things to go and fix. One shared "no sitemap" banner sends people to the wrong
  one.

- **Navigation is usually not in the repository.** On the test site the main menu is built from
  the page tree and holds no link nodes, so a link graph from weak references alone called every
  page in the menu an orphan. The rendered HTML is what a crawler follows and the only honest
  source; the scan already fetches it, so it costs nothing.
- **Do not count a reference that the HTML already counted.** The footer menu is stored as link
  nodes *and* rendered as anchors. Counting both added a menu entry to the content total, so a page
  linked only from the footer menu stopped being reported as nav-only. The reference pass must skip
  anything living on a page the scan rendered.
- **A per-language scan must not judge pages of other languages.** `PublishedMap` spans every
  language because a sitemap does. The link graph does not: it saw only the scanned language's
  pages, so every French page came out with `content: 0` and was reported weakly linked. Filter
  findings to the scanned language.
- **Exclude the site home page from link findings.** Every menu and every logo points at it, so it
  is either trivially fine or unfixable, and reporting it is pure noise.
- **Bump `CACHE_SCHEMA` in the drawer whenever the report grows a field.** It is at 8. A stale
  cached report is restored into a UI that expects the new field and the new banner silently never
  appears - which reads as "the feature does not work". This has now cost time twice.

- **A default vanity URL is the page's address; the tree path 301s to it.** `PublicUrls` returned
  the tree path and the outbound rewriter did not substitute the vanity even with the site's own
  host in the request. Because the fetch does not follow redirects, every crawler got the 301: the
  page scored 7/18 with "no text in the initial HTML", the sitemap comparison called it missing,
  and the link graph could not match links to it. Read `vanityUrlMapping` for the `j:default`
  active alias in the node's language and prefer it. One three-line fix, three features corrected.
- **Jahia will not store two vanity URLs with the same address.** Saving a second `/blogs.html`
  silently renamed it `/blogs-1.html`. That is where the `-1` suffixes on imported sites come from,
  and it is why there is no collision check: it could never fire.
- **The sitemap module does not use vanity URLs, but `llms.txt` (via `PublicUrls`) now does.** So a
  site with vanity URLs shows the page under its vanity address in one file and its tree path in
  the other. Worth knowing before reading a sitemap disagreement as a bug in the comparison.

- **You cannot ask `PublicUrls` for a page's non-vanity address.** `preferVanity=false` skips our
  own lookup, but the outbound rewriter then substitutes the vanity url anyway when the request is
  the mock one the scan uses. Resolve from the path side instead: `PublishedMap.movedFrom` turns a
  listed address back into a node and only accepts the guess when the repository confirms it. This
  cost a full build-deploy-scan cycle reporting zero findings that should have been two.
- **`SiteFilesChecker` stores `rawBody` for robots.txt only.** The llms.txt branch did not, so a
  check that read it silently saw an absent file and reported "not outdated" - a pass that looked
  like good news. Same class as the `JCRNodeWrapper` trap: a missing input became a clean result.

- **A `put` that silently drops a value is invisible in a valid result.** `StructuredData.put`
  returned early on `offers`, so every Product came out without its price - and still reported
  `valid: true`, because the name and image it did emit were all the type required. Nothing looked
  wrong. Check generated output against a node you know has the property, not just against the
  validity flag.
- **`jmix:seoHtmlHead` carries no title property.** It is `seoKeywords` and `openGraphImage` only,
  so on a stock Jahia site the rendered `<title>` always derives from `jcr:title` and the
  schema-versus-page contradiction check cannot be made to fire end to end. Verify that comparison
  at the logic level and say so.

- **Never build a CSS selector from a CSS-module class name.** The production `localIdentName`
  is base64 and can end in `=` - `k3ZrYCe10X7fGEsp-P8wFg==` - which is not a valid selector, so
  `el.closest('.' + styles.chart)` threw a SyntaxError inside the hover handler and no tooltip ever
  appeared, on any chart, with no console noise a user would see. Anchor on a data attribute
  (`[data-geo-chart]`) instead. Found by calling the React prop directly off `__reactProps$`, which
  is the fastest way to tell "handler not attached" from "handler throws".
- **A tone class must out-rank every mark's default background.** `.series2`, `.fillWarn`,
  `.segmentRest` were single-class rules; `.swatch`, `.meterFill`, `.range` and `.marker` are
  single-class too and declared later, so at equal specificity the default won and both series
  rendered in one color - the legend and bars alike. Scope tones as `.chart .series2`: two classes
  beat one regardless of source order.
- **Automation hover and programmatic `focus()` are not a test of the tooltip.** The MCP hover
  did not dispatch React's `mouseover`, and `.focus()` is silent when `document.hasFocus()` is
  false, which it is in a driven tab. Dispatch `MouseEvent('mouseover', {bubbles:true})` and
  `FocusEvent('focusin', {bubbles:true})` and read `[role="status"]` back.

- **The locale JSON is bundled, so trailing garbage in it fails the whole build - silently under
  `mvn -q`.** Appending `'\\n'` (a literal backslash-n) instead of a newline left `}\n` at the end
  of both `en.json` and `fr.json`; `yarn build:production` exited 1, Maven reported only "failed to
  run task", and a `grep ERROR` filter showed nothing useful. Run `yarn -s build:production`
  directly to see webpack's own message. When appending to a JSON file from Python, write `"\n"`
  from a normal string, and re-`json.load` the file afterwards as the check.
- **The failure matrix needs check severities on the aggregate.** Rows carry failed check ids
  only; severity lives in each page's full `checks` array, which the scan trims away. `SiteScorer`
  records `id -> severity` once per scan in `aggregate.severities` - it is the same eighteen for
  every page, so once is enough. A stored scan from before that field has no colors: rescan.

- **`set -e` does not protect a release chain in this shell.** A Python step failed mid-way
  (`substring not found`) and the chain carried on into the build, tag and GitHub release. It
  happened to be harmless - the failed step was a docs edit nothing downstream needed - but a
  failed version bump would have tagged and published the wrong number. Chain release steps with
  `&&`, put the edits whose failure must stop everything *last* in their Python script, and read the
  output before trusting the tag.

## robots.txt traps

- **The path evaluated against robots.txt must include the query string.** Rules like
  `Disallow: /*?reply=` never match a bare path. This was a real bug, caught by porting
  `RobotsRules.matches()` to JS and testing it against jahia.com's actual rules. Fifteen cases
  live in that throwaway test; if you touch the matcher, rebuild them first.
- `RobotsRules` has **no Jahia dependencies on purpose**. It is pure `java.util`, so it can be
  compiled and unit-tested on its own. Keep it that way. It was the FIRST piece testable
  without a running Jahia and is no longer the only one: `GeoScore`, `PageFetch`, `LinkGraph`,
  `LlmsFreshness`, `RobotsEditor`, `FetchGuard`, `SiteScope` and `GuestVisibility` all have unit
  tests now, several stubbing a node with Mockito.
- Robots matching is not standardised in practice. We always return the **matched rule** next to
  the verdict, so a human can disagree with us. Never show a bare allowed/disallowed.
- A missing robots.txt means everything is allowed. Report that as a finding, not as an error.
- `/llms.txt` that returns HTML is the common false positive. Sites with catch-all routing serve
  their 200 homepage for any unknown path. Check the body and the content type, not just the
  status code.

## Documentation

Ten or so files, each with one job. `README.md` is the front door: what it does, the two entry points,
configuration, known gaps. `docs/checks.md` is the reference for all eighteen checks.
`docs/architecture.md` is the shape of the code. `CHANGELOG.md` carries the behaviour notes that
explain choices the code cannot explain by itself. This file is the invariants and the traps.

Every factual claim in them — check counts, crawler counts, thresholds, endpoint aliases, config
keys — is checkable against the code, and was checked rather than remembered when written. A
number in a README that drifted from the code is worse than no number, because it is believed.

## Two entry points

- `GeoReadinessAction` -> portal drawer, registered on `headerPrimaryActions:890`. **Page scope.**
- `GeoDashboard` -> admin route `siteSettingsSeo/geoReadiness` on `jcontent-siteSettingsSeo:80`,
  which is Additional > SEO. **Site scope.** Same target the `robots` and `sitemap` modules use
  (both at `:75`), so the three sit together.

The registration shape for the settings page, copied from `robots` and `sitemap`:

```js
registry.add('adminRoute', 'siteSettingsSeo/geoReadiness', {
    targets: ['jcontent-siteSettingsSeo:80'],
    label: 'geo-readiness:dashboard.navLabel',   // an i18n key, not a literal
    isSelectable: true,
    requiredPermission: 'publish',
    requireModuleInstalledOnSite: 'geo-readiness',
    render: () => <GeoDashboard/>
});
```

The component reads its context from the store, `state.site` and `state.language`, and sends
`/sites/<siteKey>` as the path. The servlet resolves the site from any path under `/sites/`, so a
site node works exactly as a page path does.

**`doExecuteWithSystemSessionAsUser` does NOT enforce ACLs.** It is a system session merely
attributed to a user, so it reads everything. `GuestVisibility` used it first and reported every
page readable, which would have shipped a permissions check that could never find anything. Use
`JCRTemplate.doExecute(user, workspace, locale, callback)` when the question is what that user can
actually see. Proven on 8.2.3.2 against a page with ACL inheritance broken: the system variant
read it, `doExecute` threw `PathNotFoundException`, and an anonymous HTTP request got a 404.

**Restricting a page means breaking ACL inheritance, not adding a DENY.** A `DENY` ace for
`g:guest` on a page changed nothing: guest still read it, because its read comes from an inherited
grant higher up and Jahia did not let the local deny override it. `setAclInheritanceBreak(true)`,
which is what jContent's restrict-access does, is what actually closes a page. Test fixtures must
use that, or they prove nothing.

**Header pattern.** Settings panels title themselves after the thing they act on, the way
site-settings-seo does (`"<label> - <site displayName>"`). Ours reads *GEO readiness for site
{{site}} - {{language}}*, with the site's **displayName** from `useSiteInfo` rather than the site
key, and the language as its own display name plus a flag. `useSiteInfo({siteKey, displayLanguage,
uiLanguage})` comes from `@jahia/data-helper` and returns `displayName` and a `languages` array
carrying `displayName` / `uiLanguageDisplayName` per language. Redux supplies all three inputs:
`state.site`, `state.language`, `state.uilang`.

**Flags are derived, not looked up.** Moonstone ships no flag icons. `util/languageFlag.js` builds
the emoji from the locale's region subtag when it has one (`fr_BE` -> BE), else from a small
language-to-country default map, and returns null when it cannot answer. A flag is a country and a
locale is a language, so this is a convention: no flag beats the wrong flag, which is why the
unknown case renders the name alone.

**A background job has no HTTP request, and Jahia's URL rewriter needs one.** `util/MockHttp`
builds a request and response as dynamic proxies so `PublicUrls` works from a scheduled scan. The
sitemap module solves the same problem with two hand-written mock classes. The mock must answer
`getAttribute` and `setAttribute`, because the vanity URL rule stores what it finds in a request
attribute.

**Quartz reads cron in the server's timezone, not yours.** A trigger tested with a locally
computed expression fires hours later and looks broken. The container here runs UTC.

**Never rate limit the endpoint a UI polls.** The scan status share the servlet with the scans
themselves, and a long scan plus a four-second poll blew the limit in a minute, which the
dashboard would have shown as a failure. Only the expensive actions are limited now.

**Bump `CACHE_SCHEMA` in the drawer whenever the report grows a field, not only when it changes
shape incompatibly.** The symptom is nasty: the API returns the new data, the code is correct, and
the feature is invisible to every person who had opened the drawer before, because their cached
report predates the field. Cost one round trip of "it does not show up" on the noindex notice.

**A `useEffect` cannot reference a `useCallback` declared below it.** The dependency array is
evaluated during render, before the `const`, so it throws `Cannot access 'x' before initialization`
and the panel never renders. Shipped once, in the robots panel's auto-run effect. Declare
callbacks before the effects that use them.

**Never put an interactive Moonstone control in a Moonstone table cell.** A `Switch` is a 38x20
box whose two children are both `position:absolute`, so it has no in-flow content and collapses to
nothing inside the `Typography` that `TableCell` wraps its children in. The robots stance column
rendered completely empty and the switches were simply invisible. That is the third failure from
the same cause, after the clipped score explanations and the overlapping crawler marks. Treat
`Table` as a display grid for short text and nothing else.

**Moonstone `Table` rows are a fixed height.** 48px, or 64px with
`hasMultipleLines`, and `TableBodyCell` sets `overflow-y: auto`, so anything taller is
scroll-clipped rather than wrapped. That makes `Table` right for short tabular values and wrong
for explanatory prose. `TableCell` also wraps its children in a `Typography`, so a nested wrapping
flex overlaps the line above rather than pushing it down. Both symptoms bit us: the score's
per-check explanations were cut off, and the crawler's markup indicators overlapped. Both are now
plain `<ul>` lists that wrap, still built from Moonstone `Chip` and `Typography`. `Table` survives
only where every value is short and fits one line: the robots stance grid and the site-files
report. Check any new column against the 48px budget, and against that Typography wrapper, before
reaching for `Table`.

**Borrow the interaction patterns from the modules that already shipped them.** A cron expression
gets a dropdown builder, copied in shape from `jcustomer-sfdc-connector`'s `CronBuilder`, because
nobody should have to know Quartz syntax to say "every night at three". A repository path gets
`window.CE_API.openPicker({type:'editorial', ...})`, as `importContentFromJson` does, rather than a
text box someone has to type a path into correctly. Every input sits in a Moonstone `Field` with a
label and a helper. **`LayoutContent` paints its ground on an INNER div, not the one your `className` reaches.** It
renders a wrapper (which takes your class) around a scrolling div carrying `moonstone-layoutContent`,
and that inner one is grey. Styling the wrapper does nothing visible. Worse, putting a background
on your own content with `min-height: 100%` paints exactly one viewport of a scrolling area, so a
long page goes white at the top and grey further down, which is what shipped once. Target it as
`.yourClass :global(.moonstone-layoutContent)`; the plain class is emitted alongside the hashed
module one, so a global match is safe.

**Attributing a finding to a template is a statistical claim, so it is made cautiously.**
`TemplateRollup` blames a check on the template only at 90% of pages or more, over at least three
pages. The asymmetry is deliberate: a missed roll-up costs one person some time, a wrong one sends
them to edit a shared template over somebody else's typo and costs the report its credibility.
Both thresholds are named constants; do not loosen them to make a demo show more.

**Two scores for one page must explain themselves.** The site scan drops `sameContentForCrawlers`
because it needs a second fetch, so a page reads 15/17 on the dashboard and 16/18 in the drawer.
Both report the same failures, but nobody can know that by looking. Any future check that is
evaluated in one surface and not the other has to be named where the smaller number appears.

**A white ground costs you the Header's shadow.** `Header` already carries `box-shadow: 0 1px 8px`,
but the scrolling content div is its next sibling and paints its own background over it. Against a
grey ground the contrast hid the loss; on white the header simply has no edge. Add
`position: relative; z-index: 1` to `:global(.moonstone-header)` so the shadow paints on top.

Panels sit on one white ground, not on cards: Jahia's settings panels are a
single white surface, and boxing each section made the page feel cramped. `Separator` with
`spacing="big"` carries the structure instead, which is what it is for. The only fills left are
semantic, the amber and red of a verdict; neutral information uses a left rule on white.

**Use Moonstone, not raw markup.** The settings page is `LayoutContent` + `Header`, the same shape
as the other Additional panels, with `Tab` / `TabItem` in the header's `toolbarLeft` slot to keep
robots.txt and llms.txt apart. Inside, tables are `Table` / `TableHead` / `TableBody` /
`TableRow` / `TableHeadCell` / `TableBodyCell`, states are `Banner` (it needs both a `title` and
children), badges are `Chip`, the per-crawler choice is a `Switch`, and the editable file is
`Textarea`. The only raw element left on purpose is the `<pre>` holding the diff, because a
monospaced block is the right element and Moonstone has no equivalent.

Two gotchas found while converting: `Tab`'s `TabItem` is exported from the package root even
though `components/Tab/index.d.ts` does not list it, and `TabItem` has no badge slot, so a count
goes in its `icon` prop as a `Badge`. Also beware i18n key collisions: `score.check` is a
namespace holding `.label` and `.fix`, so a column header cannot reuse that key. It is
`score.checkColumn`.

**Keep the scopes apart.** Anything that edits a site-wide file belongs on the settings page. The
drawer answers one question about one page, and every control added to it has to earn its place
against that sentence.

## Layout

```
src/javascript/
├── index.js                        # jahiaApp-init:50 callback
├── init.js                         # translations + action AND admin route registration
└── GeoReadiness/
    ├── GeoReadinessAction.jsx      # useNodeChecks -> portal drawer
    ├── GeoReadinessDrawer.jsx      # run, cache, error states
    ├── api/crawlerCheck.js         # fetch to the servlet, same-origin JSON
    └── tabs/
        ├── CrawlerTab.jsx          # per-agent table, verdict, policy/reality mismatches
        └── SiteFilesTab.jsx        # robots.txt and llms.txt

src/main/java/org/jahia/se/modules/georeadiness/
├── check/RobotsRules.java          # robots.txt parser and evaluator, no Jahia deps
├── check/SiteFilesChecker.java     # fetches robots.txt, llms.txt, llms-full.txt
├── config/GeoReadinessConfigService.java
└── servlet/CrawlerCheckServlet.java
```

## The report / write line

Reporting is safe to run against any site. Writing is not. Keep the two visibly apart: the crawler
check and the score only read, and the one write path (`SiteFilesServlet`) generates, shows, and
waits for a second confirming click before it stores anything. `applyLlms` writes **exactly** the
text it is handed and never regenerates, so an editor's hand-edits survive.

## Site file storage, verified from the deployed jars

Both community modules are the same shape, which is why the two write features are symmetric:

| Module | Mixin | Property | Autocreated default |
|---|---|---|---|
| `robots` 3.0.0 | `jmix:robots` | `j:robots` (string, textarea) | `User-agent: *` |
| `llms` 1.0.0 | `jmix:llms` | `j:llms` (string, textarea) | `# Title` |

Each ships one servlet that reads the property off the site via `JahiaSitesService` and serves it
as `text/plain`, plus a `last-urlrewrite-*.xml` rule routing `/robots.txt` or `/llms.txt` to it.
Consequences worth knowing:

- **Writing means: add the mixin if absent, set the property, then publish.** The servlets read
  live. An unpublished change leaves the served file unchanged and looks like the write failed.
- **The autocreated default is not a file.** A site carrying `jmix:llms` with `# Title` reports as
  "has an llms.txt" unless you check for the placeholder explicitly.
- **`llms-full.txt` is unsupported**, not missing. There is no property and no route for it.

## Generator rules

`LlmsGenerator` walks the LIVE tree. Sections come from the page tree, not from a fixed list.
The trap: a section such as `legal` or `landing-pages` is usually **`jnt:navMenuText`**, a grouping
node that is not itself a page. Looking only for `jnt:page` children of the site finds the home page
and silently drops every page underneath those groups. On luxe that was 6 of 15 pages missing.

Section headings use the node's own title in the requested language, never a hardcoded English
word, because the generated file is site content and must follow the site's language. The fallback
when a node has no title in that language is its humanised node name.

## robots.txt merging

`RobotsEditor` parses into blocks (raw lines, and groups of consecutive `User-agent` lines plus
their rules) and re-renders. Rules that took real work to get right, all verified against
`test-fixtures/robots.txt`:

- **Allow and block are not symmetric.** Block replaces the group's rules with `Disallow: /`.
  Allow acts *only* when the group blocks the whole site, so `Disallow: /docs/` survives.
- **Shared groups split.** `GPTBot` and `OAI-SearchBot` share one group in the fixture. Changing
  one moves it into its own group and leaves the sibling's rules alone.
- **Blank lines are separators, so never trim them.** An earlier version trimmed trailing blanks
  from a group, which meant a second merge differed from the first and the UI showed a phantom
  diff on an unchanged file. There is a stability test in the fixture run: apply twice, compare.
- **Empty decisions must return the file byte for byte.** Also tested. It is what makes the
  "nothing to apply" state trustworthy.

The diff shown before an overwrite is computed in the browser (`util/diffLines.js`, LCS with a
line cap) from the current and proposed text the server returns. The merge itself only ever runs
server-side, so what the editor sees is the file that would actually be written.

## Not built yet

Wave 1 is complete. Wave 2 candidates, in the order they are worth doing: run the check across
every site language rather than one at a time, and a site-level roll-up instead of one page at a
time. The roll-up is the better presales artifact but needs crawl budgeting.
