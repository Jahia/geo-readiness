package org.jahia.se.modules.georeadiness.ai;

/**
 * Connection settings for one provider, resolved from the module's OSGi
 * configuration at call time. Immutable, and its {@code toString} never prints
 * the key: this object ends up in log lines.
 */
public final class LlmSettings {

    private final String provider;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;

    public LlmSettings(String provider, String apiKey, String baseUrl, String model, int maxTokens) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrl = trimTrailingSlashes(baseUrl);
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /** Trailing slashes removed by walking the end, so no pattern can backtrack over a long url. */
    private static String trimTrailingSlashes(String url) {
        if (url == null) {
            return "";
        }
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    public String provider() {
        return provider;
    }

    public String apiKey() {
        return apiKey;
    }

    /** The API base without a trailing slash, e.g. {@code https://api.anthropic.com}. */
    public String baseUrl() {
        return baseUrl;
    }

    public String model() {
        return model;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    @Override
    public String toString() {
        return "LlmSettings[provider=" + provider + ", model=" + model + ", baseUrl=" + baseUrl
                + ", maxTokens=" + maxTokens + ", apiKey=" + (hasApiKey() ? "***" : "<empty>") + "]";
    }
}
