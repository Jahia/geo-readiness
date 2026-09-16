package org.jahia.se.modules.georeadiness.check;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * GEO-20. Readiness one language at a time.
 *
 * A single site-wide figure hides the market that is failing. The analysis this
 * module came out of found a brand answering perfectly in one language and not
 * at all in another, and an average would have reported that site as fine.
 *
 * **A language that has never been scanned reports as unmeasured, never as
 * zero.** This is the whole point of the story and the easiest thing to get
 * wrong: a zero in a comparison table reads as "this market is broken" when it
 * means "nobody has looked". They call for opposite actions - one is a content
 * problem, the other is a scan that has not been scheduled - so they are
 * different states here and are rendered differently.
 *
 * Coverage is a repository question and needs no scan at all: a node exists in a
 * language when it carries that language's translation, and the two workspaces
 * say whether it is merely translated or actually published. The score half is
 * read from whatever scans have run.
 */
public final class Languages {

    /**
     * Two keys this class writes four times each, and both carry two meanings
     * worth keeping straight: "scored" is a BOOLEAN on a row saying whether the
     * language has been scanned, and the same key on a scan's aggregate is the
     * COUNT of pages it scored. Different objects, different types, one name -
     * which is exactly the confusion that made a reader in GeoReport report
     * "stale: false" on every report it ever produced.
     */
    private static final String PERCENT = "percent";
    private static final String SCORED = "scored";

    private static final int MAX_NODES = 10_000;

    private Languages() {
    }

    /** Coverage and score for every language the site declares. */
    public static JSONObject check(String sitePath) throws RepositoryException {
        JSONObject out = new JSONObject();
        Set<String> languages = PublishedMap.languagesOf(sitePath);
        out.put("languages", new JSONArray(languages));

        Map<String, int[]> translated = countBy(sitePath, "default", languages);
        Map<String, int[]> published = countBy(sitePath, "live", languages);
        int total = translated.isEmpty() ? 0 : translated.values().iterator().next()[1];
        out.put("total", total);

        JSONArray rows = new JSONArray();
        for (String lang : languages) {
            rows.put(row(sitePath, lang, total, translated, published));
        }
        out.put("rows", rows);
        spread(out, rows);
        return out;
    }

    /** One language: how much of the site exists in it, and what a scan made of that. */
    private static JSONObject row(String sitePath, String lang, int total,
            Map<String, int[]> translated, Map<String, int[]> published) throws RepositoryException {
        JSONObject r = new JSONObject();
        r.put("language", lang);
        int t = translated.containsKey(lang) ? translated.get(lang)[0] : 0;
        int p = published.containsKey(lang) ? published.get(lang)[0] : 0;
        r.put("translated", t);
        r.put("published", p);
        r.put("missing", Math.max(0, total - t));
        r.put("coverage", total == 0 ? 0 : Math.round((t * 100f) / total));
        score(r, sitePath, lang);
        return r;
    }

    /**
     * The score this language last got, READ rather than computed.
     *
     * The score belongs to a scan, and a language nobody has scanned has no
     * score. Absent, not zero: a zero would read as "this language scores
     * nothing", which is a finding, where the truth is that nobody looked.
     */
    private static void score(JSONObject r, String sitePath, String lang) throws RepositoryException {
        JSONObject state = ScanStore.read(sitePath, lang);
        JSONObject run = state.optJSONObject("run");
        JSONObject aggregate = run == null ? null : run.optJSONObject("aggregate");
        if (aggregate == null || !aggregate.has(PERCENT)) {
            r.put(SCORED, false);
            return;
        }
        r.put(SCORED, true);
        r.put(PERCENT, aggregate.optInt(PERCENT));
        r.put("pagesScored", aggregate.optInt(SCORED));
        r.put("scannedAt", run.opt("finishedAt"));
    }

    /**
     * The comparison the story is about.
     *
     * If one market is measured and another is not, the gap between the measured
     * ones is only half the story and the interface has to say so - which is why
     * measured and unmeasured are both reported rather than only a spread.
     */
    private static void spread(JSONObject out, JSONArray rows) {
        int best = -1;
        int worst = -1;
        int unmeasured = 0;
        int measured = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            if (!r.optBoolean(SCORED)) {
                unmeasured++;
                continue;
            }
            measured++;
            int p = r.optInt(PERCENT);
            best = best < 0 ? p : Math.max(best, p);
            worst = worst < 0 ? p : Math.min(worst, p);
        }
        out.put("unmeasured", unmeasured);
        out.put("measured", measured);
        // A spread needs two things to compare. One measured language has a
        // spread of zero arithmetically, and reporting that would say "the
        // languages agree" about a site where only one was ever looked at.
        if (measured > 1) {
            out.put("spread", best - worst);
        }
    }

    /**
     * Which of the site's languages one node exists in.
     *
     * For the drawer, where it is directly actionable: an author looking at a
     * page can see it has no French and go and write it.
     */
    public static JSONObject forNode(String sitePath, String jcrPath) throws RepositoryException {
        Set<String> languages = PublishedMap.languagesOf(sitePath);
        Set<String> translated = languagesOfNode("default", jcrPath);
        Set<String> published = languagesOfNode("live", jcrPath);

        JSONArray missing = new JSONArray();
        JSONArray notPublished = new JSONArray();
        for (String lang : languages) {
            if (!translated.contains(lang)) {
                missing.put(lang);
            } else if (!published.contains(lang)) {
                notPublished.put(lang);
            }
        }
        JSONObject out = new JSONObject();
        out.put("languages", new JSONArray(languages));
        out.put("published", new JSONArray(published));
        out.put("missing", missing);
        out.put("notPublished", notPublished);
        return out;
    }

    private static Set<String> languagesOfNode(String workspace, String jcrPath) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, workspace,
                (JCRCallback<Set<String>>) session -> {
                    Set<String> out = new LinkedHashSet<>();
                    try {
                        translationsOf(session.getNode(jcrPath), out);
                    } catch (javax.jcr.PathNotFoundException e) {
                        // Not in this workspace at all, which is itself an answer.
                    }
                    return out;
                });
    }

    /**
     * Counts nodes carrying each language, and the total, in one pass.
     *
     * `int[]{withLanguage, total}` rather than two maps, because the total has
     * to be the same denominator for every language or the comparison is
     * meaningless.
     */
    /**
     * How many of the site's items exist in each language, in one workspace.
     *
     * Each slot is {translated, total}: the count for that language, and the
     * number of items the whole site has. Both are needed per row, and total is
     * the same for every one of them, which is why it is written into each slot
     * rather than returned alongside.
     */
    private static Map<String, int[]> countBy(String sitePath, String workspace, Set<String> languages)
            throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, workspace, null,
                (JCRCallback<Map<String, int[]>>) session -> {
                    Map<String, int[]> out = new LinkedHashMap<>();
                    for (String lang : languages) {
                        out.put(lang, new int[]{0, 0});
                    }
                    int total = countInto(session, sitePath, out);
                    for (int[] slot : out.values()) {
                        slot[1] = total;
                    }
                    return out;
                });
    }

    /**
     * Walks the site once per node type, counting each item's translations into
     * {@code out}, and returns how many distinct items it saw.
     *
     * Deduplicated across the two queries: a node can be both a page and a main
     * resource, and counting it twice would make the total larger than the site
     * and every coverage percentage smaller than the truth.
     */
    private static int countInto(JCRSessionWrapper session, String sitePath, Map<String, int[]> out)
            throws RepositoryException {
        Set<String> seen = new LinkedHashSet<>();
        for (String type : new String[]{"jnt:page", "jmix:mainResource"}) {
            NodeIterator it = itemsOfType(session, sitePath, type);
            while (it.hasNext()) {
                JCRNodeWrapper n = (JCRNodeWrapper) it.nextNode();
                if (seen.add(n.getPath())) {
                    countTranslations(n, out);
                }
            }
        }
        return seen.size();
    }

    private static NodeIterator itemsOfType(JCRSessionWrapper session, String sitePath, String type)
            throws RepositoryException {
        String sql = "select * from [" + type + "] as n where isdescendantnode(n, '"
                + sitePath.replace("'", "''") + "')";
        Query q = session.getWorkspace().getQueryManager().createQuery(sql, Query.JCR_SQL2);
        q.setLimit(MAX_NODES);
        return q.execute().getNodes();
    }

    /** A language this node has no slot for is a language the site does not declare. */
    private static void countTranslations(JCRNodeWrapper n, Map<String, int[]> out)
            throws RepositoryException {
        Set<String> has = new LinkedHashSet<>();
        translationsOf(n, has);
        for (String lang : has) {
            int[] slot = out.get(lang);
            if (slot != null) {
                slot[0]++;
            }
        }
    }

    /** `j:translation_<lang>` children are how Jahia records a node's languages. */
    private static void translationsOf(javax.jcr.Node n, Set<String> into) throws RepositoryException {
        NodeIterator it = n.getNodes("j:translation_*");
        while (it.hasNext()) {
            String name = it.nextNode().getName();
            int underscore = name.indexOf('_');
            if (underscore > 0) {
                into.add(name.substring(underscore + 1));
            }
        }
    }
}
