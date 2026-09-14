package org.jahia.se.modules.georeadiness.ai;

/**
 * What a provider answered: the assistant text, whether it hit the output
 * budget, and the token counts the provider reported (or -1 when it did not).
 */
public final class Completion {

    private final String text;
    private final boolean truncated;
    private final long inputTokens;
    private final long outputTokens;

    public Completion(String text, boolean truncated, long inputTokens, long outputTokens) {
        this.text = text == null ? "" : text;
        this.truncated = truncated;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public String text() {
        return text;
    }

    public boolean truncated() {
        return truncated;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }
}
