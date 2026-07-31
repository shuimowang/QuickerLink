# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

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
