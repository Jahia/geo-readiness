package org.jahia.se.modules.georeadiness.servlet;

import org.jahia.se.modules.georeadiness.check.AiCrawlers;
import org.jahia.se.modules.georeadiness.check.GeoScore;
import org.jahia.se.modules.georeadiness.check.PageFetch;
import org.jahia.se.modules.georeadiness.check.Languages;
import org.jahia.se.modules.georeadiness.check.LinkGraph;
import org.jahia.se.modules.georeadiness.check.LlmsFreshness;
import org.jahia.se.modules.georeadiness.check.PublishedMap;
import org.jahia.se.modules.georeadiness.check.ScanStore;
import org.jahia.se.modules.georeadiness.check.SitemapCheck;
import org.jahia.se.modules.georeadiness.check.StructuredData;
import org.jahia.se.modules.georeadiness.check.VanityUrls;
import org.jahia.se.modules.georeadiness.check.TemplateRollup;
import org.jahia.se.modules.georeadiness.check.GuestVisibility;
import org.jahia.se.modules.georeadiness.check.RobotsRules;
import org.jahia.se.modules.georeadiness.check.SiteFilesChecker;
import org.jahia.se.modules.georeadiness.config.GeoReadinessConfigService;
import org.jahia.se.modules.georeadiness.util.PublicUrls;
import org.jahia.se.modules.georeadiness.util.SiteScope;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * GEO-16, the crawler access check.
 *
 * GET  /modules/geo-readiness/crawler-check
 *      -> {enabled, agents:[names]}
 * POST /modules/geo-readiness/crawler-check   body {"path":"/sites/x/home","language":"en"}
 *      -> the full report
 *
 * Why this exists. page-audit renders the page in an iframe using the editor's
 * own session. That is the wrong lens for this question: the editor is logged
 * in, runs JavaScript, and is never challenged by the firewall. A crawler is
 * none of those things. So we fetch the PUBLISHED url from the server, once per
 * bot user agent, with no cookies, and we read the INITIAL html rather than a
 * rendered DOM.
 *
 * Hardening follows page-audit's AiReviewServlet: authenticated users only, the
 * caller must be able to read the node, same-origin JSON only, per-user rate
 * limit, and a whitelisted response.
 */
@Component(
        service = {HttpServlet.class, Servlet.class},
        property = {
                "alias=/geo-readiness/crawler-check",
                "allow-api-token=true",
                "service.description=GEO readiness per-page crawler check",
                "service.vendor=Jahia Solutions Group SA"
        },
        immediate = true)
public class CrawlerCheckServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(CrawlerCheckServlet.class);

    /**
     * One list for both halves of the check, from {@link AiCrawlers}. Fetching
     * eight agents while evaluating fifteen robots tokens left seven crawlers
     * with a policy nobody had verified, which is the gap this module exists to
     * close.
     */
    private static final Map<String, String> DEFAULT_AGENTS = AiCrawlers.userAgents();

    /**
     * Sixteen sequential fetches at up to eight seconds each is over two minutes.
     * Three at a time keeps the worst case near forty seconds while staying well
     * short of what a WAF reads as an attack. Do not raise this to "make it fast":
     * the timeout is the safety net, the concurrency is the compromise.
     */
    private static final int FETCH_CONCURRENCY = 3;

    private static final Pattern H1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>");
    private static final Pattern H2 = Pattern.compile("(?is)<h2[\\s>]");

    private final Map<String, Deque<Long>> callWindows = new ConcurrentHashMap<>();

    /**
     * Injected through the constructor rather than into the field, so a servlet
     * the container shares between threads holds nothing mutable.
     */
    private final transient GeoReadinessConfigService config;

    @Activate
    public CrawlerCheckServlet(@Reference GeoReadinessConfigService config) {
        this.config = config;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        try {
            if (isGuest()) {
                deny(resp, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
                return;
            }
            JSONObject out = new JSONObject();
            out.put("enabled", true);
            out.put("agents", new JSONArray(agents().keySet()));
            writeJson(resp, HttpServletResponse.SC_OK, out);
        } catch (IOException | RuntimeException e) {
            // Nothing may leave a servlet method: the container would answer with
            // a stack trace instead of a response. A broken socket, or a JSON
            // library that throws unchecked, both end here.
            logger.debug("could not write the crawler check status", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        try {
            dispatch(req, resp);
        } catch (IOException | RuntimeException e) {
            logger.debug("could not write the crawler check response", e);
        }
    }

    private void dispatch(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JahiaUser user = currentUser();
        if (user == null || JahiaUserManagerService.GUEST_USERNAME.equals(user.getName())) {
            deny(resp, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        // CSRF: same-origin JSON only. A form post or a cross-site request cannot set these.
        String ctype = req.getContentType();
        if (ctype == null || !ctype.toLowerCase().contains("application/json")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "json required");
            return;
        }
        if (!rateLimitOk(user.getUserKey())) {
            deny(resp, 429, "rate limit");
            return;
        }

        JSONObject body;
        try {
            body = new JSONObject(read(req.getInputStream(), 64_000));
        } catch (Exception e) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed body");
            return;
        }
        String path = body.optString("path", "");
        String language = body.optString("language", "en");
        if (path.isEmpty() || !path.startsWith("/sites/")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "path required");
            return;
        }

        if (!SiteScope.isLanguage(language)) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "language required");
            return;
        }
        // The drawer opens on a node the caller has selected in jContent, so the
        // floor is "edits this content", not "can read the published page". Read
        // it in the editing workspace first and let that decide.
        try {
            JCRSessionFactory.getInstance()
                    .getCurrentUserSession("default", Locale.forLanguageTag(language))
                    .getNode(path);
        } catch (javax.jcr.PathNotFoundException | javax.jcr.AccessDeniedException e) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
            return;
        } catch (RepositoryException e) {
            logger.warn("Could not resolve {} in default: {}", path, e.getMessage());
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "operation failed");
            return;
        }

        // The node must exist in LIVE and the caller must be able to read it.
        // Absent from live means never published, which is a real answer, not an error.
        String publicUrl;
        try {
            JCRSessionWrapper live = JCRSessionFactory.getInstance()
                    .getCurrentUserSession("live", java.util.Locale.forLanguageTag(language));
            JCRNodeWrapper node = live.getNode(path);
            publicUrl = publicUrlFor(node, req, resp);
        } catch (javax.jcr.PathNotFoundException e) {
            JSONObject out = new JSONObject();
            out.put("published", false);
            writeJson(resp, HttpServletResponse.SC_OK, out);
            return;
        } catch (Exception e) {
            logger.warn("Could not resolve {} in live: {}", path, e.getMessage());
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
            return;
        }

        JSONObject out = new JSONObject();
        out.put("published", true);
        out.put("url", publicUrl);
        out.put("checkedAt", System.currentTimeMillis());

        // Split the tested url so robots.txt can be fetched from the site root and
        // evaluated against this page's own path.
        String base;
        String pagePath;
        try {
            URL u = new URL(publicUrl);
            base = u.getProtocol() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
            // robots.txt rules can target the query string too, e.g. "Disallow: /*?reply=",
            // so the path we evaluate must carry it. Dropping the query silently
            // under-reports disallows.
            String q = u.getQuery();
            pagePath = (u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath())
                    + (q == null || q.isEmpty() ? "" : "?" + q);
        } catch (Exception e) {
            base = publicUrl;
            pagePath = "/";
        }

        // robots.txt states the policy. The per-agent fetches below show the reality.
        JSONObject siteFiles = SiteFilesChecker.check(base, pagePath,
                config.getFetchTimeoutMs(), config.getMaxBodyBytes());
        JSONObject robotsJson = siteFiles.optJSONObject("robots");
        RobotsRules rules = (robotsJson != null && robotsJson.optBoolean("present", false))
                ? RobotsRules.parse(robotsJson.optString("rawBody", ""))
                : RobotsRules.empty();
        if (robotsJson != null && robotsJson.has("rawBody")) {
            // Keep a readable excerpt for the UI, not the whole file in every cache entry.
            String raw = robotsJson.getString("rawBody");
            robotsJson.put("rawBody", raw.length() > 4000 ? raw.substring(0, 4000) : raw);
        }

        JSONArray results = new JSONArray();
        int controlWords = -1;
        int blocked = 0;
        int blockedButAllowed = 0;
        int reachableButDisallowed = 0;

        // Fetched a few at a time, but consumed strictly in order: the control
        // must stay first so controlWords is taken from it.
        Map<String, String> toFetch = agents();
        // Captured by the fetch lambdas, so it has to be effectively final.
        final String origin = base;
        List<String> names = new ArrayList<>(toFetch.keySet());
        List<JSONObject> probed = new ArrayList<>(names.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(FETCH_CONCURRENCY, Math.max(1, names.size())));
        try {
            List<Future<JSONObject>> futures = new ArrayList<>(names.size());
            for (String name : names) {
                String ua = toFetch.get(name);
                final String n = name;
                futures.add(pool.submit((Callable<JSONObject>) () ->
                        PageFetch.probe(publicUrl, origin, n, ua, config.getFetchTimeoutMs(), config.getMaxBodyBytes())));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    probed.add(futures.get(i).get());
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        // get() cleared the flag on the way out. Restore it, or
                        // nothing downstream can tell the request was cancelled.
                        Thread.currentThread().interrupt();
                    }
                    // One agent failing must not lose the other fifteen.
                    JSONObject r = new JSONObject();
                    r.put("name", names.get(i));
                    r.put("status", JSONObject.NULL);
                    r.put("ms", 0);
                    r.put("bytes", 0);
                    r.put("error", "FetchFailed");
                    probed.add(r);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, String> tokens = AiCrawlers.robotsTokens();
        for (JSONObject r : probed) {
            int status = r.optInt("status", 0);
            if (controlWords < 0 && status == 200) {
                controlWords = r.optJSONObject("html") != null ? r.getJSONObject("html").optInt("words", 0) : 0;
            }
            if (status != 200) {
                blocked++;
            }

            // Cross-check policy against reality. This is the part that tells an
            // editor which team owns the fix.
            String token = tokens.get(r.optString("name", ""));
            if (token != null) {
                RobotsRules.Verdict v = rules.evaluate(token, pagePath);
                r.put("robotsAllowed", v.allowed);
                r.put("robotsNamed", v.namedExplicitly);
                r.put("robotsRule", v.matchedRule == null ? JSONObject.NULL : v.matchedRule);
                if (v.allowed && status != 200 && status != 0) {
                    r.put("mismatch", "blockedButAllowed");
                    blockedButAllowed++;
                } else if (!v.allowed && status == 200) {
                    r.put("mismatch", "reachableButDisallowed");
                    reachableButDisallowed++;
                }
            }
            results.put(r);
        }

        out.put("agents", results);
        out.put("siteFiles", siteFiles);
        out.put("blockedCount", blocked);
        out.put("blockedButAllowedCount", blockedButAllowed);
        out.put("reachableButDisallowedCount", reachableButDisallowed);
        out.put("controlWords", Math.max(controlWords, 0));
        
        // GEO-19 for this one page. A crawler served a login form gets a cheerful
        // 200, so the fetch above cannot tell the difference. The repository can.
        try {
            out.put("visibility", GuestVisibility.forPage(path, language));
        } catch (Exception e) {
            logger.debug("visibility check failed for {}", path, e);
        }

        // The score reads only what is already in the report. It adds no requests.
        JSONObject score = GeoScore.compute(out);
        out.put("score", score);

        // Everything from here to the structured data below is site-wide and
        // comes out of the stored scan, which ScanStore reads under a system
        // session and says so: "Reading them is gated at the servlet instead".
        // This servlet was the one that did not gate it. Read access to a single
        // page therefore returned the site score, every section's score, the
        // per-template failure counts, the sitemap and link state and the
        // language coverage of a site the caller holds no dashboard permission
        // on - the same data the dashboard servlets all require "publish" for.
        //
        // The per-page check above stays open to any editor who can open the
        // drawer, because that is what the drawer is for. The fields below are
        // simply left out when the caller is not entitled to them: the UI
        // already renders without them, and a page check that fails outright
        // would be a worse answer than one that says less.
        boolean storedScanAllowed = mayReadStoredScan(path, language);
        if (storedScanAllowed) {
            // GEO-18. If the last site scan says this page's template fails the same
            // checks everywhere, say so: it stops an author trying to fix something
            // that is not theirs to fix. Silent when no scan has run.
            try {
                out.put("templateRollup", rollupFor(path, language, score));
            } catch (Exception e) {
                logger.debug("template rollup unavailable for {}", path, e);
            }

            // GEO-21, one line for this page, read from the last scan rather than by
            // fetching a sitemap the drawer has no business downloading.
            try {
                out.put("sitemap", sitemapFor(path, language));
            } catch (Exception e) {
                logger.debug("sitemap state unavailable for {}", path, e);
            }

            // GEO-22, likewise from the last scan: the graph needs every page on the
            // site, which is not something a drawer can work out for one page.
            try {
                JSONObject links = linksFor(path, language);
                if (links != null) {
                    out.put("links", links);
                }
            } catch (Exception e) {
                logger.debug("link counts unavailable for {}", path, e);
            }

            // GEO-25, from the last scan: which addresses a page answers on is a
            // site-wide question, not one the drawer can answer for a single page.
            try {
                JSONObject vanity = vanityFor(path, language);
                if (vanity != null) {
                    out.put("vanity", vanity);
                }
            } catch (Exception e) {
                logger.debug("vanity state unavailable for {}", path, e);
            }

            // GEO-20, one line while editing: the languages this page is missing
            // are something the author in front of it can act on today.
            try {
                out.put("languages", languagesFor(path, language));
            } catch (Exception e) {
                logger.debug("language coverage unavailable for {}", path, e);
            }

            // Where this page stands against the site and its section, and whether
            // it is in llms.txt. Both read from the last scan.
            try {
                out.put("context", contextFor(path, language));
            } catch (Exception e) {
                logger.debug("page context unavailable for {}", path, e);
            }
        }

        // GEO-23. Generated, shown, and copied by a human - never written into
        // the page from here.
        try {
            JSONObject firstAgent = out.optJSONArray("agents") == null
                    ? null : out.getJSONArray("agents").optJSONObject(0);
            JSONObject html = firstAgent == null ? null : firstAgent.optJSONObject("html");
            String pageTitle = html == null ? null : html.optString("title", null);
            // The JSON-LD is derived from this page's own content, so it stays
            // available; the overrides it can be tuned with are stored scan data
            // and are only read for a caller entitled to that.
            out.put("schema", schemaFor(path, language, req, resp, pageTitle, storedScanAllowed));
        } catch (Exception e) {
            logger.debug("structured data unavailable for {}", path, e);
        }
        writeJson(resp, HttpServletResponse.SC_OK, out);
    }







    /**
     * True when the caller may be shown what the stored scan holds about this
     * page's site.
     *
     * The same gate the dashboard servlets use, SiteScanServlet, SiteFilesServlet
     * and GeoReportServlet alike: SiteScope.DASHBOARD on the site the path
     * resolves to, decided in the caller's own session. Anything else - no
     * permission, no such node, or a repository that cannot answer - omits the
     * data, because "could not establish the permission" is not "has it".
     */
    private static boolean mayReadStoredScan(String path, String language) {
        try {
            SiteScope.require(path, language, SiteScope.DASHBOARD);
            return true;
        } catch (javax.jcr.PathNotFoundException | javax.jcr.AccessDeniedException e) {
            logger.debug("no dashboard permission on {}, leaving the stored scan out", path, e);
            return false;
        } catch (RepositoryException e) {
            logger.warn("Could not check the dashboard permission on {}: {}", path, e.getMessage());
            return false;
        }
    }

    /** What the stored scan knows about this page's template, or an empty object. */
    private JSONObject rollupFor(String path, String language, JSONObject score) throws Exception {
        JSONObject empty = new JSONObject();
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", java.util.Locale.forLanguageTag(language));
        JCRNodeWrapper node = live.getNode(path);
        String sitePath = node.getResolveSite().getPath();
        String template = node.hasProperty("j:templateName")
                ? node.getProperty("j:templateName").getString() : null;
        if (template == null || template.isEmpty()) {
            return empty;
        }

        JSONObject state = ScanStore.read(sitePath, language);
        JSONObject run = state.optJSONObject("run");
        JSONObject aggregate = run == null ? null : run.optJSONObject("aggregate");
        if (aggregate == null) {
            return empty;
        }

        JSONArray failed = new JSONArray();
        JSONArray checks = score.optJSONArray("checks");
        for (int i = 0; checks != null && i < checks.length(); i++) {
            JSONObject c = checks.getJSONObject(i);
            if (!c.optBoolean("passed", true)) {
                failed.put(c.getString("id"));
            }
        }
        return TemplateRollup.forPage(aggregate, template, failed);
    }

    /**
     * What the last scan found about this page in the sitemap. Empty when no
     * scan has run. Three separate facts, because they call for different
     * actions: absent from the map, advertised while saying `noindex`, or
     * listed with a date that no longer matches the page.
     */
    private JSONObject sitemapFor(String path, String language) throws Exception {
        JSONObject out = new JSONObject();
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", java.util.Locale.forLanguageTag(language));
        String sitePath = live.getNode(path).getResolveSite().getPath();

        JSONObject sitemap = ScanStore.read(sitePath, language).optJSONObject("sitemap");
        if (sitemap == null) {
            return out;
        }

        out.put("present", sitemap.optBoolean("present", false));
        out.put("missing", SitemapCheck.isMissing(sitemap, path, language));
        out.put("noindexListed", SitemapCheck.isNoindexListed(sitemap, path, language));
        String stale = SitemapCheck.staleDetail(sitemap, path, language);
        if (stale != null && !stale.isEmpty()) {
            out.put("staleDetail", stale);
        }
        return out;
    }

    /**
     * How many pages link to this one, from the last scan. Nav and content
     * counted apart, because being in a menu that lists everything is not the
     * same as somebody choosing to link here.
     */
    private JSONObject linksFor(String path, String language) throws Exception {
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", java.util.Locale.forLanguageTag(language));
        String sitePath = live.getNode(path).getResolveSite().getPath();
        JSONObject links = ScanStore.read(sitePath, language).optJSONObject("links");
        JSONObject counts = LinkGraph.countsFor(links, path, language);
        return counts == null ? null : counts;
    }

    /** Any vanity URL conflict the last scan found on this page. */
    private JSONObject vanityFor(String path, String language) throws Exception {
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", java.util.Locale.forLanguageTag(language));
        String sitePath = live.getNode(path).getResolveSite().getPath();
        JSONObject vanity = ScanStore.read(sitePath, language).optJSONObject("vanity");
        return VanityUrls.findingFor(vanity, path, language);
    }

    /** Which of the site's languages this page exists in, and which it does not. */
    private JSONObject languagesFor(String path, String language) throws Exception {
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", java.util.Locale.forLanguageTag(language));
        String sitePath = live.getNode(path).getResolveSite().getPath();
        return Languages.forNode(sitePath, path);
    }

    /**
     * This page's JSON-LD, derived from its content type.
     *
     * The page's own title is passed in so the generator can say when what it
     * produced disagrees with what the page displays - the one criterion that
     * makes structured data worse than none when it is broken.
     */
    private JSONObject schemaFor(String path, String language, HttpServletRequest req,
            HttpServletResponse resp, String pageTitle, boolean withStoredOverrides) throws Exception {
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", java.util.Locale.forLanguageTag(language));
        JCRNodeWrapper node = live.getNode(path);
        String sitePath = node.getResolveSite().getPath();
        // The overrides are the one part of this that is site configuration read
        // from the stored scan, so they follow the same permission as the rest
        // of it. Without them the generator falls back to the defaults, which is
        // what it does on a site that has never been scanned.
        JSONObject overrides = withStoredOverrides
                ? ScanStore.read(sitePath, language).optJSONObject("schemaMap") : null;
        String base = org.jahia.se.modules.georeadiness.util.PublicUrls
                .base(node, req, config.getPublicBaseUrl());
        return StructuredData.forNode(path, language, base, overrides, pageTitle);
    }

    /**
     * Where this page stands relative to the rest of the site.
     *
     * A page score with no reference point is not information: nobody knows
     * whether sixteen of eighteen is good here. The site average and the score
     * of this page's own section are both already stored by the last scan, and
     * together they say whether this page is the problem or the site is.
     *
     * Also carries whether the page is in llms.txt, which the drawer had no way
     * of saying even though it already reported the sitemap.
     */
    private JSONObject contextFor(String path, String language) throws Exception {
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", java.util.Locale.forLanguageTag(language));
        String sitePath = live.getNode(path).getResolveSite().getPath();
        JSONObject state = ScanStore.read(sitePath, language);

        JSONObject out = new JSONObject();
        JSONObject run = state.optJSONObject("run");
        JSONObject aggregate = run == null ? null : run.optJSONObject("aggregate");
        if (aggregate != null && aggregate.has("percent")) {
            out.put("sitePercent", aggregate.optInt("percent"));
            out.put("scannedAt", run.opt("finishedAt"));
            String section = PublishedMap.sectionOf(sitePath, path);
            JSONArray sections = aggregate.optJSONArray("sections");
            for (int i = 0; sections != null && i < sections.length(); i++) {
                JSONObject s = sections.getJSONObject(i);
                if (section.equals(s.optString("section", null))) {
                    out.put("section", section);
                    out.put("sectionPercent", s.optInt("percent"));
                    break;
                }
            }
        }

        JSONObject listing = LlmsFreshness.listingFor(state.optJSONObject("llms"), pathOfNode(live, path));
        if (listing != null) {
            out.put("llms", listing);
        }
        return out;
    }

    /** The public path this node answers on, which is how llms.txt names it. */
    private String pathOfNode(JCRSessionWrapper live, String path) {
        try {
            return PublishedMap.pathOf(org.jahia.se.modules.georeadiness.util.PublicUrls
                    .forNode(live.getNode(path), config.getPublicBaseUrl()));
        } catch (Exception e) {
            return null;
        }
    }

    private String publicUrlFor(JCRNodeWrapper node, HttpServletRequest req, HttpServletResponse resp) throws Exception {
        return PublicUrls.forNode(node, req, resp, config.getPublicBaseUrl());
    }

    private Map<String, String> agents() {
        String custom = config == null ? "" : config.getCrawlerAgents();
        if (custom == null || custom.trim().isEmpty()) {
            return DEFAULT_AGENTS;
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (String pair : custom.split(",")) {
            int i = pair.indexOf('|');
            if (i > 0) {
                m.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
            }
        }
        return m.isEmpty() ? DEFAULT_AGENTS : m;
    }

    private boolean rateLimitOk(String userKey) {
        long now = System.currentTimeMillis();
        Deque<Long> w = callWindows.computeIfAbsent(userKey, k -> new ArrayDeque<>());
        synchronized (w) {
            while (!w.isEmpty() && now - w.peekFirst() > config.getRateWindowMs()) {
                w.pollFirst();
            }
            if (w.size() >= config.getRateMaxCalls()) {
                return false;
            }
            w.addLast(now);
            return true;
        }
    }

    private static JahiaUser currentUser() {
        try {
            return JCRSessionFactory.getInstance().getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isGuest() {
        JahiaUser u = currentUser();
        return u == null || JahiaUserManagerService.GUEST_USERNAME.equals(u.getName());
    }

    private static String read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            out.write(buf, 0, n);
            if (total >= max) {
                break;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deny(HttpServletResponse resp, int code, String msg) throws IOException {
        JSONObject o = new JSONObject();
        o.put("error", msg);
        writeJson(resp, code, o);
    }

    private static void writeJson(HttpServletResponse resp, int code, JSONObject body) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(body.toString());
    }
}
