package org.jahia.se.modules.georeadiness.config;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
        property = {
                "service.description=GEO readiness configuration",
                "service.vendor=Jahia Solutions Group SA"
        },
        immediate = true)
public class GeoReadinessConfigService {

    /**
     * The whole configuration is swapped at once, never edited in place, so a
     * reader either sees the settings as they were or as they now are and never
     * a mixture of the two. Snapshot is immutable, which is what makes that
     * safe; the reference is held here rather than marked volatile so that the
     * swap is visibly the atomic operation it has to be.
     */
    private final AtomicReference<Snapshot> config = new AtomicReference<>(Snapshot.defaults());

    @Activate
    @Modified
    protected void activate(Map<String, Object> properties) {
        this.config.set(Snapshot.from(properties));
    }

    public int getFetchTimeoutMs() {
        return config.get().fetchTimeoutMs;
    }

    public int getMaxBodyBytes() {
        return config.get().maxBodyBytes;
    }

    public int getRateMaxCalls() {
        return config.get().rateMaxCalls;
    }

    public long getRateWindowMs() {
        return config.get().rateWindowMs;
    }

    /** Blank means "use the built-in agent list", which is the normal case. */
    public String getCrawlerAgents() {
        return config.get().crawlerAgents;
    }

    /** Blank means "derive the base URL from the site's server name". */
    public String getPublicBaseUrl() {
        return config.get().publicBaseUrl;
    }

    /** anthropic, openai or deepseek. Blank disables the written report. */
    public String getAiProvider() {
        return config.get().aiProvider;
    }

    public String getAiModel() {
        return config.get().aiModel;
    }

    /** Never logged, never sent to the browser. */
    public String getAiApiKey() {
        return config.get().aiApiKey;
    }

    public int getAiMaxTokens() {
        return config.get().aiMaxTokens;
    }

    /** Blank means the provider's own endpoint. Set for a proxy or a compatible gateway. */
    public String getAiBaseUrl() {
        return config.get().aiBaseUrl;
    }

    /** Site-specific instructions appended to the report prompt. */
    public String getAiPromptAppendix() {
        return config.get().aiPromptAppendix;
    }

    /** Report generations allowed per user per ten minutes. Each one is paid for. */
    public int getAiRateMaxCalls() {
        return config.get().aiRateMaxCalls;
    }

    // Immutable snapshot. One volatile read gives a fully consistent view.
    private static final class Snapshot {

        final int fetchTimeoutMs;
        final int maxBodyBytes;
        final int rateMaxCalls;
        final long rateWindowMs;
        final String crawlerAgents;
        final String publicBaseUrl;
        final String aiProvider;
        final String aiModel;
        final String aiApiKey;
        final int aiMaxTokens;
        final String aiBaseUrl;
        final String aiPromptAppendix;
        final int aiRateMaxCalls;

        private Snapshot(int fetchTimeoutMs, int maxBodyBytes, int rateMaxCalls, long rateWindowMs,
                String crawlerAgents, String publicBaseUrl, String aiProvider, String aiModel, String aiApiKey,
                int aiMaxTokens, String aiBaseUrl, String aiPromptAppendix, int aiRateMaxCalls) {
            this.fetchTimeoutMs = fetchTimeoutMs;
            this.maxBodyBytes = maxBodyBytes;
            this.rateMaxCalls = rateMaxCalls;
            this.rateWindowMs = rateWindowMs;
            this.crawlerAgents = crawlerAgents;
            this.publicBaseUrl = publicBaseUrl;
            this.aiProvider = aiProvider;
            this.aiModel = aiModel;
            this.aiApiKey = aiApiKey;
            this.aiMaxTokens = aiMaxTokens;
            this.aiBaseUrl = aiBaseUrl;
            this.aiPromptAppendix = aiPromptAppendix;
            this.aiRateMaxCalls = aiRateMaxCalls;
        }

        static Snapshot defaults() {
            return new Snapshot(8000, 1_500_000, 20, 600_000L, "", "", "", "", "", 6000, "", "", 5);
        }

        static Snapshot from(Map<String, Object> p) {
            return new Snapshot(
                    intVal(p, "FETCH_TIMEOUT_MS", 8000),
                    intVal(p, "MAX_BODY_BYTES", 1_500_000),
                    intVal(p, "RATE_MAX_CALLS", 20),
                    longVal(p, "RATE_WINDOW_MS", 600_000L),
                    str(p, "CRAWLER_AGENTS", ""),
                    str(p, "PUBLIC_BASE_URL", ""),
                    str(p, "AI_PROVIDER", ""),
                    str(p, "AI_MODEL", ""),
                    str(p, "AI_API_KEY", ""),
                    intVal(p, "AI_MAX_TOKENS", 6000),
                    str(p, "AI_BASE_URL", ""),
                    str(p, "AI_PROMPT_APPENDIX", ""),
                    intVal(p, "AI_RATE_MAX_CALLS", 5));
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
