---
geo-readiness: patch
---

Closed an SSRF where the request's Host header chose the port this module connected to, gated the one endpoint that had no permission check, stopped a browsing node deleting every site's scan schedule from the cluster, and fixed six places where the code did the opposite of its own comment - including a config marker typo that silently reset the administrator's settings on every redeploy.
