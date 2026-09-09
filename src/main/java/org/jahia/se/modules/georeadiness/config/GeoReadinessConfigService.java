package org.jahia.se.modules.georeadiness.config;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;

import java.util.Map;

/**
 * OSGi-managed configuration for the GEO Readiness crawler check.
 *
 * Edit digital-factory-data/karaf/etc/org.jahia.se.modules.georeadiness.cfg at
 * runtime. Changes are picked up immediately through @Modified, with no
 * redeployment. Same pattern as page-audit's PageAuditConfigService.
 */
@Component(
        service = GeoReadinessConfigService.class,
        configurationPid = "org.jahia.se.modules.georeadiness",
        immediate = true)
public class GeoReadinessConfigService {

    private volatile Snapshot config = Snapshot.defaults();

    @Activate
    @Modified
    protected void activate(Map<String, Object> properties) {
        this.config = Snapshot.from(properties);
    }

    public int getFetchTimeoutMs() {
        return config.fetchTimeoutMs;
    }

    public int getMaxBodyBytes() {
        return config.maxBodyBytes;
    }

    public int getRateMaxCalls() {
        return config.rateMaxCalls;
    }

    public long getRateWindowMs() {
        return config.rateWindowMs;
    }

    /** Blank means "use the built-in agent list", which is the normal case. */
    public String getCrawlerAgents() {
        return config.crawlerAgents;
    }

    /** Blank means "derive the base URL from the site's server name". */
    public String getPublicBaseUrl() {
        return config.publicBaseUrl;
    }

    // Immutable snapshot. One volatile read gives a fully consistent view.
    private static final class Snapshot {

        final int fetchTimeoutMs;
        final int maxBodyBytes;
        final int rateMaxCalls;
        final long rateWindowMs;
        final String crawlerAgents;
        final String publicBaseUrl;

        private Snapshot(int fetchTimeoutMs, int maxBodyBytes, int rateMaxCalls, long rateWindowMs,
                String crawlerAgents, String publicBaseUrl) {
            this.fetchTimeoutMs = fetchTimeoutMs;
            this.maxBodyBytes = maxBodyBytes;
            this.rateMaxCalls = rateMaxCalls;
            this.rateWindowMs = rateWindowMs;
            this.crawlerAgents = crawlerAgents;
            this.publicBaseUrl = publicBaseUrl;
        }

        static Snapshot defaults() {
            return new Snapshot(8000, 1_500_000, 20, 600_000L, "", "");
        }

        static Snapshot from(Map<String, Object> p) {
            return new Snapshot(
                    intVal(p, "FETCH_TIMEOUT_MS", 8000),
                    intVal(p, "MAX_BODY_BYTES", 1_500_000),
                    intVal(p, "RATE_MAX_CALLS", 20),
                    longVal(p, "RATE_WINDOW_MS", 600_000L),
                    str(p, "CRAWLER_AGENTS", ""),
                    str(p, "PUBLIC_BASE_URL", ""));
        }

        private static String str(Map<String, Object> m, String key, String def) {
            Object v = m.get(key);
            if (v instanceof String) {
                String s = ((String) v).trim();
                return s.isEmpty() ? def : s;
            }
            return def;
        }

        private static int intVal(Map<String, Object> m, String key, int def) {
            try {
                return Integer.parseInt(String.valueOf(m.get(key)).trim());
            } catch (Exception e) {
                return def;
            }
        }

        private static long longVal(Map<String, Object> m, String key, long def) {
            try {
                return Long.parseLong(String.valueOf(m.get(key)).trim());
            } catch (Exception e) {
                return def;
            }
        }
    }
}
