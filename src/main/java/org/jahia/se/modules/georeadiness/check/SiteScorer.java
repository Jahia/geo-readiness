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
import java.util.LinkedHashSet;
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
    /** The one agent a site scan fetches with: named once, used for the fetch, the rules and the report. */
    private static final String SCAN_AGENT = "GPTBot";

    /** Written back this often. Often enough to look alive, rare enough not to hammer the repository. */
    private static final int PROGRESS_EVERY = 25;

    /** Worst pages kept in full. Beyond this the tail stops being read by anyone. */
    private static final int MAX_FAILURES = 500;

    /**
     * The keys of the per-page report this walks over. Named because they are
     * read here and written by {@link GeoScore}, so a typo on either side is a
     * silent zero rather than a failure.
     */
    private static final String GUEST_READABLE = "guestReadable";
    private static final String PASSED = "passed";
    private static final String TOTAL = "total";
    private static final String CRITICAL_FAILED = "criticalFailed";

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
        return new Run(sitePath, language, opts).execute();
    }

    /**
     * One scan, with its accumulators as fields.
     *
     * This used to be a single static method that Sonar scored at a cognitive
     * complexity of 42 against a threshold of 15. The length was a symptom; the
     * cause was that eleven running totals lived as locals, so no part of the
     * walk could be lifted out without threading ten arguments through it - which
     * only trades one Sonar finding for another. Giving the run an identity makes
     * every step below a method with no arguments or one.
     *
     * Deliberately short-lived and confined to {@link #scan}: one instance per
     * scan, never shared, never reused. The accumulators are plainly mutable, and
     * that is only safe because nothing else can reach them.
     */
    private static final class Run {

        private final String sitePath;
        private final String language;
        private final Options opts;
        private final String siteBase;

        /** Fetched once for the whole site: the same file for every row. */
        private JSONObject siteFiles;
        private RobotsRules rules;
        /** GEO-21, kept because the link report reads it as well as storing it. */
        private JSONObject sitemap;

        private final LinkGraph.Accumulator links = new LinkGraph.Accumulator();
        /** GEO-25. Where each fetched page says its canonical is, keyed by the path it was fetched at. */
        private final Map<String, String> canonicals = new LinkedHashMap<>();
        private final JSONArray failures = new JSONArray();
        private final Map<String, Integer> failCounts = new TreeMap<>();
        private final Map<String, String> severities = new LinkedHashMap<>();
        private final Map<String, int[]> bySection = new LinkedHashMap<>();
        private final TemplateRollup.Accumulator byTemplate = new TemplateRollup.Accumulator();

        private int scored;
        private int totalPassed;
        private int totalChecks;
        private int criticalPages;
        private int unreadable;

        Run(String sitePath, String language, Options opts) throws RepositoryException {
            this.sitePath = sitePath;
            this.language = language;
            this.opts = opts;
            this.siteBase = baseFor(sitePath, language, opts);
        }

        JSONObject execute() throws RepositoryException {
            List<String> paths = publishedPaths();

            // Claim the run before any of the work below. Refused means someone
            // else is already scanning this site and language, and the two runs
            // would otherwise interleave their writes to the same stored state.
            if (!ScanStore.tryBeginRun(sitePath, language, paths.size())) {
                throw new ScanInProgressException(sitePath, language);
            }

            siteFiles = SiteFilesChecker.check(siteBase, "/", opts.fetchTimeoutMs, opts.maxBodyBytes);
            rules = rulesOf(siteFiles);

            walk(paths);
            JSONObject aggregate = aggregate(paths.size());
            storeSiteReports(paths);

            ScanStore.progress(sitePath, language, paths.size());
            ScanStore.finishRun(sitePath, language, aggregate, failures);
            return aggregate;
        }

        /** Every published page under the scope, or under the site when none is set. */
        private List<String> publishedPaths() throws RepositoryException {
            String root = opts.scope == null || opts.scope.trim().isEmpty() ? sitePath : opts.scope.trim();
            return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live",
                    Locale.forLanguageTag(language),
                    (JCRCallback<List<String>>) session -> publishedPages(session, root, opts.maxPages));
        }

        /**
         * One fetch per page. A page that cannot be scored is logged and skipped:
         * a single bad row must not cost the other fifty thousand.
         */
        private void walk(List<String> paths) throws RepositoryException {
            for (int i = 0; i < paths.size(); i++) {
                String path = paths.get(i);
                try {
                    fold(path, scorePage(sitePath, path, language, rules, siteFiles, opts,
                            links, canonicals, siteBase));
                } catch (Exception e) {
                    logger.debug("scoring failed for {}", path, e);
                }
                if ((i + 1) % PROGRESS_EVERY == 0) {
                    ScanStore.progress(sitePath, language, i + 1L);
                }
            }
        }

        /** One page folded into the running totals. Null means it is not published. */
        private void fold(String path, JSONObject one) {
            if (one == null) {
                return;
            }
            if (!one.optBoolean(GUEST_READABLE, true)) {
                unreadable++;
            }

            JSONObject score = one.getJSONObject("score");
            scored++;
            int passed = score.optInt(PASSED, 0);
            int total = score.optInt(TOTAL, 0);
            totalPassed += passed;
            totalChecks += total;
            if (score.optInt(CRITICAL_FAILED, 0) > 0) {
                criticalPages++;
            }

            int[] agg = bySection.computeIfAbsent(sectionOf(sitePath, path), k -> new int[3]);
            agg[0]++;
            agg[1] += passed;
            agg[2] += total;

            JSONArray failed = one.getJSONArray("failed");
            byTemplate.add(one.optString("template", ""), failed);
            recordSeverities(one);
            for (int f = 0; f < failed.length(); f++) {
                failCounts.merge(failed.getString(f), 1, Integer::sum);
            }
            recordFailure(path, one, score, failed);
        }

        /**
         * The same eighteen for every page, so recorded once: what the failure
         * matrix needs to colour a cell by how much it matters.
         */
        private void recordSeverities(JSONObject one) {
            JSONObject sev = one.optJSONObject("severities");
            if (sev == null || !severities.isEmpty()) {
                return;
            }
            for (String k : sev.keySet()) {
                severities.put(k, sev.getString(k));
            }
        }

        /**
         * Only pages with something wrong are kept, worst first by virtue of
         * critical failures being rarer than advisory ones.
         */
        private void recordFailure(String path, JSONObject one, JSONObject score, JSONArray failed) {
            if (failed.length() == 0 || failures.length() >= MAX_FAILURES) {
                return;
            }
            JSONObject row = new JSONObject();
            row.put("path", path);
            row.put("title", one.optString("title", ""));
            row.put("template", one.optString("template", ""));
            row.put(PASSED, score.optInt(PASSED, 0));
            row.put(TOTAL, score.optInt(TOTAL, 0));
            row.put("critical", score.optInt(CRITICAL_FAILED, 0));
            row.put("failed", failed);
            failures.put(row);
        }

        /** What the dashboard reads: the site, its sections and its templates. */
        private JSONObject aggregate(int pages) {
            JSONObject aggregate = new JSONObject();
            aggregate.put("pages", pages);
            aggregate.put("scored", scored);
            aggregate.put("unreadable", unreadable);
            aggregate.put("criticalPages", criticalPages);
            aggregate.put("truncated", pages >= opts.maxPages);
            // One number everybody will quote, so it is stated as what it is: the
            // share of checks that passed across every page scored.
            aggregate.put("percent", totalChecks == 0 ? 0 : Math.round(totalPassed * 100.0 / totalChecks));
            aggregate.put("failCounts", new JSONObject(failCounts));
            aggregate.put("severities", new JSONObject(severities));

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
            return aggregate;
        }

        /**
         * The five reports stored beside the score rather than in it.
         *
         * None of them move the percentage: they are facts about the site, not
         * checks a page passed or failed. Each is stored on its own and each
         * swallows its own failure, so one unavailable report does not cost the
         * other four or the scan itself.
         *
         * Order matters once: the sitemap is fetched before the link report,
         * which reads it.
         */
        private void storeSiteReports(List<String> paths) {
            storeSitemap();
            storeLinks(paths);
            storeVanity();
            storeFreshness();
            storeLlms();
        }

        /**
         * GEO-21. Run once per scan, not once per page: it is the same file for
         * every row, and storing it is what lets the drawer answer "is this page
         * in the sitemap" without fetching a sitemap of its own.
         */
        private void storeSitemap() {
            try {
                sitemap = SitemapCheck.check(sitePath, language, siteBase,
                        opts.fetchTimeoutMs, opts.maxBodyBytes);
                ScanStore.saveSitemap(sitePath, language, sitemap);
            } catch (Exception e) {
                logger.debug("sitemap check failed for {}", sitePath, e);
            }
        }

        /**
         * GEO-22. "Nothing links here" is a fact about the site, not a check this
         * page passed or failed.
         */
        private void storeLinks(List<String> paths) {
            try {
                Map<String, PublishedMap.Entry> published = PublishedMap.forSite(sitePath, siteBase);
                LinkGraph.addReferences(links, published, language, new LinkedHashSet<>(paths));
                ScanStore.saveLinks(sitePath, language,
                        LinkGraph.report(links, published, language, PublishedMap.homePath(sitePath),
                                sitemap != null && sitemap.optBoolean("present", false),
                                notListed(sitemap)));
            } catch (Exception e) {
                logger.debug("link graph failed for {}", sitePath, e);
            }
        }

        /**
         * GEO-25. Almost entirely a repository question, so it needs nothing from
         * the walk except the canonical tags it already read.
         */
        private void storeVanity() {
            try {
                ScanStore.saveVanity(sitePath, language, VanityUrls.check(sitePath, canonicals));
            } catch (Exception e) {
                logger.debug("vanity url check failed for {}", sitePath, e);
            }
        }

        /**
         * GEO-24. Pure repository work, so the scheduled run keeps it current at
         * no cost; the tab can also ask for it directly at any time.
         */
        private void storeFreshness() {
            try {
                int staleDays = ScanStore.read(sitePath, language).optInt("staleDays", 0);
                if (staleDays <= 0) {
                    staleDays = Freshness.DEFAULT_STALE_DAYS;
                }
                ScanStore.saveFreshness(sitePath, language,
                        Freshness.check(sitePath, language, siteBase, staleDays), staleDays);
            } catch (Exception e) {
                logger.debug("freshness failed for {}", sitePath, e);
            }
        }

        /**
         * Is the published llms.txt still about this site? Compared against what
         * regenerating would produce now, using the copy the site files check
         * already fetched, so it costs one generation and no request.
         */
        private void storeLlms() {
            try {
                JSONObject llmsJson = siteFiles.optJSONObject("llms");
                String servedLlms = llmsJson == null ? null : llmsJson.optString("rawBody", null);
                String regenerated = GuestVisibility.inGuestSession(language, this::generateLlms);
                ScanStore.saveLlms(sitePath, language, LlmsFreshness.check(servedLlms, regenerated,
                        PublishedMap.forSite(sitePath, siteBase), sitePath));
            } catch (Exception e) {
                logger.debug("llms.txt freshness failed for {}", sitePath, e);
            }
        }

        /**
         * What llms.txt would say if it were regenerated now, as the guest sees
         * the site.
         *
         * The rethrow is not decoration: the callback may only raise
         * RepositoryException, so anything else has to be wrapped to get out.
         */
        private String generateLlms(JCRSessionWrapper guest) throws RepositoryException {
            try {
                return LlmsGenerator.generate(guest.getNode(sitePath), language,
                        n -> PublicUrls.forNode(n, opts.publicBaseUrl));
            } catch (RepositoryException e) {
                throw e;
            } catch (Exception e) {
                throw new RepositoryException(e);
            }
        }

        /**
         * The published paths the sitemap does *not* list.
         *
         * The comparison stores its findings, not the sitemap's entries - a large
         * site's entry list would dwarf everything else in the record - so "is this
         * page listed" cannot be asked directly. It does not need to be: `missing`
         * is exactly the published paths absent from the sitemap, so everything
         * else published is listed. Capped with the rest of the findings, so on a
         * site with more than two hundred missing pages some will read as listed;
         * a sitemap missing two hundred pages has a louder problem than this.
         */
        private static java.util.Set<String> notListed(JSONObject sitemap) {
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            JSONArray missing = sitemap == null ? null : sitemap.optJSONArray("missing");
            for (int i = 0; missing != null && i < missing.length(); i++) {
                out.add(missing.getJSONObject(i).optString("path", ""));
            }
            return out;
        }
    }

    /** The rules robots.txt states for this site, or none when it has nothing to say. */
    private static RobotsRules rulesOf(JSONObject siteFiles) {
        JSONObject robotsJson = siteFiles.optJSONObject("robots");
        return robotsJson != null && robotsJson.optBoolean("present", false)
                ? RobotsRules.parse(robotsJson.optString("rawBody", ""))
                : RobotsRules.empty();
    }

    /** One page: is it readable, what does its HTML contain, what does that score. */

    private static JSONObject scorePage(String sitePath, String path, String language,
            RobotsRules rules, JSONObject siteFiles, Options opts, LinkGraph.Accumulator links,
            Map<String, String> canonicals, String siteBase) throws RepositoryException {
        JSONObject visibility = GuestVisibility.forPage(path, language);
        if (!visibility.optBoolean("published", false)) {
            return null;
        }

        JSONObject out = new JSONObject();
        out.put(GUEST_READABLE, visibility.optBoolean(GUEST_READABLE, true));

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
        if (!visibility.optBoolean(GUEST_READABLE, true) || url == null) {
            // No point fetching a page a visitor cannot open. The score still
            // records it, and the critical guestReadable check does the talking.
            agent = new JSONObject();
            agent.put("name", SCAN_AGENT);
            agent.put("status", JSONObject.NULL);
        } else {
            // GEO-22. The link graph is built from the page we are already
            // fetching, so it costs nothing beyond this request.
            String from = PublishedMap.pathOf(url);
            agent = PageFetch.probe(url, siteBase, SCAN_AGENT, agentUa(), opts.fetchTimeoutMs, opts.maxBodyBytes,
                    html -> LinkGraph.addPage(links, from, html));
        }

        if (url != null) {
            JSONObject html = agent.optJSONObject("html");
            // Recorded even when absent, so "fetched and had none" can be told
            // apart from "never fetched".
            canonicals.put(PublishedMap.pathOf(url), html == null ? "" : html.optString("canonicalHref", ""));
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
        RobotsRules.Verdict v = rules.evaluate(SCAN_AGENT, pathOf(url));
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
        JSONObject severities = new JSONObject();
        for (int i = 0; i < checks.length(); i++) {
            JSONObject c = checks.getJSONObject(i);
            if (MULTI_AGENT_ONLY.equals(c.getString("id"))) {
                continue;
            }
            severities.put(c.getString("id"), c.getString("severity"));
            total++;
            if (c.getBoolean(PASSED)) {
                passed++;
            } else {
                failed.put(c.getString("id"));
                if ("critical".equals(c.getString("severity"))) {
                    critical++;
                }
            }
        }
        JSONObject trimmed = new JSONObject();
        trimmed.put(PASSED, passed);
        trimmed.put(TOTAL, total);
        trimmed.put(CRITICAL_FAILED, critical);
        out.put("score", trimmed);
        out.put("failed", failed);
        out.put("severities", severities);
        return out;
    }

    private static List<String> publishedPages(JCRSessionWrapper session, String root, int limit)
            throws RepositoryException {
        List<String> out = new ArrayList<>();
        // issamenode as well as isdescendantnode: a scope an editor typed
        // usually names a page, and isdescendantnode alone excludes that page.
        // Scoping to /sites/x/home would otherwise skip home itself.
        String escaped = root.replace("'", "''");
        String sql = "select * from [jnt:page] as p where isdescendantnode(p, '"
                + escaped + "') or issamenode(p, '" + escaped + "')";
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
            if (SCAN_AGENT.equals(c.name)) {
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
