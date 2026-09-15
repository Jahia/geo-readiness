package org.jahia.se.modules.georeadiness.check;

import org.jahia.se.modules.georeadiness.util.PublicUrls;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GEO-21. Where the sitemap and the site disagree.
 *
 * Both sides are ours, so the comparison costs almost nothing and it catches
 * real rot: a crawler handed a stale map wastes its budget on pages that are
 * gone and never sees the ones that are new.
 *
 * **Entries are resolved against the repository, not fetched.** Checking a
 * thousand-entry sitemap by requesting every URL is a crawl of your own site,
 * and it is also less accurate: a soft 404 answers 200 and passes. Asking
 * whether a URL corresponds to something published answers the same question
 * definitively and for free. The cost is that a URL broken by infrastructure
 * rather than by content looks fine here; the per-page check is where an actual
 * fetch happens.
 *
 * The sitemap module lists pages *and* `jmix:mainResource` content, so both are
 * collected. Comparing pages alone would report every article as an unknown
 * entry.
 */
public final class SitemapCheck {

    private static final Logger logger = LoggerFactory.getLogger(SitemapCheck.class);

    /** Parsed rather than XML-parsed: no namespaces to fight, no XXE surface. */
    private static final Pattern URL_BLOCK = Pattern.compile("(?is)<url>(.*?)</url>");
    private static final Pattern LOC = Pattern.compile("(?is)<loc>\\s*(.*?)\\s*</loc>");
    private static final Pattern LASTMOD = Pattern.compile("(?is)<lastmod>\\s*(.*?)\\s*</lastmod>");
    private static final Pattern SITEMAP_BLOCK = Pattern.compile("(?is)<sitemap>(.*?)</sitemap>");

    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_REPORTED = 200;

    private SitemapCheck() {
    }

    public static JSONObject check(String sitePath, String language, String baseUrl,
            int timeoutMs, int maxBytes) throws RepositoryException {
        JSONObject out = new JSONObject();
        out.put("sitePath", sitePath);
        out.put("language", language);

        String base = baseUrl.replaceAll("/+$", "");
        SiteFilesChecker.Fetched index = SiteFilesChecker.fetch(base + "/sitemap.xml", base, timeoutMs, maxBytes);
        boolean answered = index.status != null && index.status == 200 && index.body != null;
        // "200 and contains a tag" is not good enough. Anything answering that
        // address with an HTML page - a proxy's catch-all, a vanity URL, a
        // custom error page served as 200 - would pass that test, parse to zero
        // entries, and make every published page look missing from the sitemap.
        // It has to actually be a sitemap. Same false positive the llms.txt
        // check already guards against.
        boolean present = answered && looksLikeSitemap(index.body);
        out.put("present", present);
        out.put("status", index.status == null ? JSONObject.NULL : index.status);
        out.put("url", base + "/sitemap.xml");

        if (!present) {
            // Not a defect in itself, but everything below is unanswerable, and
            // the three ways of having no usable sitemap call for three
            // different responses, so name which one this is.
            out.put("reason", answered ? "notSitemap"
                    : (index.status == null ? "unreachable" : "none"));
            return out;
        }

        Map<String, String> entries = new LinkedHashMap<>();
        collect(index.body, base, entries, timeoutMs, maxBytes, 0);
        out.put("entries", entries.size());

        Map<String, PublishedMap.Entry> published = PublishedMap.forSite(sitePath, base);
        out.put("published", published.size());

        JSONArray missing = new JSONArray();
        JSONArray unknown = new JSONArray();
        JSONArray staleDate = new JSONArray();
        JSONArray noindexListed = new JSONArray();
        JSONArray redirects = new JSONArray();

        // The sitemap module does not use vanity urls, so a page that has one is
        // listed under the address Jahia redirects *from*. Left alone that reads
        // as two unrelated findings - the real address missing, the listed one
        // unknown - when it is one thing: the sitemap is handing crawlers a
        // redirect instead of the page. Recognise the pair and report it once.
        Set<String> pairedPrimary = new LinkedHashSet<>();
        Set<String> pairedAlt = new LinkedHashSet<>();
        for (String listed : entries.keySet()) {
            PublishedMap.Entry moved = PublishedMap.movedFrom(published, sitePath, listed);
            if (moved == null) {
                continue;
            }
            // The node is published, at an address the sitemap does not list,
            // while the sitemap lists one that redirects to it. One problem.
            String now = null;
            for (Map.Entry<String, PublishedMap.Entry> p : published.entrySet()) {
                if (p.getValue() == moved && !entries.containsKey(p.getKey())) {
                    now = p.getKey();
                    break;
                }
            }
            if (now == null) {
                continue;
            }
            if (redirects.length() < MAX_REPORTED) {
                redirects.put(row(moved, now, listed));
            }
            pairedPrimary.add(now);
            pairedAlt.add(listed);
        }

        for (Map.Entry<String, PublishedMap.Entry> p : published.entrySet()) {
            if (!entries.containsKey(p.getKey()) && !pairedPrimary.contains(p.getKey())
                    && missing.length() < MAX_REPORTED) {
                missing.put(row(p.getValue(), p.getKey(), null));
            }
        }

        for (Map.Entry<String, String> e : entries.entrySet()) {
            PublishedMap.Entry node = published.get(e.getKey());
            if (node == null) {
                if (pairedAlt.contains(e.getKey())) {
                    // Already reported, as the redirect it is.
                    continue;
                }
                if (unknown.length() < MAX_REPORTED) {
                    JSONObject r = new JSONObject();
                    r.put("path", e.getKey());
                    unknown.put(r);
                }
                continue;
            }
            if (node.noindex && noindexListed.length() < MAX_REPORTED) {
                noindexListed.put(row(node, e.getKey(), null));
            }
            String claimed = e.getValue();
            String actual = node.modifiedOn;
            if (claimed != null && actual != null && !claimed.startsWith(actual)
                    && staleDate.length() < MAX_REPORTED) {
                staleDate.put(row(node, e.getKey(), claimed + " → " + actual));
            }
        }

        out.put("missing", missing);
        out.put("unknown", unknown);
        out.put("staleDate", staleDate);
        out.put("noindexListed", noindexListed);
        out.put("redirects", redirects);
        out.put("agrees", missing.length() == 0 && unknown.length() == 0
                && staleDate.length() == 0 && noindexListed.length() == 0
                && redirects.length() == 0);
        return out;
    }

    /**
     * Was this page found to be published and absent from the sitemap?
     *
     * Deliberately not "is it listed". Entry paths are not stored, because a
     * large site's would dwarf everything else in the record, so the only
     * question answerable from the stored scan is whether the page turned up in
     * the missing list. Anything else is unknown, and unknown must not become a
     * warning: a gated page is correctly absent from the sitemap and absent from
     * the missing list too, and warning about it would be wrong twice.
     */
    public static boolean isMissing(JSONObject report, String jcrPath, String language) {
        return find(report, "missing", jcrPath, language) != null;
    }

    /**
     * The sitemap's `lastmod` for this page against its real modification date,
     * as "claimed → actual", or null when they agree. A crawler that trusts a
     * stale date has no reason to come back for content that did change.
     */
    public static String staleDetail(JSONObject report, String jcrPath, String language) {
        JSONObject row = find(report, "staleDate", jcrPath, language);
        return row == null ? null : row.optString("detail", null);
    }

    /**
     * Whether the sitemap advertises this page while the page itself says
     * `noindex`. Two of our own files contradicting each other, which is worth
     * saying on the page it happens to.
     */
    public static boolean isNoindexListed(JSONObject report, String jcrPath, String language) {
        return find(report, "noindexListed", jcrPath, language) != null;
    }

    /**
     * The row for this node in one of the finding lists, or null.
     *
     * Matching is on path **and** language: the stored scan covers every
     * language of the site, so the same node appears once per language and
     * matching on path alone would show the French finding on the English page.
     * A row with no language predates that field and matches on path alone, so
     * a stored scan from an older run still answers rather than going silent.
     */
    private static JSONObject find(JSONObject report, String kind, String jcrPath, String language) {
        if (report == null || !report.optBoolean("present", false) || jcrPath == null) {
            return null;
        }
        JSONArray rows = report.optJSONArray(kind);
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!jcrPath.equals(row.optString("jcrPath", null))) {
                continue;
            }
            String rowLang = row.optString("language", null);
            if (rowLang == null || rowLang.isEmpty() || rowLang.equals(language)) {
                return row;
            }
        }
        return null;
    }

    /**
     * Whether the body is a sitemap at all, rather than whatever else happened
     * to answer that address with a 200.
     */
    private static boolean looksLikeSitemap(String body) {
        String head = body.length() > 4096 ? body.substring(0, 4096) : body;
        return head.contains("<urlset") || head.contains("<sitemapindex");
    }

    /**
     * Follows a sitemap index one level down. Depth is capped because a sitemap
     * that points at itself should not become an infinite fetch.
     */
    private static void collect(String xml, String base, Map<String, String> into,
            int timeoutMs, int maxBytes, int depth) {
        Matcher urls = URL_BLOCK.matcher(xml);
        boolean any = false;
        while (urls.find() && into.size() < MAX_ENTRIES) {
            any = true;
            String block = urls.group(1);
            Matcher loc = LOC.matcher(block);
            if (!loc.find()) {
                continue;
            }
            Matcher mod = LASTMOD.matcher(block);
            into.put(PublishedMap.pathOf(loc.group(1)), mod.find() ? mod.group(1) : null);
        }
        if (any || depth >= 1) {
            return;
        }

        Matcher children = SITEMAP_BLOCK.matcher(xml);
        while (children.find() && into.size() < MAX_ENTRIES) {
            Matcher loc = LOC.matcher(children.group(1));
            if (!loc.find()) {
                continue;
            }
            // A sitemap index names its children, and the file is fetched
            // content: only a child on the site's own origin is followed.
            SiteFilesChecker.Fetched child = SiteFilesChecker.fetch(loc.group(1), base, timeoutMs, maxBytes);
            if (child.status != null && child.status == 200 && child.body != null) {
                collect(child.body, base, into, timeoutMs, maxBytes, depth + 1);
            }
        }
    }

    /**
     * Public path to node, for everything the sitemap could legitimately list.
     *
     * Two things this has to get right, both of which produced false positives
     * on the first run.
     *
     * **Every language, not one.** A sitemap index covers the whole site, so
     * checking English against English-only URLs reported all 189 French
     * entries as unknown. The paths carry their own language prefix, so one map
     * across every active language resolves them all.
     *
     * **As `guest`, not as system.** A page a visitor cannot open has no
     * business in a sitemap, so its absence is correct. Building the map with a
     * system session reported the one gated page on the test site as missing,
     * which would have sent somebody to add it.
     */
    private static JSONObject row(PublishedMap.Entry node, String path, String detail) {
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("jcrPath", node.jcrPath);
        o.put("title", node.title);
        o.put("language", node.language);
        o.put("detail", detail == null ? JSONObject.NULL : detail);
        return o;
    }
}
