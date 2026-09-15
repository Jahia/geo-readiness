package org.jahia.se.modules.georeadiness.scheduler;

import org.jahia.se.modules.georeadiness.check.ScanStore;
import org.jahia.settings.SettingsBean;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.quartz.JobDataMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Ties the scan triggers to the life of this bundle.
 *
 * A Quartz trigger is durable and names a job class from this bundle. Left in
 * place across a refresh it would point at a class the framework has replaced,
 * so the triggers are removed when the bundle stops.
 *
 * That alone would lose an editor's schedule on every redeploy, which is why
 * the pair matters: the schedules themselves live in the repository, and they
 * are reinstalled from there when the bundle starts.
 */
@Component(
        service = ScanLifecycle.class,
        property = {
                "service.description=Reinstalls the GEO readiness scan triggers with the bundle",
                "service.vendor=Jahia Solutions Group SA"
        },
        immediate = true)
public class ScanLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(ScanLifecycle.class);

    /** DS instantiates this. */
    public ScanLifecycle() {
    }

    @Activate
    protected void activate() {
        // Never let a restore failure stop the bundle: the dashboard still
        // works without a trigger, and saving the schedule again reinstalls it.
        try {
            if (!SettingsBean.getInstance().isProcessingServer()) {
                return;
            }
            restore();
        } catch (Exception e) {
            logger.warn("Could not reinstall the GEO scan triggers: {}", e.getMessage());
            logger.debug("GEO scan trigger restore failed", e);
        }
    }

    @Deactivate
    protected void deactivate() {
        // Guarded exactly as activate() is, and for a sharper reason. The
        // scheduler is Jahia's persistent, cluster-shared Quartz store, so
        // unscheduleAll() deletes the triggers for EVERY site from the whole
        // cluster - and an unguarded browsing node did that on every restart,
        // bundle refresh and rolling deploy. It then could not put them back,
        // because activate() returns early on a node that is not the processing
        // server. Every site's nightly scan stopped, silently, until somebody
        // re-saved the schedule by hand.
        if (!SettingsBean.getInstance().isProcessingServer()) {
            return;
        }
        ScanScheduler.unscheduleAll();
    }

    private void restore() throws Exception {
        List<ScanStore.Schedule> stored = ScanStore.schedules();
        for (ScanStore.Schedule s : stored) {
            if (!ScanScheduler.isValidCron(s.cron)) {
                logger.warn("Stored GEO schedule for {} [{}] has an invalid expression, skipped",
                        s.sitePath, s.language);
                continue;
            }
            JobDataMap data = new JobDataMap();
            data.put(SiteScanJob.SITE_PATH, s.sitePath);
            data.put(SiteScanJob.LANGUAGE, s.language);
            data.put(SiteScanJob.SCOPE, s.scope);
            data.put(SiteScanJob.BASE_URL, s.baseUrl);
            data.put(SiteScanJob.USER_KEY, s.userKey);
            ScanScheduler.schedule(s.sitePath, s.language, s.cron, data);
        }
        if (!stored.isEmpty()) {
            logger.info("Reinstalled {} GEO scan trigger(s)", stored.size());
        }
    }
}
