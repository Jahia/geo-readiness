// Writes one marker line into jahia.log, so a run can be located in the server
// log afterwards. @jahia/cypress substitutes MESSAGE.
//
// This is a copy of the identical fixture inside @jahia/cypress, and it is here
// on purpose. registerSupport() enables the spec markers unconditionally, so
// every before/beforeEach/afterEach calls executeGroovy with this path. The
// package's own `fixture` override looks in THIS folder first and only falls
// back to cy.readFile('./node_modules/@jahia/cypress/fixtures/...') when it
// finds nothing - and that fallback intermittently times out under load, which
// took down the heaviest UI spec in an afterEach hook. Resolving on the first
// lookup makes the markers deterministic.
import org.slf4j.Logger
import org.slf4j.LoggerFactory

final Logger logger = LoggerFactory.getLogger(this.class);

logger.info("MESSAGE")
