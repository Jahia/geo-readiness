package org.jahia.se.modules.georeadiness.util;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;

import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import java.util.Locale;

/**
 * Deciding which site a request is allowed to act on.
 *
 * The site-wide reads in this module run under a system session, because
 * reporting what guest cannot see is the whole point of them. The site those
 * sessions are pointed at is therefore settled here, once, in the caller's own
 * session: what they can reach, and what they are allowed to do with it. A path
 * in a request body decides nothing on its own.
 */
public final class SiteScope {

    /**
     * The permission the dashboard route declares in the front end. The
     * endpoints the dashboard calls hold to the same one, so the two cannot
     * drift apart.
     */
    public static final String DASHBOARD = "publish";

    private SiteScope() {
    }

    /**
     * The path of the site owning {@code path}, resolved with the caller's own
     * editing session, returned only when the caller holds {@code permission}
     * on that site.
     *
     * @throws javax.jcr.PathNotFoundException when the caller cannot reach the node
     * @throws AccessDeniedException           when the caller does not hold the permission
     */
    public static String require(String path, String language, String permission)
            throws RepositoryException {
        JCRSessionWrapper edit = JCRSessionFactory.getInstance()
                .getCurrentUserSession("default", Locale.forLanguageTag(language));
        JCRNodeWrapper site = edit.getNode(path).getResolveSite();
        if (!site.hasPermission(permission)) {
            throw new AccessDeniedException(permission + " required on " + site.getPath());
        }
        return site.getPath();
    }

    /**
     * True when {@code scope} is blank, names the site itself, or names a node
     * inside it.
     *
     * The trailing slash is required rather than decorative: without it
     * {@code /sites/abc} passes as a node of {@code /sites/ab}.
     */
    public static boolean covers(String sitePath, String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return true;
        }
        String candidate = scope.trim();
        return candidate.equals(sitePath) || candidate.startsWith(sitePath + "/");
    }
}
