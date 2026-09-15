// Registers cy.waitUntil. @jahia/cypress calls it from
// dist/utils/PublicationAndWorkflowHelper.js, and its own
// dist/support/commands.js requires the plugin — but registerSupport() never
// requires that file, so nothing here loads it. Without this import every spec
// that publishes content dies in its before-all hook with
// "TypeError: cy.waitUntil is not a function". The sibling harnesses import it
// in exactly this place for the same reason.
import 'cypress-wait-until';
import {registerSupport} from '@jahia/cypress/dist/support/registerSupport';

/**
 * Command registration only.
 *
 * Implementation lives in the small modules next to this file (`geo.ts`,
 * `auth.ts`, `fixtures.ts`, `ui.ts`). Keeping registration separate is what stops
 * this file from becoming the place every helper ends up.
 */
registerSupport();
