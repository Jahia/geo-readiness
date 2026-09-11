package org.jahia.se.modules.georeadiness.servlet;

import org.jahia.se.modules.georeadiness.check.LlmsGenerator;
import org.jahia.se.modules.georeadiness.check.RobotsEditor;
import org.jahia.se.modules.georeadiness.check.RobotsRules;
import org.jahia.se.modules.georeadiness.check.SiteFilesChecker;
import org.jahia.se.modules.georeadiness.config.GeoReadinessConfigService;
import org.jahia.se.modules.georeadiness.util.PublicUrls;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPublicationService;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Generating the two site files, and writing them back when an editor says so.
 *
 * This is the one part of the module that writes. The crawler check is safe to
 * run against anything; this is not, so the split is deliberate and visible:
 *
 *   preview -> returns the generated text AND the text currently stored,
 *              so the caller can diff them. Changes nothing.
 *   apply   -> writes exactly the text it was given, then publishes.
 *
 * "apply" never invents content. It stores what the editor saw and approved,
 * which means an editor can hand-edit the generated file before saving it and
 * we will not quietly regenerate over the top.
 *
 * POST /modules/geo-readiness/site-files
 *   {"action":"previewLlms","path":"/sites/x/home","language":"en"}
 *   {"action":"applyLlms","path":"/sites/x/home","content":"# ..."}
 */
@Component(
        service = {HttpServlet.class, Servlet.class},
        property = {"alias=/geo-readiness/site-files", "allow-api-token=true"},
        immediate = true)
public class SiteFilesServlet extends HttpServlet {

    private static final Logger logger = LoggerFactory.getLogger(SiteFilesServlet.class);

    private static final String LLMS_MIXIN = "jmix:llms";
    private static final String LLMS_PROP = "j:llms";
    /** The community llms module ships this as the autocreated default. It is not a real file. */
    private static final String LLMS_DEFAULT = "# Title";
    private static final String ROBOTS_MIXIN = "jmix:robots";
    private static final String ROBOTS_PROP = "j:robots";
    /** The community robots module ships this as the autocreated default. */
    private static final String ROBOTS_DEFAULT = "User-agent: *";
    private static final int MAX_CONTENT = 400_000;

    @Reference
    private GeoReadinessConfigService config;

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
            body = new JSONObject(read(req.getInputStream(), MAX_CONTENT));
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
            switch (action) {
                case "previewLlms":
                    writeJson(resp, HttpServletResponse.SC_OK, previewLlms(path, language, req, resp));
                    return;
                case "applyLlms":
                    writeJson(resp, HttpServletResponse.SC_OK, applySiteFile(path, language,
                            body.optString("content", ""), LLMS_MIXIN, LLMS_PROP));
                    return;
                case "previewRobots":
                    writeJson(resp, HttpServletResponse.SC_OK,
                            previewRobots(path, language, body.optJSONObject("decisions")));
                    return;
                case "applyRobots":
                    writeJson(resp, HttpServletResponse.SC_OK, applySiteFile(path, language,
                            body.optString("content", ""), ROBOTS_MIXIN, ROBOTS_PROP));
                    return;
                default:
                    deny(resp, HttpServletResponse.SC_BAD_REQUEST, "unknown action");
            }
        } catch (javax.jcr.AccessDeniedException e) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "not allowed");
        } catch (Exception e) {
            logger.warn("site-files {} failed for {}: {}", action, path, e.getMessage());
            logger.debug("site-files failure", e);
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "operation failed");
        }
    }

    /**
     * Generate from LIVE, because llms.txt describes the public site. A page an
     * editor can see but a visitor cannot has no business being advertised.
     */
    private JSONObject previewLlms(String path, String language, HttpServletRequest req, HttpServletResponse resp)
            throws Exception {
        JSONObject out = new JSONObject();
        JCRSessionWrapper live = JCRSessionFactory.getInstance()
                .getCurrentUserSession("live", Locale.forLanguageTag(language));
        JCRNodeWrapper node = live.getNode(path);
        JCRNodeWrapper site = node.getResolveSite();

        String generated = LlmsGenerator.generate(site, live, language,
                n -> PublicUrls.forNode(n, req, resp, config.getPublicBaseUrl()));

        // What is stored today, read from the editing workspace: that is what an
        // apply would overwrite, and it may differ from what live currently serves.
        JCRSessionWrapper edit = JCRSessionFactory.getInstance()
                .getCurrentUserSession("default", Locale.forLanguageTag(language));
        JCRNodeWrapper editSite = edit.getNode(site.getPath());
        String current = editSite.hasProperty(LLMS_PROP) ? editSite.getProperty(LLMS_PROP).getString() : null;

        out.put("siteKey", site.getName());
        out.put("sitePath", site.getPath());
        out.put("generated", generated);
        out.put("current", current == null ? JSONObject.NULL : current);
        out.put("mixinPresent", editSite.isNodeType(LLMS_MIXIN));
        // An autocreated "# Title" is the module's placeholder, not a published file.
        out.put("currentIsPlaceholder", current != null && LLMS_DEFAULT.equals(current.trim()));
        out.put("unchanged", current != null && current.equals(generated));
        out.put("canWrite", editSite.hasPermission("jcr:modifyProperties"));
        return out;
    }

    /**
     * The stance the stored file takes on each AI crawler today, and what the
     * file would look like with the caller's decisions merged in.
     *
     * Nothing is written. The proposed text is returned so the caller can show
     * the editor exactly which lines change before anything is overwritten.
     */
    private JSONObject previewRobots(String path, String language, JSONObject decisions) throws Exception {
        JCRSessionWrapper edit = JCRSessionFactory.getInstance()
                .getCurrentUserSession("default", Locale.forLanguageTag(language));
        JCRNodeWrapper site = edit.getNode(path).getResolveSite();
        JCRNodeWrapper editSite = edit.getNode(site.getPath());

        String current = editSite.hasProperty(ROBOTS_PROP) ? editSite.getProperty(ROBOTS_PROP).getString() : "";

        Map<String, String> wanted = new LinkedHashMap<>();
        if (decisions != null) {
            for (String token : decisions.keySet()) {
                wanted.put(token, decisions.optString(token, ""));
            }
        }
        String proposed = RobotsEditor.apply(current, wanted);

        // Where each crawler stands in the file as it is stored right now.
        RobotsRules rules = RobotsRules.parse(current);
        JSONArray agents = new JSONArray();
        for (Map.Entry<String, String> e : SiteFilesChecker.AI_TOKENS.entrySet()) {
            RobotsRules.Verdict v = rules.evaluate(e.getValue(), "/");
            JSONObject a = new JSONObject();
            a.put("name", e.getKey());
            a.put("token", e.getValue());
            a.put("named", v.namedExplicitly);
            a.put("current", v.allowed ? RobotsEditor.ALLOW : RobotsEditor.BLOCK);
            agents.put(a);
        }

        // The panel runs the crawler check against one representative page to show
        // what the server actually does, beside what robots.txt says it should.
        JCRNodeWrapper home = LlmsGenerator.homeOf(site);

        JSONObject out = new JSONObject();
        out.put("siteKey", site.getName());
        out.put("homePath", home == null ? JSONObject.NULL : home.getPath());
        out.put("current", current);
        out.put("proposed", proposed);
        out.put("changed", !proposed.equals(current));
        out.put("mixinPresent", editSite.isNodeType(ROBOTS_MIXIN));
        out.put("currentIsPlaceholder", ROBOTS_DEFAULT.equals(current.trim()));
        out.put("canWrite", editSite.hasPermission("jcr:modifyProperties"));
        out.put("agents", agents);
        return out;
    }

    /**
     * Writes exactly what it was handed, then publishes so the community
     * servlet can serve it. Shared by both files: they have the same shape, one
     * textarea property on the site node behind one mixin.
     */
    private JSONObject applySiteFile(String path, String language, String content, String mixin, String prop)
            throws Exception {
        if (content == null || content.trim().isEmpty()) {
            JSONObject err = new JSONObject();
            err.put("error", "empty content");
            return err;
        }
        JCRSessionWrapper edit = JCRSessionFactory.getInstance()
                .getCurrentUserSession("default", Locale.forLanguageTag(language));
        JCRNodeWrapper site = edit.getNode(path).getResolveSite();
        JCRNodeWrapper editSite = edit.getNode(site.getPath());

        if (!editSite.isNodeType(mixin)) {
            // Without the mixin the community servlet has nothing to read and the
            // file stays a 404, however good the text is.
            editSite.addMixin(mixin);
        }
        editSite.setProperty(prop, content);
        edit.save();

        JCRPublicationService.getInstance().publishByMainId(
                editSite.getIdentifier(), "default", "live",
                Collections.singleton(language), false, null);

        JSONObject out = new JSONObject();
        out.put("written", true);
        out.put("sitePath", site.getPath());
        out.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
        return out;
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
