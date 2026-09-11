package org.jahia.se.modules.georeadiness.check;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRUserNode;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.services.visibility.VisibilityService;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GEO-19. Published pages that no AI crawler can ever read.
 *
 * A crawler hitting a gated page is served a login form and a cheerful 200. It
 * reports success, and the page is counted as content that works. Only the
 * repository knows the page is invisible, which is why no external audit tool
 * can produce this list.
 *
 * The whole value is in the second half of the story: separating content that
 * is gated **on purpose** from content that is gated **by accident**. A members
 * area is not a defect, and a tool that reports one as a problem gets ignored.
 * So a page whose parent is equally unreadable is treated as part of a
 * deliberately closed branch and reported separately, while a closed page
 * inside an open section is the finding.
 *
 * Readability is decided by asking, not by reasoning about ACLs: the pages are
 * listed with a system session and then read again as `guest`. Whatever the
 * repository denies that session is what a crawler is denied too.
 */
public final class GuestVisibility {

    /** Findings that usually mean somebody forgot something. */
    public static final String ISOLATED = "isolatedRestriction";
    public static final String EXPIRED = "expiredCondition";
    public static final String HIDDEN = "hiddenByCondition";
    /** Not a finding. A closed branch inside a closed branch is a members area. */
    public static final String GATED_BRANCH = "gatedBranch";

    private static final String VISIBILITY_NODE = "j:conditionalVisibility";
    private static final int DEFAULT_MAX = 2000;

    private GuestVisibility() {
    }

    /** One page, for the drawer. Cheap: two session reads and a condition evaluation. */
    public static JSONObject forPage(String path, String language) throws RepositoryException {
        Locale locale = Locale.forLanguageTag(language);
        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live", locale,
                (JCRCallback<JSONObject>) systemSession -> {
                    JSONObject out = new JSONObject();
                    JCRNodeWrapper node;
                    try {
                        node = systemSession.getNode(path);
                    } catch (PathNotFoundException e) {
                        out.put("published", false);
                        return out;
                    }
                    out.put("published", true);
                    return asGuest(locale, guest -> {
                        boolean readable = canRead(guest, path);
                        out.put("guestReadable", readable);
                        String parent = parentOf(path);
                        boolean parentReadable = parent == null || canRead(guest, parent);
                        out.put("parentReadable", parentReadable);
                        String kind = classify(readable, parentReadable, node);
                        out.put("kind", kind == null ? JSONObject.NULL : kind);
                        describeConditions(node, out);
                        return out;
                    });
                });
    }

    /** Every published page of a site, for the dashboard. */
    public static JSONObject scanSite(String sitePath, String language, int maxPages) throws RepositoryException {
        Locale locale = Locale.forLanguageTag(language);
        int cap = maxPages > 0 ? maxPages : DEFAULT_MAX;

        return JCRTemplate.getInstance().doExecuteWithSystemSession(null, "live", locale,
                (JCRCallback<JSONObject>) systemSession -> {
                    // Listed as system so the scan sees everything, including what
                    // guest cannot. That asymmetry is the entire point.
                    List<JCRNodeWrapper> pages = publishedPages(systemSession, sitePath, cap + 1);
                    boolean truncated = pages.size() > cap;
                    if (truncated) {
                        pages = pages.subList(0, cap);
                    }

                    final List<JCRNodeWrapper> scanned = pages;
                    return asGuest(locale, guest -> {
                        JSONObject out = new JSONObject();
                        out.put("sitePath", sitePath);
                        out.put("language", language);
                        out.put("scanned", scanned.size());
                        out.put("truncated", truncated);

                        JSONArray findings = new JSONArray();
                        JSONArray deliberate = new JSONArray();
                        // Readability is asked once per path and reused: a branch of
                        // 400 pages would otherwise re-ask for every child.
                        Map<String, Boolean> readable = new LinkedHashMap<>();
                        int readableCount = 0;

                        for (JCRNodeWrapper page : scanned) {
                            String path = page.getPath();
                            boolean r = readable.computeIfAbsent(path, p -> canRead(guest, p));
                            if (r) {
                                readableCount++;
                            }
                            String parent = parentOf(path);
                            boolean parentReadable = parent == null
                                    || readable.computeIfAbsent(parent, p -> canRead(guest, p));

                            String kind = classify(r, parentReadable, page);
                            if (kind == null) {
                                continue;
                            }
                            JSONObject row = row(page, kind);
                            describeConditions(page, row);
                            if (GATED_BRANCH.equals(kind)) {
                                deliberate.put(row);
                            } else {
                                findings.put(row);
                            }
                        }

                        out.put("guestReadable", readableCount);
                        out.put("findings", findings);
                        out.put("deliberate", deliberate);
                        return out;
                    });
                });
    }

    /**
     * null means nothing to report. Order matters: an unreadable page is judged
     * on its ACLs first, because a visibility condition on a page nobody can
     * reach is moot.
     */
    private static String classify(boolean readable, boolean parentReadable, JCRNodeWrapper node) {
        if (!readable) {
            // Closed inside a closed branch is a members area. Closed inside an
            // open section is the one somebody forgot.
            return parentReadable ? ISOLATED : GATED_BRANCH;
        }
        if (!hasConditions(node)) {
            return null;
        }
        if (expiredEnd(node) != null) {
            return EXPIRED;
        }
        try {
            return VisibilityService.getInstance().matchesConditions(node) ? null : HIDDEN;
        } catch (Exception e) {
            // A condition we cannot evaluate is not a finding we can defend.
            return null;
        }
    }

    private static boolean canRead(JCRSessionWrapper guest, String path) {
        try {
            guest.getNode(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<JCRNodeWrapper> publishedPages(JCRSessionWrapper session, String sitePath, int limit)
            throws RepositoryException {
        List<JCRNodeWrapper> out = new ArrayList<>();
        String sql = "select * from [jnt:page] as p where isdescendantnode(p, '"
                + sitePath.replace("'", "''") + "')";
        Query q = session.getWorkspace().getQueryManager().createQuery(sql, Query.JCR_SQL2);
        q.setLimit(limit);
        NodeIterator it = q.execute().getNodes();
        while (it.hasNext()) {
            out.add((JCRNodeWrapper) it.nextNode());
        }
        return out;
    }

    private static boolean hasConditions(JCRNodeWrapper node) {
        try {
            return node.hasNode(VISIBILITY_NODE) && node.getNode(VISIBILITY_NODE).getNodes().hasNext();
        } catch (RepositoryException e) {
            return false;
        }
    }

    /**
     * The end date of a condition that has already passed, or null.
     *
     * Read generically off any condition carrying `j:end` rather than matching a
     * node type, so a custom condition with the same property is handled and an
     * unknown one is ignored instead of guessed at.
     */
    private static Calendar expiredEnd(JCRNodeWrapper node) {
        try {
            if (!node.hasNode(VISIBILITY_NODE)) {
                return null;
            }
            NodeIterator it = node.getNode(VISIBILITY_NODE).getNodes();
            Calendar now = Calendar.getInstance();
            while (it.hasNext()) {
                JCRNodeWrapper c = (JCRNodeWrapper) it.nextNode();
                if (c.hasProperty("j:end")) {
                    Calendar end = c.getProperty("j:end").getDate();
                    if (end != null && end.before(now)) {
                        return end;
                    }
                }
            }
        } catch (RepositoryException e) {
            return null;
        }
        return null;
    }

    private static void describeConditions(JCRNodeWrapper node, JSONObject out) {
        Calendar end = expiredEnd(node);
        out.put("expiredOn", end == null ? JSONObject.NULL : end.toInstant().toString());
        out.put("hasConditions", hasConditions(node));
    }

    private static JSONObject row(JCRNodeWrapper page, String kind) {
        JSONObject o = new JSONObject();
        o.put("path", page.getPath());
        o.put("kind", kind);
        String title = null;
        try {
            title = page.hasProperty("jcr:title") ? page.getProperty("jcr:title").getString() : null;
        } catch (RepositoryException e) {
            // fall through to the node name
        }
        o.put("title", title == null || title.trim().isEmpty() ? page.getName() : title);
        return o;
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? null : path.substring(0, i);
    }

    /**
     * Runs the callback in a live session owned by `guest`, which is what a
     * crawler is.
     *
     * It must be {@code doExecute(user, ...)} and never
     * {@code doExecuteWithSystemSessionAsUser}. The latter is a system session
     * merely attributed to a user: it bypasses ACLs, so every page reads as
     * visible and this whole check silently reports that nothing is wrong.
     * Verified on 8.2.3.2 against a page with ACL inheritance broken: the system
     * variant read it, this one throws PathNotFoundException, and an anonymous
     * HTTP request gets a 404.
     */
    private static <T> T asGuest(Locale locale, GuestWork<T> work) throws RepositoryException {
        JCRUserNode guestNode = JahiaUserManagerService.getInstance()
                .lookupUser(JahiaUserManagerService.GUEST_USERNAME);
        if (guestNode == null) {
            throw new RepositoryException("guest user not found");
        }
        JahiaUser guest = guestNode.getJahiaUser();
        return JCRTemplate.getInstance().doExecute(guest, "live", locale, (JCRCallback<T>) work::run);
    }

    @FunctionalInterface
    private interface GuestWork<T> {
        T run(JCRSessionWrapper session) throws RepositoryException;
    }
}
