# Connection implementation notes

Quicker Link follows Quicker's official [WebSocket service documentation](https://getquicker.net/KC/Manual/Doc/websocketservice). This document records the product's security and compatibility boundaries; it intentionally does not serve as a wire-level reproduction guide.

## Transport boundary

- Connections use certificate-validated `WSS` only. The app does not expose or automatically fall back to cleartext `WS`.
- Connection targets are restricted to private IPv4 addresses suitable for a trusted, mutually reachable LAN.
- Authentication credentials are sent only after a candidate has completed the validated secure handshake.
- Non-idempotent side-effecting commands are never replayed automatically after a reconnect.

## Discovery and pairing

Quicker does not currently expose a documented LAN discovery service. The app therefore uses a bounded local search with fixed candidate, concurrency, and timeout limits. Discovery probes do not contain the connection verification code, and automatic authentication proceeds only when the completed search has one unambiguous candidate.

The dedicated Quicker Link pairing code is generated locally by the companion action and is validated on the phone before use. It contains the information needed to establish the local connection, including the Quicker verification code, so it must be treated as a credential and never shared or retained as a screenshot.

Quicker's cloud-push QR code belongs to a separate service and cannot be used for local Quicker Link pairing.

## Action catalog

The companion action provides a narrowly scoped catalog of the global and common new panels. The catalog contains only the action identity, source panel, and display metadata needed by the phone; it excludes action source code, internal parameters, application-specific panels, and the companion action itself.

The Android client applies strict size, identity, grouping, ordering, and error-envelope validation before replacing synchronized entries. Existing manual entries and phone-side confirmation or parameter settings remain local. The app and companion action must use the same current catalog generation; older catalog responses are rejected, and no synchronized entry is changed unless the complete response passes validation.

Action identity, scene, grouping, and order take priority over optional display metadata. If a large catalog reaches the bounded response budget, the companion may omit trailing inline icons or shortcut parameter choices so the action mapping can still synchronize.

The current catalog explicitly declares whether synchronized actions can be stopped. A stop request is limited to a validated synchronized action identity, is sent only after an explicit menu command, and is reported as successful only after Quicker confirms that no running instance remains. Control responses are compact and never include action source or catalog data.

This catalog filter limits what Quicker Link displays. It does not change the permission boundary of Quicker's authenticated WebSocket service, so the connection should remain limited to a trusted LAN and protected by a verification code.

## Compatibility policy

- Incoming responses are correlated to the request that created them and are bounded by local timeouts.
- Unknown or unsupported input is not executed implicitly.
- One-shot screen capture and click, clipboard text, fixed computer controls, and single-file transfer are available only through the current companion-action generation and an authenticated foreground session.
- Files are limited to 64 MiB and transferred as ordered 64 KiB chunks with per-chunk and whole-file SHA-256 validation. Both sides stage incomplete data in `.part` files and publish it only after final validation. The limit bounds action-channel round trips and staging rather than reflecting a WebSocket frame limit.
- The Android client runs one toolbox task at a time, exposes cancellation, and never resumes or replays a side-effecting chunk automatically after reconnect.
- Final upload commit is idempotent for one transfer identity. Cancellation is disabled during commit; an ambiguous response can be queried again without publishing a second file.
- Phone-to-computer files are confined to a directory selected and persisted on the desktop; its default is derived from the Windows Downloads known folder. The phone cannot submit a desktop path. Computer-to-phone selection requires an explicit desktop file-picker choice, and the phone is not given a directory-listing operation.
- A screen click is bound to one recent snapshot and consumed after one left click. The companion does not expose dragging, keyboard input, continuous capture, or a general remote-control channel.
- Computer controls are a fixed allowlist of sleep, shutdown, and Quicker restart. The Android client requires an explicit confirmation and never forwards an arbitrary process or command line.
- Disconnects fail pending work, then reconnect through a fresh secure authentication flow when the user has chosen to save the connection.

The implementation may evolve as Quicker changes. Security-sensitive behavior is covered by focused tests and should be reviewed from the exact tagged source corresponding to a published APK.
