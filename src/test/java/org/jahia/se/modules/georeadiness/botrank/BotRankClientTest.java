package org.jahia.se.modules.georeadiness.botrank;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client against a scripted transport: what it sends, how it pages, how it reads refusals. */
class BotRankClientTest {

    private final List<URI> sent = new ArrayList<>();
    private final List<String> projectsSent = new ArrayList<>();

    private BotRankClient client(BotRankClient.Response... answers) {
        int[] i = {0};
        return new BotRankClient("https://api.example/public/v1/", "brk_secret", (uri, key, project) -> {
            assertThat(key).isEqualTo("brk_secret");
            sent.add(uri);
            projectsSent.add(project);
            return answers[Math.min(i[0]++, answers.length - 1)];
        });
    }

    private static BotRankClient.Response ok(String body) {
        return new BotRankClient.Response(200, body);
    }

    @Test
    void walksTheCursorAndSendsHostsAndDates() throws Exception {
        BotRankClient c = client(
                ok("{\"domain\":\"example.com\",\"pages\":[{\"url\":\"https://example.com/a\",\"rank\":1,\"citationCount\":5,\"citationCountVariation\":2}],\"nextCursor\":\"c 1\"}"),
                ok("{\"domain\":\"example.com\",\"pages\":[{\"url\":\"https://example.com/b\",\"rank\":2,\"citationCount\":3,\"citationCountVariation\":null}],\"nextCursor\":null}"));
        List<BotRankClient.CitedPage> pages = c.citedPages("2188", "example.com", java.util.Arrays.asList("example.com", "fr.example.com"),
                BotRankClient.Period.between(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 15)));

        assertThat(pages).extracting(p -> p.url).containsExactly("https://example.com/a", "https://example.com/b");
        assertThat(pages.get(0).variation).isEqualTo(2);
        assertThat(pages.get(1).variation).isNull();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).toString())
                .startsWith("https://api.example/public/v1/ai-visibility/pages?")
                .contains("domain=example.com", "host=example.com", "host=fr.example.com", "limit=100",
                        "startDate=2026-09-16", "endDate=2026-10-15")
                .doesNotContain("cursor=");
        assertThat(sent.get(1).toString()).contains("cursor=c+1");
        assertThat(projectsSent).containsOnly("2188");
    }

    @Test
    void allTimeIsAPresetNotDates() throws Exception {
        BotRankClient c = client(ok("{\"pages\":[],\"nextCursor\":null}"));
        c.citedPages("1", "x.com", Collections.singletonList("x.com"), BotRankClient.Period.allTime());
        assertThat(sent.get(0).toString()).contains("period=all_time").doesNotContain("startDate");
    }

    @Test
    void stopsAtTheBoundEvenIfTheCursorNeverEnds() throws Exception {
        BotRankClient c = client(ok("{\"pages\":[],\"nextCursor\":\"again\"}"));
        c.citedPages("1", "x.com", Collections.singletonList("x.com"), BotRankClient.Period.allTime());
        assertThat(sent).hasSize(BotRankClient.MAX_LIST_PAGES);
    }

    @Test
    void projectsNeedNoProjectHeader() throws Exception {
        BotRankClient c = client(ok("{\"projects\":[{\"id\":\"2188\",\"name\":\"Clairseve\"}]}"));
        assertThat(c.projects()).extracting(p -> p.name).containsExactly("Clairseve");
        assertThat(projectsSent).containsExactly((String) null);
    }

    @Test
    void readsTheProblemTypeAndKeepsTheKeyOutOfTheMessage() {
        BotRankClient c = client(new BotRankClient.Response(401,
                "{\"type\":\"https://api.botrank.ai/public/v1/problems/revoked-api-key\",\"title\":\"Revoked API key\",\"status\":401,\"detail\":\"brk_secret was revoked\"}"));
        assertThatThrownBy(c::projects)
                .isInstanceOfSatisfying(BotRankException.class, e -> {
                    assertThat(e.type()).isEqualTo("revoked-api-key");
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.isTransient()).isFalse();
                    assertThat(e.getMessage()).contains("Revoked API key").doesNotContain("brk_secret");
                });
    }

    @Test
    void aBodyThatIsNotAProblemStillGivesAType() {
        BotRankClient c = client(new BotRankClient.Response(503, "<html>down</html>"));
        assertThatThrownBy(c::projects)
                .isInstanceOfSatisfying(BotRankException.class, e -> {
                    assertThat(e.type()).isEqualTo("http-503");
                    assertThat(e.isTransient()).isTrue();
                });
    }

    @Test
    void noAnswerIsTransientAndNamesNoDetail() {
        BotRankClient c = new BotRankClient("https://api.example/v1", "brk_secret", (u, k, p) -> {
            throw new IOException("connect timed out to brk_secret");
        });
        assertThatThrownBy(c::projects)
                .isInstanceOfSatisfying(BotRankException.class, e -> {
                    assertThat(e.type()).isEqualTo(BotRankException.UNREACHABLE);
                    assertThat(e.getMessage()).doesNotContain("brk_secret");
                });
    }
}
