package org.jahia.se.modules.georeadiness.servlet;

import org.jahia.se.modules.georeadiness.botrank.BotRankConnection;
import org.jahia.se.modules.georeadiness.botrank.BotRankConnections;
import org.jahia.se.modules.georeadiness.botrank.BotRankException;
import org.jahia.se.modules.georeadiness.botrank.CitationScheduler;
import org.jahia.se.modules.georeadiness.botrank.CitationStore;
import org.jahia.se.modules.georeadiness.botrank.CitationsAccess;
import org.jahia.se.modules.georeadiness.botrank.CitedUrls;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.usermanager.JahiaUser;
import org.json.JSONArray;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * BotRank citations: the page drawer's read and the site settings, at
 * {@code /modules/geo-readiness/citations}.
 *
 * <table>
 *   <caption>Routes</caption>
 *   <tr><th>Route</th><th>Who</th><th>Does</th></tr>
 *   <tr><td>{@code GET ?path&language}</td><td>a user who can read the page</td>
 *       <td>the stored figures of that page and the freshness of the data; never calls BotRank</td></tr>
 *   <tr><td>{@code POST {action, path, language, ...}}</td>
 *       <td>{@value CitationsAccess#SETTINGS_PERMISSION} on the site</td>
 *       <td>{@code status}, {@code projects}, {@code save}, {@code syncNow}, {@code map}, {@code unmap}</td></tr>
 * </table>
 *
 * <p>Both refuse guests (401), a site that does not enable the module (403) and a node the caller
 * cannot read (403). The POST routes require {@code application/json}, which is the CSRF control of
 * every servlet of this module (see {@link GeoServlet}); {@code projects} and {@code syncNow}, the two
 * that reach BotRank, are rate-limited per user. Answers never contain the API key: a connection is
 * shown by id, label and problem only.</p>
 */
@Component(service = {HttpServlet.class, Servlet.class},
        property = {
                "alias=/geo-readiness/citations",
                "allow-api-token=true",
                "service.description=GEO readiness - BotRank citations",
                "service.vendor=Jahia Solutions Group SA"
        },
        immediate = true)
public class CitationsServlet extends GeoServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(CitationsServlet.class);

    private static final int MAX_BODY = 64 * 1024;
    private static final long WINDOW_MS = 600_000L;
    private static final int MAX_REMOTE_CALLS = 20;
    private static final Pattern PROJECT = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** One DNS label; a host is checked label by label, so no pattern repeats a group. */
    private static final Pattern HOST_LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final String STATUS = "status";
    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private final transient BotRankConnections connections;

    @Activate
    public CitationsServlet(@Reference BotRankConnections connections) {
        this.connections = connections;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
        try {
            read(req, resp);
        } catch (IOException | RuntimeException e) {
            // Nothing may leave a servlet method: the container would answer with a stack trace.
            logger.debug("could not write the citations read", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) {
        try {
            settings(req, resp);
        } catch (IOException | RuntimeException e) {
            logger.debug("could not write the citations settings response", e);
        }
    }

    private void read(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            if (requireUser(resp) == null) {
                return;
            }
            String path = req.getParameter("path");
            String language = req.getParameter("language");
            if (!isWellFormed(path, language, resp)) {
                return;
            }
            CitationsAccess.Site site = site(path, language, resp);
            if (site == null) {
                return;
            }
            // The drawer opens on pages and main resources only. Any other readable node under /sites
            // (a site user's own profile node, for one) would otherwise read the site's sync status.
            if (resolvePage(path, language) == null) {
                deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
                return;
            }
            CitationStore.Settings settings = CitationStore.settings(site.path);
            JSONObject out = new JSONObject();
            out.put("canConfigure", site.mayChangeSettings);
            if (!settings.isComplete() || !settings.enabled) {
                out.put("configured", false);
                writeJson(resp, HttpServletResponse.SC_OK, out);
                return;
            }
            JSONObject status = CitationStore.status(site.path);
            out.put("configured", true);
            out.put(STATUS, status.get(STATUS));
            out.put("syncedAt", status.get("syncedAt"));
            out.put("latestAnswerAt", status.get("latestAnswerAt"));
            out.put("citedPages", status.get("citedPages"));
            out.put("published", isPublished(path, language));
            JSONObject page = CitationStore.page(site.path, path, language);
            out.put("page", page == null ? JSONObject.NULL : page);
            writeJson(resp, HttpServletResponse.SC_OK, out);
        } catch (RepositoryException | RuntimeException e) {
            logger.warn("citations read failed: {}", e.toString());
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "operation failed");
        }
    }

    private void settings(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            JahiaUser user = requireUser(resp);
            if (user == null) {
                return;
            }
            JSONObject body = jsonBody(req, resp, MAX_BODY);
            if (body == null) {
                return;
            }
            String path = body.optString("path", "");
            String language = body.optString("language", "en");
            if (!isWellFormed(path, language, resp)) {
                return;
            }
            CitationsAccess.Site site = site(path, language, resp);
            if (site == null) {
                return;
            }
            if (!site.mayChangeSettings) {
                deny(resp, HttpServletResponse.SC_FORBIDDEN, "site administration required");
                return;
            }
            String action = body.optString("action", "");
            switch (action) {
                case STATUS:
                    writeJson(resp, HttpServletResponse.SC_OK, status(site));
                    break;
                case "projects":
                    projects(site, user, body, resp);
                    break;
                case "save":
                    save(site, user, body, resp);
                    break;
                case "syncNow":
                    syncNow(site, user, resp);
                    break;
                case "map":
                    map(site, body, language, resp);
                    break;
                case "unmap":
                    unmap(site, body, resp);
                    break;
                default:
                    deny(resp, HttpServletResponse.SC_BAD_REQUEST, "unknown action");
            }
        } catch (RepositoryException | RuntimeException e) {
            logger.warn("citations settings failed: {}", e.toString());
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "operation failed");
        }
    }

    /** The site of the path, or null once a refusal is written. */
    private static CitationsAccess.Site site(String path, String language, HttpServletResponse resp)
            throws IOException, RepositoryException {
        CitationsAccess.Site site;
        try {
            site = CitationsAccess.siteOf(path, language);
        } catch (PathNotFoundException | AccessDeniedException e) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "cannot read node");
            return null;
        }
        if (!site.moduleEnabled) {
            deny(resp, HttpServletResponse.SC_FORBIDDEN, "module not enabled on this site");
            return null;
        }
        return site;
    }

    private JSONObject status(CitationsAccess.Site site) throws RepositoryException {
        JSONObject out = CitationStore.status(site.path);
        JSONArray list = new JSONArray();
        for (BotRankConnection c : connections.visibleTo(site.key)) {
            list.put(new JSONObject().put("id", c.id()).put("label", c.label())
                    .put("problem", connections.problemOf(c) == null ? JSONObject.NULL : connections.problemOf(c)));
        }
        out.put("connections", list);
        out.put("serverNames", new JSONArray(site.serverNames));
        Date next = CitationScheduler.nextRun(site.path);
        out.put("nextRun", next == null ? JSONObject.NULL : next.toInstant().toString());
        out.put("limits", new JSONObject().put("hosts", CitationStore.MAX_HOSTS).put("mappings", CitationStore.MAX_MAPPINGS));
        return out;
    }

    private void projects(CitationsAccess.Site site, JahiaUser user, JSONObject body, HttpServletResponse resp)
            throws IOException {
        BotRankConnection c = connections.forSite(body.optString("connection", ""), site.key);
        if (c == null) {
            deny(resp, HttpServletResponse.SC_NOT_FOUND, "unknown connection");
            return;
        }
        if (!rateLimitOk(user.getUserKey(), WINDOW_MS, MAX_REMOTE_CALLS)) {
            deny(resp, 429, "rate limit");
            return;
        }
        try {
            JSONArray list = new JSONArray();
            c.client().projects().forEach(p -> list.put(new JSONObject().put("id", p.id).put("name", p.name)));
            writeJson(resp, HttpServletResponse.SC_OK, new JSONObject().put("projects", list));
        } catch (BotRankException e) {
            writeJson(resp, HttpServletResponse.SC_BAD_GATEWAY,
                    new JSONObject().put("error", e.getMessage()).put("type", e.type()));
        }
    }

    private void save(CitationsAccess.Site site, JahiaUser user, JSONObject body, HttpServletResponse resp)
            throws IOException, RepositoryException {
        String connection = body.optString("connection", "");
        BotRankConnection c = connections.forSite(connection, site.key);
        if (c == null) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "unknown connection");
            return;
        }
        String project = body.optString("project", "");
        if (!PROJECT.matcher(project).matches()) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "invalid project");
            return;
        }
        List<String> hosts = hosts(body.optJSONArray("hosts"));
        if (hosts == null) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "invalid host");
            return;
        }
        if (hosts.isEmpty() || hosts.size() > CitationStore.MAX_HOSTS) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "between 1 and " + CitationStore.MAX_HOSTS + " hosts");
            return;
        }
        String time = body.optString("time", "06:30");
        if (!TIME.matcher(time).matches()) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "invalid time");
            return;
        }
        boolean enabled = body.optBoolean("enabled", false);
        CitationStore.Settings s = CitationStore.saveSettings(site.path, connection, project, hosts, enabled, time,
                user.getLocalPath());
        try {
            if (s.enabled) {
                CitationScheduler.scheduleDaily(site.path, s.time, s.owner);
            } else {
                CitationScheduler.unschedule(site.path);
            }
        } catch (Exception e) {
            logger.warn("Could not schedule the BotRank sync of {}: {}", site.path, e.getMessage());
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "saved, but the daily sync could not be scheduled");
            return;
        }
        writeJson(resp, HttpServletResponse.SC_OK, status(site));
    }

    /** The folded, distinct hosts of the request, or null when one is not a host name. */
    static List<String> hosts(JSONArray raw) {
        List<String> hosts = new ArrayList<>();
        for (int i = 0; raw != null && i < raw.length(); i++) {
            String h = CitedUrls.foldHost(raw.optString(i));
            if (h.isEmpty() || hosts.contains(h)) {
                continue;
            }
            if (!isHostName(h)) {
                return null;
            }
            hosts.add(h);
        }
        return hosts;
    }

    /** At least two DNS labels, 253 characters at most. */
    static boolean isHostName(String h) {
        if (h.length() > 253) {
            return false;
        }
        String[] labels = h.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!HOST_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    private void syncNow(CitationsAccess.Site site, JahiaUser user, HttpServletResponse resp)
            throws IOException, RepositoryException {
        CitationStore.Settings s = CitationStore.settings(site.path);
        if (!s.isComplete()) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "the site is not connected yet");
            return;
        }
        if (CitationStore.STATUS_RUNNING.equals(CitationStore.status(site.path).optString(STATUS))) {
            deny(resp, HttpServletResponse.SC_CONFLICT, "a sync is already running");
            return;
        }
        if (!rateLimitOk(user.getUserKey() + ":sync", WINDOW_MS, 6)) {
            deny(resp, 429, "rate limit");
            return;
        }
        try {
            CitationScheduler.runOnce(site.path, user.getLocalPath(), 0, 1);
        } catch (Exception e) {
            logger.warn("Could not start the BotRank sync of {}: {}", site.path, e.getMessage());
            deny(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "the sync could not start");
            return;
        }
        writeJson(resp, HttpServletResponse.SC_ACCEPTED, status(site));
    }

    private void map(CitationsAccess.Site site, JSONObject body, String language, HttpServletResponse resp)
            throws IOException, RepositoryException {
        String key = CitedUrls.key(body.optString("url", ""));
        CitationStore.Settings s = CitationStore.settings(site.path);
        if (key == null || !CitedUrls.onHosts(CitedUrls.host(key), s.hosts)) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "the URL is not on a host of this site");
            return;
        }
        // Resolved, not compared as a string: the stored path is the node's own, and its site is checked.
        JCRNodeWrapper target = resolvePage(body.optString("target", ""), language);
        String targetPath;
        try {
            targetPath = target == null || !site.path.equals(target.getResolveSite().getPath()) ? null : target.getPath();
        } catch (RepositoryException e) {
            targetPath = null;
        }
        if (targetPath == null) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "the target is not a page of this site");
            return;
        }
        if (!CitationStore.saveMapping(site.path, key, targetPath + "@" + language)) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "too many mappings");
            return;
        }
        writeJson(resp, HttpServletResponse.SC_OK, status(site));
    }

    private void unmap(CitationsAccess.Site site, JSONObject body, HttpServletResponse resp)
            throws IOException, RepositoryException {
        String key = CitedUrls.key(body.optString("url", ""));
        if (key == null) {
            deny(resp, HttpServletResponse.SC_BAD_REQUEST, "invalid URL");
            return;
        }
        CitationStore.saveMapping(site.path, key, null);
        writeJson(resp, HttpServletResponse.SC_OK, status(site));
    }

    /** The node, when the caller can read it in default and it is a page or a main resource; else null. */
    private static JCRNodeWrapper resolvePage(String path, String language) {
        if (path == null || !path.startsWith("/sites/")) {
            return null;
        }
        try {
            JCRNodeWrapper n = JCRSessionFactory.getInstance()
                    .getCurrentUserSession("default", Locale.forLanguageTag(language)).getNode(path);
            return n.isNodeType("jnt:page") || n.isNodeType("jmix:mainResource") ? n : null;
        } catch (RepositoryException e) {
            return null;
        }
    }

    /** Whether the caller can read the page in live: a page never published has no citations to show. */
    private static boolean isPublished(String path, String language) {
        try {
            JCRSessionFactory.getInstance().getCurrentUserSession("live", Locale.forLanguageTag(language)).getNode(path);
            return true;
        } catch (RepositoryException e) {
            return false;
        }
    }
}
