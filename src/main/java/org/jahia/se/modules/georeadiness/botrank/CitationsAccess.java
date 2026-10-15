package org.jahia.se.modules.georeadiness.botrank;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Who may read and change the BotRank data of a site, checked with the caller's own session.
 *
 * <ul>
 *   <li><b>Drawer read</b>: the caller can read the page in {@code default}. The figures of a page
 *       describe how AI answers cite a public URL; anyone who edits the page may see them.</li>
 *   <li><b>Settings</b>: {@value #SETTINGS_PERMISSION} on the site. Binding a site to a connection
 *       spends the customer's organisation key, which the server adds to every call; that makes this
 *       permission the only gate of those routes (Cortex {@code jahia-authz-defense}, proxy secret).
 *       On 8.2.3.2 the site-administrator role holds it and the editor role does not.</li>
 *   <li>Both require the module to be enabled on the site: a site that does not use it gets 403,
 *       whatever the caller's rights.</li>
 * </ul>
 */
public final class CitationsAccess {

    /** The permission the settings routes and the scheduled job require on the site. */
    public static final String SETTINGS_PERMISSION = "site-admin";
    static final String MODULE = "geo-readiness";
    private static final String INSTALLED_MODULES = "j:installedModules";

    private CitationsAccess() {
    }

    /**
     * Whether a site still enables the module, read with a system session: used by the scheduled job,
     * which has no caller.
     *
     * @param sitePath {@code /sites/<key>}
     * @return false when the site is gone or no longer lists the module
     */
    public static boolean moduleEnabled(String sitePath) {
        try {
            return Boolean.TRUE.equals(org.jahia.services.content.JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(
                    null, "default", null, (org.jahia.services.content.JCRCallback<Boolean>) session -> {
                        JCRNodeWrapper site = session.getNode(sitePath);
                        if (!site.hasProperty(INSTALLED_MODULES)) {
                            return false;
                        }
                        for (Value v : site.getProperty(INSTALLED_MODULES).getValues()) {
                            if (MODULE.equals(v.getString())) {
                                return true;
                            }
                        }
                        return false;
                    }));
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** The site of a node, as the caller sees it. */
    public static final class Site {
        public final String path;
        public final String key;
        public final boolean moduleEnabled;
        public final boolean mayChangeSettings;
        /** Server name and aliases, as candidates for the hosts setting. */
        public final List<String> serverNames;

        Site(String path, String key, boolean moduleEnabled, boolean mayChangeSettings, List<String> serverNames) {
            this.path = path;
            this.key = key;
            this.moduleEnabled = moduleEnabled;
            this.mayChangeSettings = mayChangeSettings;
            this.serverNames = serverNames;
        }
    }

    /**
     * Resolves the site of a node with the caller's session in {@code default}.
     *
     * @param path     a node path under {@code /sites/}
     * @param language the language of the session
     * @return the site and what the caller may do on it
     * @throws javax.jcr.PathNotFoundException when the caller cannot read the node
     * @throws RepositoryException on any other repository failure
     */
    public static Site siteOf(String path, String language) throws RepositoryException {
        JCRSessionWrapper session = JCRSessionFactory.getInstance()
                .getCurrentUserSession("default", Locale.forLanguageTag(language));
        JCRNodeWrapper node = session.getNode(path);
        JCRSiteNode site = node.getResolveSite();
        if (site == null || !site.isNodeType("jnt:virtualsite")) {
            throw new javax.jcr.PathNotFoundException("no site");
        }
        boolean enabled = false;
        if (site.hasProperty(INSTALLED_MODULES)) {
            for (Value v : site.getProperty(INSTALLED_MODULES).getValues()) {
                if (MODULE.equals(v.getString())) {
                    enabled = true;
                    break;
                }
            }
        }
        List<String> names = new ArrayList<>();
        String serverName = site.getServerName();
        if (serverName != null && !serverName.isEmpty()) {
            names.add(serverName);
        }
        List<String> aliases = site.getServerNameAliases();
        if (aliases != null) {
            names.addAll(aliases);
        }
        return new Site(site.getPath(), site.getSiteKey(), enabled, site.hasPermission(SETTINGS_PERMISSION), names);
    }
}
