#!/bin/bash
# Provisions the running instance from ${MANIFEST}, checks ${MODULE_ID} is
# actually installed, runs the suite once headless, then merges the reports and
# sets the exit code from the result.
version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version env.run "$@"
