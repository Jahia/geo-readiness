package org.jahia.se.modules.georeadiness.check;

import org.jahia.se.modules.georeadiness.check.RobotsRules.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterisation tests for {@link RobotsRules}.
 *
 * These exist to freeze what parse()/evaluate() do TODAY, not to argue that it
 * is right. RobotsRules is about to be decomposed - three of its methods sit
 * above the cognitive-complexity limit - so every assertion below states the
 * ACTUAL current output. Where the output looks like a bug, the test still
 * asserts it (so a silent behaviour change during refactor is caught) and is
 * marked with a "SUSPECT:" comment explaining what looks wrong.
 */
class RobotsRulesTest {

    // ------------------------------------------------------------- parse()

    @Nested
    @DisplayName("parse()")
    class ParseTests {

        @Test
        @DisplayName("consecutive User-agent lines share one rule block")
        void parse_consecutiveUserAgentLines_shareOneRuleBlock() {
            RobotsRules r = RobotsRules.parse(
                    "User-agent: Alpha\n" +
                    "User-agent: Beta\n" +
                    "Disallow: /private\n");

            Verdict alpha = r.evaluate("Alpha", "/private/x");
            Verdict beta = r.evaluate("Beta", "/private/x");

            assertThat(alpha.allowed).isFalse();
            assertThat(alpha.matchedRule).isEqualTo("Disallow: /private");
            assertThat(beta.allowed).isFalse();
            assertThat(beta.matchedRule).isEqualTo("Disallow: /private");
        }

        @Test
        @DisplayName("wildcard '*' group applies to any crawler with no explicit group")
        void parse_wildcardGroup_appliesToUnnamedCrawler() {
            RobotsRules r = RobotsRules.parse("User-agent: *\nDisallow: /admin\n");

            Verdict v = r.evaluate("SomeRandomBot", "/admin/x");

            assertThat(v.allowed).isFalse();
            assertThat(v.matchedGroup).isEqualTo("*");
            assertThat(v.namedExplicitly).isFalse();
        }

        @Test
        @DisplayName("directive field names are matched case-insensitively")
        void parse_directiveFieldNames_areCaseInsensitive() {
            RobotsRules r = RobotsRules.parse("USER-AGENT: Bot\nDISALLOW: /x\n");

            Verdict v = r.evaluate("Bot", "/x/y");

            assertThat(v.allowed).isFalse();
            assertThat(v.matchedRule).isEqualTo("Disallow: /x");
        }

        @Test
        @DisplayName("agent tokens are matched case-insensitively")
        void parse_agentTokens_areCaseInsensitive() {
            RobotsRules r = RobotsRules.parse("User-agent: BOT\nDisallow: /y\n");

            Verdict v = r.evaluate("bot", "/y/1");

            assertThat(v.allowed).isFalse();
            assertThat(v.namedExplicitly).isTrue();
        }

        @Test
        @DisplayName("declaredAgents preserves the original casing as written in the file")
        void parse_declaredAgents_preservesOriginalCasing() {
            RobotsRules r = RobotsRules.parse("User-agent: BOT\nDisallow: /y\n");

            assertThat(r.getDeclaredAgents()).containsExactly("BOT");
        }

        @Test
        @DisplayName("a trailing inline comment is stripped from the directive value")
        void parse_inlineComment_isStrippedFromValue() {
            RobotsRules r = RobotsRules.parse(
                    "User-agent: *\nDisallow: /secret # do not crawl this\n");

            Verdict v = r.evaluate("bot", "/secret/1");

            assertThat(v.allowed).isFalse();
            assertThat(v.matchedRule).isEqualTo("Disallow: /secret");
        }

        @Test
        @DisplayName("a comment-only line is ignored")
        void parse_commentOnlyLine_isIgnored() {
            RobotsRules r = RobotsRules.parse(
                    "# this whole file disallows /admin\nUser-agent: *\nDisallow: /admin\n");

            Verdict v = r.evaluate("bot", "/admin/1");

            assertThat(v.allowed).isFalse();
            assertThat(r.getDeclaredAgents()).containsExactly("*");
        }

        // SUSPECT (RobotsRules.java:79-82): a blank line does not reset the
        // "lastLineWasAgent" flag before the User-agent case can reset the
        // currentAgents list, so a blank line between two User-agent blocks does
        // NOT start a new group the way it conventionally would in robots.txt -
        // the two blocks are silently merged into one. Pinning current behaviour.
        @Test
        @DisplayName("SUSPECT: a blank line between two User-agent blocks still merges them into one group")
        void parse_blankLineBetweenUserAgentBlocks_stillMergesGroups() {
            RobotsRules r = RobotsRules.parse(
                    "User-agent: Alpha\n" +
                    "\n" +
                    "User-agent: Beta\n" +
                    "Disallow: /z\n");

            Verdict alpha = r.evaluate("Alpha", "/z/1");
            Verdict beta = r.evaluate("Beta", "/z/1");

            // If blank lines correctly separated records, "Alpha" would have an
            // empty rule set and this path would be allowed for it. Instead it
            // shares Beta's Disallow rule.
            assertThat(alpha.allowed).isFalse();
            assertThat(alpha.matchedRule).isEqualTo("Disallow: /z");
            assertThat(beta.allowed).isFalse();
            assertThat(beta.matchedRule).isEqualTo("Disallow: /z");
        }

        @Test
        @DisplayName("CRLF line endings are parsed the same as LF")
        void parse_crlfLineEndings_areHandled() {
            RobotsRules r = RobotsRules.parse("User-agent: *\r\nDisallow: /admin\r\n");

            Verdict v = r.evaluate("bot", "/admin/1");

            assertThat(v.allowed).isFalse();
            assertThat(v.matchedRule).isEqualTo("Disallow: /admin");
        }

        @Test
        @DisplayName("a null body is treated as parsed, but yields no groups at all")
        void parse_nullBody_isParsedButEmpty() {
            RobotsRules r = RobotsRules.parse(null);

            assertThat(r.isParsed()).isTrue();
            assertThat(r.getDeclaredAgents()).isEmpty();
            Verdict v = r.evaluate("anybot", "/anything");
            assertThat(v.allowed).isTrue();
            assertThat(v.matchedGroup).isNull();
            assertThat(v.matchedRule).isNull();
        }

        @Test
        @DisplayName("an empty-string body is treated as parsed, but yields no groups at all")
        void parse_emptyBody_isParsedButEmpty() {
            RobotsRules r = RobotsRules.parse("");

            assertThat(r.isParsed()).isTrue();
            assertThat(r.getDeclaredAgents()).isEmpty();
            Verdict v = r.evaluate("anybot", "/anything");
            assertThat(v.allowed).isTrue();
        }

        @Test
        @DisplayName("sitemap directives are collected in declaration order")
        void parse_sitemapDirectives_areCollected() {
            RobotsRules r = RobotsRules.parse(
                    "Sitemap: https://example.com/sitemap-1.xml\n" +
                    "Sitemap: https://example.com/sitemap-2.xml\n");

            assertThat(r.getSitemaps())
                    .containsExactly("https://example.com/sitemap-1.xml", "https://example.com/sitemap-2.xml");
        }
    }

    // ------------------------------------------------------- evaluate(): groups

    @Nested
    @DisplayName("evaluate() - user-agent group selection")
    class GroupSelectionTests {

        @Test
        @DisplayName("a crawler token is matched when the declared agent is a prefix of it")
        void evaluate_tokenLongerThanDeclaredAgent_matchesByPrefix() {
            RobotsRules r = RobotsRules.parse("User-agent: Googlebot\nDisallow: /x\n");

            Verdict v = r.evaluate("Googlebot-Image", "/x/1");

            assertThat(v.namedExplicitly).isTrue();
            assertThat(v.matchedGroup).isEqualTo("googlebot");
            assertThat(v.allowed).isFalse();
        }

        @Test
        @DisplayName("a crawler token is matched when it is itself a prefix of the declared agent")
        void evaluate_declaredAgentLongerThanToken_matchesByPrefix() {
            RobotsRules r = RobotsRules.parse("User-agent: Googlebot-News\nDisallow: /x\n");

            Verdict v = r.evaluate("Googlebot", "/x/1");

            assertThat(v.namedExplicitly).isTrue();
            assertThat(v.matchedGroup).isEqualTo("googlebot-news");
            assertThat(v.allowed).isFalse();
        }

        @Test
        @DisplayName("an explicitly named group is preferred over the wildcard group")
        void evaluate_agentNamedExplicitly_prefersItsOwnGroupOverWildcard() {
            RobotsRules r = RobotsRules.parse(
                    "User-agent: *\n" +
                    "Disallow: /\n" +
                    "User-agent: GPTBot\n" +
                    "Allow: /\n");

            Verdict named = r.evaluate("GPTBot", "/anything");
            Verdict fallback = r.evaluate("OtherBot", "/anything");

            assertThat(named.namedExplicitly).isTrue();
            assertThat(named.matchedGroup).isEqualTo("gptbot");
            assertThat(named.allowed).isTrue();

            assertThat(fallback.namedExplicitly).isFalse();
            assertThat(fallback.matchedGroup).isEqualTo("*");
            assertThat(fallback.allowed).isFalse();
        }

        @Test
        @DisplayName("no matching named group and no wildcard group allows everything")
        void evaluate_noMatchingGroupAtAll_allowsEverything() {
            RobotsRules r = RobotsRules.parse("User-agent: SomeOtherBot\nDisallow: /\n");

            Verdict v = r.evaluate("GPTBot", "/anything");

            assertThat(v.allowed).isTrue();
            assertThat(v.matchedGroup).isNull();
            assertThat(v.matchedRule).isNull();
            assertThat(v.namedExplicitly).isFalse();
        }

        @Test
        @DisplayName("namesAgent() reports true only for tokens with their own declared group")
        void namesAgent_reflectsDeclaredGroupsOnly() {
            RobotsRules r = RobotsRules.parse("User-agent: GPTBot\nDisallow: /x\nUser-agent: *\nDisallow: /y\n");

            assertThat(r.namesAgent("GPTBot")).isTrue();
            assertThat(r.namesAgent("gptbot")).isTrue();
            assertThat(r.namesAgent("SomeOtherBot")).isFalse();
        }
    }

    // -------------------------------------------------- evaluate(): rule matching

    @Nested
    @DisplayName("evaluate() - longest match wins, Allow beats Disallow on a tie")
    class RuleMatchingTests {

        @Test
        @DisplayName("a longer, more specific Allow overrides a shorter Disallow")
        void evaluate_longerAllow_overridesShorterDisallow() {
            RobotsRules r = RobotsRules.parse(
                    "User-agent: *\nDisallow: /folder\nAllow: /folder/public\n");

            Verdict v = r.evaluate("bot", "/folder/public/file.html");

            assertThat(v.allowed).isTrue();
            assertThat(v.matchedRule).isEqualTo("Allow: /folder/public");
        }

        @Test
        @DisplayName("a longer, more specific Disallow overrides a shorter Allow")
        void evaluate_longerDisallow_overridesShorterAllow() {
            RobotsRules r = RobotsRules.parse(
                    "User-agent: *\nAllow: /folder\nDisallow: /folder/secret\n");

            Verdict v = r.evaluate("bot", "/folder/secret/file.html");

            assertThat(v.allowed).isFalse();
            assertThat(v.matchedRule).isEqualTo("Disallow: /folder/secret");
        }

        @Test
        @DisplayName("on an equal-length match, Allow wins over Disallow regardless of declaration order")
        void evaluate_equalLengthMatch_allowWinsTieBreak() {
            RobotsRules disallowFirst = RobotsRules.parse(
                    "User-agent: *\nDisallow: /page\nAllow: /page\n");
            RobotsRules allowFirst = RobotsRules.parse(
                    "User-agent: *\nAllow: /page\nDisallow: /page\n");

            Verdict v1 = disallowFirst.evaluate("bot", "/page/foo");
            Verdict v2 = allowFirst.evaluate("bot", "/page/foo");

            assertThat(v1.allowed).isTrue();
            assertThat(v1.matchedRule).isEqualTo("Allow: /page");
            assertThat(v2.allowed).isTrue();
            assertThat(v2.matchedRule).isEqualTo("Allow: /page");
        }

        @Test
        @DisplayName("an empty Disallow value conventionally means allow everything")
        void evaluate_emptyDisallowValue_meansAllowEverything() {
            RobotsRules r = RobotsRules.parse("User-agent: *\nDisallow:\n");

            Verdict v = r.evaluate("bot", "/anything/at/all");

            assertThat(v.allowed).isTrue();
            assertThat(v.matchedRule).isNull();
            assertThat(v.matchedGroup).isEqualTo("*");
        }

        @Test
        @DisplayName("a bare '/' Disallow blocks every path")
        void evaluate_bareSlashDisallow_blocksEveryPath() {
            RobotsRules r = RobotsRules.parse("User-agent: *\nDisallow: /\n");

            Verdict root = r.evaluate("bot", "/");
            Verdict deep = r.evaluate("bot", "/anything/at/all");

            assertThat(root.allowed).isFalse();
            assertThat(root.matchedRule).isEqualTo("Disallow: /");
            assertThat(deep.allowed).isFalse();
            assertThat(deep.matchedRule).isEqualTo("Disallow: /");
        }

        @Test
        @DisplayName("a rule with a query-string pattern is evaluated against the query string too")
        void evaluate_queryStringPattern_matchesPathWithQueryString() {
            RobotsRules r = RobotsRules.parse("User-agent: *\nDisallow: /*?reply=\n");

            Verdict withQuery = r.evaluate("bot", "/thread?reply=5");
            Verdict withoutQuery = r.evaluate("bot", "/thread");

            assertThat(withQuery.allowed).isFalse();
            assertThat(withQuery.matchedRule).isEqualTo("Disallow: /*?reply=");
            assertThat(withoutQuery.allowed).isTrue();
        }

        @Test
        @DisplayName("path matching against patterns is case-sensitive")
        void evaluate_pathMatching_isCaseSensitive() {
            RobotsRules r = RobotsRules.parse("User-agent: *\nDisallow: /Private\n");

            Verdict lowercasePath = r.evaluate("bot", "/private/1");
            Verdict exactCasePath = r.evaluate("bot", "/Private/1");

            assertThat(lowercasePath.allowed).isTrue();
            assertThat(exactCasePath.allowed).isFalse();
        }
    }

    // ---------------------------------------------------- matches(): wildcards

    @Nested
    @DisplayName("matches() - '*' and '$' wildcard support")
    class WildcardTests {

        @ParameterizedTest(name = "pattern=\"{0}\" path=\"{1}\" -> {2}")
        @DisplayName("prefix, mid-pattern '*' and query-string matching")
        @CsvSource({
            "/folder,           /folder/file.html,      true",
            "/folder,           /other/file.html,       false",
            "/folder/*/file,    /folder/abc/file,        true",
            "/folder/*/file,    /folder/file,            false",
            "*.pdf,             /downloads/report.pdf,   true",
            "*.pdf,             /downloads/report.txt,   false",
            "/*?reply=,         /thread?reply=5,         true",
            "/*?reply=,         /thread,                 false",
            "/,                 /anything,               true",
            "'',                /anything,               true"
        })
        void matches_prefixAndWildcardPatterns(String pattern, String path, boolean expected) {
            assertThat(RobotsRules.matches(pattern, path)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "pattern=\"{0}\" path=\"{1}\" -> {2}")
        @DisplayName("'$' end-anchor with a preceding '*' correctly matches to end of string")
        @CsvSource({
            "/*.pdf$,   /downloads/report.pdf,   true",
            "/*.pdf$,   /downloads/report.pdfx,  false"
        })
        void matches_dollarAnchorWithWildcard(String pattern, String path, boolean expected) {
            assertThat(RobotsRules.matches(pattern, path)).isEqualTo(expected);
        }

        // SUSPECT (RobotsRules.java:189-219, specifically 215-217): when the
        // pattern has NO '*' at all, the '$' anchor branch checks
        // path.endsWith(lastPart) instead of idx == path.length(). Since the
        // start-match and end-match are checked independently (not tied to the
        // same occurrence), a plain, non-wildcarded "$"-anchored pattern matches
        // any path that merely starts AND ends with that literal text, not only
        // a path that equals it exactly. Pinning the current (surprising) output.
        @Test
        @DisplayName("SUSPECT: a wildcard-free '$' anchor matches a longer path that both starts and ends with the pattern text")
        void matches_dollarAnchorWithoutWildcard_matchesLongerPathWithSameStartAndEnd() {
            // Naively one would expect only the exact path "/foo" to match "/foo$".
            assertThat(RobotsRules.matches("/foo$", "/foo")).isTrue();
            assertThat(RobotsRules.matches("/foo$", "/foo/bar/foo")).isTrue();
            // A path that starts but does not also end with the literal text still
            // correctly fails, showing the check is not a no-op.
            assertThat(RobotsRules.matches("/foo$", "/foo/bar")).isFalse();
        }

        // SUSPECT (RobotsRules.java:195-206, 215-216): when the pattern ends in
        // "*$" (a wildcard immediately followed by the end anchor), splitting on
        // '*' produces a trailing empty part, and idx is never advanced past the
        // literal prefix (there is no further non-empty part to advance it), so
        // the idx == path.length() check effectively demands the path be EXACTLY
        // the literal prefix. The trailing '*' - which should mean "then anything
        // until the end" - is silently neutered: it does not accept any extra
        // trailing characters at all.
        @Test
        @DisplayName("SUSPECT: a trailing '*$' pattern fails to match any path longer than its literal prefix")
        void matches_trailingWildcardThenDollarAnchor_rejectsLongerPaths() {
            // Naively "abc*$" reads like "starts with abc, then anything" - one
            // would expect this to match "abcdef" and "abc" both.
            assertThat(RobotsRules.matches("abc*$", "abc")).isTrue();
            assertThat(RobotsRules.matches("abc*$", "abcdef")).isFalse();
        }
    }

    // ------------------------------------------------------------ Verdict fields

    @Nested
    @DisplayName("Verdict fields")
    class VerdictFieldTests {

        @Test
        @DisplayName("allowed, matchedRule and namedExplicitly are all populated for a matched Disallow")
        void evaluate_verdict_populatesAllFieldsOnMatch() {
            RobotsRules r = RobotsRules.parse("User-agent: GPTBot\nDisallow: /no-ai\n");

            Verdict v = r.evaluate("GPTBot", "/no-ai/page");

            assertThat(v.allowed).isFalse();
            assertThat(v.matchedGroup).isEqualTo("gptbot");
            assertThat(v.matchedRule).isEqualTo("Disallow: /no-ai");
            assertThat(v.namedExplicitly).isTrue();
        }

        @Test
        @DisplayName("matchedRule is null and allowed is true when nothing in the group matches the path")
        void evaluate_verdict_noRuleMatches_defaultsToAllowedWithNullMatchedRule() {
            RobotsRules r = RobotsRules.parse("User-agent: GPTBot\nDisallow: /no-ai\n");

            Verdict v = r.evaluate("GPTBot", "/public/page");

            assertThat(v.allowed).isTrue();
            assertThat(v.matchedRule).isNull();
            assertThat(v.matchedGroup).isEqualTo("gptbot");
            assertThat(v.namedExplicitly).isTrue();
        }
    }

    // ----------------------------------------------------------------- empty()

    @Nested
    @DisplayName("empty()")
    class EmptyTests {

        @Test
        @DisplayName("an absent robots.txt allows any agent and any path")
        void empty_allowsAnyAgentAndPath() {
            RobotsRules r = RobotsRules.empty();

            Verdict v = r.evaluate("GPTBot", "/anything/at/all");

            assertThat(r.isParsed()).isFalse();
            assertThat(v.allowed).isTrue();
            assertThat(v.matchedGroup).isNull();
            assertThat(v.matchedRule).isNull();
            assertThat(v.namedExplicitly).isFalse();
        }

        @Test
        @DisplayName("an absent robots.txt has no declared agents and no sitemaps")
        void empty_hasNoDeclaredAgentsOrSitemaps() {
            RobotsRules r = RobotsRules.empty();

            assertThat(r.getDeclaredAgents()).isEmpty();
            assertThat(r.getSitemaps()).isEmpty();
            assertThat(r.namesAgent("GPTBot")).isFalse();
        }
    }
}
