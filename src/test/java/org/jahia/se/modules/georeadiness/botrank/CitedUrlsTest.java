package org.jahia.se.modules.georeadiness.botrank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The folding must agree with BotRank's "Page URL matching" rules in both directions: every
 * documented variant is the same page, and every documented difference is another page. The second
 * half matters as much as the first: a key that folded too much would merge two pages' citations.
 */
class CitedUrlsTest {

    private static final String BASE = "https://selectra.info/assurance/assurance-habitation/meilleure";

    @Nested
    @DisplayName("same page for BotRank, same key here")
    class Same {
        @ParameterizedTest
        @ValueSource(strings = {
                "https://www.selectra.info/assurance/assurance-habitation/meilleure",
                "https://selectra.info/assurance/assurance-habitation/meilleure/",
                "https://selectra.info/assurance/assurance-habitation/meilleure#top",
                "https://selectra.info/assurance/assurance-habitation/meilleure?utm_source=x&gclid=y",
                "https://SELECTRA.info/assurance/assurance-habitation/meilleure",
                "https://selectra.info/assurance/assurance-habitation/meilleure?msockid=abc&hsLang=fr",
        })
        void variants(String url) {
            assertThat(CitedUrls.key(url)).isEqualTo(CitedUrls.key(BASE));
        }
    }

    @Nested
    @DisplayName("another page for BotRank, another key here")
    class Different {
        @ParameterizedTest
        @ValueSource(strings = {
                "http://selectra.info/assurance/assurance-habitation/meilleure",
                "https://selectra.info/Assurance/assurance-habitation/meilleure",
                "https://selectra.info/assurance/assurance-habitation/meilleure?page=2",
                "https://fr.selectra.info/assurance/assurance-habitation/meilleure",
        })
        void differences(String url) {
            assertThat(CitedUrls.key(url)).isNotEqualTo(CitedUrls.key(BASE));
        }
    }

    @Test
    void keepsNonTrackingParametersInOrder() {
        assertThat(CitedUrls.key("https://x.com/a?b=1&utm_medium=m&a=2")).isEqualTo("https://x.com/a?b=1&a=2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a url", "ftp://x.com/a", "/sites/x/home.html", "https:///nohost"})
    void refusesWhatIsNotAnAbsoluteHttpUrl(String url) {
        assertThat(CitedUrls.key(url)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "https://www.Example.com:8443/x, example.com",
            "WWW.example.com, example.com",
            "blog.example.com/path?q, blog.example.com",
            "'  ', ''",
    })
    void foldsHosts(String value, String expected) {
        assertThat(CitedUrls.foldHost(value)).isEqualTo(expected);
    }

    @Test
    void hostsCompareFolded() {
        assertThat(CitedUrls.onHosts(CitedUrls.host("https://www.example.com/a"), Arrays.asList("Example.com"))).isTrue();
        assertThat(CitedUrls.onHosts(CitedUrls.host("https://fr.example.com/a"), Arrays.asList("example.com"))).isFalse();
    }

    @Test
    void pathKeyKeepsCaseAndDropsTrailingSlash() {
        assertThat(CitedUrls.pathKey("https://x.com/Fr/Page/")).isEqualTo("/Fr/Page");
        assertThat(CitedUrls.pathKey("https://x.com")).isEqualTo("/");
    }
}
