package org.jahia.se.modules.georeadiness.util;

import org.jahia.bin.Jahia;
import org.jahia.services.SpringContextSingleton;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.seo.urlrewrite.UrlRewriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import javax.servlet.http.HttpServletResponse;

/**
 * The public url of a node, as Jahia itself would print it.
 *
 * We do not guess the url shape. {@link JCRNodeWrapper#getUrl()} gives the
 * canonical render url and the outbound rewriter turns it into exactly what a
 * link to this page looks like in the rendered site: cms prefix and site key
 * dropped when the server-name rules allow it. That is the address a crawler
 * follows.
 *
 * A default vanity url takes precedence, read from the repository rather than
 * left to the rewriter, which does not reliably produce one.
 *
 * Shared by every servlet in this module so the crawler check and the generated
 * llms.txt can never disagree about what a page's address is.
 */
public final class PublicUrls {

    private static final Logger logger = LoggerFactory.getLogger(PublicUrls.class);

    /**
     * Where a site that names no server is assumed to live: a stock local
     * Jahia. A constant rather than a literal because {@link FetchGuard} has to
     * recognise it - it is the one private address this module reaches without
     * anybody configuring it, and nothing a caller sends can steer a base here.
     */
    public static final String LOCAL_FALLBACK = "http://localhost:8080";

    private static final String HTTPS = "https";

    private PublicUrls() {
    }

    /**
     * Absolute public url for a node with no HTTP request in hand, for the
     * scheduled scan. The rewriter still runs, over a mock request built from
     * the site's own server name, so a scanned url is the same url a visitor
     * would follow rather than one we assembled by hand.
     */
    public static String forNode(JCRNodeWrapper node, String configuredBase) throws Exception {
        return forNode(node, configuredBase, true);
    }

    /** Request-free variant, with the vanity url optionally ignored. */
    public static String forNode(JCRNodeWrapper node, String configuredBase, boolean preferVanity)
            throws Exception {
        String base = base(node, null, configuredBase);
        java.net.URL u = new java.net.URL(base);
        int port = u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
        HttpServletRequest req = MockHttp.request(u.getProtocol(), u.getHost(), port, Jahia.getContextPath());
        return forNode(node, req, MockHttp.response(), configuredBase, preferVanity);
    }

    /** Absolute public url for a node. `configuredBase` is PUBLIC_BASE_URL, blank to derive it. */
    public static String forNode(JCRNodeWrapper node, HttpServletRequest req, HttpServletResponse resp,
            String configuredBase) throws Exception {
        return forNode(node, req, resp, configuredBase, true);
    }

    /**
     * As above, with `preferVanity` false to get the address the page would have
     * without its vanity url - the one Jahia redirects *from*.
     *
     * The sitemap module does not use vanity urls, so a page that has one is
     * listed there under this address while it actually answers on the other.
     * Knowing both is what lets that be reported as one finding, "the sitemap
     * lists an address that redirects", rather than as an unexplained pair of a
     * missing page and an unknown entry.
     */
    public static String forNode(JCRNodeWrapper node, HttpServletRequest req, HttpServletResponse resp,
            String configuredBase, boolean preferVanity) throws Exception {
        // A default vanity url IS the page's address: Jahia answers 200 there
        // and 301s the tree path to it. The outbound rewriter does not always
        // produce one - it did not here, with the site's own host in the request
        // - and the consequences of missing it are severe and silent. Every
        // crawler fetch gets the 301 instead of the page, so the score collapses
        // and reports "no text in the initial HTML"; the sitemap comparison
        // reports the page missing because the sitemap lists the vanity; and the
        // link graph cannot match the links pointing at it. Ask the repository,
        // which is the authority, before falling back to the rewriter.
        String vanity = preferVanity ? defaultVanity(node) : null;
        if (vanity != null) {
            return base(node, req, configuredBase) + vanity;
        }
        String path = node.getUrl();
        try {
            UrlRewriteService rewriter = (UrlRewriteService) SpringContextSingleton.getBean("UrlRewriteService");
            String rewritten = rewriter.rewriteOutbound(path, req, resp);
            if (rewritten != null && !rewritten.isEmpty()) {
                path = fixContextPath(rewritten, req);
            }
        } catch (Exception e) {
            // Fall back to the render url. It is longer but always valid.
            logger.debug("Outbound rewrite failed for {}, using render url", path, e);
        }
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return base(node, req, configuredBase) + path;
    }

    /**
     * The node's default vanity url for the session's language, or null.
     *
     * Only the default one, and only when active: a non-default alias is a
     * second address that redirects here, not the address itself.
     */
    private static String defaultVanity(JCRNodeWrapper node) {
        try {
            if (!node.hasNode("vanityUrlMapping")) {
                return null;
            }
            java.util.Locale locale = node.getSession().getLocale();
            String language = locale == null ? null : locale.getLanguage();
            javax.jcr.NodeIterator it = node.getNode("vanityUrlMapping").getNodes();
            while (it.hasNext()) {
                javax.jcr.Node v = it.nextNode();
                if (!v.isNodeType("jnt:vanityUrl")
                        || !v.hasProperty("j:default") || !v.getProperty("j:default").getBoolean()
                        || (v.hasProperty("j:active") && !v.getProperty("j:active").getBoolean())) {
                    continue;
                }
                String lang = v.hasProperty("jcr:language") ? v.getProperty("jcr:language").getString() : null;
                if (language != null && lang != null && !language.equals(lang)) {
                    continue;
                }
                if (v.hasProperty("j:url")) {
                    String url = v.getProperty("j:url").getString();
                    if (url != null && url.startsWith("/")) {
                        String ctx = Jahia.getContextPath() == null ? "" : Jahia.getContextPath();
                        return ctx + url;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("no vanity url for {}", node, e);
        }
        return null;
    }

    /**
     * OSGi servlets are mounted behind the /modules bridge, and the request
     * reports that as its context path. The rewriter prepends it faithfully,
     * which gives /modules/sites/... instead of /sites/.... Swap it for the
     * real webapp context path.
     */
    public static String fixContextPath(String url, HttpServletRequest req) {
        String reqCtx = req.getContextPath() == null ? "" : req.getContextPath();
        String realCtx = Jahia.getContextPath() == null ? "" : Jahia.getContextPath();
        if (!reqCtx.isEmpty() && !reqCtx.equals(realCtx) && (url.equals(reqCtx) || url.startsWith(reqCtx + "/"))) {
            return realCtx + url.substring(reqCtx.length());
        }
        return url;
    }

    /**
     * Scheme, host and port only. The context path is already part of the rewritten path.
     *
     * Priority: the configured base when set. Otherwise, when the editor is already
     * on the site's own host, that host with the scheme and port THIS Jahia answers
     * on - see {@link #jahiaPort} - which keeps local setups such as
     * http://luxe.local.com:8080 working. Otherwise, when the site has a real server
     * name that differs from the request host (edit host versus public host), assume
     * https on the default port.
     */
    public static String base(JCRNodeWrapper node, HttpServletRequest req, String configuredBase)
            throws javax.jcr.RepositoryException {
        String b = configuredBase == null || configuredBase.trim().isEmpty()
                ? derived(node, req)
                : configuredBase;
        return b.replaceAll("/+$", "");
    }

    /**
     * Where the site lives when nothing has been configured, worked out from the
     * repository and from this container - never from the request's Host header.
     */
    private static String derived(JCRNodeWrapper node, HttpServletRequest req)
            throws javax.jcr.RepositoryException {
        JCRSiteNode site = node.getResolveSite();
        String server = site == null ? null : site.getServerName();
        if (server == null || server.isEmpty() || "localhost".equalsIgnoreCase(server)) {
            // The site does not say where it lives, so neither can we. The
            // request cannot answer it either: its Host header is written by
            // whoever sent it. PUBLIC_BASE_URL is the way to say it.
            return LOCAL_FALLBACK;
        }
        // The host is the site's own, always: the request only gets to say WHICH
        // of the site's names is in use, never a name of its own. The scheme and
        // the port are not taken from it at all, see jahiaPort.
        //
        // Which is what the line below now actually does. servedName returns the
        // REPOSITORY's spelling of the name that matched, and that is what gets
        // built into the url; it used to check the request's host and then build
        // with the request's own copy of it. Equal under equalsIgnoreCase is not
        // equal: `Host: WWW.ACME.COM` produced the base `https://WWW.ACME.COM`,
        // and SiteScanServlet persists that base in geoBaseUrl, so a casing a
        // caller chose outlived the request in every later scheduled scan. It
        // also left the only string in the url that a request could influence.
        String host = req == null ? null : servedName(site, req.getServerName());
        if (host == null) {
            // A real server name that differs from the request host: an edit host
            // against a public host. Assume https on the default port.
            return "https://" + server;
        }
        return onThisJahia(host, req);
    }

    /**
     * The site's own host, at the scheme and port THIS Jahia answers on, which
     * keeps local setups such as http://luxe.local.com:8080 working.
     */
    private static String onThisJahia(String host, HttpServletRequest req) {
        int port = jahiaPort(req);
        String scheme = scheme(req, port);
        return scheme + "://" + host + (isDefaultPort(scheme, port) ? "" : ":" + port);
    }

    /** True when the port is the one the scheme implies, so a url need not name it. */
    private static boolean isDefaultPort(String scheme, int port) {
        return ("http".equals(scheme) && port == 80)
                || (HTTPS.equals(scheme) && port == 443);
    }

    /**
     * The port this Jahia answers on, which is emphatically NOT
     * {@code req.getServerPort()}.
     *
     * Per the servlet spec that method returns the part after ':' in the Host
     * header, which the caller writes. {@link #servedName} checks the host name
     * and nothing checked the port, so {@code Host: www.acme.com:6379} produced
     * the base {@code http://www.acme.com:6379} and every fetch this module
     * makes off that base - sixteen crawler agents plus robots.txt and the two
     * llms files - went to that port, reporting status, timing, byte count and
     * the exception class per agent. That is an authenticated port scanner with
     * response reflection, and the scan servlet persisted the value in
     * geoBaseUrl so later scheduled scans kept using it.
     *
     * Both answers here come from the server: Jahia's own site.url.port, which
     * exists for a front port that differs from the connector's (a proxy
     * terminating TLS), then the local port of the socket the request arrived
     * on, which no header can move. A deployment whose public port is neither
     * of those sets PUBLIC_BASE_URL, which is what it is for.
     */
    private static int jahiaPort(HttpServletRequest req) {
        try {
            org.jahia.settings.SettingsBean settings = org.jahia.settings.SettingsBean.getInstance();
            int override = settings == null ? 0 : settings.getSiteURLPortOverride();
            if (override > 0) {
                return override;
            }
        } catch (Exception e) {
            // Not configured, or no settings bean in this context. The socket answers.
            logger.debug("no site url port override, using the local port", e);
        }
        int local = req.getLocalPort();
        if (local > 0) {
            return local;
        }
        return req.isSecure() ? 443 : 80;
    }

    /**
     * http or https for {@code port}, from the connection rather than from the
     * request line: {@code isSecure()} is the connector's own answer, adjusted
     * by Tomcat only for a proxy it has been configured to trust.
     *
     * A well-known port overrides it, because site.url.port is precisely the
     * setting used when the connector is plain http behind a TLS proxy, and
     * {@code http://host:443} would be a base nothing answers on.
     */
    private static String scheme(HttpServletRequest req, int port) {
        if (port == 443) {
            return HTTPS;
        }
        if (port == 80) {
            return "http";
        }
        return req.isSecure() ? HTTPS : "http";
    }

    /** True when {@code host} is one of the names this site answers to. */
    private static String servedName(JCRSiteNode site, String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (host.equalsIgnoreCase(site.getServerName())) {
            return site.getServerName();
        }
        List<String> aliases = site.getServerNameAliases();
        if (aliases != null) {
            for (String alias : aliases) {
                if (host.equalsIgnoreCase(alias)) {
                    return alias;
                }
            }
        }
        return null;
    }
}
