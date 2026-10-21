package org.jahia.se.modules.georeadiness.check;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The comparison of a sitemap with the published pages, every finding at once. */
class SitemapCompareTest {

    private static PublishedMap.Entry page(String jcrPath, String modifiedOn, boolean noindex) {
        return new PublishedMap.Entry(jcrPath, jcrPath, modifiedOn, -1, "jnt:page", noindex, true, "en");
    }

    @Test
    void reportsEachFindingOnceAndPairsRedirects() {
        Map<String, PublishedMap.Entry> published = new LinkedHashMap<>();
        published.put("/home.html", page("/sites/s/home", "2026-10-01", false));
        published.put("/about.html", page("/sites/s/home/about", "2026-10-01", true));
        published.put("/nice", page("/sites/s/home/offer", "2026-10-01", false));
        published.put("/news.html", page("/sites/s/home/news", "2026-10-01", false));

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("/home.html", "2026-09-01");
        entries.put("/about.html", "2026-10-01");
        entries.put("/home/offer.html", null);
        entries.put("/ghost.html", null);

        JSONObject out = new JSONObject();
        SitemapCheck.compare(out, "/sites/s", entries, published);

        // The vanity page is listed under the address that redirects to it: one redirect finding,
        // neither "missing" for /nice nor "unknown" for /home/offer.html.
        assertThat(out.getJSONArray("redirects").length()).isEqualTo(1);
        assertThat(out.getJSONArray("redirects").getJSONObject(0).getString("path")).isEqualTo("/nice");
        assertThat(out.getJSONArray("redirects").getJSONObject(0).getString("detail")).isEqualTo("/home/offer.html");
        assertThat(out.getJSONArray("missing").length()).isEqualTo(1);
        assertThat(out.getJSONArray("missing").getJSONObject(0).getString("path")).isEqualTo("/news.html");
        assertThat(out.getJSONArray("unknown").length()).isEqualTo(1);
        assertThat(out.getJSONArray("unknown").getJSONObject(0).getString("path")).isEqualTo("/ghost.html");
        assertThat(out.getJSONArray("noindexListed").length()).isEqualTo(1);
        assertThat(out.getJSONArray("noindexListed").getJSONObject(0).getString("path")).isEqualTo("/about.html");
        assertThat(out.getJSONArray("staleDate").length()).isEqualTo(1);
        assertThat(out.getJSONArray("staleDate").getJSONObject(0).getString("detail")).isEqualTo("2026-09-01 → 2026-10-01");
        assertThat(out.getBoolean("agrees")).isFalse();
    }

    @Test
    void agreesWhenEveryPublishedPageIsListedWithItsDate() {
        Map<String, PublishedMap.Entry> published = new LinkedHashMap<>();
        published.put("/home.html", page("/sites/s/home", "2026-10-01", false));
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("/home.html", "2026-10-01T08:00:00Z");

        JSONObject out = new JSONObject();
        SitemapCheck.compare(out, "/sites/s", entries, published);

        assertThat(out.getBoolean("agrees")).isTrue();
    }
}
