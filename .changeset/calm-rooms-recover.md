---
"client-sdk-android": minor
---

Add `ROOM_RECOVERING` to `MediaSendConnectionState` so apps can distinguish whole-room recovery from publisher-only media send failures. Normal `CONNECTING` negotiation is no longer classified as an abnormal publisher state. Exhaustive `when` expressions must handle the new enum value.
