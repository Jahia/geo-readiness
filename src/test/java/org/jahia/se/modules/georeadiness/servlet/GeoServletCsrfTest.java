package org.jahia.se.modules.georeadiness.servlet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.servlet.http.HttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The CSRF control of every servlet of the module. Each refusal has a test that the mutant
 * "accept it anyway" fails: a substring content-type test, a missing Sec-Fetch-Site check, a missing
 * Origin check.
 */
class GeoServletCsrfTest {

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "Application/JSON ; charset=utf-8"})
    void acceptsTheJsonMediaType(String type) {
        assertThat(GeoServlet.isJsonMediaType(type)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain; application/json", "text/plain;a=application/json", "application/jsonx",
            "multipart/form-data", ""})
    void refusesEverythingElseIncludingASimpleRequestCarryingTheWord(String type) {
        assertThat(GeoServlet.isJsonMediaType(type)).isFalse();
    }

    private static HttpServletRequest request(String secFetchSite, String origin, String host, String forwardedHost) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getHeader("Sec-Fetch-Site")).thenReturn(secFetchSite);
        when(r.getHeader("Origin")).thenReturn(origin);
        when(r.getHeader("Host")).thenReturn(host);
        when(r.getHeader("X-Forwarded-Host")).thenReturn(forwardedHost);
        return r;
    }

    @Test
    void sameOriginBrowserCallsPass() {
        assertThat(GeoServlet.isCrossSite(request("same-origin", "http://localhost:8080", "localhost:8080", null))).isFalse();
        assertThat(GeoServlet.isCrossSite(request(null, "https://www.example.com", "www.example.com", null))).isFalse();
        assertThat(GeoServlet.isCrossSite(request(null, "https://www.example.com", "www.example.com:443", null))).isFalse();
    }

    @Test
    void nonBrowserCallsPass() {
        assertThat(GeoServlet.isCrossSite(request(null, null, "localhost:8080", null))).isFalse();
    }

    @Test
    void aProxyThatRewritesHostStillPasses() {
        assertThat(GeoServlet.isCrossSite(request(null, "https://www.example.com", "jahia:8080", "www.example.com"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cross-site", "same-site"})
    void theBrowsersOwnVerdictRefuses(String verdict) {
        assertThat(GeoServlet.isCrossSite(request(verdict, "http://localhost:8080", "localhost:8080", null))).isTrue();
    }

    @Test
    void aForeignOriginRefusesEvenWithoutSecFetch() {
        assertThat(GeoServlet.isCrossSite(request(null, "https://evil.example", "www.example.com", null))).isTrue();
        assertThat(GeoServlet.isCrossSite(request(null, "https://shop.example.com", "www.example.com", null))).isTrue();
        assertThat(GeoServlet.isCrossSite(request(null, "null", "www.example.com", null))).isTrue();
    }
}
