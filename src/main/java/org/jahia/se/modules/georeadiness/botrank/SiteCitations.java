package org.jahia.se.modules.georeadiness.botrank;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Screen B: the figures of a whole site, read once per sync after its pages (BotRank public API,
 * site routes in production on 20 October 2026).
 *
 * <p><b>Site means registrable domain.</b> BotRank reads a host passed in the path on its registrable
 * domain and names the domain it used. Its totals, share history and ranking therefore cover every
 * host of that domain, other Jahia sites included. Only the pages list filters by host, so the
 * figures per host (citations, cited pages and an approximate share) are computed here from the
 * 30-day list the sync already read. The dashboard names the domain on what covers the domain.</p>
 *
 * <p>Each host is read once: the answer names its domain, and a host whose domain was already read is
 * not read again. A domain the project never cited answers {@value BotRankClient#SITE_NOT_CITED}, kept
 * as a state. Any other failure of a site read is counted and the rest of the summary is kept: the
 * pages of the run never depend on it.</p>
 *
 * <p>Variations are BotRank's: since 16 October 2026 they are null whenever the previous period is not
 * comparable. Text from BotRank (domains, engines, markets) is bounded and rendered as text.</p>
 *
 * <p>Thread safety: stateless.</p>
 */
final class SiteCitations {

    private static final Logger logger = LoggerFactory.getLogger(SiteCitations.class);

    /** The most cited pages of the site shown on the dashboard. */
    static final int TOP_PAGES = 20;
    /** Sites listed above and below the site in the ranking. */
    static final int NEIGHBOURS = 3;
    /** Bounds the lists BotRank sends, which the reference does not bound. */
    static final int MAX_ITEMS = 60;

    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** An ISO 8601 duration such as P1D, P7D or PT12H. */
    private static final Pattern DURATION = Pattern.compile("P(?:\\d{1,4}[YMWD]){0,4}(?:T(?:\\d{1,4}[HMS]){1,3})?");
    private static final String AI_SURFACE = "aiSurface";
    private static final String AI_SURFACES = "aiSurfaces";
    private static final String CITATIONS = "citations";
    private static final String CITATION_COUNT = "citationCount";
    private static final String DOMAIN = "domain";
    private static final String SHARE = "shareOfCitations";
    private static final String SHARE_VARIATION = "shareOfCitationsVariation";
    private static final String SCORE = "visibilityScore";
    private static final String SCORE_VARIATION = "visibilityScoreVariation";
    private static final String ANSWERS = "analysedAnswerCount";
    private static final String VARIATION = "variation";

    private SiteCitations() {
    }

    /** What one run reads for the site, and the reads that failed. */
    static final class Reads {
        final BotRankClient client;
        final String project;
        final BotRankClient.Period period;
        int failures;

        Reads(BotRankClient client, String project, LocalDate start, LocalDate end) {
            this.client = client;
            this.project = project;
            this.period = BotRankClient.Period.between(start, end);
        }
    }

    /**
     * The site summary of one run.
     *
     * @param reads   the client, the project and the 30-day window of the run
     * @param hosts   the site's folded hosts
     * @param recent  the 30-day cited pages of those hosts, ranked across the site
     * @param resolve the run's URL to page resolution
     * @return {@code {period, domains[], hosts[], topPages[], visibility, engines[], failures}}
     */
    static JSONObject read(Reads reads, List<String> hosts, List<BotRankClient.CitedPage> recent,
            Function<String, PageResolver.Target> resolve) {
        Map<String, String> domainOf = new LinkedHashMap<>();
        Map<String, JSONObject> domains = new LinkedHashMap<>();
        for (String host : hosts) {
            readDomain(reads, host, domainOf, domains);
        }
        JSONObject out = new JSONObject();
        out.put("period", new JSONObject().put("start", reads.period.start.toString()).put("end", reads.period.end.toString()));
        out.put("domains", new JSONArray(domains.values()));
        out.put("hosts", hosts(hosts, recent, domainOf, domains));
        out.put("topPages", topPages(recent, resolve));
        out.put("visibility", visibility(reads));
        out.put("engines", engines(reads));
        out.put("failures", reads.failures);
        return out;
    }

    /** Reads the domain of a host, unless a host read before named the same domain. */
    private static void readDomain(Reads reads, String host, Map<String, String> domainOf, Map<String, JSONObject> domains) {
        JSONObject total;
        try {
            total = reads.client.siteRead(reads.project, host, "citation-total", reads.period, 0);
        } catch (BotRankException e) {
            if (BotRankClient.SITE_NOT_CITED.equals(e.type())) {
                domainOf.put(host, host);
                domains.putIfAbsent(host, new JSONObject().put(DOMAIN, host).put("notCited", true));
            } else {
                failed(reads, "citation-total", e);
            }
            return;
        }
        String domain = CitedUrls.foldHost(CitationSync.text(total.optString(DOMAIN, host)));
        domainOf.put(host, domain.isEmpty() ? host : domain);
        if (domain.isEmpty() || domains.containsKey(domain)) {
            return;
        }
        JSONObject entry = new JSONObject()
                .put(DOMAIN, domain)
                .put("notCited", false)
                .put(CITATIONS, total.optInt(CITATION_COUNT))
                .put(VARIATION, integer(total, "citationCountVariation"))
                .put("share", number(total, SHARE))
                .put("shareVariation", number(total, SHARE_VARIATION))
                .put("citedPages", total.optInt("citedPageCount"))
                .put("citedPagesVariation", integer(total, "citedPageCountVariation"));
        entry.put("ranking", ranking(reads, domain));
        entry.put("shareHistory", shareHistory(reads, domain));
        domains.put(domain, entry);
    }

    private static JSONArray ranking(Reads reads, String domain) {
        JSONArray out = new JSONArray();
        try {
            JSONArray in = reads.client.siteRead(reads.project, domain, "ranking", reads.period, NEIGHBOURS)
                    .optJSONArray("ranking");
            for (int i = 0; in != null && i < in.length() && i < MAX_ITEMS; i++) {
                JSONObject r = in.optJSONObject(i);
                if (r != null) {
                    out.put(new JSONObject()
                            .put(DOMAIN, CitationSync.text(r.optString(DOMAIN)))
                            .put("requested", r.optBoolean("isRequestedSite"))
                            .put("rank", r.optInt("rank"))
                            .put("rankVariation", integer(r, "rankVariation"))
                            .put("share", number(r, SHARE))
                            .put("shareVariation", number(r, SHARE_VARIATION)));
                }
            }
        } catch (BotRankException e) {
            failed(reads, "ranking", e);
        }
        return out;
    }

    private static JSONArray shareHistory(Reads reads, String domain) {
        JSONArray out = new JSONArray();
        try {
            JSONArray in = reads.client.siteRead(reads.project, domain, "citation-share-history", reads.period, 0)
                    .optJSONArray("days");
            for (int i = 0; in != null && i < in.length() && i < MAX_ITEMS; i++) {
                JSONObject d = in.optJSONObject(i);
                if (d != null && DAY.matcher(d.optString("date")).matches()) {
                    out.put(new JSONObject().put("date", d.optString("date")).put("share", number(d, SHARE)));
                }
            }
        } catch (BotRankException e) {
            failed(reads, "citation-share-history", e);
        }
        return out;
    }

    /**
     * Citations and cited pages per host, from the 30-day list, with an approximate share: the host's
     * citations over the project's, the project's being the domain's citations over the domain's share.
     */
    static JSONArray hosts(List<String> hosts, List<BotRankClient.CitedPage> recent, Map<String, String> domainOf,
            Map<String, JSONObject> domains) {
        JSONArray out = new JSONArray();
        for (String host : hosts) {
            int citations = 0;
            int pages = 0;
            for (BotRankClient.CitedPage p : recent) {
                if (host.equals(CitedUrls.host(p.url))) {
                    citations += p.citations;
                    pages++;
                }
            }
            String domain = domainOf.getOrDefault(host, host);
            JSONObject d = domains.get(domain);
            Object share = JSONObject.NULL;
            if (d != null && !d.optBoolean("notCited") && !d.isNull("share") && d.optDouble("share") > 0
                    && d.optInt(CITATIONS) > 0) {
                share = citations * d.optDouble("share") / d.optInt(CITATIONS);
            }
            out.put(new JSONObject().put("host", host).put(DOMAIN, domain).put(CITATIONS, citations)
                    .put("citedPages", pages).put("share", share));
        }
        return out;
    }

    /** The most cited URLs of the site, with the page each resolves to when one does. */
    static JSONArray topPages(List<BotRankClient.CitedPage> recent, Function<String, PageResolver.Target> resolve) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < recent.size() && i < TOP_PAGES; i++) {
            BotRankClient.CitedPage p = recent.get(i);
            JSONObject e = new JSONObject()
                    .put("url", CitationSync.text(p.url))
                    .put("rank", p.rank)
                    .put(CITATIONS, p.citations)
                    .put(VARIATION, p.variation == null ? JSONObject.NULL : p.variation);
            PageResolver.Target t = resolve.apply(p.url);
            if (t != null) {
                e.put("jcrPath", t.jcrPath).put("language", t.language);
            }
            out.put(e);
        }
        return out;
    }

    /** The brand's visibility score, per engine and per market, and its daily series; null when unread. */
    private static Object visibility(Reads reads) {
        try {
            JSONObject score = reads.client.visibilityScore(reads.project, false, reads.period);
            JSONObject out = new JSONObject()
                    .put("score", number(score, SCORE))
                    .put(VARIATION, number(score, SCORE_VARIATION))
                    .put("answers", score.optInt(ANSWERS))
                    .put("byEngine", scores(score.optJSONArray(AI_SURFACES), AI_SURFACE))
                    .put("byMarket", scores(score.optJSONArray("markets"), "market"));
            JSONArray history = new JSONArray();
            JSONArray days = reads.client.visibilityScore(reads.project, true, reads.period).optJSONArray("days");
            for (int i = 0; days != null && i < days.length() && i < MAX_ITEMS; i++) {
                JSONObject d = days.optJSONObject(i);
                if (d != null && DAY.matcher(d.optString("date")).matches()) {
                    history.put(new JSONObject().put("date", d.optString("date")).put("score", number(d, SCORE)));
                }
            }
            return out.put("history", history);
        } catch (BotRankException e) {
            failed(reads, "visibility-score", e);
            return JSONObject.NULL;
        }
    }

    private static JSONArray scores(JSONArray in, String key) {
        JSONArray out = new JSONArray();
        for (int i = 0; in != null && i < in.length() && i < MAX_ITEMS; i++) {
            JSONObject o = in.optJSONObject(i);
            if (o != null) {
                out.put(new JSONObject()
                        .put("key", CitationSync.text(o.optString(key)))
                        .put("score", number(o, SCORE))
                        .put(VARIATION, number(o, SCORE_VARIATION))
                        .put("answers", o.optInt(ANSWERS)));
            }
        }
        return out;
    }

    /** The engines enabled on the project and how often BotRank refreshes each one. */
    static JSONArray engines(Reads reads) {
        JSONArray out = new JSONArray();
        try {
            JSONArray in = reads.client.project(reads.project).optJSONArray(AI_SURFACES);
            for (int i = 0; in != null && i < in.length() && i < MAX_ITEMS; i++) {
                JSONObject o = in.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String interval = o.isNull("refreshInterval") ? "" : o.optString("refreshInterval", "");
                out.put(new JSONObject()
                        .put(AI_SURFACE, CitationSync.text(o.optString(AI_SURFACE)))
                        .put("refreshInterval", DURATION.matcher(interval).matches() && interval.length() > 1
                                ? interval : JSONObject.NULL));
            }
        } catch (BotRankException e) {
            failed(reads, "project", e);
        }
        return out;
    }

    private static void failed(Reads reads, String read, BotRankException e) {
        reads.failures++;
        logger.info("BotRank site read {} failed: {} [{}]", read, e.getMessage(), e.type());
    }

    private static Object number(JSONObject o, String key) {
        return !o.has(key) || o.isNull(key) ? JSONObject.NULL : o.optDouble(key);
    }

    private static Object integer(JSONObject o, String key) {
        return !o.has(key) || o.isNull(key) ? JSONObject.NULL : o.optInt(key);
    }
}
