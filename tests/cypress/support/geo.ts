/**
 * The three geo-readiness endpoints, and the one correct way to call them.
 *
 * Every spec goes through here rather than writing `cy.request` by hand, because
 * two details are easy to get wrong and neither fails loudly:
 *
 *  - **Origin.** Jahia drops the caller's credentials on a POST that carries no
 *    `Origin` header. It does not answer 401 — it serves the request as guest.
 *    A suite that forgets the header therefore proves nothing about permissions:
 *    every authenticated case comes back 401 and reads as "the endpoint is
 *    locked down", which is the opposite of what it measured.
 *  - **Origin is a scheme and a host, never a path.** `baseUrl` carries the
 *    servlet context path when Jahia runs under one, and Jahia answers 403 to a
 *    malformed Origin. So it is derived with `new URL(...).origin`.
 */

/** The endpoints, as mounted by the OSGi HTTP whiteboard under `/modules`. */
export const ENDPOINTS = {
    crawlerCheck: '/modules/geo-readiness/crawler-check',
    siteScan: '/modules/geo-readiness/site-scan',
    siteFiles: '/modules/geo-readiness/site-files'
} as const;

/**
 * Every action `site-scan` dispatches on, read off the servlet's own switch.
 *
 * The authorization spec iterates this rather than sampling one action: the
 * permission gate sits before the switch, so a future action added below the
 * gate is covered the day it is added, and one added *above* it fails here.
 */
export const SITE_SCAN_ACTIONS = [
    'scanStatus',
    'runScan',
    'guestVisibility',
    'sitemap',
    'freshness',
    'languages',
    'schema',
    'saveSchemaMap',
    'saveSchedule'
] as const;

/** Every action `site-files` dispatches on. Same reasoning as above. */
export const SITE_FILES_ACTIONS = [
    'previewLlms',
    'applyLlms',
    'previewRobots',
    'applyRobots'
] as const;

/**
 * The actions `site-scan` rate limits (30 calls / 10 minutes / user).
 *
 * The authorization spec walks every action for several users, so it has to know
 * which ones spend budget — and the denial cases never reach the limiter, since
 * the permission gate runs first.
 */
export const RATE_LIMITED_SCAN_ACTIONS: ReadonlySet<string> = new Set([
    'guestVisibility',
    'runScan',
    'sitemap',
    'freshness'
]);

export type GeoEndpoint = typeof ENDPOINTS[keyof typeof ENDPOINTS];

export type GeoRequestOptions = {
    /** Overrides `application/json`. Pass `false` to send no Content-Type at all. */
    contentType?: string | false;
    /** Sent verbatim instead of a serialized object. Used by the malformed-body cases. */
    rawBody?: string;
    timeout?: number;
};

/**
 * The origin Jahia will accept, derived from `baseUrl`.
 * @returns {string} scheme + host + port, with no path.
 */
export function jahiaOrigin(): string {
    const baseUrl = Cypress.config('baseUrl');
    if (!baseUrl) {
        throw new Error('baseUrl is not set — the @jahia/cypress env plugin did not run');
    }

    return new URL(baseUrl).origin;
}

/**
 * POST to a geo-readiness endpoint as whoever the browser session currently is.
 *
 * Never throws on a non-2xx: every spec here asserts on the status itself, and
 * `failOnStatusCode` would turn a 403 assertion into a command error.
 *
 * @param {GeoEndpoint} endpoint one of {@link ENDPOINTS}.
 * @param {Record<string, unknown>} body the JSON body. Ignored when `options.rawBody` is set.
 * @param {GeoRequestOptions} options content-type / raw-body / timeout overrides.
 * @returns {Cypress.Chainable<Cypress.Response<any>>} the raw response.
 */
export function geoPost(
    endpoint: GeoEndpoint,
    body: Record<string, unknown>,
    options: GeoRequestOptions = {}
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
): Cypress.Chainable<Cypress.Response<any>> {
    const headers: Record<string, string> = {Origin: jahiaOrigin()};
    if (options.contentType !== false) {
        headers['Content-Type'] = options.contentType ?? 'application/json';
    }

    return cy.request({
        method: 'POST',
        url: endpoint,
        headers,
        body: options.rawBody === undefined ? body : options.rawBody,
        failOnStatusCode: false,
        timeout: options.timeout
    });
}

/**
 * Assert a response is a refusal with the status and the error message the
 * servlet documents.
 *
 * The message is asserted as well as the status because the endpoints answer 400
 * to four different things: a missing path, a bad language, a malformed body and
 * a non-JSON content type. A spec that checks only the number passes when the
 * request was refused for a reason it never meant to exercise — which is exactly
 * how a validation test rots into a tautology.
 *
 * @param {Cypress.Response<any>} response the response under test.
 * @param {number} status the expected HTTP status.
 * @param {string} error the expected `error` field.
 * @param {string} because what this refusal is meant to prove.
 */
export function expectRefusal(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    response: Cypress.Response<any>,
    status: number,
    error: string,
    because: string
): void {
    expect(response.status, because).to.eq(status);
    expect(response.body, `${because} — the refusal must be JSON`).to.be.an('object');
    expect(response.body.error, `${because} — refused for the documented reason`).to.eq(error);
}

/**
 * Assert a response is a success carrying a JSON object.
 *
 * Used before any claim about *what* the body says: a body that is a string, or
 * an error object with a 200, would otherwise satisfy a loose property check.
 *
 * @param {Cypress.Response<any>} response the response under test.
 * @param {string} because what this success is meant to prove.
 */
export function expectJsonOk(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    response: Cypress.Response<any>,
    because: string
): void {
    expect(response.status, because).to.eq(200);
    expect(response.body, `${because} — the answer must be a JSON object`).to.be.an('object');
    expect(response.body.error, `${because} — a 200 must not carry an error`).to.be.undefined;
}
