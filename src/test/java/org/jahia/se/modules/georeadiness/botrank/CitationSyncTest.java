package org.jahia.se.modules.georeadiness.botrank;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Grouping, resolution and the variation guard, on data only. */
class CitationSyncTest {

    private static final List<String> HOSTS = Arrays.asList("example.com");

    private static BotRankClient.CitedPage page(String url, int rank, int citations, Integer variation) {
        return new BotRankClient.CitedPage(url, rank, citations, variation);
    }

    private static PageResolver resolver(JSONObject mappings) {
        Map<String, PageResolver.Target> byPath = new HashMap<>();
        byPath.put("/fr/home.html", new PageResolver.Target("/sites/demo/home", "fr", "published"));
        byPath.put("/assurance-habitation", new PageResolver.Target("/sites/demo/home/habitation", "fr", "vanity"));
        Map<String, String> langs = new HashMap<>();
        langs.put("/sites/demo/home/old", "fr");
        return PageResolver.of("/sites/demo", byPath, langs, mappings);
    }

    @Test
    void severalUrlsOfOnePageAddUp() {
        PageResolver r = resolver(new JSONObject());
        Map<String, CitationSync.Group> groups = CitationSync.group(Arrays.asList(
                page("https://example.com/fr/home.html", 3, 5, 2),
                page("https://www.example.com/fr/home.html/?utm_source=x", 7, 1, 1),
                page("https://example.com/assurance-habitation", 1, 9, -1)), HOSTS, r::resolve);

        CitationSync.Group home = groups.get("/sites/demo/home@fr");
        assertThat(home.citations).isEqualTo(6);
        assertThat(home.variation).isEqualTo(3);
        assertThat(home.rank).isEqualTo(3);
        assertThat(home.byCitations().get(0).url).isEqualTo("https://example.com/fr/home.html");
        assertThat(groups.get("/sites/demo/home/habitation@fr").target.via).isEqualTo("vanity");
    }

    @Test
    void oneUnknownVariationMakesTheSumUnknown() {
        Map<String, CitationSync.Group> groups = CitationSync.group(Arrays.asList(
                page("https://example.com/fr/home.html", 1, 5, 2),
                page("https://example.com/fr/home.html#a", 2, 1, null)), HOSTS, resolver(new JSONObject())::resolve);
        assertThat(groups.get("/sites/demo/home@fr").variation).isNull();
    }

    @Test
    void urlsOnOtherHostsAreIgnoredEvenIfTheyResolve() {
        Map<String, CitationSync.Group> groups = CitationSync.group(Arrays.asList(
                page("https://fr.example.com/fr/home.html", 1, 5, 0)), HOSTS, resolver(new JSONObject())::resolve);
        assertThat(groups).isEmpty();
    }

    @Test
    void manualMappingWinsAndQueriesOnlyResolveThroughIt() {
        JSONObject mappings = new JSONObject().put(CitedUrls.key("https://example.com/legacy?id=4"), "/sites/demo/home/old@fr");
        PageResolver r = resolver(mappings);
        assertThat(r.resolve("https://example.com/legacy?id=4").via).isEqualTo("mapping");
        assertThat(r.resolve("https://example.com/legacy?id=5")).isNull();
        assertThat(r.resolve("https://example.com/sites/demo/home/old.html").jcrPath).isEqualTo("/sites/demo/home/old");
        assertThat(r.resolve("https://example.com/sites/other/x.html")).isNull();
    }

    @Test
    void unresolvedListsTheSiteOnlyMostCitedFirst() {
        JSONArray left = CitationSync.unresolved(Arrays.asList(
                page("https://example.com/nowhere", 4, 2, null),
                page("https://example.com/fr/home.html", 1, 9, null),
                page("https://example.com/elsewhere", 2, 7, null),
                page("https://other.com/x", 3, 50, null)), HOSTS, resolver(new JSONObject())::resolve);
        assertThat(left.length()).isEqualTo(2);
        assertThat(left.getJSONObject(0).getString("url")).isEqualTo("https://example.com/elsewhere");
    }

    @Test
    void variationsAreDroppedWhenThePreviousWindowHadNoRun() {
        JSONArray in = new JSONArray().put(new JSONObject().put("id", "p").put("text", "Q?").put("market", "fr")
                .put("citationCount", 14).put("citationCountVariation", 14).put("lastCitationDay", "2026-10-15"));
        assertThat(CitationSync.prompts(in, false).getJSONObject(0).isNull("variation")).isTrue();
        assertThat(CitationSync.prompts(in, true).getJSONObject(0).getInt("variation")).isEqualTo(14);
    }

    @Test
    void textFromBotRankIsBounded() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append('x');
        }
        assertThat(CitationSync.text(sb.toString())).hasSize(CitationSync.MAX_TEXT);
        assertThat(CitationSync.text(null)).isEmpty();
    }

    private static CitationSync.Group group(int variation) {
        CitationSync.Group g = new CitationSync.Group(new PageResolver.Target("/sites/demo/home", "fr", "published"));
        g.add(page("https://example.com/fr/home.html", 1, 14, variation));
        return g;
    }

    private static final LocalDate START = LocalDate.of(2026, 9, 16);
    private static final LocalDate END = LocalDate.of(2026, 10, 15);

    private static BotRankClient answering(String history) {
        return new BotRankClient("https://api.example/v1", "brk_x", (uri, key, project) -> {
            String path = uri.getPath();
            if (path.endsWith("citation-history")) {
                return new BotRankClient.Response(200, history);
            }
            if (path.endsWith("citation-total")) {
                return new BotRankClient.Response(200, "{\"citationCount\":14,\"aiSurfaces\":[]}");
            }
            return new BotRankClient.Response(200, "{}");
        });
    }

    private static CitationSync.Window window(BotRankClient client) {
        return new CitationSync.Window(client, "p", START, END, 3);
    }

    /** Like BotRank: one registrable domain per call, read from the path; a subdomain reads its domain. */
    private static BotRankClient twoDomains() {
        return new BotRankClient("https://api.example/v1", "brk_x", (uri, key, project) -> {
            String path = uri.getPath();
            String pages = "[]";
            if (path.endsWith("/sites/a.com/pages") || path.endsWith("/sites/fr.a.com/pages")) {
                pages = "[{\"url\":\"https://a.com/x\",\"rank\":1,\"citationCount\":9,\"citationCountVariation\":null},"
                        + "{\"url\":\"https://fr.a.com/y\",\"rank\":2,\"citationCount\":3,\"citationCountVariation\":1}]";
            } else if (path.endsWith("/sites/b.org/pages")) {
                pages = "[{\"url\":\"https://b.org/z\",\"rank\":1,\"citationCount\":5,\"citationCountVariation\":2}]";
            }
            return new BotRankClient.Response(200, "{\"pages\":" + pages + ",\"nextCursor\":null}");
        });
    }

    @Test
    void hostsOnSeveralDomainsAreAllReadAndRankedAcrossTheSite() throws Exception {
        List<BotRankClient.CitedPage> pages = CitationSync.citedPages(twoDomains(), "p",
                Arrays.asList("a.com", "fr.a.com", "b.org"), BotRankClient.Period.allTime());

        // Both domains are read; the a.com pages, listed for a.com and again for fr.a.com, count once.
        assertThat(pages).extracting(p -> p.url)
                .containsExactly("https://a.com/x", "https://b.org/z", "https://fr.a.com/y");
        // One ranking for the site, most cited first.
        assertThat(pages).extracting(p -> p.rank).containsExactly(1, 2, 3);
        assertThat(pages.get(1).variation).isEqualTo(2);
    }

    @Test
    void aSingleDomainKeepsBotRankOrder() throws Exception {
        List<BotRankClient.CitedPage> pages = CitationSync.citedPages(twoDomains(), "p",
                Arrays.asList("a.com"), BotRankClient.Period.allTime());
        assertThat(pages).extracting(p -> p.url).containsExactly("https://a.com/x", "https://fr.a.com/y");
        assertThat(pages).extracting(p -> p.rank).containsExactly(1, 2);
    }

    @Test
    void aVariationIsKeptWhenThePreviousWindowHadARun() throws Exception {
        JSONObject e = CitationSync.recentEntry(window(answering("{\"days\":[{\"date\":\"2026-09-01\",\"citationCount\":0},{\"date\":\"2026-10-01\",\"citationCount\":14}]}")),
                group(14), 14);
        assertThat(e.getInt("variation")).isEqualTo(14);
        assertThat(e.getInt("previousRunDays")).isEqualTo(1);
    }

    @Test
    void aVariationAgainstAnEmptyPreviousWindowIsDropped() throws Exception {
        JSONObject e = CitationSync.recentEntry(window(answering("{\"days\":[{\"date\":\"2026-10-01\",\"citationCount\":14}]}")),
                group(14), 14);
        assertThat(e.isNull("variation")).isTrue();
    }

    @Test
    void aPageWhoseDetailsFailNeverCarriesTheRawVariation() throws Exception {
        BotRankClient failing = new BotRankClient("https://api.example/v1", "brk_x",
                (uri, key, project) -> new BotRankClient.Response(503, "down"));
        CitationSync.Window w = window(failing);
        JSONObject e = CitationSync.recentEntry(w, group(14), 14);
        assertThat(e.isNull("variation")).isTrue();
        assertThat(e.getBoolean("detailsMissing")).isTrue();
        assertThat(w.failures).isEqualTo(1);
    }
}
