package org.jahia.se.modules.georeadiness.botrank;

import org.jahia.se.modules.georeadiness.check.ScanStore;
import org.jahia.se.modules.georeadiness.util.SiteScope;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Where the BotRank citation data of a site lives, under the module's per-site store.
 *
 * <pre>
 * /sites/&lt;key&gt;/geo-readiness              (nt:unstructured + jmix:nolive, see ScanStore)
 *     botrank/                               settings and the state of the last sync
 *         pages/                             replaced as a whole by every sync
 *             &lt;sha1 of jcrPath@language&gt;   one cited page: its figures as JSON blobs
 * </pre>
 *
 * <p>Written and read with a system session in {@code default}, like {@link ScanStore}: a scheduled job
 * has no user, and the figures describe the site, not anything the caller owns. Every reader is gated
 * at the servlet (the drawer reads one page the caller can read; the settings need {@code site-admin}).
 * {@code jmix:nolive} on the parent keeps all of it out of the live workspace. No CND: plain
 * {@code nt:unstructured}, as the rest of the store.</p>
 *
 * <p>Thread safety: stateless; JCR serialises the writes of one save.</p>
 */
public final class CitationStore {

    static final String NODE = "botrank";
    static final String PAGES = "pages";

    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    /** A run older than this is considered dead (bundle stopped mid-run) and may be replaced. */
    static final long MAX_RUN_AGE_MS = 2L * 60 * 60 * 1000;
    /** Bounds on what an administrator can store. */
    public static final int MAX_HOSTS = 10;
    public static final int MAX_MAPPINGS = 500;

    private CitationStore() {
    }

    /** The settings of one site, read-only. */
    public static final class Settings {
        public final String connection;
        public final String project;
        public final List<String> hosts;
        public final boolean enabled;
        /** HH:mm in Europe/Paris. */
        public final String time;
        /** Local path of the user who saved the settings; the job re-checks their permission. */
        public final String owner;
        /** Folded cited URL ({@link CitedUrls#key}) to {@code jcrPath@language}. */
        public final JSONObject mappings;

        Settings(String connection, String project, List<String> hosts, boolean enabled, String time,
                String owner, JSONObject mappings) {
            this.connection = connection;
            this.project = project;
            this.hosts = Collections.unmodifiableList(hosts);
            this.enabled = enabled;
            this.time = time;
            this.owner = owner;
            this.mappings = mappings;
        }

        public boolean isComplete() {
            return !connection.isEmpty() && !project.isEmpty() && !hosts.isEmpty();
        }
    }

    /** One enabled site, as the lifecycle needs it to reinstall a trigger. */
    public static final class Scheduled {
        public final String sitePath;
        public final String time;
        public final String owner;

        Scheduled(String sitePath, String time, String owner) {
            this.sitePath = sitePath;
            this.time = time;
            this.owner = owner;
        }
    }

    /**
     * The settings of a site; empty values when nothing was saved.
     *
     * @param sitePath {@code /sites/<key>}
     * @return the settings
     * @throws RepositoryException on a repository failure
     */
    public static Settings settings(String sitePath) throws RepositoryException {
        return in(sitePath, (node, session) -> settingsOf(node));
    }

    /**
     * Saves the settings of a site. Mappings are kept.
     *
     * @return the settings as stored
     * @throws RepositoryException on a repository failure
     */
    public static Settings saveSettings(String sitePath, String connection, String project, List<String> hosts,
            boolean enabled, String time, String owner) throws RepositoryException {
        return in(sitePath, (node, session) -> {
            node.setProperty("brConnection", connection);
            node.setProperty("brProject", project);
            node.setProperty("brHosts", hosts.toArray(new String[0]));
            node.setProperty("brEnabled", enabled);
            node.setProperty("brTime", time);
            node.setProperty("brOwner", owner);
            session.save();
            return settingsOf(node);
        });
    }

    /**
     * Adds or removes one manual mapping of a cited URL to a page.
     *
     * @param urlKey  the folded cited URL
     * @param target  {@code jcrPath@language}, or null to remove the mapping
     * @return false when adding would exceed {@link #MAX_MAPPINGS}
     * @throws RepositoryException on a repository failure
     */
    public static boolean saveMapping(String sitePath, String urlKey, String target) throws RepositoryException {
        return in(sitePath, (node, session) -> {
            JSONObject m = jsonObject(node, "brMappings");
            if (target == null) {
                m.remove(urlKey);
            } else {
                if (!m.has(urlKey) && m.length() >= MAX_MAPPINGS) {
                    return false;
                }
                m.put(urlKey, target);
            }
            node.setProperty("brMappings", m.toString());
            session.save();
            return true;
        });
    }

    /**
     * The settings and the state of the last sync, as the settings panel shows them.
     *
     * @return {@code {settings, status, startedAt, syncedAt, latestAnswerAt, message, citedPages,
     *         unresolved, mappings}}
     * @throws RepositoryException on a repository failure
     */
    public static JSONObject status(String sitePath) throws RepositoryException {
        return in(sitePath, (node, session) -> {
            Settings s = settingsOf(node);
            JSONObject out = new JSONObject();
            out.put("connection", s.connection);
            out.put("project", s.project);
            out.put("hosts", new JSONArray(s.hosts));
            out.put("enabled", s.enabled);
            out.put("time", s.time);
            out.put("status", str(node, "brStatus", STATUS_IDLE));
            out.put("startedAt", date(node, "brStartedAt"));
            out.put("syncedAt", date(node, "brSyncedAt"));
            out.put("latestAnswerAt", str(node, "brLatestAnswerAt", null) == null ? JSONObject.NULL
                    : str(node, "brLatestAnswerAt", null));
            out.put("message", str(node, "brMessage", ""));
            out.put("citedPages", node.hasProperty("brCitedPages") ? node.getProperty("brCitedPages").getLong() : 0);
            out.put("unresolved", jsonArray(node, "brUnresolved"));
            out.put("mappings", jsonObject(node, "brMappings"));
            return out;
        });
    }

    /**
     * The stored figures of one page, or null when the last sync found no citation of it.
     *
     * @param jcrPath  the node path
     * @param language the language of the drawer
     * @return the page entry, as {@link CitationSync} built it
     * @throws RepositoryException on a repository failure
     */
    public static JSONObject page(String sitePath, String jcrPath, String language) throws RepositoryException {
        return in(sitePath, (node, session) -> {
            if (!node.hasNode(PAGES)) {
                return null;
            }
            JCRNodeWrapper pages = node.getNode(PAGES);
            String name = pageName(jcrPath, language);
            if (!pages.hasNode(name)) {
                return null;
            }
            Object v = json(pages.getNode(name), "data");
            return v instanceof JSONObject ? (JSONObject) v : null;
        });
    }

    /**
     * Marks the start of a sync, unless one is already running and fresh.
     *
     * @return true when the caller now owns the run
     * @throws RepositoryException on a repository failure
     */
    public static boolean tryBegin(String sitePath) throws RepositoryException {
        return in(sitePath, (node, session) -> {
            if (STATUS_RUNNING.equals(str(node, "brStatus", STATUS_IDLE)) && node.hasProperty("brStartedAt")) {
                long age = System.currentTimeMillis() - node.getProperty("brStartedAt").getDate().getTimeInMillis();
                if (age < MAX_RUN_AGE_MS) {
                    return false;
                }
            }
            node.setProperty("brStatus", STATUS_RUNNING);
            node.setProperty("brStartedAt", Calendar.getInstance());
            node.setProperty("brMessage", "");
            session.save();
            return true;
        });
    }

    /**
     * Tells the store a running sync is alive, so {@link #tryBegin} never takes it for dead and starts a
     * second run beside it.
     *
     * @throws RepositoryException on a repository failure
     */
    public static void heartbeat(String sitePath) throws RepositoryException {
        in(sitePath, (node, session) -> {
            node.setProperty("brStartedAt", Calendar.getInstance());
            session.save();
            return null;
        });
    }

    /**
     * Stops the daily sync of a site and says why, keeping the pages of the last sync.
     *
     * @param message a sentence for administrators
     * @throws RepositoryException on a repository failure
     */
    public static void disable(String sitePath, String message) throws RepositoryException {
        in(sitePath, (node, session) -> {
            node.setProperty("brEnabled", false);
            node.setProperty("brStatus", STATUS_FAILED);
            node.setProperty("brMessage", message);
            session.save();
            return null;
        });
    }

    /**
     * Replaces every stored page with the result of a sync and marks it done.
     *
     * @param pages          page entries keyed by {@code jcrPath@language}
     * @param unresolved     the cited URLs no page matched, most cited first
     * @param latestAnswerAt BotRank's time of the latest counted answer, or null
     * @param message        a note for administrators (retries, skipped pages), or empty
     * @throws RepositoryException on a repository failure
     */
    public static void finish(String sitePath, List<JSONObject> pages, JSONArray unresolved, String latestAnswerAt,
            String message) throws RepositoryException {
        in(sitePath, (node, session) -> {
            if (node.hasNode(PAGES)) {
                node.getNode(PAGES).remove();
            }
            JCRNodeWrapper root = node.addNode(PAGES, "nt:unstructured");
            for (JSONObject p : pages) {
                JCRNodeWrapper n = root.addNode(pageName(p.getString("jcrPath"), p.getString("language")),
                        "nt:unstructured");
                n.setProperty("jcrPath", p.getString("jcrPath"));
                n.setProperty("language", p.getString("language"));
                n.setProperty("data", p.toString());
            }
            node.setProperty("brStatus", STATUS_DONE);
            node.setProperty("brSyncedAt", Calendar.getInstance());
            node.setProperty("brLatestAnswerAt", latestAnswerAt);
            node.setProperty("brCitedPages", (long) pages.size());
            node.setProperty("brUnresolved", unresolved.toString());
            node.setProperty("brMessage", message);
            session.save();
            return null;
        });
    }

    /**
     * Marks the current sync as failed; the pages of the previous sync stay.
     *
     * @param message a sentence for administrators that names no secret
     * @throws RepositoryException on a repository failure
     */
    public static void fail(String sitePath, String message) throws RepositoryException {
        in(sitePath, (node, session) -> {
            node.setProperty("brStatus", STATUS_FAILED);
            node.setProperty("brMessage", message);
            session.save();
            return null;
        });
    }

    /**
     * Puts a sync that waits for BotRank back to idle, with a note, keeping the previous pages.
     *
     * @throws RepositoryException on a repository failure
     */
    public static void postpone(String sitePath, String message) throws RepositoryException {
        in(sitePath, (node, session) -> {
            node.setProperty("brStatus", STATUS_IDLE);
            node.setProperty("brMessage", message);
            session.save();
            return null;
        });
    }

    /**
     * Every enabled and complete site of the instance.
     *
     * @return what the lifecycle reinstalls
     * @throws RepositoryException on a repository failure
     */
    public static List<Scheduled> scheduled() throws RepositoryException {
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "default",
                (JCRCallback<List<Scheduled>>) session -> {
                    List<Scheduled> out = new ArrayList<>();
                    JCRNodeWrapper sites;
                    try {
                        sites = session.getNode("/sites");
                    } catch (PathNotFoundException e) {
                        return out;
                    }
                    NodeIterator it = sites.getNodes();
                    while (it.hasNext()) {
                        JCRNodeWrapper site = (JCRNodeWrapper) it.nextNode();
                        String rel = ScanStore.STORE_NODE + "/" + NODE;
                        if (!site.isNodeType("jnt:virtualsite") || !site.hasNode(rel)) {
                            continue;
                        }
                        Settings s = settingsOf(site.getNode(rel));
                        if (s.enabled && s.isComplete()) {
                            out.add(new Scheduled(site.getPath(), s.time, s.owner));
                        }
                    }
                    return out;
                });
    }

    /** The node name of a page entry: a hash, so any path and language fit one segment. */
    static String pageName(String jcrPath, String language) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1")
                    .digest((jcrPath + "@" + language).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("p");
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 missing", e);
        }
    }

    private interface Work<T> {
        T run(JCRNodeWrapper node, JCRSessionWrapper session) throws RepositoryException;
    }

    private static <T> T in(String sitePath, Work<T> work) throws RepositoryException {
        if (sitePath == null || !sitePath.matches("/sites/[A-Za-z0-9_-]+")) {
            throw new PathNotFoundException("not a site path");
        }
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "default",
                (JCRCallback<T>) session -> {
                    JCRNodeWrapper site = session.getNode(sitePath);
                    JCRNodeWrapper store = site.hasNode(ScanStore.STORE_NODE)
                            ? site.getNode(ScanStore.STORE_NODE)
                            : site.addNode(ScanStore.STORE_NODE, "nt:unstructured");
                    if (!store.isNodeType("jmix:nolive")) {
                        store.addMixin("jmix:nolive");
                    }
                    JCRNodeWrapper node = store.hasNode(NODE) ? store.getNode(NODE)
                            : store.addNode(NODE, "nt:unstructured");
                    return work.run(node, session);
                });
    }

    private static Settings settingsOf(JCRNodeWrapper n) throws RepositoryException {
        List<String> hosts = new ArrayList<>();
        if (n.hasProperty("brHosts")) {
            for (javax.jcr.Value v : n.getProperty("brHosts").getValues()) {
                hosts.add(v.getString());
            }
        }
        String time = str(n, "brTime", "06:30");
        return new Settings(str(n, "brConnection", ""), str(n, "brProject", ""), hosts,
                n.hasProperty("brEnabled") && n.getProperty("brEnabled").getBoolean(),
                time.matches("\\d{2}:\\d{2}") ? time : "06:30", str(n, "brOwner", ""),
                jsonObject(n, "brMappings"));
    }

    private static String str(JCRNodeWrapper n, String p, String def) throws RepositoryException {
        return n.hasProperty(p) ? n.getProperty(p).getString() : def;
    }

    private static Object date(JCRNodeWrapper n, String p) throws RepositoryException {
        return n.hasProperty(p) ? n.getProperty(p).getDate().toInstant().toString() : JSONObject.NULL;
    }

    private static Object json(JCRNodeWrapper n, String p) throws RepositoryException {
        if (!n.hasProperty(p)) {
            return null;
        }
        try {
            String raw = n.getProperty(p).getString().trim();
            return raw.startsWith("[") ? new JSONArray(raw) : new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject jsonObject(JCRNodeWrapper n, String p) throws RepositoryException {
        Object v = json(n, p);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }

    private static JSONArray jsonArray(JCRNodeWrapper n, String p) throws RepositoryException {
        Object v = json(n, p);
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }

    /** True when the language is one the store may use as a key part. */
    static boolean isLanguage(String language) {
        return SiteScope.isLanguage(language);
    }
}
