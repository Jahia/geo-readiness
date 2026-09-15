---
geo-readiness: patch
---

The public base url now takes its host from the repository rather than from the request that matched it. The two were equal under a case-insensitive comparison but not identical, so a `Host:` header could choose the casing of a base url that the scan servlet then persisted in `geoBaseUrl` and reused on every later scheduled scan.
