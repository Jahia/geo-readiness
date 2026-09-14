package org.jahia.se.modules.georeadiness.ai;

import java.io.IOException;

/**
 * One chat-completion backend.
 *
 * Implementations are stateless and safe for concurrent use: everything that
 * varies per call, credentials included, arrives in {@link LlmSettings}. The
 * shape follows the provider layer of automatic-content-tags, so a reader of
 * one module recognises the other.
 */
public interface LlmProvider {

    /** The configuration value that selects this provider, lowercase. */
    String name();

    /** The API base used when the configuration names none. */
    String defaultBaseUrl();

    /**
     * Sends one system prompt and one user message and returns the answer.
     *
     * @throws IOException          on transport failure or a non-2xx status; the message carries the
     *                              status and a clipped response body, never the request
     * @throws InterruptedException when the calling thread is interrupted while waiting
     */
    Completion complete(String system, String user, LlmSettings settings) throws IOException, InterruptedException;
}
