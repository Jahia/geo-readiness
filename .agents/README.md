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
- The `.cfg` first line must be exactly `# default configuration - won't be overriden`. Without
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
- Nine sequential fetches at up to 8s each is a slow request. It is deliberate: parallel fetches
  from one server look like an attack to some WAFs. If it needs to be faster, cap concurrency at
  two or three, do not remove the timeout.
- `analyse()` strips `script`, `style`, `noscript` and `template` before counting words. Without
  that, a JS-heavy page looks content-rich because of its inline scripts.
- The control agent must stay **first** in the map. `controlWords` is taken from the first agent
  that returns 200, and every "thin content" comparison is made against it.
- Rate limiting is per user key with a sliding window, copied from page-audit. Each *check* is
  nine outbound requests, so the limit is deliberately lower than page-audit's.

## robots.txt traps

- **The path evaluated against robots.txt must include the query string.** Rules like
  `Disallow: /*?reply=` never match a bare path. This was a real bug, caught by porting
  `RobotsRules.matches()` to JS and testing it against jahia.com's actual rules. Fifteen cases
  live in that throwaway test; if you touch the matcher, rebuild them first.
- `RobotsRules` has **no Jahia dependencies on purpose**. It is pure `java.util`, so it can be
  compiled and unit-tested on its own. Keep it that way, it is the only piece of this module
  that can be tested without a running Jahia.
- Robots matching is not standardised in practice. We always return the **matched rule** next to
  the verdict, so a human can disagree with us. Never show a bare allowed/disallowed.
- A missing robots.txt means everything is allowed. Report that as a finding, not as an error.
- `/llms.txt` that returns HTML is the common false positive. Sites with catch-all routing serve
  their 200 homepage for any unknown path. Check the body and the content type, not just the
  status code.

## Layout

```
src/javascript/
├── index.js                        # jahiaApp-init:50 callback
├── init.js                         # translations + action registration
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

## Not built yet

The remaining Wave 1 items: the GEO score over the criteria we compute natively, and the two
**write** features, generating the `llms.txt` body on top of the community `llms.txt manager`
module and writing named AI-crawler rules into `robots.txt`. This module reports only. Keep that
line clear: reporting is safe to run against any site, writing is not.
