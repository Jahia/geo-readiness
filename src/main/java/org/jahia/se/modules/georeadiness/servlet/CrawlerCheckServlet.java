package org.jahia.se.modules.georeadiness.servlet;

import org.jahia.se.modules.georeadiness.check.RobotsRules;
import org.jahia.se.modules.georeadiness.check.SiteFilesChecker;
import org.jahia.se.modules.georeadiness.config.GeoReadinessConfigService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.bin.Jahia;
import org.jahia.services.SpringContextSingleton;
import org.jahia.services.seo.urlrewrite.UrlRewriteService;
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
import java.util.concurrent.ConcurrentHashMap;
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

    /** The control comes first on purpose: everything else is compared against it. */
    private static final Map<String, String> DEFAULT_AGENTS = new LinkedHashMap<String, String>() {{
        put("Browser (control)", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36");
        put("GPTBot", "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; GPTBot/1.2; +https://openai.com/gptbot");
        put("OAI-SearchBot", "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; OAI-SearchBot/1.0; +https://openai.com/searchbot");
        put("ChatGPT-User", "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; ChatGPT-User/1.0; +https://openai.com/bot");
        put("ClaudeBot", "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; ClaudeBot/1.0; +claudebot@anthropic.com");
        put("PerplexityBot", "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; PerplexityBot/1.0; +https://perplexity.ai/perplexitybot");
        put("Google-Extended", "Mozilla/5.0 (compatible; Google-Extended/1.0)");
        put("Bingbot", "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)");
        put("CCBot", "CCBot/2.0 (https://commoncrawl.org/faq/)");
    }};

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern H1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>");
    private static final Pattern META_DESC = Pattern.compile("(?is)<meta[^>]+name=[\"']description[\"']");
    private static final Pattern CANONICAL = Pattern.compile("(?is)<link[^>]+rel=[\"']canonical[\"']");
    private static final Pattern META_ROBOTS = Pattern.compile("(?is)<meta[^>]+name=[\"']robots[\"'][^>]*content=[\"']([^\"']*)[\"']");
    private static final Pattern META_REFRESH = Pattern.compile("(?is)<meta[^>]+http-equiv=[\"']refresh[\"'][^>]*content=[\"'][^\"']*?url=([^\"'>\\s]+)");
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\s[^>]*href=");
    private static final Pattern JSONLD = Pattern.compile("(?is)application/ld\\+json");
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

        for (Map.Entry<String, String> a : agents().entrySet()) {
            JSONObject r = probe(publicUrl, a.getKey(), a.getValue());
            int status = r.optInt("status", 0);
            if (controlWords < 0 && status == 200) {
                controlWords = r.optJSONObject("html") != null ? r.getJSONObject("html").optInt("words", 0) : 0;
            }
            if (status != 200) {
                blocked++;
            }

            // Cross-check policy against reality. This is the part that tells an
            // editor which team owns the fix.
            String token = SiteFilesChecker.AI_TOKENS.get(a.getKey());
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

    /**
     * The public url of a published page, as Jahia itself would print it.
     *
     * We do not guess the url shape. {@link JCRNodeWrapper#getUrl()} gives the
     * canonical render url, /cms/render/live/lang/sites/key/path.html, and the
     * outbound url rewriter turns it into exactly what a link to this page
     * looks like in the rendered site: vanity url when one exists, cms prefix
     * and site key dropped when the server name rules allow it. That is the
     * address a crawler follows.
     *
     * The host comes from PUBLIC_BASE_URL when set, else from the site's server
     * name, else from the current request. Vanity urls are resolved by the
     * rewriter in the LIVE workspace, which is what we want.
     */
    private String publicUrlFor(JCRNodeWrapper node, HttpServletRequest req, HttpServletResponse resp) throws Exception {
        String path = node.getUrl();
        try {
            UrlRewriteService rewriter = (UrlRewriteService) SpringContextSingleton.getBean("UrlRewriteService");
            String rewritten = rewriter.rewriteOutbound(path, req, resp);
            if (rewritten != null && !rewritten.isEmpty()) {
                path = fixContextPath(rewritten, req);
            }
        } catch (Exception e) {
            // Fall back to the render url. It is longer but always valid.
            logger.debug("Outbound rewrite failed for {}, using render url", path, e);
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return baseUrl(node, req) + path;
    }

    /**
     * OSGi servlets are mounted behind the /modules bridge, and the request
     * reports that as its context path. The rewriter prepends it faithfully,
     * which gives /modules/sites/... instead of /sites/.... Swap it for the
     * real webapp context path.
     */
    private static String fixContextPath(String url, HttpServletRequest req) {
        String reqCtx = req.getContextPath() == null ? "" : req.getContextPath();
        String realCtx = Jahia.getContextPath() == null ? "" : Jahia.getContextPath();
        if (!reqCtx.isEmpty() && !reqCtx.equals(realCtx) && (url.equals(reqCtx) || url.startsWith(reqCtx + "/"))) {
            return realCtx + url.substring(reqCtx.length());
        }
        return url;
    }

    /** Scheme and host only. The context path is already part of the rewritten path. */
    private String baseUrl(JCRNodeWrapper node, HttpServletRequest req) throws Exception {
        String base = config.getPublicBaseUrl();
        if (base == null || base.trim().isEmpty()) {
            String server = node.getResolveSite().getServerName();
            if (server == null || server.isEmpty() || "localhost".equalsIgnoreCase(server)) {
                base = req.getScheme() + "://" + req.getServerName()
                        + (req.getServerPort() == 80 || req.getServerPort() == 443 ? "" : ":" + req.getServerPort());
            } else {
                base = "https://" + server;
            }
        }
        return base.replaceAll("/+$", "");
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
