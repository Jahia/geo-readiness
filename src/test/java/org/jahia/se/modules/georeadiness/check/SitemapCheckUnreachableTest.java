package org.jahia.se.modules.georeadiness.check;

import org.jahia.se.modules.georeadiness.util.FetchGuard;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/** A sitemap the server cannot reach is reported as unreachable, with the failure's class and nothing more. */
class SitemapCheckUnreachableTest {

    @AfterEach
    void untrust() {
        FetchGuard.trustBase("");
    }

    @Test
    void recordsTheExceptionClassOfAFailedFetch() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        // Nothing listens on that port any more: the connection is refused.
        String base = "http://127.0.0.1:" + port;
        FetchGuard.trustBase(base);

        JSONObject out = SitemapCheck.check("/sites/demo", "en", base, 2000, 10_000);

        assertThat(out.getBoolean("present")).isFalse();
        assertThat(out.getString("reason")).isEqualTo("unreachable");
        assertThat(out.getString("error")).isEqualTo("ConnectException");
        assertThat(out.getString("url")).isEqualTo(base + "/sitemap.xml");
    }

    @Test
    void robotsTxtThatCannotBeFetchedClaimsNothing() {
        SiteFilesChecker.Fetched f = new SiteFilesChecker.Fetched();
        f.error = "ConnectException";
        JSONObject robots = SiteFilesChecker.robots(f, "/");
        assertThat(robots.getBoolean("present")).isFalse();
        assertThat(robots.has("everythingAllowed")).isFalse();
        assertThat(robots.getString("error")).isEqualTo("ConnectException");
    }
}
