/**
 * What a `path`, a `scope` and a `language` are allowed to be.
 *
 * These three values arrive from the client shaped like paths, and two of them
 * end up in front of a **system** session — the scans deliberately run
 * privileged, because reporting what guest cannot see is the point of them. So
 * the boundary is drawn in the caller's own session first, and this spec is what
 * proves the drawing holds.
 *
 * The cases that matter are the ones a prefix check would wave through:
 * `/sites/a/../b` starts with `/sites/a/` as a string and names another site as
 * a path; `..` is a valid-looking language and a directory. Both are tested
 * against a second, real site the caller has no rights on, so a refusal means
 * "refused", not "nothing there to find".
 */

import {asUser} from '../support/auth';
import {ENDPOINTS, expectJsonOk, expectRefusal, geoPost} from '../support/geo';
import {
    configureModuleForTests,
    geoFixture,
    setupGeoFixture,
    teardownGeoFixture
} from '../support/fixtures';

describe('site-scan — scope, path and language boundaries', () => {
    const fixture = geoFixture('geoscope');
    // Deliberately named so that its path has the site under test's path as a
    // string prefix: "/sites/geoscope" + "x". A prefix check written without the
    // trailing slash lets this through, and the module is careful to add one.
    const other = geoFixture('geoscopex');

    before(() => {
        setupGeoFixture(fixture);
        // A second real site, so "refused" is distinguishable from "absent".
        // The publisher of the first site holds nothing on this one.
        setupGeoFixture(other);
        configureModuleForTests();
    });

    after(() => {
        teardownGeoFixture(fixture);
        teardownGeoFixture(other);
    });

    beforeEach(() => {
        asUser(fixture.publisher);
    });

    describe('scope', () => {
        it('accepts no scope at all, and scans the whole site', () => {
            // The liveness case. Every refusal below is only meaningful because
            // this identical call, minus the hostile value, succeeds.
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, 'a scan with no scope is the ordinary case');
            });
        });

        it('accepts a scope naming a subtree of the site', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: fixture.sitePath,
                language: 'en',
                scope: fixture.childPath
            }).then(response => {
                expectJsonOk(response, 'a page inside the site is a legitimate scope');
            });
        });

        it('accepts the site path itself as a scope', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: fixture.sitePath,
                language: 'en',
                scope: fixture.sitePath
            }).then(response => {
                expectJsonOk(response, 'the site root is inside the site');
            });
        });

        const refusedScopes: Array<{label: string; scope: (() => string)}> = [
            {label: 'the repository root', scope: () => '/'},
            {label: 'the sites folder', scope: () => '/sites'},
            // Also the prefix case: "/sites/geoscopex" starts with
            // "/sites/geoscope" as a string and is a different site.
            {label: 'another site whose key extends this one', scope: () => other.sitePath},
            {label: 'a page in another site', scope: () => other.homePath},
            // The one a startsWith() check lets through.
            {label: 'a relative path climbing out of the site', scope: () => `${fixture.sitePath}/../${other.siteKey}`},
            {label: 'a relative path climbing to the sites folder', scope: () => `${fixture.sitePath}/..`}
        ];

        refusedScopes.forEach(({label, scope}) => {
            it(`refuses a scope naming ${label}`, () => {
                geoPost(ENDPOINTS.siteScan, {
                    action: 'scanStatus',
                    path: fixture.sitePath,
                    language: 'en',
                    scope: scope()
                }).then(response => {
                    expectRefusal(
                        response,
                        403,
                        'cannot read node',
                        `a scope naming ${label} must not be scanned under a system session`
                    );
                });
            });
        });
    });

    describe('path', () => {
        it('refuses the repository root', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: '/',
                language: 'en'
            }).then(response => {
                expectRefusal(response, 400, 'path required', '"/" is not inside /sites/');
            });
        });

        it('refuses the sites folder', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: '/sites',
                language: 'en'
            }).then(response => {
                // "/sites" fails the /sites/ prefix check, which is why the
                // trailing slash in that check is not cosmetic.
                expectRefusal(response, 400, 'path required', '"/sites" is not a site');
            });
        });

        it('refuses another site the caller has no rights on', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: other.sitePath,
                language: 'en'
            }).then(response => {
                expectRefusal(
                    response,
                    403,
                    'cannot read node',
                    'holding publish on one site grants nothing on another'
                );
            });
        });

        it('refuses a relative path that leaves the site', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: `${fixture.sitePath}/../${other.siteKey}`,
                language: 'en'
            }).then(response => {
                expectRefusal(
                    response,
                    403,
                    'cannot read node',
                    'a path is resolved before the site is decided, so .. cannot borrow the first site\'s rights'
                );
            });
        });

        it('proves the escape target is a real site the publisher simply may not touch', () => {
            // Without this, both refusals above would also be produced by a path
            // that resolves to nothing — and the spec would be asserting that
            // typos are rejected rather than that the boundary holds.
            asUser(other.publisher);
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: other.sitePath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, 'the other site exists and answers for its own publisher');
            });
        });
    });

    describe('language', () => {
        const refusedLanguages = ['..', '../../x', '../../../etc', 'en/../..', '', ' ', 'e', 'en_'];

        refusedLanguages.forEach(language => {
            it(`refuses the language ${JSON.stringify(language)}`, () => {
                geoPost(ENDPOINTS.siteScan, {
                    action: 'scanStatus',
                    path: fixture.sitePath,
                    language
                }).then(response => {
                    expectRefusal(
                        response,
                        400,
                        'language required',
                        `${JSON.stringify(language)} becomes a node name under a system session, so it must be refused`
                    );
                });
            });
        });

        // Both spellings Jahia uses in practice: the language tag and the
        // translation-node name. Neither is refused by the validator.
        ['en', 'fr-BE', 'pt_BR'].forEach(language => {
            it(`accepts the language ${JSON.stringify(language)}`, () => {
                geoPost(ENDPOINTS.siteScan, {
                    action: 'scanStatus',
                    path: fixture.sitePath,
                    language
                }).then(response => {
                    expectJsonOk(response, `${language} is a language Jahia recognises`);
                });
            });
        });
    });

    describe('the same boundary on the other two endpoints', () => {
        // The three servlets validate independently — they do not share a
        // filter — so the same hostile values are put to each of them.

        it('crawler-check refuses a path outside /sites/', () => {
            geoPost(ENDPOINTS.crawlerCheck, {path: '/', language: 'en'}).then(response => {
                expectRefusal(response, 400, 'path required', 'crawler-check validates its own path');
            });
        });

        it('crawler-check refuses a traversing language', () => {
            geoPost(ENDPOINTS.crawlerCheck, {path: fixture.homePath, language: '../..'}).then(response => {
                expectRefusal(response, 400, 'language required', 'crawler-check validates its own language');
            });
        });

        it('crawler-check refuses a node in another site', () => {
            geoPost(ENDPOINTS.crawlerCheck, {path: other.homePath, language: 'en'}).then(response => {
                expectRefusal(
                    response,
                    403,
                    'cannot read node',
                    'the drawer floor is "can edit this content", which this caller cannot'
                );
            });
        });

        it('site-files refuses a path outside /sites/', () => {
            geoPost(ENDPOINTS.siteFiles, {
                action: 'previewRobots',
                path: '/sites',
                language: 'en'
            }).then(response => {
                expectRefusal(response, 400, 'path required', 'site-files validates its own path');
            });
        });

        it('site-files refuses a traversing language', () => {
            geoPost(ENDPOINTS.siteFiles, {
                action: 'previewRobots',
                path: fixture.sitePath,
                language: '..'
            }).then(response => {
                expectRefusal(response, 400, 'language required', 'site-files validates its own language');
            });
        });

        it('site-files refuses another site', () => {
            geoPost(ENDPOINTS.siteFiles, {
                action: 'applyRobots',
                path: other.sitePath,
                language: 'en',
                content: 'User-agent: *\nDisallow: /'
            }).then(response => {
                expectRefusal(
                    response,
                    403,
                    'not allowed',
                    'writing another site\'s robots.txt requires publish on that site'
                );
            });
        });
    });
});
