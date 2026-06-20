# Privacy Policy

**Effective Date:** June 20, 2026

## About GostX

GostX is an open-source Android VPN proxy client that routes network traffic through user-configured proxy chains. All functionality runs locally on your device.

## Information Collection

GostX does **not** collect, store, or transmit any personal information or usage data. Specifically:

- No personal identifiers (name, email, device ID) are collected
- No analytics or tracking frameworks are integrated
- No crash reporting services are used
- No network traffic data is logged or transmitted off-device
- No data is shared with third parties

## VPN Service

GostX uses the Android `VpnService` API to create a local TUN interface for routing traffic through user-defined proxy chains. The VPN connection operates entirely on-device. GostX does not route traffic through any servers operated by the developer — all proxy destinations are configured by the user.

## Permissions

GostX requests the following permissions:

| Permission | Purpose |
|---|---|
| `android.permission.BIND_VPN_SERVICE` | Required to create and manage the VPN tunnel for traffic routing |

## Changes to This Policy

Any changes to this privacy policy will be reflected in updates to this document and the corresponding app release.

## Contact

For questions about this privacy policy, please open an issue on the [GitHub repository](https://github.com/lkebin/GostX).
