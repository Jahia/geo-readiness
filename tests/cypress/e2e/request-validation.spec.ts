/**
 * What each endpoint does with a request it cannot make sense of.
 *
 * All three servlets validate independently and in the same order: guest, then
 * content type, then body, then path, then language. The order matters as much
 * as the rules — a malformed body must not be parsed before the caller is known,
 * and a non-JSON content type must be refused before anything is read, because
 * that check is what makes a cross-site form post impossible.
 *
 * Every case here asserts the error *message* as well as the status, because
 * all four of these answer 400 and a status-only assertion cannot tell them
 * apart.
 */

import {asRoot} from '../support/auth';
import {ENDPOINTS, expectJsonOk, expectRefusal, geoPost, GeoEndpoint} from '../support/geo';
import {
    configureModuleForTests,
    geoFixture,
    setupGeoFixture,
    teardownGeoFixture
} from '../support/fixtures';

describe('geo-readiness endpoints — request validation', () => {
    const fixture = geoFixture('geovalid');

    const endpoints: Array<{name: string; url: GeoEndpoint; validBody: Record<string, unknown>}> = [
        {
            name: 'crawler-check',
            url: ENDPOINTS.crawlerCheck,
            validBody: {path: `/sites/geovalid/home`, language: 'en'}
        },
        {
            name: 'site-scan',
            url: ENDPOINTS.siteScan,
            validBody: {action: 'scanStatus', path: `/sites/geovalid`, language: 'en'}
        },
        {
            name: 'site-files',
            url: ENDPOINTS.siteFiles,
            validBody: {action: 'previewRobots', path: `/sites/geovalid`, language: 'en'}
        }
    ];

    before(() => {
        setupGeoFixture(fixture);
        configureModuleForTests();
    });

    after(() => {
        teardownGeoFixture(fixture);
    });

    beforeEach(() => {
        // Root, so nothing here can be refused for a permission reason and be
        // mistaken for a validation result.
        asRoot();
    });

    endpoints.forEach(({name, url, validBody}) => {
        describe(name, () => {
            it('accepts the well-formed request these cases are variations of', () => {
                // The liveness assertion for the whole describe. Without it a
                // wrong path or a renamed action would refuse every request
                // below for reasons that have nothing to do with what is being
                // tested, and the refusals would still all pass.
                geoPost(url, validBody).then(response => {
                    expectJsonOk(response, `${name} answers the valid form of these requests`);
                });
            });

            it('refuses a malformed JSON body', () => {
                geoPost(url, {}, {rawBody: '{"action": "scanStatus", '}).then(response => {
                    expectRefusal(response, 400, 'malformed body', 'a truncated JSON body is refused');
                });
            });

            it('refuses a body that is not JSON at all', () => {
                geoPost(url, {}, {rawBody: 'not json, not even close'}).then(response => {
                    expectRefusal(response, 400, 'malformed body', 'a plain-text body is refused');
                });
            });

            it('refuses an empty body', () => {
                geoPost(url, {}, {rawBody: ''}).then(response => {
                    expectRefusal(response, 400, 'malformed body', 'an empty body is not an empty object');
                });
            });

            it('refuses a missing path', () => {
                expect(validBody.path, 'the case is only meaningful if the valid body had a path').to.be.a('string');
                const withoutPath = {...validBody};
                delete withoutPath.path;
                geoPost(url, withoutPath).then(response => {
                    expectRefusal(response, 400, 'path required', 'a request with no path is refused');
                });
            });

            it('refuses an empty path', () => {
                geoPost(url, {...validBody, path: ''}).then(response => {
                    expectRefusal(response, 400, 'path required', 'an empty path is refused');
                });
            });

            it('refuses a path outside /sites/', () => {
                geoPost(url, {...validBody, path: '/modules/geo-readiness'}).then(response => {
                    expectRefusal(response, 400, 'path required', 'only content under /sites/ is in scope');
                });
            });

            it('refuses a form-encoded content type', () => {
                // This is the CSRF guard: a cross-site HTML form can only send
                // form, multipart or plain-text content types, so refusing
                // anything but JSON is what makes one impossible.
                geoPost(url, {}, {
                    contentType: 'application/x-www-form-urlencoded',
                    rawBody: 'action=scanStatus&path=/sites/geovalid'
                }).then(response => {
                    expectRefusal(response, 400, 'json required', 'a form post cannot reach this endpoint');
                });
            });

            it('refuses a text/plain content type', () => {
                geoPost(url, {}, {
                    contentType: 'text/plain',
                    rawBody: JSON.stringify(validBody)
                }).then(response => {
                    expectRefusal(response, 400, 'json required', 'valid JSON under the wrong type is still refused');
                });
            });

            it('refuses a request with no content type', () => {
                geoPost(url, {}, {
                    contentType: false,
                    rawBody: JSON.stringify(validBody)
                }).then(response => {
                    expectRefusal(response, 400, 'json required', 'an absent content type is refused, not assumed');
                });
            });

            it('accepts application/json with a charset', () => {
                // The check is a substring match, which is what lets a browser's
                // own "application/json;charset=UTF-8" through. Asserted so that
                // tightening it to an equality check does not silently break
                // every real client.
                geoPost(url, validBody, {contentType: 'application/json;charset=UTF-8'}).then(response => {
                    expectJsonOk(response, 'a charset parameter does not change the media type');
                });
            });

            it('refuses the content type before it reads the body', () => {
                // Order, not just outcome: a body that would fail parsing must
                // be reported as a content-type problem, which proves nothing
                // was parsed before the type was checked.
                geoPost(url, {}, {contentType: 'text/plain', rawBody: '{"broken'}).then(response => {
                    expectRefusal(response, 400, 'json required', 'the content type is checked first');
                });
            });
        });
    });

    describe('unknown actions', () => {
        it('site-scan refuses an action it does not know', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'dropEverything',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectRefusal(response, 400, 'unknown action', 'an unknown site-scan action is refused');
            });
        });

        it('site-scan refuses a missing action', () => {
            geoPost(ENDPOINTS.siteScan, {path: fixture.sitePath, language: 'en'}).then(response => {
                expectRefusal(response, 400, 'unknown action', 'an absent action has no default');
            });
        });

        it('site-files refuses an action it does not know', () => {
            geoPost(ENDPOINTS.siteFiles, {
                action: 'applyAnything',
                path: fixture.sitePath,
                language: 'en',
                content: 'x'
            }).then(response => {
                expectRefusal(response, 400, 'unknown action', 'an unknown site-files action is refused');
            });
        });
    });

    describe('applying an empty site file', () => {
        it('refuses with 400 and does not write', () => {
            // This used to assert 200-with-an-error-body, deliberately pinning the
            // defect so that fixing it would fail here rather than drift. It has
            // now been fixed: the empty-content guard refuses with a status like
            // every other refusal in the module, which matters because the
            // frontend's shared call() helper only checks res.ok and reported the
            // old 200 to the user as a successful write.
            geoPost(ENDPOINTS.siteFiles, {
                action: 'applyRobots',
                path: fixture.sitePath,
                language: 'en',
                content: '   '
            }).then(response => {
                expect(response.status, 'an empty write is a refusal, not a result').to.eq(400);
                expect(response.body.error, 'and it says why').to.eq('empty content');
                expect(response.body.written, 'nothing was written').to.be.undefined;
            });
        });
    });
});
