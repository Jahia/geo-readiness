---
geo-readiness: patch
---

Fixed the `$` end-anchor in robots.txt matching, which was wrong in two opposite directions: a pattern with no wildcard checked its start and its end independently, so `/foo$` matched `/foo/bar/foo`, and a pattern ending in `*$` rejected every path longer than its literal prefix, so `abc*$` did not match `abcdef`. Sites whose robots.txt uses `$` anchors will see corrected verdicts in the drawer's policy cross-check and may see their score move.
