package org.jahia.se.modules.georeadiness.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What this module is allowed to fetch.
 *
 * Every fetch here is meant to reach one place: the site being reported on, at
 * the address a visitor would use. The address is derived from the repository
 * or from configuration, so the guard's job is to hold the fetchers to it. Two
 * inputs would otherwise widen it: a request header, which
 * {@link PublicUrls#base} no longer reads for the host or for the port, and a
 * url read out of a fetched sitemap, which is checked here.
 *
 * Because that first sentence is an assumption about a caller, and one that a
 * Host header already broke once, {@link #open} also checks the address itself:
 * a public host, on a port a site is served from. See requireAllowedTarget.
 */
public final class FetchGuard {

    private static final Logger logger = LoggerFactory.getLogger(FetchGuard.class);

    /** PUBLIC_BASE_URL, see {@link #trustBase}. Blank until the config service says otherwise. */
    private static volatile String trustedBase = "";

    /** Settled once the connectors are known: neither they nor site.url.port move afterwards. */
    private static volatile Set<Integer> allowedPorts;

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
     *
     * The origin check only proves that the target and the base agree; it says
     * nothing about whether the base itself is somewhere this module should be
     * connecting at all. {@link #requireAllowedTarget} is the answer to that,
     * and it is deliberately here rather than in the callers: this is the only
     * place in the module where a site connection is opened, so it is the only
     * place that has to be right.
     */
    public static HttpURLConnection open(String url, String base, int timeoutMs, String userAgent)
            throws IOException {
        URL target = require(url, base);
        requireAllowedTarget(target);
        HttpURLConnection c = (HttpURLConnection) target.openConnection();
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", userAgent);
        return c;
    }

    /**
     * Where a fetch is allowed to land: a public address, on a port a site is
     * served from.
     *
     * Defence in depth behind {@link PublicUrls#base}, which is what decides the
     * address. The failure this backstops was real: the base used to take its
     * port from the request's Host header, so {@code Host: www.acme.com:6379}
     * turned the nineteen fetches of a crawler check into a port scan of the
     * container's own network, with the status, the timing and the exception
     * class reported back per agent. The base no longer reads the request for
     * that, and this makes sure a future one cannot either - by the time we are
     * here the caller's intent no longer matters, only the address does.
     *
     * Resolution happens again inside {@code openConnection}, so a name that
     * answers differently twice (DNS rebinding) is not stopped here. Stopping
     * it means connecting by address and sending the name in the Host header,
     * which breaks TLS and virtual hosting; against an authenticated editor
     * rather than the open internet, that trade is not worth making.
     */
    private static void requireAllowedTarget(URL target) throws IOException {
        if (isTrusted(target)) {
            return;
        }
        if (!allowedPorts().contains(port(target))) {
            throw new IOException("not a port a site is served from");
        }
        // Every address the name resolves to, not just the first: a name that
        // answers with one public address and one private one is the whole
        // point of the check.
        for (InetAddress address : InetAddress.getAllByName(target.getHost())) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress() || isUniqueLocal(address)) {
                throw new IOException("not a public address");
            }
        }
    }

    /**
     * An address an operator has explicitly pointed this module at, which the
     * rules above do not apply to.
     *
     * PUBLIC_BASE_URL is documented for precisely the case they refuse - "the
     * site's server name is not resolvable from inside the container, for
     * example http://localhost:8080" - and {@link PublicUrls#LOCAL_FALLBACK} is
     * this module's own constant for a site that names no server. Both are
     * decided on the server, by configuration or by code, never by a request,
     * and it is a request-influenced address the rules exist to stop.
     */
    private static boolean isTrusted(URL target) {
        return isWithin(target.toString(), PublicUrls.LOCAL_FALLBACK)
                || isWithin(target.toString(), trustedBase);
    }

    /**
     * PUBLIC_BASE_URL, handed over by the config service on activation and on
     * every change.
     *
     * Static and mutable, which nothing else here is: the fetchers are static
     * utilities reached from a servlet thread and from a scheduler thread, and
     * threading a service reference through all of them to read one string
     * would buy nothing. One volatile write per configuration change.
     */
    public static void trustBase(String publicBaseUrl) {
        trustedBase = publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    /**
     * 80 and 443, plus the ports this Jahia itself answers on, because a site
     * checked on the box that serves it is reached at the container's own port
     * - that is the http://luxe.local.com:8080 setup the base url comments
     * describe. site.url.port is Jahia's answer when a proxy publishes a
     * different port; the connectors are the answer when it does not.
     */
    private static Set<Integer> allowedPorts() {
        Set<Integer> known = allowedPorts;
        if (known != null) {
            return known;
        }
        Set<Integer> ports = new LinkedHashSet<>(Arrays.asList(80, 443));
        try {
            org.jahia.settings.SettingsBean settings = org.jahia.settings.SettingsBean.getInstance();
            int override = settings == null ? 0 : settings.getSiteURLPortOverride();
            if (override > 0) {
                ports.add(override);
            }
        } catch (Exception e) {
            // Nothing configured, or no settings bean here. The connectors answer.
            logger.debug("no site url port override", e);
        }
        Set<Integer> connectors = connectorPorts();
        ports.addAll(connectors);
        known = Collections.unmodifiableSet(ports);
        // Remembered only once the container has actually named its connectors.
        // A first fetch that happens to run before they are registered would
        // otherwise cache a list missing the very port this Jahia serves on, and
        // every fetch for the rest of the run would be refused.
        if (!connectors.isEmpty()) {
            allowedPorts = known;
        }
        return known;
    }

    /**
     * The ports the servlet container accepts on, asked of the container rather
     * than assumed. Empty when it does not say, which leaves 80 and 443 and
     * whatever PUBLIC_BASE_URL names - a deployment on another port that this
     * cannot see is one configuration line away from working.
     */
    private static Set<Integer> connectorPorts() {
        Set<Integer> ports = new LinkedHashSet<>();
        try {
            MBeanServer mbeans = ManagementFactory.getPlatformMBeanServer();
            for (ObjectName name : mbeans.queryNames(new ObjectName("*:type=Connector,*"), null)) {
                Object port = mbeans.getAttribute(name, "port");
                if (port instanceof Number && ((Number) port).intValue() > 0) {
                    ports.add(((Number) port).intValue());
                }
            }
        } catch (Exception | LinkageError e) {
            // LinkageError as well as Exception: bnd makes javax.management an
            // optional import, so on a container that does not export it this is
            // a missing class rather than a failed query - and a fetch must not
            // die because the port list could not be completed.
            logger.debug("could not read the container's connector ports", e);
        }
        return ports;
    }

    /** fc00::/7, unique local. Java has no predicate for it; it is IPv6's 10.0.0.0/8. */
    private static boolean isUniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
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
