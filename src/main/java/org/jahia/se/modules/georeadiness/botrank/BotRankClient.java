package org.jahia.se.modules.georeadiness.botrank;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Read-only client of the BotRank public API v1, for one connection.
 *
 * <p>Every call sends the organisation key as a bearer token and, except {@link #projects()}, the
 * project in a {@code Project-Id} header. Periods are explicit {@code startDate}/{@code endDate} days
 * in Europe/Paris, as the reference asks for results that are stored, or {@code all_time}.</p>
 *
 * <p>Security: the base URL comes from the connection's configuration file, never from a request, and
 * redirects are not followed, so the key cannot be sent anywhere else. The key never appears in an
 * exception or a log line.</p>
 *
 * <p>Thread safety: immutable; the shared {@link HttpClient} is thread-safe.</p>
 */
public class BotRankClient {

    /** Upper bound on the pages of the cited-pages list read in one sync (100 entries each). */
    static final int MAX_LIST_PAGES = 50;
    private static final String VARIATION = "citationCountVariation";
    /** Upper bound on one answer; a list page of 100 entries is a few kilobytes. */
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String apiKey;
    private final Transport transport;

    /** Sends one GET and returns status and body; replaced in unit tests. */
    interface Transport {
        Response get(URI uri, String apiKey, String projectId) throws IOException, InterruptedException;
    }

    /** Status and body of one answer. */
    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /**
     * @param baseUrl the API base, such as {@code https://api.botrank.ai/public/v1}
     * @param apiKey  the organisation key, {@code brk_...}
     */
    public BotRankClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, BotRankClient::send);
    }

    BotRankClient(String baseUrl, String apiKey, Transport transport) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.transport = transport;
    }

    /** One project of the organisation. */
    public static final class Project {
        public final String id;
        public final String name;

        Project(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** One entry of the cited-pages list. */
    public static final class CitedPage {
        public final String url;
        public final int rank;
        public final int citations;
        /** Null when BotRank has no comparable previous period. */
        public final Integer variation;

        CitedPage(String url, int rank, int citations, Integer variation) {
            this.url = url;
            this.rank = rank;
            this.citations = citations;
            this.variation = variation;
        }
    }

    /** What {@code data-freshness} says about a project. */
    public static final class Freshness {
        /** ISO-8601 time of the latest counted AI answer, or null when there is none. */
        public final String latestCountedAnswerAt;
        public final boolean refreshing;

        Freshness(String latestCountedAnswerAt, boolean refreshing) {
            this.latestCountedAnswerAt = latestCountedAnswerAt;
            this.refreshing = refreshing;
        }
    }

    /** A period: two days in Europe/Paris, or the whole history when both are null. */
    public static final class Period {
        final LocalDate start;
        final LocalDate end;

        private Period(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }

        public static Period between(LocalDate start, LocalDate end) {
            return new Period(start, end);
        }

        public static Period allTime() {
            return new Period(null, null);
        }

        void appendTo(StringBuilder query) {
            if (start == null) {
                param(query, "period", "all_time");
            } else {
                param(query, "startDate", start.toString());
                param(query, "endDate", end.toString());
            }
        }
    }

    /**
     * {@code GET /projects}.
     *
     * @return the projects the key can read
     * @throws BotRankException on any refusal or failure
     */
    public List<Project> projects() throws BotRankException {
        JSONArray arr = call("/projects", new StringBuilder(), null).optJSONArray("projects");
        List<Project> out = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null && !p.optString("id").isEmpty()) {
                out.add(new Project(p.optString("id"), p.optString("name", p.optString("id"))));
            }
        }
        return out;
    }

    /**
     * {@code GET /ai-visibility/data-freshness}.
     *
     * @param projectId the BotRank project
     * @return the time of the latest counted answer and whether answers are still being collected
     * @throws BotRankException on any refusal or failure
     */
    public Freshness freshness(String projectId) throws BotRankException {
        JSONObject o = call("/ai-visibility/data-freshness", new StringBuilder(), projectId);
        String latest = o.isNull("latestCountedAnswerAt") ? null : o.optString("latestCountedAnswerAt", null);
        return new Freshness(latest, o.optBoolean("refreshing", false));
    }

    /**
     * {@code GET /ai-visibility/sites/{domain}/pages}, every page of the cursor, bounded by
     * {@link #MAX_LIST_PAGES}. It replaces {@code /ai-visibility/pages?domain=}, deprecated on 20 October 2026,
     * and answers the same list.
     *
     * @param projectId the BotRank project
     * @param domain    a host of the site; BotRank reads it as its registrable domain
     * @param hosts     the hosts to keep, at most 10 (ranks are computed within them)
     * @param period    the period
     * @return the cited pages, most cited first
     * @throws BotRankException on any refusal or failure
     */
    public List<CitedPage> citedPages(String projectId, String domain, List<String> hosts, Period period)
            throws BotRankException {
        List<CitedPage> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_LIST_PAGES; page++) {
            StringBuilder q = new StringBuilder();
            for (String h : hosts) {
                param(q, "host", h);
            }
            param(q, "limit", "100");
            period.appendTo(q);
            if (cursor != null) {
                param(q, "cursor", cursor);
            }
            JSONObject o = call("/ai-visibility/sites/" + segment(domain) + "/pages", q, projectId);
            addCitedPages(o.optJSONArray("pages"), out);
            cursor = o.isNull("nextCursor") ? null : o.optString("nextCursor", null);
            if (cursor == null || cursor.isEmpty()) {
                break;
            }
        }
        return out;
    }

    /** Adds the entries of one list page that carry a URL. */
    private static void addCitedPages(JSONArray arr, List<CitedPage> out) {
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p != null && !p.optString("url").isEmpty()) {
                out.add(new CitedPage(p.optString("url"), p.optInt("rank"), p.optInt("citationCount"),
                        p.isNull(VARIATION) ? null : p.optInt(VARIATION)));
            }
        }
    }

    /**
     * One per-page read: {@code citation-total}, {@code citation-history}, {@code prompts},
     * {@code tags} or {@code co-cited}, under {@code /ai-visibility/pages/}.
     *
     * @param projectId the BotRank project
     * @param route     the last path segment
     * @param url       the cited URL, any of its variants
     * @param period    the period
     * @param limit     the top size for list routes, or 0 to omit it
     * @return the answer as BotRank sent it
     * @throws BotRankException on any refusal or failure
     */
    public JSONObject pageRead(String projectId, String route, String url, Period period, int limit)
            throws BotRankException {
        StringBuilder q = new StringBuilder();
        param(q, "url", url);
        period.appendTo(q);
        if (limit > 0) {
            param(q, "limit", Integer.toString(limit));
        }
        return call("/ai-visibility/pages/" + route, q, projectId);
    }

    /**
     * {@code GET /projects/{projectId}}: the project and its enabled AI engines, each with its
     * {@code refreshInterval} (an ISO 8601 duration, or null when not refreshed automatically).
     *
     * @param projectId the BotRank project
     * @return the answer as BotRank sent it
     * @throws BotRankException on any refusal or failure
     */
    public JSONObject project(String projectId) throws BotRankException {
        return call("/projects/" + segment(projectId), new StringBuilder(), projectId);
    }

    /**
     * One site read under {@code /ai-visibility/sites/{domain}/}: {@code citation-total},
     * {@code citation-share-history} or {@code ranking}. BotRank reads the domain as its registrable
     * domain and names the domain it used in the answer.
     *
     * @param projectId  the BotRank project
     * @param domain     a host of the site
     * @param route      the last path segment
     * @param period     the period
     * @param neighbours for {@code ranking}, the sites listed above and below (1 to 10), or 0 to omit it
     * @return the answer as BotRank sent it
     * @throws BotRankException on any refusal or failure; a domain the project never cited is a 404
     *                          of type {@value #SITE_NOT_CITED}
     */
    public JSONObject siteRead(String projectId, String domain, String route, Period period, int neighbours)
            throws BotRankException {
        StringBuilder q = new StringBuilder();
        period.appendTo(q);
        if (neighbours > 0) {
            param(q, "neighbourCount", Integer.toString(neighbours));
        }
        return call("/ai-visibility/sites/" + segment(domain) + "/" + route, q, projectId);
    }

    /**
     * {@code GET /ai-visibility/visibility-score}, or its {@code /history} when {@code history} is true:
     * the share of the project's analysed answers that mention its brand.
     *
     * @param projectId the BotRank project
     * @param history   true for the daily series
     * @param period    the period
     * @return the answer as BotRank sent it
     * @throws BotRankException on any refusal or failure
     */
    public JSONObject visibilityScore(String projectId, boolean history, Period period) throws BotRankException {
        StringBuilder q = new StringBuilder();
        period.appendTo(q);
        return call("/ai-visibility/visibility-score" + (history ? "/history" : ""), q, projectId);
    }

    /** The problem type of a domain no AI answer of the project ever cited. */
    public static final String SITE_NOT_CITED = "site-not-cited";

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JSONObject call(String path, StringBuilder query, String projectId) throws BotRankException {
        URI uri = URI.create(baseUrl + path + (query.length() == 0 ? "" : "?" + query));
        Response r;
        try {
            r = transport.get(uri, apiKey, projectId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BotRankException(BotRankException.UNREACHABLE, 0, "BotRank call interrupted");
        } catch (IOException e) {
            throw new BotRankException(BotRankException.UNREACHABLE, 0,
                    "BotRank did not answer (" + e.getClass().getSimpleName() + ")");
        }
        if (r.status >= 200 && r.status < 300) {
            try {
                return new JSONObject(r.body);
            } catch (Exception e) {
                throw new BotRankException(BotRankException.UNREACHABLE, r.status, "BotRank sent an unreadable answer");
            }
        }
        throw problem(r);
    }

    /** The problem document of an error answer, reduced to its type and its fixed title. */
    static BotRankException problem(Response r) {
        String type = "http-" + r.status;
        String title = "BotRank refused the call (HTTP " + r.status + ")";
        try {
            JSONObject p = new JSONObject(r.body);
            String uri = p.optString("type", "");
            int slash = uri.lastIndexOf('/');
            if (slash >= 0 && slash < uri.length() - 1) {
                type = uri.substring(slash + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
            }
            String t = p.optString("title", "");
            if (!t.isEmpty() && t.length() <= 120) {
                title = t + " (HTTP " + r.status + ")";
            }
        } catch (Exception ignored) {
            // Not a problem document: keep the status-based type.
        }
        return new BotRankException(type, r.status, title);
    }

    private static Response send(URI uri, String apiKey, String projectId) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .GET();
        Optional.ofNullable(projectId).ifPresent(p -> b.header("Project-Id", p));
        HttpResponse<java.io.InputStream> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (java.io.InputStream in = resp.body()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new IOException("answer larger than " + MAX_BODY_BYTES + " bytes");
            }
            return new Response(resp.statusCode(), new String(body, StandardCharsets.UTF_8));
        }
    }

    private static void param(StringBuilder q, String name, String value) {
        if (q.length() > 0) {
            q.append('&');
        }
        q.append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
