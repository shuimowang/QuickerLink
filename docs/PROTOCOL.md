# Quicker WebSocket protocol notes

Quicker Link implements the protocol documented by Quicker's [WebSocket service documentation](https://getquicker.net/KC/Manual/Doc/websocketservice). The HTTP push API is a separate, cloud-relayed interface and is not used by this project.

Quicker's documentation warns that it can lag behind current software. Items below are separated into the documented wire format and Quicker Link's defensive compatibility choices; they are not claims of an additional official protocol guarantee.

## Endpoint

```text
wss://<dashed-computer-ip>.lan.quicker.cc:<port>/ws
```

For example:

```text
wss://192-168-1-56.lan.quicker.cc:668/ws
```

The WSS hostname is retained for TLS SNI and hostname validation. Quicker Link's custom DNS implementation maps only valid `<IPv4>.lan.quicker.cc` names back to the encoded IPv4 and delegates all other lookups to the system resolver.

Although Quicker also documents cleartext `ws://` endpoints, Quicker Link intentionally supports only WSS and never downgrades automatically.

## LAN discovery

Quicker does not document a LAN discovery broadcast or mDNS service. Quicker Link therefore derives bounded candidates from an available Wi-Fi or Ethernet private IPv4 subnet and probes only the configured WSS port. Discovery performs certificate and hostname validated WebSocket handshakes without sending the verification code. After a complete scan, exactly one candidate may proceed to the normal type `5`/`6` authentication flow; zero or multiple candidates require QR pairing or a manual private IPv4 address.

The WSS handshake is not treated as a durable device identity. Users on an untrusted network should use a locally generated pairing QR code or verify the computer address manually.

## Pairing QR code

Quicker Link's pairing format is independent from Quicker's cloud push QR code:

```text
quickerlink://pair?v=1&ip=192.168.1.56&port=668&code=<percent-encoded-verification-code>&serviceActionId=<GUID>
```

The parser requires schema version `1`, a private IPv4 address, a valid port, bounded control-character-free credentials, unique known fields, and implicit WSS transport. `serviceActionId` is an optional canonical GUID for backward compatibility with older or manually generated codes; the companion action always includes its current action ID. HTTP(S), cloud push, public-IP, cleartext-WS, and unknown-version payloads are rejected.

The configured port is required. One official WSS example omits it, but the explanatory text and official sample client both include it. Quicker's example client commonly defaults to `668`; the actual WebSocket port configured on the computer remains authoritative.

The repository includes a companion Quicker action under [`quicker`](../quicker). It reads `WebsocketServerSettings` from the running Quicker instance, requires secure transport, selects from active private IPv4 addresses, and generates this payload locally with Quicker's bundled QRCoder library. It does not create a second credential or change the configured verification code.

## Message types

| Type | Meaning |
|---:|---|
| `2` | Command request or server push |
| `4` | Command response |
| `5` | Authentication request |
| `6` | Authentication response |

Authentication request:

```json
{"messageType":5,"serial":1,"data":"verification-code"}
```

Authentication is a verification-code exchange after the WebSocket opens, not an account login or durable device-pairing protocol. If Quicker has no verification code configured, it documents sending an unsolicited successful type `6` response with `replyTo: 0`. Quicker Link waits for that response before becoming ready.

Action request:

```json
{
  "messageType": 2,
  "serial": 2,
  "operation": "action",
  "action": "Action name or ID",
  "data": "input parameter",
  "wait": true
}
```

Response:

```json
{
  "messageType": 4,
  "replyTo": 2,
  "isSuccess": true,
  "data": "result"
}
```

## Global action catalog

The companion action defines a Quicker Link-specific catalog command on top of Quicker's normal `action` operation. It is not an additional official Quicker protocol:

```json
{
  "messageType": 2,
  "serial": 3,
  "operation": "action",
  "action": "<serviceActionId from the pairing code>",
  "data": "quickerlink:list-global-actions:v1",
  "wait": true
}
```

Quicker returns the action result in the outer response's `data` field as a JSON string. After decoding that string, a successful catalog has this form:

```json
{
  "protocol": "quickerlink.global-actions",
  "version": 1,
  "ok": true,
  "scene": "_global",
  "groups": ["常用"],
  "actions": [
    {
      "id": "11111111-1111-4111-8111-111111111111",
      "title": "打开项目",
      "group": "常用",
      "order": 4
    }
  ]
}
```

`groups` preserves the explicit Quicker panel group order. `group` is `null` for an ungrouped action, and `order` is the original `_global` panel entry index, so gaps are valid. The service excludes itself, uses the first placement when one action is placed more than once, caps the result at 500 actions and 100 groups, and returns no source code, internal parameters, icons, or app-specific actions.

Errors use the same protocol and version with `ok:false`, a stable `code`, and a bounded human-readable `error`. The Android client validates payload size, UUIDs, group references, and strictly increasing original order before replacing its synchronized entries. Manual entries remain untouched.

The official schema associates responses through `replyTo`, although some file-transfer examples contain conflicting casing and correlation values. Quicker Link gives every outgoing request a unique `serial`, accepts response field names case-insensitively, fails pending requests when the connection closes, and never replays commands automatically.

The WebSocket document defines `wait` but does not define the HTTP push API's `maxWaitMs` field for this transport. Quicker Link therefore applies a local 30-second command timeout instead of sending `maxWaitMs`.

Quicker documents `copy`, `paste`, `action`, `open`, keyboard/text input, input scripts, downloads, file transfer, and image paste operations across the WebSocket and related push documentation. This preview intentionally exposes only its documented feature subset; mentioning an operation here does not mean it is implemented.

## Compatibility decisions

- Outgoing fields always use lower camel case.
- Incoming fields are matched case-insensitively because official examples use both `messageType` and `MessageType` styles.
- Unknown message types and binary messages are logged without execution.
- Incoming `copy` commands write text to the Android clipboard. When the request includes a serial, Quicker Link sends a type `4` response as an interoperability choice; the official document does not fully specify client acknowledgements for server-pushed commands.
- File transfer and `pasteimage` are intentionally unsupported until their framing and response behavior are validated against current Quicker versions. The document names `pasteimage` without defining its payload.
- The official document does not specify heartbeat frames, idle timeouts, close-code semantics, retry policy, session resumption, or whether side-effecting commands are safe to replay. Quicker Link uses transport pings and exponential backoff with a 30-second delay cap plus jitter, then performs authentication again after reconnecting.
