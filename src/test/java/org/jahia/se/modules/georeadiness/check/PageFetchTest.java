package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterisation tests for {@link PageFetch#analyse(String)}.
 *
 * These freeze what analyse does TODAY, not what it should do. analyse sits at
 * cognitive complexity 26 and is about to be decomposed; every field it emits
 * (title, h1Count, h2Count, images, imagesWithAlt, metaDescription, canonical,
 * canonicalHref, lang, jsonLd, jsonLdTypes, dateModified, metaRefresh, words,
 * ...) is read directly by a named GeoScore check. A refactor that silently
 * changes a field's presence, type, or value moves a customer's score without
 * anyone noticing. If a test below pins behaviour that looks wrong, the
 * comment says "SUSPECT" - the test still passes, it is not "fixed".
 */
class PageFetchTest {

    // ------------------------------------------------------------- title

    @Nested
    @DisplayName("title")
    class TitleTests {

        @Test
        @DisplayName("present -> String, stripped and trimmed")
        void present() {
            JSONObject o = PageFetch.analyse("<html><head><title>Hello World</title></head></html>");
            assertThat(o.get("title")).isInstanceOf(String.class);
            assertThat(o.getString("title")).isEqualTo("Hello World");
        }

        @Test
        @DisplayName("absent -> JSON null")
        void absent() {
            JSONObject o = PageFetch.analyse("<html><head></head></html>");
            assertThat(o.isNull("title")).isTrue();
        }

        @Test
        @DisplayName("empty <title></title> -> empty String, not JSON null")
        void empty() {
            JSONObject o = PageFetch.analyse("<title></title>");
            assertThat(o.isNull("title")).isFalse();
            assertThat(o.get("title")).isInstanceOf(String.class);
            assertThat(o.getString("title")).isEmpty();
        }

        @Test
        @DisplayName("whitespace-only title collapses to empty String")
        void whitespaceOnly() {
            JSONObject o = PageFetch.analyse("<title>   \n\t  </title>");
            assertThat(o.get("title")).isInstanceOf(String.class);
            assertThat(o.getString("title")).isEmpty();
        }

        @Test
        @DisplayName("very long title is clipped to 120 chars, after stripping")
        void veryLong() {
            String longTitle = "a".repeat(200);
            JSONObject o = PageFetch.analyse("<title>" + longTitle + "</title>");
            assertThat(o.getString("title")).hasSize(120);
            assertThat(o.getString("title")).isEqualTo("a".repeat(120));
        }

        @Test
        @DisplayName("nested tags inside title are stripped from the text, with no space inserted at the tag boundary")
        void nestedTags() {
            // strip() removes tags via a plain replaceAll(""), so "<b>" and "</b>"
            // disappear with no separating space left behind.
            JSONObject o = PageFetch.analyse("<title>Hello <b>World</b>!</title>");
            assertThat(o.getString("title")).isEqualTo("Hello World!");
        }

        @Test
        @DisplayName("multiple title tags: only the first is used")
        void multipleTitles() {
            JSONObject o = PageFetch.analyse("<title>First</title><title>Second</title>");
            assertThat(o.getString("title")).isEqualTo("First");
        }

        @Test
        @DisplayName("title tag matching is case-insensitive")
        void caseInsensitiveTag() {
            JSONObject o = PageFetch.analyse("<TITLE>Shouty</TITLE>");
            assertThat(o.getString("title")).isEqualTo("Shouty");
        }
    }

    // ------------------------------------------------------------- headings

    @Nested
    @DisplayName("h1 / h1Count")
    class H1Tests {

        @Test
        @DisplayName("zero h1 -> h1Count 0 (int), h1 JSON null")
        void zero() {
            JSONObject o = PageFetch.analyse("<html><body><p>no headings</p></body></html>");
            assertThat(o.get("h1Count")).isInstanceOf(Integer.class);
            assertThat(o.getInt("h1Count")).isZero();
            assertThat(o.isNull("h1")).isTrue();
        }

        @Test
        @DisplayName("one h1 -> h1Count 1, h1 holds the stripped text")
        void one() {
            JSONObject o = PageFetch.analyse("<h1>Main Heading</h1>");
            assertThat(o.getInt("h1Count")).isEqualTo(1);
            assertThat(o.get("h1")).isInstanceOf(String.class);
            assertThat(o.getString("h1")).isEqualTo("Main Heading");
        }

        @Test
        @DisplayName("many h1 -> h1Count counts all, h1 field only holds the first")
        void many() {
            JSONObject o = PageFetch.analyse("<h1>One</h1><p>x</p><h1>Two</h1><h1>Three</h1>");
            assertThat(o.getInt("h1Count")).isEqualTo(3);
            assertThat(o.getString("h1")).isEqualTo("One");
        }

        @Test
        @DisplayName("attributes on the h1 tag do not prevent matching")
        void withAttributes() {
            JSONObject o = PageFetch.analyse("<h1 class=\"hero\" id=\"top\">Attributed</h1>");
            assertThat(o.getInt("h1Count")).isEqualTo(1);
            assertThat(o.getString("h1")).isEqualTo("Attributed");
        }

        @Test
        @DisplayName("uppercase/mixed-case H1 tag still matches (case-insensitive regex)")
        void mixedCase() {
            JSONObject o = PageFetch.analyse("<H1>Yell</H1><h1>calm</h1>");
            assertThat(o.getInt("h1Count")).isEqualTo(2);
            assertThat(o.getString("h1")).isEqualTo("Yell");
        }

        // SUSPECT: PageFetch.java:56 - H1 = <h1[^>]*>(.*?)</h1> requires a
        // literal closing </h1>. A self-closing "<h1/>" with no matching close
        // tag is never counted at all, so a malformed/self-closed h1 silently
        // scores the same as "no h1" instead of flagging a heading problem.
        @Test
        @DisplayName("SUSPECT: self-closing <h1/> with no closing tag is not counted")
        void selfClosingNotCounted() {
            JSONObject o = PageFetch.analyse("<h1/><p>body</p>");
            assertThat(o.getInt("h1Count")).isZero();
            assertThat(o.isNull("h1")).isTrue();
        }
    }

    @Nested
    @DisplayName("h2Count")
    class H2Tests {

        @Test
        @DisplayName("zero h2 -> 0 (int)")
        void zero() {
            JSONObject o = PageFetch.analyse("<p>none</p>");
            assertThat(o.get("h2Count")).isInstanceOf(Integer.class);
            assertThat(o.getInt("h2Count")).isZero();
        }

        @Test
        @DisplayName("one h2 -> 1")
        void one() {
            JSONObject o = PageFetch.analyse("<h2>Sub</h2>");
            assertThat(o.getInt("h2Count")).isEqualTo(1);
        }

        @Test
        @DisplayName("many h2, including with attributes and mixed case, all counted")
        void many() {
            JSONObject o = PageFetch.analyse("<h2>A</h2><H2 class=\"x\">B</H2><h2 id=\"y\">C</h2>");
            assertThat(o.getInt("h2Count")).isEqualTo(3);
        }

        // WAS SUSPECT, FIXED by the Markup walker. The old pattern required the
        // character after "h2" to be whitespace or '>', so a self-closing tag -
        // where it is '/' - was silently not counted. Markup tokenises the tag
        // rather than matching around it, so the name ends where it ends.
        @Test
        @DisplayName("a self-closing heading is counted")
        void selfClosingIsCounted() {
            assertThat(PageFetch.analyse("<h2/>").getInt("h2Count")).isEqualTo(1);
        }

        @Test
        @DisplayName("a tag like <h2foo> does not count as an h2 (no separator after h2)")
        void notFollowedBySeparator() {
            JSONObject o = PageFetch.analyse("<h2foo>text</h2foo>");
            assertThat(o.getInt("h2Count")).isZero();
        }
    }

    // ------------------------------------------------------------- metaDescription

    @Nested
    @DisplayName("metaDescription")
    class MetaDescriptionTests {

        @Test
        @DisplayName("present -> boolean true")
        void present() {
            JSONObject o = PageFetch.analyse("<meta name=\"description\" content=\"a page\">");
            assertThat(o.get("metaDescription")).isInstanceOf(Boolean.class);
            assertThat(o.getBoolean("metaDescription")).isTrue();
        }

        @Test
        @DisplayName("absent -> boolean false")
        void absent() {
            JSONObject o = PageFetch.analyse("<meta name=\"viewport\" content=\"width=device-width\">");
            assertThat(o.getBoolean("metaDescription")).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            // presence only: the content is never inspected
            "<meta name=\"description\" content=\"\">",
            // attribute order is not fixed
            "<meta content=\"a page\" name=\"description\">",
            // either quoting style
            "<meta name='description' content='a page'>"
        })
        @DisplayName("any well-formed description meta counts as present")
        void variantsAllCountAsPresent(String html) {
            assertThat(PageFetch.analyse(html).getBoolean("metaDescription")).isTrue();
        }
    }

    // ------------------------------------------------------------- canonical

    @Nested
    @DisplayName("canonical / canonicalHref")
    class CanonicalTests {

        @Test
        @DisplayName("present -> canonical boolean true, canonicalHref String")
        void present() {
            JSONObject o = PageFetch.analyse("<link rel=\"canonical\" href=\"https://example.com/page\">");
            assertThat(o.get("canonical")).isInstanceOf(Boolean.class);
            assertThat(o.getBoolean("canonical")).isTrue();
            assertThat(o.get("canonicalHref")).isInstanceOf(String.class);
            assertThat(o.getString("canonicalHref")).isEqualTo("https://example.com/page");
        }

        @Test
        @DisplayName("absent -> canonical false, canonicalHref key not present at all")
        void absent() {
            JSONObject o = PageFetch.analyse("<p>no link tag</p>");
            assertThat(o.getBoolean("canonical")).isFalse();
            assertThat(o.has("canonicalHref")).isFalse();
        }

        @Test
        @DisplayName("empty href -> canonical true, canonicalHref present as empty String")
        void emptyHref() {
            JSONObject o = PageFetch.analyse("<link rel=\"canonical\" href=\"\">");
            assertThat(o.getBoolean("canonical")).isTrue();
            assertThat(o.getString("canonicalHref")).isEmpty();
        }

        // SUSPECT: PageFetch.java:38-40,142-147 - HREF is applied to the whole
        // matched <link ...> tag, so href is picked up regardless of which
        // side of rel="canonical" it sits on; this test pins the "href before
        // rel" ordering to make sure a future change does not accidentally
        // stop supporting it.
        @Test
        @DisplayName("href attribute before rel attribute is still captured")
        void hrefBeforeRel() {
            JSONObject o = PageFetch.analyse("<link href=\"https://example.com/canon\" rel=\"canonical\">");
            assertThat(o.getBoolean("canonical")).isTrue();
            assertThat(o.getString("canonicalHref")).isEqualTo("https://example.com/canon");
        }

        @Test
        @DisplayName("no href attribute at all on the canonical link -> canonical true but canonicalHref absent")
        void noHrefAttribute() {
            JSONObject o = PageFetch.analyse("<link rel=\"canonical\">");
            assertThat(o.getBoolean("canonical")).isTrue();
            assertThat(o.has("canonicalHref")).isFalse();
        }
    }

    // ------------------------------------------------------------- metaRefresh

    @Nested
    @DisplayName("metaRefresh")
    class MetaRefreshTests {

        @Test
        @DisplayName("present -> String holding the url target")
        void present() {
            JSONObject o = PageFetch.analyse(
                    "<meta http-equiv=\"refresh\" content=\"5;url=https://example.com/next\">");
            assertThat(o.get("metaRefresh")).isInstanceOf(String.class);
            assertThat(o.getString("metaRefresh")).isEqualTo("https://example.com/next");
        }

        @Test
        @DisplayName("absent -> JSON null")
        void absent() {
            JSONObject o = PageFetch.analyse("<p>nothing here</p>");
            assertThat(o.isNull("metaRefresh")).isTrue();
        }

        @Test
        @DisplayName("refresh without a url= target -> JSON null (pattern requires url=)")
        void noUrlTarget() {
            JSONObject o = PageFetch.analyse("<meta http-equiv=\"refresh\" content=\"5\">");
            assertThat(o.isNull("metaRefresh")).isTrue();
        }

        @Test
        @DisplayName("very long refresh target is clipped to 300 chars")
        void veryLongClipped() {
            String longUrl = "https://example.com/" + "a".repeat(400);
            JSONObject o = PageFetch.analyse(
                    "<meta http-equiv=\"refresh\" content=\"0;url=" + longUrl + "\">");
            assertThat(o.getString("metaRefresh")).hasSize(300);
        }
    }

    // ------------------------------------------------------------- lang

    @Nested
    @DisplayName("lang")
    class LangTests {

        @Test
        @DisplayName("present on <html> -> trimmed String")
        void present() {
            JSONObject o = PageFetch.analyse("<html lang=\"en-US\"><body></body></html>");
            assertThat(o.get("lang")).isInstanceOf(String.class);
            assertThat(o.getString("lang")).isEqualTo("en-US");
        }

        @Test
        @DisplayName("absent -> JSON null")
        void absent() {
            JSONObject o = PageFetch.analyse("<html><body></body></html>");
            assertThat(o.isNull("lang")).isTrue();
        }

        // SUSPECT: PageFetch.java:45 - HTML_LANG captures the attribute value
        // with [^"']+ (one or more chars required), unlike e.g. IMG_ALT which
        // uses [^"']* (zero or more). An explicitly empty lang="" therefore
        // does NOT match the pattern at all, so it is indistinguishable from
        // "no lang attribute" - both come out as JSON null - even though an
        // empty lang is arguably worse (declared but not filled in).
        @Test
        @DisplayName("SUSPECT: empty lang=\"\" does not match the pattern (+ requires 1+ chars) -> JSON null, same as absent")
        void empty() {
            JSONObject o = PageFetch.analyse("<html lang=\"\"><body></body></html>");
            assertThat(o.isNull("lang")).isTrue();
        }

        @Test
        @DisplayName("unusual attribute order (lang before other attrs) still matches")
        void unusualOrder() {
            JSONObject o = PageFetch.analyse("<html lang=\"fr\" data-x=\"1\"><body></body></html>");
            assertThat(o.getString("lang")).isEqualTo("fr");
        }
    }

    // ------------------------------------------------------------- images

    @Nested
    @DisplayName("images / imagesWithAlt")
    class ImageTests {

        @Test
        @DisplayName("img with non-empty alt counts toward both images and imagesWithAlt")
        void withAlt() {
            JSONObject o = PageFetch.analyse("<img src=\"a.jpg\" alt=\"A cat\">");
            assertThat(o.get("images")).isInstanceOf(Integer.class);
            assertThat(o.getInt("images")).isEqualTo(1);
            assertThat(o.get("imagesWithAlt")).isInstanceOf(Integer.class);
            assertThat(o.getInt("imagesWithAlt")).isEqualTo(1);
        }

        @Test
        @DisplayName("img without any alt attribute counts toward images but not imagesWithAlt")
        void withoutAlt() {
            JSONObject o = PageFetch.analyse("<img src=\"a.jpg\">");
            assertThat(o.getInt("images")).isEqualTo(1);
            assertThat(o.getInt("imagesWithAlt")).isZero();
        }

        // Pinning the accessibility-relevant case explicitly: an explicitly
        // empty alt (alt="") is treated as NOT "with alt" by this code, i.e.
        // decorative-image style empty alt does not count as descriptive text.
        @Test
        @DisplayName("img with empty alt=\"\" does NOT count as imagesWithAlt")
        void emptyAltDoesNotCount() {
            JSONObject o = PageFetch.analyse("<img src=\"a.jpg\" alt=\"\">");
            assertThat(o.getInt("images")).isEqualTo(1);
            assertThat(o.getInt("imagesWithAlt")).isZero();
        }

        @Test
        @DisplayName("img with whitespace-only alt does NOT count as imagesWithAlt")
        void whitespaceOnlyAltDoesNotCount() {
            JSONObject o = PageFetch.analyse("<img src=\"a.jpg\" alt=\"   \">");
            assertThat(o.getInt("imagesWithAlt")).isZero();
        }

        @Test
        @DisplayName("several images, mixed alt presence, are all counted correctly")
        void several() {
            JSONObject o = PageFetch.analyse(
                    "<img src=\"a.jpg\" alt=\"cat\"><img src=\"b.jpg\"><img src=\"c.jpg\" alt=\"\">"
                            + "<img src=\"d.jpg\" alt=\"dog\">");
            assertThat(o.getInt("images")).isEqualTo(4);
            assertThat(o.getInt("imagesWithAlt")).isEqualTo(2);
        }

        @Test
        @DisplayName("no images at all -> both counters 0")
        void none() {
            JSONObject o = PageFetch.analyse("<p>no pictures</p>");
            assertThat(o.getInt("images")).isZero();
            assertThat(o.getInt("imagesWithAlt")).isZero();
        }

        // WAS SUSPECT, FIXED by the Markup walker. The old pattern required
        // whitespace straight after "img", so an image with no attributes at all
        // was excluded from BOTH counts rather than counted as one lacking alt
        // text - which is the accessibility finding the check exists to make.
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"<img>", "<img/>"})
        @DisplayName("an image with no attributes is still an image, and still has no alt")
        void attributelessImageIsCounted(String html) {
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getInt("images")).isEqualTo(1);
            assertThat(o.getInt("imagesWithAlt")).isZero();
        }

        @Test
        @DisplayName("self-closing image WITH a space before the slash is counted")
        void selfClosingWithSpaceIsCounted() {
            JSONObject o = PageFetch.analyse("<img src=\"a.jpg\" alt=\"cat\" />");
            assertThat(o.getInt("images")).isEqualTo(1);
            assertThat(o.getInt("imagesWithAlt")).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------- JSON-LD

    @Nested
    @DisplayName("jsonLd / jsonLdTypes")
    class JsonLdTests {

        @Test
        @DisplayName("one ld+json script block with one @type -> jsonLd 1 (int), jsonLdTypes array of size 1")
        void oneBlock() {
            String html = "<script type=\"application/ld+json\">{\"@type\":\"Article\"}</script>";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.get("jsonLd")).isInstanceOf(Integer.class);
            assertThat(o.getInt("jsonLd")).isEqualTo(1);
            assertThat(o.get("jsonLdTypes")).isInstanceOf(JSONArray.class);
            JSONArray types = o.getJSONArray("jsonLdTypes");
            assertThat(types.length()).isEqualTo(1);
            assertThat(types.getString(0)).isEqualTo("Article");
        }

        @Test
        @DisplayName("no ld+json at all -> jsonLd 0, jsonLdTypes empty array (not null)")
        void none() {
            JSONObject o = PageFetch.analyse("<p>plain page</p>");
            assertThat(o.getInt("jsonLd")).isZero();
            assertThat(o.getJSONArray("jsonLdTypes").length()).isZero();
        }

        @Test
        @DisplayName("several blocks with distinct types are all collected, in first-seen order")
        void severalBlocksDistinctTypes() {
            String html = "<script type=\"application/ld+json\">{\"@type\":\"Article\"}</script>"
                    + "<script type=\"application/ld+json\">{\"@type\":\"Organization\"}</script>";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getInt("jsonLd")).isEqualTo(2);
            JSONArray types = o.getJSONArray("jsonLdTypes");
            assertThat(types.length()).isEqualTo(2);
            assertThat(types.getString(0)).isEqualTo("Article");
            assertThat(types.getString(1)).isEqualTo("Organization");
        }

        @Test
        @DisplayName("duplicate @type across blocks is deduplicated")
        void duplicateTypeDeduplicated() {
            String html = "<script type=\"application/ld+json\">{\"@type\":\"Article\"}</script>"
                    + "<script type=\"application/ld+json\">{\"@type\":\"Article\"}</script>";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getInt("jsonLd")).isEqualTo(2);
            assertThat(o.getJSONArray("jsonLdTypes").length()).isEqualTo(1);
        }

        @Test
        @DisplayName("@graph array: every @type found anywhere in the block text is picked up")
        void graphStructure() {
            String html = "<script type=\"application/ld+json\">"
                    + "{\"@context\":\"https://schema.org\",\"@graph\":["
                    + "{\"@type\":\"WebPage\"},{\"@type\":\"BreadcrumbList\"}]}"
                    + "</script>";
            JSONObject o = PageFetch.analyse(html);
            JSONArray types = o.getJSONArray("jsonLdTypes");
            assertThat(types.length()).isEqualTo(2);
            assertThat(types.getString(0)).isEqualTo("WebPage");
            assertThat(types.getString(1)).isEqualTo("BreadcrumbList");
        }

        // WAS SUSPECT, NOW FIXED. The types used to come from a regex over the
        // block's raw text, so a block no consumer can read still reported its
        // types - and GeoScore's structuredData check PASSED a page whose
        // JSON-LD is broken. That is a false pass on the exact thing the check
        // exists to find. A crawler parses the block; if it does not parse, the
        // page has declared nothing, whatever the text looks like.
        @Test
        @DisplayName("a block that does not parse declares nothing, even if the text contains an @type")
        void malformedJson_declaresNothing() {
            String html = "<script type=\"application/ld+json\">{\"@type\":\"Article\", not valid json at all !! }</script>";

            JSONObject o = PageFetch.analyse(html);

            // The BLOCK is still counted - there is an ld+json script on the page.
            assertThat(o.getInt("jsonLd")).isEqualTo(1);
            // What it declares is nothing, because nothing can read it.
            assertThat(o.getJSONArray("jsonLdTypes").length()).isZero();
        }

        /** {@code {"@type":"Top","n":{"n":{ ... {"@type":"Bottom"} ... }}}} */
        private String nested(int depth) {
            StringBuilder b = new StringBuilder("{\"@type\":\"Top\",\"n\":");
            for (int i = 0; i < depth; i++) {
                b.append("{\"n\":");
            }
            b.append("{\"@type\":\"Bottom\"}");
            for (int i = 0; i < depth; i++) {
                b.append("}");
            }
            return b.append("}").toString();
        }

        @Test
        @DisplayName("the walk stops at its depth cap instead of following a document down")
        void deeplyNestedJsonLd_walkIsCapped() {
            String html = "<script type=\"application/ld+json\">" + nested(100) + "</script>";

            JSONArray types = PageFetch.analyse(html).getJSONArray("jsonLdTypes");

            // Top is at the root; Bottom sits past the cap and is never reached.
            assertThat(types.length()).isEqualTo(1);
            assertThat(types.getString(0)).isEqualTo("Top");
        }

        @Test
        @DisplayName("the cap is the only thing bounding the walk: org.json parses arbitrary nesting")
        void absurdlyNestedJsonLd_doesNotOverflow() {
            // Measured rather than assumed: org.json in this version parses 2000
            // levels without complaint, so it contributes no bound of its own.
            // The walk's own cap is what keeps a page the module does not control
            // from choosing how deep this recurses.
            String html = "<script type=\"application/ld+json\">" + nested(2000) + "</script>";

            JSONObject o = PageFetch.analyse(html);

            assertThat(o.getInt("jsonLd")).isEqualTo(1);
            assertThat(o.getJSONArray("jsonLdTypes").length()).isEqualTo(1);
            assertThat(o.getJSONArray("jsonLdTypes").getString(0)).isEqualTo("Top");
        }

        @Test
        @DisplayName("malformed JSON with no @type token at all -> no type extracted, still no throw")
        void malformedJsonNoTypeToken() {
            String html = "<script type=\"application/ld+json\">not json { [ } definitely broken</script>";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getInt("jsonLd")).isEqualTo(1);
            assertThat(o.getJSONArray("jsonLdTypes").length()).isZero();
        }

        @Test
        @DisplayName("a non-JSON-LD script tag does not add to jsonLd count or jsonLdTypes")
        void nonJsonLdScript() {
            String html = "<script>var x = {\"@type\":\"Article\"};</script>";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getInt("jsonLd")).isZero();
            assertThat(o.getJSONArray("jsonLdTypes").length()).isZero();
        }

        // SUSPECT: PageFetch.java:44,155 - the "jsonLd" count is produced by
        // JSONLD = the literal text "application/ld+json" matched ANYWHERE in
        // the whole html, not only inside <script> tags. A page that merely
        // mentions the string (e.g. in a code sample or comment) is counted as
        // having a structured-data block even though jsonLdTypes (which does
        // require a real <script> tag) would show none.
        @Test
        @DisplayName("SUSPECT: jsonLd count matches the literal string anywhere, not just inside a real script tag")
        void jsonLdCountMatchesLiteralStringOutsideScript() {
            String html = "<p>This page discusses application/ld+json as a format.</p>";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getInt("jsonLd")).isEqualTo(1);
            assertThat(o.getJSONArray("jsonLdTypes").length()).isZero();
        }
    }

    // ------------------------------------------------------------- dateModified

    @Nested
    @DisplayName("dateModified")
    class DateModifiedTests {

        @Test
        @DisplayName("present via ld+json dateModified field -> String")
        void presentViaJsonLd() {
            String html = "<script type=\"application/ld+json\">{\"dateModified\":\"2024-05-01T10:00:00Z\"}</script>";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.get("dateModified")).isInstanceOf(String.class);
            assertThat(o.getString("dateModified")).isEqualTo("2024-05-01T10:00:00Z");
        }

        @Test
        @DisplayName("present via article:modified_time meta tag -> String")
        void presentViaArticleModifiedTime() {
            String html = "<meta property=\"article:modified_time\" content=\"2024-06-01\">";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.get("dateModified")).isInstanceOf(String.class);
            assertThat(o.getString("dateModified")).isEqualTo("2024-06-01");
        }

        @Test
        @DisplayName("absent -> JSON null")
        void absent() {
            JSONObject o = PageFetch.analyse("<p>no dates here</p>");
            assertThat(o.isNull("dateModified")).isTrue();
        }

        @Test
        @DisplayName("very long value is clipped to 40 chars")
        void veryLongClipped() {
            String longDate = "2024-01-01T00:00:00Z" + "x".repeat(40);
            String html = "<script type=\"application/ld+json\">{\"dateModified\":\"" + longDate + "\"}</script>";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getString("dateModified")).hasSize(40);
        }
    }

    // ------------------------------------------------------------- word counting

    @Nested
    @DisplayName("words")
    class WordCountTests {

        @Test
        @DisplayName("simple text body -> words counted by splitting on single spaces after normalisation")
        void simpleText() {
            JSONObject o = PageFetch.analyse("<p>one two three</p>");
            assertThat(o.get("words")).isInstanceOf(Integer.class);
            assertThat(o.getInt("words")).isEqualTo(3);
        }

        @Test
        @DisplayName("empty html -> 0 words")
        void empty() {
            JSONObject o = PageFetch.analyse("");
            assertThat(o.getInt("words")).isZero();
        }

        @Test
        @DisplayName("only tags, no text -> 0 words")
        void onlyTags() {
            JSONObject o = PageFetch.analyse("<div><span></span></div>");
            assertThat(o.getInt("words")).isZero();
        }

        // SUSPECT: PageFetch.java:52,212 - SCRIPTS strips <script>/<style>/
        // <noscript>/<template> contents before word counting, so text inside
        // those tags is correctly excluded from "words". This test pins that
        // exclusion so a refactor cannot silently start counting script/style
        // text as page content.
        @Test
        @DisplayName("script and style content is excluded from the word count")
        void scriptAndStyleExcluded() {
            String html = "<style>.a{color:red}</style>"
                    + "<script>var hidden = 'lots of extra words here';</script>"
                    + "<p>real content here</p>";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getInt("words")).isEqualTo(3);
        }

        @Test
        @DisplayName("a plain HTML comment (no internal '>') is fully stripped, as a side effect of TAGS matching <...>")
        void plainCommentIsStripped() {
            // TAGS = <[^>]+> matches from "<" up to the first ">" it meets. A
            // comment with no internal '>' is consumed by that single match,
            // same as any other tag, so its text never reaches the word count.
            String html = "<p>real</p><!-- hidden comment words -->";
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getInt("words")).isEqualTo(1);
        }

        // SUSPECT: PageFetch.java:53 - TAGS = <[^>]+> stops at the FIRST '>'.
        // A comment containing an internal '>' before its closing "-->" (e.g.
        // an HTML snippet or a "a > b" comparison inside commented-out
        // boilerplate) only has its opening portion consumed as a "tag"; the
        // remainder of the comment, including the dangling "-->", leaks
        // through as ordinary page text and is counted as words.
        @Test
        @DisplayName("SUSPECT: a comment with an internal '>' partially leaks into the word count")
        void commentWithInternalGtLeaksText() {
            String html = "<p>real</p><!-- a > b -->";
            JSONObject o = PageFetch.analyse(html);
            // "<!-- a " up to the first '>' is stripped as a tag; " b -->" remains
            // as text, contributing the words "b" and "-->".
            assertThat(o.getInt("words")).isEqualTo(3);
        }

        @Test
        @DisplayName("HTML entities are not decoded; an entity is counted as part of a word verbatim")
        void entitiesNotDecoded() {
            JSONObject o = PageFetch.analyse("<p>Fish &amp; Chips</p>");
            assertThat(o.getInt("words")).isEqualTo(3);
            // the middle token is the literal, undecoded entity text
        }

        @Test
        @DisplayName("runs of whitespace collapse to single separators for word counting")
        void whitespaceRunsCollapse() {
            JSONObject o = PageFetch.analyse("<p>one    two\n\n\tthree</p>");
            assertThat(o.getInt("words")).isEqualTo(3);
        }

        @Test
        @DisplayName("non-Latin text with no spaces is counted as a single word")
        void nonLatinNoSpacesIsOneWord() {
            JSONObject o = PageFetch.analyse("<p>你好世界</p>");
            assertThat(o.getInt("words")).isEqualTo(1);
        }

        @Test
        @DisplayName("non-Latin text WITH spaces counts each space-separated run as a word")
        void nonLatinWithSpaces() {
            JSONObject o = PageFetch.analyse("<p>你好 世界</p>");
            assertThat(o.getInt("words")).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------- robustness

    @Nested
    @DisplayName("robustness")
    class RobustnessTests {

        // SUSPECT: PageFetch.java:123 - analyse(html) calls TITLE.matcher(html)
        // (and every other pattern) directly on the argument with no null
        // check. Matcher operations on a null CharSequence throw
        // NullPointerException, so analyse is NOT null-safe despite every
        // caller in this file passing a non-empty body (probe() only calls
        // analyse when `!html.isEmpty()`, which also guards against null
        // implicitly, but analyse itself has no such guard if called
        // directly, e.g. from a test or a future caller).
        @Test
        @DisplayName("SUSPECT: null input throws NullPointerException instead of returning a safe empty result")
        void nullInputThrows() {
            assertThatThrownBy(() -> PageFetch.analyse(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("empty string input never throws, returns all the 'absent' defaults")
        void emptyStringInput() {
            JSONObject o = PageFetch.analyse("");
            assertThat(o.isNull("title")).isTrue();
            assertThat(o.getInt("h1Count")).isZero();
            assertThat(o.isNull("h1")).isTrue();
            assertThat(o.getBoolean("metaDescription")).isFalse();
            assertThat(o.getBoolean("canonical")).isFalse();
            assertThat(o.has("canonicalHref")).isFalse();
            assertThat(o.isNull("metaRefresh")).isTrue();
            assertThat(o.getInt("links")).isZero();
            assertThat(o.getInt("jsonLd")).isZero();
            assertThat(o.isNull("lang")).isTrue();
            assertThat(o.getJSONArray("hreflang").length()).isZero();
            assertThat(o.getInt("h2Count")).isZero();
            assertThat(o.getInt("images")).isZero();
            assertThat(o.getInt("imagesWithAlt")).isZero();
            assertThat(o.getJSONArray("jsonLdTypes").length()).isZero();
            assertThat(o.isNull("dateModified")).isTrue();
            assertThat(o.getInt("words")).isZero();
        }

        @Test
        @DisplayName("a fragment that is not HTML at all is treated as plain text, never throws")
        void notHtmlAtAll() {
            JSONObject o = PageFetch.analyse("just some plain text, no markup whatsoever");
            assertThat(o.isNull("title")).isTrue();
            assertThat(o.getInt("words")).isEqualTo(7);
        }

        @Test
        @DisplayName("unclosed tags do not throw; unmatched constructs are simply not counted")
        void unclosedTags() {
            JSONObject o = PageFetch.analyse("<div><p>unclosed everything<h1>oops");
            // The heading never closes, so the h1 pattern does not match it and
            // the count stays at zero. The tag-stripping pass still removes
            // every angle-bracket run it can find, so all three words survive
            // as plain text.
            assertThat(o.getInt("h1Count")).isZero();
            assertThat(o.getInt("words")).isEqualTo(3);
        }

        @Test
        @DisplayName("a very large input (thousands of paragraphs) does not throw and counts correctly")
        void veryLargeInput() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5000; i++) {
                sb.append("<p>word").append(i).append(" more text here</p>");
            }
            JSONObject o = PageFetch.analyse(sb.toString());
            // each paragraph contributes 4 words: "word{i}", "more", "text", "here"
            assertThat(o.getInt("words")).isEqualTo(5000 * 4);
        }
    }

    // ------------------------------------------------------------- combined / table-driven

    @Nested
    @DisplayName("meta description presence, table-driven")
    class MetaDescriptionTableTests {

        @ParameterizedTest(name = "[{index}] {0} -> metaDescription={1}")
        @CsvSource({
            "'<meta name=\"description\" content=\"x\">', true",
            "'<meta name=\"Description\" content=\"x\">', true",
            "'<meta name=\"keywords\" content=\"x\">', false",
            "'<p>no meta at all</p>', false"
        })
        void metaDescriptionPresence(String html, boolean expected) {
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.getBoolean("metaDescription")).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("links count")
    class LinksTests {

        @ParameterizedTest
        @ValueSource(strings = {
            "<a href=\"/one\">One</a>",
            "<a class=\"x\" href=\"/one\">One</a>"
        })
        @DisplayName("a single anchor with an href is counted once")
        void singleAnchor(String html) {
            JSONObject o = PageFetch.analyse(html);
            assertThat(o.get("links")).isInstanceOf(Integer.class);
            assertThat(o.getInt("links")).isEqualTo(1);
        }

        @Test
        @DisplayName("no anchors -> 0")
        void none() {
            JSONObject o = PageFetch.analyse("<p>no links</p>");
            assertThat(o.getInt("links")).isZero();
        }

        @Test
        @DisplayName("several anchors are all counted")
        void several() {
            JSONObject o = PageFetch.analyse("<a href=\"/a\">a</a><a href=\"/b\">b</a><a href=\"/c\">c</a>");
            assertThat(o.getInt("links")).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("hreflang")
    class HreflangTests {

        @Test
        @DisplayName("present -> JSONArray of trimmed values")
        void present() {
            JSONObject o = PageFetch.analyse(
                    "<link rel=\"alternate\" hreflang=\"en\" href=\"/en\">"
                            + "<link rel=\"alternate\" hreflang=\"fr\" href=\"/fr\">");
            assertThat(o.get("hreflang")).isInstanceOf(JSONArray.class);
            JSONArray arr = o.getJSONArray("hreflang");
            assertThat(arr.length()).isEqualTo(2);
            assertThat(arr.getString(0)).isEqualTo("en");
            assertThat(arr.getString(1)).isEqualTo("fr");
        }

        @Test
        @DisplayName("absent -> empty JSONArray, not JSON null")
        void absent() {
            JSONObject o = PageFetch.analyse("<p>no alternates</p>");
            assertThat(o.get("hreflang")).isInstanceOf(JSONArray.class);
            assertThat(o.getJSONArray("hreflang").length()).isZero();
        }

        @Test
        @DisplayName("more than 20 hreflang entries are capped at 20")
        void cappedAt20() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 25; i++) {
                sb.append("<link rel=\"alternate\" hreflang=\"l").append(i).append("\" href=\"/l").append(i).append("\">");
            }
            JSONObject o = PageFetch.analyse(sb.toString());
            assertThat(o.getJSONArray("hreflang").length()).isEqualTo(20);
        }
    }
}
