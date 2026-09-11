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

    /**
     * Everything we need about a node, read while its session is still open.
     *
     * A `JCRNodeWrapper` must never outlive the callback it came from: the
     * session closes on return and every later call throws, which a defensive
     * catch then turns into a silent wrong answer. That is exactly what happened
     * here on the first run, and it read as "nothing to report".
     */
    private static final class Entry {
        final String jcrPath;
        final String title;
        final String modifiedOn;
        final boolean noindex;

        Entry(String jcrPath, String title, String modifiedOn, boolean noindex) {
            this.jcrPath = jcrPath;
            this.title = title;
            this.modifiedOn = modifiedOn;
            this.noindex = noindex;
        }
    }

    private SitemapCheck() {
    }

    public static JSONObject check(String sitePath, String language, String baseUrl,
            int timeoutMs, int maxBytes) throws RepositoryException {
        JSONObject out = new JSONObject();
        out.put("sitePath", sitePath);
        out.put("language", language);

        String base = baseUrl.replaceAll("/+$", "");
        SiteFilesChecker.Fetched index = SiteFilesChecker.fetch(base + "/sitemap.xml", timeoutMs, maxBytes);
        boolean present = index.status != null && index.status == 200
                && index.body != null && index.body.contains("<");
        out.put("present", present);
        out.put("status", index.status == null ? JSONObject.NULL : index.status);
        out.put("url", base + "/sitemap.xml");

        if (!present) {
            // Not a defect in itself, but everything below is unanswerable, and a
            // site with no sitemap is handing crawlers no map at all.
            return out;
        }

        Map<String, String> entries = new LinkedHashMap<>();
        collect(index.body, base, entries, timeoutMs, maxBytes, 0);
        out.put("entries", entries.size());

        Map<String, Entry> published = publishedByPath(sitePath, base);
        out.put("published", published.size());

        JSONArray missing = new JSONArray();
        JSONArray unknown = new JSONArray();
        JSONArray staleDate = new JSONArray();
        JSONArray noindexListed = new JSONArray();

        for (Map.Entry<String, Entry> p : published.entrySet()) {
            if (!entries.containsKey(p.getKey()) && missing.length() < MAX_REPORTED) {
                missing.put(row(p.getValue(), p.getKey(), null));
            }
        }

        for (Map.Entry<String, String> e : entries.entrySet()) {
            Entry node = published.get(e.getKey());
            if (node == null) {
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
        out.put("agrees", missing.length() == 0 && unknown.length() == 0
                && staleDate.length() == 0 && noindexListed.length() == 0);
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
    public static boolean isMissing(JSONObject report, String jcrPath) {
        if (report == null || !report.optBoolean("present", false)) {
            return false;
        }
        JSONArray missing = report.optJSONArray("missing");
        for (int i = 0; missing != null && i < missing.length(); i++) {
            if (jcrPath.equals(missing.getJSONObject(i).optString("jcrPath", null))) {
                return true;
            }
        }
        return false;
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
            into.put(pathOf(loc.group(1)), mod.find() ? mod.group(1) : null);
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
            SiteFilesChecker.Fetched child = SiteFilesChecker.fetch(loc.group(1), timeoutMs, maxBytes);
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
    private static Map<String, Entry> publishedByPath(String sitePath, String base)
            throws RepositoryException {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (String lang : languagesOf(sitePath)) {
            GuestVisibility.inGuestSession(lang, guest -> {
                Set<String> seen = new LinkedHashSet<>();
                for (String type : new String[]{"jnt:page", "jmix:mainResource"}) {
                    String sql = "select * from [" + type + "] as n where isdescendantnode(n, '"
                            + sitePath.replace("'", "''") + "')";
                    Query q = guest.getWorkspace().getQueryManager().createQuery(sql, Query.JCR_SQL2);
                    q.setLimit(MAX_ENTRIES);
                    NodeIterator it = q.execute().getNodes();
                    while (it.hasNext()) {
                        JCRNodeWrapper n = (JCRNodeWrapper) it.nextNode();
                        if (!seen.add(n.getPath())) {
                            continue;
                        }
                        try {
                            // Read now, inside the session, never after it closes.
                            out.put(pathOf(PublicUrls.forNode(n, base)), new Entry(
                                    n.getPath(), titleOf(n), modifiedOn(n), isNoindex(n)));
                        } catch (Exception e) {
                            logger.debug("no public url for {}", n.getPath(), e);
                        }
                    }
                }
                return null;
            });
        }
        return out;
    }

    /** The site's active languages, so every entry in the index can be resolved. */
    private static Set<String> languagesOf(String sitePath) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live",
                (JCRCallback<Set<String>>) session -> {
                    Set<String> langs = new LinkedHashSet<>();
                    JCRNodeWrapper site = session.getNode(sitePath);
                    if (site.hasProperty("j:languages")) {
                        for (javax.jcr.Value v : site.getProperty("j:languages").getValues()) {
                            langs.add(v.getString());
                        }
                    }
                    if (langs.isEmpty()) {
                        langs.add("en");
                    }
                    return langs;
                });
    }

    /** Compared on path, not on the whole url: host and scheme differ legitimately. */
    private static String pathOf(String url) {
        try {
            String p = new java.net.URL(url.trim()).getPath();
            return p == null || p.isEmpty() ? "/" : p;
        } catch (Exception e) {
            return url.trim();
        }
    }

    private static String modifiedOn(JCRNodeWrapper n) {
        try {
            if (!n.hasProperty("jcr:lastModified")) {
                return null;
            }
            Calendar c = n.getProperty("jcr:lastModified").getDate();
            return c == null ? null : String.format("%1$tY-%1$tm-%1$td", c);
        } catch (RepositoryException e) {
            return null;
        }
    }

    private static boolean isNoindex(JCRNodeWrapper n) {
        try {
            return n.isNodeType("jmix:noindex") || n.isNodeType("jseomix:noIndex");
        } catch (RepositoryException e) {
            return false;
        }
    }

    private static String titleOf(JCRNodeWrapper n) {
        try {
            return n.hasProperty("jcr:title") ? n.getProperty("jcr:title").getString() : n.getName();
        } catch (RepositoryException e) {
            return n.getName();
        }
    }

    private static JSONObject row(Entry node, String path, String detail) {
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("jcrPath", node.jcrPath);
        o.put("title", node.title);
        o.put("detail", detail == null ? JSONObject.NULL : detail);
        return o;
    }
}
