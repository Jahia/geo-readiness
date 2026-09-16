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
import java.io.IOException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

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
public class CrawlerCheckServlet extends GeoServlet {

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

    /** A path and a language. Anything larger is not this endpoint's request. */
    private static final int MAX_BODY = 64_000;

    /**
     * How much of robots.txt the UI is shown. A readable excerpt, not the whole
     * file in every cache entry.
     */
    private static final int MAX_RAW_ROBOTS = 4000;

    /** The fetched body of robots.txt or llms.txt, as the site-files check returns it. */
    private static final String RAW_BODY = "rawBody";
    /** The per-agent results array, the report's own key for it. */
    private static final String AGENTS = "agents";

    /** Set by the site-files check when the file was actually served. */
    private static final String PRESENT = "present";

    /** robots.txt allows this agent, but something on the way refused it. */
    private static final String BLOCKED_BUT_ALLOWED = "blockedButAllowed";
    /** robots.txt disallows this agent, and it read the page anyway. */
    private static final String REACHABLE_BUT_DISALLOWED = "reachableButDisallowed";

    private static final Pattern H1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>");
    private static final Pattern H2 = Pattern.compile("(?is)<h2[\\s>]");

    /**
     * Injected through the constructor rather than into the field, so a servlet
     * the container shares between threads holds nothing mutable.
     */
    private final transient GeoReadinessConfigService config;

    @Activate
    public CrawlerCheckServlet(@Reference GeoReadinessConfigService config) {
        this.config = config;
    }

    /**
     * The crawler user agents this module knows about, for a site the caller may
     * administer.
     *
     * The gate is {@link #requireDashboard}, not a guest check. The list names
     * the exact user agents the site treats specially, which is reconnaissance
     * for anyone shaping a request to be handled as a crawler, and it is
     * operator configuration like every other value in this module's OSGi
     * config. Before JAHIA-SEC-432 this asked only whether the caller was logged
     * in, so an account holding no role on any site read the whole list.
     *
     * The list is global to the module rather than per-site, so the site here is
     * what the caller must hold the permission ON, not what is looked up.
     */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        try {
            if (requireDashboard(req, resp) == null) {
                return;
            }
            JSONObject out = new JSONObject();
            out.put("enabled", true);
            out.put(AGENTS, new JSONArray(agents().keySet()));
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

    /**
     * The POST, as a sequence of steps that can each refuse it.
     *
     * Everything here used to be one method, which Sonar scored at a cognitive
     * complexity of 74 against a threshold of 15 - and the number was fair: the
     * refusals, the fetching and the report building were interleaved, so
     * nothing could be read or changed on its own.
     *
     * The convention that makes the split safe is worth stating, because getting
     * it wrong writes TWO responses to one request: a helper either ANSWERS the
     * request and says so by returning null or false, or it returns a value and
     * stays silent. Every call below is therefore a single "if ... return".
     */
    private void dispatch(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JahiaUser user = requireUser(resp);
        if (user == null) {
            return;
        }
        // The content type is checked before the rate limit and the body is read
        // after it, so a flood of malformed bodies still spends the caller's
        // budget rather than being refused for free. jsonBody re-checks the
        // content type; that is cheap and keeps it safe to call on its own.
        if (!isJsonRequest(req, resp)) {
            return;
        }
        if (!rateLimitOk(user.getUserKey(), config.getRateWindowMs(), config.getRateMaxCalls())) {
            deny(resp, 429, "rate limit");
            return;
        }
        JSONObject body = jsonBody(req, resp, MAX_BODY);
        if (body == null) {
            return;
        }
        String path = body.optString("path", "");
        String language = body.optString("language", "en");
        if (!isWellFormed(path, language, resp) || !mayEditNode(path, language, resp)) {
            return;
        }
        String publicUrl = publishedUrlOf(path, language, req, resp);
        if (publicUrl == null) {
            return;
        }
        writeJson(resp, HttpServletResponse.SC_OK, report(path, language, publicUrl, req));
    }

    /**
     * True when the caller can open this node in the editing workspace.
     *
     * The drawer opens on a node the caller has selected in jContent, so the
     * floor is "edits this content", not "can read the published page". Reading
     * it in default with their own session is what decides that.
     */
    private boolean mayEditNode(String path, String language, HttpServletResponse resp) throws IOException {
        try {
            JCRSessionFactory.getInstance()
                    .getCurrentUserSession("default", Locale.forLanguageTag(language))
                    .getNode(path);
            return true;
        } catch (javax.jcr.PathNotFoundException | javax.jcr.AccessDeniedException e) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
            return false;
        } catch (RepositoryException e) {
            logger.warn("Could not resolve {} in default: {}", path, e.getMessage());
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "operation failed");
            return false;
        }
    }

    /**
     * The url a visitor would use, or null when the request has been answered.
     *
     * The node must exist in LIVE and the caller must be able to read it. Absent
     * from live means never published, which is a real answer rather than an
     * error - so that case answers 200 with published:false and returns null
     * like any other "already answered".
     */
    private String publishedUrlOf(String path, String language, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        try {
            JCRSessionWrapper live = JCRSessionFactory.getInstance()
                    .getCurrentUserSession("live", Locale.forLanguageTag(language));
            JCRNodeWrapper node = live.getNode(path);
            return publicUrlFor(node, req, resp);
        } catch (javax.jcr.PathNotFoundException e) {
            JSONObject out = new JSONObject();
            out.put("published", false);
            writeJson(resp, HttpServletResponse.SC_OK, out);
            return null;
        } catch (Exception e) {
            logger.warn("Could not resolve {} in live: {}", path, e.getMessage());
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
            return null;
        }
    }

    /**
     * The report itself, for a page already established as published and
     * readable. Every step from here on adds to the object; none of them refuse.
     */
    private JSONObject report(String path, String language, String publicUrl, HttpServletRequest req) {
        JSONObject out = new JSONObject();
        out.put("published", true);
        out.put("url", publicUrl);
        out.put("checkedAt", System.currentTimeMillis());

        Target target = Target.of(publicUrl);

        // robots.txt states the policy. The per-agent fetches below show the reality.
        JSONObject siteFiles = SiteFilesChecker.check(target.base, target.pagePath,
                config.getFetchTimeoutMs(), config.getMaxBodyBytes());
        RobotsRules rules = rulesOf(siteFiles);
        out.put("siteFiles", siteFiles);

        tally(out, probeAgents(publicUrl, target.base), rules, target.pagePath);

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

        boolean storedScanAllowed = mayReadStoredScan(path, language);
        if (storedScanAllowed) {
            addStoredScanFields(out, path, language, score);
        }
        addSchema(out, path, language, req, storedScanAllowed);
        return out;
    }

    /**
     * A tested url split the way the two halves of the check need it: robots.txt
     * is fetched from the site root, and evaluated against this page's own path.
     */
    private static final class Target {
        private final String base;
        private final String pagePath;

        private Target(String base, String pagePath) {
            this.base = base;
            this.pagePath = pagePath;
        }

        static Target of(String publicUrl) {
            try {
                URL u = new URL(publicUrl);
                String base = u.getProtocol() + "://" + u.getHost()
                        + (u.getPort() == -1 ? "" : ":" + u.getPort());
                // robots.txt rules can target the query string too, e.g.
                // "Disallow: /*?reply=", so the path we evaluate must carry it.
                // Dropping the query silently under-reports disallows.
                String q = u.getQuery();
                String pagePath = (u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath())
                        + (q == null || q.isEmpty() ? "" : "?" + q);
                return new Target(base, pagePath);
            } catch (Exception e) {
                return new Target(publicUrl, "/");
            }
        }
    }

    /**
     * The rules robots.txt states, and - as a side effect the caller depends on -
     * a rawBody trimmed to an excerpt, so the whole file does not end up in every
     * cache entry.
     */
    private static RobotsRules rulesOf(JSONObject siteFiles) {
        JSONObject robotsJson = siteFiles.optJSONObject("robots");
        if (robotsJson == null) {
            return RobotsRules.empty();
        }
        RobotsRules rules = robotsJson.optBoolean(PRESENT, false)
                ? RobotsRules.parse(robotsJson.optString(RAW_BODY, ""))
                : RobotsRules.empty();
        if (robotsJson.has(RAW_BODY)) {
            String raw = robotsJson.getString(RAW_BODY);
            robotsJson.put(RAW_BODY, raw.length() > MAX_RAW_ROBOTS ? raw.substring(0, MAX_RAW_ROBOTS) : raw);
        }
        return rules;
    }

    /**
     * One probe per bot user agent.
     *
     * Fetched a few at a time, but returned strictly in submission order: the
     * control agent must stay first, because it is where controlWords comes from.
     */
    private List<JSONObject> probeAgents(String publicUrl, String origin) {
        Map<String, String> toFetch = agents();
        List<String> names = new ArrayList<>(toFetch.keySet());
        List<JSONObject> probed = new ArrayList<>(names.size());
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(FETCH_CONCURRENCY, Math.max(1, names.size())));
        try {
            List<Future<JSONObject>> futures = new ArrayList<>(names.size());
            for (String name : names) {
                String ua = toFetch.get(name);
                futures.add(pool.submit((Callable<JSONObject>) () -> PageFetch.probe(
                        publicUrl, origin, name, ua,
                        config.getFetchTimeoutMs(), config.getMaxBodyBytes())));
            }
            for (int i = 0; i < futures.size(); i++) {
                probed.add(resultOf(futures.get(i), names.get(i)));
            }
        } finally {
            pool.shutdownNow();
        }
        return probed;
    }

    /** One agent's result, or a synthetic failure: one agent must not lose the rest. */
    private static JSONObject resultOf(Future<JSONObject> future, String name) {
        try {
            return future.get();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                // get() cleared the flag on the way out. Restore it, or nothing
                // downstream can tell the request was cancelled.
                Thread.currentThread().interrupt();
            }
            JSONObject r = new JSONObject();
            r.put("name", name);
            r.put("status", JSONObject.NULL);
            r.put("ms", 0);
            r.put("bytes", 0);
            r.put("error", "FetchFailed");
            return r;
        }
    }

    /**
     * Counts the probes into the report, cross-checking each against the policy.
     *
     * The cross-check is the part that tells an editor which team owns the fix:
     * a page robots.txt allows but the firewall blocks is an infrastructure
     * problem, and one it disallows but every bot can read is a content problem.
     */
    private static void tally(JSONObject out, List<JSONObject> probed, RobotsRules rules, String pagePath) {
        Map<String, String> tokens = AiCrawlers.robotsTokens();
        JSONArray results = new JSONArray();
        int controlWords = -1;
        int blocked = 0;
        int blockedButAllowed = 0;
        int reachableButDisallowed = 0;

        for (JSONObject r : probed) {
            int status = r.optInt("status", 0);
            if (controlWords < 0 && status == 200) {
                controlWords = wordsOf(r);
            }
            if (status != 200) {
                blocked++;
            }
            String mismatch = crossCheck(r, rules, tokens.get(r.optString("name", "")), pagePath, status);
            if (BLOCKED_BUT_ALLOWED.equals(mismatch)) {
                blockedButAllowed++;
            } else if (REACHABLE_BUT_DISALLOWED.equals(mismatch)) {
                reachableButDisallowed++;
            }
            results.put(r);
        }

        out.put(AGENTS, results);
        out.put("blockedCount", blocked);
        out.put("blockedButAllowedCount", blockedButAllowed);
        out.put("reachableButDisallowedCount", reachableButDisallowed);
        out.put("controlWords", Math.max(controlWords, 0));
    }

    /**
     * Writes what robots.txt says about this agent onto its result, and names the
     * contradiction if there is one. Null when the agent carries no robots token,
     * so there is no policy to contradict.
     */
    private static String crossCheck(JSONObject r, RobotsRules rules, String token, String pagePath, int status) {
        if (token == null) {
            return null;
        }
        RobotsRules.Verdict v = rules.evaluate(token, pagePath);
        r.put("robotsAllowed", v.allowed);
        r.put("robotsNamed", v.namedExplicitly);
        r.put("robotsRule", v.matchedRule == null ? JSONObject.NULL : v.matchedRule);
        String mismatch = mismatchOf(v.allowed, status);
        if (mismatch != null) {
            r.put("mismatch", mismatch);
        }
        return mismatch;
    }

    /** Policy against reality: the two ways they can disagree, or null when they do not. */
    private static String mismatchOf(boolean allowed, int status) {
        // status 0 is "the fetch never completed", which contradicts nothing.
        if (allowed && status != 200 && status != 0) {
            return BLOCKED_BUT_ALLOWED;
        }
        if (!allowed && status == 200) {
            return REACHABLE_BUT_DISALLOWED;
        }
        return null;
    }

    private static int wordsOf(JSONObject r) {
        JSONObject html = r.optJSONObject("html");
        return html == null ? 0 : html.optInt("words", 0);
    }

    /**
     * The site-wide fields, for a caller entitled to them.
     *
     * These come out of the stored scan, which ScanStore reads under a system
     * session and says so: "Reading them is gated at the servlet instead". This
     * servlet was the one that did not gate it. Read access to a single page
     * therefore returned the site score, every section's score, the per-template
     * failure counts, the sitemap and link state and the language coverage of a
     * site the caller holds no dashboard permission on - the same data the
     * dashboard servlets all require "publish" for.
     *
     * The per-page check stays open to any editor who can open the drawer,
     * because that is what the drawer is for. These are simply left out when the
     * caller is not entitled to them: the UI already renders without them, and a
     * page check that fails outright would be a worse answer than one that says
     * less.
     *
     * Each is attempted on its own. One unavailable field must not cost the
     * others, so every block logs at debug and moves on.
     */
    private void addStoredScanFields(JSONObject out, String path, String language, JSONObject score) {
        // GEO-18. If the last site scan says this page's template fails the same
        // checks everywhere, say so: it stops an author trying to fix something
        // that is not theirs to fix. Silent when no scan has run.
        put(out, "templateRollup", path, () -> rollupFor(path, language, score));
        // GEO-21, one line for this page, read from the last scan rather than by
        // fetching a sitemap the drawer has no business downloading.
        put(out, "sitemap", path, () -> sitemapFor(path, language));
        // GEO-22, likewise from the last scan: the graph needs every page on the
        // site, which is not something a drawer can work out for one page.
        put(out, "links", path, () -> linksFor(path, language));
        // GEO-25, from the last scan: which addresses a page answers on is a
        // site-wide question, not one the drawer can answer for a single page.
        put(out, "vanity", path, () -> vanityFor(path, language));
        // GEO-20, one line while editing: the languages this page is missing are
        // something the author in front of it can act on today.
        put(out, "languages", path, () -> languagesFor(path, language));
        // Where this page stands against the site and its section, and whether it
        // is in llms.txt. Both read from the last scan.
        put(out, "context", path, () -> contextFor(path, language));
    }

    /**
     * Adds one optional field, or logs why it is missing.
     *
     * A null result is left out rather than written as JSON null: linksFor and
     * vanityFor both return null for "the scan holds nothing about this page",
     * and the UI reads an absent key, not a null one.
     */
    private static void put(JSONObject out, String key, String path, Callable<JSONObject> value) {
        try {
            JSONObject v = value.call();
            if (v != null) {
                out.put(key, v);
            }
        } catch (Exception e) {
            logger.debug("{} unavailable for {}", key, path, e);
        }
    }

    /**
     * GEO-23. Generated, shown, and copied by a human - never written into the
     * page from here.
     *
     * The JSON-LD is derived from this page's own content, so it stays available
     * whoever asks; the overrides it can be tuned with are stored scan data and
     * are only read for a caller entitled to that.
     */
    private void addSchema(JSONObject out, String path, String language, HttpServletRequest req,
            boolean withStoredOverrides) {
        try {
            JSONObject firstAgent = out.optJSONArray(AGENTS) == null
                    ? null : out.getJSONArray(AGENTS).optJSONObject(0);
            JSONObject html = firstAgent == null ? null : firstAgent.optJSONObject("html");
            String pageTitle = html == null ? null : html.optString("title", null);
            out.put("schema", schemaFor(path, language, req, pageTitle, withStoredOverrides));
        } catch (Exception e) {
            logger.debug("structured data unavailable for {}", path, e);
        }
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

        out.put(PRESENT, sitemap.optBoolean(PRESENT, false));
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
            String pageTitle, boolean withStoredOverrides) throws RepositoryException {
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
}
