package org.jahia.se.modules.georeadiness.botrank;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every BotRank connection configured on this Jahia, by id.
 *
 * <p>Connections come and go with their configuration files; DS binds and unbinds them here.
 * Lookups never cache a connection beyond one call, so a removed file stops being used at once.</p>
 *
 * <p>Thread safety: a concurrent map updated by DS threads, read by request and job threads.</p>
 */
@Component(service = BotRankConnections.class, immediate = true, property = {
        "service.description=GEO readiness - BotRank connections",
        "service.vendor=Jahia Solutions Group SA"
})
public class BotRankConnections {

    private final Map<BotRankConnection, Boolean> bound = new ConcurrentHashMap<>();

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
            service = BotRankConnection.class)
    protected void bind(BotRankConnection connection) {
        bound.put(connection, Boolean.TRUE);
    }

    protected void unbind(BotRankConnection connection) {
        bound.remove(connection);
    }

    /**
     * The connection with this id, if a site may use it.
     *
     * @param id      the connection id
     * @param siteKey the site asking
     * @return the connection, or null when it does not exist or does not allow the site
     */
    public BotRankConnection forSite(String id, String siteKey) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (BotRankConnection c : bound.keySet()) {
            if (id.equals(c.id())) {
                return c.allows(siteKey) ? c : null;
            }
        }
        return null;
    }

    /**
     * The connections a site may use, sorted by label.
     *
     * @param siteKey the site asking
     * @return the connections, usable or not ({@link BotRankConnection#problem()} tells)
     */
    public List<BotRankConnection> visibleTo(String siteKey) {
        List<BotRankConnection> out = new ArrayList<>();
        for (BotRankConnection c : bound.keySet()) {
            if (c.allows(siteKey)) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing(BotRankConnection::label));
        return out;
    }
}
