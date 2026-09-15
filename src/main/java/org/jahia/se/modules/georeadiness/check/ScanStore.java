package org.jahia.se.modules.georeadiness.check;

import org.jahia.services.content.JCRCallback;
import org.jahia.se.modules.georeadiness.util.SiteScope;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.jcr.PathNotFoundException;
import javax.jcr.NodeIterator;
import java.util.List;
import java.util.ArrayList;

import javax.jcr.RepositoryException;
import java.util.Calendar;

/**
 * Where a site's scan results live.
 *
 * On the site itself, not in systemsite: the work is per site, the numbers
 * belong to that site's people, and deleting a site should take its scan with
 * it. A hidden `nt:unstructured` node carries the whole thing, which needs no
 * CND and therefore cannot fail to register or break Content Editor on a bad
 * deploy. `jmix:nolive` keeps it out of the published workspace, because these
 * numbers are back-office data and have no business being served.
 *
 * What is stored is an aggregate plus the pages that failed something, never a
 * record per page. Measured on a real report, a per-page record is 86 bytes:
 * 50,000 pages across three languages would be 12 MB of it, against 2.5 MB for
 * the failures alone, and the dashboard's four questions (overall score, score
 * by section, worst pages, movement since last run) are all answerable from the
 * smaller shape.
 */
public final class ScanStore {

    public static final String STORE_NODE = "geo-readiness";

    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    /**
     * How long a run may claim to be running before it is treated as abandoned.
     *
     * STATUS_RUNNING is only ever cleared by the scanning thread reaching
     * finishRun or failRun, so a JVM kill, an OOM, an undeploy mid-scan or a
     * cluster failover leaves it set for ever. Without this window the guard in
     * tryBeginRun would then refuse every future scan of that site and language,
     * with no way to clear it from the UI - trading a rare corruption for a
     * permanent wedge. Six hours is well past the worst honest run: 2000 pages
     * at one fetch each, with the fetch timeout at its default.
     */
    private static final long MAX_RUN_AGE_MS = 6L * 60 * 60 * 1000;

    // Config, on the store node itself.
    private static final String CRON = "geoCron";
    private static final String ENABLED = "geoEnabled";
    private static final String SCOPE = "geoScope";
    private static final String BASE_URL = "geoBaseUrl";
    private static final String USER_KEY = "geoUserKey";

    // Run state, per language.
    private static final String STATUS = "geoStatus";
    private static final String STARTED = "geoStartedAt";
    private static final String FINISHED = "geoFinishedAt";
    private static final String DONE_COUNT = "geoPagesDone";
    private static final String TOTAL_COUNT = "geoPagesTotal";
    private static final String AGGREGATE = "geoAggregate";
    private static final String PREVIOUS = "geoPreviousAggregate";
    private static final String FAILURES = "geoFailures";
    private static final String MESSAGE = "geoMessage";
    /**
     * The sitemap comparison is stored beside the score, not inside it. It is
     * not a score: it answers whether the map matches the site, it can be
     * refreshed on its own without walking every page, and putting it in the
     * aggregate meant a sitemap-only refresh would have created a half-built
     * aggregate the score panel would then render as "undefined%".
     */
    private static final String SITEMAP = "geoSitemap";
    private static final String SITEMAP_AT = "geoSitemapAt";
    /** GEO-22, stored beside the score for the same reason as the sitemap. */
    private static final String LINKS = "geoLinks";
    /** GEO-25, likewise: a fact about addresses, not a check a page passes. */
    private static final String VANITY = "geoVanity";
    /** Whether the served llms.txt still matches what generating would produce. */
    private static final String LLMS = "geoLlms";
    /** GEO-24, and the threshold it was computed against. */
    private static final String FRESHNESS = "geoFreshness";
    private static final String STALE_DAYS = "geoStaleDays";
    /** GEO-23: node type to schema.org type, chosen by whoever defined the types. */
    private static final String SCHEMA_MAP = "geoSchemaMap";
    /** The written report, as the provider answered it after parsing. One per language. */
    private static final String REPORT = "geoReport";

    private ScanStore() {
    }

    /** Everything the dashboard needs for one site and language, in one read. */
    public static JSONObject read(String sitePath, String language) throws RepositoryException {
        return inStore(sitePath, (store, session) -> {
            JSONObject out = new JSONObject();
            out.put("cron", str(store, CRON, ""));
            out.put("enabled", bool(store, ENABLED, false));
            out.put("scope", str(store, SCOPE, ""));
            out.put("baseUrl", str(store, BASE_URL, ""));
            out.put("staleDays", (int) num(store, STALE_DAYS));
            out.put("schemaMap", json(store, SCHEMA_MAP));
            out.put("language", language);

            JSONObject run = new JSONObject();
            run.put("status", STATUS_IDLE);
            if (store.hasNode(language)) {
                JCRNodeWrapper l = store.getNode(language);
                String status = str(l, STATUS, STATUS_IDLE);

                // Report an abandoned run as what it is. The dashboard polls
                // every four seconds for as long as it reads "running", so a run
                // whose thread died - JVM kill, undeploy, failover - left the
                // panel showing a progress banner that would never move again,
                // and there is no admin action in the UI to clear it.
                if (STATUS_RUNNING.equals(status) && isAbandoned(l)) {
                    status = STATUS_FAILED;
                    run.put("message", "interrupted");
                }
                run.put("status", status);
                run.put("startedAt", date(l, STARTED));
                run.put("finishedAt", date(l, FINISHED));
                run.put("pagesDone", num(l, DONE_COUNT));
                run.put("pagesTotal", num(l, TOTAL_COUNT));
                if (!run.has("message")) {
                    run.put("message", str(l, MESSAGE, ""));
                }
                run.put("aggregate", json(l, AGGREGATE));
                run.put("previous", json(l, PREVIOUS));
                run.put("failures", json(l, FAILURES));
            }
            out.put("run", run);
            if (store.hasNode(language)) {
                JCRNodeWrapper l = store.getNode(language);
                out.put("sitemap", json(l, SITEMAP));
                out.put("sitemapCheckedAt", date(l, SITEMAP_AT));
                out.put("links", json(l, LINKS));
                out.put("vanity", json(l, VANITY));
                out.put("llms", json(l, LLMS));
                out.put("freshness", json(l, FRESHNESS));
            }
            return out;
        });
    }

    /**
     * `baseUrl` is recorded because the scheduled job has no HTTP request and
     * therefore no way to know the scheme and port the site answers on. It is
     * captured from the request that saved the schedule: the address the site
     * was reachable at when somebody configured this.
     */
    /** One stored schedule, as the lifecycle needs it to reinstall a trigger. */
    public static final class Schedule {
        public final String sitePath;
        public final String language;
        public final String cron;
        public final String scope;
        public final String baseUrl;
        public final String userKey;

        Schedule(String sitePath, String language, String cron, String scope, String baseUrl, String userKey) {
            this.sitePath = sitePath;
            this.language = language;
            this.cron = cron;
            this.scope = scope;
            this.baseUrl = baseUrl;
            this.userKey = userKey;
        }
    }

    /**
     * Every enabled schedule on the instance, read from where it was stored.
     *
     * The triggers themselves do not survive the bundle, so this is what the
     * module reinstalls them from when it starts.
     */
    public static List<Schedule> schedules() throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "default",
                (JCRCallback<List<Schedule>>) session -> {
                    List<Schedule> out = new ArrayList<>();
                    JCRNodeWrapper sites;
                    try {
                        sites = session.getNode("/sites");
                    } catch (PathNotFoundException e) {
                        return out;
                    }
                    NodeIterator it = sites.getNodes();
                    while (it.hasNext()) {
                        JCRNodeWrapper site = (JCRNodeWrapper) it.nextNode();
                        if (!site.isNodeType("jnt:virtualsite") || !site.hasNode(STORE_NODE)) {
                            continue;
                        }
                        JCRNodeWrapper store = site.getNode(STORE_NODE);
                        if (!bool(store, ENABLED, false)) {
                            continue;
                        }
                        String cron = str(store, CRON, "");
                        if (cron.isEmpty()) {
                            continue;
                        }
                        NodeIterator langs = store.getNodes();
                        while (langs.hasNext()) {
                            JCRNodeWrapper lang = (JCRNodeWrapper) langs.nextNode();
                            out.add(new Schedule(site.getPath(), lang.getName(), cron,
                                    str(store, SCOPE, ""), str(store, BASE_URL, ""),
                                    str(store, USER_KEY, "")));
                        }
                    }
                    return out;
                });
    }

    /** Stores the written report beside the measurements it was written from. */
    public static void saveReport(String sitePath, String language, JSONObject report) throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            JCRNodeWrapper l = language(store, language);
            l.setProperty(REPORT, report.toString());
            session.save();
            return null;
        });
    }

    /** The stored report, or null when none was generated for this language. */
    public static JSONObject readReport(String sitePath, String language) throws RepositoryException {
        return inStore(sitePath, (StoreWork<JSONObject>) (store, session) -> {
            if (!store.hasNode(language)) {
                return null;
            }
            JCRNodeWrapper l = store.getNode(language);
            if (!l.hasProperty(REPORT)) {
                return null;
            }
            Object stored = json(l, REPORT);
            return stored instanceof JSONObject ? (JSONObject) stored : null;
        });
    }

    public static void saveConfig(String sitePath, String cron, boolean enabled, String scope, String baseUrl,
            String userKey)
            throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            store.setProperty(CRON, cron == null ? "" : cron.trim());
            store.setProperty(ENABLED, enabled);
            store.setProperty(SCOPE, scope == null ? "" : scope.trim());
            store.setProperty(BASE_URL, baseUrl == null ? "" : baseUrl.trim());
            store.setProperty(USER_KEY, userKey == null ? "" : userKey.trim());
            session.save();
            return null;
        });
    }

    /**
     * Claim the run for this site and language, or report that someone else
     * holds it.
     *
     * Nothing used to check. A double-click on "Scan now", or a cron trigger
     * firing while an editor ran one by hand, started two scans over the same
     * stored state: the second copied the FIRST run's half-written aggregate
     * into PREVIOUS, so "movement since the last run" became meaningless, both
     * raced on progress() and finishRun(), and pagesDone walked backwards in the
     * polling UI.
     *
     * The read, the decision and the write are one inStore callback and one
     * session.save(), so they are a single JCR transaction rather than three
     * separate ones with a window between them. A JVM-local flag would not do:
     * the cron job and the manual run can land on different cluster nodes.
     *
     * @return false when a run younger than {@link #MAX_RUN_AGE_MS} is already
     *         in flight, in which case nothing was written.
     */
    public static boolean tryBeginRun(String sitePath, String language, long total) throws RepositoryException {
        return inStore(sitePath, (store, session) -> {
            JCRNodeWrapper l = language(store, language);

            if (STATUS_RUNNING.equals(str(l, STATUS, STATUS_IDLE)) && !isAbandoned(l)) {
                return false;
            }
            // The previous aggregate is kept before it is overwritten: "movement
            // since the last run" is the only reason to store it at all.
            if (l.hasProperty(AGGREGATE)) {
                l.setProperty(PREVIOUS, l.getProperty(AGGREGATE).getString());
            }
            l.setProperty(STATUS, STATUS_RUNNING);
            l.setProperty(STARTED, Calendar.getInstance());
            l.setProperty(DONE_COUNT, 0L);
            l.setProperty(TOTAL_COUNT, total);
            l.setProperty(MESSAGE, "");
            session.save();
            return true;
        });
    }

    /**
     * True when a run claiming to be running started longer ago than any honest
     * run could last, so the thread that owned it is gone.
     */
    private static boolean isAbandoned(JCRNodeWrapper l) throws RepositoryException {
        if (!l.hasProperty(STARTED)) {
            // Running with no start date is incoherent on its face; let it go.
            return true;
        }
        long startedAt = l.getProperty(STARTED).getDate().getTimeInMillis();
        return System.currentTimeMillis() - startedAt > MAX_RUN_AGE_MS;
    }

    /** Called every N pages, not every page: silence reads as a hang, but so does a write storm. */
    public static void progress(String sitePath, String language, long done) throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            language(store, language).setProperty(DONE_COUNT, done);
            session.save();
            return null;
        });
    }

    public static void finishRun(String sitePath, String language, JSONObject aggregate, JSONArray failures)
            throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            JCRNodeWrapper l = language(store, language);
            l.setProperty(STATUS, STATUS_DONE);
            l.setProperty(FINISHED, Calendar.getInstance());
            l.setProperty(AGGREGATE, aggregate.toString());
            l.setProperty(FAILURES, failures.toString());
            session.save();
            return null;
        });
    }

    /**
     * Records a sitemap comparison on its own, so the tab that shows it can be
     * refreshed without running a site scan and the drawer still reads exactly
     * what the tab shows.
     */
    public static void saveSitemap(String sitePath, String language, JSONObject sitemap)
            throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            JCRNodeWrapper l = language(store, language);
            l.setProperty(SITEMAP, sitemap.toString());
            l.setProperty(SITEMAP_AT, Calendar.getInstance());
            session.save();
            return null;
        });
    }

    /**
     * Records the inbound-link graph. Produced by a scan rather than on its own,
     * because it is built from the HTML of every page the scan fetches.
     */
    public static void saveLinks(String sitePath, String language, JSONObject links)
            throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            JCRNodeWrapper l = language(store, language);
            l.setProperty(LINKS, links.toString());
            session.save();
            return null;
        });
    }

    /** Records the vanity URL findings. Produced by a scan, like the link graph. */
    public static void saveVanity(String sitePath, String language, JSONObject vanity)
            throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            JCRNodeWrapper l = language(store, language);
            l.setProperty(VANITY, vanity.toString());
            session.save();
            return null;
        });
    }

    /** Records whether the published llms.txt is still current. */
    public static void saveLlms(String sitePath, String language, JSONObject llms)
            throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            JCRNodeWrapper l = language(store, language);
            l.setProperty(LLMS, llms.toString());
            session.save();
            return null;
        });
    }

    /**
     * Records the freshness picture and the threshold it was measured against.
     * The threshold lives on the site, not the language, so a scheduled run in
     * any language uses the one somebody chose.
     */
    public static void saveFreshness(String sitePath, String language, JSONObject freshness, int staleDays)
            throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            store.setProperty(STALE_DAYS, staleDays);
            JCRNodeWrapper l = language(store, language);
            l.setProperty(FRESHNESS, freshness.toString());
            session.save();
            return null;
        });
    }

    /**
     * The site's node-type to schema.org-type mapping. Site level, not language
     * level: a type is the same thing in every language.
     */
    public static void saveSchemaMap(String sitePath, JSONObject map) throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            store.setProperty(SCHEMA_MAP, map.toString());
            session.save();
            return null;
        });
    }

    public static void failRun(String sitePath, String language, String message) throws RepositoryException {
        inStore(sitePath, (store, session) -> {
            JCRNodeWrapper l = language(store, language);
            l.setProperty(STATUS, STATUS_FAILED);
            l.setProperty(FINISHED, Calendar.getInstance());
            // Generic on purpose: this string reaches a browser.
            l.setProperty(MESSAGE, message == null ? "" : message);
            session.save();
            return null;
        });
    }

    // ---- plumbing ----

    private interface StoreWork<T> {
        T run(JCRNodeWrapper store, JCRSessionWrapper session) throws RepositoryException;
    }

    /**
     * The store is written with a system session on purpose. A scheduled job has
     * no user, and the numbers describe the whole site rather than anything the
     * caller owns. Reading them is gated at the servlet instead.
     */
    private static <T> T inStore(String sitePath, StoreWork<T> work) throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "default",
                (JCRCallback<T>) session -> {
                    JCRNodeWrapper site = session.getNode(sitePath);
                    JCRNodeWrapper store = site.hasNode(STORE_NODE)
                            ? site.getNode(STORE_NODE)
                            : site.addNode(STORE_NODE, "nt:unstructured");
                    if (!store.isNodeType("jmix:nolive")) {
                        store.addMixin("jmix:nolive");
                    }
                    return work.run(store, session);
                });
    }

    /**
     * The per-language child of the store.
     *
     * JCR reads a relative path the way a filesystem does, so this name is kept
     * to one segment: the servlets check the language they are given, and the
     * scheduled job reaches here without passing one of them.
     */
    private static JCRNodeWrapper language(JCRNodeWrapper store, String language) throws RepositoryException {
        String name = language == null || language.trim().isEmpty() ? "und" : language.trim();
        if (!SiteScope.isLanguage(name)) {
            name = "und";
        }
        return store.hasNode(name) ? store.getNode(name) : store.addNode(name, "nt:unstructured");
    }

    private static String str(JCRNodeWrapper n, String p, String def) throws RepositoryException {
        return n.hasProperty(p) ? n.getProperty(p).getString() : def;
    }

    private static boolean bool(JCRNodeWrapper n, String p, boolean def) throws RepositoryException {
        return n.hasProperty(p) ? n.getProperty(p).getBoolean() : def;
    }

    private static long num(JCRNodeWrapper n, String p) throws RepositoryException {
        return n.hasProperty(p) ? n.getProperty(p).getLong() : 0L;
    }

    private static Object date(JCRNodeWrapper n, String p) throws RepositoryException {
        return n.hasProperty(p) ? n.getProperty(p).getDate().toInstant().toString() : JSONObject.NULL;
    }

    private static Object json(JCRNodeWrapper n, String p) throws RepositoryException {
        if (!n.hasProperty(p)) {
            return JSONObject.NULL;
        }
        try {
            String raw = n.getProperty(p).getString();
            return raw.trim().startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
        } catch (Exception e) {
            return JSONObject.NULL;
        }
    }
}
