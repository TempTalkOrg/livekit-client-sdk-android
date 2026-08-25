---
"client-sdk-android": patch
---

Preserve original signaling transport errors for tokenless TT calls and certificate failures by skipping authenticated validation when no bearer token exists or TLS trust has already failed.
