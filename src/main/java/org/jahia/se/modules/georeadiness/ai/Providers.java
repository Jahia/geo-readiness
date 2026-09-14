package org.jahia.se.modules.georeadiness.ai;

import java.util.Locale;

/** The three providers this module knows, looked up by their configuration name. */
public final class Providers {

    private static final LlmProvider[] ALL = {
            new AnthropicProvider(), new OpenAiProvider(), new DeepSeekProvider()
    };

    private Providers() {
    }

    /** The provider named in configuration, or null when the name is not one of ours. */
    public static LlmProvider forName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (LlmProvider p : ALL) {
            if (p.name().equals(wanted)) {
                return p;
            }
        }
        return null;
    }
}
