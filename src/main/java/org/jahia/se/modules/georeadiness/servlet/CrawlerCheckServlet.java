package org.jahia.se.modules.georeadiness.servlet;

import org.jahia.se.modules.georeadiness.check.AiCrawlers;
import org.jahia.se.modules.georeadiness.check.GeoScore;
import org.jahia.se.modules.georeadiness.check.RobotsRules;
import org.jahia.se.modules.georeadiness.check.SiteFilesChecker;
import org.jahia.se.modules.georeadiness.config.GeoReadinessConfigService;
import org.jahia.se.modules.georeadiness.util.PublicUrls;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        property = {"alias=/geo-readiness/crawler-check", "allow-api-token=true"},
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

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern H1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>");
    private static final Pattern META_DESC = Pattern.compile("(?is)<meta[^>]+name=[\"']description[\"']");
    private static final Pattern CANONICAL = Pattern.compile("(?is)<link[^>]+rel=[\"']canonical[\"']");
    private static final Pattern META_ROBOTS = Pattern.compile("(?is)<meta[^>]+name=[\"']robots[\"'][^>]*content=[\"']([^\"']*)[\"']");
    private static final Pattern META_REFRESH = Pattern.compile("(?is)<meta[^>]+http-equiv=[\"']refresh[\"'][^>]*content=[\"'][^\"']*?url=([^\"'>\\s]+)");
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\s[^>]*href=");
    private static final Pattern JSONLD = Pattern.compile("(?is)application/ld\\+json");
    private static final Pattern HTML_LANG = Pattern.compile("(?is)<html[^>]+\\blang=[\"']([^\"']+)[\"']");
    private static final Pattern HREFLANG = Pattern.compile("(?is)<link[^>]+hreflang=[\"']([^\"']+)[\"']");
    private static final Pattern H2 = Pattern.compile("(?is)<h2[\\s>]");
    private static final Pattern IMG = Pattern.compile("(?is)<img\\s[^>]*>");
    private static final Pattern IMG_ALT = Pattern.compile("(?is)\\balt=[\"']([^\"']*)[\"']");
    private static final Pattern JSONLD_BLOCK = Pattern.compile("(?is)<script[^>]+application/ld\\+json[^>]*>(.*?)</script>");
    private static final Pattern JSONLD_TYPE = Pattern.compile("(?is)[\"']@type[\"']\\s*:\\s*[\"']([^\"']+)[\"']");
    private static final Pattern MODIFIED = Pattern.compile("(?is)[\"']dateModified[\"']\\s*:\\s*[\"']([^\"']+)[\"']|article:modified_time[\"'][^>]*content=[\"']([^\"']+)[\"']");
    private static final Pattern SCRIPTS = Pattern.compile("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+");

    private final Map<String, Deque<Long>> callWindows = new ConcurrentHashMap<>();

    @Reference
    private GeoReadinessConfigService config;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (isGuest()) {
            deny(resp, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        JSONObject out = new JSONObject();
        out.put("enabled", true);
        out.put("agents", new JSONArray(agents().keySet()));
        writeJson(resp, HttpServletResponse.SC_OK, out);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
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
        List<String> names = new ArrayList<>(toFetch.keySet());
        List<JSONObject> probed = new ArrayList<>(names.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(FETCH_CONCURRENCY, Math.max(1, names.size())));
        try {
            List<Future<JSONObject>> futures = new ArrayList<>(names.size());
            for (String name : names) {
                String ua = toFetch.get(name);
                final String n = name;
                futures.add(pool.submit((Callable<JSONObject>) () -> probe(publicUrl, n, ua)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    probed.add(futures.get(i).get());
                } catch (Exception e) {
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
        
        // The score reads only what is already in the report. It adds no requests.
        out.put("score", GeoScore.compute(out));
        writeJson(resp, HttpServletResponse.SC_OK, out);
    }

    /** One fetch, no cookies, no silent redirect following. */
    private JSONObject probe(String url, String name, String ua) {
        JSONObject r = new JSONObject();
        r.put("name", name);
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(config.getFetchTimeoutMs());
            c.setReadTimeout(config.getFetchTimeoutMs());
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "en");
            int status = c.getResponseCode();
            r.put("status", status);
            String location = c.getHeaderField("Location");
            if (location != null) {
                r.put("location", location);
            }
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String html = in == null ? "" : read(in, config.getMaxBodyBytes());
            r.put("bytes", html.length());
            r.put("ms", System.currentTimeMillis() - t0);
            if (!html.isEmpty()) {
                r.put("html", analyse(html));
            }
        } catch (Exception e) {
            r.put("status", JSONObject.NULL);
            r.put("ms", System.currentTimeMillis() - t0);
            r.put("bytes", 0);
            // Generic on purpose. Upstream detail is not leaked to the browser.
            r.put("error", e.getClass().getSimpleName());
            logger.debug("Probe failed for {} on {}", name, url, e);
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }

    /** What a crawler can see in the raw html, before any JavaScript runs. */
    private JSONObject analyse(String html) {
        JSONObject o = new JSONObject();
        Matcher t = TITLE.matcher(html);
        o.put("title", t.find() ? clip(strip(t.group(1)), 120) : JSONObject.NULL);
        Matcher h = H1.matcher(html);
        int h1Count = 0;
        String firstH1 = null;
        while (h.find()) {
            if (firstH1 == null) {
                firstH1 = clip(strip(h.group(1)), 120);
            }
            h1Count++;
        }
        o.put("h1Count", h1Count);
        o.put("h1", firstH1 == null ? JSONObject.NULL : firstH1);
        o.put("metaDescription", META_DESC.matcher(html).find());
        o.put("canonical", CANONICAL.matcher(html).find());
        Matcher mr = META_ROBOTS.matcher(html);
        o.put("metaRobots", mr.find() ? clip(mr.group(1).trim(), 60) : JSONObject.NULL);
        // A client-side redirect. The page answers 200 with a shell, and only a
        // browser follows it. To a crawler this is the whole page.
        Matcher mf = META_REFRESH.matcher(html);
        o.put("metaRefresh", mf.find() ? clip(mf.group(1).trim(), 300) : JSONObject.NULL);
        o.put("links", count(ANCHOR, html));
        o.put("jsonLd", count(JSONLD, html));

        // Signals below cost nothing extra: the html is already in memory. They are
        // what turns "the crawler got a page" into "the crawler got a USEFUL page".
        Matcher lang = HTML_LANG.matcher(html);
        o.put("lang", lang.find() ? clip(lang.group(1).trim(), 20) : JSONObject.NULL);

        Matcher hl = HREFLANG.matcher(html);
        JSONArray alts = new JSONArray();
        while (hl.find() && alts.length() < 20) {
            alts.put(clip(hl.group(1).trim(), 20));
        }
        o.put("hreflang", alts);

        o.put("h2Count", count(H2, html));

        // Alt text is how a text-only crawler learns what an image shows. An
        // explicitly empty alt is a decorative image, correct but not descriptive.
        Matcher im = IMG.matcher(html);
        int images = 0;
        int withAlt = 0;
        while (im.find()) {
            images++;
            Matcher a = IMG_ALT.matcher(im.group());
            if (a.find() && !a.group(1).trim().isEmpty()) {
                withAlt++;
            }
        }
        o.put("images", images);
        o.put("imagesWithAlt", withAlt);

        // A count of ld+json blocks says nothing. Which schema types are declared does.
        Matcher jb = JSONLD_BLOCK.matcher(html);
        JSONArray types = new JSONArray();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        while (jb.find()) {
            Matcher jt = JSONLD_TYPE.matcher(jb.group(1));
            while (jt.find() && seen.size() < 15) {
                seen.add(clip(jt.group(1).trim(), 40));
            }
        }
        seen.forEach(types::put);
        o.put("jsonLdTypes", types);

        Matcher md = MODIFIED.matcher(html);
        String modified = null;
        if (md.find()) {
            modified = md.group(1) != null ? md.group(1) : md.group(2);
        }
        o.put("dateModified", modified == null ? JSONObject.NULL : clip(modified.trim(), 40));

        String text = textOf(html);
        o.put("words", text.isEmpty() ? 0 : text.split(" ").length);
        return o;
    }

    private static String textOf(String html) {
        String s = SCRIPTS.matcher(html).replaceAll(" ");
        s = TAGS.matcher(s).replaceAll(" ");
        return WS.matcher(s).replaceAll(" ").trim();
    }

    private static String strip(String s) {
        return WS.matcher(TAGS.matcher(s).replaceAll("")).replaceAll(" ").trim();
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
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
