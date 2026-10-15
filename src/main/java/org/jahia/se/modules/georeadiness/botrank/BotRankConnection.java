package org.jahia.se.modules.georeadiness.botrank;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One BotRank organisation this Jahia can read, from one configuration file.
 *
 * <p>Each file {@code karaf/etc/org.jahia.se.modules.georeadiness.botrank-<id>.cfg} is a factory
 * configuration of the PID below, and DS creates one instance of this component per file
 * ({@code configurationPolicy = REQUIRE}: no file, no connection). The id is the part of the file
 * name after the dash. Keys:</p>
 * <ul>
 *   <li>{@code apiKey} (required): an API key of the organisation, created by an owner in BotRank
 *       under Organisation, Integrations, API keys;</li>
 *   <li>{@code baseUrl}: the API base, {@value #DEFAULT_BASE_URL} by default;</li>
 *   <li>{@code label}: the name shown to site administrators, the id by default;</li>
 *   <li>{@code sites}: comma-separated site keys allowed to use this connection; blank means
 *       every site of the instance.</li>
 * </ul>
 *
 * <p>A file with a missing key still registers, with {@link #problem()} set, so administrators see
 * it in Site Settings; it is never used to call BotRank. {@link #toString()} never prints the key.</p>
 *
 * <p>Thread safety: the settings are one immutable snapshot swapped in one volatile write.</p>
 */
@Component(
        service = BotRankConnection.class,
        configurationPid = BotRankConnection.PID,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property = {
                "service.description=GEO readiness - one BotRank organisation",
                "service.vendor=Jahia Solutions Group SA"
        })
public class BotRankConnection {

    /** Factory PID of the connection files. */
    public static final String PID = "org.jahia.se.modules.georeadiness.botrank";
    public static final String DEFAULT_BASE_URL = "https://api.botrank.ai/public/v1";

    private static final Logger logger = LoggerFactory.getLogger(BotRankConnection.class);
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern SITE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private volatile Settings settings = Settings.EMPTY;

    @Activate
    @Modified
    protected void apply(Map<String, Object> config) {
        Settings s = Settings.from(config);
        settings = s;
        if (s.problem != null) {
            logger.warn("BotRank connection {} is not usable: {}", s.id, s.problem);
        } else {
            logger.info("BotRank connection {} ready ({})", s.id, s.baseUrl);
            if (s.baseUrl.startsWith("http://")) {
                logger.warn("BotRank connection {} sends its API key over plain http; use https outside a test stack", s.id);
            }
        }
    }

    /** @return the id from the file name, lower case */
    public String id() {
        return settings.id;
    }

    /** @return the name shown to administrators */
    public String label() {
        return settings.label;
    }

    /** @return a sentence that says why the connection cannot be used, or null when it can */
    public String problem() {
        return settings.problem;
    }

    /**
     * Whether a site may use this connection.
     *
     * @param siteKey the site key
     * @return true when no {@code sites} list is set, or when it names the site
     */
    public boolean allows(String siteKey) {
        Set<String> sites = settings.sites;
        return sites.isEmpty() || sites.contains(siteKey);
    }

    /**
     * A client for this organisation.
     *
     * @return the client
     * @throws BotRankException when the connection has a {@link #problem()}
     */
    public BotRankClient client() throws BotRankException {
        Settings s = settings;
        if (s.problem != null) {
            throw new BotRankException("connection-not-usable", 0, s.problem);
        }
        return new BotRankClient(s.baseUrl, s.apiKey);
    }

    @Override
    public String toString() {
        Settings s = settings;
        return "BotRankConnection[" + s.id + ", " + s.baseUrl + ", key " + (s.apiKey.isEmpty() ? "missing" : "set") + "]";
    }

    static final class Settings {
        static final Settings EMPTY = new Settings("unconfigured", "unconfigured", "", DEFAULT_BASE_URL,
                Collections.emptySet(), "no configuration");

        final String id;
        final String label;
        final String apiKey;
        final String baseUrl;
        final Set<String> sites;
        final String problem;

        private Settings(String id, String label, String apiKey, String baseUrl, Set<String> sites, String problem) {
            this.id = id;
            this.label = label;
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.sites = sites;
            this.problem = problem;
        }

        static Settings from(Map<String, Object> c) {
            String id = idOf(c);
            String apiKey = str(c, "apiKey");
            String baseUrl = str(c, "baseUrl");
            if (baseUrl.isEmpty()) {
                baseUrl = DEFAULT_BASE_URL;
            }
            String label = str(c, "label");
            Set<String> sites = new LinkedHashSet<>();
            boolean badSite = false;
            String rawSites = str(c, "sites");
            if (!rawSites.isEmpty()) {
                for (String s : rawSites.split(",")) {
                    String k = s.trim();
                    if (SITE_KEY.matcher(k).matches()) {
                        sites.add(k);
                    } else {
                        badSite = true;
                    }
                }
            }
            String problem = null;
            // A restriction that cannot be read closes the connection: dropping the bad entries would
            // leave an empty list, which means "every site", and open the key to every administrator.
            if (badSite) {
                problem = "the sites list has an entry that is not a site key (sites)";
            } else if (apiKey.isEmpty()) {
                problem = "the API key is missing (apiKey)";
            } else if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
                problem = "the base URL is not an http or https URL (baseUrl)";
            }
            return new Settings(id, label.isEmpty() ? id : label, apiKey, baseUrl,
                    Collections.unmodifiableSet(sites), problem);
        }

        /** The part of the file name after the PID and a dash, or "default" for the plain PID file. */
        private static String idOf(Map<String, Object> c) {
            String file = str(c, "felix.fileinstall.filename");
            int slash = file.lastIndexOf('/');
            String name = slash >= 0 ? file.substring(slash + 1) : file;
            String prefix = PID + "-";
            if (name.startsWith(prefix) && name.endsWith(".cfg")) {
                String id = name.substring(prefix.length(), name.length() - 4).toLowerCase(Locale.ROOT);
                if (ID.matcher(id).matches()) {
                    return id;
                }
            }
            return "default";
        }

        private static String str(Map<String, Object> c, String key) {
            Object v = c == null ? null : c.get(key);
            return v == null ? "" : v.toString().trim();
        }
    }
}
