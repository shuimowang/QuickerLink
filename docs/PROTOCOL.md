# Connection implementation notes

Quicker Link follows Quicker's official [WebSocket service documentation](https://getquicker.net/KC/Manual/Doc/websocketservice). This document records the product's security and compatibility boundaries; it intentionally does not serve as a wire-level reproduction guide.

## Transport boundary

- Connections use certificate-validated `WSS` only. The app does not expose or automatically fall back to cleartext `WS`.
- A non-empty Quicker WebSocket verification code is strongly recommended, but Quicker's passwordless WSS mode remains supported for compatibility. Passwordless mode changes only application authentication; TLS certificate and hostname validation remain mandatory.
- Connection targets are restricted to private IPv4 addresses suitable for a trusted, mutually reachable LAN.
- The validated `*.lan.quicker.cc` hostname is resolved directly to the encoded private IPv4 address, and both connection and discovery clients bypass HTTP proxies.
- Android's system trust store remains the default trust source. The `lan.quicker.cc` domain additionally trusts the bundled public ISRG Root X2 so a device missing that root can validate the public chain. Hostname verification remains enabled, user-added CAs are not added, and the app does not pin or trust an arbitrary leaf certificate.
- Authentication credentials are sent only after a candidate has completed the validated secure handshake.
- Non-idempotent side-effecting commands are never replayed automatically after a reconnect.

## Authentication mode

The companion action reads the Quicker WebSocket settings that already exist; it cannot generate, change, or apply a verification code. After changing the Quicker setting, the user must save it and generate a new pairing code. With an empty app code, the client accepts only Quicker's documented passwordless authentication reply. With a non-empty app code, the code must match and the response must correlate to that authentication request. Discovery performs only a validated WSS handshake and does not authenticate.

Authentication replies are bound to the current socket generation and request. If a client configured with a non-empty code receives Quicker's preliminary passwordless success form, the client ignores that form and continues waiting for the response correlated to its code-authentication request. It never treats the passwordless form as authenticated or silently downgrades to the weaker mode.

## Discovery and pairing

Quicker does not currently expose a documented LAN discovery service. The app therefore uses a bounded local search with fixed candidate, concurrency, and timeout limits. Discovery probes do not contain the connection verification code, and automatic authentication proceeds only when the completed search has one unambiguous candidate.

The dedicated Quicker Link pairing code is generated locally by the companion action and is validated on the phone before use. It contains the information needed to establish the local connection and includes the Quicker verification code when one is configured, so it must be treated as connection data and never shared or retained as a screenshot.

Quicker's cloud-push QR code belongs to a separate service and cannot be used for local Quicker Link pairing.

## Action catalog

The companion action provides a narrowly scoped catalog of the global and common new panels. The catalog contains only the action identity, source panel, and display metadata needed by the phone; it excludes action source code, internal parameters, application-specific panels, and the companion action itself.

The Android client applies strict size, identity, grouping, ordering, and error-envelope validation before replacing synchronized entries. Existing manual entries and phone-side confirmation or parameter settings remain local. The app and companion action must use the same current catalog generation; older catalog responses are rejected, and no synchronized entry is changed unless the complete response passes validation.

Action identity, scene, grouping, and order take priority over optional display metadata. If a large catalog reaches the bounded response budget, the companion may omit trailing inline icons or shortcut parameter choices so the action mapping can still synchronize.

The current catalog explicitly declares action-control support. A synchronized action is stopped by its validated ID. A manually added shortcut may request a stop by action GUID or name; ambiguous names are rejected, and the companion action cannot stop itself. Requests are sent only after an explicit menu command and are reported as successful only after Quicker confirms that no running instance remains. Control responses are compact and never include action source or catalog data.

This catalog filter limits what Quicker Link displays. It does not change the permission boundary of Quicker's authenticated WebSocket service, so the connection should remain limited to a trusted LAN and protected by a verification code.

## Desktop-to-phone delivery

The v8 companion action exposes a user-facing local-automation entry point for bounded text, notification, and file delivery. Only a computer-local Quicker trigger may use it; an external trigger is rejected so a connected phone cannot submit a desktop file path or impersonate local automation.

Text is copied to the phone clipboard and transfer editor. Notifications use the Android notification permission. A file is staged on the desktop, announced by validated metadata, and downloaded only after the phone user accepts the offer. The existing 64 MiB transfer state machine, checksum validation, temporary files, and single-task rule still apply. Delivery is best effort and requires a current authenticated v8 client.

## Clipboard and lifecycle

Automatic clipboard synchronization is a separate, default-off enhancement. It is eligible only while the app is foregrounded, the WSS session is ready, and both v8 clipboard capabilities have been confirmed. Phone changes are debounced, the computer is polled serially, Android-sensitive clips are ignored, and an in-memory SHA-256 fingerprint guard prevents echo loops without retaining clipboard text. Moving the app to the background stops both the listener and polling regardless of the background-connection setting.

Background receive is enabled by default and remains user-disableable. An Android `connectedDevice` foreground service owns the service notification and observes the application-level connection manager, allowing normal lock-screen, app-switch, and Activity-recreation transitions to retain WSS and reconnect. On Android 13+, notification permission controls whether desktop notifications and receipt prompts are visible; denial does not disable WSS retention. Incoming text is consumed by the application runtime, while the latest file offer is retained for later UI acceptance. The service has a Stop action, no boot receiver, and `START_NOT_STICKY` behavior; stopping the service, force-stopping the app, or losing the process interrupts immediate delivery, and accepting a file offer still requires the UI.

## Compatibility policy

- Incoming responses are correlated to the request that created them and are bounded by local timeouts.
- Unknown or unsupported input is not executed implicitly.
- Catalog, action-control, toolbox, clipboard-write, and desktop-push capabilities currently require strict generation v8 on both sides. Older or malformed generations are rejected before state is changed or a side effect is executed.
- Screen capture and click, manual clipboard operations, fixed computer controls, and phone-initiated single-file transfer require the authenticated foreground UI. Accepted desktop pushes may arrive during ordinary background retention, subject to the lifecycle limits above.
- Files are limited to 64 MiB and transferred as ordered 64 KiB chunks with per-chunk and whole-file SHA-256 validation. Both sides stage incomplete data in `.part` files and publish it only after final validation. At the limit, 1,024 chunks plus Base64 expansion and action round trips must be handled; the limit bounds time, temporary storage, memory pressure, and abuse rather than reflecting a WebSocket frame limit.
- The Android client runs one toolbox task at a time, exposes cancellation, and never resumes or replays a side-effecting chunk automatically after reconnect.
- Final upload commit is idempotent for one transfer identity. Cancellation is disabled during commit; an ambiguous response can be queried again without publishing a second file.
- Phone-to-computer files are confined to a directory selected and persisted on the desktop; its default is derived from the Windows Downloads known folder. A phone-initiated computer-to-phone transfer requires an explicit desktop file-picker choice. A computer-local automation may instead stage an explicit local path as a file offer, but external/phone triggers cannot submit that path and the phone user must still accept the offer. The phone is never given a directory-listing operation.
- A screen click is bound to one recent snapshot and consumed after one left click. While explicit click mode remains visible, the Android client requests serialized replacement snapshots at roughly 1.2-second intervals; this is not a video stream. The companion does not expose dragging, arbitrary keyboard input, or a general remote-control channel.
- Computer controls are a fixed allowlist of sleep, shutdown, and Quicker restart. The Android client requires an explicit confirmation and never forwards an arbitrary process or command line.
- Disconnects fail pending work, then reconnect through a fresh secure authentication flow when the user has chosen to save the connection.
- SMS monitoring, OTP forwarding, system-wide proxying, and VPN traffic forwarding are outside the protocol. The Android app requests no SMS permission and does not implement `VpnService`.

The implementation may evolve as Quicker changes. Security-sensitive behavior is covered by focused tests and should be reviewed from the exact tagged source corresponding to a published APK.
