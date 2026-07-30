# Changelog

All notable changes to this project will be documented in this file.

## Unreleased

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
