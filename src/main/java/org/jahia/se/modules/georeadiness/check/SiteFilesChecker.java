package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The two files that tell AI crawlers what to do: robots.txt and llms.txt.
 *
 * robots.txt states the policy. The per-agent fetch in CrawlerCheckServlet
 * shows the reality. The interesting result is the gap between them, so this
 * class reports what the policy says and leaves the comparison to the caller.
 */
public final class SiteFilesChecker {

    /**
     * Crawler name to robots token, from the one registry in {@link AiCrawlers}
     * so this list and the fetched list can never disagree again.
     */
    public static final Map<String, String> AI_TOKENS = AiCrawlers.robotsTokens();

    private static final Pattern MD_H1 = Pattern.compile("(?m)^#\\s+(.+)$");
    private static final Pattern MD_H2 = Pattern.compile("(?m)^##\\s+(.+)$");
    private static final Pattern MD_LINK = Pattern.compile("\\[[^\\]]*\\]\\([^)]+\\)");
    private static final Pattern MD_QUOTE = Pattern.compile("(?m)^>\\s+\\S");

    private SiteFilesChecker() {
    }

    public static JSONObject check(String baseUrl, String pagePath, int timeoutMs, int maxBytes) {
        JSONObject out = new JSONObject();
        out.put("baseUrl", baseUrl);

        Fetched robotsFetch = fetch(baseUrl + "/robots.txt", timeoutMs, maxBytes);
        out.put("robots", robots(robotsFetch, pagePath));

        out.put("llms", llms(fetch(baseUrl + "/llms.txt", timeoutMs, maxBytes), true));
        out.put("llmsFull", llms(fetch(baseUrl + "/llms-full.txt", timeoutMs, maxBytes), false));
        return out;
    }

    /** Exposed so the servlet can reuse the parsed rules for its per-agent verdicts. */
    public static RobotsRules rulesFrom(JSONObject robotsJson, String rawBody) {
        return robotsJson.optBoolean("present", false) ? RobotsRules.parse(rawBody) : RobotsRules.empty();
    }

    private static JSONObject robots(Fetched f, String pagePath) {
        JSONObject o = new JSONObject();
        o.put("status", f.status == null ? JSONObject.NULL : f.status);
        o.put("present", f.status != null && f.status == 200);
        o.put("bytes", f.body == null ? 0 : f.body.length());
        if (f.error != null) {
            o.put("error", f.error);
        }
        if (!o.optBoolean("present")) {
            // No robots.txt means every crawler is allowed everywhere. Worth saying
            // out loud, because "no file" reads as "not configured" to most people.
            o.put("everythingAllowed", true);
            return o;
        }

        RobotsRules rules = RobotsRules.parse(f.body);
        o.put("sitemaps", new JSONArray(rules.getSitemaps()));
        o.put("declaredAgents", new JSONArray(rules.getDeclaredAgents()));
        o.put("rawBody", f.body.length() > 20000 ? f.body.substring(0, 20000) : f.body);

        JSONArray agents = new JSONArray();
        int named = 0;
        int disallowed = 0;
        for (Map.Entry<String, String> e : AI_TOKENS.entrySet()) {
            RobotsRules.Verdict v = rules.evaluate(e.getValue(), pagePath);
            JSONObject a = new JSONObject();
            a.put("name", e.getKey());
            a.put("token", e.getValue());
            a.put("allowed", v.allowed);
            a.put("namedExplicitly", v.namedExplicitly);
            a.put("group", v.matchedGroup == null ? JSONObject.NULL : v.matchedGroup);
            a.put("rule", v.matchedRule == null ? JSONObject.NULL : v.matchedRule);
            if (v.namedExplicitly) {
                named++;
            }
            if (!v.allowed) {
                disallowed++;
            }
            agents.put(a);
        }
        o.put("agents", agents);
        o.put("namedAiBotCount", named);
        o.put("disallowedAiBotCount", disallowed);
        o.put("pagePath", pagePath);
        return o;
    }

    private static JSONObject llms(Fetched f, boolean validateStructure) {
        JSONObject o = new JSONObject();
        o.put("status", f.status == null ? JSONObject.NULL : f.status);
        boolean present = f.status != null && f.status == 200 && f.body != null && !f.body.trim().isEmpty();
        o.put("bytes", f.body == null ? 0 : f.body.length());
        if (f.error != null) {
            o.put("error", f.error);
        }
        o.put("contentType", f.contentType == null ? JSONObject.NULL : f.contentType);

        // A site that serves its normal HTML page for /llms.txt has not published one.
        // This is the common false positive, so check before declaring success.
        boolean looksLikeHtml = present
                && (f.body.trim().toLowerCase().startsWith("<!doctype html")
                    || f.body.trim().toLowerCase().startsWith("<html")
                    || (f.contentType != null && f.contentType.toLowerCase().contains("text/html")));
        if (looksLikeHtml) {
            present = false;
            o.put("servedHtmlInstead", true);
        }
        o.put("present", present);

        if (present && validateStructure) {
            Matcher h1 = MD_H1.matcher(f.body);
            o.put("h1", h1.find() ? h1.group(1).trim() : JSONObject.NULL);
            o.put("hasSummary", MD_QUOTE.matcher(f.body).find());
            o.put("sections", count(MD_H2, f.body));
            o.put("links", count(MD_LINK, f.body));
        }
        return o;
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    public static Fetched fetch(String url, int timeoutMs, int maxBytes) {
        Fetched f = new Fetched();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            // Ask as a plain crawler would, not as a browser.
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; JahiaGeoReadiness/1.0)");
            c.setRequestProperty("Accept", "text/plain,text/markdown,*/*;q=0.8");
            f.status = c.getResponseCode();
            f.contentType = c.getContentType();
            InputStream in = f.status >= 400 ? c.getErrorStream() : c.getInputStream();
            f.body = in == null ? "" : read(in, maxBytes);
        } catch (Exception e) {
            f.error = e.getClass().getSimpleName();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return f;
    }

    private static String read(InputStream in, int max) throws Exception {
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

    public static final class Fetched {
        public Integer status;
        public String body;
        public String contentType;
        public String error;
    }
}
