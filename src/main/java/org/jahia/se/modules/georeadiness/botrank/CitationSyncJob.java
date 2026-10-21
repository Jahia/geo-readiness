package org.jahia.se.modules.georeadiness.botrank;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.settings.SettingsBean;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.PathNotFoundException;
import java.time.Clock;

/**
 * The Quartz job that runs one BotRank sync of a site.
 *
 * <p>Runs on the processing server only. Before each run it checks that the user who saved the
 * settings still holds {@value CitationsAccess#SETTINGS_PERMISSION} on the site: the organisation key
 * is spent on that user's authority, and a user who lost it stops the sync. While BotRank is still
 * collecting answers the run reschedules itself 30 minutes later, up to
 * {@link CitationSync#MAX_ATTEMPTS} attempts.</p>
 */
public class CitationSyncJob extends BackgroundJob {

    private static final Logger logger = LoggerFactory.getLogger(CitationSyncJob.class);

    static final String SITE_PATH = "brSitePath";
    static final String OWNER = "brOwner";
    static final String ATTEMPT = "brAttempt";

    @Override
    public void executeJahiaJob(JobExecutionContext context) throws Exception {
        if (!SettingsBean.getInstance().isProcessingServer()) {
            return;
        }
        JobDataMap data = context.getMergedJobDataMap();
        String sitePath = data.getString(SITE_PATH);
        String owner = data.getString(OWNER);
        int attempt = parse(data.getString(ATTEMPT));
        BotRankConnections connections = CitationLifecycle.connections();
        if (sitePath == null || connections == null) {
            logger.warn("BotRank sync job started without a site or before the module was ready");
            return;
        }
        if (!CitationsAccess.moduleEnabled(sitePath)) {
            stop(sitePath, "the daily sync stopped: the GEO readiness module is no longer enabled on the site");
            return;
        }
        if (!stillPermitted(owner, sitePath)) {
            logger.warn("BotRank sync of {} stopped: {} no longer holds {} on the site", sitePath, owner,
                    CitationsAccess.SETTINGS_PERMISSION);
            stop(sitePath, "the daily sync stopped: the administrator who saved the settings no longer administers"
                    + " the site; a site administrator saves them again to restart it");
            return;
        }
        CitationSync.Result r = CitationSync.run(sitePath, connections, CitationLifecycle.baseUrl(),
                attempt, Clock.systemUTC());
        if (r.outcome == CitationSync.Outcome.RETRY) {
            CitationScheduler.runOnce(sitePath, owner, CitationScheduler.RETRY_DELAY_MS, attempt + 1);
        } else if (r.outcome != CitationSync.Outcome.DONE) {
            logger.info("BotRank sync of {}: {} ({})", sitePath, r.outcome, r.message);
        }
    }

    /** Disables the sync of a site and says why, so Site Settings shows the reason. */
    private static void stop(String sitePath, String message) {
        CitationScheduler.unschedule(sitePath);
        try {
            CitationStore.disable(sitePath, message);
        } catch (Exception e) {
            logger.warn("Could not record why the BotRank sync of {} stopped", sitePath);
        }
    }

    private static int parse(String s) {
        try {
            return Math.max(1, Integer.parseInt(s));
        } catch (Exception e) {
            return 1;
        }
    }

    /** True when the owner still holds the settings permission on the site; an unknown owner is refused. */
    private static boolean stillPermitted(String owner, String sitePath) {
        if (owner == null || owner.isEmpty()) {
            return false;
        }
        try {
            JCRUserNode user = JahiaUserManagerService.getInstance().lookupUserByPath(owner);
            if (user == null) {
                return false;
            }
            return Boolean.TRUE.equals(JCRTemplate.getInstance().doExecute(user.getJahiaUser(), "default", java.util.Locale.ENGLISH,
                    (JCRCallback<Boolean>) session -> {
                        try {
                            return session.getNode(sitePath).hasPermission(CitationsAccess.SETTINGS_PERMISSION);
                        } catch (PathNotFoundException e) {
                            return false;
                        }
                    }));
        } catch (Exception e) {
            logger.warn("Could not confirm the owner of the BotRank sync of {}: {}", sitePath, e.getMessage());
            return false;
        }
    }
}
