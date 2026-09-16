---
geo-readiness: patch
---

Audited every factual claim in the documentation against the code and corrected what was wrong, including two instructions that would have broken a contributor's setup: a `yarn e2e` command that does not exist, and a CI `instance_type` the live workflow deliberately omits because it fails. Two of the corrections were code rather than prose — the test harness dropped the `notests` flag it documented, and a comment claimed the module refuses to fetch a site with no server name when it in fact falls back to `localhost:8080`.
