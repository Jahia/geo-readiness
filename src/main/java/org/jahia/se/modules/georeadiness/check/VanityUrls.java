package org.jahia.se.modules.georeadiness.check;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GEO-25. One page, several addresses.
 *
 * Jahia owns the vanity URL service, so every alias a page answers on is a
 * repository fact rather than something to be discovered by crawling. That
 * makes the duplicate-address problem answerable exactly, and cheaply: the
 * whole check is one query plus whatever the scan already fetched.
 *
 * Three findings, in the order they cost a site traffic:
 *
 * **Unresolvable.** A vanity URL registered under a language the site does not
 * serve. It cannot resolve, and it does not: the test instance had nine of
 * these under `de_DE` on a site serving `de`, every one of them a 404 while its
 * `en` sibling answered 200. Nothing links to them, nothing can.
 *
 * There is deliberately no check for two nodes claiming the same URL. Jahia
 * will not store one: saving a second `/blogs.html` renamed it to
 * `/blogs-1.html` without comment, which is also where the `-1` suffixes on
 * imported sites come from. A check that cannot fire is worse than no check,
 * because it implies somebody is watching for a risk that does not exist.
 *
 * **Duplicate.** One node answering on several live addresses in one language.
 * Every engine that counts links now has two places to put the credit, and
 * nothing tells it which is the real one - that is what `j:default` is for, so
 * the finding is loudest when none of them is marked default.
 *
 * **Canonical.** A page with more than one address and no canonical tag, or a
 * canonical pointing at something that is neither its own address nor any of
 * its aliases. Only answerable for pages the scan actually fetched, since it is
 * a fact about the rendered output rather than about the repository.
 */
public final class VanityUrls {

    private static final Logger logger = LoggerFactory.getLogger(VanityUrls.class);

    private static final int MAX_URLS = 10_000;
    private static final int MAX_REPORTED = 200;

    /** One alias, read while its session is open. */
    private static final class Alias {
        final String url;
        final String language;
        final boolean active;
        final boolean isDefault;
        final String targetPath;
        final String targetTitle;

        Alias(String url, String language, boolean active, boolean isDefault,
                String targetPath, String targetTitle) {
            this.url = url;
            this.language = language;
            this.active = active;
            this.isDefault = isDefault;
            this.targetPath = targetPath;
            this.targetTitle = targetTitle;
        }
    }

    private VanityUrls() {
    }

    /**
     * @param canonicalByPath canonical href seen in each page the scan fetched,
     *                        keyed by public path. Empty is fine: the canonical
     *                        findings are simply not produced.
     */
    public static JSONObject check(String sitePath, Map<String, String> canonicalByPath)
            throws RepositoryException {
        JSONObject out = new JSONObject();
        out.put("sitePath", sitePath);

        Set<String> siteLanguages = PublishedMap.languagesOf(sitePath);
        List<Alias> aliases = read(sitePath);
        out.put("total", aliases.size());
        out.put("languages", new JSONArray(siteLanguages));

        JSONArray unresolvable = new JSONArray();
        JSONArray duplicates = new JSONArray();
        JSONArray canonical = new JSONArray();

        // target + language -> the live addresses it answers on
        Map<String, List<Alias>> byTarget = new LinkedHashMap<>();

        for (Alias a : aliases) {
            if (!siteLanguages.contains(a.language)) {
                if (unresolvable.length() < MAX_REPORTED) {
                    unresolvable.put(row(a, a.language));
                }
                // A URL that cannot resolve is not competing with anything, so
                // it must not also be counted as a duplicate of its siblings.
                continue;
            }
            if (!a.active) {
                // Switched off on purpose. Not an address, not a finding.
                continue;
            }
            byTarget.computeIfAbsent(a.targetPath + "\n" + a.language, k -> new ArrayList<>()).add(a);
        }

        for (Map.Entry<String, List<Alias>> e : byTarget.entrySet()) {
            List<Alias> group = e.getValue();
            if (group.size() > 1 && duplicates.length() < MAX_REPORTED) {
                boolean anyDefault = group.stream().anyMatch(a -> a.isDefault);
                StringBuilder urls = new StringBuilder();
                for (Alias a : group) {
                    urls.append(urls.length() == 0 ? "" : " · ").append(a.url);
                }
                JSONObject r = row(group.get(0), urls.toString());
                r.put("why", anyDefault ? "duplicate" : "noDefault");
                duplicates.put(r);
            }
        }

        if (canonicalByPath != null && !canonicalByPath.isEmpty()) {
            canonical = canonicalFindings(byTarget, canonicalByPath);
        }

        out.put("unresolvable", unresolvable);
        out.put("duplicates", duplicates);
        out.put("canonical", canonical);
        out.put("agrees", unresolvable.length() == 0
                && duplicates.length() == 0 && canonical.length() == 0);
        return out;
    }

    /**
     * Pages that answer on more than one address and do not say which one counts.
     *
     * Only pages the scan fetched are considered, and only where an alias is
     * actually in play: a page with one address and no canonical tag is not a
     * duplicate-content problem, and saying so would bury the ones that are.
     */
    private static JSONArray canonicalFindings(Map<String, List<Alias>> byTarget,
            Map<String, String> canonicalByPath) {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, List<Alias>> e : byTarget.entrySet()) {
            List<Alias> group = e.getValue();
            Alias first = group.get(0);
            Set<String> addresses = new LinkedHashSet<>();
            for (Alias a : group) {
                addresses.add(a.url);
            }

            // Which fetched page is this node? The alias answers on its own URL
            // too, so look the canonical up under any address we have a page for.
            String pagePath = null;
            for (String address : addresses) {
                if (canonicalByPath.containsKey(address)) {
                    pagePath = address;
                    break;
                }
            }
            if (pagePath == null) {
                continue;
            }
            String href = canonicalByPath.get(pagePath);

            if (href == null || href.isEmpty()) {
                if (out.length() < MAX_REPORTED) {
                    JSONObject r = row(first, String.valueOf(addresses.size()));
                    r.put("why", "missing");
                    out.put(r);
                }
                continue;
            }
            String canonicalPath = PublishedMap.pathOf(href);
            if (!addresses.contains(canonicalPath) && !canonicalPath.equals(pagePath)
                    && out.length() < MAX_REPORTED) {
                JSONObject r = row(first, href);
                r.put("why", "elsewhere");
                out.put(r);
            }
        }
        return out;
    }

    /** Every alias on the site, flattened, read inside one session. */
    private static List<Alias> read(String sitePath) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live",
                (JCRCallback<List<Alias>>) session -> {
                    List<Alias> out = new ArrayList<>();
                    String sql = "select * from [jnt:vanityUrl] as n where isdescendantnode(n, '"
                            + sitePath.replace("'", "''") + "')";
                    Query q = session.getWorkspace().getQueryManager().createQuery(sql, Query.JCR_SQL2);
                    q.setLimit(MAX_URLS);
                    NodeIterator it = q.execute().getNodes();
                    while (it.hasNext()) {
                        JCRNodeWrapper n = (JCRNodeWrapper) it.nextNode();
                        try {
                            out.add(toAlias(n));
                        } catch (RepositoryException e) {
                            logger.debug("unreadable vanity url {}", n.getPath(), e);
                        }
                    }
                    return out;
                });
    }

    private static Alias toAlias(JCRNodeWrapper n) throws RepositoryException {
        // .../<target>/vanityUrlMapping/<alias>, so the target is two up.
        JCRNodeWrapper target = n.getParent().getParent();
        return new Alias(
                n.hasProperty("j:url") ? n.getProperty("j:url").getString() : n.getName(),
                n.hasProperty("jcr:language") ? n.getProperty("jcr:language").getString() : "",
                !n.hasProperty("j:active") || n.getProperty("j:active").getBoolean(),
                n.hasProperty("j:default") && n.getProperty("j:default").getBoolean(),
                target.getPath(),
                target.hasProperty("jcr:title") ? target.getProperty("jcr:title").getString() : target.getName());
    }

    /**
     * The conflict the last scan found on this node, or null. Carries the kind
     * and the detail, so the drawer can name the addresses rather than just
     * saying something is wrong.
     */
    public static JSONObject findingFor(JSONObject report, String jcrPath, String language) {
        if (report == null || jcrPath == null) {
            return null;
        }
        for (String kind : new String[]{"unresolvable", "duplicates", "canonical"}) {
            JSONArray rows = report.optJSONArray(kind);
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                if (!jcrPath.equals(r.optString("jcrPath", null))) {
                    continue;
                }
                // Unresolvable is the one finding that is about a language the
                // site does not serve, so it cannot be filtered by language.
                if (!"unresolvable".equals(kind) && language != null
                        && !language.equals(r.optString("language", language))) {
                    continue;
                }
                JSONObject out = new JSONObject();
                out.put("kind", kind);
                out.put("why", r.opt("why"));
                out.put("detail", r.opt("detail"));
                return out;
            }
        }
        return null;
    }

    private static JSONObject row(Alias a, String detail) {
        JSONObject o = new JSONObject();
        o.put("url", a.url);
        o.put("jcrPath", a.targetPath);
        o.put("title", a.targetTitle);
        o.put("language", a.language);
        o.put("detail", detail == null ? JSONObject.NULL : detail);
        return o;
    }
}
