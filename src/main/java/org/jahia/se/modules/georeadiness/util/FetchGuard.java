package org.jahia.se.modules.georeadiness.util;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * What this module is allowed to fetch.
 *
 * Every fetch here is meant to reach one place: the site being reported on, at
 * the address a visitor would use. The address is derived from the repository
 * or from configuration, so the guard's job is to hold the fetchers to it. Two
 * inputs would otherwise widen it: a request header, which
 * {@link PublicUrls#base} no longer reads for the host, and a url read out of a
 * fetched sitemap, which is checked here.
 */
public final class FetchGuard {

    private FetchGuard() {
    }

    /**
     * The url, confirmed to be an http(s) address on the same origin as
     * {@code base}: same scheme, same host, same port.
     *
     * @throws IOException when it is anything else, so a caller that already
     *                     reports a failed fetch reports this the same way
     */
    public static URL require(String url, String base) throws IOException {
        URL target = parse(url);
        URL origin = parse(base);
        if (!target.getProtocol().equals(origin.getProtocol())
                || !host(target).equals(host(origin))
                || port(target) != port(origin)) {
            throw new IOException("outside the site being checked");
        }
        return target;
    }

    /** True when {@code url} is on the same origin as {@code base}. */
    public static boolean isWithin(String url, String base) {
        try {
            require(url, base);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The one place a connection to a checked site is opened.
     *
     * The url is validated against {@code base} and the VALIDATED value is what
     * gets opened, so the address that was checked and the address that is
     * fetched cannot differ. Redirects are never followed: a 30x is a finding
     * about the site, and following one would leave the origin behind.
     */
    public static HttpURLConnection open(String url, String base, int timeoutMs, String userAgent)
            throws IOException {
        URL target = require(url, base);
        HttpURLConnection c = (HttpURLConnection) target.openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", userAgent);
        return c;
    }

    private static URL parse(String url) throws IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new MalformedURLException("no url");
        }
        URL u = new URL(url.trim());
        String scheme = u.getProtocol().toLowerCase(Locale.ROOT);
        // Anything else reaches a protocol handler nobody here asked for.
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new MalformedURLException("unsupported scheme");
        }
        if (u.getHost() == null || u.getHost().isEmpty()) {
            throw new MalformedURLException("no host");
        }
        return u;
    }

    private static String host(URL u) {
        return u.getHost().toLowerCase(Locale.ROOT);
    }

    private static int port(URL u) {
        return u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
    }
}
