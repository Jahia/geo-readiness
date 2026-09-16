package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.jahia.se.modules.georeadiness.util.FetchGuard;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One fetch of one page as one crawler, and what can be read out of the HTML
 * that comes back.
 *
 * Extracted so the per-page drawer check and the site-wide scan share one
 * implementation. They ask different questions - the drawer fetches sixteen
 * times to compare crawlers, the scan fetches once per page because sixteen
 * times fifty thousand pages is not a thing you do to your own site - but what
 * a fetch is, and what a page's initial HTML contains, must mean the same in
 * both or the two scores will drift apart.
 *
 * No cookies, no session, redirects not followed, and the INITIAL HTML is read
 * rather than a rendered DOM. That is the whole point: a page whose content
 * only appears after JavaScript looks perfect to an editor and empty here.
 */
public final class PageFetch {

    private static final Logger logger = LoggerFactory.getLogger(PageFetch.class);

    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META_DESC = Pattern.compile("(?is)<meta[^>]+name=[\"']description[\"']");
    private static final Pattern CANONICAL = Pattern.compile("(?is)<link[^>]+rel=[\"']canonical[\"'][^>]*>");
    /** Applied to the matched tag, because href may sit either side of rel. */
    private static final Pattern HREF = Pattern.compile("(?is)href=[\"']([^\"']*)[\"']");
    private static final Pattern META_ROBOTS = Pattern.compile("(?is)<meta[^>]+name=[\"']robots[\"'][^>]*content=[\"']([^\"']*)[\"']");
    private static final Pattern META_REFRESH = Pattern.compile("(?is)<meta[^>]+http-equiv=[\"']refresh[\"'][^>]*content=[\"'][^\"']*?url=([^\"'>\\s]+)");
    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\s[^>]*href=");
    private static final Pattern JSONLD = Pattern.compile("(?is)application/ld\\+json");
    private static final Pattern HTML_LANG = Pattern.compile("(?is)<html[^>]+\\blang=[\"']([^\"']+)[\"']");
    private static final Pattern HREFLANG = Pattern.compile("(?is)<link[^>]+hreflang=[\"']([^\"']+)[\"']");
    private static final Pattern IMG = Pattern.compile("(?is)<img\\s[^>]*>");
    private static final Pattern IMG_ALT = Pattern.compile("(?is)\\balt=[\"']([^\"']*)[\"']");
    private static final Pattern JSONLD_BLOCK = Pattern.compile("(?is)<script[^>]+application/ld\\+json[^>]*>(.*?)</script>");
    /** Enough to show the page is translated; past this the list is not read. */
    private static final int MAX_HREFLANG = 20;
    /** Enough to name what the page declares itself as. */
    private static final int MAX_JSONLD_TYPES = 15;

    private static final Pattern JSONLD_TYPE = Pattern.compile("(?is)[\"']@type[\"']\\s*:\\s*[\"']([^\"']+)[\"']");
    /**
     * The attribute run is BOUNDED, and that is not style.
     *
     * `[^>]*` followed by `content=` backtracks: the engine scans to the end of
     * the tag, fails to find content=, then retries from the next position, and
     * does that again at every occurrence of the prefix. On a page carrying many
     * repetitions of `article:modified_time"` with no closing `>` that is
     * quadratic in the page size - and this regex runs over HTML fetched from a
     * site the module does not control, up to maxBodyBytes of it, once per page
     * of a scheduled scan. A hostile or merely pathological page could hold a
     * scan thread indefinitely. CodeQL java/polynomial-redos.
     *
     * A cap of 200 makes the work per start position constant, so the whole
     * match is linear again. Real meta tags put a handful of attributes between
     * the property and its content; 200 characters is far past any of them.
     */
    private static final Pattern MODIFIED = Pattern.compile(
            "(?is)[\"']dateModified[\"']\\s*:\\s*[\"']([^\"']+)[\"']"
            + "|article:modified_time[\"'][^>]{0,200}content=[\"']([^\"']+)[\"']");
    private static final Pattern SCRIPTS = Pattern.compile("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+");

    private static final Pattern H1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>");
    private static final Pattern H2 = Pattern.compile("(?is)<h2[\\s>]");

    private PageFetch() {
    }

    public static JSONObject probe(String url, String base, String name, String ua, int timeoutMs, int maxBytes) {
        return probe(url, base, name, ua, timeoutMs, maxBytes, null);
    }

    /**
     * As above, but hands the raw body to `bodySink` before discarding it.
     *
     * The site scan builds its link graph from the rendered HTML of pages it is
     * already fetching. Returning the body in the report instead would put a
     * copy of every page into the drawer's response, sixteen times over, to
     * serve one caller.
     */
    public static JSONObject probe(String url, String base, String name, String ua, int timeoutMs, int maxBytes,
            java.util.function.Consumer<String> bodySink) {
        JSONObject r = new JSONObject();
        r.put("name", name);
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = FetchGuard.open(url, base, timeoutMs, ua);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "en");
            int status = c.getResponseCode();
            r.put("status", status);
            String location = c.getHeaderField("Location");
            if (location != null) {
                r.put("location", location);
            }
            String html = body(c, status, maxBytes);
            r.put("bytes", html.length());
            r.put("ms", System.currentTimeMillis() - t0);
            if (!html.isEmpty()) {
                r.put("html", analyse(html));
                if (bodySink != null) {
                    bodySink.accept(html);
                }
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

    /** The response body, with the stream closed whichever stream it came from. */
    private static String body(HttpURLConnection c, int status, int maxBytes) throws IOException {
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            return in == null ? "" : read(in, maxBytes);
        }
    }

    public static JSONObject analyse(String html) {
        JSONObject o = new JSONObject();
        headings(o, html);
        metaTags(o, html);
        languages(o, html);
        images(o, html);
        structuredData(o, html);

        o.put("links", count(ANCHOR, html));
        // Matches the literal media type anywhere in the document, not only in a
        // script tag. Reported for information; the structured-data CHECK reads
        // jsonLdTypes, which is scoped to real ld+json blocks.
        o.put("jsonLd", count(JSONLD, html));

        String text = textOf(html);
        o.put("words", text.isEmpty() ? 0 : text.split(" ").length);
        return o;
    }

    /** The title and the h1 outline: what a crawler reads as the page's name. */
    private static void headings(JSONObject o, String html) {
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
        o.put("h2Count", count(H2, html));
    }

    /** The head's instructions to a crawler, and where it says the page really lives. */
    private static void metaTags(JSONObject o, String html) {
        o.put("metaDescription", META_DESC.matcher(html).find());

        Matcher can = CANONICAL.matcher(html);
        boolean hasCanonical = can.find();
        o.put("canonical", hasCanonical);
        // GEO-25 needs where it points, not only that it exists: a canonical
        // naming some other page is worse than none at all.
        if (hasCanonical) {
            Matcher href = HREF.matcher(can.group());
            if (href.find()) {
                o.put("canonicalHref", href.group(1));
            }
        }

        Matcher mr = META_ROBOTS.matcher(html);
        o.put("metaRobots", mr.find() ? clip(mr.group(1).trim(), 60) : JSONObject.NULL);

        // A client-side redirect. The page answers 200 with a shell, and only a
        // browser follows it. To a crawler this is the whole page.
        Matcher mf = META_REFRESH.matcher(html);
        o.put("metaRefresh", mf.find() ? clip(mf.group(1).trim(), 300) : JSONObject.NULL);

        Matcher md = MODIFIED.matcher(html);
        String modified = null;
        if (md.find()) {
            modified = md.group(1) != null ? md.group(1) : md.group(2);
        }
        o.put("dateModified", modified == null ? JSONObject.NULL : clip(modified.trim(), 40));
    }

    /** What the page says it is written in, and what else it exists as. */
    private static void languages(JSONObject o, String html) {
        Matcher lang = HTML_LANG.matcher(html);
        o.put("lang", lang.find() ? clip(lang.group(1).trim(), 20) : JSONObject.NULL);

        Matcher hl = HREFLANG.matcher(html);
        JSONArray alts = new JSONArray();
        while (hl.find() && alts.length() < MAX_HREFLANG) {
            alts.put(clip(hl.group(1).trim(), 20));
        }
        o.put("hreflang", alts);
    }

    /**
     * Alt text is how a text-only crawler learns what an image shows. An
     * explicitly empty alt is a decorative image - correct, but not descriptive,
     * so it does not count towards coverage.
     */
    private static void images(JSONObject o, String html) {
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
    }

    /**
     * Which schema types are declared. A count of ld+json blocks says nothing;
     * the types do.
     *
     * Scraped with a regex rather than parsed, and scoped to real ld+json script
     * blocks. The consequence is worth knowing: a block whose JSON is invalid
     * still yields its types here, so the structured-data check passes for a
     * page that a crawler actually parsing the block would read as having none.
     */
    private static void structuredData(JSONObject o, String html) {
        Matcher jb = JSONLD_BLOCK.matcher(html);
        JSONArray types = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        while (jb.find()) {
            Matcher jt = JSONLD_TYPE.matcher(jb.group(1));
            while (jt.find() && seen.size() < MAX_JSONLD_TYPES) {
                seen.add(clip(jt.group(1).trim(), 40));
            }
        }
        seen.forEach(types::put);
        o.put("jsonLdTypes", types);
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
}
