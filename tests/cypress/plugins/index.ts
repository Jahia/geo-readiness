import env from '@jahia/cypress/dist/plugins/env';
import {registerGlobalVarsTasks} from '@jahia/cypress/dist/plugins/globalVars';

/**
 * Node-side setup for the suite.
 *
 * Cypress 13 has no `plugins/index.js` auto-loading any more, so this is called
 * explicitly from `cypress.config.ts`. It stays in its own file because the
 * Jahia convention puts node-side wiring here, and because a `setupNodeEvents`
 * that grows inline in the config is the usual way suites end up with untestable
 * setup.
 *
 * @param {Cypress.PluginEvents} on Cypress plugin event registrar.
 * @param {Cypress.PluginConfigOptions} config the resolved Cypress configuration.
 * @returns {Cypress.PluginConfigOptions} the configuration with the Jahia environment applied.
 */
export function setupNodeEvents(
    on: Cypress.PluginEvents,
    config: Cypress.PluginConfigOptions
): Cypress.PluginConfigOptions {
    // Cross-spec key/value store, used by the shared-fixture guard in support/e2e.ts.
    registerGlobalVarsTasks(on);

    // Reads JAHIA_URL / SUPER_USER_PASSWORD from the process environment and
    // sets baseUrl + Cypress.env() from them.
    return env(on, config);
}
