/**
 * The primary flow, both as the browser drives it and as the endpoints answer it.
 *
 * The coverage standard's floor is that every feature has at least one Cypress
 * scenario going through the real UI — that is what proves the feature works for
 * an editor rather than that its servlets compile. The API half is kept beside
 * it because it is what says *why* a UI failure happened: if the dashboard
 * renders but reports nothing, these tests say whether the scan itself answered.
 */

import {asUser} from '../support/auth';
import {ENDPOINTS, expectJsonOk, geoPost} from '../support/geo';
import {
    configureModuleForTests,
    geoFixture,
    setupGeoFixture,
    teardownGeoFixture
} from '../support/fixtures';
import {
    GEO_ACTION_SELECTOR,
    GEO_NAV_LABEL,
    openGeoDashboard,
    openJContentApps,
    openJContentPages,
    SEO_LABEL,
    selectTab
} from '../support/ui';

describe('geo-readiness — happy path', () => {
    const fixture = geoFixture('geohappy');

    before(() => {
        setupGeoFixture(fixture);
        configureModuleForTests();
    });

    after(() => {
        teardownGeoFixture(fixture);
    });

    describe('through the endpoints', () => {
        beforeEach(() => {
            asUser(fixture.publisher);
        });

        it('runs a site scan and stores a score for the site', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'runScan',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, 'a publisher can run a scan of their own site');
                // Asserting the stored state, not just that the call returned:
                // runScan answers with what ScanStore holds afterwards, and what
                // it holds is a `run` object - there is no `lastRun` anywhere in
                // the module. `startedAt` is what distinguishes a run that
                // happened from the idle shape, which is `{status: "IDLE"}`.
                expect(response.body, 'the scan must record a run').to.have.property('run');
                expect(response.body.run.startedAt, 'the run must have started').to.not.be.null;
            });
        });

        it('reads the stored scan back through scanStatus', () => {
            geoPost(ENDPOINTS.siteScan, {
                action: 'scanStatus',
                path: fixture.sitePath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, 'the dashboard polls this while a scan runs');
                expect(
                    response.body.run,
                    'scanStatus must carry the run block'
                ).to.exist;
                expect(
                    response.body.run.startedAt,
                    'the run performed in the previous test must still be there'
                ).to.not.be.null;
            });
        });

        it('reports the public URL of a published page to the drawer', () => {
            geoPost(ENDPOINTS.crawlerCheck, {
                path: fixture.homePath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, 'the drawer checks a page the editor selected');
                expect(response.body.published, 'the fixture home page was published in before()').to.eq(true);
                expect(response.body.url, 'the drawer shows the URL it tested').to.be.a('string');
                expect(response.body.url, 'it must be the page that was asked about').to.contain('home');
                expect(response.body.agents, 'one row per AI crawler').to.be.an('array');
                expect(response.body.agents.length, 'the crawler list must not be empty').to.be.greaterThan(0);
            });
        });

        it('reports an unpublished page as unpublished rather than failing', () => {
            // The child page was created after the site was published, so it is
            // absent from live. That is a real answer, not an error, and the
            // drawer renders it as one.
            geoPost(ENDPOINTS.crawlerCheck, {
                path: fixture.childPath,
                language: 'en'
            }).then(response => {
                expectJsonOk(response, 'a never-published page still answers');
                expect(response.body.published, 'it has no public URL yet').to.eq(false);
                expect(response.body.url, 'and therefore no URL to show').to.be.undefined;
            });
        });
    });

    describe('through jContent', () => {
        // These are the scenarios the coverage standard will not let unit or
        // endpoint tests stand in for.

        it('shows the dashboard under site settings > SEO to a user holding publish', () => {
            cy.login(fixture.publisher.username, fixture.publisher.password);
            openGeoDashboard(fixture.siteKey);

            // The dashboard's own header names the site and the language.
            cy.contains('h1, h2, header, [data-sel-role="header"]', GEO_NAV_LABEL, {timeout: 30000})
                .should('be.visible');
            // The four groups the panel is organised into.
            cy.contains('Overview', {timeout: 30000}).should('be.visible');
        });

        it('scans the site from the dashboard and shows a result', () => {
            cy.login(fixture.publisher.username, fixture.publisher.password);
            openGeoDashboard(fixture.siteKey);

            // "Invisible content" — the cheapest panel that performs a real
            // scan: it reads the repository rather than fetching every page.
            //
            // It is a SUB-tab. The dashboard's top level is Overview / Can a
            // crawler reach it / Is what arrives usable / Site-level files, and
            // Invisible content sits under the second of those next to Internal
            // links and Addresses. Reaching for it directly matched nothing.
            selectTab('Can a crawler reach it');
            selectTab('Invisible content');
            cy.contains('button', 'Scan the site', {timeout: 30000}).click();

            // The summary line the panel renders from the scan's own numbers.
            // Retry-driven: the scan is a server round trip with no other signal.
            cy.contains('published pages can be read by a visitor with no account', {timeout: 120000})
                .should('be.visible');
        });

        it('offers the drawer on a page, and the drawer reports the tested URL', () => {
            cy.login(fixture.publisher.username, fixture.publisher.password);
            openJContentPages(fixture.siteKey);

            // /pages lands on the site's home page with it already selected and
            // its toolbar rendered, so there is no row to click - and no table
            // to click it in, since this view is Page Builder.
            cy.get(GEO_ACTION_SELECTOR, {timeout: 30000}).click();

            // The drawer opens on its own prompt and a Run check button; the
            // tested URL is a result, so it is asserted after the run and not
            // before it.
            cy.contains('button', 'Run check', {timeout: 30000}).click();

            // The drawer fetches the page once per crawler, three at a time, so
            // the budget here is the servlet's worst case and not Cypress's. The
            // score line is what proves a result was rendered rather than a
            // spinner left running: it is computed from those fetches.
            //
            // This used to look for /Reachable|Blocked/. Both strings exist in
            // en.json, but they are not what the panel puts on the page once a
            // check has run, so the assertion sat through its whole 180s budget
            // against a drawer that had finished and was showing its results.
            cy.contains(/\d+ of \d+ checks passed/, {timeout: 180000}).should('be.visible');

            // And the address it actually fetched, which is on Crawler access
            // rather than on the Score tab the results open on.
            selectTab('Crawler access', 60000);
            cy.contains('Tested URL', {timeout: 60000}).should('be.visible');
        });

        it('does not offer the dashboard to a contributor', () => {
            // The UI half of the authorization boundary: the admin route
            // declares requiredPermission 'publish', so the entry must not be
            // rendered at all for a user who lacks it.
            //
            // The liveness for this absence is the *previous* test: the same
            // navigation, taken by a user who does hold publish, ends on the
            // label. So a red here means one of two things and both are worth
            // knowing — either the entry is shown to a contributor (a real
            // defect), or the contributor cannot reach the SEO section at all,
            // in which case the security property holds and this navigation
            // needs rewriting. tests/README.md says how to tell them apart.
            cy.login(fixture.contributor.username, fixture.contributor.password);
            openJContentApps(fixture.siteKey);

            // The liveness this test's premise needs, asserted rather than
            // assumed: the contributor reaches the SEO section itself, so an
            // absent entry below means absent and not unreachable. Measured on
            // 8.2, a contributor is offered Page models and SEO but NOT Link
            // checker - the section is filtered by permission, so arriving here
            // proves nothing was hidden wholesale.
            cy.contains(SEO_LABEL, {timeout: 30000}).should('be.visible');
            cy.contains(GEO_NAV_LABEL).should('not.exist');
        });
    });
});
