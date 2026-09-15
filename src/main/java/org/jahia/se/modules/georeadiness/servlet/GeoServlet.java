package org.jahia.se.modules.georeadiness.servlet;

import org.jahia.se.modules.georeadiness.util.SiteScope;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONObject;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every servlet in this module does before it does anything of its own.
 *
 * The four servlets each carried their own copy of these seven methods. That is
 * how they were written and it read as harmless, because the copies started
 * identical. They did not stay identical, and the drift is the argument for this
 * class - not the line count:
 *
 * <ul>
 * <li>{@link #writeJson} set {@code X-Content-Type-Options: nosniff} in
 * GeoReportServlet and in none of the other three, so three of this module's
 * four JSON endpoints were served without it.</li>
 * <li>{@link #read} threw on an oversized body in GeoReportServlet and silently
 * TRUNCATED it in the other three - where a body one byte over the limit came
 * back to the caller as "malformed body", which is not what happened.</li>
 * </ul>
 *
 * Neither would ever have been reported as a bug. Both are fixed here by
 * keeping the stricter copy in each case, which is why this is a behaviour
 * change and not only a refactor.
 *
 * Not an OSGi component itself: it has no alias and registers nothing. Each
 * concrete servlet keeps its own {@code @Component}, its own configuration
 * reference and its own rate-limit policy.
 */
public abstract class GeoServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /**
     * Per servlet instance, deliberately not shared between them. One map per
     * endpoint means a burst against the crawler check does not spend the
     * budget for the site scan, which is what a single shared window would do.
     */
    private final transient Map<String, Deque<Long>> callWindows = new ConcurrentHashMap<>();

    /**
     * The caller, or null when there is nobody to speak of.
     *
     * Never throws: a servlet method that lets anything escape gets a container
     * stack trace in place of a response.
     */
    protected static JahiaUser currentUser() {
        try {
            return JCRSessionFactory.getInstance().getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when nobody is logged in, guest included. */
    protected static boolean isGuest() {
        JahiaUser u = currentUser();
        return u == null || JahiaUserManagerService.GUEST_USERNAME.equals(u.getName());
    }

    /**
     * The authenticated caller, or null once the request has been answered 401.
     *
     * The convention every servlet here follows: a helper either ANSWERS the
     * request and says so by returning null or false, or it returns a value and
     * stays silent. Getting that wrong writes two responses to one request.
     */
    protected static JahiaUser requireUser(HttpServletResponse resp) throws IOException {
        JahiaUser user = currentUser();
        if (user == null || JahiaUserManagerService.GUEST_USERNAME.equals(user.getName())) {
            deny(resp, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return null;
        }
        return user;
    }

    /**
     * The parsed request body, or null once the request has been refused and
     * answered.
     *
     * The content type is the CSRF control, and it is the only one these
     * endpoints have: a form post and a cross-site request cannot set
     * application/json, so requiring it keeps them same-origin.
     */
    protected static JSONObject jsonBody(HttpServletRequest req, HttpServletResponse resp, int maxBytes)
            throws IOException {
        if (!isJsonRequest(req, resp)) {
            return null;
        }
        try {
            return new JSONObject(read(req.getInputStream(), maxBytes));
        } catch (Exception e) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed body");
            return null;
        }
    }

    /**
     * True when this is a same-origin JSON call; answers 400 when not.
     *
     * Separate from {@link #jsonBody} because where the rate limit sits relative
     * to PARSING is a real decision and the two servlets make it differently.
     * The crawler check meters before it reads anything, so a flood of malformed
     * bodies still spends the caller's budget. The site scan cannot: its limit
     * applies to four of nine actions, and which action was asked for is only
     * known once the body is parsed.
     */
    protected static boolean isJsonRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String ctype = req.getContentType();
        if (ctype == null || !ctype.toLowerCase(Locale.ROOT).contains("application/json")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "json required");
            return false;
        }
        return true;
    }

    /**
     * True when the body names a site path and a language; answers 400 when not.
     *
     * The language check is not cosmetic. It becomes a node name under a system
     * session further down, so without this it is a path.
     */
    protected static boolean isWellFormed(String path, String language, HttpServletResponse resp)
            throws IOException {
        if (path == null || path.isEmpty() || !path.startsWith("/sites/")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "path required");
            return false;
        }
        if (!SiteScope.isLanguage(language)) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "language required");
            return false;
        }
        return true;
    }

    /**
     * A sliding window per user, with the policy passed in because the endpoints
     * do not share one: the AI report is metered far more tightly than a scan
     * status the dashboard polls.
     */
    protected boolean rateLimitOk(String userKey, long windowMs, int maxCalls) {
        long now = System.currentTimeMillis();
        Deque<Long> w = callWindows.computeIfAbsent(userKey, k -> new ArrayDeque<>());
        synchronized (w) {
            while (!w.isEmpty() && now - w.peekFirst() > windowMs) {
                w.pollFirst();
            }
            if (w.size() >= Math.max(1, maxCalls)) {
                return false;
            }
            w.addLast(now);
            return true;
        }
    }

    /**
     * The body, refusing anything over {@code max} rather than truncating it.
     *
     * Three of the four copies this replaces broke out of the read loop at the
     * limit and returned what they had. A body one byte too long therefore
     * reached the JSON parser cut in half and was reported as "malformed body" -
     * an answer that sent the caller looking for a syntax error that was not
     * there. Refusing it says what actually happened.
     */
    protected static String read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) {
                throw new IOException("body too large");
            }
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** An error body with the status to match. */
    protected static void deny(HttpServletResponse resp, int status, String message) throws IOException {
        writeJson(resp, status, new JSONObject().put("error", message));
    }

    /**
     * The one place this module writes a response.
     *
     * nosniff is not optional here and was missing from three of the four copies
     * this replaces. These endpoints echo content read from the site being
     * checked - page titles, robots.txt excerpts, error text - so a browser that
     * sniffs a JSON response as HTML has an XSS in it.
     */
    protected static void writeJson(HttpServletResponse resp, int status, JSONObject json) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.getWriter().write(json.toString());
    }
}
