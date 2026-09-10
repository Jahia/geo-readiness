package org.jahia.se.modules.georeadiness.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Merges per-crawler decisions into an existing robots.txt.
 *
 * The file belongs to whoever wrote it. This class touches the groups for the
 * AI crawlers it was asked about and leaves everything else byte for byte as it
 * found it: the wildcard group, comments, blank lines, Sitemap lines, crawl
 * delays, and any group naming an agent we do not manage.
 *
 * Two decisions, and they are not symmetric on purpose:
 *
 *   block - the caller wants this crawler out. Its Allow/Disallow rules are
 *           replaced with a single "Disallow: /". Destructive, and meant to be.
 *
 *   allow - the caller wants this crawler in. We only act when the group
 *           currently blocks the whole site. A group with path-level rules such
 *           as "Disallow: /docs/" already allows the crawler, just not
 *           everywhere, and flattening that to "Allow: /" would silently throw
 *           away the operator's work.
 *
 * When a group names several crawlers and only one of them changes, the token
 * is split out into its own group so its siblings keep the rules they had.
 */
public final class RobotsEditor {

    public static final String ALLOW = "allow";
    public static final String BLOCK = "block";

    private static final String UA = "user-agent:";
    private static final String ALLOW_ALL = "Allow: /";
    private static final String DISALLOW_ALL = "Disallow: /";

    private RobotsEditor() {
    }

    /**
     * @param original  the stored robots.txt, may be null or empty
     * @param decisions crawler token to {@link #ALLOW} or {@link #BLOCK}; tokens
     *                  absent from the map are not managed and never touched
     * @return the merged file
     */
    public static String apply(String original, Map<String, String> decisions) {
        List<Block> blocks = parse(original == null ? "" : original);

        for (Map.Entry<String, String> d : decisions.entrySet()) {
            String token = d.getKey();
            String want = d.getValue();
            if (token == null || token.trim().isEmpty()
                    || (!ALLOW.equals(want) && !BLOCK.equals(want))) {
                continue;
            }
            applyOne(blocks, token.trim(), want);
        }

        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            b.render(sb);
        }
        // Exactly one trailing newline. Without this, a no-op merge would append
        // one every time it ran, and "nothing changed" would still show a diff.
        String out = sb.toString().replaceAll("\\n+$", "");
        return out.isEmpty() ? out : out + "\n";
    }

    /** What the file says today for each token, so the UI can show the starting point. */
    public static Map<String, String> currentDecisions(String original, Iterable<String> tokens) {
        RobotsRules rules = RobotsRules.parse(original == null ? "" : original);
        Map<String, String> out = new LinkedHashMap<>();
        for (String t : tokens) {
            out.put(t, rules.evaluate(t, "/").allowed ? ALLOW : BLOCK);
        }
        return out;
    }

    private static void applyOne(List<Block> blocks, String token, String want) {
        Group owner = null;
        for (Block b : blocks) {
            if (b instanceof Group && ((Group) b).names(token)) {
                owner = (Group) b;
                break;
            }
        }

        if (owner == null) {
            // Not named anywhere. Blocking needs a group; allowing gets one too,
            // because naming the crawler explicitly is the point of the feature.
            blocks.add(newGroup(token, want));
            return;
        }

        if (ALLOW.equals(want) && !owner.blocksEverything()) {
            // Already allowed, possibly with path rules we must not flatten.
            return;
        }

        if (owner.agentCount() == 1) {
            owner.replaceRules(want);
            return;
        }

        // Shared group. Take this token out so its siblings keep their rules.
        owner.removeAgent(token);
        blocks.add(newGroup(token, want));
    }

    private static Group newGroup(String token, String want) {
        Group g = new Group();
        g.appended = true;
        g.agentLines.add("User-agent: " + token);
        g.ruleLines.add(BLOCK.equals(want) ? DISALLOW_ALL : ALLOW_ALL);
        return g;
    }

    // ---- parsing ----

    private static List<Block> parse(String text) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int i = 0;
        Raw pending = new Raw();

        while (i < lines.length) {
            if (isAgentLine(lines[i])) {
                if (!pending.lines.isEmpty()) {
                    blocks.add(pending);
                    pending = new Raw();
                }
                Group g = new Group();
                while (i < lines.length && isAgentLine(lines[i])) {
                    g.agentLines.add(lines[i]);
                    i++;
                }
                // Everything up to the next User-agent line belongs to this group.
                // Comments and blanks inside it are kept where they are.
                while (i < lines.length && !isAgentLine(lines[i])) {
                    g.ruleLines.add(lines[i]);
                    i++;
                }
                // Blank lines are NOT trimmed here. They are the separators between
                // groups, and dropping them makes a second merge differ from the
                // first, which would show as a phantom diff.
                blocks.add(g);
            } else {
                pending.lines.add(lines[i]);
                i++;
            }
        }
        if (!pending.lines.isEmpty()) {
            blocks.add(pending);
        }
        return blocks;
    }

    private static boolean isAgentLine(String line) {
        return stripComment(line).toLowerCase(Locale.ROOT).startsWith(UA);
    }

    private static String stripComment(String line) {
        int h = line.indexOf('#');
        return (h >= 0 ? line.substring(0, h) : line).trim();
    }

    private static String valueOf(String line) {
        String s = stripComment(line);
        int c = s.indexOf(':');
        return c < 0 ? "" : s.substring(c + 1).trim();
    }

    private static boolean isRuleLine(String line) {
        String s = stripComment(line).toLowerCase(Locale.ROOT);
        return s.startsWith("allow:") || s.startsWith("disallow:");
    }

    // ---- blocks ----

    private interface Block {
        void render(StringBuilder sb);
    }

    /** Comments, blanks, Sitemap lines: anything outside a group. Never modified. */
    private static final class Raw implements Block {
        final List<String> lines = new ArrayList<>();

        @Override
        public void render(StringBuilder sb) {
            for (String l : lines) {
                sb.append(l).append("\n");
            }
        }
    }

    private static final class Group implements Block {
        /** Set only on groups this class adds, so they can space themselves off the previous one. */
        boolean appended;
        final List<String> agentLines = new ArrayList<>();
        final List<String> ruleLines = new ArrayList<>();

        boolean names(String token) {
            for (String l : agentLines) {
                if (valueOf(l).equalsIgnoreCase(token)) {
                    return true;
                }
            }
            return false;
        }

        int agentCount() {
            return agentLines.size();
        }

        void removeAgent(String token) {
            agentLines.removeIf(l -> valueOf(l).equalsIgnoreCase(token));
        }

        /** True when the group shuts the whole site, which is the only case "allow" may undo. */
        boolean blocksEverything() {
            for (String l : ruleLines) {
                String s = stripComment(l).toLowerCase(Locale.ROOT);
                if (s.startsWith("disallow:") && "/".equals(valueOf(l))) {
                    return true;
                }
            }
            return false;
        }

        /** Swap the Allow/Disallow lines for one directive, keeping comments and other rules. */
        void replaceRules(String want) {
            String directive = BLOCK.equals(want) ? DISALLOW_ALL : ALLOW_ALL;
            int insertAt = -1;
            for (int i = 0; i < ruleLines.size(); i++) {
                if (isRuleLine(ruleLines.get(i))) {
                    insertAt = i;
                    break;
                }
            }
            ruleLines.removeIf(RobotsEditor::isRuleLine);
            if (insertAt < 0 || insertAt > ruleLines.size()) {
                insertAt = 0;
            }
            ruleLines.add(insertAt, directive);
        }

        @Override
        public void render(StringBuilder sb) {
            if (appended && sb.length() > 0 && !sb.toString().endsWith("\n\n")) {
                sb.append("\n");
            }
            for (String l : agentLines) {
                sb.append(l).append("\n");
            }
            for (String l : ruleLines) {
                sb.append(l).append("\n");
            }
        }
    }
}
