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
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;

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
public class GeoReportServlet extends GeoServlet {

    private static final Logger logger = LoggerFactory.getLogger(GeoReportServlet.class);

    private static final int MAX_BODY = 16_000;
    /** HttpServletResponse has no constant for it. */
    private static final int TOO_MANY_REQUESTS = 429;
    private static final String AUTH_REQUIRED = "authentication required";
    private static final String OPERATION_FAILED = "operation failed";
    private static final long RATE_WINDOW_MS = 600_000L;

    /**
     * Injected through the constructor rather than into the field, so a servlet
     * the container shares between threads holds nothing mutable.
     */
    private final transient GeoReadinessConfigService config;

    @Activate
    public GeoReportServlet(@Reference GeoReadinessConfigService config) {
        this.config = config;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        try {
            if (guest()) {
                deny(resp, HttpServletResponse.SC_UNAUTHORIZED, AUTH_REQUIRED);
                return;
            }
            JSONObject out = new JSONObject();
            boolean enabled = GeoReport.enabled(config);
            out.put("enabled", enabled);
            out.put("provider", enabled ? config.getAiProvider() : "");
            out.put("model", enabled ? config.getAiModel() : "");
            writeJson(resp, HttpServletResponse.SC_OK, out);
        } catch (IOException | RuntimeException e) {
            // Nothing may leave a servlet method: the container would answer with
            // a stack trace instead of a response. A broken socket, or a JSON
            // library that throws unchecked, both end here.
            logger.debug("could not write the report status", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        try {
            dispatch(req, resp);
        } catch (IOException | RuntimeException e) {
            logger.debug("could not write the report response", e);
        }
    }

    private void dispatch(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JahiaUser user = currentUser();
        if (user == null || JahiaUserManagerService.GUEST_USERNAME.equals(user.getName())) {
            deny(resp, HttpServletResponse.SC_UNAUTHORIZED, AUTH_REQUIRED);
            return;
        }
        Ask ask = read(req, resp);
        if (ask == null) {
            return;
        }
        String sitePath = resolveSite(ask.path, ask.language, resp);
        if (sitePath == null) {
            return;
        }
        run(ask, sitePath, user, req, resp);
    }

    /** What the caller asked for, or null once the refusal has been written. */
    private Ask read(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String ctype = req.getContentType();
        if (ctype == null || !ctype.toLowerCase(Locale.ROOT).contains("application/json")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "json required");
            return null;
        }
        JSONObject body;
        try {
            body = new JSONObject(read(req.getInputStream(), MAX_BODY));
        } catch (IOException | JSONException e) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "malformed body");
            return null;
        }

        Ask ask = new Ask(body);
        if (ask.path.isEmpty() || !ask.path.startsWith("/sites/")) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "path required");
            return null;
        }
        // Both become node names or prompt content; neither may be a path.
        if (!SiteScope.isLanguage(ask.language) || !SiteScope.isLanguage(ask.reportLanguage)) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "language required");
            return null;
        }
        return ask;
    }

    /** Reads the stored report, or asks for a new one. */
    private void run(Ask ask, String sitePath, JahiaUser user, HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        try {
            if ("read".equals(ask.action)) {
                JSONObject out = new JSONObject();
                JSONObject stored = ScanStore.readReport(sitePath, ask.language);
                out.put("report", stored == null ? JSONObject.NULL : stored);
                writeJson(resp, HttpServletResponse.SC_OK, out);
            } else if ("generate".equals(ask.action)) {
                generate(sitePath, ask.language, ask.reportLanguage, user, req, resp);
            } else {
                deny(resp, HttpServletResponse.SC_BAD_REQUEST, "unknown action");
            }
        } catch (IOException e) {
            // The provider's own words stay in the log. The browser learns that
            // the provider failed, and how, in a form that names no secret.
            logger.warn("GEO report for {} [{}] failed: {}", sitePath, ask.language, e.getMessage());
            deny(resp, HttpServletResponse.SC_BAD_GATEWAY, "provider failed: " + safe(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deny(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "interrupted");
        } catch (RepositoryException | RuntimeException e) {
            logger.warn("GEO report {} failed for {}: {}", ask.action, sitePath, e.getMessage());
            logger.debug("GEO report failure", e);
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, OPERATION_FAILED);
        }
    }

    /** The request body, read once. */
    private static final class Ask {
        private final String action;
        private final String path;
        private final String language;
        private final String reportLanguage;

        private Ask(JSONObject body) {
            this.action = body.optString("action", "read");
            this.path = body.optString("path", "");
            this.language = body.optString("language", "en");
            this.reportLanguage = body.optString("reportLanguage", this.language);
        }
    }

    /** The site the caller may act on, or null once the refusal has been written. */
    private String resolveSite(String path, String language, HttpServletResponse resp) throws IOException {
        try {
            return SiteScope.require(path, language, SiteScope.DASHBOARD);
        } catch (PathNotFoundException | AccessDeniedException e) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
        } catch (RepositoryException e) {
            logger.warn("report gate failed for {}: {}", path, e.getMessage());
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, OPERATION_FAILED);
        }
        return null;
    }

    /** The paid path: a provider must exist and the caller must be within their budget. */
    private void generate(String sitePath, String language, String reportLanguage, JahiaUser user,
            HttpServletRequest req, HttpServletResponse resp)
            throws IOException, InterruptedException, RepositoryException {
        if (!GeoReport.enabled(config)) {
            deny(resp, HttpServletResponse.SC_CONFLICT, "no provider configured");
            return;
        }
        if (!rateLimitOk(user.getUserKey(), RATE_WINDOW_MS, Math.max(1, config.getAiRateMaxCalls()))) {
            deny(resp, TOO_MANY_REQUESTS, "rate limit");
            return;
        }
        String base = baseUrlFor(sitePath, language, req);
        JSONObject report = GeoReport.generate(sitePath, language, reportLanguage, base, config);
        JSONObject out = new JSONObject();
        out.put("report", report);
        writeJson(resp, HttpServletResponse.SC_OK, out);
    }

    private String baseUrlFor(String sitePath, String language, HttpServletRequest req) throws RepositoryException {
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

    private static boolean guest() {
        JahiaUser u = currentUser();
        return u == null || JahiaUserManagerService.GUEST_USERNAME.equals(u.getName());
    }
}
