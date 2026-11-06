---
"client-sdk-android": patch
---

Stop the QUIC signaling transports' callback threads once the connection has closed, so a finished call's Room no longer stays in memory
