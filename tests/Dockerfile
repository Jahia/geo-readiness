# The tests container the CI action builds and runs as the `cypress` service.
#
# Mirrors @jahia/cypress's own env.Dockerfile — the browsers image, a non-root
# user, the suite copied in and installed, and env.run.sh as the entry point.
# env.run.sh is the local wrapper, as in the sibling harnesses: it resolves the
# pinned @jahia/cypress from package.json and delegates to its CLI, which checks
# the module is actually installed before running a single spec, merges the
# reports afterwards and sets the exit code.
#
# Build locally with:
#   docker build -t geo-readiness-tests:latest .

FROM cypress/browsers:node-22.21.0-chrome-141.0.7390.107-1-ff-144.0-edge-141.0.3537.92-1

ARG YARN_VERSION="1.22.22"

RUN apt-get update && apt-get install -y jq curl && rm -rf /var/lib/apt/lists/*

RUN adduser --disabled-password jahians
USER jahians
WORKDIR /home/jahians

COPY --chown=jahians:jahians package.json yarn.lock /home/jahians/

RUN mkdir -p /home/jahians/run-artifacts /home/jahians/results/reports

# CI=true keeps the install logs readable in a CI transcript.
RUN CI=true yarn set version ${YARN_VERSION}

COPY --chown=jahians:jahians . /home/jahians

RUN CI=true yarn install && /home/jahians/node_modules/.bin/cypress install

CMD ["/bin/bash", "-c", "/home/jahians/env.run.sh"]
