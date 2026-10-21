package org.jahia.se.modules.georeadiness.botrank;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading a connection file: its id, its problem, and a sites list that fails closed. */
class BotRankConnectionTest {

    private static Map<String, Object> file(String name, String sites) {
        Map<String, Object> c = new HashMap<>();
        c.put("felix.fileinstall.filename", "file:/var/jahia/karaf/etc/" + name);
        c.put("apiKey", "brk_x");
        if (sites != null) {
            c.put("sites", sites);
        }
        return c;
    }

    @Test
    void theIdComesFromTheFileName() {
        assertThat(BotRankConnection.Settings.from(file("org.jahia.se.modules.georeadiness.botrank-acme.cfg", null)).id)
                .isEqualTo("acme");
        assertThat(BotRankConnection.Settings.from(file("other.cfg", null)).id).isEqualTo("default");
    }

    @Test
    void aValidListRestricts() {
        BotRankConnection.Settings s = BotRankConnection.Settings.from(file("org.jahia.se.modules.georeadiness.botrank-a.cfg", "fr-site, com_site"));
        assertThat(s.problem).isNull();
        assertThat(s.sites).containsExactly("fr-site", "com_site");
    }

    @ParameterizedTest
    @ValueSource(strings = {"acme-fr;acme-com", "acme fr", "acme.fr", "acme-fr,,../x"})
    void anUnreadableListClosesTheConnectionInsteadOfOpeningIt(String sites) {
        BotRankConnection.Settings s = BotRankConnection.Settings.from(file("org.jahia.se.modules.georeadiness.botrank-a.cfg", sites));
        assertThat(s.problem).contains("sites");
    }

    @Test
    void aMissingKeyIsAProblemNotACrash() {
        Map<String, Object> c = file("org.jahia.se.modules.georeadiness.botrank-a.cfg", null);
        c.remove("apiKey");
        assertThat(BotRankConnection.Settings.from(c).problem).contains("apiKey");
    }
}
