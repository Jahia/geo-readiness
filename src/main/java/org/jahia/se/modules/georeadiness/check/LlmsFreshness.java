package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

    /**
     * One key name, two shapes, which is worth naming once rather than four
     * times. check() writes it as a COUNT of the pages llms.txt lists;
     * listingFor() writes it as a BOOLEAN saying whether one page is among them.
     * Different objects, read by different callers - but a reader that confuses
     * them gets a silent wrong answer, which is exactly what happened to this
     * class's "stale" and "outdated" in GeoReport.
     */
    private static final String LISTED = "listed";

    private static final String PRESENT = "present";
    private static final String OUTDATED = "outdated";
    private static final String PATH = "path";
    private static final String TITLE = "title";

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
        out.put(PRESENT, present);
        // Both sides trimmed, which they were not: served used trim() and
        // generated did not, so a generated body of nothing but whitespace fell
        // through to the comparison, found no links in it, and reported every
        // page llms.txt lists as stale with why="gone" - the loudest possible
        // answer to "we could not generate anything".
        if (!present || generated == null || generated.trim().isEmpty()) {
            // Absent is already reported as its own check on the site files. Not
            // being able to generate is not a staleness finding either. Only
            // these two keys are written on this path, which a reader depends on.
            out.put(OUTDATED, false);
            return out;
        }

        Map<String, String> current = linksOf(generated);
        Map<String, String> listed = linksOf(served);
        out.put(LISTED, listed.size());
        out.put("current", current.size());

        JSONArray stale = staleOf(listed, current, published, sitePath);
        JSONArray missing = missingOf(listed, current);

        // The listed paths themselves, so the drawer can answer "is this page in
        // llms.txt" definitively rather than inferring it from the findings.
        //
        // Capped like stale and missing are. It used to rely on the generator's
        // own link budget to keep it small, which says nothing about the SERVED
        // file - that one is written by whoever wrote it, and a file listing ten
        // thousand paths would have put all ten thousand in every report.
        out.put("listedPaths", new JSONArray(capped(listed.keySet())));
        out.put("stale", stale);
        out.put("missing", missing);
        out.put(OUTDATED, stale.length() > 0 || missing.length() > 0);
        return out;
    }

    /** At most MAX_REPORTED entries, in the order the file listed them. */
    private static List<String> capped(Collection<String> paths) {
        List<String> out = new ArrayList<>(paths);
        return out.size() <= MAX_REPORTED ? out : out.subList(0, MAX_REPORTED);
    }

    /**
     * Pages the served file lists that the generator would no longer produce.
     *
     * Each carries WHY, because why it is no longer right decides what an editor
     * does about it: a page that still exists was dropped from the map, one that
     * moved needs the new address, and one that is gone needs the line removed.
     */
    private static JSONArray staleOf(Map<String, String> listed, Map<String, String> current,
            Map<String, PublishedMap.Entry> published, String sitePath) {
        JSONArray stale = new JSONArray();
        for (Map.Entry<String, String> e : listed.entrySet()) {
            if (current.containsKey(e.getKey())) {
                continue;
            }
            if (stale.length() >= MAX_REPORTED) {
                break;
            }
            JSONObject r = new JSONObject();
            r.put(PATH, e.getKey());
            r.put(TITLE, e.getValue());
            r.put("why", whyStale(published, sitePath, e.getKey()));
            stale.put(r);
        }
        return stale;
    }

    private static String whyStale(Map<String, PublishedMap.Entry> published, String sitePath, String path) {
        if (published != null && published.get(path) != null) {
            return "noLongerListed";
        }
        if (PublishedMap.movedFrom(published, sitePath, path) != null) {
            return "addressChanged";
        }
        return "gone";
    }

    /**
     * Pages the generator would produce that the served file does not list.
     *
     * A pure diff of the two link sets: the published map is not consulted here,
     * only in {@link #whyStale}.
     */
    private static JSONArray missingOf(Map<String, String> listed, Map<String, String> current) {
        JSONArray missing = new JSONArray();
        for (Map.Entry<String, String> e : current.entrySet()) {
            if (!listed.containsKey(e.getKey()) && missing.length() < MAX_REPORTED) {
                JSONObject r = new JSONObject();
                r.put(PATH, e.getKey());
                r.put(TITLE, e.getValue());
                missing.put(r);
            }
        }
        return missing;
    }

    /**
     * What the drawer should say about one page's place in llms.txt.
     *
     * Three answers, not two. Listed is the good case. Not listed *while the
     * generator would include it* means the file is behind and regenerating
     * fixes it. Not listed *because the generator never includes it* is the
     * design working - llms.txt is a short map of the important pages, not an
     * index - and telling an author to act on that would be wrong.
     */
    public static JSONObject listingFor(JSONObject report, String path) {
        if (report == null || !report.optBoolean(PRESENT, false) || path == null) {
            return null;
        }
        JSONArray listed = report.optJSONArray("listedPaths");
        for (int i = 0; listed != null && i < listed.length(); i++) {
            if (path.equals(listed.optString(i))) {
                JSONObject out = new JSONObject();
                out.put(LISTED, true);
                return out;
            }
        }
        JSONArray wouldAdd = report.optJSONArray("missing");
        for (int i = 0; wouldAdd != null && i < wouldAdd.length(); i++) {
            JSONObject r = wouldAdd.optJSONObject(i);
            if (r != null && path.equals(r.optString("path", null))) {
                JSONObject out = new JSONObject();
                out.put(LISTED, false);
                out.put("wouldAdd", true);
                return out;
            }
        }
        JSONObject out = new JSONObject();
        out.put(LISTED, false);
        out.put("wouldAdd", false);
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
