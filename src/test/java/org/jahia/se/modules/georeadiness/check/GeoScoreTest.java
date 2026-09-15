package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterisation tests for the scoring engine.
 *
 * These exist to freeze what {@link GeoScore#compute} does TODAY, not to argue
 * that it is right. compute is about to be decomposed - Sonar scores it at a
 * cognitive complexity of 43 and calls it a Brain Method with 34 variables - and
 * both the drawer and the site-wide scan route through it. Without something
 * pinning the output, a refactor could change every site's score and the whole
 * suite would still pass: the Cypress tests assert that a run HAPPENED and that
 * the UI shows "{n} of {m} checks passed", never which n or which m.
 *
 * So each test below states an input and the exact verdict it produces. If one
 * fails after a change to compute, the change altered a score. That is the
 * entire point; decide deliberately whether the new answer is the wanted one and
 * update the test with a reason, rather than adjusting it to go green.
 */
class GeoScoreTest {

    /** The eighteen check ids, in the order compute emits them. */
    private static final String[] ALL_CHECKS = {
        "reachable", "policyMatchesReality", "noClientRedirect", "namedInRobots",
        "contentInInitialHtml", "sameContentForCrawlers", "title", "singleH1",
        "headingOutline", "metaDescription", "canonical", "langDeclared",
        "structuredData", "freshness", "imageAlt", "robotsPresent", "llmsPresent"
    };

    // ---------------------------------------------------------------- helpers

    /** A report with nothing in it: every optional read falls back. */
    private static JSONObject emptyReport() {
        return new JSONObject();
    }

    /** One agent that answered 200 with the given html block - compute's "control". */
    private static JSONObject withControl(JSONObject html) {
        JSONObject agent = new JSONObject();
        agent.put("status", 200);
        agent.put("html", html);
        JSONObject report = new JSONObject();
        report.put("agents", new JSONArray().put(agent));
        return report;
    }

    private static JSONObject check(JSONObject score, String id) {
        JSONArray checks = score.getJSONArray("checks");
        for (int i = 0; i < checks.length(); i++) {
            JSONObject c = checks.getJSONObject(i);
            if (id.equals(c.getString("id"))) {
                return c;
            }
        }
        throw new AssertionError("no check with id " + id);
    }

    private static boolean passed(JSONObject score, String id) {
        return check(score, id).getBoolean("passed");
    }

    // ------------------------------------------------------------ the shape

    @Nested
    @DisplayName("the shape of the result")
    class Shape {

        @Test
        @DisplayName("an empty report still produces all seventeen unconditional checks")
        void emptyReport_emitsSeventeenChecks() {
            JSONObject score = GeoScore.compute(emptyReport());

            assertThat(score.getInt("total")).isEqualTo(17);
            assertThat(score.getJSONArray("checks").length()).isEqualTo(17);
        }

        @Test
        @DisplayName("checks come out in a fixed order, which the UI relies on")
        void checkOrder_isStable() {
            JSONArray checks = GeoScore.compute(emptyReport()).getJSONArray("checks");

            String[] ids = new String[checks.length()];
            for (int i = 0; i < checks.length(); i++) {
                ids[i] = checks.getJSONObject(i).getString("id");
            }
            assertThat(ids).containsExactly(ALL_CHECKS);
        }

        @Test
        @DisplayName("every check carries id, group, severity, passed and value")
        void everyCheck_hasTheFullShape() {
            JSONArray checks = GeoScore.compute(emptyReport()).getJSONArray("checks");

            for (int i = 0; i < checks.length(); i++) {
                JSONObject c = checks.getJSONObject(i);
                assertThat(c.keySet())
                        .as("check %s", c.optString("id"))
                        .containsExactlyInAnyOrder("id", "group", "severity", "passed", "value");
                assertThat(c.getString("group")).isIn("access", "content", "files");
                assertThat(c.getString("severity")).isIn("critical", "important", "advisory");
            }
        }

        @ParameterizedTest(name = "{0} is {1}/{2}")
        @CsvSource({
            "reachable,             access,  critical",
            "policyMatchesReality,  access,  important",
            "noClientRedirect,      access,  critical",
            "namedInRobots,         access,  advisory",
            "contentInInitialHtml,  content, critical",
            "sameContentForCrawlers,content, critical",
            "title,                 content, important",
            "singleH1,              content, important",
            "headingOutline,        content, advisory",
            "metaDescription,       content, important",
            "canonical,             content, important",
            "langDeclared,          content, important",
            "structuredData,        content, important",
            "freshness,             content, advisory",
            "imageAlt,              content, advisory",
            "robotsPresent,         files,   important",
            "llmsPresent,           files,   advisory"
        })
        @DisplayName("each check keeps its group and severity")
        void groupAndSeverity_arePinned(String id, String group, String severity) {
            JSONObject c = check(GeoScore.compute(emptyReport()), id);

            assertThat(c.getString("group")).isEqualTo(group);
            assertThat(c.getString("severity")).isEqualTo(severity);
        }

        @Test
        @DisplayName("a value the report cannot supply is JSON null, never absent")
        void missingValue_isJsonNull() {
            JSONObject score = GeoScore.compute(emptyReport());

            assertThat(check(score, "metaDescription").isNull("value")).isTrue();
            assertThat(check(score, "imageAlt").isNull("value")).isTrue();
        }
    }

    // ------------------------------------------------- the conditional check

    @Nested
    @DisplayName("guestReadable, the one conditional check")
    class GuestReadable {

        @Test
        @DisplayName("is absent when the repository did not answer, so the total stays seventeen")
        void absentVisibility_omitsTheCheck() {
            JSONObject score = GeoScore.compute(emptyReport());

            assertThat(score.getInt("total")).isEqualTo(17);
            assertThat(score.getJSONArray("checks").toString()).doesNotContain("guestReadable");
        }

        @Test
        @DisplayName("appears only when visibility actually carries the key, taking the total to eighteen")
        void presentVisibility_addsTheCheck() {
            JSONObject report = emptyReport();
            report.put("visibility", new JSONObject().put("guestReadable", true));

            JSONObject score = GeoScore.compute(report);

            assertThat(score.getInt("total")).isEqualTo(18);
            assertThat(passed(score, "guestReadable")).isTrue();
        }

        @Test
        @DisplayName("a visibility object WITHOUT the key still omits the check")
        void visibilityWithoutTheKey_omitsTheCheck() {
            // has() not optBoolean(): "the repository could not say" must not read
            // as a failure, which a default would have made it.
            JSONObject report = emptyReport();
            report.put("visibility", new JSONObject().put("kind", "unknown"));

            assertThat(GeoScore.compute(report).getInt("total")).isEqualTo(17);
        }

        @Test
        @DisplayName("a gated page fails critically and reports the kind as the value")
        void gatedPage_failsCriticallyWithKind() {
            JSONObject report = emptyReport();
            report.put("visibility", new JSONObject()
                    .put("guestReadable", false)
                    .put("kind", "acl"));

            JSONObject score = GeoScore.compute(report);

            assertThat(passed(score, "guestReadable")).isFalse();
            assertThat(check(score, "guestReadable").getString("value")).isEqualTo("acl");
        }
    }

    // --------------------------------------------------------- access checks

    @Nested
    @DisplayName("access")
    class Access {

        @Test
        @DisplayName("reachable passes only when nothing was blocked, and reports the count")
        void reachable_countsBlocked() {
            assertThat(passed(GeoScore.compute(emptyReport()), "reachable")).isTrue();

            JSONObject report = emptyReport();
            report.put("blockedCount", 3);
            JSONObject score = GeoScore.compute(report);

            assertThat(passed(score, "reachable")).isFalse();
            assertThat(check(score, "reachable").getInt("value")).isEqualTo(3);
        }

        @Test
        @DisplayName("policyMatchesReality sums BOTH kinds of contradiction")
        void policyMatchesReality_sumsBothMismatchKinds() {
            JSONObject report = emptyReport();
            report.put("blockedButAllowedCount", 2);
            report.put("reachableButDisallowedCount", 1);

            JSONObject score = GeoScore.compute(report);

            assertThat(passed(score, "policyMatchesReality")).isFalse();
            assertThat(check(score, "policyMatchesReality").getInt("value")).isEqualTo(3);
        }

        @Test
        @DisplayName("being disallowed in robots.txt is deliberately NOT a failure on its own")
        void disallowedButConsistent_isNotAFailure() {
            // The class comment states this as a rule: refusing a crawler is a
            // legitimate decision. Only a DISAGREEMENT between policy and reality
            // is reported, because then one of the two is wrong.
            JSONObject report = emptyReport();
            report.put("blockedCount", 0);
            report.put("blockedButAllowedCount", 0);
            report.put("reachableButDisallowedCount", 0);

            JSONObject score = GeoScore.compute(report);

            assertThat(passed(score, "policyMatchesReality")).isTrue();
            assertThat(passed(score, "reachable")).isTrue();
        }

        @Test
        @DisplayName("a meta refresh fails critically and reports the target")
        void metaRefresh_failsCritically() {
            JSONObject score = GeoScore.compute(withControl(
                    new JSONObject().put("metaRefresh", "0;url=/elsewhere")));

            assertThat(passed(score, "noClientRedirect")).isFalse();
            assertThat(check(score, "noClientRedirect").getString("value")).isEqualTo("0;url=/elsewhere");
        }

        @Test
        @DisplayName("namedInRobots is advisory and passes on any non-zero count")
        void namedInRobots_passesOnAnyCount() {
            JSONObject report = emptyReport();
            report.put("siteFiles", new JSONObject()
                    .put("robots", new JSONObject().put("namedAiBotCount", 1)));

            assertThat(passed(GeoScore.compute(report), "namedInRobots")).isTrue();
        }
    }

    // -------------------------------------------------------- content checks

    @Nested
    @DisplayName("content")
    class Content {

        @Test
        @DisplayName("the control is the FIRST agent that answered 200 with html")
        void control_isFirst200WithHtml() {
            JSONArray agents = new JSONArray();
            agents.put(new JSONObject().put("status", 403));
            // 200 but no html block: skipped, not taken as the control.
            agents.put(new JSONObject().put("status", 200));
            agents.put(new JSONObject().put("status", 200)
                    .put("html", new JSONObject().put("h1Count", 1)));
            agents.put(new JSONObject().put("status", 200)
                    .put("html", new JSONObject().put("h1Count", 9)));
            JSONObject report = new JSONObject();
            report.put("agents", agents);

            assertThat(check(GeoScore.compute(report), "singleH1").getInt("value")).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0} words -> passed={1}")
        @CsvSource({"0, false", "49, false", "50, true", "51, true"})
        @DisplayName("contentInInitialHtml turns at fifty words")
        void contentInInitialHtml_thresholdIsFifty(int words, boolean expected) {
            JSONObject report = emptyReport();
            report.put("controlWords", words);

            assertThat(passed(GeoScore.compute(report), "contentInInitialHtml")).isEqualTo(expected);
        }

        @ParameterizedTest(name = "title of {0} chars -> passed={1}")
        @CsvSource({"9, false", "10, true", "70, true", "71, false"})
        @DisplayName("title is inclusive at both ends, ten to seventy")
        void title_boundsAreInclusive(int length, boolean expected) {
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < length; i++) {
                t.append('x');
            }
            JSONObject score = GeoScore.compute(withControl(
                    new JSONObject().put("title", t.toString())));

            assertThat(passed(score, "title")).isEqualTo(expected);
            assertThat(check(score, "title").getInt("value")).isEqualTo(length);
        }

        @Test
        @DisplayName("an absent title fails and reports length zero")
        void absentTitle_failsWithZero() {
            JSONObject score = GeoScore.compute(withControl(new JSONObject()));

            assertThat(passed(score, "title")).isFalse();
            assertThat(check(score, "title").getInt("value")).isZero();
        }

        @ParameterizedTest(name = "{0} h1 -> passed={1}")
        @CsvSource({"0, false", "1, true", "2, false"})
        @DisplayName("singleH1 wants exactly one, so none and two both fail")
        void singleH1_wantsExactlyOne(int count, boolean expected) {
            JSONObject score = GeoScore.compute(withControl(
                    new JSONObject().put("h1Count", count)));

            assertThat(passed(score, "singleH1")).isEqualTo(expected);
        }

        @Test
        @DisplayName("a crawler under half the control's word count counts as thin")
        void sameContentForCrawlers_halfIsTheThreshold() {
            JSONArray agents = new JSONArray();
            agents.put(new JSONObject().put("status", 200)
                    .put("html", new JSONObject().put("words", 100)));
            // exactly half: NOT thin, the comparison is strictly less than
            agents.put(new JSONObject().put("status", 200)
                    .put("html", new JSONObject().put("words", 50)));
            agents.put(new JSONObject().put("status", 200)
                    .put("html", new JSONObject().put("words", 49)));
            JSONObject report = new JSONObject();
            report.put("agents", agents);
            report.put("controlWords", 100);

            JSONObject score = GeoScore.compute(report);

            assertThat(passed(score, "sameContentForCrawlers")).isFalse();
            assertThat(check(score, "sameContentForCrawlers").getInt("value")).isEqualTo(1);
        }

        @Test
        @DisplayName("with no control words the thin check cannot run, and passes")
        void sameContentForCrawlers_noControlWords_passes() {
            // SUSPECT: a page that returned nothing at all scores a PASS here
            // rather than being excluded. contentInInitialHtml catches that case
            // critically, so the total is not misleading, but this check reads as
            // "every crawler got the same content" when the truth is "there was
            // nothing to compare". Pinned as-is.
            JSONArray agents = new JSONArray();
            agents.put(new JSONObject().put("status", 200)
                    .put("html", new JSONObject().put("words", 0)));
            JSONObject report = new JSONObject();
            report.put("agents", agents);
            report.put("controlWords", 0);

            assertThat(passed(GeoScore.compute(report), "sameContentForCrawlers")).isTrue();
        }

        @ParameterizedTest(name = "{0} images, {1} with alt -> passed={2}")
        @CsvSource({
            "0,  0,  true",
            "10, 8,  true",
            "10, 7,  false",
            "1,  0,  false",
            "1,  1,  true"
        })
        @DisplayName("imageAlt wants eighty percent, and no images is not a failure")
        void imageAlt_coverageIsEightyPercent(int images, int withAlt, boolean expected) {
            JSONObject score = GeoScore.compute(withControl(new JSONObject()
                    .put("images", images)
                    .put("imagesWithAlt", withAlt)));

            assertThat(passed(score, "imageAlt")).isEqualTo(expected);
        }

        @Test
        @DisplayName("imageAlt reports the ratio, or null when there is nothing to describe")
        void imageAlt_valueIsTheRatio() {
            JSONObject some = GeoScore.compute(withControl(new JSONObject()
                    .put("images", 4).put("imagesWithAlt", 3)));
            assertThat(check(some, "imageAlt").getString("value")).isEqualTo("3/4");

            JSONObject none = GeoScore.compute(withControl(new JSONObject().put("images", 0)));
            assertThat(check(none, "imageAlt").isNull("value")).isTrue();
        }

        @Test
        @DisplayName("structuredData joins the JSON-LD types with a comma")
        void structuredData_joinsTypes() {
            JSONObject score = GeoScore.compute(withControl(new JSONObject()
                    .put("jsonLdTypes", new JSONArray().put("WebPage").put("Article"))));

            assertThat(passed(score, "structuredData")).isTrue();
            assertThat(check(score, "structuredData").getString("value")).isEqualTo("WebPage, Article");
        }

        @Test
        @DisplayName("an empty JSON-LD array is not structured data")
        void structuredData_emptyArrayFails() {
            JSONObject score = GeoScore.compute(withControl(new JSONObject()
                    .put("jsonLdTypes", new JSONArray())));

            assertThat(passed(score, "structuredData")).isFalse();
        }

        @Test
        @DisplayName("langDeclared reports the language it found")
        void langDeclared_reportsTheValue() {
            JSONObject score = GeoScore.compute(withControl(new JSONObject().put("lang", "fr")));

            assertThat(passed(score, "langDeclared")).isTrue();
            assertThat(check(score, "langDeclared").getString("value")).isEqualTo("fr");
        }
    }

    // ------------------------------------------------------------ site files

    @Nested
    @DisplayName("site files")
    class Files {

        @Test
        @DisplayName("both fail when siteFiles is absent entirely")
        void absentSiteFiles_bothFail() {
            JSONObject score = GeoScore.compute(emptyReport());

            assertThat(passed(score, "robotsPresent")).isFalse();
            assertThat(passed(score, "llmsPresent")).isFalse();
        }

        @Test
        @DisplayName("each passes only on its own present flag")
        void presentFlags_areIndependent() {
            JSONObject report = emptyReport();
            report.put("siteFiles", new JSONObject()
                    .put("robots", new JSONObject().put("present", true))
                    .put("llms", new JSONObject().put("present", false)));

            JSONObject score = GeoScore.compute(report);

            assertThat(passed(score, "robotsPresent")).isTrue();
            assertThat(passed(score, "llmsPresent")).isFalse();
        }
    }

    // ---------------------------------------------------------------- tally

    @Nested
    @DisplayName("the tally")
    class Tally {

        @Test
        @DisplayName("an empty report scores five of seventeen with ONE critical failure")
        void emptyReport_scoresFiveOfSeventeen() {
            // The exact baseline. If a refactor moves any of these four numbers it
            // has changed what every unscored page reports.
            //
            // SUSPECT, and the reason this test is worth having. A report about a
            // page nothing is known about - never fetched, no html, no site files -
            // reports only ONE critical failure, because three critical checks pass
            // on the ABSENCE of evidence rather than on evidence:
            //
            //   reachable              blockedCount defaults to 0, so "nothing was
            //                          blocked" and "nothing was tried" are equal
            //   noClientRedirect       no control html means no metaRefresh found
            //   sameContentForCrawlers controlWords is 0, so the thin-content loop
            //                          never runs and thin stays 0
            //
            // Only contentInInitialHtml fails. A page that could not be fetched at
            // all therefore scores better than a page that was fetched and found
            // wanting. Pinned as today's behaviour, not endorsed.
            JSONObject score = GeoScore.compute(emptyReport());

            assertThat(score.getInt("total")).isEqualTo(17);
            assertThat(score.getInt("passed")).isEqualTo(5);
            assertThat(score.getInt("criticalFailed")).isEqualTo(1);
            assertThat(score.getInt("importantFailed")).isEqualTo(7);
        }

        @Test
        @DisplayName("the three checks that pass on absence of evidence, named")
        void absenceOfEvidence_passesThreeCriticalChecks() {
            // Pinned individually so a refactor that changes any ONE of them is
            // identified, rather than only showing up as a moved total above.
            JSONObject score = GeoScore.compute(emptyReport());

            assertThat(passed(score, "reachable")).isTrue();
            assertThat(passed(score, "noClientRedirect")).isTrue();
            assertThat(passed(score, "sameContentForCrawlers")).isTrue();
            assertThat(passed(score, "contentInInitialHtml")).isFalse();
        }

        @Test
        @DisplayName("passed plus the two failure counts plus advisory failures equals the total")
        void counts_accountForEveryCheck() {
            JSONObject score = GeoScore.compute(emptyReport());

            int advisoryFailed = 0;
            JSONArray checks = score.getJSONArray("checks");
            for (int i = 0; i < checks.length(); i++) {
                JSONObject c = checks.getJSONObject(i);
                if (!c.getBoolean("passed") && "advisory".equals(c.getString("severity"))) {
                    advisoryFailed++;
                }
            }

            assertThat(score.getInt("passed")
                    + score.getInt("criticalFailed")
                    + score.getInt("importantFailed")
                    + advisoryFailed)
                    .isEqualTo(score.getInt("total"));
        }

        @Test
        @DisplayName("a fully healthy page passes everything")
        void healthyPage_passesAll() {
            JSONObject html = new JSONObject()
                    .put("title", "A title comfortably inside the bounds")
                    .put("h1Count", 1)
                    .put("h2Count", 3)
                    .put("words", 500)
                    .put("metaDescription", true)
                    .put("canonical", true)
                    .put("lang", "en")
                    .put("dateModified", "2026-01-01")
                    .put("images", 2)
                    .put("imagesWithAlt", 2)
                    .put("jsonLdTypes", new JSONArray().put("WebPage"));

            JSONObject report = withControl(html);
            report.put("controlWords", 500);
            report.put("blockedCount", 0);
            report.put("siteFiles", new JSONObject()
                    .put("robots", new JSONObject().put("present", true).put("namedAiBotCount", 4))
                    .put("llms", new JSONObject().put("present", true)));
            report.put("visibility", new JSONObject().put("guestReadable", true));

            JSONObject score = GeoScore.compute(report);

            assertThat(score.getInt("total")).isEqualTo(18);
            assertThat(score.getInt("passed")).isEqualTo(18);
            assertThat(score.getInt("criticalFailed")).isZero();
            assertThat(score.getInt("importantFailed")).isZero();
        }
    }
}
