package org.jahia.se.modules.georeadiness.util;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Just enough of a request and a response for Jahia's URL rewriter to run
 * outside a real HTTP call.
 *
 * A background job has no request, and the rewriter needs one: it reads the
 * scheme, host and context path, and the vanity URL rule stores what it finds
 * in a request attribute. Without this, a scheduled scan would have to build
 * page URLs by hand, which is precisely the guessing this module refuses to do
 * everywhere else.
 *
 * The sitemap module solves the same problem with two hand-written mock
 * classes. A dynamic proxy does it in a fraction of the code: the handful of
 * methods the rewriter actually calls are answered, and everything else returns
 * a harmless default rather than throwing, because an unimplemented getter must
 * not take down a scan.
 */
public final class MockHttp {

    private MockHttp() {
    }

    public static HttpServletRequest request(String scheme, String host, int port, String contextPath) {
        Map<String, Object> attributes = new HashMap<>();
        String ctx = contextPath == null ? "" : contextPath;
        return (HttpServletRequest) Proxy.newProxyInstance(
                MockHttp.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getScheme":
                            return scheme;
                        case "getServerName":
                            return host;
                        case "getServerPort":
                        // The socket port, which is what PublicUrls.base reads now
                        // that a real request's Host header may not be believed for
                        // it. Without this the proxy's default of 0 would be taken
                        // for the port and a request-free url would lose it.
                        case "getLocalPort":
                            return port;
                        case "getContextPath":
                            return ctx;
                        case "isSecure":
                            return "https".equals(scheme);
                        case "getMethod":
                            return "GET";
                        case "getCharacterEncoding":
                            return "UTF-8";
                        case "getRequestURI":
                            return ctx + "/";
                        case "getRequestURL":
                            return new StringBuffer(scheme + "://" + host + ":" + port + ctx + "/");
                        case "getQueryString":
                        case "getPathInfo":
                        case "getServletPath":
                            return null;
                        case "getAttribute":
                            return attributes.get(String.valueOf(args[0]));
                        case "setAttribute":
                            attributes.put(String.valueOf(args[0]), args[1]);
                            return null;
                        case "removeAttribute":
                            attributes.remove(String.valueOf(args[0]));
                            return null;
                        case "getAttributeNames":
                            return Collections.enumeration(new HashSet<>(attributes.keySet()));
                        case "getHeaderNames":
                            return Collections.enumeration(Collections.<String>emptySet());
                        case "toString":
                            return "MockHttp.request(" + scheme + "://" + host + ":" + port + ctx + ")";
                        default:
                            return defaultFor(method.getReturnType());
                    }
                });
    }

    public static HttpServletResponse response() {
        return (HttpServletResponse) Proxy.newProxyInstance(
                MockHttp.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        // The rewriter round-trips URLs through these. Returning the
                        // input unchanged is both correct here and the only safe answer.
                        case "encodeURL":
                        case "encodeRedirectURL":
                        case "encodeUrl":
                        case "encodeRedirectUrl":
                            return args[0];
                        case "getCharacterEncoding":
                            return "UTF-8";
                        case "isCommitted":
                            return Boolean.FALSE;
                        case "toString":
                            return "MockHttp.response()";
                        default:
                            return defaultFor(method.getReturnType());
                    }
                });
    }

    /** A proxy must return something of the right shape for a primitive. */
    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == void.class) {
            return null;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0;
    }
}
