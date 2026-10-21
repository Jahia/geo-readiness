package org.jahia.se.modules.georeadiness.botrank;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Screen B's summary, against a BotRank that answers like the live API of 20 October 2026. */
class SiteCitationsTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 21);
    private static final LocalDate END = LocalDate.of(2026, 10, 20);

    private final List<URI> sent = new ArrayList<>();

    private static BotRankClient.CitedPage page(String url, int rank, int citations, Integer variation) {
        return new BotRankClient.CitedPage(url, rank, citations, variation);
    }

    /**
     * a.com and its subdomain fr.a.com share one registrable domain; b.org was never cited; the
     * ranking of a.com fails with a 503.
     */
    private BotRankClient botRank() {
        return new BotRankClient("https://api.example/v1", "brk_x", (uri, key, project) -> {
            sent.add(uri);
            String path = uri.getPath();
            if (path.contains("/sites/b.org/")) {
                return new BotRankClient.Response(404,
                        "{\"type\":\"https://api.botrank.ai/public/v1/problems/site-not-cited\",\"title\":\"Site not cited\",\"status\":404}");
            }
            if (path.endsWith("/citation-total")) {
                return new BotRankClient.Response(200, "{\"domain\":\"a.com\",\"citationCount\":40,\"citationCountVariation\":null,"
                        + "\"shareOfCitations\":0.02,\"shareOfCitationsVariation\":null,\"citedPageCount\":3,\"citedPageCountVariation\":1}");
            }
            if (path.endsWith("/ranking")) {
                return new BotRankClient.Response(503, "down");
            }
            if (path.endsWith("/citation-share-history")) {
                return new BotRankClient.Response(200, "{\"domain\":\"a.com\",\"days\":[{\"date\":\"2026-10-19\",\"shareOfCitations\":0.018},"
                        + "{\"date\":\"2026-10-20\",\"shareOfCitations\":null},{\"date\":\"not a day\",\"shareOfCitations\":1}]}");
            }
            if (path.endsWith("/visibility-score")) {
                return new BotRankClient.Response(200, "{\"visibilityScore\":0.44,\"visibilityScoreVariation\":null,\"analysedAnswerCount\":1473,"
                        + "\"aiSurfaces\":[{\"aiSurface\":\"chatgpt\",\"visibilityScore\":0.31,\"visibilityScoreVariation\":0.02,\"analysedAnswerCount\":375}],"
                        + "\"markets\":[{\"market\":\"fr\",\"visibilityScore\":0.44,\"visibilityScoreVariation\":null,\"analysedAnswerCount\":1473}]}");
            }
            if (path.endsWith("/visibility-score/history")) {
                return new BotRankClient.Response(200, "{\"days\":[{\"date\":\"2026-10-20\",\"visibilityScore\":0.416}]}");
            }
            if (path.endsWith("/projects/p")) {
                return new BotRankClient.Response(200, "{\"id\":\"p\",\"aiSurfaces\":[{\"aiSurface\":\"chatgpt\",\"refreshInterval\":\"P1D\"},"
                        + "{\"aiSurface\":\"gemini\",\"refreshInterval\":null},{\"aiSurface\":\"claude\",\"refreshInterval\":\"every day\"}]}");
            }
            return new BotRankClient.Response(404, "{}");
        });
    }

    private JSONObject summary(List<String> hosts, List<BotRankClient.CitedPage> recent) {
        return SiteCitations.read(new SiteCitations.Reads(botRank(), "p", START, END), hosts, recent,
                url -> url.endsWith("/x") ? new PageResolver.Target("/sites/demo/home/x", "fr", "published") : null);
    }

    private static final List<BotRankClient.CitedPage> RECENT = Arrays.asList(
            page("https://a.com/x", 1, 30, 4),
            page("https://fr.a.com/y", 2, 8, null),
            page("https://a.com/z", 3, 2, null));

    @Test
    void aDomainIsReadOnceForAllItsHosts() {
        JSONObject s = summary(Arrays.asList("a.com", "fr.a.com"), RECENT);

        JSONArray domains = s.getJSONArray("domains");
        assertThat(domains.length()).isEqualTo(1);
        JSONObject a = domains.getJSONObject(0);
        assertThat(a.getString("domain")).isEqualTo("a.com");
        assertThat(a.getInt("citations")).isEqualTo(40);
        assertThat(a.isNull("variation")).isTrue();
        assertThat(a.getDouble("share")).isEqualTo(0.02);
        assertThat(a.getInt("citedPagesVariation")).isEqualTo(1);
        // One citation-total per host, the ranking and history of the domain once.
        assertThat(sent.stream().filter(u -> u.getPath().endsWith("/citation-total"))).hasSize(2);
        assertThat(sent.stream().filter(u -> u.getPath().endsWith("/citation-share-history"))).hasSize(1);
        assertThat(sent.stream().filter(u -> u.getPath().endsWith("/ranking")).findFirst().orElseThrow().getQuery())
                .contains("neighbourCount=3", "startDate=2026-09-21", "endDate=2026-10-20");
    }

    @Test
    void hostsGetTheirOwnFiguresAndAnApproximateShare() {
        JSONArray hosts = summary(Arrays.asList("a.com", "fr.a.com"), RECENT).getJSONArray("hosts");

        JSONObject a = hosts.getJSONObject(0);
        assertThat(a.getInt("citations")).isEqualTo(32);
        assertThat(a.getInt("citedPages")).isEqualTo(2);
        // 32 of the domain's 40 citations, which are 2% of the project's: 1.6%.
        assertThat(a.getDouble("share")).isCloseTo(0.016, org.assertj.core.data.Offset.offset(1e-9));
        JSONObject fr = hosts.getJSONObject(1);
        assertThat(fr.getString("domain")).isEqualTo("a.com");
        assertThat(fr.getInt("citations")).isEqualTo(8);
    }

    @Test
    void aDomainNeverCitedIsAStateAndOneFailedReadIsCounted() {
        JSONObject s = summary(Arrays.asList("a.com", "b.org"), RECENT);

        JSONObject b = s.getJSONArray("domains").getJSONObject(1);
        assertThat(b.getString("domain")).isEqualTo("b.org");
        assertThat(b.getBoolean("notCited")).isTrue();
        assertThat(s.getJSONArray("hosts").getJSONObject(1).isNull("share")).isTrue();
        // The ranking's 503 is the only failure; the rest of the summary is kept.
        assertThat(s.getInt("failures")).isEqualTo(1);
        assertThat(s.getJSONArray("domains").getJSONObject(0).getJSONArray("ranking").length()).isZero();
    }

    @Test
    void historyKeepsRealDaysAndNullShares() {
        JSONArray history = summary(Collections.singletonList("a.com"), RECENT)
                .getJSONArray("domains").getJSONObject(0).getJSONArray("shareHistory");
        assertThat(history.length()).isEqualTo(2);
        assertThat(history.getJSONObject(1).isNull("share")).isTrue();
    }

    @Test
    void topPagesNameTheirPageWhenOneResolves() {
        JSONArray top = summary(Collections.singletonList("a.com"), RECENT).getJSONArray("topPages");
        assertThat(top.length()).isEqualTo(3);
        assertThat(top.getJSONObject(0).getString("jcrPath")).isEqualTo("/sites/demo/home/x");
        assertThat(top.getJSONObject(0).getInt("variation")).isEqualTo(4);
        assertThat(top.getJSONObject(1).has("jcrPath")).isFalse();
    }

    @Test
    void visibilityAndEnginesAreKeptAsSent() {
        JSONObject s = summary(Collections.singletonList("a.com"), RECENT);

        JSONObject v = s.getJSONObject("visibility");
        assertThat(v.getDouble("score")).isEqualTo(0.44);
        assertThat(v.getInt("answers")).isEqualTo(1473);
        assertThat(v.getJSONArray("byEngine").getJSONObject(0).getString("key")).isEqualTo("chatgpt");
        assertThat(v.getJSONArray("byMarket").getJSONObject(0).getString("key")).isEqualTo("fr");
        assertThat(v.getJSONArray("history").length()).isEqualTo(1);

        JSONArray engines = s.getJSONArray("engines");
        assertThat(engines.getJSONObject(0).getString("refreshInterval")).isEqualTo("P1D");
        assertThat(engines.getJSONObject(1).isNull("refreshInterval")).isTrue();
        // Not an ISO 8601 duration: dropped rather than shown.
        assertThat(engines.getJSONObject(2).isNull("refreshInterval")).isTrue();
    }
}
