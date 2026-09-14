package org.jahia.se.modules.georeadiness.ai;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * JSON over HTTP for the providers, on the JDK client so the bundle carries no
 * HTTP dependency. One client, shared: it is thread-safe.
 *
 * The endpoints reached here come from configuration only, never from a
 * request, which is why this helper carries no origin check of its own.
 */
final class HttpJson {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** A site-wide report is a long answer; models take their time over it. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(150);
    private static final int MAX_ERROR_CHARS = 400;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpJson() {
    }

    /**
     * Posts {@code body} and returns the parsed response.
     *
     * @throws IOException on a non-2xx status, with the status and a clipped body in the message
     */
    static JSONObject post(String url, Map<String, String> headers, JSONObject body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(builder::header);

        HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String detail = response.body() == null ? "" : response.body();
            if (detail.length() > MAX_ERROR_CHARS) {
                detail = detail.substring(0, MAX_ERROR_CHARS) + "...";
            }
            throw new IOException("provider answered HTTP " + status + ": " + detail);
        }
        return new JSONObject(response.body());
    }
}
