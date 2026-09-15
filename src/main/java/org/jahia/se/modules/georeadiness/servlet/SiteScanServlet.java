package org.jahia.se.modules.georeadiness.servlet;

import org.jahia.se.modules.georeadiness.check.GuestVisibility;
import org.jahia.se.modules.georeadiness.check.Freshness;
import org.jahia.se.modules.georeadiness.check.Languages;
import org.jahia.se.modules.georeadiness.check.SchemaMap;
import org.jahia.se.modules.georeadiness.check.StructuredData;
import org.jahia.se.modules.georeadiness.check.ScanInProgressException;
import org.jahia.se.modules.georeadiness.check.ScanStore;
import org.jahia.se.modules.georeadiness.check.SiteScorer;
import org.jahia.se.modules.georeadiness.check.SitemapCheck;
import org.jahia.se.modules.georeadiness.scheduler.ScanScheduler;
import org.jahia.se.modules.georeadiness.scheduler.SiteScanJob;
import org.jahia.se.modules.georeadiness.config.GeoReadinessConfigService;
import org.jahia.se.modules.georeadiness.util.SiteScope;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Site-wide scans that read the repository rather than fetching pages.
 *
 * Read-only, unlike SiteFilesServlet which is the write path. Everything here
 * answers a question about the whole site, which is why it is a separate
 * endpoint from the per-page crawler check: the dashboard calls it, the drawer
 * does not.
 *
 * POST /modules/geo-readiness/site-scan
 *   {"action":"guestVisibility","path":"/sites/x","language":"en"}
 */
@Component(
        service = {HttpServlet.class, Servlet.class},
        property = {
                "alias=/geo-readiness/site-scan",
                "allow-api-token=true",
                "service.description=GEO readiness site scans",
                "service.vendor=Jahia Solutions Group SA"
        },
        immediate = true)
public class SiteScanServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(SiteScanServlet.class);

    private static final int MAX_BODY = 64_000;
    /** Applies to the scans only, never to reading their results. */
    private static final int RATE_MAX_CALLS = 30;
    private static final long RATE_WINDOW_MS = 600_000L;
    private static final int MAX_PAGES = 2000;

    private final Map<String, Deque<Long>> callWindows = new ConcurrentHashMap<>();

    /**
     * Injected through the constructor rather than into the field, so a servlet
     * the container shares between threads holds nothing mutable.
     */
    private final transient GeoReadinessConfigService config;

    @Activate
    public SiteScanServlet(@Reference GeoReadinessConfigService config) {
        this.config = config;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        try {
            dispatch(req, resp);
        } catch (IOException | RuntimeException e) {
            // Nothing may leave a servlet method: the container would answer with
            // a stack trace instead of a response.
            logger.debug("could not write the site scan response", e);
        }
    }

    private void dispatch(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JahiaUser user = currentUser();
        if (user == null || JahiaUserManagerService.GUEST_USERNAME.equals(user.getName())) {
            deny(resp, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        String ctype = req.getContentType();
        if (ctype == null || !ctype.toLowerCase(Locale.ROOT).contains("application/json")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "json required");
            return;
        }
        JSONObject body;
        try {
            body = new JSONObject(read(req.getInputStream(), MAX_BODY));
        } catch (Exception e) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed body");
            return;
        }

        String action = body.optString("action", "");
        String path = body.optString("path", "");
        String language = body.optString("language", "en");
        String scope = body.optString("scope", "").trim();
        if (path.isEmpty() || !path.startsWith("/sites/")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "path required");
            return;
        }
        // The language becomes a node name under a system session further down,
        // so it is a path unless it is checked here.
        if (!SiteScope.isLanguage(language)) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "language required");
            return;
        }

        try {
            // Only the expensive actions are rate limited. Reading the stored
            // state is what the dashboard polls while a scan runs, and limiting
            // that would make a long scan look like a failure after a minute.
            if (("guestVisibility".equals(action) || "runScan".equals(action) || "sitemap".equals(action)
                    || "freshness".equals(action))
                    && !rateLimitOk(user.getUserKey())) {
                deny(resp, 429, "rate limit");
                return;
            }

            // The caller must hold the dashboard's own permission on the site
            // before we tell them anything about it, or act on it. The scans
            // themselves then run with a system session, because reporting what
            // guest CANNOT see is the whole point and a caller-scoped session
            // could not do it.
            String sitePath;
            String within;
            try {
                sitePath = SiteScope.require(path, language, SiteScope.DASHBOARD);
                // A scan reads and stores under a system session, so the subtree
                // it is pointed at is resolved and confirmed to be in the site.
                within = SiteScope.requireWithin(sitePath, scope, language);
            } catch (javax.jcr.PathNotFoundException | javax.jcr.AccessDeniedException e) {
                deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
                return;
            }

            switch (action) {
                case "guestVisibility":
                    writeJson(resp, HttpServletResponse.SC_OK,
                            GuestVisibility.scanSite(sitePath, language, MAX_PAGES));
                    return;
                case "sitemap":
                    // Stored as well as returned, so the drawer reports exactly
                    // what the dashboard shows and a sitemap refresh does not
                    // need a walk of every page.
                    JSONObject sitemap = SitemapCheck.check(sitePath, language,
                            baseUrlFor(sitePath, language, req),
                            config.getFetchTimeoutMs(), config.getMaxBodyBytes());
                    ScanStore.saveSitemap(sitePath, language, sitemap);
                    writeJson(resp, HttpServletResponse.SC_OK, ScanStore.read(sitePath, language));
                    return;
                case "freshness":
                    // A repository query, so it answers now and does not wait
                    // for a walk of the site. Stored as well as returned, so a
                    // scheduled run keeps it current with the same threshold.
                    int staleDays = body.optInt("staleDays", 0);
                    if (staleDays <= 0) {
                        staleDays = ScanStore.read(sitePath, language).optInt("staleDays", 0);
                    }
                    if (staleDays <= 0) {
                        staleDays = Freshness.DEFAULT_STALE_DAYS;
                    }
                    JSONObject freshness = Freshness.check(sitePath, language,
                            baseUrlFor(sitePath, language, req), staleDays);
                    ScanStore.saveFreshness(sitePath, language, freshness, staleDays);
                    writeJson(resp, HttpServletResponse.SC_OK, ScanStore.read(sitePath, language));
                    return;
                case "languages":
                    // Coverage is a repository question and the scores are read
                    // from whatever scans have run, so this needs no scan of
                    // its own and is not rate limited.
                    writeJson(resp, HttpServletResponse.SC_OK, Languages.check(sitePath));
                    return;
                case "schema":
                    // Coverage plus the vocabulary the mapping interface needs,
                    // in one read. No scan: the content model is the source.
                    JSONObject state = ScanStore.read(sitePath, language);
                    JSONObject schema = StructuredData.coverage(sitePath, language,
                            baseUrlFor(sitePath, language, req), state.optJSONObject("schemaMap"));
                    schema.put("vocabulary", SchemaMap.vocabulary());
                    schema.put("schemaMap", state.opt("schemaMap"));
                    writeJson(resp, HttpServletResponse.SC_OK, schema);
                    return;
                case "saveSchemaMap":
                    // A mapping is a statement about content types, so it is
                    // stored and never applied to a page by itself. Generating
                    // and writing stay separate here as everywhere else.
                    JSONObject map = body.optJSONObject("map");
                    ScanStore.saveSchemaMap(sitePath, map == null ? new JSONObject() : map);
                    writeJson(resp, HttpServletResponse.SC_OK, ScanStore.read(sitePath, language));
                    return;
                case "scanStatus":
                    writeJson(resp, HttpServletResponse.SC_OK, status(sitePath, language));
                    return;
                case "saveSchedule":
                    writeJson(resp, HttpServletResponse.SC_OK,
                            saveSchedule(sitePath, language, within, body, req));
                    return;
                case "runScan":
                    runScan(sitePath, language, within, req);
                    writeJson(resp, HttpServletResponse.SC_OK, ScanStore.read(sitePath, language));
                    return;
                default:
                    deny(resp, HttpServletResponse.SC_BAD_REQUEST, "unknown action");
            }
        } catch (ScanInProgressException e) {
            // 409, not 500: nothing is broken, the site is simply busy. The
            // dashboard polls scanStatus, so the editor sees the run that is
            // already going rather than being told to retry into a collision.
            deny(resp, HttpServletResponse.SC_CONFLICT, "a scan is already running");
        } catch (Exception e) {
            logger.warn("site-scan {} failed for {}: {}", action, path, e.getMessage());
            logger.debug("site-scan failure", e);
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "operation failed");
        }
    }

    /** The stored state plus what only the scheduler knows. */
    private JSONObject status(String sitePath, String language) throws Exception {
        JSONObject out = ScanStore.read(sitePath, language);
        java.util.Date next = ScanScheduler.nextRun(sitePath, language);
        out.put("nextRun", next == null ? JSONObject.NULL : next.toInstant().toString());
        return out;
    }

    /**
     * Validates the cron before storing anything, then installs or removes the
     * trigger so the stored config and the scheduler cannot disagree.
     */
    private JSONObject saveSchedule(String sitePath, String language, String scope, JSONObject body,
            HttpServletRequest req)
            throws Exception {
        String cron = body.optString("cron", "").trim();
        boolean enabled = body.optBoolean("enabled", false);

        if (enabled && !ScanScheduler.isValidCron(cron)) {
            JSONObject err = new JSONObject();
            err.put("error", "invalid cron");
            return err;
        }

        String base = baseUrlFor(sitePath, language, req);
        // Recorded so a run can ask again whether this account is still
        // entitled to it, rather than trusting a decision made once.
        String owner = currentUser() == null ? "" : currentUser().getLocalPath();
        ScanStore.saveConfig(sitePath, cron, enabled, scope, base, owner);

        if (enabled) {
            org.quartz.JobDataMap data = new org.quartz.JobDataMap();
            data.put(SiteScanJob.SITE_PATH, sitePath);
            data.put(SiteScanJob.LANGUAGE, language);
            data.put(SiteScanJob.SCOPE, scope);
            data.put(SiteScanJob.BASE_URL, base);
            data.put(SiteScanJob.TIMEOUT_MS, config.getFetchTimeoutMs());
            data.put(SiteScanJob.MAX_BYTES, config.getMaxBodyBytes());
            data.put(SiteScanJob.MAX_PAGES, MAX_PAGES);
            data.put(SiteScanJob.USER_KEY, owner);
            ScanScheduler.schedule(sitePath, language, cron, data);
        } else {
            ScanScheduler.unschedule(sitePath, language);
        }
        return status(sitePath, language);
    }

    /**
     * Runs the scan now, in the request thread. Honest about what that costs:
     * one fetch per page, so a large site will outlast a browser's patience.
     * The scheduled job is the way to scan a big site; this button is for
     * seeing it work and for sites small enough not to care.
     */
    private void runScan(String sitePath, String language, String scope, HttpServletRequest req)
            throws Exception {
        SiteScorer.Options opts = new SiteScorer.Options();
        opts.fetchTimeoutMs = config.getFetchTimeoutMs();
        opts.maxBodyBytes = config.getMaxBodyBytes();
        // A manual run has a request, so the base url is known exactly rather
        // than assumed. The scheduled job has no request and uses whatever was
        // recorded when the schedule was saved.
        opts.publicBaseUrl = baseUrlFor(sitePath, language, req);
        opts.maxPages = MAX_PAGES;
        opts.scope = scope;
        try {
            SiteScorer.scan(sitePath, language, opts);
        } catch (ScanInProgressException e) {
            // Not a failure, and emphatically not ours to mark failed: the run
            // that holds it is still going. Re-thrown for dispatch to answer 409.
            throw e;
        } catch (Exception e) {
            ScanStore.failRun(sitePath, language, "scan failed");
            throw e;
        }
    }

    private String baseUrlFor(String sitePath, String language, HttpServletRequest req) throws Exception {
        String configured = config.getPublicBaseUrl();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", Locale.forLanguageTag(language));
        return org.jahia.se.modules.georeadiness.util.PublicUrls.base(live.getNode(sitePath), req, "");
    }

    private boolean rateLimitOk(String userKey) {
        long now = System.currentTimeMillis();
        Deque<Long> w = callWindows.computeIfAbsent(userKey, k -> new ArrayDeque<>());
        synchronized (w) {
            while (!w.isEmpty() && now - w.peekFirst() > RATE_WINDOW_MS) {
                w.pollFirst();
            }
            if (w.size() >= RATE_MAX_CALLS) {
                return false;
            }
            w.addLast(now);
            return true;
        }
    }

    private static JahiaUser currentUser() {
        try {
            return JCRSessionFactory.getInstance().getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            out.write(buf, 0, n);
            if (total >= max) {
                break;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deny(HttpServletResponse resp, int code, String msg) throws IOException {
        JSONObject o = new JSONObject();
        o.put("error", msg);
        writeJson(resp, code, o);
    }

    private static void writeJson(HttpServletResponse resp, int code, JSONObject body) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(body.toString());
    }
}
