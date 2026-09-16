#!/bin/bash
# Boots the stack and runs the suite. Pass `notests` to boot Jahia only, which is
# the starting point for the local-node loop:
#
#   ./ci.startup.sh notests && ./env.run.sh
#
# The `"$@"` on the last line is what makes that true. Without it the flag was
# read off this script's own arguments and then never handed on, so the package's
# ci.startup saw none - and `notests` booted the stack AND ran every spec, which
# is the opposite of what the line above promises.
source ./set-env.sh

echo " == Printing the most important environment variables"
echo " MANIFEST: ${MANIFEST}"
echo " TESTS_IMAGE: ${TESTS_IMAGE}"
echo " JAHIA_IMAGE: ${JAHIA_IMAGE}"
echo " MODULE_ID: ${MODULE_ID}"
echo " JAHIA_URL: ${JAHIA_URL}"
echo " SUPER_USER_PASSWORD: ${SUPER_USER_PASSWORD}"

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version ci.startup "$@"
