package org.jahia.se.modules.georeadiness.ai;

import org.json.JSONObject;

/** DeepSeek, which speaks the Chat Completions dialect. */
public final class DeepSeekProvider extends OpenAiCompatibleProvider {

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public String defaultBaseUrl() {
        return "https://api.deepseek.com";
    }

    @Override
    protected String maxTokensField() {
        return "max_tokens";
    }

    @Override
    protected void decorate(JSONObject body) {
        // V4 models reason by default. The hidden reasoning eats the output
        // budget and the visible answer drifts from the JSON-only instruction.
        // A structured report needs no chain of thought, so it is switched off.
        body.put("thinking", new JSONObject().put("type", "disabled"));
    }
}
