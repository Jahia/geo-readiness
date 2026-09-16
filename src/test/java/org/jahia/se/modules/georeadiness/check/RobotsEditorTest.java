package org.jahia.se.modules.georeadiness.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterisation tests for {@link RobotsEditor#parse(String)}.
 *
 * parse() is private (cognitive complexity 16) and about to be decomposed, so
 * these tests reach it exclusively through {@link RobotsEditor#apply}, which
 * is the only public entry point that calls it: {@link RobotsEditor#currentDecisions}
 * delegates to a *different* class, {@code RobotsRules.parse}, already
 * covered by {@code RobotsRulesTest}, and never touches this one.
 *
 * The vehicle used throughout to observe parse() in isolation is calling
 * apply() with an EMPTY decisions map: with nothing to change, apply() just
 * re-renders the parsed block structure, so its output is exactly what
 * parse() produced. Every assertion states the ACTUAL current output. Where
 * the output looks wrong it is still pinned, with a "SUSPECT:" comment
 * explaining the concern, rather than silently fixed.
 */
class RobotsEditorTest {

    private static final Map<String, String> NO_DECISIONS = new LinkedHashMap<>();

    private static Map<String, String> decision(String token, String want) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(token, want);
        return m;
    }

    // ------------------------------------------------------- grouping / round-trip

    @Nested
    @DisplayName("grouping User-agent blocks with their directives")
    class Grouping {

        // @ValueSource rather than @CsvSource on purpose: CSV trims leading and
        // trailing whitespace, and preserving it byte for byte is exactly what
        // these assert. A Java string literal in the annotation keeps it.
        @ParameterizedTest(name = "case {index}")
        @ValueSource(strings = {
            // consecutive User-agent lines share one rule block
            "User-agent: A\nUser-agent: B\nUser-agent: C\nDisallow: /\n",
            // separate groups, and the blank line that divides them
            "User-agent: A\nDisallow: /a\n\nUser-agent: B\nDisallow: /b\n",
            // a directive this module does not manage
            "User-agent: *\nCrawl-delay: 10\nDisallow: /x\n",
            // a Sitemap line outside any group, parsed as a Raw block
            "Sitemap: https://example.com/sitemap.xml\nUser-agent: *\nDisallow: /x\n"
        })
        @DisplayName("with no decisions to apply, the file comes back byte for byte")
        void noDecisions_roundTripsUntouched(String original) {
            assertThat(RobotsEditor.apply(original, NO_DECISIONS)).isEqualTo(original);
        }

        @Test
        @DisplayName("splitting a shared group: blocking one of several agents moves it into its own new group")
        void blockingOneOfSeveralAgents_splitsItIntoItsOwnGroup() {
            String original = "User-agent: A\nUser-agent: B\nDisallow: /shared\n";

            String out = RobotsEditor.apply(original, decision("A", RobotsEditor.BLOCK));

            // B keeps the original shared rule in place; A is pulled out into a
            // brand-new appended group with a fresh "Disallow: /", separated by
            // exactly one blank line.
            assertThat(out).isEqualTo("User-agent: B\nDisallow: /shared\n\nUser-agent: A\nDisallow: /\n");
        }

        @Test
        @DisplayName("two decisions on the same three-agent shared group each split out their own token in turn")
        void twoDecisionsOnSameSharedGroup_eachSplitOutInDecisionOrder() {
            String original = "User-agent: A\nUser-agent: B\nUser-agent: C\nDisallow: /\n";
            Map<String, String> decisions = new LinkedHashMap<>();
            decisions.put("A", RobotsEditor.BLOCK);
            decisions.put("B", RobotsEditor.ALLOW);

            String out = RobotsEditor.apply(original, decisions);

            assertThat(out).isEqualTo(
                    "User-agent: C\nDisallow: /\n\nUser-agent: A\nDisallow: /\n\nUser-agent: B\nAllow: /\n");
        }
    }

    @Nested
    @DisplayName("comments, blank lines, CRLF and whitespace")
    class WhitespaceAndComments {

        @ParameterizedTest(name = "case {index}")
        @ValueSource(strings = {
            // comments before, between and after - the file belongs to whoever wrote it
            "# comment\n\nUser-agent: *\nDisallow: /admin\n\n# trailing comment\n",
            // indentation and trailing spaces on an agent line
            "  User-agent: *  \nDisallow: /admin\n"
        })
        @DisplayName("comments and whitespace survive a no-op merge verbatim")
        void commentsAndWhitespace_survive(String original) {
            assertThat(RobotsEditor.apply(original, NO_DECISIONS)).isEqualTo(original);
        }

        @Test
        @DisplayName("CRLF line endings round-trip as CRLF when nothing is changed")
        void crlfLineEndings_roundTripAsCrlf() {
            String original = "User-agent: *\r\nDisallow: /admin\r\n";

            String out = RobotsEditor.apply(original, NO_DECISIONS);

            assertThat(out).isEqualTo(original);
        }

        // WAS SUSPECT, NOW FIXED. The agent line kept its own "\r" because it is
        // untouched raw text, while the inserted rule had none and render always
        // appended a plain "\n" - so one edit left a customer's CRLF file mixing
        // both endings. render now takes the file's own ending and every line
        // gets it.
        @Test
        @DisplayName("an edit to a CRLF file keeps CRLF on every line, including the inserted one")
        void replacingRuleInCrlfFile_keepsCrlfThroughout() {
            String original = "User-agent: A\r\nDisallow: /old\r\n";

            String out = RobotsEditor.apply(original, decision("A", RobotsEditor.BLOCK));

            assertThat(out).isEqualTo("User-agent: A\r\nDisallow: /\r\n");
        }

        @Test
        @DisplayName("a comment-only body round-trips, and a new group is appended after it with one blank-line gap")
        void commentOnlyBody_newGroupAppendedWithOneBlankLine() {
            String original = "# just a comment\n";

            String out = RobotsEditor.apply(original, decision("NewBot", RobotsEditor.BLOCK));

            assertThat(out).isEqualTo("# just a comment\n\nUser-agent: NewBot\nDisallow: /\n");
        }

        // WAS SUSPECT, NOW FIXED. This class documents that it leaves blank
        // lines "byte for byte as it found it". A body of nothing but blank
        // lines contradicted that completely: the trailing-newline
        // normalisation matched the whole thing and returned an empty string.
        // A no-op merge now returns the input untouched.
        @Test
        @DisplayName("a body of blank lines survives a no-op merge unchanged")
        void blankLineOnlyBody_noDecisions_isUnchanged() {
            String original = "\n\n\n";

            assertThat(RobotsEditor.apply(original, NO_DECISIONS)).isEqualTo(original);
        }

        // WAS SUSPECT, NOW FIXED. parse("") yielded one empty line, which became
        // a Raw block of one blank line, and the appended group added a spacer on
        // top - so a brand-new robots.txt opened with two blank lines nobody
        // wrote. An empty body now parses to no blocks at all.
        @Test
        @DisplayName("a new token on an empty file starts at the first line")
        void newTokenOnEmptyOriginal_startsAtTheFirstLine() {
            String out = RobotsEditor.apply("", decision("NewBot", RobotsEditor.BLOCK));

            assertThat(out).isEqualTo("User-agent: NewBot\nDisallow: /\n");
        }
    }

    @Nested
    @DisplayName("empty or absent body")
    class EmptyOrAbsentBody {

        @Test
        @DisplayName("a null original is treated exactly like an empty string")
        void nullOriginal_treatedAsEmptyString() {
            assertThat(RobotsEditor.apply(null, NO_DECISIONS))
                    .isEqualTo(RobotsEditor.apply("", NO_DECISIONS));
            assertThat(RobotsEditor.apply(null, NO_DECISIONS)).isEmpty();
        }

        @Test
        @DisplayName("an empty original with no decisions stays empty, with no trailing newline added")
        void emptyOriginal_noDecisions_staysEmpty() {
            assertThat(RobotsEditor.apply("", NO_DECISIONS)).isEmpty();
        }

        // Not on the SUSPECT list; fixing the byte-for-byte promise properly is
        // what surfaced it. A no-op merge used to ADD a trailing newline to a
        // file the caller never asked to change, which is a diff on an untouched
        // file. Normalisation still applies when a decision is really merged.
        @Test
        @DisplayName("a no-op merge does not add a trailing newline the file did not have")
        void missingTrailingNewline_isLeftAloneWhenNothingChanges() {
            String original = "User-agent: *\nDisallow: /admin";

            assertThat(RobotsEditor.apply(original, NO_DECISIONS)).isEqualTo(original);
        }

        @Test
        @DisplayName("a User-agent line with no rule lines at all still gets a directive inserted when decided")
        void agentLineWithNoRules_getsDirectiveInserted() {
            String out = RobotsEditor.apply("User-agent: Lonely\n", decision("Lonely", RobotsEditor.BLOCK));

            assertThat(out).isEqualTo("User-agent: Lonely\nDisallow: /\n");
        }

        @Test
        @DisplayName("consecutive agent lines with no rule lines before EOF round-trip untouched")
        void consecutiveAgentLinesNoRulesEOF_roundTrip() {
            String original = "User-agent: A\nUser-agent: B\n";

            String out = RobotsEditor.apply(original, NO_DECISIONS);

            assertThat(out).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("apply(): decisions already present, and conflicting decisions")
    class ApplyDecisionLogic {

        @Test
        @DisplayName("allow on a group that already allows (path-level rules only) is a strict no-op")
        void allowOnAlreadyPathLevelAllowed_isNoOp() {
            String original = "User-agent: A\nDisallow: /docs/\n";

            String out = RobotsEditor.apply(original, decision("A", RobotsEditor.ALLOW));

            assertThat(out).isEqualTo(original);
        }

        @Test
        @DisplayName("allow on a group that blocks everything replaces its rule, since that IS the conflict to fix")
        void allowOnFullyBlockingGroup_replacesRule() {
            String original = "User-agent: A\nDisallow: /\n";

            String out = RobotsEditor.apply(original, decision("A", RobotsEditor.ALLOW));

            assertThat(out).isEqualTo("User-agent: A\nAllow: /\n");
        }

        @Test
        @DisplayName("block replaces existing rules on a single-agent group, keeping any comment among them")
        void blockOnSingleAgentGroup_replacesRulesKeepingComment() {
            String original = "User-agent: A\nAllow: /\n# a comment kept?\nDisallow: /old\n";

            String out = RobotsEditor.apply(original, decision("A", RobotsEditor.BLOCK));

            // Both old rule lines (Allow and Disallow) are removed; the single new
            // "Disallow: /" is inserted at the position of the FIRST rule line, and
            // the comment - which is not itself a rule line - survives, now sitting
            // after the one remaining directive.
            assertThat(out).isEqualTo("User-agent: A\nDisallow: /\n# a comment kept?\n");
        }

        @Test
        @DisplayName("a token named nowhere gets a brand-new appended group, for block or for allow")
        void tokenNamedNowhere_getsNewAppendedGroup() {
            String original = "User-agent: Other\nDisallow: /x\n";

            String blocked = RobotsEditor.apply(original, decision("NewBot", RobotsEditor.BLOCK));
            String allowed = RobotsEditor.apply(original, decision("NewBot", RobotsEditor.ALLOW));

            assertThat(blocked).isEqualTo("User-agent: Other\nDisallow: /x\n\nUser-agent: NewBot\nDisallow: /\n");
            assertThat(allowed).isEqualTo("User-agent: Other\nDisallow: /x\n\nUser-agent: NewBot\nAllow: /\n");
        }

        @Test
        @DisplayName("two brand-new tokens are both appended, in the decisions map's iteration order")
        void twoNewTokens_appendedInDecisionOrder() {
            String original = "User-agent: *\nDisallow: /x\n";
            Map<String, String> decisions = new LinkedHashMap<>();
            decisions.put("First", RobotsEditor.BLOCK);
            decisions.put("Second", RobotsEditor.ALLOW);

            String out = RobotsEditor.apply(original, decisions);

            assertThat(out).isEqualTo("User-agent: *\nDisallow: /x\n"
                    + "\nUser-agent: First\nDisallow: /\n"
                    + "\nUser-agent: Second\nAllow: /\n");
        }

        @Test
        @DisplayName("a case-different token still matches an existing group and rewrites its rules in place")
        void caseInsensitiveTokenMatch_rewritesExistingGroup() {
            String original = "User-Agent: GPTBot\nDisallow: /\n";

            String out = RobotsEditor.apply(original, decision("gptbot", RobotsEditor.ALLOW));

            // The original agent line's casing is preserved verbatim; only the
            // rule line is rewritten.
            assertThat(out).isEqualTo("User-Agent: GPTBot\nAllow: /\n");
        }

        @Test
        @DisplayName("a blank or whitespace-only token in the decisions map is ignored")
        void blankToken_isIgnored() {
            String original = "User-agent: *\nDisallow: /x\n";

            String out = RobotsEditor.apply(original, decision("   ", RobotsEditor.BLOCK));

            assertThat(out).isEqualTo(original);
        }

        @Test
        @DisplayName("a decision value that is neither allow nor block is ignored")
        void invalidWantValue_isIgnored() {
            String original = "User-agent: *\nDisallow: /x\n";

            String out = RobotsEditor.apply(original, decision("A", "bogus"));

            assertThat(out).isEqualTo(original);
        }

        @Test
        @DisplayName("an empty decisions map leaves any original untouched, aside from trailing-newline normalisation")
        void emptyDecisionsMap_leavesOriginalUntouched() {
            String original = "User-agent: *\nAllow: /\n";

            String out = RobotsEditor.apply(original, NO_DECISIONS);

            assertThat(out).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("currentDecisions() does not reach RobotsEditor.parse")
    class CurrentDecisionsScopeNote {

        @Test
        @DisplayName("currentDecisions delegates to RobotsRules.parse, a different class, and still answers correctly")
        void currentDecisions_worksViaADifferentParser() {
            // Documented here rather than skipped: currentDecisions() calls
            // RobotsRules.parse(...) (RobotsEditor.java:74), NOT the private
            // RobotsEditor.parse() under test in this file. That parser is
            // already characterised in RobotsRulesTest. This test only confirms
            // currentDecisions' own public contract still holds; it is NOT
            // coverage of RobotsEditor.parse().
            Map<String, String> decisions = RobotsEditor.currentDecisions(
                    "User-agent: GPTBot\nDisallow: /\n", List.of("GPTBot", "OtherBot"));

            assertThat(decisions)
                    .containsEntry("GPTBot", RobotsEditor.BLOCK)
                    .containsEntry("OtherBot", RobotsEditor.ALLOW);
        }
    }
}
