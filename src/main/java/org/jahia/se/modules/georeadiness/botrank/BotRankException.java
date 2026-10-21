package org.jahia.se.modules.georeadiness.botrank;

import java.io.IOException;

/**
 * A refusal or a failure of the BotRank public API, carrying the stable problem type.
 *
 * <p>BotRank answers errors as RFC 9457 problem documents whose {@code type} is a stable URI such as
 * {@code .../problems/revoked-api-key}. {@link #type()} is the last segment of that URI, which is
 * what callers branch on. {@link #getMessage()} never contains the API key or a response body: it is
 * safe to show to a site administrator and to log.</p>
 */
public class BotRankException extends IOException {

    private static final long serialVersionUID = 1L;

    /** No answer at all: timeout, refused connection, unreadable body. */
    public static final String UNREACHABLE = "unreachable";

    private final String type;
    private final int status;

    /**
     * @param type    the last segment of the problem type URI, or {@link #UNREACHABLE}
     * @param status  the HTTP status, 0 when there was no answer
     * @param message a short explanation that names no secret
     */
    public BotRankException(String type, int status, String message) {
        super(message);
        this.type = type;
        this.status = status;
    }

    /** @return the stable problem type, such as {@code revoked-api-key} or {@code unknown-project} */
    public String type() {
        return type;
    }

    /** @return the HTTP status of the answer, 0 when there was none */
    public int status() {
        return status;
    }

    /** @return true when retrying later may succeed: no answer, or a 5xx */
    public boolean isTransient() {
        return UNREACHABLE.equals(type) || status >= 500;
    }
}
