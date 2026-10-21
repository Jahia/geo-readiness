package org.jahia.se.modules.georeadiness.botrank;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * One sync of a site with BotRank: reads the citations and stores one entry per cited page.
 *
 * <p>Order of a run, as agreed with BotRank (backlog, 15 October 2026):</p>
 * <ol>
 *   <li>{@code data-freshness}: while answers are still being collected and it is before
 *       {@link #RETRY_UNTIL} Paris time, the run is postponed ({@link Outcome#RETRY}); after that it
 *       syncs anyway and the drawer shows the time of the latest counted answer;</li>
 *   <li>the cited pages of the site's hosts over the last 30 days, and over all time (the all-time
 *       list tells "never cited" from "not recently" without any call from the drawer);</li>
 *   <li>each cited URL resolved to a page ({@link PageResolver}); several URLs of one page add up;</li>
 *   <li>for each page cited in the last 30 days: total and engines, the 60-day history (the current
 *       window plus the previous one), prompts, tags and co-cited pages;</li>
 *   <li>the site's own figures for the dashboard ({@link SiteCitations}), which never fail the run.</li>
 * </ol>
 *
 * <p><b>Variation guard.</b> BotRank computes a variation against the previous period even when no
 * prompt ran in it (found in the test of 15 October 2026: {@code +14} against an empty week). A
 * variation is kept only when the previous 30-day window holds at least one run day, which the
 * history tells: it only returns days on which the project received answers.</p>
 *
 * <p>Calls are sequential, to stay a light client of a partner API with no published rate limit.
 * Text from BotRank (prompts, tags, URLs) is stored as data and bounded in length; the drawer renders
 * it as text.</p>
 *
 * <p>Thread safety: stateless; the store's run lock keeps one run per site.</p>
 */
public final class CitationSync {

    private static final Logger logger = LoggerFactory.getLogger(CitationSync.class);

    public static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final LocalTime RETRY_UNTIL = LocalTime.of(10, 30);
    public static final int MAX_ATTEMPTS = 8;
    static final int DAYS = 30;
    static final int MAX_URLS_PER_PAGE = 5;
    static final int MAX_PAGE_FAILURES = 20;
    /** Pages whose details are read in one run, most cited first; the others keep their list figures. */
    static final int MAX_DETAILED_PAGES = 200;
    /** How often (in detailed pages) the run tells the store it is alive, so it is never taken for dead. */
    static final int HEARTBEAT_EVERY = 10;
    static final int MAX_UNRESOLVED = 100;
    static final int MAX_TEXT = 500;

    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final String RECENT = "recent";
    private static final String RANK_OF = "rankOf";
    private static final String CITATIONS = "citations";
    private static final String VARIATION = "variation";
    private static final String CITATION_COUNT = "citationCount";
    private static final String CITATION_COUNT_VARIATION = "citationCountVariation";
    private static final String AI_SURFACE = "aiSurface";
    private static final String AI_SURFACES = "aiSurfaces";
    private static final String PROMPTS = "prompts";
    private static final String AVERAGE_RANK = "averageRank";
    private static final String REQUESTED_PAGE_AVERAGE_RANK = "requestedPageAverageRank";

    /** How a run ended. */
    public enum Outcome { DONE, RETRY, SKIPPED, FAILED }

    /** The outcome and a note for administrators that names no secret. */
    public static final class Result {
        public final Outcome outcome;
        public final String message;

        Result(Outcome outcome, String message) {
            this.outcome = outcome;
            this.message = message;
        }
    }

    private CitationSync() {
    }

    /**
     * Runs one sync of a site.
     *
     * @param sitePath    {@code /sites/<key>}
     * @param connections the configured connections
     * @param baseUrl     a base URL for the page index (only paths are compared)
     * @param attempt     1 for the scheduled run, more for its retries
     * @param clock       the clock (Paris time is derived from it)
     * @return the outcome
     */
    public static Result run(String sitePath, BotRankConnections connections, String baseUrl, int attempt,
            Clock clock) {
        CitationStore.Settings settings;
        try {
            settings = CitationStore.settings(sitePath);
        } catch (RepositoryException e) {
            return new Result(Outcome.FAILED, "the site settings could not be read");
        }
        if (!settings.isComplete()) {
            return new Result(Outcome.SKIPPED, "the site is not connected to BotRank");
        }
        String siteKey = sitePath.substring("/sites/".length());
        BotRankConnection connection = connections.forSite(settings.connection, siteKey);
        try {
            if (!CitationStore.tryBegin(sitePath)) {
                return new Result(Outcome.SKIPPED, "a sync is already running");
            }
        } catch (RepositoryException e) {
            return new Result(Outcome.FAILED, "the sync could not start");
        }
        if (connection == null) {
            return fail(sitePath, "the connection \"" + settings.connection + "\" is not available to this site");
        }
        try {
            return sync(sitePath, settings, connection.client(), baseUrl, attempt, clock);
        } catch (BotRankException e) {
            logger.warn("BotRank sync of {} failed: {} [{}]", sitePath, e.getMessage(), e.type());
            return fail(sitePath, e.getMessage());
        } catch (RepositoryException | RuntimeException e) {
            logger.warn("BotRank sync of {} failed: {}", sitePath, e.toString());
            return fail(sitePath, "the sync failed on the Jahia side; see the server log");
        }
    }

    /** The body of a run, once the run lock is held. */
    private static Result sync(String sitePath, CitationStore.Settings settings, BotRankClient client, String baseUrl,
            int attempt, Clock clock) throws BotRankException, RepositoryException {
        BotRankClient.Freshness fresh = client.freshness(settings.project);
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(PARIS));
        if (fresh.refreshing && attempt < MAX_ATTEMPTS && now.toLocalTime().isBefore(RETRY_UNTIL)) {
            CitationStore.postpone(sitePath, "BotRank is still collecting answers; the sync will retry in 30 minutes");
            return new Result(Outcome.RETRY, "BotRank is still collecting answers");
        }
        LocalDate today = now.toLocalDate();
        LocalDate start = today.minusDays(DAYS - 1L);
        List<String> hosts = hosts(settings.hosts);
        List<BotRankClient.CitedPage> recent = citedPages(client, settings.project, hosts,
                BotRankClient.Period.between(start, today));
        List<BotRankClient.CitedPage> ever = citedPages(client, settings.project, hosts,
                BotRankClient.Period.allTime());
        PageResolver resolver = PageResolver.build(sitePath, baseUrl, settings.mappings);

        Map<String, Group> recentGroups = group(recent, hosts, resolver::resolve);
        Map<String, Group> everGroups = group(ever, hosts, resolver::resolve);
        JSONArray unresolved = unresolved(ever, hosts, resolver::resolve);

        Window window = new Window(client, settings.project, start, today, recent.size());
        List<JSONObject> pages = new ArrayList<>();
        int skipped = recentEntries(sitePath, window, recentGroups, everGroups, pages);
        for (Group g : everGroups.values()) {
            if (!recentGroups.containsKey(g.key())) {
                JSONObject entry = base(g, g.citations);
                entry.put(RECENT, false);
                entry.put(CITATIONS, 0);
                entry.put(VARIATION, JSONObject.NULL);
                pages.add(entry);
            }
        }
        CitationStore.heartbeat(sitePath);
        JSONObject site = SiteCitations.read(new SiteCitations.Reads(client, settings.project, start, today), hosts,
                recent, resolver::resolve);
        String note = note(window.failures, skipped, site.optInt("failures"));
        CitationStore.finish(sitePath, pages, unresolved, fresh.latestCountedAnswerAt, note, site);
        logger.info("BotRank sync of {}: {} cited page(s), {} unmatched URL(s)", sitePath, pages.size(), unresolved.length());
        return new Result(Outcome.DONE, note);
    }

    /**
     * The cited pages of every host of the site, most cited first, ranked across the site.
     *
     * <p>BotRank lists the pages of one registrable domain per call and only keeps the hosts within
     * it, so a site served on two domains ({@code example.fr} and {@code example.com}) needs one list
     * per domain. Without a public suffix list we cannot group hosts by domain ourselves: each host is
     * passed as the domain once, with all the hosts, and pages read twice (hosts of the same domain)
     * are kept once. Ranks are recomputed over the merged list in BotRank's own order, most citations
     * first then by URL, so that one site has one ranking.</p>
     */
    static List<BotRankClient.CitedPage> citedPages(BotRankClient client, String project, List<String> hosts,
            BotRankClient.Period period) throws BotRankException {
        Map<String, BotRankClient.CitedPage> byUrl = new LinkedHashMap<>();
        for (String host : hosts) {
            for (BotRankClient.CitedPage p : client.citedPages(project, host, hosts, period)) {
                byUrl.putIfAbsent(p.url, p);
            }
        }
        List<BotRankClient.CitedPage> merged = new ArrayList<>(byUrl.values());
        merged.sort(Comparator.comparingInt((BotRankClient.CitedPage p) -> p.citations).reversed()
                .thenComparing(p -> p.url));
        List<BotRankClient.CitedPage> ranked = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            BotRankClient.CitedPage p = merged.get(i);
            ranked.add(new BotRankClient.CitedPage(p.url, i + 1, p.citations, p.variation));
        }
        return ranked;
    }

    /** The configured hosts, folded, distinct and bounded. */
    private static List<String> hosts(List<String> configured) {
        List<String> hosts = new ArrayList<>();
        for (String h : configured) {
            String f = CitedUrls.foldHost(h);
            if (!f.isEmpty() && !hosts.contains(f) && hosts.size() < CitationStore.MAX_HOSTS) {
                hosts.add(f);
            }
        }
        return hosts;
    }

    /**
     * Adds the entries of the pages cited in the window, most cited first: details for the first
     * {@link #MAX_DETAILED_PAGES}, list figures for the others.
     *
     * @return how many pages kept their list figures only
     */
    private static int recentEntries(String sitePath, Window window, Map<String, Group> recentGroups,
            Map<String, Group> everGroups, List<JSONObject> pages) throws BotRankException, RepositoryException {
        List<Group> byCitations = new ArrayList<>(recentGroups.values());
        byCitations.sort(Comparator.comparingInt((Group g) -> g.citations).reversed());
        int skipped = 0;
        for (int i = 0; i < byCitations.size(); i++) {
            Group g = byCitations.get(i);
            Group all = everGroups.get(g.key());
            int everCitations = all == null ? g.citations : all.citations;
            if (i < MAX_DETAILED_PAGES) {
                pages.add(recentEntry(window, g, everCitations));
                if (i % HEARTBEAT_EVERY == 0) {
                    CitationStore.heartbeat(sitePath);
                }
            } else {
                JSONObject entry = base(g, everCitations);
                entry.put(RECENT, true).put(RANK_OF, window.rankOf).put(VARIATION, JSONObject.NULL);
                entry.put("detailsSkipped", true);
                pages.add(entry);
                skipped++;
            }
        }
        return skipped;
    }

    private static String note(int failures, int skipped, int siteFailures) {
        List<String> notes = new ArrayList<>();
        if (siteFailures > 0) {
            notes.add(siteFailures + " site-level read(s) failed: the dashboard shows what BotRank answered");
        }
        if (failures > 0) {
            notes.add(failures + " page(s) kept their totals only: BotRank did not answer for their details");
        }
        if (skipped > 0) {
            notes.add(skipped + " less cited page(s) kept their totals only: details are read for the "
                    + MAX_DETAILED_PAGES + " most cited pages");
        }
        return String.join(". ", notes);
    }

    /** What the page reads of one run share: the client, the project, the window, and the failures so far. */
    static final class Window {
        final BotRankClient client;
        final String project;
        final LocalDate start;
        final LocalDate end;
        /** The number of cited pages the rank of a page is computed among. */
        final int rankOf;
        /** Pages whose details could not be read; the run fails above {@link #MAX_PAGE_FAILURES}. */
        int failures;

        Window(BotRankClient client, String project, LocalDate start, LocalDate end, int rankOf) {
            this.client = client;
            this.project = project;
            this.start = start;
            this.end = end;
            this.rankOf = rankOf;
        }
    }

    /**
     * The entry of a page cited in the last 30 days, details included when BotRank answers for them.
     *
     * <p>The variation starts null and is set only once the history shows a run day in the previous
     * window: a page whose details fail never carries BotRank's raw variation.</p>
     *
     * @throws BotRankException on a refusal that concerns the whole run (key, project, subscription),
     *                          or once more than {@link #MAX_PAGE_FAILURES} pages failed
     */
    static JSONObject recentEntry(Window w, Group g, int everCitations) throws BotRankException {
        JSONObject entry = base(g, everCitations);
        entry.put(RECENT, true);
        entry.put(RANK_OF, w.rankOf);
        entry.put(VARIATION, JSONObject.NULL);
        try {
            details(w, g, entry);
        } catch (BotRankException e) {
            if (!e.isTransient() && e.status() != 404) {
                throw e;
            }
            w.failures++;
            entry.put(VARIATION, JSONObject.NULL);
            entry.put("detailsMissing", true);
            if (w.failures > MAX_PAGE_FAILURES) {
                throw new BotRankException(e.type(), e.status(), "too many pages could not be read: " + e.getMessage());
            }
        }
        return entry;
    }

    /** The cited URLs of one page, added up. */
    static final class Group {
        final PageResolver.Target target;
        final List<BotRankClient.CitedPage> urls = new ArrayList<>();
        int citations;
        Integer variation = 0;
        int rank = Integer.MAX_VALUE;

        Group(PageResolver.Target target) {
            this.target = target;
        }

        String key() {
            return target.key();
        }

        void add(BotRankClient.CitedPage p) {
            urls.add(p);
            citations += p.citations;
            variation = variation == null || p.variation == null ? null : variation + p.variation;
            rank = Math.min(rank, p.rank);
        }

        /** The most cited URL first. */
        List<BotRankClient.CitedPage> byCitations() {
            List<BotRankClient.CitedPage> out = new ArrayList<>(urls);
            out.sort(Comparator.comparingInt((BotRankClient.CitedPage p) -> p.citations).reversed());
            return out;
        }
    }

    /** Cited URLs on the site's hosts, grouped by the page they resolve to. */
    static Map<String, Group> group(List<BotRankClient.CitedPage> list, List<String> hosts,
            Function<String, PageResolver.Target> resolve) {
        Map<String, Group> out = new LinkedHashMap<>();
        for (BotRankClient.CitedPage p : list) {
            if (!CitedUrls.onHosts(CitedUrls.host(p.url), hosts)) {
                continue;
            }
            PageResolver.Target t = resolve.apply(p.url);
            if (t != null) {
                out.computeIfAbsent(t.key(), k -> new Group(t)).add(p);
            }
        }
        return out;
    }

    /** The cited URLs of the site that no page matched, most cited first, bounded. */
    static JSONArray unresolved(List<BotRankClient.CitedPage> ever, List<String> hosts,
            Function<String, PageResolver.Target> resolve) {
        List<BotRankClient.CitedPage> left = new ArrayList<>();
        for (BotRankClient.CitedPage p : ever) {
            if (CitedUrls.onHosts(CitedUrls.host(p.url), hosts) && resolve.apply(p.url) == null) {
                left.add(p);
            }
        }
        left.sort(Comparator.comparingInt((BotRankClient.CitedPage p) -> p.citations).reversed());
        JSONArray out = new JSONArray();
        for (int i = 0; i < left.size() && i < MAX_UNRESOLVED; i++) {
            out.put(new JSONObject().put("url", text(left.get(i).url)).put(CITATIONS, left.get(i).citations));
        }
        return out;
    }

    private static JSONObject base(Group g, int everCitations) {
        JSONObject e = new JSONObject();
        e.put("jcrPath", g.target.jcrPath);
        e.put("language", g.target.language);
        e.put("via", g.target.via);
        JSONArray urls = new JSONArray();
        for (BotRankClient.CitedPage p : g.byCitations()) {
            urls.put(text(p.url));
        }
        e.put("urls", urls);
        e.put(CITATIONS, g.citations);
        e.put(VARIATION, g.variation == null ? JSONObject.NULL : g.variation);
        e.put("rank", g.rank == Integer.MAX_VALUE ? JSONObject.NULL : g.rank);
        e.put("everCitations", everCitations);
        return e;
    }

    /** Fills the per-page figures of a recently cited page. */
    private static void details(Window w, Group g, JSONObject entry) throws BotRankException {
        BotRankClient.Period current = BotRankClient.Period.between(w.start, w.end);
        BotRankClient.Period both = BotRankClient.Period.between(w.start.minusDays(DAYS), w.end);
        List<BotRankClient.CitedPage> urls = g.byCitations();
        String best = urls.get(0).url;

        Map<String, Integer> surfaces = new TreeMap<>();
        Map<String, Integer> days = new TreeMap<>();
        for (int i = 0; i < urls.size() && i < MAX_URLS_PER_PAGE; i++) {
            String url = urls.get(i).url;
            addSurfaces(w.client.pageRead(w.project, "citation-total", url, current, 0).optJSONArray(AI_SURFACES), surfaces);
            addDays(w.client.pageRead(w.project, "citation-history", url, both, 0).optJSONArray("days"), days);
        }
        JSONArray aiSurfaces = new JSONArray();
        surfaces.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> aiSurfaces.put(new JSONObject().put(AI_SURFACE, e.getKey()).put(CITATION_COUNT, e.getValue())));
        entry.put(AI_SURFACES, aiSurfaces);

        String startDay = w.start.toString();
        JSONArray history = new JSONArray();
        int previousRunDays = 0;
        for (Map.Entry<String, Integer> e : days.entrySet()) {
            if (e.getKey().compareTo(startDay) < 0) {
                previousRunDays++;
            } else {
                history.put(new JSONObject().put("date", e.getKey()).put(CITATION_COUNT, e.getValue()));
            }
        }
        boolean trusted = previousRunDays > 0;
        entry.put("history", history);
        entry.put("previousRunDays", previousRunDays);
        entry.put(VARIATION, trusted && g.variation != null ? g.variation : JSONObject.NULL);

        entry.put(PROMPTS, prompts(w.client.pageRead(w.project, PROMPTS, best, current, 20).optJSONArray(PROMPTS), trusted));
        entry.put("tags", tags(w.client.pageRead(w.project, "tags", best, current, 10).optJSONArray("tags"), trusted));
        entry.put("coCited", coCited(w.client.pageRead(w.project, "co-cited", best, current, 10).optJSONArray("coCitedPages"), trusted));
        entry.put("period", new JSONObject().put("start", startDay).put("end", w.end.toString()));
    }

    /** Adds the citations per engine of one URL. */
    private static void addSurfaces(JSONArray s, Map<String, Integer> surfaces) {
        for (int j = 0; s != null && j < s.length(); j++) {
            JSONObject o = s.optJSONObject(j);
            if (o != null) {
                surfaces.merge(text(o.optString(AI_SURFACE)), o.optInt(CITATION_COUNT), Integer::sum);
            }
        }
    }

    /** Adds the citations per run day of one URL. */
    private static void addDays(JSONArray d, Map<String, Integer> days) {
        for (int j = 0; d != null && j < d.length(); j++) {
            JSONObject o = d.optJSONObject(j);
            if (o != null && DAY.matcher(o.optString("date")).matches()) {
                days.merge(o.optString("date"), o.optInt(CITATION_COUNT), Integer::sum);
            }
        }
    }

    static JSONArray prompts(JSONArray in, boolean trusted) {
        JSONArray out = new JSONArray();
        for (int i = 0; in != null && i < in.length(); i++) {
            JSONObject p = in.optJSONObject(i);
            if (p == null) {
                continue;
            }
            JSONArray surfaces = new JSONArray();
            JSONArray s = p.optJSONArray(AI_SURFACES);
            for (int j = 0; s != null && j < s.length(); j++) {
                JSONObject o = s.optJSONObject(j);
                if (o != null) {
                    surfaces.put(new JSONObject().put(AI_SURFACE, text(o.optString(AI_SURFACE)))
                            .put(CITATION_COUNT, o.optInt(CITATION_COUNT)));
                }
            }
            out.put(new JSONObject()
                    .put("id", text(p.optString("id")))
                    .put("text", text(p.optString("text")))
                    .put("market", text(p.optString("market")))
                    .put(CITATION_COUNT, p.optInt(CITATION_COUNT))
                    .put(VARIATION, variation(p, trusted))
                    .put(AI_SURFACES, surfaces)
                    .put("lastCitationDay", text(p.optString("lastCitationDay"))));
        }
        return out;
    }

    static JSONArray tags(JSONArray in, boolean trusted) {
        JSONArray out = new JSONArray();
        for (int i = 0; in != null && i < in.length(); i++) {
            JSONObject t = in.optJSONObject(i);
            if (t != null) {
                out.put(new JSONObject()
                        .put("id", text(t.optString("id")))
                        .put("name", text(t.optString("name")))
                        .put("promptCount", t.optInt("promptCount"))
                        .put(CITATION_COUNT, t.optInt(CITATION_COUNT))
                        .put(VARIATION, variation(t, trusted)));
            }
        }
        return out;
    }

    static JSONArray coCited(JSONArray in, boolean trusted) {
        JSONArray out = new JSONArray();
        for (int i = 0; in != null && i < in.length(); i++) {
            JSONObject c = in.optJSONObject(i);
            if (c != null) {
                out.put(new JSONObject()
                        .put("url", text(c.optString("url")))
                        .put("sharedAnswerCount", c.optInt("sharedAnswerCount"))
                        .put(CITATION_COUNT, c.optInt(CITATION_COUNT))
                        .put(VARIATION, variation(c, trusted))
                        .put(AVERAGE_RANK, c.isNull(AVERAGE_RANK) ? JSONObject.NULL : c.optDouble(AVERAGE_RANK))
                        .put(REQUESTED_PAGE_AVERAGE_RANK, c.isNull(REQUESTED_PAGE_AVERAGE_RANK) ? JSONObject.NULL
                                : c.optDouble(REQUESTED_PAGE_AVERAGE_RANK))
                        .put("outrankingAnswerCount", c.optInt("outrankingAnswerCount")));
            }
        }
        return out;
    }

    private static Object variation(JSONObject o, boolean trusted) {
        return !trusted || !o.has(CITATION_COUNT_VARIATION) || o.isNull(CITATION_COUNT_VARIATION)
                ? JSONObject.NULL : o.optInt(CITATION_COUNT_VARIATION);
    }

    /** Text from BotRank, bounded; stored as data and rendered as text. */
    static String text(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }

    private static Result fail(String sitePath, String message) {
        try {
            CitationStore.fail(sitePath, message);
        } catch (RepositoryException e) {
            logger.warn("Could not record the failed BotRank sync of {}", sitePath);
        }
        return new Result(Outcome.FAILED, message);
    }
}
