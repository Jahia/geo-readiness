package org.jahia.se.modules.georeadiness.scheduler;

import org.jahia.se.modules.georeadiness.check.ScanStore;
import org.jahia.se.modules.georeadiness.check.SiteScorer;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.settings.SettingsBean;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        } catch (Exception e) {
            // The run record is how the dashboard learns this failed. Losing it
            // would leave the UI saying "running" for ever.
            ScanStore.failRun(sitePath, language, "scan failed");
            logger.error("GEO scan of {} [{}] failed", sitePath, language, e);
            throw e;
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
