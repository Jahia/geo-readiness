import './commands';
import {ENDPOINTS, jahiaOrigin} from './geo';

/**
 * Run-level guard.
 *
 * Every spec in this suite asserts a status code from one of three servlets. If
 * the module is not deployed, those servlets are not mounted and Jahia answers
 * 404 to all of them — at which point a spec asserting "guest gets 401" fails
 * with a confusing diff, and worse, any spec asserting an absence would pass.
 *
 * So the run states the precondition once, in its own name, before any spec
 * interprets a status code. The probe is deliberately unauthenticated: a guest
 * POST to a mounted endpoint answers 401, which proves the servlet is there
 * without needing a session or a site.
 */
before(() => {
    cy.request({
        method: 'POST',
        url: ENDPOINTS.crawlerCheck,
        headers: {Origin: jahiaOrigin(), 'Content-Type': 'application/json'},
        body: {},
        failOnStatusCode: false
    }).then(response => {
        expect(
            response.status,
            'the geo-readiness module must be deployed and ACTIVE — an unauthenticated POST to ' +
            `${ENDPOINTS.crawlerCheck} must answer 401, not ${response.status}`
        ).to.eq(401);
    });
});
