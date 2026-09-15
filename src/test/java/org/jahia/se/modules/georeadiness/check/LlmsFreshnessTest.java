package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterisation tests for {@link LlmsFreshness#check}.
 *
 * These exist to freeze what check does TODAY, not to argue that it is right.
 * check is at cognitive complexity 19 and about to be decomposed. A real bug
 * was already found downstream of this exact method: GeoReport read "stale" as
 * a boolean and "outdated" as an array - the reverse of what this class
 * actually produces - so every report ever generated silently said the file
 * was never stale. That is only catchable by asserting the JSON *type* of each
 * key, not just its value, so every test below does both.
 *
 * sitePath used throughout: "/sites/mysite".
 */
class LlmsFreshnessTest {

    private static final String SITE = "/sites/mysite";

    // ---------------------------------------------------------------- helpers

    private static PublishedMap.Entry entry(String jcrPath) {
        return new PublishedMap.Entry(jcrPath, "Some Title", "2024-01-01", 0L, "jnt:page", false, true, "en");
    }

    private static Map<String, PublishedMap.Entry> published(String key, PublishedMap.Entry value) {
        Map<String, PublishedMap.Entry> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static String link(String title, String path) {
        return "- [" + title + "](" + path + ")\n";
    }

    /** Builds `count` distinct links, e.g. link(0)..link(count-1), all under distinct paths. */
    private static String links(int count, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(link("Title " + i, "/" + prefix + "-" + i + ".html"));
        }
        return sb.toString();
    }

    /** Cases where `served` is missing/blank; kept at top level since @Nested classes are non-static. */
    private static Stream<org.junit.jupiter.params.provider.Arguments> notPresentCases() {
        String someGenerated = link("Home", "/index.html");
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("served is null", null, someGenerated),
                org.junit.jupiter.params.provider.Arguments.of("served is empty string", "", someGenerated),
                org.junit.jupiter.params.provider.Arguments.of("served is whitespace only", "   \n\t", someGenerated)
        );
    }

    // ------------------------------------------------------- absent / empty inputs

    @Nested
    @DisplayName("served or generated missing short-circuits before any comparison")
    class AbsentOrEmptyInputs {

        @ParameterizedTest(name = "{0}")
        @MethodSource("org.jahia.se.modules.georeadiness.check.LlmsFreshnessTest#notPresentCases")
        @DisplayName("present is false and outdated is false, no other keys are emitted")
        void servedMissingOrBlank_reportsNotPresent(String label, String served, String generated) {
            JSONObject out = LlmsFreshness.check(served, generated, null, SITE);

            assertThat(out.get("present")).isInstanceOf(Boolean.class);
            assertThat(out.getBoolean("present")).isFalse();
            assertThat(out.get("outdated")).isInstanceOf(Boolean.class);
            assertThat(out.getBoolean("outdated")).isFalse();

            assertThat(out.has("listed")).isFalse();
            assertThat(out.has("current")).isFalse();
            assertThat(out.has("stale")).isFalse();
            assertThat(out.has("missing")).isFalse();
            assertThat(out.has("listedPaths")).isFalse();
        }

        @Test
        @DisplayName("served present but generated is null: present true, outdated false, no other keys")
        void generatedNull_reportsPresentButNotOutdated() {
            JSONObject out = LlmsFreshness.check(link("Home", "/index.html"), null, null, SITE);

            assertThat(out.get("present")).isInstanceOf(Boolean.class);
            assertThat(out.getBoolean("present")).isTrue();
            assertThat(out.get("outdated")).isInstanceOf(Boolean.class);
            assertThat(out.getBoolean("outdated")).isFalse();
            assertThat(out.has("listed")).isFalse();
            assertThat(out.has("stale")).isFalse();
        }

        @Test
        @DisplayName("served present but generated is the empty string: present true, outdated false, no other keys")
        void generatedEmptyString_reportsPresentButNotOutdated() {
            JSONObject out = LlmsFreshness.check(link("Home", "/index.html"), "", null, SITE);

            assertThat(out.getBoolean("present")).isTrue();
            assertThat(out.getBoolean("outdated")).isFalse();
            assertThat(out.has("listed")).isFalse();
        }

        /**
         * SUSPECT (LlmsFreshness.java:58): the empty-generated guard is
         * `generated == null || generated.isEmpty()` - it does NOT trim, unlike the
         * `served` check three lines above which uses `served.trim().isEmpty()`. A
         * generated value that is whitespace-only (e.g. a single space) is therefore
         * treated as "present" content and falls through to full comparison, where
         * the regex simply finds zero links in it. The practical effect: every
         * link in `served` is then reported "stale" with why="gone", because
         * `current` silently becomes an empty map instead of the method reporting
         * "not outdated" the way it does for a null/empty generated value. This
         * test pins that inconsistency exactly as it stands today.
         */
        @Test
        @DisplayName("SUSPECT: whitespace-only generated is NOT treated as absent, unlike whitespace-only served")
        void generatedWhitespaceOnly_isTreatedAsPresentContentWithZeroLinks() {
            String served = link("Home", "/index.html");
            JSONObject out = LlmsFreshness.check(served, "   ", null, SITE);

            assertThat(out.getBoolean("present")).isTrue();
            assertThat(out.get("current")).isInstanceOf(Integer.class);
            assertThat(out.getInt("current")).isZero();
            assertThat(out.get("listed")).isInstanceOf(Integer.class);
            assertThat(out.getInt("listed")).isEqualTo(1);

            assertThat(out.get("stale")).isInstanceOf(JSONArray.class);
            JSONArray stale = out.getJSONArray("stale");
            assertThat(stale.length()).isEqualTo(1);
            JSONObject staleEntry = stale.getJSONObject(0);
            assertThat(staleEntry.getString("path")).isEqualTo("/index.html");
            assertThat(staleEntry.getString("why")).isEqualTo("gone");

            assertThat(out.get("outdated")).isInstanceOf(Boolean.class);
            assertThat(out.getBoolean("outdated")).isTrue();
        }
    }

    // ------------------------------------------------------------------ fresh case

    @Nested
    @DisplayName("served identical to what regenerating would produce")
    class FreshCase {

        @Test
        @DisplayName("same links in both: not outdated, empty stale/missing arrays, matching counts")
        void identicalContent_isNotOutdated() {
            String body = link("Home", "/index.html") + link("About", "/about.html");

            JSONObject out = LlmsFreshness.check(body, body, null, SITE);

            assertThat(out.get("present")).isInstanceOf(Boolean.class);
            assertThat(out.getBoolean("present")).isTrue();

            assertThat(out.get("listed")).isInstanceOf(Integer.class);
            assertThat(out.getInt("listed")).isEqualTo(2);
            assertThat(out.get("current")).isInstanceOf(Integer.class);
            assertThat(out.getInt("current")).isEqualTo(2);

            assertThat(out.get("stale")).isInstanceOf(JSONArray.class);
            assertThat(out.getJSONArray("stale").length()).isZero();
            assertThat(out.get("missing")).isInstanceOf(JSONArray.class);
            assertThat(out.getJSONArray("missing").length()).isZero();

            assertThat(out.get("listedPaths")).isInstanceOf(JSONArray.class);
            JSONArray listedPaths = out.getJSONArray("listedPaths");
            assertThat(listedPaths.length()).isEqualTo(2);
            assertThat(listedPaths.getString(0)).isEqualTo("/index.html");
            assertThat(listedPaths.getString(1)).isEqualTo("/about.html");

            assertThat(out.get("outdated")).isInstanceOf(Boolean.class);
            assertThat(out.getBoolean("outdated")).isFalse();
        }
    }

    // --------------------------------------------------- whitespace / line endings

    @Nested
    @DisplayName("formatting differences between served and generated")
    class WhitespaceAndLineEndings {

        @Test
        @DisplayName("CRLF line endings and extra indentation/trailing text do not create false staleness")
        void crlfAndIndentationDifferences_areNotReportedAsStale() {
            String served = "  -   [Home](/index.html)  trailing text\r\n"
                    + "  -  [About](/about.html)\r\n";
            String generated = "- [Home](/index.html) different trailing text\n"
                    + "- [About](/about.html)\n";

            JSONObject out = LlmsFreshness.check(served, generated, null, SITE);

            assertThat(out.getInt("listed")).isEqualTo(2);
            assertThat(out.getInt("current")).isEqualTo(2);
            assertThat(out.getJSONArray("stale").length()).isZero();
            assertThat(out.getJSONArray("missing").length()).isZero();
            assertThat(out.getBoolean("outdated")).isFalse();
        }

        @Test
        @DisplayName("only the URL path is compared: differing host/scheme is normalised away")
        void differentHostSameLinkPath_isNotReportedAsStale() {
            String served = link("Home", "https://old-domain.example/index.html");
            String generated = link("Home", "https://new-domain.example/index.html");

            JSONObject out = LlmsFreshness.check(served, generated, null, SITE);

            assertThat(out.getJSONArray("stale").length()).isZero();
            assertThat(out.getJSONArray("missing").length()).isZero();
            assertThat(out.getBoolean("outdated")).isFalse();
            assertThat(out.getJSONArray("listedPaths").getString(0)).isEqualTo("/index.html");
        }
    }

    // ------------------------------------------------------------------ stale pages

    @Nested
    @DisplayName("pages listed in served that the generator would no longer produce")
    class StalePages {

        @Test
        @DisplayName("stale path that is a direct key in published: why is noLongerListed")
        void stalePathStillPublished_reportsNoLongerListed() {
            String served = link("Old Page", "/old-page.html");
            // generated must not be null/empty to reach the comparison branch,
            // so give it at least one unrelated link.
            String generated = link("Home", "/index.html");

            Map<String, PublishedMap.Entry> pub = published("/old-page.html", entry(SITE + "/old-page"));

            JSONObject out = LlmsFreshness.check(served, generated, pub, SITE);

            JSONArray stale = out.getJSONArray("stale");
            assertThat(stale.length()).isEqualTo(1);
            JSONObject e = stale.getJSONObject(0);
            assertThat(e.get("path")).isInstanceOf(String.class);
            assertThat(e.getString("path")).isEqualTo("/old-page.html");
            assertThat(e.getString("title")).isEqualTo("Old Page");
            assertThat(e.getString("why")).isEqualTo("noLongerListed");
        }

        @Test
        @DisplayName("stale path not in published directly, but its node is published at a vanity url: why is addressChanged")
        void stalePathMovedToVanityUrl_reportsAddressChanged() {
            String served = link("Old Page", "/en/old-page.html");
            String generated = link("Home", "/index.html");

            // The node behind /en/old-page.html is now published as /en/new-page.html,
            // i.e. its jcrPath resolves from the old address once .html and the
            // language prefix are stripped.
            Map<String, PublishedMap.Entry> pub =
                    published("/en/new-page.html", entry(SITE + "/old-page"));

            JSONObject out = LlmsFreshness.check(served, generated, pub, SITE);

            JSONArray stale = out.getJSONArray("stale");
            assertThat(stale.length()).isEqualTo(1);
            assertThat(stale.getJSONObject(0).getString("why")).isEqualTo("addressChanged");
        }

        @Test
        @DisplayName("stale path with no published trace at all: why is gone")
        void stalePathNotPublishedAnywhere_reportsGone() {
            String served = link("Ghost Page", "/ghost.html");
            String generated = link("Home", "/index.html");

            JSONObject out = LlmsFreshness.check(served, generated, new LinkedHashMap<>(), SITE);

            JSONArray stale = out.getJSONArray("stale");
            assertThat(stale.length()).isEqualTo(1);
            assertThat(stale.getJSONObject(0).getString("why")).isEqualTo("gone");
        }

        @Test
        @DisplayName("null published map: stale reason falls back to gone, no NPE")
        void nullPublishedMap_reportsGoneWithoutError() {
            String served = link("Ghost Page", "/ghost.html");
            String generated = link("Home", "/index.html");

            JSONObject out = LlmsFreshness.check(served, generated, null, SITE);

            JSONArray stale = out.getJSONArray("stale");
            assertThat(stale.length()).isEqualTo(1);
            assertThat(stale.getJSONObject(0).getString("why")).isEqualTo("gone");
        }

        @Test
        @DisplayName("empty published map behaves the same as null for the gone reason")
        void emptyPublishedMap_reportsGone() {
            String served = link("Ghost Page", "/ghost.html");
            String generated = link("Home", "/index.html");

            JSONObject out = LlmsFreshness.check(served, generated, new LinkedHashMap<>(), SITE);

            assertThat(out.getJSONArray("stale").getJSONObject(0).getString("why")).isEqualTo("gone");
        }
    }

    // ---------------------------------------------------------------- missing pages

    @Nested
    @DisplayName("published pages missing from served")
    class MissingPages {

        @Test
        @DisplayName("a link only the generator would produce is reported under missing, not stale")
        void generatorOnlyLink_isReportedAsMissing() {
            String served = link("Home", "/index.html");
            String generated = link("Home", "/index.html") + link("New Page", "/new-page.html");

            JSONObject out = LlmsFreshness.check(served, generated, null, SITE);

            assertThat(out.getJSONArray("stale").length()).isZero();
            JSONArray missing = out.getJSONArray("missing");
            assertThat(missing.length()).isEqualTo(1);
            JSONObject m = missing.getJSONObject(0);
            assertThat(m.get("path")).isInstanceOf(String.class);
            assertThat(m.getString("path")).isEqualTo("/new-page.html");
            assertThat(m.getString("title")).isEqualTo("New Page");
            assertThat(out.getBoolean("outdated")).isTrue();
        }

        /**
         * SUSPECT (LlmsFreshness.java:97-104): the `missing` computation never
         * reads `published` at all - it is a pure diff between `listed` and
         * `current`. A page that is genuinely unpublished (absent from
         * `published`) but still emitted by the generator today would still show
         * up here as "missing" with no way to distinguish it from a legitimately
         * published-but-unlisted page. This test simply pins that `published` is
         * not consulted for this array.
         */
        @Test
        @DisplayName("SUSPECT: missing is computed without consulting published at all")
        void missingComputation_ignoresPublishedMap() {
            String served = link("Home", "/index.html");
            String generated = link("Home", "/index.html") + link("Untracked", "/untracked.html");

            // published map does not even contain the untracked page's node.
            JSONObject out = LlmsFreshness.check(served, generated, new LinkedHashMap<>(), SITE);

            JSONArray missing = out.getJSONArray("missing");
            assertThat(missing.length()).isEqualTo(1);
            assertThat(missing.getJSONObject(0).getString("path")).isEqualTo("/untracked.html");
        }
    }

    // ------------------------------------------------------------------ empty published

    @Nested
    @DisplayName("empty published map overall")
    class EmptyPublishedMap {

        @Test
        @DisplayName("fresh content with an empty published map: still not outdated")
        void emptyPublishedMap_doesNotAffectFreshContent() {
            String body = link("Home", "/index.html");

            JSONObject out = LlmsFreshness.check(body, body, new LinkedHashMap<>(), SITE);

            assertThat(out.getBoolean("outdated")).isFalse();
            assertThat(out.getJSONArray("stale").length()).isZero();
            assertThat(out.getJSONArray("missing").length()).isZero();
        }
    }

    // ------------------------------------------------------------------ ordering & cap

    @Nested
    @DisplayName("ordering and the 100-item cap on stale/missing")
    class OrderingAndCap {

        @Test
        @DisplayName("101 stale links: stale array is capped at 100, keeping the first 100 in document order")
        void staleArray_isCappedAt100InDocumentOrder() {
            String served = links(101, "stale");
            String generated = link("Home", "/index.html"); // unrelated, keeps generated non-empty

            JSONObject out = LlmsFreshness.check(served, generated, null, SITE);

            JSONArray stale = out.getJSONArray("stale");
            assertThat(stale.length()).isEqualTo(100);
            assertThat(stale.getJSONObject(0).getString("path")).isEqualTo("/stale-0.html");
            assertThat(stale.getJSONObject(99).getString("path")).isEqualTo("/stale-99.html");
        }

        @Test
        @DisplayName("101 missing links: missing array is capped at 100, keeping the first 100 in document order")
        void missingArray_isCappedAt100InDocumentOrder() {
            String served = link("Home", "/index.html"); // unrelated, keeps served non-empty
            String generated = links(101, "missing");

            JSONObject out = LlmsFreshness.check(served, generated, null, SITE);

            JSONArray missing = out.getJSONArray("missing");
            assertThat(missing.length()).isEqualTo(100);
            assertThat(missing.getJSONObject(0).getString("path")).isEqualTo("/missing-0.html");
            assertThat(missing.getJSONObject(99).getString("path")).isEqualTo("/missing-99.html");
        }

        /**
         * SUSPECT (LlmsFreshness.java:109): `listedPaths` is built from the full
         * `listed` map with no cap at all, unlike `stale`/`missing` which are
         * capped at MAX_REPORTED (100). The class comment says this is "capped by
         * the generator's own link budget", i.e. it relies on the generator never
         * producing more than ~100 links in practice - it is not enforced here.
         * This test pins that 150 served links all surface in listedPaths
         * uncapped.
         */
        @Test
        @DisplayName("SUSPECT: listedPaths is not capped at 100, unlike stale/missing")
        void listedPaths_isNotCappedUnlikeStaleAndMissing() {
            String served = links(150, "page");

            JSONObject out = LlmsFreshness.check(served, served, null, SITE);

            assertThat(out.getInt("listed")).isEqualTo(150);
            JSONArray listedPaths = out.getJSONArray("listedPaths");
            assertThat(listedPaths.length()).isEqualTo(150);
            assertThat(listedPaths.getString(0)).isEqualTo("/page-0.html");
            assertThat(listedPaths.getString(149)).isEqualTo("/page-149.html");
        }
    }

    // ---------------------------------------------------------------- listingFor

    @Nested
    @DisplayName("listingFor: reading a report produced by check")
    class ListingFor {

        @Test
        @DisplayName("null report returns null")
        void nullReport_returnsNull() {
            assertThat(LlmsFreshness.listingFor(null, "/index.html")).isNull();
        }

        @Test
        @DisplayName("report where present is false returns null regardless of path")
        void reportNotPresent_returnsNull() {
            JSONObject report = LlmsFreshness.check(null, null, null, SITE);
            assertThat(LlmsFreshness.listingFor(report, "/index.html")).isNull();
        }

        @Test
        @DisplayName("null path returns null even for a present report")
        void nullPath_returnsNull() {
            String body = link("Home", "/index.html");
            JSONObject report = LlmsFreshness.check(body, body, null, SITE);
            assertThat(LlmsFreshness.listingFor(report, null)).isNull();
        }

        @Test
        @DisplayName("a listed path: listed=true, no wouldAdd key")
        void listedPath_reportsListedTrue() {
            String body = link("Home", "/index.html");
            JSONObject report = LlmsFreshness.check(body, body, null, SITE);

            JSONObject result = LlmsFreshness.listingFor(report, "/index.html");
            assertThat(result).isNotNull();
            assertThat(result.get("listed")).isInstanceOf(Boolean.class);
            assertThat(result.getBoolean("listed")).isTrue();
            assertThat(result.has("wouldAdd")).isFalse();
        }

        @Test
        @DisplayName("a path the generator would add: listed=false, wouldAdd=true")
        void missingPath_reportsWouldAddTrue() {
            String served = link("Home", "/index.html");
            String generated = link("Home", "/index.html") + link("New", "/new.html");
            JSONObject report = LlmsFreshness.check(served, generated, null, SITE);

            JSONObject result = LlmsFreshness.listingFor(report, "/new.html");
            assertThat(result).isNotNull();
            assertThat(result.get("listed")).isInstanceOf(Boolean.class);
            assertThat(result.getBoolean("listed")).isFalse();
            assertThat(result.get("wouldAdd")).isInstanceOf(Boolean.class);
            assertThat(result.getBoolean("wouldAdd")).isTrue();
        }

        @Test
        @DisplayName("a path neither listed nor added: listed=false, wouldAdd=false")
        void unrelatedPath_reportsNeitherListedNorWouldAdd() {
            String body = link("Home", "/index.html");
            JSONObject report = LlmsFreshness.check(body, body, null, SITE);

            JSONObject result = LlmsFreshness.listingFor(report, "/somewhere-else.html");
            assertThat(result).isNotNull();
            assertThat(result.getBoolean("listed")).isFalse();
            assertThat(result.getBoolean("wouldAdd")).isFalse();
        }
    }
}
