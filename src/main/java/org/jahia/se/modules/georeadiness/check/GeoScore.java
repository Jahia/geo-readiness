package org.jahia.se.modules.georeadiness.check;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Turns the raw report into a small set of pass/fail checks.
 *
 * Deliberately NOT a 0-100 number. Nothing behind such a number would be
 * defensible, and a score an editor cannot argue with is a score they cannot
 * act on. Every check here points at one observed fact in the report, and the
 * UI shows that fact next to the verdict.
 *
 * Three severities:
 *   critical  - an AI crawler cannot read this page at all
 *   important - it can read it, but materially less well than it should
 *   advisory  - worth doing, not worth blocking on
 *
 * One rule is deliberately absent: being disallowed in robots.txt is NOT a
 * failure. Refusing a crawler is a legitimate decision. What IS reported is a
 * disagreement between the policy and what the server actually does, because
 * one of the two is then wrong and somebody owns the fix.
 */
public final class GeoScore {

    /** Below this, the initial html is a shell whoever asks for it. Matches the UI constant. */
    private static final int MIN_WORDS = 50;
    /** A crawler receiving less than half the control's text is looking at a different page. */
    private static final double THIN_RATIO = 0.5;
    private static final int TITLE_MIN = 10;
    private static final int TITLE_MAX = 70;
    private static final double ALT_COVERAGE = 0.8;

    private static final String CRITICAL = "critical";
    private static final String IMPORTANT = "important";
    private static final String ADVISORY = "advisory";

    private static final String ACCESS = "access";
    private static final String CONTENT = "content";
    private static final String FILES = "files";

    private GeoScore() {
    }

    public static JSONObject compute(JSONObject report) {
        JSONArray checks = new JSONArray();

        JSONArray agents = report.optJSONArray("agents");
        JSONObject control = controlHtml(agents);
        int controlWords = report.optInt("controlWords", 0);
        JSONObject files = report.optJSONObject("siteFiles");
        JSONObject robots = files == null ? null : files.optJSONObject("robots");
        JSONObject llms = files == null ? null : files.optJSONObject("llms");

        // ---- Access: can a crawler get the bytes at all ----
        int blocked = report.optInt("blockedCount", 0);
        add(checks, "reachable", ACCESS, CRITICAL, blocked == 0, blocked);

        int mismatches = report.optInt("blockedButAllowedCount", 0)
                + report.optInt("reachableButDisallowedCount", 0);
        add(checks, "policyMatchesReality", ACCESS, IMPORTANT, mismatches == 0, mismatches);

        boolean redirect = control != null && !control.isNull("metaRefresh");
        add(checks, "noClientRedirect", ACCESS, CRITICAL, !redirect,
                redirect ? control.optString("metaRefresh") : null);

        int named = robots == null ? 0 : robots.optInt("namedAiBotCount", 0);
        add(checks, "namedInRobots", ACCESS, ADVISORY, named > 0, named);

        // GEO-19. A page a guest cannot read is unreadable to every crawler for
        // good, whatever the fetch returned: some servers answer a gated page
        // with a login form and a cheerful 200. Only added when the repository
        // actually answered, so an unavailable check never reads as a failure.
        JSONObject vis = report.optJSONObject("visibility");
        if (vis != null && vis.has("guestReadable")) {
            boolean guestReadable = vis.optBoolean("guestReadable", true);
            add(checks, "guestReadable", ACCESS, CRITICAL, guestReadable,
                    guestReadable ? null : vis.optString("kind", null));
        }

        // ---- Content: is what arrives actually usable ----
        add(checks, "contentInInitialHtml", CONTENT, CRITICAL, controlWords >= MIN_WORDS, controlWords);

        int thin = 0;
        if (agents != null && controlWords > 0) {
            for (int i = 0; i < agents.length(); i++) {
                JSONObject h = agents.getJSONObject(i).optJSONObject("html");
                if (h != null && h.optInt("words", 0) < controlWords * THIN_RATIO) {
                    thin++;
                }
            }
        }
        add(checks, "sameContentForCrawlers", CONTENT, CRITICAL, thin == 0, thin);

        String title = control == null || control.isNull("title") ? null : control.optString("title");
        int titleLen = title == null ? 0 : title.length();
        add(checks, "title", CONTENT, IMPORTANT,
                title != null && titleLen >= TITLE_MIN && titleLen <= TITLE_MAX, titleLen);

        int h1 = control == null ? 0 : control.optInt("h1Count", 0);
        add(checks, "singleH1", CONTENT, IMPORTANT, h1 == 1, h1);

        int h2 = control == null ? 0 : control.optInt("h2Count", 0);
        add(checks, "headingOutline", CONTENT, ADVISORY, h2 > 0, h2);

        add(checks, "metaDescription", CONTENT, IMPORTANT,
                control != null && control.optBoolean("metaDescription", false), null);

        add(checks, "canonical", CONTENT, IMPORTANT,
                control != null && control.optBoolean("canonical", false), null);

        String lang = control == null || control.isNull("lang") ? null : control.optString("lang");
        add(checks, "langDeclared", CONTENT, IMPORTANT, lang != null, lang);

        JSONArray types = control == null ? null : control.optJSONArray("jsonLdTypes");
        boolean hasSchema = types != null && types.length() > 0;
        add(checks, "structuredData", CONTENT, IMPORTANT, hasSchema,
                hasSchema ? join(types) : null);

        boolean fresh = control != null && !control.isNull("dateModified");
        add(checks, "freshness", CONTENT, ADVISORY, fresh,
                fresh ? control.optString("dateModified") : null);

        int images = control == null ? 0 : control.optInt("images", 0);
        int withAlt = control == null ? 0 : control.optInt("imagesWithAlt", 0);
        // No images is not a failure. It is simply nothing to describe.
        boolean altOk = images == 0 || withAlt >= images * ALT_COVERAGE;
        add(checks, "imageAlt", CONTENT, ADVISORY, altOk, images == 0 ? null : withAlt + "/" + images);

        // ---- Site files ----
        add(checks, "robotsPresent", FILES, IMPORTANT,
                robots != null && robots.optBoolean("present", false), null);
        add(checks, "llmsPresent", FILES, ADVISORY,
                llms != null && llms.optBoolean("present", false), null);

        int passed = 0;
        int criticalFailed = 0;
        int importantFailed = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject c = checks.getJSONObject(i);
            if (c.getBoolean("passed")) {
                passed++;
            } else if (CRITICAL.equals(c.getString("severity"))) {
                criticalFailed++;
            } else if (IMPORTANT.equals(c.getString("severity"))) {
                importantFailed++;
            }
        }

        JSONObject out = new JSONObject();
        out.put("passed", passed);
        out.put("total", checks.length());
        out.put("criticalFailed", criticalFailed);
        out.put("importantFailed", importantFailed);
        out.put("checks", checks);
        return out;
    }

    /**
     * The control is the first agent that answered 200. Everything is judged
     * against what a normal browser received, not against an ideal.
     */
    private static JSONObject controlHtml(JSONArray agents) {
        if (agents == null) {
            return null;
        }
        for (int i = 0; i < agents.length(); i++) {
            JSONObject a = agents.getJSONObject(i);
            if (a.optInt("status", 0) == 200 && a.optJSONObject("html") != null) {
                return a.getJSONObject("html");
            }
        }
        return null;
    }

    private static String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(a.getString(i));
        }
        return sb.toString();
    }

    /** `value` is the observed fact the UI shows next to the verdict. Never a sentence: the UI translates. */
    private static void add(JSONArray checks, String id, String group, String severity, boolean passed, Object value) {
        JSONObject c = new JSONObject();
        c.put("id", id);
        c.put("group", group);
        c.put("severity", severity);
        c.put("passed", passed);
        c.put("value", value == null ? JSONObject.NULL : value);
        checks.put(c);
    }
}
