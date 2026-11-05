# Validation — 5 November 2026

## Completed

- `assembleDebug`: successful. Development-signed universal APK generated for Android 8.0+.
- `testDebugUnitTest`: 7 tests passed; 0 failures and 0 skipped.
  - Bilateral ECDH/HKDF agreement and encrypted message round trip.
  - Distinct nonces and rejection of incorrect device keys, dates, authenticated metadata, truncated data and ciphertext tampering.
  - Two-client protocol exchange: text, sticker, voice payload and signaling round trips; duplicate-message rejection and disconnect state.
  - Invalid handshake and malformed packet rejection without terminating the message collector.
  - Captured ciphertext rejection after a process restart, with the same daily keys and an empty replay cache.
  - UTC midnight history expiration, fresh device keys and rejection of yesterday's packets.
- `lintDebug`: passed (one nonblocking obsolete Android version check warning in starter service).
- `npm test` in `relay/`: 2 integration tests passed. Checks ciphertext forwarding, room separation, two-person limit, disconnect events and invalid frame rejection.
- `apksigner verify --verbose`: signature verified. This APK uses a debug signing key; it is not a production release.

## Still required

- Android launch/UI smoke testing remains unverified. API 35 SDK image lacked a boot kernel; the API 34 software emulator had no KVM acceleration and remained in system first-boot optimization. Installation attempts failed inside Android StorageManager before the app could be installed (PackageManagerInternal not initialized). No app startup success is claimed. Retry on a fully booted emulator or physical phone.
- Two physical Google Play Services Android phones: Nearby discovery, matching/rejecting pairing digits, fragmented voice transfer, permissions and reconnection.
- Real microphone playback and camera/audio behavior, incoming call accept/decline, mute, camera toggle, hangup and foreground-only lifecycle.
- A hosted secure WebSocket relay, plus TURN configuration and calls across independent mobile networks / restrictive NAT.
- Independent cryptographic review, production signing and distribution, and background/push calling before a public production release.

The protocol tests use real cryptographic operations and an in-memory transport; they do not prove radio, hardware, network or WebRTC media behavior. Relay tests run actual local WebSocket connections. No publicly deployed internet service or successful two-phone call is claimed.
