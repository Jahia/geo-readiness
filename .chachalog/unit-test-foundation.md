---
geo-readiness: patch
---

The module has unit tests for the first time: JUnit 5, AssertJ and JaCoCo, with 189 characterisation tests pinning the scoring engine, the robots.txt matcher, the llms.txt freshness comparison and the two security guards. They exist so the decomposition that follows cannot change a score silently — the existing Cypress suite asserts that a scan happened and that the UI reads "n of m checks passed", never which n or which m.
