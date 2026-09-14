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

    private static final String ROLE = "role";
    private static final String CONTENT = "content";

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
                .put(new JSONObject().put(ROLE, "system").put(CONTENT, system))
                .put(new JSONObject().put(ROLE, "user").put(CONTENT, user));
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
        String content = message == null ? "" : message.optString(CONTENT, "");
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
