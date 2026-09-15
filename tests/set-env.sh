#!/bin/bash
# Exports every variable in .env (or .env.example when there is no .env) into the
# shell, which is where Cypress and docker compose read them from. Source it, do
# not execute it: a subshell's exports die with the subshell.
#
#   source set-env.sh
#
# The ci.* and env.* wrappers source it themselves, so it is only needed by hand
# before `yarn e2e:ci` or `yarn e2e:debug`, and again in every new terminal.
#
# Only lines that look like KEY=... are exported. The sibling harnesses get away
# with exporting every line because their .env.example carries no comments; ours
# documents itself, and a comment reaching `export` would start a shell comment
# and silently swallow every name after it.

__envfile=.env
[[ -f "${__envfile}" ]] || __envfile=.env.example

# shellcheck source=/dev/null
source "${__envfile}"
export $(grep -E '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=' "${__envfile}" | sed -E 's/^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=.*/\1/' | xargs)
unset __envfile
