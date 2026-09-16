package org.jahia.se.modules.georeadiness.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A small robots.txt parser, good enough to answer one question:
 * "is this crawler allowed to fetch this path?"
 *
 * It follows what the major crawlers actually do rather than the 1994 draft:
 *  - consecutive User-agent lines share one group
 *  - the most specific matching user-agent group wins (longest token match)
 *  - within a group, the longest matching rule wins; Allow beats Disallow on a tie
 *  - '*' and '$' wildcards are honoured
 *
 * It is deliberately not a full RFC 9309 implementation. Where behaviour is
 * genuinely ambiguous between crawlers we report what we matched, so a human
 * can see the reasoning rather than trust a bare yes or no.
 */
public final class RobotsRules {

    private final Map<String, List<Rule>> groups = new LinkedHashMap<>();
    private final Set<String> declaredAgents = new LinkedHashSet<>();
    private final List<String> sitemaps = new ArrayList<>();
    private final boolean parsed;

    public static final class Rule {
        public final boolean allow;
        public final String pattern;

        Rule(boolean allow, String pattern) {
            this.allow = allow;
            this.pattern = pattern;
        }
    }

    /** What we decided, and why. The "why" matters more than the verdict. */
    public static final class Verdict {
        public final boolean allowed;
        public final String matchedGroup;   // the User-agent group we used, or null
        public final String matchedRule;    // "Disallow: /cms/", or null when nothing matched
        public final boolean namedExplicitly;

        Verdict(boolean allowed, String matchedGroup, String matchedRule, boolean namedExplicitly) {
            this.allowed = allowed;
            this.matchedGroup = matchedGroup;
            this.matchedRule = matchedRule;
            this.namedExplicitly = namedExplicitly;
        }
    }

    private RobotsRules(boolean parsed) {
        this.parsed = parsed;
    }

    public static RobotsRules empty() {
        return new RobotsRules(false);
    }

    public static RobotsRules parse(String body) {
        RobotsRules r = new RobotsRules(true);
        if (body == null) {
            return r;
        }
        // Consecutive User-agent lines share one rule block, which is what this
        // flag tracks: a directive between them ends the run and the next agent
        // line starts a fresh group.
        Parsing state = new Parsing();
        for (String raw : body.split("\r?\n")) {
            String line = strip(raw);
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            directive(r, state, field, value);
        }
        return r;
    }

    /** A line with its comment removed and its edges trimmed; empty when there is nothing left. */
    private static String strip(String raw) {
        String line = raw;
        int hash = line.indexOf('#');
        if (hash >= 0) {
            line = line.substring(0, hash);
        }
        return line.trim();
    }

    /** What carries between lines: which agents the next rule applies to. */
    private static final class Parsing {
        private List<String> agents = new ArrayList<>();
        private boolean lastLineWasAgent;
    }

    private static void directive(RobotsRules r, Parsing state, String field, String value) {
        switch (field) {
            case "user-agent":
                agentLine(r, state, value);
                break;
            case "allow":
            case "disallow":
                ruleLine(r, state, "allow".equals(field), value);
                break;
            case "sitemap":
                r.sitemaps.add(value);
                state.lastLineWasAgent = false;
                break;
            default:
                state.lastLineWasAgent = false;
        }
    }

    private static void agentLine(RobotsRules r, Parsing state, String value) {
        if (!state.lastLineWasAgent) {
            state.agents = new ArrayList<>();
        }
        String token = value.toLowerCase(Locale.ROOT);
        state.agents.add(token);
        r.declaredAgents.add(value);
        r.groups.computeIfAbsent(token, k -> new ArrayList<>());
        state.lastLineWasAgent = true;
    }

    private static void ruleLine(RobotsRules r, Parsing state, boolean allow, String value) {
        for (String a : state.agents) {
            r.groups.computeIfAbsent(a, k -> new ArrayList<>()).add(new Rule(allow, value));
        }
        state.lastLineWasAgent = false;
    }

    public boolean isParsed() {
        return parsed;
    }

    public Set<String> getDeclaredAgents() {
        return declaredAgents;
    }

    public List<String> getSitemaps() {
        return sitemaps;
    }

    /** True when this exact crawler token has its own group, rather than falling under '*'. */
    public boolean namesAgent(String token) {
        return groups.containsKey(token.toLowerCase(Locale.ROOT));
    }

    /**
     * @param token the crawler's robots token, e.g. "GPTBot", not the full user-agent string
     * @param path  the url path being fetched, e.g. "/en/home.html"
     */
    public Verdict evaluate(String token, String path) {
        if (!parsed) {
            // No robots.txt at all means everything is allowed. That is the spec,
            // and it is also a finding worth showing.
            return new Verdict(true, null, null, false);
        }
        String lower = token.toLowerCase(Locale.ROOT);

        String own = ownGroup(lower);
        boolean named = own != null;
        String group = named ? own : wildcardGroup();
        if (group == null) {
            return new Verdict(true, null, null, false);
        }

        Rule best = longestMatch(groups.get(group), path);
        if (best == null) {
            return new Verdict(true, group, null, named);
        }
        return new Verdict(best.allow, group, (best.allow ? "Allow: " : "Disallow: ") + best.pattern, named);
    }

    /**
     * The group that names this agent, or null when only '*' covers it.
     *
     * A robots token matches when the declared name is a prefix of the crawler
     * token, or the reverse: crawlers are lenient here and so are we. The
     * longest match wins, so a file naming both 'gpt' and 'gptbot' gives GPTBot
     * the more specific of the two.
     */
    private String ownGroup(String lower) {
        String group = null;
        for (String candidate : groups.keySet()) {
            if ("*".equals(candidate)) {
                continue;
            }
            boolean matches = lower.startsWith(candidate) || candidate.startsWith(lower);
            if (matches && (group == null || candidate.length() > group.length())) {
                group = candidate;
            }
        }
        return group;
    }

    private String wildcardGroup() {
        return groups.containsKey("*") ? "*" : null;
    }

    /**
     * The rule that decides, by the spec's own tie-breaks: the longest pattern
     * wins, and Allow wins a tie against Disallow.
     */
    private static Rule longestMatch(List<Rule> rules, String path) {
        Rule best = null;
        for (Rule rule : rules) {
            if (rule.pattern.isEmpty()) {
                // "Disallow:" with no value means allow everything.
                continue;
            }
            if (matches(rule.pattern, path) && beats(rule, best)) {
                best = rule;
            }
        }
        return best;
    }

    private static boolean beats(Rule rule, Rule best) {
        if (best == null) {
            return true;
        }
        if (rule.pattern.length() != best.pattern.length()) {
            return rule.pattern.length() > best.pattern.length();
        }
        return rule.allow;
    }

    /**
     * Prefix match with '*' as any-run and '$' as end-anchor.
     *
     * The '$' half was wrong in two OPPOSITE directions, because it decided what
     * to do from the last part of the split and had both branches the wrong way
     * round:
     *
     *   '/foo$'  has no '*', so the last part is the whole pattern. It checked
     *            startsWith at the front and endsWith at the back INDEPENDENTLY,
     *            never tying them to one occurrence - so '/foo/bar/foo' matched
     *            a pattern that means exactly '/foo'.
     *   'abc*$'  splits to ["abc", ""], and the empty last part took the "ends
     *            exactly here" branch - so the trailing '*', which reads as
     *            "then anything", rejected every path longer than 'abc'.
     *
     * An empty last part means the pattern ended with '*': anything may follow,
     * up to the end. A non-empty one has to sit AT the end - and there
     * specifically, which is why it is held back from the forward scan rather
     * than matched greedily with the rest. Taking the first occurrence would
     * reject '/a*b$' against '/axbyb', where the 'b' the scan reaches first is
     * not the one the anchor is about.
     */
    static boolean matches(String pattern, String path) {
        String p = pattern;
        boolean anchored = p.endsWith("$");
        if (anchored) {
            p = p.substring(0, p.length() - 1);
        }
        String[] parts = p.split("\\*", -1);
        // Anchored: the final literal belongs to endsThere, not to this scan.
        int scanned = anchored ? parts.length - 1 : parts.length;
        int idx = 0;
        for (int i = 0; i < scanned; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            if (i == 0) {
                if (!path.startsWith(part)) {
                    return false;
                }
                idx = part.length();
            } else {
                int found = path.indexOf(part, idx);
                if (found < 0) {
                    return false;
                }
                idx = found + part.length();
            }
        }
        return !anchored || endsThere(parts, idx, path);
    }

    /**
     * The '$' half: what the pattern's final part has to do at the end of the
     * path, given the forward scan reached {@code idx}.
     */
    private static boolean endsThere(String[] parts, int idx, String path) {
        String last = parts[parts.length - 1];
        if (last.isEmpty()) {
            // The pattern ended with '*', so anything runs to the end. A lone
            // '$' is the degenerate case: it anchors the empty path and nothing
            // else.
            return parts.length > 1 ? idx <= path.length() : path.isEmpty();
        }
        if (parts.length == 1) {
            // No wildcard anywhere, so the anchor makes the pattern the whole path.
            return path.equals(last);
        }
        int at = path.length() - last.length();
        // Must sit at the very end, and not overlap what the scan already took.
        return at >= idx && path.startsWith(last, at);
    }
}
