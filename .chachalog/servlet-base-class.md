---
geo-readiness: patch
---

Gave the four servlets one base class instead of four copies of the same seven helpers, which fixed two defects that were living in the drift between them: three of the module's four JSON endpoints were served without `X-Content-Type-Options: nosniff` while echoing content read from the site being checked, and three of them silently truncated an oversized request body so it came back as "malformed body" instead of being refused for what it was.
