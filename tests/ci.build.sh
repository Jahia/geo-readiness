#!/bin/bash
# Builds the tests container and stages the module under test beside it.
#
# Re-run this after ANY change under tests/, or the change never reaches the
# container and you debug a stale image.
source ./set-env.sh

mkdir -p ./artifacts
if [[ -e ../target ]]; then
  cp -R ../target/*-SNAPSHOT.jar ./artifacts/
fi

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version ci.build
