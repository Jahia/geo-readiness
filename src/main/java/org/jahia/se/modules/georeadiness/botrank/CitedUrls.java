package org.jahia.se.modules.georeadiness.botrank;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The identity of a cited page, folded the way BotRank folds it.
 *
 * <p>BotRank's public API v1 (reference read on 2026-10-15, "Page URL matching") counts these
 * variants as one page: with or without {@code www.}, with or without a trailing slash, with or
 * without the anchor, with or without its published list of tracking parameters, and any case in
 * the host. Every other difference (scheme, subdomain, path case, other query parameters) makes a
 * different page. Both sides of a comparison go through {@link #key(String)}, so a Jahia URL and a
 * cited URL meet whatever spelling each side used.</p>
 *
 * <p>Pure functions, no state, safe from any thread.</p>
 */
public final class CitedUrls {

    /** The parameters BotRank ignores, by provider, copied from its reference. */
    static final Set<String> TRACKING = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            // UTM
            "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term", "utm_support", "utm_id",
            // Matomo
            "mtm_source", "mtm_medium", "mtm_campaign",
            // AT Internet
            "at_campaign", "at_medium", "at_channel", "at_identifier", "at_custom1", "at_custom2",
            // Facebook, Microsoft
            "fbclid", "msockid", "msclkid",
            // Google
            "ved", "usg", "sa", "opi", "ssr", "sst", "ssq", "ssn", "ssc", "ssm", "ssa", "ssp", "sso", "ssb",
            "ssx", "ssi", "ssk", "srsltid", "gclid",
            // HubSpot
            "__hssc", "__hstc", "__hsfp", "hsLang", "hs_amp", "hsCtaTracking",
            // Ad platforms
            "adw", "adp", "cmp", "campaign_ID", "cplid", "adgrp", "hsa_src", "hsa_tgt", "hsa_cam",
            "hsa_grp", "hsa_kw", "hsa_ad", "hsa_acc", "hsa_mt", "hsa_ver", "hsa_net")));

    private CitedUrls() {
    }

    /**
     * The folded identity of {@code url}: {@code scheme://host/path?query}, or null when the value
     * is not an absolute http or https URL.
     *
     * @param url an absolute URL, as cited or as Jahia builds it
     * @return the key two URLs share exactly when BotRank counts them as one page
     */
    public static String key(String url) {
        URI uri = parse(url);
        if (uri == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        out.append(uri.getScheme().toLowerCase(Locale.ROOT)).append("://").append(host(uri));
        if (uri.getPort() > 0) {
            out.append(':').append(uri.getPort());
        }
        out.append(path(uri));
        String query = query(uri.getRawQuery());
        if (!query.isEmpty()) {
            out.append('?').append(query);
        }
        return out.toString();
    }

    /**
     * The host with BotRank's folding: lower case, without a leading {@code www.}.
     *
     * @param url an absolute URL
     * @return the folded host, or null when the value is not an absolute http or https URL
     */
    public static String host(String url) {
        URI uri = parse(url);
        return uri == null ? null : host(uri);
    }

    /**
     * The folded path and query, without scheme and host: what a Jahia page index is keyed by once
     * the host has been checked separately.
     *
     * @param url an absolute URL
     * @return the folded path, with its query when one survives, or null when the value is not a URL
     */
    public static String pathKey(String url) {
        URI uri = parse(url);
        if (uri == null) {
            return null;
        }
        String query = query(uri.getRawQuery());
        return query.isEmpty() ? path(uri) : path(uri) + "?" + query;
    }

    /**
     * Whether {@code host} is one of the site's hosts, compared with BotRank's folding.
     *
     * @param host  a host as {@link #host(String)} returns it
     * @param hosts the hosts configured for the site
     * @return true when the folded hosts are equal
     */
    public static boolean onHosts(String host, List<String> hosts) {
        if (host == null) {
            return false;
        }
        for (String h : hosts) {
            if (host.equals(foldHost(h))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A configured host, folded: lower case, no scheme, no port, no path, no leading {@code www.}.
     *
     * @param value what an administrator typed
     * @return the folded host, or an empty string for a blank value
     */
    public static String foldHost(String value) {
        if (value == null) {
            return "";
        }
        String h = value.trim().toLowerCase(Locale.ROOT);
        int scheme = h.indexOf("://");
        if (scheme >= 0) {
            h = h.substring(scheme + 3);
        }
        int cut = firstOf(h, '/', ':', '?', '#');
        if (cut >= 0) {
            h = h.substring(0, cut);
        }
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static URI parse(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) {
                return null;
            }
            String s = scheme.toLowerCase(Locale.ROOT);
            return "http".equals(s) || "https".equals(s) ? uri : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String host(URI uri) {
        return foldHost(uri.getHost());
    }

    /** The raw path, kept case-sensitive, without a trailing slash except for the root. */
    private static String path(URI uri) {
        String p = uri.getRawPath();
        if (p == null || p.isEmpty()) {
            return "/";
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** The raw query with tracking parameters removed, the others kept in their order. */
    private static String query(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            if (!TRACKING.contains(name)) {
                kept.add(pair);
            }
        }
        return String.join("&", kept);
    }

    private static int firstOf(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best;
    }
}
