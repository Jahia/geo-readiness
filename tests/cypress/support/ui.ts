/**
 * Reaching the module's two entry points inside jContent.
 *
 * Both are registered with `@jahia/ui-extender`, so neither has a URL of its
 * own that this suite should hard-code: the drawer is a header action on the
 * selected content, and the dashboard is an admin route contributed to
 * jContent's own SEO section. They are therefore reached the way an editor
 * reaches them, through the chrome.
 *
 * Every query here is `cy.contains(selector, text)` rather than
 * `cy.get(selector).contains(text)`. jContent paints its chrome first and fills
 * it from an async call, and `cy.get()` resolves against whatever matched on the
 * first pass — usually the chrome — after which `.contains()` searches only
 * inside that frozen set and the retry budget is spent on elements that will
 * never hold the text.
 *
 * The two label constants are overridable because they come from jContent's own
 * resource bundle rather than from this module, and they have been renamed
 * between versions. The module's own label is not overridable: asserting it is
 * part of the point.
 */

/** jContent's accordion holding the site-level settings. */
const ADDITIONAL_LABEL: string = Cypress.env('JCONTENT_ADDITIONAL_LABEL') || 'Additional';

/** jContent's SEO group inside that accordion. */
export const SEO_LABEL: string = Cypress.env('JCONTENT_SEO_LABEL') || 'SEO';

/** The module's own nav label, from `geo-readiness:dashboard.navLabel`. */
export const GEO_NAV_LABEL = 'GEO readiness';

/**
 * The drawer's action button. jContent renders a registered action with a
 * `data-sel-role` taken from the action key, which is `geoReadiness` here.
 */
export const GEO_ACTION_SELECTOR: string =
    Cypress.env('GEO_ACTION_SELECTOR') || '[data-sel-role="geoReadiness"]';

/**
 * Open jContent's page list for a site.
 * @param {string} siteKey the site to open.
 * @param {string} language the content language.
 */
export function openJContentPages(siteKey: string, language = 'en'): void {
    cy.visit(`/jahia/jcontent/${siteKey}/${language}/pages`);
    // The page tree carrying the site's home page is the signal jContent
    // finished booting; asserting on the chrome alone would let the next step
    // race the data.
    //
    // Two things were wrong with looking for 'home' in a content-list table.
    // /pages redirects to /pages/home and opens Page Builder, which renders the
    // page itself and no table at all — so the selector could never match. And
    // the tree labels the node "Home", while cy.contains is case-sensitive.
    // Both were guesses made without an instance, and both cost the three UI
    // tests sixty seconds each before failing.
    cy.contains(/^Home$/i, {timeout: 60000}).should('be.visible');
}

/**
 * Walk jContent's left panel to the module's dashboard.
 * @param {string} siteKey the site whose settings to open.
 * @param {string} language the content language.
 */
export function openGeoDashboard(siteKey: string, language = 'en'): void {
    // jContent's admin routes have URLs after all: the registry key this module
    // registers, `siteSettingsSeo/geoReadiness`, is served at
    // /jahia/jcontent/<site>/<lang>/apps/siteSettingsSeo/geoReadiness.
    //
    // Walking the chrome by label was tried and does not survive contact with
    // jContent: the accordion labelled "Additional" is keyed `apps`, and the SEO
    // entry's clickable target is nested inside its <li>, so clicking the
    // element that *contains* the text does nothing. Both were guesses.
    //
    // Discoverability is still asserted, just afterwards and against the nav
    // that the route itself expands, which is the part that matters: the entry
    // has to be in SEO next to Robots.txt and Llms.txt, not merely routable.
    cy.visit(`/jahia/jcontent/${siteKey}/${language}/apps/siteSettingsSeo/geoReadiness`);
    cy.contains(SEO_LABEL, {timeout: 30000}).should('be.visible');
    cy.contains(GEO_NAV_LABEL, {timeout: 30000}).should('be.visible');
}

/**
 * Open jContent's "Additional" accordion, which is keyed `apps` rather than by
 * its label. Used by the test that asserts a contributor is NOT offered the
 * dashboard, which has to reach the section to prove the entry is absent.
 * @param {string} siteKey the site to open.
 * @param {string} language the content language.
 */
export function openJContentApps(siteKey: string, language = 'en'): void {
    cy.visit(`/jahia/jcontent/${siteKey}/${language}/pages`);
    cy.contains(/^Home$/i, {timeout: 60000}).should('be.visible');

    // Keyed, not labelled. The label is asserted rather than used for the click,
    // so a rename in jContent's bundle is reported as a rename instead of as a
    // missing dashboard - which is what ADDITIONAL_LABEL is for.
    const accordion = 'section.moonstone-accordionItem header[aria-controls="apps"]';
    cy.get(accordion, {timeout: 30000}).should('contain.text', ADDITIONAL_LABEL);
    cy.get(accordion).click();
}

/**
 * Select a Moonstone tab by its label, tolerating the case where it is already
 * the active one.
 *
 * Moonstone sets `pointer-events: none` on the selected tab, so clicking the tab
 * you are already on fails with "element cannot be interacted with" rather than
 * doing nothing. A test that wants to *be* on a tab should not care whether it
 * arrived there by default or by clicking.
 *
 * @param {string} label the tab's visible label.
 * @param {number} timeout how long to wait for the tab to appear.
 */
export function selectTab(label: string, timeout = 30000): void {
    cy.contains('button[role="tab"], button.moonstone-tabItem', label, {timeout}).then($tab => {
        if ($tab.attr('aria-selected') !== 'true') {
            cy.wrap($tab).click();
        }
    });
}
