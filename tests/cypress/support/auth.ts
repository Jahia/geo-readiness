/**
 * Who the next call acts as.
 *
 * There are two identities in a Jahia Cypress run and they are independent:
 *
 *  - the **browser session**, which `cy.login()` sets and which `cy.request()`
 *    (so every call in `geo.ts`) carries;
 *  - the **Apollo client**, which stays the super user until
 *    `cy.apolloClient({username, password})` says otherwise, no matter who is
 *    logged in.
 *
 * Forgetting the second one raises no error — the GraphQL call simply succeeds
 * as root. In this suite that would be fatal in the quietest possible way: the
 * permission premise ("the contributor does NOT hold publish") would be asked of
 * root, answered `true`, and the authorization specs would be measuring nothing.
 * So both are always set together, and never separately.
 */

export type GeoTestUser = {
    username: string;
    password: string;
};

/**
 * Act as `user` for both the browser session and GraphQL.
 * @param {GeoTestUser} user the user to act as.
 */
export function asUser(user: GeoTestUser): void {
    cy.login(user.username, user.password);
    cy.apolloClient({username: user.username, password: user.password});
}

/**
 * Act as the super user for both the browser session and GraphQL.
 *
 * Fixture setup and teardown run as root; specs call this between identity
 * switches so a leftover session cannot make a fixture step fail for the wrong
 * reason.
 */
export function asRoot(): void {
    cy.login('root', Cypress.env('SUPER_USER_PASSWORD'));
    cy.apolloClient({username: 'root', password: Cypress.env('SUPER_USER_PASSWORD')});
}

/**
 * Act as an anonymous visitor.
 *
 * Cookies are cleared rather than a logout being posted: a logout only ends a
 * session that exists, while the guest cases have to hold whether or not a
 * previous test left one behind. The Apollo client is deliberately untouched —
 * nothing anonymous is asserted through GraphQL.
 */
export function asGuest(): void {
    cy.clearCookies();
    cy.getCookies().should('be.empty');
}
