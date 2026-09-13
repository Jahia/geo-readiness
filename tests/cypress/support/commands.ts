import {registerSupport} from '@jahia/cypress/dist/support/registerSupport';

/**
 * Command registration only.
 *
 * Implementation lives in the small modules next to this file (`geo.ts`,
 * `auth.ts`, `fixtures.ts`, `ui.ts`). Keeping registration separate is what stops
 * this file from becoming the place every helper ends up.
 */
registerSupport();
