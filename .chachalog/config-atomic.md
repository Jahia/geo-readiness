---
geo-readiness: patch
---

The runtime configuration is swapped through an AtomicReference, so the operation that replaces it reads as the atomic one it always had to be.
