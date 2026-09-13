import {defineConfig} from 'cypress';
import {setupNodeEvents} from './cypress/plugins';

export default defineConfig({
    video: false,
    screenshotOnRunFailure: true,
    viewportWidth: 1440,
    viewportHeight: 900,

    // A crawler check fetches the page once per AI user agent, three at a time,
    // with an 8s per-fetch timeout. The worst case is comfortably over a minute,
    // so the response budget is set from that rather than from Cypress's default.
    defaultCommandTimeout: 10000,
    requestTimeout: 30000,
    responseTimeout: 180000,

    retries: {
        runMode: 1,
        openMode: 0
    },

    // Two reporters: mochawesome for the human report env.run.sh merges, and
    // JUnit XML because that is what the Jahia integration-tests action collects
    // (tests_report_path defaults to artifacts/results/xml_reports).
    reporter: 'cypress-multi-reporters',
    reporterOptions: {
        configFile: 'reporter-config.json'
    },

    e2e: {
        supportFile: 'cypress/support/e2e.ts',
        specPattern: 'cypress/e2e/**/*.spec.ts',
        setupNodeEvents
    }
});
