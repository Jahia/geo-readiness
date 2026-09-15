package org.jahia.se.modules.georeadiness.util;

/**
 * Finding tags in a document this module did not write.
 *
 * Every check here reads markup fetched from somewhere else, and the obvious
 * way to find a tag - a pattern like `<img\s[^>]*>` - is linear on real markup
 * and quadratic on a megabyte of `<img ` that never closes: the engine re-runs
 * the same hopeless scan from every position a tag could have started at. An
 * editor pressing a button would wait, and the server would spend the time.
 *
 * Bounding the pattern instead trades one bug for another. Real tags are longer
 * than they look - a single `srcset` runs to hundreds of characters, an inline
 * data URL to thousands - so any bound low enough to be safe is also low enough
 * to silently stop counting images on a real page.
 *
 * So tags are found by walking the document once. Each tag is handed on as a
 * short string of its own, and patterns are left to do what they are good at:
 * reading an attribute out of one tag.
 *
 * Deliberately not an HTML parse. A quoted `>` inside an attribute value ends
 * the tag early here, exactly as it did when a pattern looked for the same
 * thing, and the checks that read these tags are counting and sampling rather
 * than building a tree.
 */
public final class Markup {

    /** One tag, and where it sat: `end` is the index just past its `>`. */
    public interface TagSink {
        void accept(String tag, int start, int end);
    }

    /** One element: its opening tag, and everything up to its closer. */
    public interface ElementSink {
        void accept(String tag, String body, int start);
    }

    private Markup() {
    }

    /**
     * Every tag in the document, opening and closing alike, in order.
     *
     * An unterminated tag ends the walk: there is nothing after it that any
     * reading of the document could turn into markup.
     */
    public static void forEachTag(String html, TagSink sink) {
        int i = 0;
        while (i < html.length()) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                return;
            }
            int gt = html.indexOf('>', lt + 1);
            if (gt < 0) {
                return;
            }
            sink.accept(html.substring(lt, gt + 1), lt, gt + 1);
            i = gt + 1;
        }
    }

    /** The opening tags of one element type. */
    public static void forEachTag(String html, String name, TagSink sink) {
        forEachTag(html, (tag, start, end) -> {
            if (opens(tag, name)) {
                sink.accept(tag, start, end);
            }
        });
    }

    /**
     * Each element of one type with its body.
     *
     * An element that is never closed is not reported, which is what a pattern
     * spanning the pair did too, and nesting of the same name is not tracked:
     * the first closer ends the element.
     */
    public static void forEachElement(String html, String name, ElementSink sink) {
        String closer = "</" + name + ">";
        int i = 0;
        while (i < html.length()) {
            int lt = html.indexOf('<', i);
            if (lt < 0) {
                return;
            }
            int gt = html.indexOf('>', lt + 1);
            if (gt < 0) {
                return;
            }
            if (!opens(html.substring(lt, gt + 1), name)) {
                i = gt + 1;
                continue;
            }
            int close = indexOfIgnoreCase(html, closer, gt + 1);
            if (close < 0) {
                return;
            }
            sink.accept(html.substring(lt, gt + 1), html.substring(gt + 1, close), lt);
            i = close + closer.length();
        }
    }

    /** Whether this tag opens an element of that name. */
    public static boolean opens(String tag, String name) {
        int after = 1 + name.length();
        return tag.length() > after
                && tag.regionMatches(true, 1, name, 0, name.length())
                && (tag.charAt(after) == '>' || tag.charAt(after) == '/'
                    || Character.isWhitespace(tag.charAt(after)));
    }

    /** Whether this tag closes one. */
    public static boolean closes(String tag, String name) {
        return tag.length() > 1 + name.length()
                && tag.charAt(1) == '/'
                && tag.regionMatches(true, 2, name, 0, name.length());
    }

    /** As String.indexOf, ignoring case, so `</SCRIPT>` closes a script. */
    public static int indexOfIgnoreCase(String haystack, String needle, int from) {
        for (int i = Math.max(from, 0); i + needle.length() <= haystack.length(); i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}
