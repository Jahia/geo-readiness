package org.jahia.se.modules.georeadiness.util;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;

import javax.jcr.AccessDeniedException;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deciding which site a request is allowed to act on, and where inside it.
 *
 * The site-wide reads in this module run under a system session, because
 * reporting what guest cannot see is the whole point of them. Everything those
 * sessions are pointed at is therefore settled here, in the caller's own
 * session, and settled on the RESOLVED node rather than on the string that
 * arrived: a request carries three values shaped like a path, and JCR reads a
 * relative one the way a filesystem does.
 */
public final class SiteScope {

    /**
     * What the dashboard requires, and only this.
     *
     * jContent's publication actions on a piece of content accept
     * "publication-start" as well, and matching that here was tried and
     * reverted: on a stock site the contributor role holds it, and this screen
     * is not a publication action. It rewrites robots.txt and llms.txt for the
     * whole site and schedules server-side work, so it is site settings, and
     * "publish" is what the dashboard route itself declares in the front end.
     */
    public static final List<String> DASHBOARD =
            Collections.unmodifiableList(Collections.singletonList("publish"));

    /**
     * A language as Jahia names one, allowing both spellings in use: the tag
     * form ({@code fr-BE}) and the node-name form ({@code pt_BR}). Anything
     * else is refused before it reaches the repository, because the value ends
     * up as a node name under a system session.
     */
    private static final Pattern LANGUAGE = Pattern.compile("[a-zA-Z]{2,8}([-_][a-zA-Z0-9]{1,8})*");

    private SiteScope() {
    }

    /** True when {@code language} is a language and not a path. */
    public static boolean isLanguage(String language) {
        return language != null && LANGUAGE.matcher(language).matches();
    }

    /**
     * The path of the site owning {@code path}, resolved with the caller's own
     * editing session, returned only when the caller holds one of
     * {@code anyOf} on that site.
     *
     * @throws PathNotFoundException when the caller cannot reach the node, or it is not in a site
     * @throws AccessDeniedException when the caller holds none of the permissions
     */
    public static String require(String path, String language, List<String> anyOf)
            throws RepositoryException {
        JCRSessionWrapper edit = session(language);
        JCRNodeWrapper site = edit.getNode(path).getResolveSite();

        // getResolveSite falls back to the system site and then to null, so
        // neither outcome may be taken for the site that was asked about.
        if (site == null || !site.isNodeType("jnt:virtualsite")) {
            throw new PathNotFoundException("no site resolves " + path);
        }
        for (String permission : anyOf) {
            if (site.hasPermission(permission)) {
                return site.getPath();
            }
        }
        throw new AccessDeniedException(anyOf + " required on " + site.getPath());
    }

    /**
     * The canonical path of the subtree a scan may cover: {@code sitePath} when
     * no scope was given, otherwise the scope, resolved in the caller's session
     * and confirmed to sit inside the site.
     *
     * Resolved rather than prefix-matched, because {@code /sites/a/../b} starts
     * with {@code /sites/a/} as a string and names another site as a path. JCR
     * hands back the canonical path, and that is what is compared and returned.
     *
     * @throws PathNotFoundException when the caller cannot reach it, or it lies outside the site
     */
    public static String requireWithin(String sitePath, String scope, String language)
            throws RepositoryException {
        if (scope == null || scope.trim().isEmpty()) {
            return sitePath;
        }
        String resolved = session(language).getNode(scope.trim()).getPath();

        // The trailing slash is required rather than decorative: without it
        // /sites/abc passes as a node of /sites/ab.
        if (!resolved.equals(sitePath) && !resolved.startsWith(sitePath + "/")) {
            throw new PathNotFoundException(resolved + " is outside " + sitePath);
        }
        return resolved;
    }

    private static JCRSessionWrapper session(String language) throws RepositoryException {
        return JCRSessionFactory.getInstance()
                .getCurrentUserSession("default", Locale.forLanguageTag(language));
    }
}
