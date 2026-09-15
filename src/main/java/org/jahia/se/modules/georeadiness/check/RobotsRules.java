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
        List<String> currentAgents = new ArrayList<>();
        boolean lastLineWasAgent = false;

        for (String raw : body.split("\r?\n")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String field = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();

            switch (field) {
                case "user-agent":
                    if (!lastLineWasAgent) {
                        currentAgents = new ArrayList<>();
                    }
                    currentAgents.add(value.toLowerCase(Locale.ROOT));
                    r.declaredAgents.add(value);
                    r.groups.computeIfAbsent(value.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
                    lastLineWasAgent = true;
                    break;
                case "allow":
                case "disallow":
                    boolean allow = "allow".equals(field);
                    for (String a : currentAgents) {
                        r.groups.computeIfAbsent(a, k -> new ArrayList<>()).add(new Rule(allow, value));
                    }
                    lastLineWasAgent = false;
                    break;
                case "sitemap":
                    r.sitemaps.add(value);
                    lastLineWasAgent = false;
                    break;
                default:
                    lastLineWasAgent = false;
            }
        }
        return r;
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

        String group = null;
        for (String candidate : groups.keySet()) {
            if ("*".equals(candidate)) {
                continue;
            }
            // A robots token matches when the declared name is a prefix of the crawler
            // token, or the reverse. Crawlers are lenient here and so are we.
            if (lower.startsWith(candidate) || candidate.startsWith(lower)) {
                if (group == null || candidate.length() > group.length()) {
                    group = candidate;
                }
            }
        }
        boolean named = group != null;
        if (group == null) {
            group = groups.containsKey("*") ? "*" : null;
        }
        if (group == null) {
            return new Verdict(true, null, null, false);
        }

        Rule best = null;
        for (Rule rule : groups.get(group)) {
            if (rule.pattern.isEmpty()) {
                continue;   // "Disallow:" with no value means allow everything
            }
            if (matches(rule.pattern, path)) {
                if (best == null
                        || rule.pattern.length() > best.pattern.length()
                        || (rule.pattern.length() == best.pattern.length() && rule.allow)) {
                    best = rule;
                }
            }
        }
        if (best == null) {
            return new Verdict(true, group, null, named);
        }
        return new Verdict(best.allow, group, (best.allow ? "Allow: " : "Disallow: ") + best.pattern, named);
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
