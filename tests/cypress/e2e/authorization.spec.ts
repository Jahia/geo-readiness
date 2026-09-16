/**
 * The permission boundary of the three geo-readiness endpoints, in both
 * directions.
 *
 * The module's own reasoning, from SiteScope: the dashboard rewrites robots.txt
 * and llms.txt for the whole site and schedules server-side work, so it is site
 * settings and its gate is `publish` — deliberately NOT `publication-start`,
 * which a stock contributor holds. The per-page drawer has a different and lower
 * floor: "can edit this content", because it opens on a node the caller already
 * selected in jContent.
 *
 * That difference is the whole security model, so it is asserted from both
 * sides. A one-directional suite — only checking that the allowed user is
 * allowed — would stay green if the gate were removed entirely.
 */

import {asGuest, asRoot, asUser} from '../support/auth';
import {
    ENDPOINTS,
    expectJsonOk,
    expectRefusal,
    geoGet,
    geoPost,
    SITE_FILES_ACTIONS,
    SITE_SCAN_ACTIONS
} from '../support/geo';
import {
    assertSitePermission,
    configureModuleForTests,
    geoFixture,
    setupGeoFixture,
    teardownGeoFixture
} from '../support/fixtures';

describe('geo-readiness endpoints — authorization', () => {
    const fixture = geoFixture('geoauthz');

    before(() => {
        setupGeoFixture(fixture);
        configureModuleForTests();
    });

    after(() => {
        teardownGeoFixture(fixture);
    });

    describe('the fixture premise', () => {
        // Asserted, not assumed. Every denial below would also pass if the role
        // grants had silently failed and the users held nothing at all — the
        // suite would be green while measuring the absence of any rights rather
        // than the absence of `publish`.

        it('grants the publisher "publish" on the site', () => {
            asUser(fixture.publisher);
            assertSitePermission(fixture.publisher, fixture.sitePath, 'publish', true);
        });

        it('gives the contributor edit rights but NOT "publish"', () => {
            asUser(fixture.contributor);
            assertSitePermission(fixture.contributor, fixture.sitePath, 'jcr:modifyProperties', true);
            assertSitePermission(fixture.contributor, fixture.sitePath, 'publication-start', true);
            assertSitePermission(fixture.contributor, fixture.sitePath, 'publish', false);
        });
    });

    describe('a guest', () => {
        beforeEach(() => {
            asGuest();
        });

        // One call per endpoint is enough: the guest check is the first thing
        // each servlet does, before the content type, the body and the action
        // are looked at, so no action can reach past it.

        it('is refused by crawler-check with 401', () => {
            geoPost(ENDPOINTS.crawlerCheck, {path: fixture.homePath, language: 'en'}).then(response => {
                expectRefusal(response, 401, 'authentication required', 'an anonymous crawler check is refused');
            });
        });

        it('is refused by site-scan with 401', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectRefusal(response, 401, 'authentication required', 'an anonymous site scan is refused');
            });
        });

        it('is refused by site-files with 401', () => {
            geoPost(ENDPOINTS.siteFiles, {
                action: 'previewRobots',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectRefusal(response, 401, 'authentication required', 'an anonymous robots preview is refused');
            });
        });
    });

    /**
     * JAHIA-SEC-432. Both GET handlers answered ANY authenticated caller with
     * the operator's configuration: which AI provider and model this site's
     * content is sent to, and the full crawler user-agent list.
     *
     * None of this was covered before. Every call in this suite was a POST, so
     * the entire GET surface went unasserted and the finding survived three
     * releases that edited these very files - including one whose message says
     * it gated the ungated endpoint.
     *
     * All three actors are asserted, and the middle one is the point. The
     * endpoints were never open to anonymous callers, so a suite checking only
     * guest-refused and publisher-allowed would have been green throughout. The
     * defect was that the gate asked about AUTHENTICATION where the question is
     * AUTHORISATION, and only an authenticated-but-unprivileged actor can tell
     * those two apart.
     */
    describe('the GET endpoints, which answer with operator configuration', () => {
        // The field each endpoint carries its payload in, so a refusal can be
        // checked for having disclosed nothing rather than only for its status.
        // The fiche makes that distinction explicitly: the discriminator is the
        // RESPONSE BODY, not the code.
        const GETS = [
            {name: 'report', endpoint: ENDPOINTS.report, payload: 'enabled'},
            {name: 'crawler-check', endpoint: ENDPOINTS.crawlerCheck, payload: 'agents'}
        ] as const;

        GETS.forEach(({name, endpoint, payload}) => {
            it(`refuses a guest with 401 on ${name}`, () => {
                asGuest();
                geoGet(endpoint, {path: fixture.sitePath, language: 'en'}).then(response => {
                    expectRefusal(response, 401, 'authentication required',
                        `an anonymous ${name} GET is refused`);
                });
            });

            it(`refuses a contributor with 403 on ${name}, disclosing nothing`, () => {
                asUser(fixture.contributor);
                geoGet(endpoint, {path: fixture.sitePath, language: 'en'}).then(response => {
                    expectRefusal(response, 403, 'cannot read node',
                        `${name} is operator configuration: it needs publish, not a login`);
                    expect(response.body[payload],
                        `${name} must carry no configuration while refusing`).to.be.undefined;
                });
            });

            it(`answers a publisher on ${name}`, () => {
                // The positive half. Without it every refusal above is also
                // consistent with a gate that refuses everybody.
                asUser(fixture.publisher);
                geoGet(endpoint, {path: fixture.sitePath, language: 'en'}).then(response => {
                    expectJsonOk(response, `a publisher may read ${name}`);
                    expect(response.body[payload],
                        `${name} answers the caller who holds publish`).to.not.be.undefined;
                });
            });

            it(`refuses ${name} with 400 when no site is named`, () => {
                asUser(fixture.publisher);
                geoGet(endpoint).then(response => {
                    expectRefusal(response, 400, 'path required',
                        `${name} cannot decide who may be told without a site to decide on`);
                });
            });
        });
    });

    describe('a contributor, who holds publication-start but not publish', () => {
        beforeEach(() => {
            asUser(fixture.contributor);
        });

        it('may still run the per-page crawler check', () => {
            // The positive half of the boundary. Without it, every 403 below
            // would be consistent with a user who simply cannot reach the site.
            geoPost(ENDPOINTS.crawlerCheck, {path: fixture.homePath, language: 'en'}).then(response => {
                expectJsonOk(response, 'a contributor may check a page they can edit');
                expect(response.body.published, 'the fixture home page is published').to.eq(true);
            });
        });

        SITE_SCAN_ACTIONS.forEach(action => {
            it(`is refused site-scan "${action}" with 403`, () => {
                geoPost(ENDPOINTS.siteScan, {
                    action,
                    path: fixture.sitePath,
                    language: 'en'
                }).then(response => {
                    expectRefusal(
                        response,
                        403,
                        'cannot read node',
                        `site-scan "${action}" requires publish, which a contributor does not hold`
                    );
                });
            });
        });

        SITE_FILES_ACTIONS.forEach(action => {
            it(`is refused site-files "${action}" with 403`, () => {
                geoPost(ENDPOINTS.siteFiles, {
                    action,
                    path: fixture.sitePath,
                    language: 'en',
                    // Real content, so a refusal cannot be the empty-content
                    // guard answering instead of the permission gate.
                    content: '# written by a user who should not be able to'
                }).then(response => {
                    expectRefusal(
                        response,
                        403,
                        'not allowed',
                        `site-files "${action}" requires publish, which a contributor does not hold`
                    );
                });
            });
        });

        it('did not write robots.txt while being refused', () => {
            // The refusals above claim an absence of effect. This asserts it:
            // whatever applyRobots answered, the site must not carry the text.
            asRoot();
            geoPost(ENDPOINTS.siteFiles, {
                action: 'previewRobots',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, 'root can read the stored robots.txt');
                expect(
                    response.body.current,
                    'the contributor\'s refused applyRobots must not have written anything'
                ).to.not.contain('should not be able to');
            });
        });
    });

    describe('a user holding publish', () => {
        beforeEach(() => {
            asUser(fixture.publisher);
        });

        it('may run the per-page crawler check', () => {
            geoPost(ENDPOINTS.crawlerCheck, {path: fixture.homePath, language: 'en'}).then(response => {
                expectJsonOk(response, 'a publisher may check a page');
                expect(response.body.published).to.eq(true);
            });
        });

        // The read-only and cheap actions, all of which sit behind the same gate
        // as the expensive ones. runScan is exercised once, in the happy-path
        // spec, because it fetches every published page.
        const allowedScanActions = SITE_SCAN_ACTIONS.filter(
            action => action !== 'runScan' && action !== 'saveSchedule'
        );

        allowedScanActions.forEach(action => {
            it(`is allowed site-scan "${action}"`, () => {
                geoPost(ENDPOINTS.siteScan, {
                    action,
                    path: fixture.sitePath,
                    language: 'en',
                    // Only read by saveSchemaMap; ignored by the others.
                    map: {}
                }).then(response => {
                    expectJsonOk(response, `site-scan "${action}" is allowed for a publisher`);
                });
            });
        });

        it('is allowed site-scan "saveSchedule", and the scheduler agrees with what was stored', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'saveSchedule',
                path: fixture.sitePath,
                language: 'en',
                enabled: false,
                cron: ''
            }).then(response => {
                expectJsonOk(response, 'saveSchedule is allowed for a publisher');
                // Disabled means unscheduled, so there must be no next run.
                expect(response.body.nextRun, 'a disabled schedule has no next run').to.eq(null);
            });
        });

        it('is allowed site-files "previewRobots", and gets the crawler table the panel needs', () => {
            geoPost(ENDPOINTS.siteFiles, {
                action: 'previewRobots',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, 'previewRobots is allowed for a publisher');
                expect(response.body.siteKey).to.eq(fixture.siteKey);
                expect(response.body.agents, 'the panel needs one row per AI crawler').to.be.an('array');
                expect(response.body.agents.length, 'the crawler list must not be empty').to.be.greaterThan(0);
            });
        });

        it('is allowed site-files "previewLlms"', () => {
            geoPost(ENDPOINTS.siteFiles, {
                action: 'previewLlms',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, 'previewLlms is allowed for a publisher');
            });
        });
    });
});
