package org.jahia.se.modules.georeadiness.check;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.se.modules.georeadiness.util.PublicUrls;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * GEO-17. Scores every published page of a site, on a schedule.
 *
 * One fetch per page, not sixteen. The drawer fetches as every crawler because
 * comparing them is the point there; doing that site-wide would be 800,000
 * requests against a 50,000 page site and about fifteen hours. Once per page as
 * a single AI crawler takes roughly an hour, which is a job you can run at
 * night. The cost is the thin-content ratio, which needs a second fetch to
 * compare against; a page that only renders through JavaScript is still caught
 * by the word-count check, and the per-crawler comparison stays in the drawer.
 *
 * Scoring runs through {@link GeoScore} over a report shaped exactly like the
 * drawer's, so the site score and the page score cannot drift apart. The one
 * check that genuinely needs several agents is dropped rather than allowed to
 * pass for free.
 */
public final class SiteScorer {

    private static final Logger logger = LoggerFactory.getLogger(SiteScorer.class);

    /** Needs two fetches to mean anything, so it is not part of a site score. */
    private static final String MULTI_AGENT_ONLY = "sameContentForCrawlers";

    /** Written back this often. Often enough to look alive, rare enough not to hammer the repository. */
    private static final int PROGRESS_EVERY = 25;

    /** Worst pages kept in full. Beyond this the tail stops being read by anyone. */
    private static final int MAX_FAILURES = 500;

    private SiteScorer() {
    }

    public static class Options {
        public int fetchTimeoutMs = 8000;
        public int maxBodyBytes = 1_500_000;
        public int maxPages = 10_000;
        public String publicBaseUrl = "";
        /** Blank scans the whole site; otherwise only this subtree. */
        public String scope = "";
    }

    /**
     * Scans one site in one language and stores the result. Long-running by
     * nature: the caller decides whether that is a job or a request.
     */
    public static JSONObject scan(String sitePath, String language, Options opts) throws RepositoryException {
        String root = opts.scope == null || opts.scope.trim().isEmpty() ? sitePath : opts.scope.trim();
        Locale locale = Locale.forLanguageTag(language);

        List<String> paths = JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live", locale,
                (JCRCallback<List<String>>) session -> publishedPages(session, root, opts.maxPages));

        ScanStore.beginRun(sitePath, language, paths.size());

        // The two site files are fetched once for the whole site, not once per
        // page: they are the same file for every row.
        String siteBase = baseFor(sitePath, language, opts);
        JSONObject siteFiles = SiteFilesChecker.check(siteBase, "/", opts.fetchTimeoutMs, opts.maxBodyBytes);
        JSONObject robotsJson = siteFiles.optJSONObject("robots");
        RobotsRules rules = robotsJson != null && robotsJson.optBoolean("present", false)
                ? RobotsRules.parse(robotsJson.optString("rawBody", ""))
                : RobotsRules.empty();

        JSONArray failures = new JSONArray();
        Map<String, Integer> failCounts = new TreeMap<>();
        Map<String, int[]> bySection = new LinkedHashMap<>();
        TemplateRollup.Accumulator byTemplate = new TemplateRollup.Accumulator();
        int scored = 0;
        int totalPassed = 0;
        int totalChecks = 0;
        int criticalPages = 0;
        int unreadable = 0;

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            try {
                JSONObject one = scorePage(sitePath, path, language, rules, siteFiles, opts);
                if (one == null) {
                    continue;
                }

                boolean pageUnreadable = !one.optBoolean("guestReadable", true);
                if (pageUnreadable) {
                    unreadable++;
                }

                JSONObject score = one.getJSONObject("score");
                scored++;
                totalPassed += score.optInt("passed", 0);
                totalChecks += score.optInt("total", 0);
                boolean critical = score.optInt("criticalFailed", 0) > 0;
                if (critical) {
                    criticalPages++;
                }

                String section = sectionOf(sitePath, path);
                int[] agg = bySection.computeIfAbsent(section, k -> new int[3]);
                agg[0]++;
                agg[1] += score.optInt("passed", 0);
                agg[2] += score.optInt("total", 0);

                JSONArray failed = one.getJSONArray("failed");
                byTemplate.add(one.optString("template", ""), failed);
                for (int f = 0; f < failed.length(); f++) {
                    String id = failed.getString(f);
                    failCounts.merge(id, 1, Integer::sum);
                }

                // Only pages with something wrong are kept, worst first by virtue
                // of critical failures being rarer than advisory ones.
                if (failed.length() > 0 && failures.length() < MAX_FAILURES) {
                    JSONObject row = new JSONObject();
                    row.put("path", path);
                    row.put("title", one.optString("title", ""));
                    row.put("template", one.optString("template", ""));
                    row.put("passed", score.optInt("passed", 0));
                    row.put("total", score.optInt("total", 0));
                    row.put("critical", score.optInt("criticalFailed", 0));
                    row.put("failed", failed);
                    failures.put(row);
                }
            } catch (Exception e) {
                logger.debug("scoring failed for {}", path, e);
            }

            if ((i + 1) % PROGRESS_EVERY == 0) {
                ScanStore.progress(sitePath, language, i + 1L);
            }
        }

        JSONObject aggregate = new JSONObject();
        aggregate.put("pages", paths.size());
        aggregate.put("scored", scored);
        aggregate.put("unreadable", unreadable);
        aggregate.put("criticalPages", criticalPages);
        aggregate.put("truncated", paths.size() >= opts.maxPages);
        // One number everybody will quote, so it is stated as what it is: the
        // share of checks that passed across every page scored.
        aggregate.put("percent", totalChecks == 0 ? 0 : Math.round(totalPassed * 100.0 / totalChecks));
        aggregate.put("failCounts", new JSONObject(failCounts));

        JSONArray sections = new JSONArray();
        bySection.forEach((name, agg) -> {
            JSONObject s = new JSONObject();
            s.put("section", name);
            s.put("pages", agg[0]);
            s.put("percent", agg[2] == 0 ? 0 : Math.round(agg[1] * 100.0 / agg[2]));
            sections.put(s);
        });
        aggregate.put("sections", sections);
        // GEO-18. Ranked by pages rendered, so the biggest single fix is first.
        aggregate.put("templates", byTemplate.toJson());

        ScanStore.progress(sitePath, language, paths.size());
        ScanStore.finishRun(sitePath, language, aggregate, failures);
        return aggregate;
    }

    /** One page: is it readable, what does its HTML contain, what does that score. */
    private static JSONObject scorePage(String sitePath, String path, String language,
            RobotsRules rules, JSONObject siteFiles, Options opts) throws RepositoryException {
        JSONObject visibility = GuestVisibility.forPage(path, language);
        if (!visibility.optBoolean("published", false)) {
            return null;
        }

        JSONObject out = new JSONObject();
        out.put("guestReadable", visibility.optBoolean("guestReadable", true));

        String url = JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live",
                Locale.forLanguageTag(language), (JCRCallback<String>) session -> {
                    JCRNodeWrapper n = session.getNode(path);
                    out.put("title", title(n));
                    out.put("template", template(n));
                    try {
                        return PublicUrls.forNode(n, opts.publicBaseUrl);
                    } catch (Exception e) {
                        return null;
                    }
                });

        JSONObject agent;
        if (!visibility.optBoolean("guestReadable", true) || url == null) {
            // No point fetching a page a visitor cannot open. The score still
            // records it, and the critical guestReadable check does the talking.
            agent = new JSONObject();
            agent.put("name", "GPTBot");
            agent.put("status", JSONObject.NULL);
        } else {
            agent = PageFetch.probe(url, "GPTBot", agentUa(), opts.fetchTimeoutMs, opts.maxBodyBytes);
        }

        // A report shaped like the drawer's, so one scorer serves both.
        JSONObject report = new JSONObject();
        report.put("published", true);
        report.put("url", url == null ? JSONObject.NULL : url);
        JSONArray agents = new JSONArray();
        agents.put(agent);
        report.put("agents", agents);
        report.put("controlWords", agent.optJSONObject("html") != null
                ? agent.getJSONObject("html").optInt("words", 0) : 0);
        report.put("blockedCount", agent.optInt("status", 0) == 200 ? 0 : 1);
        report.put("visibility", visibility);

        int mismatches = 0;
        RobotsRules.Verdict v = rules.evaluate("GPTBot", pathOf(url));
        if (v.allowed && agent.optInt("status", 0) != 200 && !agent.isNull("status")) {
            mismatches++;
        } else if (!v.allowed && agent.optInt("status", 0) == 200) {
            mismatches++;
        }
        report.put("blockedButAllowedCount", 0);
        report.put("reachableButDisallowedCount", mismatches);
        report.put("siteFiles", siteFiles);

        JSONObject score = GeoScore.compute(report);
        JSONArray failed = new JSONArray();
        JSONArray checks = score.getJSONArray("checks");
        int passed = 0;
        int total = 0;
        int critical = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject c = checks.getJSONObject(i);
            if (MULTI_AGENT_ONLY.equals(c.getString("id"))) {
                continue;
            }
            total++;
            if (c.getBoolean("passed")) {
                passed++;
            } else {
                failed.put(c.getString("id"));
                if ("critical".equals(c.getString("severity"))) {
                    critical++;
                }
            }
        }
        JSONObject trimmed = new JSONObject();
        trimmed.put("passed", passed);
        trimmed.put("total", total);
        trimmed.put("criticalFailed", critical);
        out.put("score", trimmed);
        out.put("failed", failed);
        return out;
    }

    private static List<String> publishedPages(JCRSessionWrapper session, String root, int limit)
            throws RepositoryException {
        List<String> out = new ArrayList<>();
        String sql = "select * from [jnt:page] as p where isdescendantnode(p, '"
                + root.replace("'", "''") + "')";
        Query q = session.getWorkspace().getQueryManager().createQuery(sql, Query.JCR_SQL2);
        q.setLimit(limit);
        NodeIterator it = q.execute().getNodes();
        while (it.hasNext()) {
            out.add(it.nextNode().getPath());
        }
        return out;
    }

    private static String baseFor(String sitePath, String language, Options opts) throws RepositoryException {
        if (opts.publicBaseUrl != null && !opts.publicBaseUrl.trim().isEmpty()) {
            return opts.publicBaseUrl.trim().replaceAll("/+$", "");
        }
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live",
                Locale.forLanguageTag(language), (JCRCallback<String>) session ->
                        PublicUrls.base(session.getNode(sitePath), null, ""));
    }

    private static String agentUa() {
        for (AiCrawlers.Crawler c : AiCrawlers.all()) {
            if ("GPTBot".equals(c.name)) {
                return c.userAgent;
            }
        }
        return AiCrawlers.CONTROL_UA;
    }

    private static String pathOf(String url) {
        try {
            return new java.net.URL(url).getPath();
        } catch (Exception e) {
            return "/";
        }
    }

    /** The first path segment under the site, which is what people call a section. */
    private static String sectionOf(String sitePath, String path) {
        String rel = path.startsWith(sitePath) ? path.substring(sitePath.length()) : path;
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        int slash = rel.indexOf('/');
        String first = slash < 0 ? rel : rel.substring(0, slash);
        return first.isEmpty() ? "/" : first;
    }

    private static String title(JCRNodeWrapper n) {
        try {
            return n.hasProperty("jcr:title") ? n.getProperty("jcr:title").getString() : n.getName();
        } catch (RepositoryException e) {
            return n.getName();
        }
    }

    /** The template is what makes a finding fixable once instead of four hundred times. */
    private static String template(JCRNodeWrapper n) {
        try {
            return n.hasProperty("j:templateName") ? n.getProperty("j:templateName").getString() : "";
        } catch (RepositoryException e) {
            return "";
        }
    }
}
