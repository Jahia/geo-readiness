/**
 * What the guest-visibility scan actually REPORTS.
 *
 * GEO-19 asks a question no crawl can answer: some servers hand a gated page a
 * login form and a cheerful 200, so a fetch cannot tell "readable" from "looks
 * readable". The repository can, and this check is the module asking it as
 * `guest` rather than as the editor.
 *
 * Nothing asserted its output before this file. `guestVisibility` was called
 * three times across the whole suite, all inside rate-limit.spec.ts, to spend
 * the per-user budget - and those calls assert only that the endpoint answered
 * 200. Inverting `isolatedRestriction` and `gatedBranch`, which would tell a
 * customer their members area is a misconfiguration or their misconfiguration
 * is a members area, would not have failed anything.
 *
 * It has to be an end-to-end test rather than a unit test. In Jahia a server
 * administrator is a ROLE, and a role is delivered through ACL entries, which is
 * exactly what breaking inheritance removes; and the check deliberately reads
 * `live` through a real guest session because a system session would bypass the
 * very ACLs it is measuring. A mock of that session would be a mock of the one
 * thing under test.
 */

import {asUser, asRoot} from '../support/auth';
import {ENDPOINTS, expectJsonOk, geoPost} from '../support/geo';
import {
    configureModuleForTests,
    geoFixture,
    setupGeoFixture,
    teardownGeoFixture
} from '../support/fixtures';

describe('site-scan — what the guest-visibility scan reports', () => {
    const fixture = geoFixture('geovis');

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

    /** The scan, as a publisher, for the fixture's own site. */
    const scan = () => geoPost(ENDPOINTS.siteScan, {
        action: 'guestVisibility',
        path: fixture.sitePath,
        language: 'en'
    });

    /** Every row the scan produced, findings and deliberate alike, by path. */
    const rowsByPath = (body): Record<string, {kind: string; path: string}> => {
        const all = [...(body.findings || []), ...(body.deliberate || [])];
        return all.reduce((acc, r) => ({...acc, [r.path]: r}), {});
    };

    it('finds the branch a guest cannot read at all', () => {
        scan().then(response => {
            expectJsonOk(response, 'a publisher may scan their own site');

            // The premise. If this is zero the fixture did not build, and every
            // assertion below would pass by describing an empty scan.
            expect(response.body.scanned, 'the scan must have read some pages')
                .to.be.greaterThan(0);

            const rows = rowsByPath(response.body);
            expect(rows, 'the page whose ACL inheritance was broken must be reported')
                .to.have.property(fixture.gatedPath);
        });
    });

    it('calls a closed page under an open one the permission somebody forgot', () => {
        scan().then(response => {
            const rows = rowsByPath(response.body);

            // home is readable, geo-gated is not: an island, and almost always a
            // mistake rather than a decision.
            expect(rows[fixture.gatedPath].kind).to.equal('isolatedRestriction');
        });
    });

    it('calls a closed page under a closed one a members area, not a defect', () => {
        scan().then(response => {
            const rows = rowsByPath(response.body);

            // Both unreadable. Reporting this as a defect is how a check gets
            // ignored: it is what a members area looks like when it works.
            expect(rows[fixture.gatedChildPath].kind).to.equal('gatedBranch');
        });
    });

    it('separates the two: one is a finding, the other is deliberate', () => {
        scan().then(response => {
            const findings = (response.body.findings || []).map(r => r.path);
            const deliberate = (response.body.deliberate || []).map(r => r.path);

            // The split is what an editor acts on. Collapsing them into one list
            // would leave a real defect buried among intended restrictions.
            expect(findings, 'the isolated page is the one to act on')
                .to.include(fixture.gatedPath);
            expect(deliberate, 'the gated branch is intended and is listed apart')
                .to.include(fixture.gatedChildPath);
        });
    });

    it('does not report the pages a guest can read perfectly well', () => {
        scan().then(response => {
            const rows = rowsByPath(response.body);

            expect(rows, 'the published home page is readable and is not a finding')
                .to.not.have.property(fixture.homePath);
        });
    });

    it('sees the restriction that root does not', () => {
        // The reason this suite cannot be written as root. root is the JCR
        // system user: it holds no role, so no ACL applies to it and every page
        // reads as visible. The check runs as guest precisely to avoid that, and
        // this asserts the difference is real rather than assumed - if the ACL
        // break had silently failed, the scan would report nothing and every
        // assertion above would be describing an empty list.
        asRoot();
        cy.executeGroovy('groovy/logger.groovy', {MESSAGE: 'geovis: root reads the gated page'});

        asUser(fixture.publisher);
        scan().then(response => {
            const rows = rowsByPath(response.body);
            expect(rows[fixture.gatedPath], 'guest is denied where root is not')
                .to.have.property('kind');
        });
    });
});
