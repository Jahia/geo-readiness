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
        String text = original == null ? "" : original;
        List<Block> blocks = parse(text);

        boolean changed = false;
        for (Map.Entry<String, String> d : decisions.entrySet()) {
            String token = d.getKey();
            String want = d.getValue();
            if (token == null || token.trim().isEmpty()
                    || (!ALLOW.equals(want) && !BLOCK.equals(want))) {
                continue;
            }
            changed |= applyOne(blocks, token.trim(), want);
        }

        // Nothing to merge means the file comes back exactly as it arrived.
        //
        // This class promises above to leave what it was not asked about "byte
        // for byte", and rendering could not keep that promise: the trailing
        // newline normalisation below collapsed a file of nothing but blank
        // lines to an empty string - a diff on a file nobody edited. Returning
        // the input is the only way to mean byte for byte.
        if (!changed) {
            return text;
        }

        // The file's own line ending, so an inserted rule does not leave one LF
        // line in the middle of a CRLF file.
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            b.render(sb, eol);
        }
        // Exactly one trailing newline, so merging the same decisions twice does
        // not append another and read as a change.
        String out = sb.toString().replaceAll("(\\r?\\n)+$", "");
        return out.isEmpty() ? out : out + eol;
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

    /** True when the file actually changed, which is what lets a no-op return the input. */
    private static boolean applyOne(List<Block> blocks, String token, String want) {
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
            return true;
        }

        if (ALLOW.equals(want) && !owner.blocksEverything()) {
            // Already allowed, possibly with path rules we must not flatten.
            return false;
        }

        if (owner.agentCount() == 1) {
            owner.replaceRules(want);
            return true;
        }

        // Shared group. Take this token out so its siblings keep their rules.
        owner.removeAgent(token);
        blocks.add(newGroup(token, want));
        return true;
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
        if (text.isEmpty()) {
            // Otherwise the split yields one empty line, which becomes a Raw
            // block of one blank line - and a group appended after it opens the
            // file with two blank lines nobody wrote.
            return blocks;
        }
        String[] lines = text.split("\n", -1);
        Cursor at = new Cursor();
        Raw pending = new Raw();

        while (at.i < lines.length) {
            if (!isAgentLine(lines[at.i])) {
                pending.lines.add(lines[at.i]);
                at.i++;
                continue;
            }
            if (!pending.lines.isEmpty()) {
                blocks.add(pending);
                pending = new Raw();
            }
            blocks.add(group(lines, at));
        }
        if (!pending.lines.isEmpty()) {
            blocks.add(pending);
        }
        return blocks;
    }

    /**
     * One group: its run of consecutive User-agent lines, then everything up to
     * the next one.
     *
     * Blank lines are NOT trimmed. They are the separators between groups, and
     * dropping them would make a second merge differ from the first, which shows
     * as a phantom diff on a file nobody changed. Comments and blanks inside the
     * group are kept exactly where they sit.
     */
    private static Group group(String[] lines, Cursor at) {
        Group g = new Group();
        while (at.i < lines.length && isAgentLine(lines[at.i])) {
            g.agentLines.add(lines[at.i]);
            at.i++;
        }
        while (at.i < lines.length && !isAgentLine(lines[at.i])) {
            g.ruleLines.add(lines[at.i]);
            at.i++;
        }
        return g;
    }

    /** How far through the file the parse has got, shared with {@link #group}. */
    private static final class Cursor {
        private int i;
    }

    private static boolean isAgentLine(String line) {
        return stripComment(line).toLowerCase(Locale.ROOT).startsWith(UA);
    }

    /**
     * A line without the carriage return the split on '\n' left on it, so that
     * render owns the line ending and every line in the output has the same one.
     */
    private static String withoutCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
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
        void render(StringBuilder sb, String eol);
    }

    /** Comments, blanks, Sitemap lines: anything outside a group. Never modified. */
    private static final class Raw implements Block {
        final List<String> lines = new ArrayList<>();

        @Override
        public void render(StringBuilder sb, String eol) {
            for (String l : lines) {
                sb.append(withoutCr(l)).append(eol);
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
        public void render(StringBuilder sb, String eol) {
            if (appended && sb.length() > 0 && !sb.toString().endsWith(eol + eol)) {
                sb.append(eol);
            }
            for (String l : agentLines) {
                sb.append(withoutCr(l)).append(eol);
            }
            for (String l : ruleLines) {
                sb.append(withoutCr(l)).append(eol);
            }
        }
    }
}
