package org.jahia.se.modules.georeadiness.servlet;

import org.jahia.se.modules.georeadiness.check.GuestVisibility;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Site-wide scans that read the repository rather than fetching pages.
 *
 * Read-only, unlike SiteFilesServlet which is the write path. Everything here
 * answers a question about the whole site, which is why it is a separate
 * endpoint from the per-page crawler check: the dashboard calls it, the drawer
 * does not.
 *
 * POST /modules/geo-readiness/site-scan
 *   {"action":"guestVisibility","path":"/sites/x","language":"en"}
 */
@Component(
        service = {HttpServlet.class, Servlet.class},
        property = {"alias=/geo-readiness/site-scan", "allow-api-token=true"},
        immediate = true)
public class SiteScanServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(SiteScanServlet.class);

    private static final int MAX_BODY = 64_000;
    /** A scan reads every published page. Cheaper than the crawler check, not free. */
    private static final int RATE_MAX_CALLS = 30;
    private static final long RATE_WINDOW_MS = 600_000L;
    private static final int MAX_PAGES = 2000;

    private final Map<String, Deque<Long>> callWindows = new ConcurrentHashMap<>();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JahiaUser user = currentUser();
        if (user == null || JahiaUserManagerService.GUEST_USERNAME.equals(user.getName())) {
            deny(resp, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        String ctype = req.getContentType();
        if (ctype == null || !ctype.toLowerCase(Locale.ROOT).contains("application/json")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "json required");
            return;
        }
        if (!rateLimitOk(user.getUserKey())) {
            deny(resp, 429, "rate limit");
            return;
        }

        JSONObject body;
        try {
            body = new JSONObject(read(req.getInputStream(), MAX_BODY));
        } catch (Exception e) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed body");
            return;
        }

        String action = body.optString("action", "");
        String path = body.optString("path", "");
        String language = body.optString("language", "en");
        if (path.isEmpty() || !path.startsWith("/sites/")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "path required");
            return;
        }

        try {
            if (!"guestVisibility".equals(action)) {
                deny(resp, HttpServletResponse.SC_BAD_REQUEST, "unknown action");
                return;
            }
            // The caller must be able to see the site in the editing workspace
            // before we tell them anything about it. The scan itself then runs
            // with a system session, because listing what guest CANNOT see is
            // the whole point and a caller-scoped session could not do it.
            String sitePath = resolveSite(path, language);
            writeJson(resp, HttpServletResponse.SC_OK,
                    GuestVisibility.scanSite(sitePath, language, MAX_PAGES));
        } catch (javax.jcr.PathNotFoundException | javax.jcr.AccessDeniedException e) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
        } catch (Exception e) {
            logger.warn("site-scan {} failed for {}: {}", action, path, e.getMessage());
            logger.debug("site-scan failure", e);
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "operation failed");
        }
    }

    private String resolveSite(String path, String language) throws Exception {
        JCRSessionWrapper edit = JCRSessionFactory.getInstance()
                .getCurrentUserSession("default", Locale.forLanguageTag(language));
        JCRNodeWrapper node = edit.getNode(path);
        return node.getResolveSite().getPath();
    }

    private boolean rateLimitOk(String userKey) {
        long now = System.currentTimeMillis();
        Deque<Long> w = callWindows.computeIfAbsent(userKey, k -> new ArrayDeque<>());
        synchronized (w) {
            while (!w.isEmpty() && now - w.peekFirst() > RATE_WINDOW_MS) {
                w.pollFirst();
            }
            if (w.size() >= RATE_MAX_CALLS) {
                return false;
            }
            w.addLast(now);
            return true;
        }
    }

    private static JahiaUser currentUser() {
        try {
            return JCRSessionFactory.getInstance().getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    private static String read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            out.write(buf, 0, n);
            if (total >= max) {
                break;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deny(HttpServletResponse resp, int code, String msg) throws IOException {
        JSONObject o = new JSONObject();
        o.put("error", msg);
        writeJson(resp, code, o);
    }

    private static void writeJson(HttpServletResponse resp, int code, JSONObject body) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(body.toString());
    }
}
