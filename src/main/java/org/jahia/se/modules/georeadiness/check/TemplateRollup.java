package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GEO-18. Groups findings by the template that produced them, so one fix
 * replaces four hundred edits.
 *
 * No external tool can do this, because none of them know which template
 * rendered a page. That is the whole argument for the story, and it is also
 * what makes the hard part hard: sending somebody to edit a template over one
 * author's typo wastes their afternoon and costs the report its credibility.
 *
 * So attribution is statistical and deliberately cautious. A check is blamed on
 * the template only when it fails on nearly every page that template renders,
 * across enough pages for that to mean anything. Three pages failing out of four
 * hundred is three authors; four hundred out of four hundred is the template.
 * Anything in between is left as a page-level finding, because the cost of a
 * wrong accusation is higher than the cost of a missed roll-up.
 */
public final class TemplateRollup {

    /** Below this many pages a template tells you nothing about itself. */
    private static final int MIN_PAGES = 3;

    /** Nearly all of them. Deliberately high: see the class comment. */
    private static final double TEMPLATE_RATIO = 0.9;

    private TemplateRollup() {
    }

    /** Per-page failures, accumulated as the scan walks the site. */
    public static final class Accumulator {
        private final Map<String, int[]> pageCount = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> failsByTemplate = new LinkedHashMap<>();

        public void add(String template, JSONArray failedChecks) {
            String key = template == null || template.trim().isEmpty() ? "" : template.trim();
            pageCount.computeIfAbsent(key, k -> new int[1])[0]++;
            Map<String, Integer> fails = failsByTemplate.computeIfAbsent(key, k -> new LinkedHashMap<>());
            for (int i = 0; i < failedChecks.length(); i++) {
                fails.merge(failedChecks.getString(i), 1, Integer::sum);
            }
        }

        /**
         * Templates ranked by how many pages they render, so the biggest single
         * fix is first. Each carries the checks that are the template's fault
         * and the checks that merely happen on some of its pages.
         */
        public JSONArray toJson() {
            List<Map.Entry<String, int[]>> ordered = new ArrayList<>(pageCount.entrySet());
            ordered.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));

            JSONArray out = new JSONArray();
            for (Map.Entry<String, int[]> e : ordered) {
                String template = e.getKey();
                int pages = e.getValue()[0];
                Map<String, Integer> fails = failsByTemplate.getOrDefault(template, new LinkedHashMap<>());

                JSONArray fromTemplate = new JSONArray();
                JSONArray fromPages = new JSONArray();
                int pagesWithAnything = 0;
                for (Map.Entry<String, Integer> f : fails.entrySet()) {
                    JSONObject row = new JSONObject();
                    row.put("check", f.getKey());
                    row.put("pages", f.getValue());
                    if (isTemplateWide(pages, f.getValue())) {
                        fromTemplate.put(row);
                    } else {
                        fromPages.put(row);
                    }
                    pagesWithAnything = Math.max(pagesWithAnything, f.getValue());
                }

                JSONObject t = new JSONObject();
                t.put("template", template.isEmpty() ? JSONObject.NULL : template);
                t.put("pages", pages);
                t.put("fromTemplate", fromTemplate);
                t.put("fromPages", fromPages);
                out.put(t);
            }
            return out;
        }
    }

    /**
     * True when a check fails on nearly every page a template renders, over
     * enough pages for the ratio to mean something.
     */
    public static boolean isTemplateWide(int templatePages, int failingPages) {
        return templatePages >= MIN_PAGES && failingPages >= templatePages * TEMPLATE_RATIO;
    }

    /**
     * For one page: which of its failed checks its template is answerable for,
     * read back from a stored scan. This is what lets the drawer tell an author
     * "this one is not yours to fix", which is the only thing worth saying
     * about a template while looking at a single page.
     */
    public static JSONObject forPage(JSONObject aggregate, String template, JSONArray failedChecks) {
        JSONObject out = new JSONObject();
        if (aggregate == null || template == null || template.trim().isEmpty() || failedChecks == null) {
            return out;
        }

        JSONArray templates = aggregate.optJSONArray("templates");
        if (templates == null) {
            return out;
        }

        for (int i = 0; i < templates.length(); i++) {
            JSONObject t = templates.getJSONObject(i);
            if (!template.equals(t.optString("template", null))) {
                continue;
            }

            JSONArray shared = new JSONArray();
            JSONArray fromTemplate = t.optJSONArray("fromTemplate");
            for (int j = 0; fromTemplate != null && j < fromTemplate.length(); j++) {
                String check = fromTemplate.getJSONObject(j).optString("check", "");
                if (contains(failedChecks, check)) {
                    shared.put(check);
                }
            }

            out.put("template", template);
            out.put("pages", t.optInt("pages", 0));
            out.put("sharedChecks", shared);
            return out;
        }
        return out;
    }

    private static boolean contains(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i, null))) {
                return true;
            }
        }
        return false;
    }
}
