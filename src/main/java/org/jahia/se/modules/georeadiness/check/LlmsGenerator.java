package org.jahia.se.modules.georeadiness.check;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an llms.txt body from the site's own published page tree.
 *
 * Deterministic on purpose. No model call, no external service: the same site
 * produces the same file every time, and an editor can read the rule and
 * predict the output. The module claims to need nothing from any vendor, and
 * generating this file is where that claim would otherwise quietly break.
 *
 * The shape follows the llms.txt convention:
 *
 *   # Site title
 *   > one-line summary
 *   ## Section
 *   - [Page](absolute url): description
 *
 * Rules, in one place so they can be argued with:
 *  - only pages published in LIVE are listed, because llms.txt is a public file
 *  - the home page leads the first section
 *  - a level-1 page with published children becomes its own section
 *  - pages hidden from the navigation are skipped, being usually utility pages
 *  - descriptions come from jcr:description, never invented
 *  - the whole file is capped, because a 4000-line llms.txt helps nobody
 */
public final class LlmsGenerator {

    private static final int MAX_LINKS = 200;
    private static final int MAX_DESC = 200;

    /** Resolves a node to its public absolute url. Supplied by the servlet. */
    public interface UrlResolver {
        String urlOf(JCRNodeWrapper node) throws Exception;
    }

    private LlmsGenerator() {
    }

    public static String generate(JCRNodeWrapper site, JCRSessionWrapper liveSession, String language,
            UrlResolver urls) throws Exception {
        StringBuilder sb = new StringBuilder();
        int[] budget = {MAX_LINKS};

        sb.append("# ").append(titleOf(site, site.getName())).append("\n");

        String summary = firstNonEmpty(prop(site, "j:description"), prop(site, "jcr:description"));
        if (summary != null) {
            sb.append("\n> ").append(oneLine(summary, MAX_DESC)).append("\n");
        }

        JCRNodeWrapper home = homeOf(site);
        List<JCRNodeWrapper> level1 = home == null ? new ArrayList<>() : pageChildren(home);

        // First section: the home page plus every level-1 page that has no children
        // of its own. These are the pages a visitor reaches straight from the menu.
        StringBuilder main = new StringBuilder();
        if (home != null) {
            appendLink(main, home, language, urls, budget);
        }
        for (JCRNodeWrapper p : level1) {
            if (pageChildren(p).isEmpty()) {
                appendLink(main, p, language, urls, budget);
            }
        }
        if (main.length() > 0) {
            sb.append("\n## ").append(titleOf(home, "Main pages")).append("\n\n").append(main);
        }

        // Then one section per level-1 page that groups others under it.
        for (JCRNodeWrapper p : level1) {
            List<JCRNodeWrapper> kids = pageChildren(p);
            if (kids.isEmpty()) {
                continue;
            }
            StringBuilder sec = new StringBuilder();
            appendLink(sec, p, language, urls, budget);
            for (JCRNodeWrapper k : kids) {
                appendLink(sec, k, language, urls, budget);
            }
            if (sec.length() > 0) {
                sb.append("\n## ").append(titleOf(p, p.getName())).append("\n\n").append(sec);
            }
        }

        // Finally the page groups that live beside the home page rather than under
        // it, such as legal or landing pages. These are usually jnt:navMenuText,
        // a grouping node that is not itself a page, so looking only for pages
        // here silently drops every page underneath them.
        for (JCRNodeWrapper other : groupChildren(site)) {
            if (home != null && other.getPath().equals(home.getPath())) {
                continue;
            }
            StringBuilder sec = new StringBuilder();
            if (isPage(other)) {
                appendLink(sec, other, language, urls, budget);
            }
            for (JCRNodeWrapper k : pagesUnder(other, 2)) {
                appendLink(sec, k, language, urls, budget);
            }
            if (sec.length() > 0) {
                sb.append("\n## ").append(titleOf(other, other.getName())).append("\n\n").append(sec);
            }
        }

        return sb.toString();
    }

    private static void appendLink(StringBuilder sb, JCRNodeWrapper page, String language, UrlResolver urls,
            int[] budget) throws Exception {
        if (page == null || budget[0] <= 0 || isHidden(page)) {
            return;
        }
        String url = urls.urlOf(page);
        if (url == null || url.isEmpty()) {
            return;
        }
        budget[0]--;
        sb.append("- [").append(escape(titleOf(page, page.getName()))).append("](").append(url).append(")");
        String d = firstNonEmpty(prop(page, "jcr:description"), prop(page, "j:description"));
        if (d != null) {
            sb.append(": ").append(oneLine(d, MAX_DESC));
        }
        sb.append("\n");
    }

    /**
     * Site children that can hold pages: pages themselves, and the navMenuText
     * grouping nodes Jahia uses for sections that have no landing page of their own.
     */
    private static List<JCRNodeWrapper> groupChildren(JCRNodeWrapper site) {
        List<JCRNodeWrapper> out = new ArrayList<>();
        if (site == null) {
            return out;
        }
        try {
            for (JCRNodeWrapper c : site.getNodes()) {
                if (isPage(c) || c.isNodeType("jnt:navMenuText")) {
                    out.add(c);
                }
            }
        } catch (RepositoryException e) {
            // A branch we cannot read is a branch we do not advertise.
        }
        return out;
    }

    /** Pages at any depth up to maxDepth below a grouping node. */
    private static List<JCRNodeWrapper> pagesUnder(JCRNodeWrapper parent, int maxDepth) {
        List<JCRNodeWrapper> out = new ArrayList<>();
        if (parent == null || maxDepth <= 0) {
            return out;
        }
        try {
            for (JCRNodeWrapper c : parent.getNodes()) {
                if (isPage(c)) {
                    out.add(c);
                    out.addAll(pagesUnder(c, maxDepth - 1));
                } else if (c.isNodeType("jnt:navMenuText")) {
                    out.addAll(pagesUnder(c, maxDepth - 1));
                }
            }
        } catch (RepositoryException e) {
            // ignored on purpose, see above
        }
        return out;
    }

    private static boolean isPage(JCRNodeWrapper n) {
        try {
            return n.isNodeType("jnt:page");
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** Published children only. The live session is the filter: absent means unpublished. */
    private static List<JCRNodeWrapper> pageChildren(JCRNodeWrapper parent) {
        List<JCRNodeWrapper> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        try {
            for (JCRNodeWrapper c : parent.getNodes()) {
                if (c.isNodeType("jnt:page")) {
                    out.add(c);
                }
            }
        } catch (RepositoryException e) {
            // A branch we cannot read is a branch we do not advertise.
        }
        return out;
    }

    private static JCRNodeWrapper homeOf(JCRNodeWrapper site) {
        for (JCRNodeWrapper c : pageChildren(site)) {
            try {
                if (c.isNodeType("jnt:page") && c.hasProperty("j:isHomePage")
                        && c.getProperty("j:isHomePage").getBoolean()) {
                    return c;
                }
            } catch (RepositoryException e) {
                // keep looking
            }
        }
        for (JCRNodeWrapper c : pageChildren(site)) {
            if ("home".equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    private static boolean isHidden(JCRNodeWrapper n) {
        try {
            return n.hasProperty("j:hideFromNavigationMenu")
                    && n.getProperty("j:hideFromNavigationMenu").getBoolean();
        } catch (RepositoryException e) {
            return false;
        }
    }

    private static String titleOf(JCRNodeWrapper n, String fallback) {
        if (n == null) {
            return humanise(fallback);
        }
        String t = prop(n, "jcr:title");
        if (t == null) {
            t = n.getDisplayableName();
        }
        return t == null || t.trim().isEmpty() ? humanise(fallback) : t.trim();
    }

    /**
     * Last resort when a node carries no title in this language. A node name is
     * a slug, so "landing-pages" becomes "Landing pages". Still not a title, but
     * it does not look like a bug in the generated file.
     */
    private static String humanise(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String s = name.replace('-', ' ').replace('_', ' ').trim();
        return s.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + s.substring(1);
    }

    private static String prop(JCRNodeWrapper n, String name) {
        try {
            return n.hasProperty(name) ? n.getProperty(name).getString() : null;
        } catch (RepositoryException e) {
            return null;
        }
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) {
            if (s != null && !s.trim().isEmpty()) {
                return s.trim();
            }
        }
        return null;
    }

    /** Markdown list items are one per line, so a description must not carry newlines. */
    private static String oneLine(String s, int max) {
        String out = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return out.length() <= max ? out : out.substring(0, max).trim() + "…";
    }

    private static String escape(String s) {
        return s.replace("[", "\\[").replace("]", "\\]");
    }
}
