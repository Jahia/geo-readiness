---
geo-readiness: patch
---

Fixed seven defects the new characterisation tests had pinned. The largest: a page whose JSON-LD is syntactically broken now correctly reports no structured data, where it used to pass the check because the types were read out of the text with a regex rather than parsed. The robots.txt editor also now keeps the promise it makes in its own documentation — a merge that changes nothing returns a customer's file exactly as it arrived, rather than dropping blank lines, adding a trailing newline or mixing line endings into it.
