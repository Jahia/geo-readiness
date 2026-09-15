package org.jahia.se.modules.georeadiness.scheduler;

import org.jahia.se.modules.georeadiness.check.ScanInProgressException;
import org.jahia.se.modules.georeadiness.check.ScanStore;
import org.jahia.se.modules.georeadiness.check.SiteScorer;
import org.jahia.se.modules.georeadiness.util.SiteScope;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.settings.SettingsBean;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PathNotFoundException;
import java.util.Locale;

/**
 * The scheduled half of GEO-17. One run scores one site in one language.
 *
 * Deliberately dumb: everything it needs comes from the job data map, so the
 * same class serves a cron trigger and a "run now" without branching. The
 * decisions all live in {@link SiteScorer}.
 */
public class SiteScanJob extends BackgroundJob {

    private static final Logger logger = LoggerFactory.getLogger(SiteScanJob.class);

    public static final String SITE_PATH = "geoSitePath";
    public static final String LANGUAGE = "geoLanguage";
    public static final String SCOPE = "geoScope";
    public static final String BASE_URL = "geoBaseUrl";
    public static final String TIMEOUT_MS = "geoTimeoutMs";
    public static final String MAX_BYTES = "geoMaxBytes";
    public static final String MAX_PAGES = "geoMaxPages";
    /** Who asked for this schedule. Re-checked on every run. */
    public static final String USER_KEY = "geoUserKey";

    @Override
    public void executeJahiaJob(JobExecutionContext context) throws Exception {
        // In a cluster this must run on one node. Without the guard every node
        // scans the same site and they overwrite each other's results.
        if (!SettingsBean.getInstance().isProcessingServer()) {
            logger.debug("Not the processing server, skipping GEO scan");
            return;
        }

        JobDataMap data = context.getJobDetail().getJobDataMap();
        String sitePath = data.getString(SITE_PATH);
        String language = data.getString(LANGUAGE);
        if (sitePath == null || language == null) {
            logger.warn("GEO scan job started without a site or language");
            return;
        }

        // A schedule outlives the rights of whoever saved it, so the run asks
        // again rather than trusting what was stored. A trigger nobody is
        // entitled to any more removes itself instead of running nightly.
        String userKey = data.getString(USER_KEY);
        if (!stillPermitted(userKey, sitePath, language)) {
            logger.warn("GEO scan of {} [{}] removed: {} no longer holds the permission",
                    sitePath, language, userKey);
            ScanScheduler.unschedule(sitePath, language);
            return;
        }

        SiteScorer.Options opts = new SiteScorer.Options();
        opts.scope = data.getString(SCOPE);
        opts.publicBaseUrl = data.getString(BASE_URL);
        opts.fetchTimeoutMs = intOr(data, TIMEOUT_MS, 8000);
        opts.maxBodyBytes = intOr(data, MAX_BYTES, 1_500_000);
        opts.maxPages = intOr(data, MAX_PAGES, 10_000);

        long t0 = System.currentTimeMillis();
        try {
            SiteScorer.scan(sitePath, language, opts);
            logger.info("GEO scan of {} [{}] finished in {}ms", sitePath, language,
                    System.currentTimeMillis() - t0);
        } catch (ScanInProgressException e) {
            // An editor is scanning this site by hand right now. Step aside and
            // let theirs finish; the next trigger will pick it up. Marking the
            // run failed here would destroy the state of a run still in flight.
            logger.info("GEO scan of {} [{}] skipped: {}", sitePath, language, e.getMessage());
        } catch (Exception e) {
            // The run record is how the dashboard learns this failed. Losing it
            // would leave the UI saying "running" for ever.
            ScanStore.failRun(sitePath, language, "scan failed");
            logger.error("GEO scan of {} [{}] failed", sitePath, language, e);
            throw e;
        }
    }

    /**
     * True when the account that installed the schedule still holds the
     * dashboard's permission on the site. An unknown account is refused: a
     * trigger with no owner has nobody entitled to it.
     */
    private static boolean stillPermitted(String userKey, String sitePath, String language) {
        if (userKey == null || userKey.isEmpty()) {
            return false;
        }
        try {
            JCRUserNode owner = JahiaUserManagerService.getInstance().lookupUserByPath(userKey);
            if (owner == null) {
                return false;
            }
            return Boolean.TRUE.equals(JCRTemplate.getInstance().doExecute(owner.getJahiaUser(), "default",
                    Locale.forLanguageTag(language), (JCRCallback<Boolean>) session -> {
                        try {
                            return session.getNode(sitePath).hasPermission(SiteScope.DASHBOARD.get(0));
                        } catch (PathNotFoundException e) {
                            return false;
                        }
                    }));
        } catch (Exception e) {
            logger.warn("Could not confirm the owner of the GEO scan of {}: {}", sitePath, e.getMessage());
            return false;
        }
    }

    private static int intOr(JobDataMap data, String key, int fallback) {
        try {
            return data.containsKey(key) ? data.getInt(key) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
