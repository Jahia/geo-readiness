/**
 * The written report endpoint: its gate, its inputs, and its two states.
 *
 * The provider is external and paid for, so this spec never asks for a report.
 * What it can prove without one is everything around the call: that the same
 * `publish` gate as the dashboard applies, that the status never carries a key,
 * that a path-shaped language is refused before anything runs, and that with no
 * provider configured a generation is refused with 409 rather than attempted.
 */

import {asGuest, asUser} from '../support/auth';
import {ENDPOINTS, expectJsonOk, expectRefusal, geoPost, jahiaOrigin} from '../support/geo';
import {configureModuleForTests, geoFixture, setupGeoFixture, teardownGeoFixture} from '../support/fixtures';

describe('geo-readiness report endpoint', () => {
    const fixture = geoFixture('georeport');

    before(() => {
        setupGeoFixture(fixture);
        // No provider: the tab must not exist and a generation must not be attempted.
        configureModuleForTests({AI_PROVIDER: '', AI_MODEL: '', AI_API_KEY: ''});
    });

    after(() => {
        teardownGeoFixture(fixture);
    });

    describe('status', () => {
        it('refuses a guest', () => {
            asGuest();
            cy.request({url: ENDPOINTS.report, headers: {Origin: jahiaOrigin()}, failOnStatusCode: false})
                .then(response => {
                    expectRefusal(response, 401, 'authentication required', 'the status is not public');
                });
        });

        it('says whether a provider is configured, and nothing else', () => {
            asUser(fixture.publisher);
            cy.request({url: ENDPOINTS.report, headers: {Origin: jahiaOrigin()}}).then(response => {
                expectJsonOk(response, 'the status answers an authenticated user');
                expect(response.body.enabled, 'no provider is configured in this run').to.eq(false);
                expect(Object.keys(response.body).sort(), 'the status carries no key and no url')
                    .to.deep.eq(['enabled', 'model', 'provider']);
            });
        });
    });

    describe('a publisher', () => {
        beforeEach(() => {
            asUser(fixture.publisher);
        });

        it('reads back the stored report, which is null before any generation', () => {
            geoPost(ENDPOINTS.report, {action: 'read', path: fixture.sitePath, language: 'en'}).then(response => {
                expectJsonOk(response, 'reading is allowed with publish');
                expect(response.body.report, 'nothing has been generated for this fresh site').to.eq(null);
            });
        });

        it('is refused a generation with 409 while no provider is configured', () => {
            geoPost(ENDPOINTS.report, {
                action: 'generate', path: fixture.sitePath, language: 'en', reportLanguage: 'en'
            }).then(response => {
                expectRefusal(response, 409, 'no provider configured',
                    'a generation must not be attempted without a provider');
            });
        });

        ['..', '../../x', 'en/../..'].forEach(bad => {
            it(`is refused a language of "${bad}" before anything runs`, () => {
                geoPost(ENDPOINTS.report, {action: 'read', path: fixture.sitePath, language: bad}).then(response => {
                    expectRefusal(response, 400, 'language required', 'the language becomes a node name');
                });
            });

            it(`is refused a report language of "${bad}" before anything runs`, () => {
                geoPost(ENDPOINTS.report, {
                    action: 'read', path: fixture.sitePath, language: 'en', reportLanguage: bad
                }).then(response => {
                    expectRefusal(response, 400, 'language required', 'the report language reaches the prompt');
                });
            });
        });

        it('is refused an unknown action', () => {
            geoPost(ENDPOINTS.report, {action: 'delete', path: fixture.sitePath, language: 'en'}).then(response => {
                expectRefusal(response, 400, 'unknown action', 'only read and generate exist');
            });
        });

        it('is refused a form post', () => {
            geoPost(ENDPOINTS.report, {}, {
                contentType: 'application/x-www-form-urlencoded',
                rawBody: 'action=read&path=/sites/x'
            }).then(response => {
                expectRefusal(response, 400, 'json required', 'a cross-site form cannot reach the endpoint');
            });
        });
    });

    describe('a contributor, who holds publication-start but not publish', () => {
        beforeEach(() => {
            asUser(fixture.contributor);
        });

        it('may read the status', () => {
            cy.request({url: ENDPOINTS.report, headers: {Origin: jahiaOrigin()}}).then(response => {
                expectJsonOk(response, 'the status only says whether a provider exists');
            });
        });

        ['read', 'generate'].forEach(action => {
            it(`is refused "${action}" with 403`, () => {
                geoPost(ENDPOINTS.report, {action, path: fixture.sitePath, language: 'en', reportLanguage: 'en'})
                    .then(response => {
                        expectRefusal(response, 403, 'cannot read node',
                            `the report requires publish, which a contributor does not hold`);
                    });
            });
        });
    });
});
