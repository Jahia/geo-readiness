package org.jahia.se.modules.georeadiness.botrank;

import org.jahia.se.modules.georeadiness.config.GeoReadinessConfigService;
import org.jahia.se.modules.georeadiness.util.PublicUrls;
import org.jahia.settings.SettingsBean;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Reinstalls the daily BotRank sync triggers when the bundle starts, removes them when it stops, and
 * hands the Quartz job the services it cannot get through DS (Quartz instantiates jobs itself).
 *
 * <p>The JCR store is the source of truth: triggers are rebuilt from the settings of every enabled
 * site. Both directions run on the processing server only, for the reason {@code ScanLifecycle}
 * documents: the Quartz store is shared by the cluster, and an unguarded browsing node would delete
 * every site's trigger and could not put them back.</p>
 */
@Component(service = CitationLifecycle.class, immediate = true, property = {
        "service.description=GEO readiness - BotRank sync triggers",
        "service.vendor=Jahia Solutions Group SA"
})
public class CitationLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(CitationLifecycle.class);

    // Static holders for the Quartz job, which Quartz instantiates outside DS.
    private static final AtomicReference<BotRankConnections> CONNECTIONS = new AtomicReference<>();
    private static final AtomicReference<GeoReadinessConfigService> CONFIG = new AtomicReference<>();

    @Reference
    private BotRankConnections boundConnections;
    @Reference
    private GeoReadinessConfigService boundConfig;

    @Activate
    protected void activate() {
        CONNECTIONS.set(boundConnections);
        CONFIG.set(boundConfig);
        try {
            if (!SettingsBean.getInstance().isProcessingServer()) {
                return;
            }
            int n = 0;
            for (CitationStore.Scheduled s : CitationStore.scheduled()) {
                CitationScheduler.scheduleDaily(s.sitePath, s.time, s.owner);
                n++;
            }
            if (n > 0) {
                logger.info("Reinstalled {} BotRank sync trigger(s)", n);
            }
        } catch (Exception e) {
            // Never stop the bundle for this: saving the settings again reinstalls the trigger.
            logger.warn("Could not reinstall the BotRank sync triggers: {}", e.getMessage());
        }
    }

    @Deactivate
    protected void deactivate() {
        CONNECTIONS.set(null);
        CONFIG.set(null);
        if (SettingsBean.getInstance().isProcessingServer()) {
            CitationScheduler.unscheduleAll();
        }
    }

    /** The connections, or null while the bundle is stopped. */
    static BotRankConnections connections() {
        return CONNECTIONS.get();
    }

    /** The base URL used to build the page index; only paths are compared, so a fallback is fine. */
    static String baseUrl() {
        GeoReadinessConfigService c = CONFIG.get();
        String configured = c == null ? "" : c.getPublicBaseUrl();
        return configured == null || configured.isEmpty() ? PublicUrls.LOCAL_FALLBACK : configured;
    }
}
