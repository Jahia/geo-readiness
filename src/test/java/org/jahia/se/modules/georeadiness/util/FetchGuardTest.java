package org.jahia.se.modules.georeadiness.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Characterisation tests for {@link FetchGuard#require(String, String)} and
 * {@link FetchGuard#isWithin(String, String)}, which together enforce
 * same-origin fetches and are the last line of defence against the SSRF
 * described in {@link FetchGuard}'s class Javadoc.
 *
 * Only the pure surface is exercised here: {@code open}, {@code
 * requireAllowedTarget} and {@code allowedPorts} touch a real socket, DNS and
 * a JMX MBean server and are deliberately out of scope (they would be
 * environment-dependent and flaky).
 */
class FetchGuardTest {

    @Nested
    class SameOriginAccepted {

        @ParameterizedTest(name = "{0} is within {1}")
        @CsvSource({
                "http://a.com/path1,               http://a.com/path2",
                "https://a.com/x,                  https://a.com/y",
                "http://a.com:8080/x,               http://a.com:8080/y",
                "https://a.com:8443/x,              https://a.com:8443/y",
        })
        void require_sameSchemeHostAndPort_isAccepted(String url, String base) throws Exception {
            URL result = FetchGuard.require(url, base);

            assertThat(result.toString()).isEqualTo(url);
            assertThat(FetchGuard.isWithin(url, base)).isTrue();
        }

        @Test
        void require_hostComparison_isCaseInsensitive() throws Exception {
            assertThat(FetchGuard.isWithin("http://A.Com/x", "http://a.com/y")).isTrue();
            assertThat(FetchGuard.isWithin("http://a.com/x", "http://A.COM/y")).isTrue();
        }

        @Test
        void require_schemeComparison_isCaseInsensitive() throws Exception {
            // java.net.URL itself lowercases the parsed protocol, so an upper- or
            // mixed-case scheme in the input never survives to FetchGuard's own
            // comparison - pinned here regardless of which layer normalises it.
            assertThat(FetchGuard.isWithin("HTTP://a.com/x", "http://a.com/y")).isTrue();
            assertThat(FetchGuard.isWithin("HtTpS://a.com/x", "https://a.com/y")).isTrue();
        }
    }

    @Nested
    class DifferingComponentsRejected {

        @Test
        void require_differentHost_isRejected() {
            assertThatThrownBy(() -> FetchGuard.require("http://evil.com/x", "http://good.com/y"))
                    .isInstanceOf(IOException.class);
            assertThat(FetchGuard.isWithin("http://evil.com/x", "http://good.com/y")).isFalse();
        }

        @Test
        void require_differentPort_isRejected() {
            assertThatThrownBy(() -> FetchGuard.require("http://a.com:8080/x", "http://a.com:9090/y"))
                    .isInstanceOf(IOException.class);
            assertThat(FetchGuard.isWithin("http://a.com:8080/x", "http://a.com:9090/y")).isFalse();
        }

        @Test
        void require_differentScheme_isRejected() {
            assertThatThrownBy(() -> FetchGuard.require("https://a.com/x", "http://a.com/y"))
                    .isInstanceOf(IOException.class);
            assertThat(FetchGuard.isWithin("https://a.com/x", "http://a.com/y")).isFalse();
        }
    }

    @Nested
    class DefaultPorts {

        @Test
        void require_httpImplicitPortAndExplicitPort80_areSameOrigin() throws Exception {
            assertThat(FetchGuard.isWithin("http://a.com/x", "http://a.com:80/y")).isTrue();
            assertThat(FetchGuard.isWithin("http://a.com:80/x", "http://a.com/y")).isTrue();
        }

        @Test
        void require_httpsImplicitPortAndExplicitPort443_areSameOrigin() throws Exception {
            assertThat(FetchGuard.isWithin("https://a.com/x", "https://a.com:443/y")).isTrue();
            assertThat(FetchGuard.isWithin("https://a.com:443/x", "https://a.com/y")).isTrue();
        }

        @Test
        void require_httpPort80AndHttpsPort443_areNotSameOrigin() {
            // Same numeric "default" port for their respective schemes, but the
            // scheme mismatch alone is enough to reject: ports are never compared
            // across schemes.
            assertThat(FetchGuard.isWithin("http://a.com:80/x", "https://a.com:443/y")).isFalse();
        }

        @Test
        void require_httpExplicitPort443_isNotSameOriginAsImplicitHttp() {
            // http on port 443 is a different origin from the implicit http
            // default of 80 - the scheme's own default, not the other scheme's,
            // is what an omitted port resolves to.
            assertThat(FetchGuard.isWithin("http://a.com:443/x", "http://a.com/y")).isFalse();
        }
    }

    @Nested
    class SchemeValidation {

        @ParameterizedTest(name = "[{0}] is rejected")
        @ValueSource(strings = {
                "file:///etc/passwd",
                "ftp://a.com/x",
                "jar:http://a.com/x.jar!/y",
                "gopher://a.com/x",
        })
        void require_nonHttpScheme_isRejected(String url) {
            assertThatThrownBy(() -> FetchGuard.require(url, "http://a.com/y"))
                    .isInstanceOf(IOException.class);
            assertThat(FetchGuard.isWithin(url, "http://a.com/y")).isFalse();
        }
    }

    @Nested
    class NullEmptyAndWhitespaceInput {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        void require_blankUrl_throwsMalformedUrlException(String blank) {
            assertThatThrownBy(() -> FetchGuard.require(blank, "http://a.com/y"))
                    .isInstanceOf(MalformedURLException.class);
            assertThat(FetchGuard.isWithin(blank, "http://a.com/y")).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n"})
        void require_blankBase_throwsMalformedUrlException(String blank) {
            assertThatThrownBy(() -> FetchGuard.require("http://a.com/x", blank))
                    .isInstanceOf(MalformedURLException.class);
            assertThat(FetchGuard.isWithin("http://a.com/x", blank)).isFalse();
        }
    }

    @Nested
    class NoHost {

        @Test
        void require_urlWithEmptyHost_isRejected() {
            // "http:///path" parses with an empty (not null) host; parse() treats
            // that the same as no host at all.
            assertThatThrownBy(() -> FetchGuard.require("http:///path", "http://a.com/y"))
                    .isInstanceOf(MalformedURLException.class);
            assertThat(FetchGuard.isWithin("http:///path", "http://a.com/y")).isFalse();
        }
    }

    @Nested
    class MalformedInput {

        @Test
        void require_notAUrlAtAll_isRejected() {
            assertThatThrownBy(() -> FetchGuard.require("not a url at all", "http://a.com/y"))
                    .isInstanceOf(MalformedURLException.class);
            assertThat(FetchGuard.isWithin("not a url at all", "http://a.com/y")).isFalse();
        }
    }

    @Nested
    class UserInfoInAuthority {

        // Classic origin-confusion vector: does the code follow the pre-@
        // "evil.com" or the actual host "good.com"? java.net.URL parses the
        // authority correctly (host = the part after '@'), and FetchGuard reads
        // getHost(), so it is NOT confused here - pinning that as current,
        // correct behaviour.
        @Test
        void require_userInfoInAuthority_usesHostAfterAtSign_notUserInfo() throws Exception {
            assertThat(FetchGuard.isWithin("http://evil.com@good.com/path", "http://good.com/y")).isTrue();
            assertThat(FetchGuard.isWithin("http://evil.com@good.com/path", "http://evil.com/y")).isFalse();

            URL result = FetchGuard.require("http://evil.com@good.com/path", "http://good.com/y");
            assertThat(result.getHost()).isEqualTo("good.com");
        }
    }

    @Nested
    class LeadingAndTrailingWhitespace {

        @Test
        void require_urlWithSurroundingWhitespace_isTrimmedBeforeParsing() throws Exception {
            URL result = FetchGuard.require("  http://a.com/x  ", "http://a.com/y");

            assertThat(result.toString()).isEqualTo("http://a.com/x");
            assertThat(FetchGuard.isWithin("  http://a.com/x  ", "http://a.com/y")).isTrue();
        }

        @Test
        void require_baseWithSurroundingWhitespace_isTrimmedBeforeParsing() throws Exception {
            assertThat(FetchGuard.isWithin("http://a.com/x", "  http://a.com/y  ")).isTrue();
        }
    }

    @Nested
    class ExceptionBehaviourContract {

        @Test
        void require_onRejection_throwsIoExceptionNotUnchecked() {
            assertThatThrownBy(() -> FetchGuard.require("http://evil.com/x", "http://good.com/y"))
                    .isInstanceOf(IOException.class);
        }

        @Test
        void isWithin_onRejection_returnsFalseRatherThanThrowing() {
            assertThat(FetchGuard.isWithin(null, "http://good.com/y")).isFalse();
            assertThat(FetchGuard.isWithin("not a url", "http://good.com/y")).isFalse();
            assertThat(FetchGuard.isWithin("gopher://a.com/x", "http://good.com/y")).isFalse();
        }
    }
}
