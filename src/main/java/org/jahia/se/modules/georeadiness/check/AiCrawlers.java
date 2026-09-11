package org.jahia.se.modules.georeadiness.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one list of AI crawlers this module knows about.
 *
 * It exists because there used to be two: the servlet fetched eight agents and
 * the robots.txt checker evaluated fifteen tokens. Seven crawlers therefore had
 * a stated policy and a toggle in the settings panel while nobody ever checked
 * whether the server would actually serve them, which is precisely the gap this
 * module exists to close. One list, read by both, so they cannot drift again.
 *
 * User agent strings are the ones each operator publishes. They are what a WAF
 * rule matches on, so a typo here silently turns a real finding into a pass.
 */
public final class AiCrawlers {

    /**
     * The control comes first and must stay first: `controlWords` is taken from
     * the first agent that returns 200, and every thin-content comparison is
     * made against it.
     */
    public static final String CONTROL_NAME = "Browser (control)";
    public static final String CONTROL_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/140.0.0.0 Safari/537.36";

    /** Display name, the user agent we send, and the token robots.txt names it by. */
    public static final class Crawler {
        public final String name;
        public final String userAgent;
        /** null for the control browser, which no robots.txt group addresses. */
        public final String robotsToken;

        Crawler(String name, String userAgent, String robotsToken) {
            this.name = name;
            this.userAgent = userAgent;
            this.robotsToken = robotsToken;
        }
    }

    private static final List<Crawler> ALL;

    static {
        List<Crawler> l = new ArrayList<>();
        l.add(new Crawler(CONTROL_NAME, CONTROL_UA, null));
        add(l, "GPTBot", "GPTBot",
                "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; GPTBot/1.2; +https://openai.com/gptbot");
        add(l, "OAI-SearchBot", "OAI-SearchBot",
                "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; OAI-SearchBot/1.0; +https://openai.com/searchbot");
        add(l, "ChatGPT-User", "ChatGPT-User",
                "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; ChatGPT-User/1.0; +https://openai.com/bot");
        add(l, "ClaudeBot", "ClaudeBot",
                "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; ClaudeBot/1.0; +claudebot@anthropic.com");
        add(l, "Claude-SearchBot", "Claude-SearchBot",
                "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; Claude-SearchBot/1.0; +claudebot@anthropic.com");
        add(l, "PerplexityBot", "PerplexityBot",
                "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; PerplexityBot/1.0; +https://perplexity.ai/perplexitybot");
        add(l, "Perplexity-User", "Perplexity-User",
                "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; Perplexity-User/1.0; +https://perplexity.ai/perplexity-user");
        add(l, "Google-Extended", "Google-Extended",
                "Mozilla/5.0 (compatible; Google-Extended/1.0)");
        add(l, "GoogleOther", "GoogleOther",
                "Mozilla/5.0 (compatible; GoogleOther)");
        add(l, "Bingbot", "bingbot",
                "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)");
        add(l, "CCBot", "CCBot",
                "CCBot/2.0 (https://commoncrawl.org/faq/)");
        add(l, "Bytespider", "Bytespider",
                "Mozilla/5.0 (compatible; Bytespider; spider-feedback@bytedance.com)");
        add(l, "Amazonbot", "Amazonbot",
                "Mozilla/5.0 (compatible; Amazonbot/0.1; +https://developer.amazon.com/support/amazonbot)");
        add(l, "Applebot-Extended", "Applebot-Extended",
                "Mozilla/5.0 (compatible; Applebot-Extended/0.1; +http://www.apple.com/go/applebot)");
        add(l, "meta-externalagent", "meta-externalagent",
                "meta-externalagent/1.1 (+https://developers.facebook.com/docs/sharing/webmasters/crawler)");
        ALL = Collections.unmodifiableList(l);
    }

    private AiCrawlers() {
    }

    private static void add(List<Crawler> l, String name, String token, String ua) {
        l.add(new Crawler(name, ua, token));
    }

    /** Control first, then every AI crawler. This is the fetch order. */
    public static List<Crawler> all() {
        return ALL;
    }

    /** Display name to user agent, control included. What the servlet fetches. */
    public static Map<String, String> userAgents() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Crawler c : ALL) {
            m.put(c.name, c.userAgent);
        }
        return m;
    }

    /** Display name to robots token, control excluded. What the policy check evaluates. */
    public static Map<String, String> robotsTokens() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Crawler c : ALL) {
            if (c.robotsToken != null) {
                m.put(c.name, c.robotsToken);
            }
        }
        return m;
    }
}
