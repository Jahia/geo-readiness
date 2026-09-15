/**
 * The site, the users and the role grants every geo-readiness spec needs.
 *
 * Site-per-suite: each spec file owns a site key of its own and deletes it in
 * `after()`, so specs cannot interfere and a repeated run is stable. Teardown
 * runs first in `before()` as well, because a suite killed mid-run leaves a site
 * and two users behind and the next run must not inherit them.
 */

import {
    addNode,
    createSite,
    createUser,
    deleteSite,
    deleteUser,
    enableModule,
    grantRoles,
    publishAndWaitJobEnding
} from '@jahia/cypress';
import {asRoot, GeoTestUser} from './auth';

/** The module under test, as Jahia names its bundle. */
export const MODULE_NAME = 'geo-readiness';

/**
 * The template set the fixture site is built from.
 *
 * Overridable because the suite asserts nothing about rendering — it needs a
 * site with a page tree and a published home page, and any template set that
 * provides one will do.
 */
export const TEMPLATE_SET: string = Cypress.env('GEO_TEMPLATE_SET') || 'dx-base-demo-templates';

/**
 * The roles that carry `publish` on a site.
 *
 * `editor-in-chief` was the original choice and does not exist on
 * `ghcr.io/jahia/jahia-ee-dev:8-SNAPSHOT`, which ships eleven roles and not
 * that one. Granting a role that is not there grants nothing, so every user
 * came out with no rights at all — which is the state `assertSitePermission`
 * exists to catch, and did.
 *
 * Measured on that image, the roles carrying `publish` are `reviewer`,
 * `site-administrator` and `server-administrator`. `site-administrator` is the
 * one used here because the UI half of the happy path opens Site Settings, and
 * a role carrying `publish` but unable to reach that screen would fail the UI
 * tests for an unrelated reason.
 *
 * Overridable, because which role carries `publish` is exactly the thing that
 * moves between Jahia versions.
 */
export const PUBLISHER_ROLES = (Cypress.env('GEO_PUBLISHER_ROLES') || 'site-administrator,privileged').split(',');

/**
 * The roles that carry `jcr:modifyProperties` and `publication-start` but NOT
 * `publish`. This is the interesting denial case: a user who can edit and can
 * *request* publication, and who must still be refused the dashboard, because
 * the dashboard rewrites robots.txt and llms.txt for the whole site.
 *
 * `contributor` does not exist on the image either. `editor` is the role that
 * holds `publication-start` without `publish` there, which is the shape this
 * constant is describing.
 */
export const CONTRIBUTOR_ROLES = (Cypress.env('GEO_CONTRIBUTOR_ROLES') || 'editor,privileged').split(',');

export type GeoFixture = {
    siteKey: string;
    sitePath: string;
    homePath: string;
    /** A page inside the site, used as a legitimate `scope`. */
    childPath: string;
    publisher: GeoTestUser;
    contributor: GeoTestUser;
};

/**
 * Describe (but do not create) the fixture for one suite.
 * @param {string} siteKey the suite's own site key. Must be unique per spec file.
 * @returns {GeoFixture} every path and user the suite will use.
 */
export function geoFixture(siteKey: string): GeoFixture {
    return {
        siteKey,
        sitePath: `/sites/${siteKey}`,
        homePath: `/sites/${siteKey}/home`,
        childPath: `/sites/${siteKey}/home/geo-child`,
        publisher: {username: `${siteKey}-publisher`, password: 'geoPublisher1234'},
        contributor: {username: `${siteKey}-contributor`, password: 'geoContributor1234'}
    };
}

/**
 * Make the module's runtime configuration suitable for a test run.
 *
 * Two settings, both of which produce confusing reds if left at their defaults:
 *
 *  - **PUBLIC_BASE_URL.** Otherwise the crawler check resolves the public URL
 *    from the site's server name, which in a test environment is `localhost` for
 *    the *browser* and not necessarily for the JVM. The check then reports every
 *    crawler as unreachable — a plausible-looking red that says nothing about
 *    the product.
 *  - **RATE_MAX_CALLS.** The per-user limit on crawler checks defaults to 20 per
 *    ten minutes. A suite that exercises the validation cases spends that budget
 *    as root, and a second run inside the same window then meets 429 on a test
 *    asserting 400. Raising it here is not hiding the limiter: `site-scan`'s own
 *    limit is a constant in the servlet, not configuration, and it is what
 *    `rate-limit.spec.ts` measures.
 *
 * @param {Record<string, string>} overrides extra configuration keys for one suite.
 */
export function configureModuleForTests(overrides: Record<string, string> = {}): void {
    const properties: Record<string, string> = {
        PUBLIC_BASE_URL: Cypress.env('GEO_PUBLIC_BASE_URL') || 'http://localhost:8080',
        RATE_MAX_CALLS: '10000',
        ...overrides
    };
    const lines = ['- editConfiguration: "org.jahia.se.modules.georeadiness"', '  properties:'];
    Object.entries(properties).forEach(([key, value]) => {
        lines.push(`      ${key}: "${value}"`);
    });
    cy.runProvisioningScript({
        script: {fileContent: lines.join('\n'), type: 'application/yaml'}
    });
}

/**
 * Create the site, a child page, the two users and their role grants, and
 * publish the site so the crawler check has something live to look at.
 *
 * @param {GeoFixture} fixture the fixture to build.
 */
export function setupGeoFixture(fixture: GeoFixture): void {
    asRoot();

    // A previous run that was killed rather than finished leaves all of this
    // behind, and createSite is a no-op on an existing key — which would hand
    // this run a site with unknown ACLs.
    teardownGeoFixture(fixture);

    createSite(fixture.siteKey, {
        templateSet: TEMPLATE_SET,
        serverName: 'localhost',
        locale: 'en',
        languages: 'en'
    });

    // Both front-end entry points declare requireModuleInstalledOnSite, so
    // without this the drawer action and the dashboard route are not registered
    // at all and the UI spec looks for controls that were never rendered.
    enableModule(MODULE_NAME, fixture.siteKey);

    // Publish first, so that what follows is genuinely absent from live.
    publishAndWaitJobEnding(fixture.sitePath, ['en']);

    // A page inside the site, so a scope naming a real subtree can be tested
    // against something that exists rather than against the site root.
    //
    // Created AFTER the publication on purpose. The crawler check reports
    // `published: false` for a node absent from live, and the happy path has a
    // test for exactly that answer. Creating it before the publish put it in
    // live with the rest of the site and that test asserted false against a
    // page that was published.
    addNode({
        parentPathOrId: fixture.homePath,
        primaryNodeType: 'jnt:page',
        name: 'geo-child',
        properties: [
            // Mandatory and actually enforced on jnt:page, unlike jcr:title.
            {name: 'j:templateName', type: 'STRING', value: 'simple'},
            {name: 'jcr:title', type: 'STRING', value: 'GEO child page', language: 'en'}
        ]
    });

    createUser(fixture.publisher.username, fixture.publisher.password);
    createUser(fixture.contributor.username, fixture.contributor.password);
    grantRoles(fixture.sitePath, PUBLISHER_ROLES, fixture.publisher.username, 'USER');
    grantRoles(fixture.sitePath, CONTRIBUTOR_ROLES, fixture.contributor.username, 'USER');
}

/**
 * Remove everything {@link setupGeoFixture} creates. Safe to call when none of
 * it exists.
 * @param {GeoFixture} fixture the fixture to remove.
 */
export function teardownGeoFixture(fixture: GeoFixture): void {
    asRoot();
    deleteUser(fixture.publisher.username);
    deleteUser(fixture.contributor.username);
    deleteSite(fixture.siteKey);
}

/**
 * Assert, as the given user, what that user may do on the site.
 *
 * This is the premise the authorization specs rest on, and it is checked rather
 * than assumed for one reason: if a role grant silently failed, every denial
 * assertion in this suite would still pass — the user would be refused because
 * they have no rights at all, not because `publish` is the gate. The suite would
 * be green and would be measuring nothing. Asserting `jcr:modifyProperties` is
 * true *and* `publish` is false is what tells those two states apart.
 *
 * @param {GeoTestUser} user the user to check, who must already be the Apollo identity.
 * @param {string} sitePath the site to check on.
 * @param {string} permission the permission name.
 * @param {boolean} expected whether the user is expected to hold it.
 */
export function assertSitePermission(
    user: GeoTestUser,
    sitePath: string,
    permission: string,
    expected: boolean
): void {
    cy.apollo({
        queryFile: 'graphql/hasPermission.graphql',
        variables: {path: sitePath, permission}
    }).then((result: any) => {
        expect(result.errors, `${user.username} must be able to read ${sitePath} for this premise to mean anything`)
            .to.be.undefined;
        const node = result.data?.jcr?.nodeByPath;
        // to.not.be.null passes for undefined, which is what nodeByPath is when
        // the user cannot read the node at all - so the next line threw a
        // TypeError instead of saying so. to.exist covers both.
        expect(node, `${user.username} must resolve ${sitePath}`).to.exist;
        expect(
            node.hasPermission,
            `${user.username} ${expected ? 'must hold' : 'must NOT hold'} "${permission}" on ${sitePath}`
        ).to.eq(expected);
    });
}
