package org.jahia.se.modules.georeadiness.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;

/**
 * The Chat Completions shape ({@code POST {base}/v1/chat/completions}, Bearer
 * token) that OpenAI defined and DeepSeek kept. Subclasses differ in the name
 * of the output-token field and in what they add to the body.
 */
public abstract class OpenAiCompatibleProvider implements LlmProvider {

    /** OpenAI renamed {@code max_tokens}; DeepSeek did not. */
    protected abstract String maxTokensField();

    /** A hook for provider-specific body fields. Default: none. */
    protected void decorate(JSONObject body) {
        // nothing by default
    }

    @Override
    public Completion complete(String system, String user, LlmSettings settings)
            throws IOException, InterruptedException {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", system))
                .put(new JSONObject().put("role", "user").put("content", user));
        JSONObject body = new JSONObject()
                .put("model", settings.model())
                .put(maxTokensField(), settings.maxTokens())
                .put("messages", messages);
        decorate(body);

        JSONObject response = HttpJson.post(settings.baseUrl() + "/v1/chat/completions",
                Collections.singletonMap("Authorization", "Bearer " + settings.apiKey()), body);

        JSONArray choices = response.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return new Completion("", false, -1, -1);
        }
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.optJSONObject("message");
        String content = message == null ? "" : message.optString("content", "");
        if (content.trim().isEmpty() && message != null && message.has("reasoning_content")) {
            // The model spent the whole budget thinking and said nothing. Naming
            // the cause is what lets an administrator fix it from the config.
            throw new IOException("the model used its whole output budget on reasoning and returned no answer;"
                    + " raise AI_MAX_TOKENS or choose a non-reasoning model");
        }
        JSONObject usage = response.optJSONObject("usage");
        return new Completion(content,
                "length".equals(choice.optString("finish_reason")),
                usage == null ? -1 : usage.optLong("prompt_tokens", -1),
                usage == null ? -1 : usage.optLong("completion_tokens", -1));
    }
}
