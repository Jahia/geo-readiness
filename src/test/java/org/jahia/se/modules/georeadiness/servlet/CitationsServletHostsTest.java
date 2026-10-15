package org.jahia.se.modules.georeadiness.servlet;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The hosts setting, checked label by label. */
class CitationsServletHostsTest {

    @Test
    void acceptsHostNames() {
        assertThat(CitationsServlet.isHostName("example.com")).isTrue();
        assertThat(CitationsServlet.isHostName("fr.my-site.example.co.uk")).isTrue();
        assertThat(CitationsServlet.isHostName("a1.b2")).isTrue();
    }

    @Test
    void refusesWhatIsNotAHostName() {
        assertThat(CitationsServlet.isHostName("localhost")).isFalse();
        assertThat(CitationsServlet.isHostName("example..com")).isFalse();
        assertThat(CitationsServlet.isHostName("example.com.")).isFalse();
        assertThat(CitationsServlet.isHostName("-example.com")).isFalse();
        assertThat(CitationsServlet.isHostName("example-.com")).isFalse();
        assertThat(CitationsServlet.isHostName("exa_mple.com")).isFalse();
        assertThat(CitationsServlet.isHostName("a".repeat(64) + ".com")).isFalse();
        assertThat(CitationsServlet.isHostName(("a".repeat(60) + ".").repeat(5) + "com")).isFalse();
    }

    @Test
    void foldsDeduplicatesAndRefusesOneBadHost() {
        assertThat(CitationsServlet.hosts(new JSONArray().put("WWW.Example.com").put("example.com").put("")))
                .containsExactly("example.com");
        assertThat(CitationsServlet.hosts(new JSONArray().put("example.com").put("not a host"))).isNull();
        assertThat(CitationsServlet.hosts(null)).isEmpty();
    }
}
