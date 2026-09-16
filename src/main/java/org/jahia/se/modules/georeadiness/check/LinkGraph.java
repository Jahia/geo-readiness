package org.jahia.se.modules.georeadiness.check;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GEO-22. What nothing links to.
 *
 * A crawler can report what it found. It cannot report what it missed, because
 * it never knew the page existed. We know: the repository has the full list of
 * what is published, so the pages no crawler will reach are the difference
 * between that list and what the site actually links to.
 *
 * **Links are read from the rendered HTML, not from the repository.** A crawler
 * follows `<a href>`, so that is the only thing that decides reachability. The
 * scan already fetches every page once, so the graph costs no extra requests,
 * and reading the rendered output means every way of producing a link counts
 * the same: a navigation menu built from the page tree, a listing that queries
 * content, a rich text link, a configured call to action. A repository-only
 * graph would have missed all four on the test site, where the main navigation
 * holds no link nodes at all.
 *
 * Repository references are then added on top, because they cover the one thing
 * HTML cannot: a link from a page the scan did not fetch.
 *
 * **Navigation and content links are counted apart.** A page reachable only
 * from a menu that lists every page is not really linked to - nobody chose to
 * point at it, and a crawler weighting links will treat it accordingly. A page
 * an author linked from body copy is a different thing, and the two should not
 * be added together.
 */
public final class LinkGraph {

    private static final Logger logger = LoggerFactory.getLogger(LinkGraph.class);

    private static final Pattern ANCHOR = Pattern.compile("(?is)<a\\s[^>]*?href=[\"']([^\"'#][^\"']*)[\"']");
    /**
     * Regions whose links are navigation. `<header>` is deliberately not here:
     * it is also used for the heading of a card or an article, so treating it as
     * navigation would file real content links as menu entries.
     */
    private static final String[] NAV_TAGS = {"nav", "footer"};

    private static final int MAX_REPORTED = 200;
    /** Per-page counts are kept for pages only, and only up to this many. */
    private static final int MAX_COUNTS = 2_000;

    /** Inbound counts, accumulated as the scan walks the site. */
    public static final class Accumulator {
        /** Public path -> how many distinct pages link to it, by kind. */
        private final Map<String, int[]> inbound = new LinkedHashMap<>();
        private int pagesRead;

        private int[] slot(String path) {
            return inbound.computeIfAbsent(path, k -> new int[2]);
        }
    }

    private static final int NAV = 0;
    private static final int CONTENT = 1;

    private LinkGraph() {
    }

    /**
     * Records the links one rendered page points at.
     *
     * Counted once per source page per target: a menu repeated in a mobile and a
     * desktop variant is one editor decision, not two, and a listing that shows
     * the same article twice does not make it twice as linked.
     */
    public static void addPage(Accumulator acc, String fromPath, String html) {
        if (acc == null || html == null || html.isEmpty()) {
            return;
        }
        acc.pagesRead++;

        List<int[]> navRegions = new ArrayList<>();
        for (String tag : NAV_TAGS) {
            navRegions.addAll(regions(html, tag));
        }

        Map<String, Integer> seen = new LinkedHashMap<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            String href = m.group(1).trim();
            if (!href.startsWith("/")) {
                // Same-site links are emitted root-relative by the renderer.
                // Anything else is either external or an in-page fragment.
                continue;
            }
            String target = stripQuery(href);
            if (target.equals(fromPath)) {
                // A page linking to itself says nothing about reachability.
                continue;
            }
            int kind = inAny(navRegions, m.start()) ? NAV : CONTENT;
            // Content wins a tie: if the same target is linked from both a menu
            // and the body, somebody chose to point at it.
            seen.merge(target, kind, Math::max);
        }

        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            acc.slot(e.getKey())[e.getValue()]++;
        }
    }

    /**
     * Adds repository references to the graph.
     *
     * The HTML pass only sees links on pages the scan fetched. A link
     * configured on content that has its own URL - an article pointing at a
     * landing page - is invisible to it, and reporting that landing page as
     * unlinked would be wrong. A weak reference to the node covers exactly that
     * case and costs one repository read.
     *
     * Counted as content links: a reference somebody configured is an editorial
     * decision, which is what the content count means.
     */
    public static void addReferences(Accumulator acc, Map<String, PublishedMap.Entry> published,
            String language, Set<String> renderedPages) {
        if (acc == null) {
            return;
        }
        // jcrPath -> public path, so a reference can be attributed to the URL
        // the rest of the report talks about.
        Map<String, String> byJcrPath = new LinkedHashMap<>();
        for (Map.Entry<String, PublishedMap.Entry> e : published.entrySet()) {
            byJcrPath.putIfAbsent(e.getValue().jcrPath, e.getKey());
        }

        try {
            JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live",
                    Locale.forLanguageTag(language), (JCRCallback<Void>) session -> {
                        for (Map.Entry<String, String> e : byJcrPath.entrySet()) {
                            int refs = countReferences(session, e.getKey(), renderedPages);
                            if (refs > 0) {
                                acc.slot(e.getValue())[CONTENT] += refs;
                            }
                        }
                        return null;
                    });
        } catch (RepositoryException e) {
            logger.debug("reference pass failed", e);
        }
    }

    private static int countReferences(JCRSessionWrapper session, String jcrPath, Set<String> renderedPages) {
        try {
            JCRNodeWrapper n = session.getNode(jcrPath);
            int refs = 0;
            PropertyIterator it = n.getWeakReferences();
            while (it.hasNext() && refs < 50) {
                Property p = it.nextProperty();
                String from = p.getPath();
                // A reference from inside the node itself is not an inbound link.
                if (from.startsWith(jcrPath + "/")) {
                    continue;
                }
                // A reference living on a page we rendered is already counted,
                // and counted better: the HTML says whether it came out in a
                // menu or in the body. Counting it again here would add a menu
                // entry to the content total and hide a nav-only page. This
                // pass is only for sources the scan never fetched.
                if (renderedOn(from, renderedPages)) {
                    continue;
                }
                refs++;
            }
            return refs;
        } catch (RepositoryException e) {
            return 0;
        }
    }

    /** Whether this property lives on, or inside, a page the scan rendered. */
    private static boolean renderedOn(String propertyPath, Set<String> renderedPages) {
        if (renderedPages == null || renderedPages.isEmpty()) {
            return false;
        }
        String p = propertyPath;
        while (p.length() > 1) {
            int slash = p.lastIndexOf('/');
            if (slash <= 0) {
                return false;
            }
            p = p.substring(0, slash);
            if (renderedPages.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What the graph says, once the walk is done.
     *
     * Two findings, because they are two different problems. A page nothing
     * points at is invisible: a crawler reaches it only if the sitemap names it,
     * and if the sitemap does not, nothing will ever find it. A page reachable
     * only through a menu is found but uncommitted - no author thought it worth
     * linking to, and that is a weaker signal than being genuinely cited.
     *
     * Only pages are reported. Content items with their own URL are listed from
     * queries rather than linked by hand, and calling every article unlinked
     * would bury the finding that matters.
     */
    public static JSONObject report(Accumulator acc, Map<String, PublishedMap.Entry> published,
            String language, String homePath, boolean sitemapPresent, Set<String> notListed) {
        JSONObject out = new JSONObject();
        if (acc == null) {
            // Deliberately empty, not merely zeroed: no accumulator means no walk
            // happened, and "nothing links anywhere" is not what that means.
            return out;
        }
        out.put("pagesRead", acc.pagesRead);

        Findings f = new Findings(sitemapPresent, notListed);
        int pages = 0;
        for (Map.Entry<String, PublishedMap.Entry> e : published.entrySet()) {
            if (!judged(e.getValue(), language, homePath)) {
                continue;
            }
            pages++;
            f.add(acc, e.getKey(), e.getValue());
        }

        out.put("pages", pages);
        out.put("orphans", f.orphans);
        out.put("weak", f.navOnly);
        out.put("counts", f.counts);
        return out;
    }

    /**
     * Whether this entry is one this report has anything to say about.
     *
     * Three exclusions, and the middle one is not obvious. The published map
     * spans every language because a sitemap does, but a scan fetches ONE, so
     * the other languages' pages have no links OBSERVED - which is not the same
     * as no links. Judging them reported every French page as weakly linked on a
     * site whose French pages had simply not been looked at.
     */
    private static boolean judged(PublishedMap.Entry node, String language, String homePath) {
        return node.page
                && (language == null || language.equals(node.language))
                && !node.jcrPath.equals(homePath);
    }

    /** The three arrays the report is built from, and the rules that fill them. */
    private static final class Findings {
        private final JSONArray orphans = new JSONArray();
        private final JSONArray navOnly = new JSONArray();
        private final JSONObject counts = new JSONObject();
        private final boolean sitemapPresent;
        private final Set<String> notListed;

        private Findings(boolean sitemapPresent, Set<String> notListed) {
            this.sitemapPresent = sitemapPresent;
            this.notListed = notListed;
        }

        private void add(Accumulator acc, String path, PublishedMap.Entry node) {
            int[] in = acc.inbound.get(path);
            int nav = in == null ? 0 : in[NAV];
            int content = in == null ? 0 : in[CONTENT];
            record(path, node, nav, content);
            classify(path, node, nav, content);
        }

        private void record(String path, PublishedMap.Entry node, int nav, int content) {
            if (counts.length() >= MAX_COUNTS) {
                return;
            }
            JSONObject c = new JSONObject();
            c.put("nav", nav);
            c.put("content", content);
            c.put("path", path);
            counts.put(node.jcrPath + "@" + node.language, c);
        }

        private void classify(String path, PublishedMap.Entry node, int nav, int content) {
            if (nav == 0 && content == 0) {
                unlinked(path, node);
            } else if (content == 0 && navOnly.length() < MAX_REPORTED) {
                navOnly.put(row(node, path, "navOnly"));
            }
        }

        /**
         * Nothing points here. Being in the sitemap is the difference between "a
         * crawler will never hear of this" and "a crawler is told, but nobody
         * vouches for it": a campaign page deliberately out of the menus belongs
         * in the second group, not reported as broken.
         */
        private void unlinked(String path, PublishedMap.Entry node) {
            boolean listed = sitemapPresent && (notListed == null || !notListed.contains(path));
            if (!listed && orphans.length() < MAX_REPORTED) {
                orphans.put(row(node, path, null));
            } else if (listed && navOnly.length() < MAX_REPORTED) {
                navOnly.put(row(node, path, "sitemapOnly"));
            }
        }
    }

    /** Inbound counts for one node in one language, or null when not recorded. */
    public static JSONObject countsFor(JSONObject report, String jcrPath, String language) {
        if (report == null) {
            return null;
        }
        JSONObject counts = report.optJSONObject("counts");
        return counts == null ? null : counts.optJSONObject(jcrPath + "@" + language);
    }

    private static JSONObject row(PublishedMap.Entry node, String path, String why) {
        JSONObject o = new JSONObject();
        o.put("path", path);
        o.put("jcrPath", node.jcrPath);
        o.put("title", node.title);
        o.put("language", node.language);
        o.put("why", why == null ? JSONObject.NULL : why);
        return o;
    }

    private static String stripQuery(String href) {
        int q = href.indexOf('?');
        String p = q < 0 ? href : href.substring(0, q);
        int h = p.indexOf('#');
        return h < 0 ? p : p.substring(0, h);
    }

    private static boolean inAny(List<int[]> regions, int at) {
        for (int[] r : regions) {
            if (at >= r[0] && at < r[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * The spans covered by one element, counting nesting of the same tag so a
     * `<nav>` inside a `<nav>` does not close the outer one early.
     *
     * Deliberately not an HTML parse. The question is only "is this anchor
     * inside a menu", a wrong answer misfiles one link rather than breaking the
     * report, and adding a parser to a module that already reads HTML with
     * patterns would be the odd one out.
     */
    private static List<int[]> regions(String html, String tag) {
        List<int[]> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<(/?)" + tag + "(?:\\s[^>]*)?>").matcher(html);
        List<int[]> events = new ArrayList<>();
        while (m.find()) {
            events.add(new int[]{m.start(), m.group(1).isEmpty() ? 1 : -1, m.end()});
        }
        int depth = 0;
        int start = -1;
        for (int[] ev : events) {
            if (ev[1] == 1) {
                if (depth == 0) {
                    start = ev[0];
                }
                depth++;
            } else {
                depth = Math.max(0, depth - 1);
                if (depth == 0 && start >= 0) {
                    out.add(new int[]{start, ev[2]});
                    start = -1;
                }
            }
        }
        return out;
    }
}
