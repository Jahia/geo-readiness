package org.jahia.se.modules.georeadiness.servlet;

import org.jahia.se.modules.georeadiness.check.GeoReport;
import org.jahia.se.modules.georeadiness.check.ScanStore;
import org.jahia.se.modules.georeadiness.config.GeoReadinessConfigService;
import org.jahia.se.modules.georeadiness.util.PublicUrls;
import org.jahia.se.modules.georeadiness.util.SiteScope;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The written report, generated on request and read back afterwards.
 *
 * GET  /modules/geo-readiness/report            -> {enabled, provider, model}; never the key
 * POST {"action":"read", path, language}        -> the stored report, or {"report": null}
 * POST {"action":"generate", path, language, reportLanguage} -> asks the provider, stores, returns
 *
 * Same gate as the dashboard's other endpoints: the caller must hold the
 * permission the dashboard route declares, on the site the path resolves to.
 * Generation is metered separately and tightly, because each call is paid for.
 */
@Component(
        service = {HttpServlet.class, Servlet.class},
        property = {
                "alias=/geo-readiness/report",
                "allow-api-token=true",
                "service.description=GEO readiness written report",
                "service.vendor=Jahia Solutions Group SA"
        },
        immediate = true)
public class GeoReportServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(GeoReportServlet.class);

    private static final int MAX_BODY = 16_000;
    private static final long RATE_WINDOW_MS = 600_000L;

    private final Map<String, Deque<Long>> callWindows = new ConcurrentHashMap<>();

    @Reference
    private GeoReadinessConfigService config;

    /** DS instantiates this. */
    public GeoReportServlet() {
    }

    /** Test seam. */
    GeoReportServlet(GeoReadinessConfigService config) {
        this.config = config;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (guest()) {
            deny(resp, HttpServletResponse.SC_UNAUTHORIZED, "authentication required");
            return;
        }
        JSONObject out = new JSONObject();
        boolean enabled = GeoReport.enabled(config);
        out.put("enabled", enabled);
        out.put("provider", enabled ? config.getAiProvider() : "");
        out.put("model", enabled ? config.getAiModel() : "");
        writeJson(resp, HttpServletResponse.SC_OK, out);
    }

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
        JSONObject body;
        try {
            body = new JSONObject(read(req.getInputStream(), MAX_BODY));
        } catch (Exception e) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed body");
            return;
        }

        String action = body.optString("action", "read");
        String path = body.optString("path", "");
        String language = body.optString("language", "en");
        String reportLanguage = body.optString("reportLanguage", language);
        if (path.isEmpty() || !path.startsWith("/sites/")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "path required");
            return;
        }
        // Both become node names or prompt content; neither may be a path.
        if (!SiteScope.isLanguage(language) || !SiteScope.isLanguage(reportLanguage)) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "language required");
            return;
        }

        String sitePath;
        try {
            sitePath = SiteScope.require(path, language, SiteScope.DASHBOARD);
        } catch (PathNotFoundException | AccessDeniedException e) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
            return;
        } catch (Exception e) {
            logger.warn("report gate failed for {}: {}", path, e.getMessage());
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "operation failed");
            return;
        }

        try {
            if ("read".equals(action)) {
                JSONObject out = new JSONObject();
                JSONObject stored = ScanStore.readReport(sitePath, language);
                out.put("report", stored == null ? JSONObject.NULL : stored);
                writeJson(resp, HttpServletResponse.SC_OK, out);
                return;
            }
            if (!"generate".equals(action)) {
                deny(resp, HttpServletResponse.SC_BAD_REQUEST, "unknown action");
                return;
            }
            if (!GeoReport.enabled(config)) {
                deny(resp, HttpServletResponse.SC_CONFLICT, "no provider configured");
                return;
            }
            if (!rateLimitOk(user.getUserKey())) {
                deny(resp, 429, "rate limit");
                return;
            }
            String base = baseUrlFor(sitePath, language, req);
            JSONObject report = GeoReport.generate(sitePath, language, reportLanguage, base, config);
            JSONObject out = new JSONObject();
            out.put("report", report);
            writeJson(resp, HttpServletResponse.SC_OK, out);
        } catch (IOException e) {
            // The provider's own words stay in the log. The browser learns that
            // the provider failed, and how, in a form that names no secret.
            logger.warn("GEO report for {} [{}] failed: {}", sitePath, language, e.getMessage());
            deny(resp, HttpServletResponse.SC_BAD_GATEWAY, "provider failed: " + safe(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deny(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "interrupted");
        } catch (Exception e) {
            logger.warn("GEO report {} failed for {}: {}", action, sitePath, e.getMessage());
            logger.debug("GEO report failure", e);
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "operation failed");
        }
    }

    private String baseUrlFor(String sitePath, String language, HttpServletRequest req) throws Exception {
        String configured = config.getPublicBaseUrl();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", Locale.forLanguageTag(language));
        return PublicUrls.base(live.getNode(sitePath), req, "");
    }

    /** A provider message can quote the request it rejected; a key never appears there, but be sure. */
    private static String safe(String message) {
        if (message == null) {
            return "";
        }
        String m = message.replaceAll("(?i)(bearer|x-api-key|sk-[a-z0-9_-]+)\\S*", "***");
        return m.length() > 300 ? m.substring(0, 300) + "..." : m;
    }

    private boolean rateLimitOk(String userKey) {
        int max = Math.max(1, config.getAiRateMaxCalls());
        long now = System.currentTimeMillis();
        Deque<Long> w = callWindows.computeIfAbsent(userKey, k -> new ArrayDeque<>());
        synchronized (w) {
            while (!w.isEmpty() && now - w.peekFirst() > RATE_WINDOW_MS) {
                w.pollFirst();
            }
            if (w.size() >= max) {
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

    private static boolean guest() {
        JahiaUser u = currentUser();
        return u == null || JahiaUserManagerService.GUEST_USERNAME.equals(u.getName());
    }

    private static String read(InputStream in, int max) throws IOException {
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
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static void writeJson(HttpServletResponse resp, int status, JSONObject json) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.getWriter().write(json.toString());
    }

    private static void deny(HttpServletResponse resp, int status, String message) throws IOException {
        writeJson(resp, status, new JSONObject().put("error", message));
    }
}
