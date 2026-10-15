package org.jahia.se.modules.georeadiness.botrank;

import org.jahia.se.modules.georeadiness.check.PublishedMap;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.json.JSONObject;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Jahia page behind a cited URL.
 *
 * <p>BotRank only folds {@code www.}, the trailing slash, the anchor and tracking parameters. A Jahia
 * page also answers on its vanity URLs (old ones included, after a rename), on its {@code /sites/...}
 * path and on its render path, so string equality with one URL per page misses real citations. The
 * index is built once per sync from what Jahia knows, and consulted in this order:</p>
 * <ol>
 *   <li>a manual mapping saved by an administrator (any URL, any host of the site);</li>
 *   <li>the default public URL of every page and main resource a guest can reach, per language
 *       ({@link PublishedMap});</li>
 *   <li>every vanity URL of the site in live, active or not;</li>
 *   <li>a {@code /sites/<key>/...} or {@code /cms/render/live/<lang>/sites/<key>/...} path.</li>
 * </ol>
 * <p>A cited URL with a query that survives BotRank's folding is a different page for BotRank, and it
 * is resolved by a manual mapping only.</p>
 *
 * <p>Thread safety: immutable once built.</p>
 */
public final class PageResolver {

    /** {@code jcrPath@language}, how a page is keyed in the store. */
    public static final class Target {
        public final String jcrPath;
        public final String language;
        /** mapping, published, vanity or path. */
        public final String via;

        Target(String jcrPath, String language, String via) {
            this.jcrPath = jcrPath;
            this.language = language;
            this.via = via;
        }

        public String key() {
            return jcrPath + "@" + language;
        }
    }

    private static final Pattern RENDER_PATH = Pattern.compile(
            "^(?:/cms/render/live)?(?:/([a-z]{2}(?:_[A-Z]{2})?))?(/sites/[A-Za-z0-9_-]+/.+?)(?:\\.html)?$");

    private final String sitePath;
    private final Map<String, Target> byPath;
    private final Map<String, String> languageByJcrPath;
    private final JSONObject mappings;

    private PageResolver(String sitePath, Map<String, Target> byPath, Map<String, String> languageByJcrPath,
            JSONObject mappings) {
        this.sitePath = sitePath;
        this.byPath = byPath;
        this.languageByJcrPath = languageByJcrPath;
        this.mappings = mappings;
    }

    /**
     * Builds the index of a site.
     *
     * @param sitePath {@code /sites/<key>}
     * @param baseUrl  a base URL for {@link PublishedMap} (only paths are kept)
     * @param mappings folded cited URL to {@code jcrPath@language}
     * @return the resolver
     * @throws RepositoryException on a repository failure
     */
    public static PageResolver build(String sitePath, String baseUrl, JSONObject mappings) throws RepositoryException {
        Map<String, Target> byPath = new HashMap<>();
        Map<String, String> languageByJcrPath = new HashMap<>();
        for (Map.Entry<String, PublishedMap.Entry> e : PublishedMap.forSite(sitePath, baseUrl).entrySet()) {
            PublishedMap.Entry p = e.getValue();
            byPath.putIfAbsent(fold(e.getKey()), new Target(p.jcrPath, p.language, "published"));
            languageByJcrPath.putIfAbsent(p.jcrPath, p.language);
        }
        for (Map.Entry<String, Target> v : vanityUrls(sitePath).entrySet()) {
            byPath.putIfAbsent(v.getKey(), v.getValue());
        }
        return new PageResolver(sitePath, byPath, languageByJcrPath, mappings);
    }

    /** For unit tests: an index given as data. */
    static PageResolver of(String sitePath, Map<String, Target> byPath, Map<String, String> languageByJcrPath,
            JSONObject mappings) {
        return new PageResolver(sitePath, byPath, languageByJcrPath, mappings);
    }

    /**
     * The page behind a cited URL, or null.
     *
     * @param url a cited URL, already checked to be on one of the site's hosts
     * @return the page and the way it was found, or null when nothing matches
     */
    public Target resolve(String url) {
        String key = CitedUrls.key(url);
        if (key == null) {
            return null;
        }
        String mapped = mappings.optString(key, "");
        int at = mapped.lastIndexOf('@');
        if (at > 0) {
            return new Target(mapped.substring(0, at), mapped.substring(at + 1), "mapping");
        }
        String path = CitedUrls.pathKey(url);
        if (path == null || path.contains("?")) {
            return null;
        }
        Target t = byPath.get(path);
        if (t != null) {
            return t;
        }
        Matcher m = RENDER_PATH.matcher(path);
        if (m.matches()) {
            String jcrPath = m.group(2);
            String language = m.group(1) != null ? m.group(1) : languageByJcrPath.get(jcrPath);
            if (jcrPath.startsWith(sitePath + "/") && language != null) {
                return new Target(jcrPath, language, "path");
            }
        }
        return null;
    }

    /** A path folded the way {@link CitedUrls#pathKey} folds it. */
    static String fold(String path) {
        String p = path == null || path.isEmpty() ? "/" : path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * Every vanity URL of the site in live, folded path to target. Read with a system session: the
     * targets are checked against the published map's guest view only through the drawer's own read
     * check, and an unpublished target simply never shows up there.
     */
    private static Map<String, Target> vanityUrls(String sitePath) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, "live", null,
                (JCRCallback<Map<String, Target>>) session -> {
                    Map<String, Target> out = new HashMap<>();
                    String sql = "select * from [jnt:vanityUrl] as v where isdescendantnode(v, '"
                            + sitePath.replace("'", "''") + "')";
                    Query q = session.getWorkspace().getQueryManager().createQuery(sql, Query.JCR_SQL2);
                    q.setLimit(20_000);
                    NodeIterator it = q.execute().getNodes();
                    while (it.hasNext()) {
                        JCRNodeWrapper v = (JCRNodeWrapper) it.nextNode();
                        try {
                            if (!v.hasProperty("j:url") || !v.hasProperty("jcr:language")) {
                                continue;
                            }
                            JCRNodeWrapper target = v.getParent().getParent();
                            out.putIfAbsent(fold(v.getProperty("j:url").getString()),
                                    new Target(target.getPath(), v.getProperty("jcr:language").getString(), "vanity"));
                        } catch (RepositoryException e) {
                            // One broken vanity node must not cost the others.
                        }
                    }
                    return out;
                });
    }
}
