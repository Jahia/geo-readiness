package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterisation tests for {@link LinkGraph#report}.
 *
 * These exist to freeze what report() does TODAY, not to argue that it is
 * right. report() is about to be decomposed - Sonar scores it at cognitive
 * complexity 28 - and it is the only place that turns raw inbound-link counts
 * into the module's two headline findings (GEO-22 orphans, and the quieter
 * nav-only pages). Every assertion below states the ACTUAL current output,
 * including the exact JSON keys and their JSON types, because a consumer in
 * another file has already been bitten by two keys' types being swapped.
 *
 * {@link LinkGraph#addPage} is used throughout to build realistic
 * accumulators from rendered HTML rather than reaching inside the
 * accumulator, per the module's own contract (links are read from HTML, not
 * the repository).
 */
class LinkGraphTest {

    private static final String LANG = "en";
    private static final String SITE = "/sites/mySite";

    // ---------------------------------------------------------------- helpers

    private static LinkGraph.Accumulator newAcc() {
        return new LinkGraph.Accumulator();
    }

    private static Map<String, PublishedMap.Entry> newPublished() {
        return new LinkedHashMap<>();
    }

    /** A published *page* (Entry.page == true), which is all report() considers. */
    private static PublishedMap.Entry pageEntry(String jcrPath, String language) {
        return new PublishedMap.Entry(jcrPath, "Title for " + jcrPath, null, -1L,
                "jnt:page", false, true, language);
    }

    /** A published content item (Entry.page == false) - report() must skip these. */
    private static PublishedMap.Entry contentEntry(String jcrPath, String language) {
        return new PublishedMap.Entry(jcrPath, "Content for " + jcrPath, null, -1L,
                "jnt:article", false, false, language);
    }

    private static void addPage(Map<String, PublishedMap.Entry> published, String path, String jcrPath) {
        published.put(path, pageEntry(jcrPath, LANG));
    }

    private static JSONObject firstOrphan(JSONObject report) {
        return report.getJSONArray("orphans").getJSONObject(0);
    }

    private static JSONObject firstWeak(JSONObject report) {
        return report.getJSONArray("weak").getJSONObject(0);
    }

    private static List<String> pathsOf(JSONArray rows) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            out.add(rows.getJSONObject(i).getString("path"));
        }
        return out;
    }

    // ------------------------------------------------------- null / empty inputs

    @Nested
    @DisplayName("null and empty inputs")
    class NullAndEmptyInputs {

        @Test
        @DisplayName("a null accumulator returns a completely empty object, not even pagesRead")
        void nullAccumulator_returnsCompletelyEmptyObject() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/p", SITE + "/p");

            JSONObject out = LinkGraph.report(null, published, LANG, null, false, null);

            assertThat(out.keySet()).isEmpty();
        }

        @Test
        @DisplayName("a null accumulator short-circuits before published is touched, so published may also be null")
        void nullAccumulator_toleratesNullPublishedToo() {
            JSONObject out = LinkGraph.report(null, null, LANG, null, false, null);

            assertThat(out.keySet()).isEmpty();
        }

        @Test
        @DisplayName("a null published map throws NPE once the accumulator is non-null")
        void nonNullAccumulator_nullPublished_throwsNPE() {
            LinkGraph.Accumulator acc = newAcc();

            assertThatThrownBy(() -> LinkGraph.report(acc, null, LANG, null, false, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an empty accumulator with real published pages reports every page as an orphan")
        void emptyAccumulator_allPagesAreOrphans() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/a", SITE + "/a");
            addPage(published, "/b", SITE + "/b");

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, false, null);

            assertThat(out.getInt("pagesRead")).isZero();
            assertThat(out.getInt("pages")).isEqualTo(2);
            assertThat(out.getJSONArray("orphans").length()).isEqualTo(2);
            assertThat(out.getJSONArray("weak")).isEmpty();
        }

        @Test
        @DisplayName("an empty published map reports zero pages and empty arrays/object, regardless of the accumulator")
        void emptyPublishedMap_producesZeroPagesAndEmptyCollections() {
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/from", "<a href=\"/somewhere\">x</a>");

            JSONObject out = LinkGraph.report(acc, newPublished(), LANG, null, false, null);

            assertThat(out.getInt("pagesRead")).isEqualTo(1);
            assertThat(out.getInt("pages")).isZero();
            assertThat(out.getJSONArray("orphans")).isEmpty();
            assertThat(out.getJSONArray("weak")).isEmpty();
            assertThat(out.getJSONObject("counts").keySet()).isEmpty();
        }

        @Test
        @DisplayName("a null homePath is safe and excludes nothing")
        void nullHomePath_isSafeAndExcludesNothing() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/a", SITE + "/a");

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, false, null);

            assertThat(out.getInt("pages")).isEqualTo(1);
        }

        @Test
        @DisplayName("a null language includes pages of every language")
        void nullLanguage_includesEveryLanguage() {
            Map<String, PublishedMap.Entry> published = newPublished();
            published.put("/en-page", pageEntry(SITE + "/en-page", "en"));
            published.put("/fr-page", pageEntry(SITE + "/fr-page", "fr"));

            JSONObject out = LinkGraph.report(newAcc(), published, null, null, false, null);

            assertThat(out.getInt("pages")).isEqualTo(2);
        }

        @Test
        @DisplayName("a non-null language excludes pages recorded under a different language")
        void nonNullLanguage_excludesOtherLanguages() {
            Map<String, PublishedMap.Entry> published = newPublished();
            published.put("/en-page", pageEntry(SITE + "/en-page", "en"));
            published.put("/fr-page", pageEntry(SITE + "/fr-page", "fr"));

            JSONObject out = LinkGraph.report(newAcc(), published, "en", null, false, null);

            assertThat(out.getInt("pages")).isEqualTo(1);
            assertThat(firstOrphan(out).getString("path")).isEqualTo("/en-page");
        }

        @Test
        @DisplayName("a null notListed set, combined with sitemapPresent, treats every orphan candidate as listed")
        void nullNotListed_withSitemapPresent_treatsEverythingAsListed() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/a", SITE + "/a");

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, true, null);

            assertThat(out.getJSONArray("orphans")).isEmpty();
            assertThat(out.getJSONArray("weak").length()).isEqualTo(1);
            assertThat(firstWeak(out).getString("why")).isEqualTo("sitemapOnly");
        }

        @Test
        @DisplayName("content items (Entry.page == false) are skipped entirely, not counted and not reported")
        void contentItems_areSkippedEntirely() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/a-page", SITE + "/a-page");
            published.put("/an-article", contentEntry(SITE + "/an-article", LANG));

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, false, null);

            assertThat(out.getInt("pages")).isEqualTo(1);
            assertThat(pathsOf(out.getJSONArray("orphans"))).containsExactly("/a-page");
        }
    }

    // ------------------------------------------------------------- the home page

    @Nested
    @DisplayName("the home page is special-cased and never reported")
    class HomePage {

        @Test
        @DisplayName("the home page is excluded from pages, orphans, weak and counts, even with zero inbound links")
        void homePage_excludedFromEverything() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/home", SITE + "/home");
            addPage(published, "/other", SITE + "/other");

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, SITE + "/home", false, null);

            assertThat(out.getInt("pages")).isEqualTo(1);
            assertThat(pathsOf(out.getJSONArray("orphans"))).containsExactly("/other");
            assertThat(out.getJSONObject("counts").keySet()).containsExactly(SITE + "/other@" + LANG);
        }

        @Test
        @DisplayName("the match is on Entry.jcrPath against homePath, not on the published (public) path key")
        void homeMatch_isByJcrPath_notByPublicPathKey() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/home", SITE + "/home");

            // Passing the PUBLIC path instead of the jcrPath does not match anything:
            // the page is treated as an ordinary page, not excluded as home.
            JSONObject out = LinkGraph.report(newAcc(), published, LANG, "/home", false, null);

            assertThat(out.getInt("pages")).isEqualTo(1);
            assertThat(pathsOf(out.getJSONArray("orphans"))).containsExactly("/home");
        }
    }

    // ------------------------------------------------------- orphan vs. nav-only

    @Nested
    @DisplayName("orphan vs. nav-only, the report's two findings")
    class OrphanVsNavOnly {

        @Test
        @DisplayName("zero inbound links, no sitemap at all -> orphan with why == JSON null")
        void zeroLinks_noSitemap_isOrphanWithNullWhy() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/lonely", SITE + "/lonely");

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, false, null);

            JSONObject row = firstOrphan(out);
            assertThat(row.isNull("why")).isTrue();
        }

        @Test
        @DisplayName("zero inbound links, sitemap present and this path IS listed -> weak/sitemapOnly, not an orphan")
        void zeroLinks_sitemapPresentAndListed_isWeakSitemapOnly() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/lonely", SITE + "/lonely");
            Set<String> notListed = new LinkedHashSet<>();

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, true, notListed);

            assertThat(out.getJSONArray("orphans")).isEmpty();
            assertThat(firstWeak(out).getString("why")).isEqualTo("sitemapOnly");
        }

        @Test
        @DisplayName("zero inbound links, sitemap present but this path is in notListed -> still an orphan")
        void zeroLinks_sitemapPresentButNotListed_isStillOrphan() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/lonely", SITE + "/lonely");
            Set<String> notListed = new LinkedHashSet<>();
            notListed.add("/lonely");

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, true, notListed);

            assertThat(out.getJSONArray("weak")).isEmpty();
            assertThat(firstOrphan(out).isNull("why")).isTrue();
        }

        @Test
        @DisplayName("without a sitemap, notListed is never consulted: every zero-link page is an orphan")
        void withoutSitemap_notListedIsIgnored() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/lonely", SITE + "/lonely");
            Set<String> notListed = new LinkedHashSet<>(); // deliberately empty, i.e. "would be listed"

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, false, notListed);

            assertThat(out.getJSONArray("weak")).isEmpty();
            assertThat(out.getJSONArray("orphans").length()).isEqualTo(1);
        }

        @Test
        @DisplayName("linked only from navigation -> weak/navOnly, regardless of the sitemap arguments")
        void navOnlyLink_isWeakNavOnly() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/target", SITE + "/target");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/from", "<nav><a href=\"/target\">menu</a></nav>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);

            assertThat(out.getJSONArray("orphans")).isEmpty();
            assertThat(firstWeak(out).getString("why")).isEqualTo("navOnly");
        }

        @Test
        @DisplayName("linked from content -> not reported in either array, only recorded in counts")
        void contentLink_isNotReportedAtAll() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/target", SITE + "/target");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/from", "<main><a href=\"/target\">read more</a></main>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);

            assertThat(out.getJSONArray("orphans")).isEmpty();
            assertThat(out.getJSONArray("weak")).isEmpty();
            JSONObject counts = out.getJSONObject("counts").getJSONObject(SITE + "/target@" + LANG);
            assertThat(counts.getInt("content")).isEqualTo(1);
            assertThat(counts.getInt("nav")).isZero();
        }

        @Test
        @DisplayName("linked from BOTH nav and content (different source pages) -> content wins, not reported")
        void linkedFromBothNavAndContent_contentDominatesReporting() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/target", SITE + "/target");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/menu-source", "<nav><a href=\"/target\">menu</a></nav>");
            LinkGraph.addPage(acc, "/body-source", "<main><a href=\"/target\">body</a></main>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);

            assertThat(out.getJSONArray("orphans")).isEmpty();
            assertThat(out.getJSONArray("weak")).isEmpty();
            JSONObject counts = out.getJSONObject("counts").getJSONObject(SITE + "/target@" + LANG);
            assertThat(counts.getInt("nav")).isEqualTo(1);
            assertThat(counts.getInt("content")).isEqualTo(1);
        }
    }

    // ------------------------------------------------- building the graph with addPage

    @Nested
    @DisplayName("building realistic graphs with addPage")
    class BuildingTheGraph {

        @Test
        @DisplayName("footer is treated as navigation, same as nav")
        void footerLinks_countAsNav() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/target", SITE + "/target");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/from", "<footer><a href=\"/target\">x</a></footer>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);

            assertThat(firstWeak(out).getString("why")).isEqualTo("navOnly");
        }

        @Test
        @DisplayName("header is deliberately NOT navigation: a link there counts as content")
        void headerLinks_countAsContent_notNav() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/target", SITE + "/target");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/from", "<header><a href=\"/target\">x</a></header>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);

            // Not weak/navOnly: the header link registered as content, so the page
            // is fully "found" and absent from both reported arrays.
            assertThat(out.getJSONArray("orphans")).isEmpty();
            assertThat(out.getJSONArray("weak")).isEmpty();
            JSONObject counts = out.getJSONObject("counts").getJSONObject(SITE + "/target@" + LANG);
            assertThat(counts.getInt("content")).isEqualTo(1);
            assertThat(counts.getInt("nav")).isZero();
        }

        @Test
        @DisplayName("a page linking to itself does not count as an inbound link")
        void selfLink_isNotCounted() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/self", SITE + "/self");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/self", "<main><a href=\"/self\">self</a></main>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);

            assertThat(out.getJSONArray("orphans").length()).isEqualTo(1);
            assertThat(firstOrphan(out).getString("path")).isEqualTo("/self");
        }

        @Test
        @DisplayName("duplicate links to the same target within one page count once, not twice")
        void duplicateLinksInOnePage_countOnce() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/target", SITE + "/target");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/from",
                    "<main><a href=\"/target\">one</a><a href=\"/target\">two</a></main>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);

            JSONObject counts = out.getJSONObject("counts").getJSONObject(SITE + "/target@" + LANG);
            assertThat(counts.getInt("content")).isEqualTo(1);
        }

        @Test
        @DisplayName("the same target linked from N distinct source pages accumulates to N")
        void manySources_accumulateContentCount() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/popular", SITE + "/popular");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/a", "<main><a href=\"/popular\">x</a></main>");
            LinkGraph.addPage(acc, "/b", "<main><a href=\"/popular\">x</a></main>");
            LinkGraph.addPage(acc, "/c", "<main><a href=\"/popular\">x</a></main>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);

            JSONObject counts = out.getJSONObject("counts").getJSONObject(SITE + "/popular@" + LANG);
            assertThat(counts.getInt("content")).isEqualTo(3);
            assertThat(out.getJSONArray("orphans")).isEmpty();
            assertThat(out.getJSONArray("weak")).isEmpty();
        }

        @Test
        @DisplayName("links to a path absent from the published map are silently dropped from the report")
        void linksToUnpublishedPath_areSilentlyDropped() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/known", SITE + "/known");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/from", "<main><a href=\"/unpublished\">x</a></main>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);

            assertThat(out.getInt("pagesRead")).isEqualTo(1);
            assertThat(out.getInt("pages")).isEqualTo(1);
            assertThat(out.getJSONObject("counts").keySet()).containsExactly(SITE + "/known@" + LANG);
            assertThat(pathsOf(out.getJSONArray("orphans"))).containsExactly("/known");
        }
    }

    // -------------------------------------------------------------------- caps

    @Nested
    @DisplayName("caps on the reported arrays and counts")
    class Caps {

        @Test
        @DisplayName("the orphans array is capped at 200, keeping the first 200 in published-map iteration order")
        void orphansArray_capsAt200_inInsertionOrder() {
            // The cap (MAX_REPORTED) is a private constant documented in
            // LinkGraph.java:62 as 200; re-asserted here as a literal.
            Map<String, PublishedMap.Entry> published = newPublished();
            int total = 205;
            for (int i = 0; i < total; i++) {
                addPage(published, "/orphan" + i, SITE + "/orphan" + i);
            }

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, false, null);

            assertThat(out.getInt("pages")).isEqualTo(total);
            JSONArray orphans = out.getJSONArray("orphans");
            assertThat(orphans.length()).isEqualTo(200);
            List<String> paths = pathsOf(orphans);
            assertThat(paths.get(0)).isEqualTo("/orphan0");
            assertThat(paths.get(199)).isEqualTo("/orphan199");
            assertThat(paths).doesNotContain("/orphan200", "/orphan204");
        }

        @Test
        @DisplayName("the counts object is capped at 2000 entries, independently of the orphans cap")
        void countsObject_capsAt2000() {
            // MAX_COUNTS is a private constant documented in LinkGraph.java:64 as
            // 2000; re-asserted here as a literal.
            Map<String, PublishedMap.Entry> published = newPublished();
            int total = 2005;
            for (int i = 0; i < total; i++) {
                addPage(published, "/p" + i, SITE + "/p" + i);
            }

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, false, null);

            assertThat(out.getInt("pages")).isEqualTo(total);
            assertThat(out.getJSONObject("counts").length()).isEqualTo(2000);
            // The orphans cap is lower and independent, so it still bites at 200.
            assertThat(out.getJSONArray("orphans").length()).isEqualTo(200);
        }
    }

    // ------------------------------------------------------------- the JSON shape

    @Nested
    @DisplayName("the exact JSON shape")
    class JsonShape {

        @Test
        @DisplayName("top-level keys and their JSON types")
        void topLevelKeys_andTypes() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/p", SITE + "/p");

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, false, null);

            assertThat(out.keySet()).containsExactlyInAnyOrder("pagesRead", "pages", "orphans", "weak", "counts");
            assertThat(out.get("pagesRead")).isInstanceOf(Integer.class);
            assertThat(out.get("pages")).isInstanceOf(Integer.class);
            assertThat(out.get("orphans")).isInstanceOf(JSONArray.class);
            assertThat(out.get("weak")).isInstanceOf(JSONArray.class);
            assertThat(out.get("counts")).isInstanceOf(JSONObject.class);
        }

        @Test
        @DisplayName("an orphan row has exactly path/jcrPath/title/language/why, all String except why == JSON null")
        void orphanRow_keysAndTypes() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/lonely", SITE + "/lonely");

            JSONObject row = firstOrphan(LinkGraph.report(newAcc(), published, LANG, null, false, null));

            assertThat(row.keySet()).containsExactlyInAnyOrder("path", "jcrPath", "title", "language", "why");
            assertThat(row.get("path")).isInstanceOf(String.class);
            assertThat(row.get("jcrPath")).isInstanceOf(String.class);
            assertThat(row.get("title")).isInstanceOf(String.class);
            assertThat(row.get("language")).isInstanceOf(String.class);
            assertThat(row.get("why")).isSameAs(JSONObject.NULL);
        }

        @Test
        @DisplayName("a weak/sitemapOnly and a weak/navOnly row both carry why as a JSON String")
        void weakRow_whyIsAString() {
            Map<String, PublishedMap.Entry> sitemapOnlyPublished = newPublished();
            addPage(sitemapOnlyPublished, "/lonely", SITE + "/lonely");
            JSONObject sitemapOnlyRow = firstWeak(
                    LinkGraph.report(newAcc(), sitemapOnlyPublished, LANG, null, true, null));
            assertThat(sitemapOnlyRow.get("why")).isInstanceOf(String.class);
            assertThat(sitemapOnlyRow.getString("why")).isEqualTo("sitemapOnly");

            Map<String, PublishedMap.Entry> navOnlyPublished = newPublished();
            addPage(navOnlyPublished, "/target", SITE + "/target");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/from", "<nav><a href=\"/target\">x</a></nav>");
            JSONObject navOnlyRow = firstWeak(LinkGraph.report(acc, navOnlyPublished, LANG, null, false, null));
            assertThat(navOnlyRow.get("why")).isInstanceOf(String.class);
            assertThat(navOnlyRow.getString("why")).isEqualTo("navOnly");
        }

        @Test
        @DisplayName("a counts entry has exactly nav/content/path, nav and content are Integer, path is String")
        void countsEntry_keysAndTypes() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/target", SITE + "/target");
            LinkGraph.Accumulator acc = newAcc();
            LinkGraph.addPage(acc, "/from", "<nav><a href=\"/target\">x</a></nav>");

            JSONObject out = LinkGraph.report(acc, published, LANG, null, false, null);
            JSONObject countsEntry = out.getJSONObject("counts").getJSONObject(SITE + "/target@" + LANG);

            assertThat(countsEntry.keySet()).containsExactlyInAnyOrder("nav", "content", "path");
            assertThat(countsEntry.get("nav")).isInstanceOf(Integer.class);
            assertThat(countsEntry.get("content")).isInstanceOf(Integer.class);
            assertThat(countsEntry.get("path")).isInstanceOf(String.class);
            assertThat(countsEntry.getInt("nav")).isEqualTo(1);
            assertThat(countsEntry.getInt("content")).isZero();
            assertThat(countsEntry.getString("path")).isEqualTo("/target");
        }

        @Test
        @DisplayName("the counts object key is jcrPath@language, not the public path")
        void countsKey_isJcrPathAtLanguage() {
            Map<String, PublishedMap.Entry> published = newPublished();
            addPage(published, "/public-path", SITE + "/jcr-path");

            JSONObject out = LinkGraph.report(newAcc(), published, LANG, null, false, null);

            assertThat(out.getJSONObject("counts").keySet()).containsExactly(SITE + "/jcr-path@" + LANG);
        }
    }
}
