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
const SEO_LABEL: string = Cypress.env('JCONTENT_SEO_LABEL') || 'SEO';

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
    // The page list carrying rows is the signal jContent finished booting;
    // asserting on the chrome alone would let the next step race the data.
    cy.contains('[data-sel-role="table-content-list"], table, main', 'home', {timeout: 60000})
        .should('be.visible');
}

/**
 * Walk jContent's left panel to the module's dashboard.
 * @param {string} siteKey the site whose settings to open.
 * @param {string} language the content language.
 */
export function openGeoDashboard(siteKey: string, language = 'en'): void {
    openJContentPages(siteKey, language);
    cy.contains('button, [role="tab"], li, div', ADDITIONAL_LABEL, {timeout: 30000}).click();
    cy.contains('button, li, div, a', SEO_LABEL, {timeout: 30000}).click();
    cy.contains('button, li, div, a', GEO_NAV_LABEL, {timeout: 30000}).click();
}
