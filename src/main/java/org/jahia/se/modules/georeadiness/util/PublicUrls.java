package org.jahia.se.modules.georeadiness.util;

import org.jahia.bin.Jahia;
import org.jahia.services.SpringContextSingleton;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.seo.urlrewrite.UrlRewriteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The public url of a node, as Jahia itself would print it.
 *
 * We do not guess the url shape. {@link JCRNodeWrapper#getUrl()} gives the
 * canonical render url and the outbound rewriter turns it into exactly what a
 * link to this page looks like in the rendered site: vanity url when one
 * resolves on that host, cms prefix and site key dropped when the server-name
 * rules allow it. That is the address a crawler follows.
 *
 * Shared by every servlet in this module so the crawler check and the generated
 * llms.txt can never disagree about what a page's address is.
 */
public final class PublicUrls {

    private static final Logger logger = LoggerFactory.getLogger(PublicUrls.class);

    private PublicUrls() {
    }

    /**
     * Absolute public url for a node with no HTTP request in hand, for the
     * scheduled scan. The rewriter still runs, over a mock request built from
     * the site's own server name, so a scanned url is the same url a visitor
     * would follow rather than one we assembled by hand.
     */
    public static String forNode(JCRNodeWrapper node, String configuredBase) throws Exception {
        String base = base(node, null, configuredBase);
        java.net.URL u = new java.net.URL(base);
        int port = u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
        HttpServletRequest req = MockHttp.request(u.getProtocol(), u.getHost(), port, Jahia.getContextPath());
        return forNode(node, req, MockHttp.response(), configuredBase);
    }

    /** Absolute public url for a node. `configuredBase` is PUBLIC_BASE_URL, blank to derive it. */
    public static String forNode(JCRNodeWrapper node, HttpServletRequest req, HttpServletResponse resp,
            String configuredBase) throws Exception {
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
     * on the site's own host, reuse the request's scheme and port verbatim, which
     * keeps local setups such as http://luxe.local.com:8080 working. Otherwise,
     * when the site has a real server name that differs from the request host
     * (edit host versus public host), assume https on the default port.
     */
    public static String base(JCRNodeWrapper node, HttpServletRequest req, String configuredBase)
            throws javax.jcr.RepositoryException {
        String b = configuredBase;
        if (b == null || b.trim().isEmpty()) {
            String server = node.getResolveSite().getServerName();
            boolean hasHost = server != null && !server.isEmpty() && !"localhost".equalsIgnoreCase(server);
            if (req == null) {
                // No request at all: the scheduled scan. The site's own server name
                // is the only statement of where it lives that we have.
                return hasHost ? "https://" + server : "http://localhost:8080";
            }
            String reqHost = req.getServerName();
            boolean siteHasHost = server != null && !server.isEmpty() && !"localhost".equalsIgnoreCase(server);
            if (!siteHasHost || server.equalsIgnoreCase(reqHost)) {
                int port = req.getServerPort();
                boolean defaultPort = ("http".equals(req.getScheme()) && port == 80)
                        || ("https".equals(req.getScheme()) && port == 443);
                b = req.getScheme() + "://" + reqHost + (defaultPort ? "" : ":" + port);
            } else {
                b = "https://" + server;
            }
        }
        return b.replaceAll("/+$", "");
    }
}
