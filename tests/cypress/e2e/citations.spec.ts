/**
 * BotRank citations (GEO-4): the settings routes, the daily sync against a stand-in BotRank, and
 * the drawer tab.
 *
 * The stand-in is test-fixtures/botrank-stub.js, a service of the test stack reachable from Jahia at
 * BOTRANK_STUB_URL (default http://botrank-stub:9091/public/v1). Locally, run it on the host with
 * `node test-fixtures/botrank-stub.js` and set CYPRESS_BOTRANK_STUB_URL to
 * http://host.docker.internal:9091/public/v1.
 *
 * Security model asserted in both directions: the settings routes spend the customer's organisation
 * key, so `site-admin` on the site is their only gate (the publisher fixture holds it through
 * site-administrator, the contributor through editor does not); the drawer read needs only to read
 * the page. Every absence asserted below is preceded by a liveness check on the same answer.
 */

import {asGuest, asRoot, asUser} from '../support/auth';
import {ENDPOINTS, expectJsonOk, expectRefusal, geoGet, geoPost} from '../support/geo';
import {configureModuleForTests, geoFixture, setupGeoFixture, teardownGeoFixture} from '../support/fixtures';
import {GEO_ACTION_SELECTOR, openJContentPages, selectTab} from '../support/ui';

const STUB = Cypress.env('BOTRANK_STUB_URL') || 'http://botrank-stub:9091/public/v1';
const OFFER = 'https://stub-site.example/legacy/offer';

/**
 * Starts a sync and polls until it has finished: a new syncedAt, or a failure. Comparing with the
 * syncedAt read before the start is what keeps a second sync from passing on the first one's date.
 */
function syncAndWait(path: string): void {
    geoPost(ENDPOINTS.citations, {action: 'status', path, language: 'en'}).then(before => {
        const previous = before.body.syncedAt;
        geoPost(ENDPOINTS.citations, {action: 'syncNow', path, language: 'en'}).its('status').should('eq', 202);
        const poll = (tries: number): void => {
            geoPost(ENDPOINTS.citations, {action: 'status', path, language: 'en'}).then(r => {
                expectJsonOk(r, 'status answers while a sync runs');
                if (r.body.status === 'failed') {
                    throw new Error(`the sync failed: ${r.body.message}`);
                }
                if (r.body.syncedAt === previous || r.body.status === 'running') {
                    if (tries <= 0) {
                        throw new Error('the sync never finished');
                    }
                    // eslint-disable-next-line cypress/no-unnecessary-waiting
                    cy.wait(2000);
                    poll(tries - 1);
                }
            });
        };
        poll(30);
    });
}

describe('geo-readiness - BotRank citations', () => {
    const fixture = geoFixture('geocite');

    before(() => {
        setupGeoFixture(fixture);
        configureModuleForTests();
        // One connection, as a factory configuration, restricted to this site.
        cy.runProvisioningScript({
            script: {
                fileContent: [
                    '- editConfiguration: "org.jahia.se.modules.georeadiness.botrank"',
                    '  configIdentifier: "cypress"',
                    '  properties:',
                    '      label: "Cypress stub"',
                    '      apiKey: "brk_test"',
                    `      baseUrl: "${STUB}"`,
                    `      sites: "${fixture.siteKey}"`
                ].join('\n'),
                type: 'application/yaml'
            }
        });
    });

    after(() => {
        asRoot();
        teardownGeoFixture(fixture);
    });

    describe('who may call what', () => {
        it('refuses a guest on the drawer read', () => {
            asGuest();
            geoGet(ENDPOINTS.citations, {path: fixture.homePath, language: 'en'}).then(r =>
                expectRefusal(r, 401, 'authentication required', 'a guest reads no citations'));
        });

        it('refuses the settings to a contributor and lets them read the drawer', () => {
            asUser(fixture.contributor);
            geoGet(ENDPOINTS.citations, {path: fixture.homePath, language: 'en'}).then(r =>
                expectJsonOk(r, 'a contributor who can read the page reads its citations'));
            geoPost(ENDPOINTS.citations, {action: 'status', path: fixture.sitePath, language: 'en'}).then(r =>
                expectRefusal(r, 403, 'site administration required', 'binding the key is for site administrators'));
            geoPost(ENDPOINTS.citations, {action: 'projects', path: fixture.sitePath, language: 'en', connection: 'cypress'}).then(r =>
                expectRefusal(r, 403, 'site administration required', 'listing projects spends the key'));
        });

        it('lets a site administrator see the connection, never its key', () => {
            asUser(fixture.publisher);
            geoPost(ENDPOINTS.citations, {action: 'status', path: fixture.sitePath, language: 'en'}).then(r => {
                expectJsonOk(r, 'a site administrator reads the settings');
                const ids = r.body.connections.map((c: {id: string}) => c.id);
                expect(ids, 'the factory configuration is listed by its id').to.include('cypress');
                expect(JSON.stringify(r.body), 'no answer carries the key').not.to.contain('brk_test');
            });
            geoPost(ENDPOINTS.citations, {action: 'projects', path: fixture.sitePath, language: 'en', connection: 'cypress'}).then(r => {
                expectJsonOk(r, 'projects come from the stub');
                expect(r.body.projects[0].id).to.eq('p1');
            });
        });

        it('refuses a JSON-less settings call', () => {
            asUser(fixture.publisher);
            geoPost(ENDPOINTS.citations, {action: 'status', path: fixture.sitePath, language: 'en'}, {contentType: 'text/plain'}).then(r =>
                expectRefusal(r, 400, 'json required', 'the content type is the CSRF control'));
        });
    });

    describe('refusals that a working request would pass', () => {
        // Each case changes one thing of a request the publisher may otherwise make.
        const status = {action: 'status', path: fixture.sitePath, language: 'en'};

        it('refuses a simple request that only mentions application/json', () => {
            asUser(fixture.publisher);
            geoPost(ENDPOINTS.citations, status).then(r => expectJsonOk(r, 'liveness: the same call passes as JSON'));
            geoPost(ENDPOINTS.citations, status, {contentType: 'text/plain; application/json'}).then(r =>
                expectRefusal(r, 400, 'json required', 'a browser sends this cross-site without a preflight'));
        });

        it('refuses what the browser marks as another site', () => {
            asUser(fixture.publisher);
            ['cross-site', 'same-site'].forEach(verdict => {
                cy.request({
                    method: 'POST', url: ENDPOINTS.citations, failOnStatusCode: false, body: status,
                    headers: {'Content-Type': 'application/json', 'Sec-Fetch-Site': verdict}
                }).then(r => expectRefusal(r, 403, 'cross-site request refused', `Sec-Fetch-Site: ${verdict}`));
            });
            cy.request({
                method: 'POST', url: ENDPOINTS.citations, failOnStatusCode: false, body: status,
                headers: {'Content-Type': 'application/json', Origin: 'https://evil.example'}
            }).then(r => expectRefusal(r, 403, 'cross-site request refused', 'a foreign Origin'));
        });

        it('refuses settings that are not valid', () => {
            asUser(fixture.publisher);
            const save = {action: 'save', path: fixture.sitePath, language: 'en', connection: 'cypress', project: 'p1',
                hosts: ['stub-site.example'], enabled: false, time: '06:30'};
            geoPost(ENDPOINTS.citations, save).then(r => expectJsonOk(r, 'liveness: the valid settings save'));
            geoPost(ENDPOINTS.citations, {...save, hosts: ['not a host']}).then(r => expectRefusal(r, 400, 'invalid host', 'host'));
            geoPost(ENDPOINTS.citations, {...save, time: '25:00'}).then(r => expectRefusal(r, 400, 'invalid time', 'time'));
            geoPost(ENDPOINTS.citations, {...save, project: 'p1/../x'}).then(r => expectRefusal(r, 400, 'invalid project', 'project'));
            geoPost(ENDPOINTS.citations, {...save, connection: 'nope'}).then(r => expectRefusal(r, 400, 'unknown connection', 'connection'));
        });

        it('refuses a mapping target that is not a page of the site', () => {
            asUser(fixture.publisher);
            const map = {action: 'map', path: fixture.sitePath, language: 'en', url: OFFER, target: fixture.homePath};
            geoPost(ENDPOINTS.citations, {...map, action: 'unmap'}).then(r => expectJsonOk(r, 'liveness: unmap answers'));
            geoPost(ENDPOINTS.citations, {...map, target: fixture.sitePath}).then(r =>
                expectRefusal(r, 400, 'the target is not a page of this site', 'the site node is not a page'));
            geoPost(ENDPOINTS.citations, {...map, target: '/sites/systemsite/home'}).then(r =>
                expectRefusal(r, 400, 'the target is not a page of this site', 'another site'));
        });

        it('opens the drawer read on pages only', () => {
            asUser(fixture.contributor);
            geoGet(ENDPOINTS.citations, {path: fixture.homePath, language: 'en'}).then(r => expectJsonOk(r, 'liveness: a page reads'));
            geoGet(ENDPOINTS.citations, {path: fixture.sitePath, language: 'en'}).then(r =>
                expectRefusal(r, 403, 'cannot read node', 'the site node is not a page'));
        });
    });

    describe('sync, mapping and the drawer', () => {
        it('syncs, lists the unmatched cited URL, then counts it once mapped', () => {
            asUser(fixture.publisher);
            geoPost(ENDPOINTS.citations, {
                action: 'save', path: fixture.sitePath, language: 'en',
                connection: 'cypress', project: 'p1', hosts: ['www.stub-site.example'], enabled: true, time: '06:30'
            }).then(r => {
                expectJsonOk(r, 'settings saved');
                expect(r.body.hosts, 'hosts are folded').to.deep.eq(['stub-site.example']);
                expect(r.body.nextRun, 'the daily trigger is installed').to.be.a('string');
            });

            syncAndWait(fixture.sitePath);
            geoPost(ENDPOINTS.citations, {action: 'status', path: fixture.sitePath, language: 'en'}).then(r => {
                expect(r.body.status, r.body.message).to.eq('done');
                const urls = r.body.unresolved.map((u: {url: string}) => u.url);
                expect(urls, 'the stub URL matches no page of the fixture yet').to.include(OFFER);
            });

            geoPost(ENDPOINTS.citations, {action: 'map', path: fixture.sitePath, language: 'en', url: `${OFFER}/?utm_source=x`, target: fixture.homePath})
                .then(r => expectJsonOk(r, 'a cited URL maps to a page of the site'));
            geoPost(ENDPOINTS.citations, {action: 'map', path: fixture.sitePath, language: 'en', url: 'https://elsewhere.example/x', target: fixture.homePath})
                .then(r => expectRefusal(r, 400, 'the URL is not on a host of this site', 'only the site\'s own hosts map'));

            syncAndWait(fixture.sitePath);

            geoGet(ENDPOINTS.citations, {path: fixture.homePath, language: 'en'}).then(r => {
                expectJsonOk(r, 'the drawer read answers');
                expect(r.body.configured, 'liveness: the site is connected').to.eq(true);
                const page = r.body.page;
                expect(page.citations, 'the stub total').to.eq(12);
                expect(page.via).to.eq('mapping');
                expect(page.variation, 'kept: the previous window has a run day').to.eq(3);
                expect(page.prompts[0].text, 'stored as data').to.eq('Which <b>offer</b> fits a first home?');
                expect(page.coCited[0].outrankingAnswerCount).to.eq(6);
            });

            geoGet(ENDPOINTS.citations, {path: fixture.childPath, language: 'en'}).then(r => {
                expectJsonOk(r, 'the drawer read answers for an uncited page');
                expect(r.body.configured, 'liveness: same connected site').to.eq(true);
                expect(r.body.syncedAt, 'liveness: a sync happened').to.be.a('string');
                expect(r.body.page, 'never cited').to.eq(null);
            });
        });

        it('shows the citations tab before any crawler check, and prompt text as text', () => {
            cy.login(fixture.publisher.username, fixture.publisher.password);
            openJContentPages(fixture.siteKey);
            cy.get(GEO_ACTION_SELECTOR, {timeout: 30000}).click();
            selectTab('AI citations');
            cy.contains('Cited 12 times in AI answers over the last 30 days', {timeout: 30000}).should('be.visible');
            cy.contains('Which <b>offer</b> fits a first home?').should('be.visible');
            cy.contains('https://competitor.example/best-offer').should('be.visible');
        });
    });
});
