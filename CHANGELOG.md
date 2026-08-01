# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

## [0.5.0-alpha.3] - 2026-08-01

### Changed

- Remove the Android system speech-recognition entry points from the transfer editor and post-action quick input
- Rename the foreground-service option to “后台接收与连接”, enable it by default unless the user explicitly opts out, and retain the authenticated session for desktop text, notification, and file-offer delivery
- Keep background WSS retention active when Android notification permission is denied; only notification visibility is affected
- Discover updates from a bounded GitHub Pages manifest first and use the GitHub Releases REST API only as a one-request fallback; checks, downloads, and installs remain user-initiated

### Fixed

- Strip one case-insensitive `sendText:` compatibility prefix before placing desktop text in the phone clipboard or transfer editor
- Retain background-delivered desktop text in the transfer editor even when an OEM blocks writing the phone clipboard

### Security

- Keep automatic clipboard synchronization app-visible only on Android 10+, regardless of the background connection setting
- Continue to omit SMS read/receive and `VpnService` capabilities; SMS monitoring and phone system proxying remain unimplemented
- Reject redirected, oversized, malformed, wrong-repository, or noncanonical update manifests before trusting their exact GitHub release asset metadata

## [0.5.0-alpha.2] - 2026-08-01

### Fixed

- Discard stale computer clipboard reads when the phone clipboard changes before the response arrives
- Bind screen capture, screen clicks, downloads, cleanup, cancellation, and incoming file offers to their originating computer and companion action
- Keep WebSocket message serials inside Quicker's positive 32-bit range and reject excess computer-to-phone commands with an explicit busy response
- Stop automatic retries after certificate-chain or hostname verification failures while retaining reconnects for ordinary network interruptions
- Handle a speech-recognition service disappearing between capability detection and launch without closing the App
- Keep the foreground-service notification active when disabling the persisted background setting fails

## [0.5.0-alpha.1] - 2026-08-01

### Added

- Let computer-local Quicker automations send bounded text, Android notifications, or file offers to currently authenticated phones
- Add a default-off foreground-only clipboard text sync with sensitive-clip filtering, bounded polling, and in-memory echo suppression
- Add a default-off Android `connectedDevice` foreground service that retains the WSS session during normal lock-screen and app-switch transitions, with a persistent notification and Stop action
- Add system speech-recognition entry points for the transfer editor and post-action quick input
- Let the action-sent Snackbar open quick input with an optional Enter key, enabled by default
- Allow manually added action shortcuts to request termination by action name or GUID
- Add an optional, default-off desktop text history managed from the companion pairing window, bounded to 100 entries and 1 MiB

### Changed

- Upgrade the strict catalog, action-control, toolbox, clipboard-write, and desktop-push capability contract to v8
- Recommend a Quicker verification code with an explicit risk warning while retaining compatibility with passwordless, certificate-validated WSS
- Keep explicit screen click mode active and refresh it through serialized snapshots at roughly 1.2-second intervals instead of returning to static view after each click
- Consolidate clipboard copy and current-window paste into the transfer text editor, including a clear control, and move panel refresh beside the action-list count
- Retain one application-level WSS connection owner so an enabled foreground service can outlive the Activity without creating a second socket
- Require Android notification permission before background retention and keep clipboard synchronization foreground-only even when background connection is enabled
- Document the 64 MiB limit as a bound on 1,024 action-channel chunks, Base64 expansion, temporary storage, and transfer time rather than a WebSocket limit

### Fixed

- Add the public ISRG Root X2 trust anchor only for `lan.quicker.cc` while retaining system trust and hostname validation, improving diagnostics for certificate-chain failures without adding a cleartext or trust-all fallback
- Ignore Quicker's preliminary passwordless success form when the App has supplied a verification code, then authenticate only from the matching code-request response
- Return concise computer-push and clipboard summaries without placing full text, action catalogs, or file metadata into connection logs

### Security

- Keep passwordless compatibility explicit: an empty client code accepts only the passwordless handshake, while a configured client never silently downgrades
- Keep WSS connection and discovery traffic direct and proxy-free; no `VpnService`, system proxy, SMS-read, or SMS-receive capability is added
- Restrict desktop-to-phone file paths to computer-local Quicker triggers and require explicit phone acceptance before download

## [0.4.0-alpha.1] - 2026-07-31

### Added

- Map one explicit tap from the current full-screen snapshot to a one-time desktop left click, then refresh the snapshot
- Let the desktop pairing window choose and persist the directory used for files sent from the phone
- Add confirmed sleep, shutdown, and Quicker restart controls backed by a fixed command allowlist
- Add a clear control to the transfer text editor

### Changed

- Raise the single-file transfer limit from 8 MiB to 64 MiB while retaining ordered 64 KiB chunks, temporary staging, cancellation, and SHA-256 verification
- Use the Windows Downloads known folder by default so redirected Downloads locations are respected
- Keep clipboard copy in the transfer workspace and reduce the connection-page text shortcut to paste only
- Upgrade the strict catalog, action-control, and toolbox contract to v7
- Tighten the desktop pairing window by removing its duplicate heading and reducing the QR code footprint
- Pin GitHub Actions used by CI, Pages, and signed releases to immutable commit revisions

### Fixed

- Treat an uninitialized Quicker common panel as an empty scene instead of failing the complete global/common synchronization
- Remove the former 500-group and 500-action catalog ceilings, raising the logical guard to 10,000 while retaining the bounded response-size limit
- Restore Link capabilities once on the first successful connection of each app session while avoiding repeated synchronization on same-computer reconnects
- Preserve large action catalogs by treating inline icons and shortcut parameters as optional metadata when the bounded response budget is exhausted

## [0.3.0-alpha.3] - 2026-07-31

### Fixed

- Accept Quicker global and common panels containing up to 500 action groups during synchronization

## [0.3.0-alpha.2] - 2026-07-31

### Fixed

- Restore WSS authentication when Quicker is configured without a verification code while keeping authentication responses strictly bound to the active connection

## [0.3.0-alpha.1] - 2026-07-31

### Added

- Stop a synchronized action from its overflow menu through the current Quicker Link companion action
- Add a dedicated transfer workspace for one-shot screen capture, two-way clipboard text, and two-way small-file transfer
- Transfer files in strict 64 KiB chunks with per-chunk and whole-file SHA-256 verification, cancellable progress, and temporary `.part` staging
- Save received files and screen captures through Android's Downloads collection under `Quicker Link`
- Reject duplicate JSON fields throughout the authenticated connection and companion-action response parsers
- Reject coerced WSS envelope fields, require authentication replies to match the active request, and bound inbound text before parsing

### Changed

- Replace vertically stacked action groups with a sticky horizontal group navigator that returns to the group start when switched
- Preserve the action search, selected group, and grid position while switching between bottom navigation destinations
- Record action names together with targets and keep successful connection records to concise summaries instead of catalog payloads
- Upgrade the strict global/common catalog, action-control, and transfer contract to v6
- Confirm that a stopped action has no remaining running instance before reporting success
- Wait for the saved WSS connection to recover after Android's document picker temporarily backgrounds the app
- Raise the minimum supported platform to Android 10 so all received files use the public Downloads collection
- Make final upload confirmation idempotently retryable and disable cancellation while either side is publishing a verified file
- Bind uploads and delayed save confirmation to the original WSS connection settings and companion action
- Report the companion action's transfer-capacity limit as a specific user-facing error

## [0.2.0-alpha.5] - 2026-07-31

### Added

- Synchronize Quicker action context-menu values as labeled parameter choices while retaining free-form parameter input

### Changed

- Limit synchronized actions to local run settings; names, targets, parameter choices, and removal continue to follow Quicker
- Prevent stale editors or delete requests from restoring or removing Quicker-managed synchronized actions
- Upgrade the strict global/common action catalog contract to v4
- Distinguish a missing or outdated companion action from ordinary synchronization failures

## [0.2.0-alpha.4] - 2026-07-31

### Added

- Manually download, verify, and hand a newer signed APK to Android's system installer without navigating through GitHub
- Show the Quicker companion-action page when panel synchronization cannot obtain the global/common catalog
- Add an embedded GitHub icon and repository link to the pairing window, remove its redundant footer close button, and show it non-modally

### Changed

- Dispatch action triggers with `wait=false` and return the Android UI immediately instead of waiting for long-running Quicker actions to finish
- Verify update asset names, URLs, sizes, SHA-256, package identity, version, and signing certificate before installation

## [0.2.0-alpha.3] - 2026-07-31

### Changed

- Use the published Quicker companion action share ID as the stable catalog target when a pairing code does not provide a local action ID
- Replace the one-action-per-row list with a responsive 4-6 column icon grid while retaining grouping, search, editing, deletion, confirmation, and running states
- Upgrade the strict catalog contract to v3 and synchronize validated Quicker action icons
- Distribute the companion action through its Quicker share page while keeping the Android client, tests, and build pipeline openly auditable
- Remove the companion action C# source and complete ActionItem2 export from the Android repository

## [0.2.0-alpha.2] - 2026-07-31

### Added

- Open-source `Quicker Link` companion action that reads the current Quicker WSS settings and generates the Android pairing QR code entirely in memory
- One-tap synchronization of Quicker global and common new-panel actions, including names, source panels, groups, ungrouped entries, and panel order
- Companion-action discovery through the pairing QR code instead of relying only on a built-in action ID
- Searchable, grouped action browser that remains readable while the computer is offline
- About page with project, publisher, author-support, feedback, and manual-only update links

### Changed

- Synced actions now follow Quicker renames and removals while preserving Android-side parameters and confirmation settings
- New installations securely remember pairing credentials by default so the last computer reconnects when the app returns to the foreground
- Scanning a Quicker Link pairing code now fetches the global and common action catalog once after authentication
- Refined connection status, first-run pairing, compact-screen, large-text, dark-theme, and QR-scanner states
- Public documentation now focuses on usage and security boundaries instead of wire-level reproduction examples

## [0.2.0-alpha.1] - 2026-07-30

### Added

- Bounded, credential-free WSS LAN discovery with unique-endpoint selection
- On-device QR pairing scanner and offline local pairing-code generator

### Changed

- Removed cleartext WS support; all connections now require WSS
- Restricted manual and QR endpoints to private IPv4 addresses
- Kept invalid and Quicker cloud-push QR codes in the scanner with a specific local error
- Generated real PNG downloads from the offline pairing tool

## [0.1.0-alpha.1] - 2026-07-30

Dedicated-key-signed prerelease for testing. This is not a stable production release; debug-signed and unsigned APKs are not distributed through GitHub Releases.

### Added

- Direct LAN WSS/WS connection to Quicker
- Verification-code authentication and reconnect state machine
- Saved action shortcuts with optional confirmation
- Text copy and paste commands
- Encrypted verification-code storage using Android Keystore
- Android 17 local-network permission handling
