# Documentation

Guides for configuring GostX. Every profile is a single gost YAML file; start
with the [`config.yaml.example`](../config.yaml.example) and add the features
you need.

## Guides

| Document | Description |
| --- | --- |
| [TUN mode: the `tungo` handler](tungo.md) | Required by every VPN profile on Android and iOS; how traffic is routed and how DNS is handled. |
| [Fake IP](fakeip.md) | Let the proxy resolve names: apps get placeholder addresses, bypass rules match domains. |
| [WireGuard upstream](wireguard.md) | Use a WireGuard peer as the upstream, with whitelist-based split tunneling. |
| [Hysteria upstream](hysteria.md) | Use a Hysteria 2 server as the upstream over QUIC. |

## Internals

| Document | Description |
| --- | --- |
| [Android VPN data flow](data-flow.md) | Packet path from the TUN device through sing-tun and gost to the proxy. |
| [Project README](../README.md) | Features, supported platforms and build instructions. |