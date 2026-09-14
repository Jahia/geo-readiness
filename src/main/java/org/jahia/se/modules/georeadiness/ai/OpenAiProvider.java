package org.jahia.se.modules.georeadiness.ai;

/** OpenAI's Chat Completions API. */
public final class OpenAiProvider extends OpenAiCompatibleProvider {

    @Override
    public String name() {
        return "openai";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.openai.com";
    }

    @Override
    protected String maxTokensField() {
        // Renamed by OpenAI; the gpt-5 family rejects the old name.
        return "max_completion_tokens";
    }
}
