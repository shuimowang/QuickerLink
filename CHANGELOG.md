# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

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
