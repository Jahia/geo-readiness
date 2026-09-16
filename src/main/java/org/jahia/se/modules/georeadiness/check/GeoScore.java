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

    private static final String GUEST_READABLE = "guestReadable";
    private static final String TITLE = "title";
    private static final String PASSED = "passed";
    private static final String SEVERITY = "severity";

    private GeoScore() {
    }

    public static JSONObject compute(JSONObject report) {
        JSONArray checks = new JSONArray();
        Facts facts = Facts.of(report);
        access(checks, facts);
        content(checks, facts);
        files(checks, facts);
        return tally(checks);
    }

    /**
     * Everything the checks read, pulled out of the report once.
     *
     * compute used to open with a dozen lines doing this and then keep all of it
     * as locals, which is most of why Sonar called it a Brain Method with 34
     * variables. Reading it once and passing it around lets each group of checks
     * be its own method without re-deriving the control or re-walking the agents.
     */
    private static final class Facts {
        private final JSONArray agents;
        /** The first agent that answered 200 with html, or null when none did. */
        private final JSONObject control;
        private final int controlWords;
        private final JSONObject robots;
        private final JSONObject llms;
        private final JSONObject visibility;
        private final int blocked;
        private final int mismatches;

        private Facts(JSONObject report) {
            this.agents = report.optJSONArray("agents");
            this.control = controlHtml(agents);
            this.controlWords = report.optInt("controlWords", 0);
            JSONObject siteFiles = report.optJSONObject("siteFiles");
            this.robots = siteFiles == null ? null : siteFiles.optJSONObject("robots");
            this.llms = siteFiles == null ? null : siteFiles.optJSONObject("llms");
            this.visibility = report.optJSONObject("visibility");
            this.blocked = report.optInt("blockedCount", 0);
            this.mismatches = report.optInt("blockedButAllowedCount", 0)
                    + report.optInt("reachableButDisallowedCount", 0);
        }

        static Facts of(JSONObject report) {
            return new Facts(report);
        }

        /** A field of the control's html, or null when there is no control at all. */
        private String text(String key) {
            return control == null || control.isNull(key) ? null : control.optString(key);
        }

        private int number(String key) {
            return control == null ? 0 : control.optInt(key, 0);
        }

        private boolean flag(String key) {
            return control != null && control.optBoolean(key, false);
        }

        private boolean has(String key) {
            return control != null && !control.isNull(key);
        }
    }

    /** Can a crawler get the bytes at all. */
    private static void access(JSONArray checks, Facts f) {
        add(checks, "reachable", ACCESS, CRITICAL, f.blocked == 0, f.blocked);
        add(checks, "policyMatchesReality", ACCESS, IMPORTANT, f.mismatches == 0, f.mismatches);

        boolean redirect = f.has("metaRefresh");
        add(checks, "noClientRedirect", ACCESS, CRITICAL, !redirect,
                redirect ? f.text("metaRefresh") : null);

        int named = f.robots == null ? 0 : f.robots.optInt("namedAiBotCount", 0);
        add(checks, "namedInRobots", ACCESS, ADVISORY, named > 0, named);

        // GEO-19. A page a guest cannot read is unreadable to every crawler for
        // good, whatever the fetch returned: some servers answer a gated page
        // with a login form and a cheerful 200. Added only when the repository
        // actually answered, so an unavailable check never reads as a failure -
        // which is why this asks has() rather than taking a default.
        if (f.visibility != null && f.visibility.has(GUEST_READABLE)) {
            boolean guestReadable = f.visibility.optBoolean(GUEST_READABLE, true);
            add(checks, GUEST_READABLE, ACCESS, CRITICAL, guestReadable,
                    guestReadable ? null : f.visibility.optString("kind", null));
        }
    }

    /** Is what arrives actually usable. */
    private static void content(JSONArray checks, Facts f) {
        add(checks, "contentInInitialHtml", CONTENT, CRITICAL, f.controlWords >= MIN_WORDS, f.controlWords);

        int thin = thinCount(f);
        add(checks, "sameContentForCrawlers", CONTENT, CRITICAL, thin == 0, thin);

        String title = f.text(TITLE);
        int titleLen = title == null ? 0 : title.length();
        add(checks, TITLE, CONTENT, IMPORTANT,
                title != null && titleLen >= TITLE_MIN && titleLen <= TITLE_MAX, titleLen);

        int h1 = f.number("h1Count");
        add(checks, "singleH1", CONTENT, IMPORTANT, h1 == 1, h1);

        int h2 = f.number("h2Count");
        add(checks, "headingOutline", CONTENT, ADVISORY, h2 > 0, h2);

        add(checks, "metaDescription", CONTENT, IMPORTANT, f.flag("metaDescription"), null);
        add(checks, "canonical", CONTENT, IMPORTANT, f.flag("canonical"), null);

        String lang = f.text("lang");
        add(checks, "langDeclared", CONTENT, IMPORTANT, lang != null, lang);

        JSONArray types = f.control == null ? null : f.control.optJSONArray("jsonLdTypes");
        boolean hasSchema = types != null && types.length() > 0;
        add(checks, "structuredData", CONTENT, IMPORTANT, hasSchema, hasSchema ? join(types) : null);

        boolean fresh = f.has("dateModified");
        add(checks, "freshness", CONTENT, ADVISORY, fresh, fresh ? f.text("dateModified") : null);

        imageAlt(checks, f);
    }

    /** No images is not a failure. It is simply nothing to describe. */
    private static void imageAlt(JSONArray checks, Facts f) {
        int images = f.number("images");
        int withAlt = f.number("imagesWithAlt");
        boolean altOk = images == 0 || withAlt >= images * ALT_COVERAGE;
        add(checks, "imageAlt", CONTENT, ADVISORY, altOk, images == 0 ? null : withAlt + "/" + images);
    }

    /** How many agents received materially less text than the control did. */
    private static int thinCount(Facts f) {
        if (f.agents == null || f.controlWords <= 0) {
            return 0;
        }
        int thin = 0;
        for (int i = 0; i < f.agents.length(); i++) {
            JSONObject h = f.agents.getJSONObject(i).optJSONObject("html");
            if (h != null && h.optInt("words", 0) < f.controlWords * THIN_RATIO) {
                thin++;
            }
        }
        return thin;
    }

    private static void files(JSONArray checks, Facts f) {
        add(checks, "robotsPresent", FILES, IMPORTANT,
                f.robots != null && f.robots.optBoolean("present", false), null);
        add(checks, "llmsPresent", FILES, ADVISORY,
                f.llms != null && f.llms.optBoolean("present", false), null);
    }

    /** The counts the dashboard and the drawer both quote. */
    private static JSONObject tally(JSONArray checks) {
        int passed = 0;
        int criticalFailed = 0;
        int importantFailed = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject c = checks.getJSONObject(i);
            if (c.getBoolean(PASSED)) {
                passed++;
            } else if (CRITICAL.equals(c.getString(SEVERITY))) {
                criticalFailed++;
            } else if (IMPORTANT.equals(c.getString(SEVERITY))) {
                importantFailed++;
            }
        }

        JSONObject out = new JSONObject();
        out.put(PASSED, passed);
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
        c.put(SEVERITY, severity);
        c.put(PASSED, passed);
        c.put("value", value == null ? JSONObject.NULL : value);
        checks.put(c);
    }
}
