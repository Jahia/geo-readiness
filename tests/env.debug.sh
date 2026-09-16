#!/bin/bash
# As env.run.sh, but leaves the interactive Cypress runner open.
version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version env.debug "$@"
