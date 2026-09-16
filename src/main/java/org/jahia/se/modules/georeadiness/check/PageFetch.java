package org.jahia.se.modules.georeadiness.check;

import org.json.JSONException;
import org.json.JSONArray;
import org.jahia.se.modules.georeadiness.util.FetchGuard;
import org.jahia.se.modules.georeadiness.util.Markup;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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

    /*
     * How this file reads markup, and why it reads it this way.
     *
     * The document comes from somewhere else, so tags are not found by pattern
     * at all: Markup walks the document once and hands each tag on as a short
     * string of its own. The reasoning is written out there.
     *
     * That leaves the attribute patterns below, which run against one tag.
     * They are bounded anyway - an attribute value long enough to matter does
     * not exist in the ones read here, and a bound is what makes the cost
     * independent of what the far end sends.
     */
    /** An attribute value. Two thousand is the classic limit on a URL. */
    private static final String VALUE = "[^\"']{0,2000}";
    /** A language tag, a robots directive, a schema type: short by definition. */
    private static final String SHORT = "[^\"']{1,100}";
    /** Enough to name what a page declares itself as. */
    private static final int MAX_JSONLD_TYPES = 15;
    /** A parsed tree from a page this module does not own is walked, not trusted. */
    private static final int MAX_JSONLD_DEPTH = 20;

    private static final Pattern HREF = value("href", VALUE);
    private static final Pattern CONTENT = value("content", VALUE);
    private static final Pattern ALT = value("alt", VALUE);
    private static final Pattern LANG = value("lang", SHORT);
    private static final Pattern HREFLANG = value("hreflang", SHORT);
    private static final Pattern REL_CANONICAL = is("rel", "canonical");
    private static final Pattern NAME_DESCRIPTION = is("name", "description");
    private static final Pattern NAME_ROBOTS = is("name", "robots");
    private static final Pattern EQUIV_REFRESH = is("http-equiv", "refresh");
    private static final Pattern REFRESH_URL = Pattern.compile("(?is)url=([^\"'>\\s]{1,2000})");
    private static final Pattern LD_JSON = Pattern.compile("(?is)application/ld\\+json");
    private static final Pattern JSONLD_TYPE = Pattern.compile("(?is)[\"']@type[\"']\\s{0,20}:\\s{0,20}[\"']" + captured(SHORT));
    /** Two spellings of the same fact, each simple enough to read. */
    private static final Pattern MODIFIED_LD = Pattern.compile("(?is)[\"']dateModified[\"']\\s{0,20}:\\s{0,20}[\"']" + captured(SHORT));
    private static final Pattern MODIFIED_META = Pattern.compile("(?is)article:modified_time[\"'][^>]{0,400}content=[\"']" + captured(SHORT));
    /** One pass, nothing to re-run: safe on any input. */
    private static final Pattern WS = Pattern.compile("\\s+");

    /** `name="..."`, with the value captured. */
    private static Pattern value(String name, String bound) {
        return Pattern.compile("(?is)\\b" + name + "=[\"']" + captured(bound));
    }

    /**
     * A capturing group for {@code bound}, closed by a quote.
     *
     * Named because four patterns end this way and the fragment is unreadable
     * inline - `)[\"']` is a closing paren, a class of two quote characters,
     * and two levels of escaping, which is not something to re-read four times.
     */
    private static String captured(String bound) {
        return "(" + bound + ")[\"']";
    }

    /** `name="literal"`, for an attribute read only to recognise the tag. */
    private static Pattern is(String name, String literal) {
        return Pattern.compile("(?is)\\b" + name + "=[\"']" + literal + "[\"']");
    }

    /** Elements whose body is markup for a machine, not text for a reader. */
    private static final String[] OPAQUE = {"script", "style", "noscript", "template"};

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

    /**
     * What a crawler can read out of this page, one concern at a time.
     *
     * Each step below walks the document itself rather than sharing a pass.
     * A walk is cheap - it is an indexOf per tag - and keeping them apart is
     * what lets each question be read on its own.
     */
    public static JSONObject analyse(String html) {
        JSONObject o = new JSONObject();
        headings(html, o);
        metaTags(html, o);
        linkTags(html, o);
        anchorsAndLanguage(html, o);
        images(html, o);
        structuredData(html, o);
        o.put("words", words(html));
        return o;
    }

    /** The title and the h1s: what the page says it is about. */
    private static void headings(String html, JSONObject o) {
        String[] title = {null};
        Markup.forEachElement(html, "title", (tag, body, at) -> {
            if (title[0] == null) {
                title[0] = clip(strip(body), 120);
            }
        });
        o.put("title", title[0] == null ? JSONObject.NULL : title[0]);

        int[] h1Count = {0};
        String[] firstH1 = {null};
        Markup.forEachElement(html, "h1", (tag, body, at) -> {
            if (firstH1[0] == null) {
                firstH1[0] = clip(strip(body), 120);
            }
            h1Count[0]++;
        });
        o.put("h1Count", h1Count[0]);
        o.put("h1", firstH1[0] == null ? JSONObject.NULL : firstH1[0]);

        int[] h2Count = {0};
        Markup.forEachTag(html, "h2", (tag, at, end) -> h2Count[0]++);
        o.put("h2Count", h2Count[0]);
    }

    /** The description, what robots are told, and any client-side redirect. */
    private static void metaTags(String html, JSONObject o) {
        boolean[] hasDescription = {false};
        String[] robots = {null};
        String[] refresh = {null};
        Markup.forEachTag(html, "meta", (tag, at, end) -> {
            if (NAME_DESCRIPTION.matcher(tag).find()) {
                hasDescription[0] = true;
            }
            if (robots[0] == null && NAME_ROBOTS.matcher(tag).find()) {
                robots[0] = clip(attribute(CONTENT, tag, "").trim(), 60);
            }
            // A client-side redirect. The page answers 200 with a shell, and only
            // a browser follows it. To a crawler this is the whole page.
            if (refresh[0] == null && EQUIV_REFRESH.matcher(tag).find()) {
                Matcher url = REFRESH_URL.matcher(attribute(CONTENT, tag, ""));
                if (url.find()) {
                    refresh[0] = clip(url.group(1).trim(), 300);
                }
            }
        });
        o.put("metaDescription", hasDescription[0]);
        o.put("metaRobots", robots[0] == null ? JSONObject.NULL : robots[0]);
        o.put("metaRefresh", refresh[0] == null ? JSONObject.NULL : refresh[0]);
    }

    /** The canonical, and the languages this page says it also exists in. */
    private static void linkTags(String html, JSONObject o) {
        boolean[] hasCanonical = {false};
        String[] canonicalHref = {null};
        JSONArray alts = new JSONArray();
        Markup.forEachTag(html, "link", (tag, at, end) -> {
            // GEO-25 needs where it points, not only that it exists: a canonical
            // naming some other page is worse than none at all.
            if (!hasCanonical[0] && REL_CANONICAL.matcher(tag).find()) {
                hasCanonical[0] = true;
                Matcher href = HREF.matcher(tag);
                if (href.find()) {
                    canonicalHref[0] = href.group(1);
                }
            }
            Matcher hl = HREFLANG.matcher(tag);
            if (hl.find() && alts.length() < 20) {
                alts.put(clip(hl.group(1).trim(), 20));
            }
        });
        o.put("canonical", hasCanonical[0]);
        if (canonicalHref[0] != null) {
            o.put("canonicalHref", canonicalHref[0]);
        }
        o.put("hreflang", alts);
    }

    /** How many links the page offers, and what language it declares. */
    private static void anchorsAndLanguage(String html, JSONObject o) {
        int[] links = {0};
        Markup.forEachTag(html, "a", (tag, at, end) -> {
            if (tag.toLowerCase(Locale.ROOT).contains("href=")) {
                links[0]++;
            }
        });
        o.put("links", links[0]);

        String[] lang = {null};
        Markup.forEachTag(html, "html", (tag, at, end) -> {
            Matcher m = LANG.matcher(tag);
            if (lang[0] == null && m.find()) {
                lang[0] = clip(m.group(1).trim(), 20);
            }
        });
        o.put("lang", lang[0] == null ? JSONObject.NULL : lang[0]);
    }

    /**
     * Alt text is how a text-only crawler learns what an image shows. An
     * explicitly empty alt is a decorative image, correct but not descriptive.
     */
    private static void images(String html, JSONObject o) {
        int[] images = {0};
        int[] withAlt = {0};
        Markup.forEachTag(html, "img", (tag, at, end) -> {
            images[0]++;
            if (!attribute(ALT, tag, "").trim().isEmpty()) {
                withAlt[0]++;
            }
        });
        o.put("images", images[0]);
        o.put("imagesWithAlt", withAlt[0]);
    }

    /** The ld+json blocks, and the one date a crawler is most likely to believe. */
    private static void structuredData(String html, JSONObject o) {
        o.put("jsonLd", count(LD_JSON, html));

        // A count of ld+json blocks says nothing. Which schema types are declared does.
        JSONArray types = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        Markup.forEachElement(html, "script", (tag, body, at) -> {
            if (LD_JSON.matcher(tag).find()) {
                declaredTypes(body, seen);
            }
        });
        seen.forEach(types::put);
        o.put("jsonLdTypes", types);

        String modified = earlier(MODIFIED_LD, MODIFIED_META, html);
        o.put("dateModified", modified == null ? JSONObject.NULL : clip(modified.trim(), 40));
    }

    /**
     * Whichever of two patterns matches first, as one alternation used to.
     *
     * The page decides which spelling it uses and can carry both; taking the
     * earlier one keeps the answer the same as when a single pattern with an
     * alternation walked the document once.
     */
    private static String earlier(Pattern a, Pattern b, String html) {
        Matcher ma = a.matcher(html);
        Matcher mb = b.matcher(html);
        boolean foundA = ma.find();
        boolean foundB = mb.find();
        if (foundA && (!foundB || ma.start() <= mb.start())) {
            return ma.group(1);
        }
        return foundB ? mb.group(1) : null;
    }

    private static int words(String html) {
        String text = textOf(html);
        return text.isEmpty() ? 0 : text.split(" ").length;
    }

    /** The first match of an attribute pattern against one tag, or `orElse`. */
    private static String attribute(Pattern p, String tag, String orElse) {
        Matcher m = p.matcher(tag);
        return m.find() ? m.group(1) : orElse;
    }

    /** Visible text: tags dropped, and the bodies of OPAQUE elements with them. */
    /**
     * The schema types one ld+json block declares - PARSED, not scraped.
     *
     * It used to be a regex for `"@type": "..."` over the block's raw text, and
     * that reported types out of a block no consumer could read. A crawler parses
     * the JSON; if it does not parse, the page has declared nothing, whatever the
     * text looks like. Scraping it made the structured-data check PASS for a page
     * whose JSON-LD is broken - a false pass on the exact thing that check exists
     * to find, which is the worst direction for it to be wrong in.
     */
    private static void declaredTypes(String body, Set<String> seen) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            walk(text.startsWith("[") ? new JSONArray(text) : new JSONObject(text), seen, 0);
        } catch (JSONException e) {
            // Not parseable is not a finding here: llmsPresent and the checks in
            // GeoScore speak to what is missing. This one only reports what is
            // genuinely declared.
            logger.debug("ld+json block did not parse", e);
        }
    }

    /**
     * Every @type anywhere in the document, @graph and nesting included.
     *
     * Depth-limited because this is parsed from a page the module does not own.
     * org.json builds the whole tree before this walks it, so the cap is not
     * about the parse; it is about not recursing a few thousand frames deep on a
     * document built to do exactly that.
     */
    private static void walk(Object node, Set<String> seen, int depth) {
        if (depth > MAX_JSONLD_DEPTH || seen.size() >= MAX_JSONLD_TYPES) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            addTypes(o.opt("@type"), seen);
            for (String key : o.keySet()) {
                walk(o.get(key), seen, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                walk(a.get(i), seen, depth + 1);
            }
        }
    }

    /** @type is a string or an array of them; anything else declares nothing. */
    private static void addTypes(Object type, Set<String> seen) {
        if (type instanceof String) {
            seen.add(clip(((String) type).trim(), 40));
            return;
        }
        if (type instanceof JSONArray) {
            JSONArray a = (JSONArray) type;
            for (int i = 0; i < a.length() && seen.size() < MAX_JSONLD_TYPES; i++) {
                if (a.get(i) instanceof String) {
                    seen.add(clip(a.getString(i).trim(), 40));
                }
            }
        }
    }

    private static String textOf(String html) {
        return WS.matcher(withoutTags(html, true, " ")).replaceAll(" ").trim();
    }

    /** The same for a fragment already captured, which carries no script of its own. */
    private static String strip(String s) {
        return WS.matcher(withoutTags(s, false, "")).replaceAll(" ").trim();
    }

    /**
     * Tags removed by one left-to-right scan, for the reason Markup sets out.
     *
     * `gap` is what a removed tag leaves behind: a space where it separated two
     * words of running text, nothing where it sat inside one heading.
     */
    private static String withoutTags(String html, boolean dropOpaqueBodies, String gap) {
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (i >= 0 && i < html.length()) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                // No tag left: what remains is all text.
                out.append(html, i, html.length());
                i = -1;
                continue;
            }
            out.append(html, i, lt).append(gap);
            int gt = html.indexOf('>', lt);
            // A tag that never ends ends the document: nothing after it is
            // something any reading could turn back into markup.
            i = gt < 0 ? -1 : after(html, lt, gt, dropOpaqueBodies);
        }
        return out.toString();
    }

    /** Where reading resumes: past the tag, or past the element it opened. */
    private static int after(String html, int lt, int gt, boolean dropOpaqueBodies) {
        String opaque = dropOpaqueBodies ? opaqueAt(html, lt) : null;
        if (opaque == null) {
            return gt + 1;
        }
        String closer = "</" + opaque + ">";
        int end = Markup.indexOfIgnoreCase(html, closer, gt + 1);
        return end < 0 ? html.length() : end + closer.length();
    }

    /** The opaque element opening at `lt`, or null for any other tag. */
    private static String opaqueAt(String html, int lt) {
        for (String name : OPAQUE) {
            int after = lt + 1 + name.length();
            if (after < html.length()
                    && html.regionMatches(true, lt + 1, name, 0, name.length())
                    && (html.charAt(after) == '>' || Character.isWhitespace(html.charAt(after)))) {
                return name;
            }
        }
        return null;
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
