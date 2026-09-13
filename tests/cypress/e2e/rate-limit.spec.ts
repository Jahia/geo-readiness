/**
 * The per-user rate limit on `site-scan`, and the carve-out it is built around.
 *
 * The servlet limits the four expensive actions to 30 calls per ten minutes per
 * user, and deliberately does **not** limit the rest: the dashboard polls
 * `scanStatus` while a scan runs, and limiting that would make a long scan look
 * like a failure after a minute. That carve-out is the interesting half — it is
 * the kind of thing a later refactor "tidies up" into a single check.
 *
 * Own spec, own site, own users, and retries off: the window is ten minutes, so
 * a retry inside it would meet an already-spent budget and fail for a reason
 * that has nothing to do with the assertion.
 */

import {asUser} from '../support/auth';
import {ENDPOINTS, expectJsonOk, expectRefusal, geoPost} from '../support/geo';
import {
    configureModuleForTests,
    geoFixture,
    setupGeoFixture,
    teardownGeoFixture
} from '../support/fixtures';

/** The constant in SiteScanServlet. Not configuration — changing it is a code change. */
const SCAN_RATE_MAX_CALLS = 30;

describe('site-scan — rate limiting', {retries: {runMode: 0, openMode: 0}}, () => {
    const fixture = geoFixture('geolimit');

    before(() => {
        setupGeoFixture(fixture);
        configureModuleForTests();
    });

    after(() => {
        teardownGeoFixture(fixture);
    });

    beforeEach(() => {
        asUser(fixture.publisher);
    });

    it(`answers 429 after ${SCAN_RATE_MAX_CALLS} expensive calls, and keeps answering the cheap ones`, () => {
        // guestVisibility is the cheapest of the four limited actions: it reads
        // the repository and makes no outbound request, so the budget can be
        // spent in seconds on a two-page site.
        const spend = (n: number): void => {
            if (n === 0) {
                return;
            }

            geoPost(ENDPOINTS.siteScan, {
                action: 'guestVisibility',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, `call ${SCAN_RATE_MAX_CALLS - n + 1} is inside the window`);
                spend(n - 1);
            });
        };

        spend(SCAN_RATE_MAX_CALLS);

        geoPost(ENDPOINTS.siteScan, {
            action: 'guestVisibility',
            path: fixture.sitePath,
            language: 'en'
        }).then(response => {
            expectRefusal(
                response,
                429,
                'rate limit',
                `call ${SCAN_RATE_MAX_CALLS + 1} in the window is refused`
            );
        });

        // The carve-out. Reading the stored state is what the dashboard polls
        // while a scan runs, so it must still answer after the budget is gone.
        geoPost(ENDPOINTS.siteScan, {
            action: 'scanStatus',
            path: fixture.sitePath,
            language: 'en'
        }).then(response => {
            expectJsonOk(response, 'reading a stored result is never rate limited');
        });

        geoPost(ENDPOINTS.siteScan, {
            action: 'languages',
            path: fixture.sitePath,
            language: 'en'
        }).then(response => {
            expectJsonOk(response, 'a repository question is never rate limited either');
        });
    });

    it('limits per user, not globally', () => {
        // The publisher's budget is spent by the test above. The contributor
        // would be refused for permission reasons, so the check is made with
        // root, whose own window is untouched.
        //
        // Without this, a limiter keyed on something global — the site, or a
        // static counter — would pass every assertion above and would take the
        // whole instance down the first time two editors used the dashboard.
        cy.login('root', Cypress.env('SUPER_USER_PASSWORD'));
        geoPost(ENDPOINTS.siteScan, {
            action: 'guestVisibility',
            path: fixture.sitePath,
            language: 'en'
        }).then(response => {
            expectJsonOk(response, 'another user still has their own budget');
        });
    });
});
