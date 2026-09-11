package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether the published `llms.txt` still describes the site it is about.
 *
 * The file is generated once and then served unchanged, so it goes out of date
 * silently: nothing fails, nothing 500s, an assistant is simply handed a map of
 * a site that has moved on. That happened on the test site the moment a vanity
 * url was added - the file kept pointing at `/home/agencies.html`, which now
 * redirects, and there was no signal anywhere that it had.
 *
 * **Compared against the generator, not against a rule of its own.** Asking
 * "which pages should be listed" a second way would be a second implementation
 * to keep in step, and the two would drift. Instead the current file is compared
 * with what regenerating would produce right now. That makes the finding exactly
 * as trustworthy as the button offered to fix it: if they differ, regenerating
 * changes something; if they agree, it would not.
 *
 * Deliberately not tied to the readiness score. Regenerating this file does not
 * move the score - the score is about crawler access and what arrives in the
 * HTML - so prompting for it there would send people to do something that
 * changes nothing they were looking at.
 */
public final class LlmsFreshness {

    private static final Logger logger = LoggerFactory.getLogger(LlmsFreshness.class);

    /** `- [Title](url): description`, the one line shape the generator emits. */
    private static final Pattern LINK = Pattern.compile("(?m)^\\s*-\\s*\\[([^\\]]*)\\]\\(([^)]+)\\)");

    private static final int MAX_REPORTED = 100;

    private LlmsFreshness() {
    }

    /**
     * @param served    the body currently served at /llms.txt, or null when the
     *                  file is absent
     * @param generated what regenerating would produce right now
     */
    public static JSONObject check(String served, String generated, Map<String, PublishedMap.Entry> published,
            String sitePath) {
        JSONObject out = new JSONObject();
        boolean present = served != null && !served.trim().isEmpty();
        out.put("present", present);
        if (!present || generated == null || generated.isEmpty()) {
            // Absent is already reported as its own check on the site files. Not
            // being able to generate is not a staleness finding either.
            out.put("outdated", false);
            return out;
        }

        Map<String, String> current = linksOf(generated);
        Map<String, String> listed = linksOf(served);
        out.put("listed", listed.size());
        out.put("current", current.size());

        JSONArray stale = new JSONArray();
        JSONArray missing = new JSONArray();

        for (Map.Entry<String, String> e : listed.entrySet()) {
            if (current.containsKey(e.getKey())) {
                continue;
            }
            if (stale.length() >= MAX_REPORTED) {
                break;
            }
            // Why it is no longer right decides what an editor does about it.
            PublishedMap.Entry node = published == null ? null : published.get(e.getKey());
            String why;
            if (node != null) {
                why = "noLongerListed";
            } else if (PublishedMap.movedFrom(published, sitePath, e.getKey()) != null) {
                why = "addressChanged";
            } else {
                why = "gone";
            }
            JSONObject r = new JSONObject();
            r.put("path", e.getKey());
            r.put("title", e.getValue());
            r.put("why", why);
            stale.put(r);
        }

        for (Map.Entry<String, String> e : current.entrySet()) {
            if (!listed.containsKey(e.getKey()) && missing.length() < MAX_REPORTED) {
                JSONObject r = new JSONObject();
                r.put("path", e.getKey());
                r.put("title", e.getValue());
                missing.put(r);
            }
        }

        out.put("stale", stale);
        out.put("missing", missing);
        out.put("outdated", stale.length() > 0 || missing.length() > 0);
        return out;
    }

    /** Public path to link text, for every link in a generated file. */
    private static Map<String, String> linksOf(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = LINK.matcher(body);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String path = PublishedMap.pathOf(m.group(2).trim());
            if (seen.add(path)) {
                out.put(path, m.group(1).trim());
            }
        }
        return out;
    }
}
