package org.jahia.se.modules.georeadiness.check;

/**
 * A scan was asked for while one was already running for that site and language.
 *
 * Its own type rather than a message on a general exception, because the two
 * callers answer it differently and neither should be guessing from a string: a
 * request says 409 and the editor tries again later, while the scheduled job
 * logs and steps aside so the manual run already in flight can finish.
 *
 * Unchecked on purpose. {@link SiteScorer#scan} already declares
 * RepositoryException, and making this a second checked type would force every
 * caller to handle a case most of them cannot meet.
 */
public class ScanInProgressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScanInProgressException(String sitePath, String language) {
        super("a scan of " + sitePath + " [" + language + "] is already running");
    }
}
