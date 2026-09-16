package org.jahia.se.modules.georeadiness.check;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.jcr.RepositoryException;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterisation tests for the parts of GuestVisibility that decide things.
 *
 * Deliberately NOT a test of scanSite. That method is a session chain -
 * doExecuteWithSystemSession, a JCR query, then a nested guest session - and
 * mocking it end to end costs a hundred lines of wiring to verify that the mocks
 * return what they were told to. The logic worth pinning does not live there.
 *
 * It lives in the helpers, and every one of them takes a NODE rather than a
 * session: classify, isNoindex, hasConditions, expiredEnd, describeConditions,
 * row, parentOf. A node is one cheap mock with a few stubbed reads, so these get
 * real coverage of the decisions at a fraction of the cost. What is left
 * unpinned - canRead, publishedPages, asGuest - is thin plumbing whose one
 * subtle property is stated in a comment on asGuest rather than in code, and
 * could not be asserted without a live repository anyway.
 *
 * Worth knowing when reading a green suite: the Cypress e2e specs call the
 * guestVisibility endpoint three times, but ONLY inside rate-limit.spec.ts, to
 * spend the rate budget. They assert the call returned 200. Nothing anywhere
 * else asserts what this scan actually reports, which is why these exist.
 */
class GuestVisibilityTest {

    // The helpers are private statics; reached by reflection rather than by
    // widening them, because the production visibility is correct as it stands
    // and a test should not be the reason to loosen it.
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = GuestVisibility.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static String classify(boolean readable, boolean parentReadable, JCRNodeWrapper node)
            throws Exception {
        return (String) call("classify",
                new Class<?>[]{boolean.class, boolean.class, JCRNodeWrapper.class},
                readable, parentReadable, node);
    }

    private static String parentOf(String path) throws Exception {
        return (String) call("parentOf", new Class<?>[]{String.class}, path);
    }

    private static JSONObject row(JCRNodeWrapper page, String kind) throws Exception {
        return (JSONObject) call("row", new Class<?>[]{JCRNodeWrapper.class, String.class}, page, kind);
    }

    /** A node that is not noindex and carries no visibility conditions. */
    private static JCRNodeWrapper plainNode() throws RepositoryException {
        JCRNodeWrapper n = mock(JCRNodeWrapper.class);
        when(n.isNodeType(anyString())).thenReturn(false);
        when(n.hasNode(anyString())).thenReturn(false);
        when(n.hasProperty(anyString())).thenReturn(false);
        when(n.getPath()).thenReturn("/sites/acme/home/page");
        when(n.getName()).thenReturn("page");
        return n;
    }

    @Nested
    @DisplayName("classify decides what, if anything, is worth reporting")
    class Classify {

        @Test
        @DisplayName("unreadable inside a readable parent is the one somebody forgot")
        void unreadableUnderReadableParent_isIsolated() throws Exception {
            assertThat(classify(false, true, plainNode())).isEqualTo(GuestVisibility.ISOLATED);
        }

        @Test
        @DisplayName("unreadable inside an unreadable parent is a members area, not a mistake")
        void unreadableUnderUnreadableParent_isGatedBranch() throws Exception {
            assertThat(classify(false, false, plainNode())).isEqualTo(GuestVisibility.GATED_BRANCH);
        }

        @Test
        @DisplayName("ACLs are judged before conditions, because a condition on an unreachable page is moot")
        void unreadable_ignoresNoindexAndConditions() throws Exception {
            // The node is noindex AND unreadable. The comment on classify states
            // the order explicitly; this pins it, because a refactor that moved
            // the noindex check first would relabel every gated page.
            JCRNodeWrapper n = plainNode();
            when(n.isNodeType("jmix:noindex")).thenReturn(true);

            assertThat(classify(false, false, n)).isEqualTo(GuestVisibility.GATED_BRANCH);
        }

        @Test
        @DisplayName("a readable, unremarkable page is nothing to report")
        void readablePlainPage_isNotAFinding() throws Exception {
            assertThat(classify(true, true, plainNode())).isNull();
        }

        @Test
        @DisplayName("noindex is reported, and is checked last so it never masks a real defect")
        void readableNoindexPage_isNoindex() throws Exception {
            JCRNodeWrapper n = plainNode();
            when(n.isNodeType("jmix:noindex")).thenReturn(true);

            assertThat(classify(true, true, n)).isEqualTo(GuestVisibility.NOINDEX);
        }
    }

    @Nested
    @DisplayName("parentOf, which decides whether a finding is isolated or a whole branch")
    class ParentOf {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "/sites/acme/home/page, /sites/acme/home",
            "/sites/acme/home,      /sites/acme",
            "/sites/acme,           /sites",
            "/sites,                "
        })
        @DisplayName("walks up one segment, and stops at the root")
        void walksUpOneSegment(String path, String expected) throws Exception {
            assertThat(parentOf(path)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a root-level path has no parent rather than an empty one")
        void rootLevelPath_hasNoParent() throws Exception {
            // i <= 0 rather than i < 0: "/x" must not yield "", which would then
            // be looked up as a node and quietly read as unreadable, turning
            // every top-level page into a gatedBranch.
            assertThat(parentOf("/x")).isNull();
        }
    }

    @Nested
    @DisplayName("row, the shape the dashboard lists")
    class Row {

        @Test
        @DisplayName("prefers jcr:title and falls back to the node name")
        void title_prefersJcrTitleThenName() throws Exception {
            JCRNodeWrapper n = plainNode();
            JCRPropertyWrapper p = mock(JCRPropertyWrapper.class);
            when(p.getString()).thenReturn("Our Story");
            when(n.hasProperty("jcr:title")).thenReturn(true);
            when(n.getProperty("jcr:title")).thenReturn(p);

            assertThat(row(n, GuestVisibility.ISOLATED).getString("title")).isEqualTo("Our Story");
            assertThat(row(plainNode(), GuestVisibility.ISOLATED).getString("title")).isEqualTo("page");
        }

        @Test
        @DisplayName("a blank title falls back to the name rather than rendering as empty")
        void blankTitle_fallsBackToName() throws Exception {
            JCRNodeWrapper n = plainNode();
            JCRPropertyWrapper p = mock(JCRPropertyWrapper.class);
            when(p.getString()).thenReturn("   ");
            when(n.hasProperty("jcr:title")).thenReturn(true);
            when(n.getProperty("jcr:title")).thenReturn(p);

            assertThat(row(n, GuestVisibility.ISOLATED).getString("title")).isEqualTo("page");
        }

        @Test
        @DisplayName("a repository that cannot answer for the title falls back rather than failing the scan")
        void titleReadFailure_fallsBackToName() throws Exception {
            JCRNodeWrapper n = plainNode();
            when(n.hasProperty("jcr:title")).thenReturn(true);
            when(n.getProperty("jcr:title")).thenThrow(new RepositoryException("gone"));

            assertThat(row(n, GuestVisibility.ISOLATED).getString("title")).isEqualTo("page");
        }

        @Test
        @DisplayName("carries the path and the kind exactly as given")
        void carriesPathAndKind() throws Exception {
            JSONObject o = row(plainNode(), GuestVisibility.EXPIRED);

            assertThat(o.getString("path")).isEqualTo("/sites/acme/home/page");
            assertThat(o.getString("kind")).isEqualTo(GuestVisibility.EXPIRED);
        }
    }
}
