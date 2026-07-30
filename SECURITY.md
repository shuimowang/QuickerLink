# Security Policy

## Supported versions

There is no stable production release yet. `v0.2.0-alpha.1` is a dedicated-key-signed prerelease for testing and receives fixes on a best-effort basis. Pre-release builds may change storage or protocol behavior without backward compatibility.

GitHub Releases publish neither debug-signed nor unsigned APKs. Verify the downloaded release APK against the SHA-256 file attached to the same release. A matching checksum verifies bytes; trust in the first installation still depends on obtaining it from the authentic project release page. Subsequent versions must retain the dedicated signing key so Android can verify update continuity.

The dedicated Release signing certificate has this SHA-256 fingerprint:

```text
55:FA:F4:A1:58:7B:58:7A:BF:0E:42:7C:08:02:99:32:35:DA:E8:1E:3C:05:B6:21:C2:FE:D3:F5:7A:60:89:C7
```

## Reporting a vulnerability

Please use GitHub's private security advisory feature for vulnerabilities involving authentication, TLS validation, credential storage, command execution, or unintended network access.

Do not include real Quicker verification codes, private IP mappings, action parameters, or other credentials in public issues.

## Security boundaries

- Quicker Link is intended for a trusted, mutually reachable LAN.
- WSS is the only supported transport; the app does not offer a cleartext WS downgrade.
- Discovery is restricted to private IPv4 Wi-Fi/Ethernet subnets and bounded by fixed candidate, concurrency, and timeout limits. Probes perform only certificate-validated WSS handshakes and send no verification code; automatic authentication begins only when the completed scan has exactly one candidate.
- A successful discovery handshake identifies a compatible WSS endpoint, not a durable identity for a particular computer. Use the locally generated QR code or a verified manual address on an untrusted or ambiguous LAN.
- Pairing QR codes contain the verification code. Generate and scan them locally, never publish them, and regenerate the code after suspected disclosure.
- The app does not make a LAN service safe to expose directly to the public internet.
- Triggered Quicker actions run with the permissions and side effects defined by the user on the computer.
- Android backup and device-transfer rules exclude app preferences and the other app data domains, but this does not protect data extracted from a rooted or otherwise compromised device.
