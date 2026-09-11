package org.jahia.se.modules.georeadiness.check;

import org.jahia.se.modules.georeadiness.util.PublicUrls;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Everything a visitor with no account can reach, keyed by the public path it is
 * reachable at.
 *
 * Shared, because two features need exactly this and getting it wrong is not
 * obvious from the results. Both subtleties below were live false positives
 * before they were fixed, and duplicating the walk would mean paying for them
 * twice.
 *
 * **Every language, not one.** A sitemap index and a rendered page both carry
 * URLs for every language of the site. Building the map for one language
 * reported all 189 French URLs as unresolvable.
 *
 * **As `guest`, not as system.** A page a visitor cannot open is legitimately
 * absent from a sitemap and legitimately unlinked. Building the map with a
 * system session reported the one gated page on the test site as a finding,
 * which would have sent somebody to "fix" a page that was closed on purpose.
 *
 * Pages *and* `jmix:mainResource` content, because on a site whose articles are
 * content rather than pages the articles are most of the public URLs.
 */
public final class PublishedMap {

    private static final Logger logger = LoggerFactory.getLogger(PublishedMap.class);

    private static final int MAX_ENTRIES = 10_000;

    /**
     * What is known about one published thing, read while its session is open.
     *
     * A `JCRNodeWrapper` must never outlive the callback it came from: the
     * session closes on return and every later call throws, which a defensive
     * catch then turns into a silent wrong answer. That is exactly what happened
     * here on the first run, and it read as "nothing to report".
     */
    public static final class Entry {
        public final String jcrPath;
        public final String title;
        public final String modifiedOn;
        public final boolean noindex;
        /** True for `jnt:page`, false for content that merely has its own URL. */
        public final boolean page;
        /**
         * The language this URL belongs to. The same node appears once per
         * language, so without this a finding about the French URL would be
         * reported against the English page.
         */
        public final String language;

        Entry(String jcrPath, String title, String modifiedOn, boolean noindex, boolean page, String language) {
            this.jcrPath = jcrPath;
            this.title = title;
            this.modifiedOn = modifiedOn;
            this.noindex = noindex;
            this.page = page;
            this.language = language;
        }
    }

    private PublishedMap() {
    }

    /** Public path to node, for everything a guest can reach, in every language. */
    public static Map<String, Entry> forSite(String sitePath, String base) throws RepositoryException {
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
                                    n.getPath(), titleOf(n), modifiedOn(n), isNoindex(n),
                                    n.isNodeType("jnt:page"), lang));
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

    /**
     * The site's home page, which is never a finding about linking: it is the
     * entry point every menu and every logo points at, and "nothing links to
     * your home page" is not a job anyone can action.
     */
    public static String homePath(String sitePath) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live",
                (JCRCallback<String>) session -> {
                    try {
                        JCRNodeWrapper site = session.getNode(sitePath);
                        JCRNodeWrapper home = site instanceof org.jahia.services.content.decorator.JCRSiteNode
                                ? ((org.jahia.services.content.decorator.JCRSiteNode) site).getHome()
                                : null;
                        return home == null ? "" : home.getPath();
                    } catch (RepositoryException e) {
                        return "";
                    }
                });
    }

    /** The site's active languages, so every URL can be resolved. */
    public static Set<String> languagesOf(String sitePath) throws RepositoryException {
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
    public static String pathOf(String url) {
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
}
