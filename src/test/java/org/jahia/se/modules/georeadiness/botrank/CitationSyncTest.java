package org.jahia.se.modules.georeadiness.botrank;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

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
}
