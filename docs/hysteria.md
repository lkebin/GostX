# Hysteria upstream

Use a Hysteria 2 server as the upstream. The dialer opens a QUIC session and the
connector opens TCP and UDP flows inside it.

You need UDP reachability to the server, the server's `auth` string, and the
TLS server name the certificate was issued for.

## Config

```yaml
services:
  - name: vpn
    handler:
      type: tungo
      chain: upstream

chains:
  - name: upstream
    hops:
      - name: hop0
        nodes:
          - name: hy
            addr: hy.example.com:443
            dialer:
              type: hysteria
              tls:
                serverName: hy.example.com
                # secure: true
                # caFile: ca.pem
              metadata:
                auth: "<password>"
                direct: true
                congestion: bbr
                # bandwidth.tx: 12500000   # bytes per second (Brutal)
                # bandwidth.rx: 12500000
            connector:
              type: hysteria
```

`direct: true` is required when using the `hysteria` connector: the dialer
returns the session handle and the connector opens a stream to the target
address. Without it the dialer opens a stream to a placeholder address and the
target is lost.

## Dialer metadata

| Key | Required | Default | Description |
| --- | --- | --- | --- |
| `auth` | yes | — | Hysteria authentication string. |
| `direct` | yes | `false` | Return the session handle for the connector. Set to `true`. |
| `congestion` | no | `bbr` | Congestion control; use `brutal` for fixed-rate bandwidth. |
| `bandwidth.tx` | no | — | Max send rate in bytes per second (Brutal only). |
| `bandwidth.rx` | no | — | Max receive rate in bytes per second (Brutal only). |
| `fastOpen` | no | `false` | Enable QUIC fast open. |
| `keepAlive` | no | `true` | Enable QUIC keepalive. |
| `ttl` | no | `10s` | Keepalive interval. |
| `maxIdleTimeout` | no | `30s` | QUIC idle timeout. |

## TLS

`dialer.tls.serverName` sets the SNI and the certificate name. Certificate
verification is off by default, which is convenient for self-signed servers.
Set `secure: true` to verify, optionally with `caFile` for a private CA.

## Notes

- `addr` is the Hysteria server host and port; the port defaults to `443` when
  omitted.
- The QUIC session is reused across flows and rebuilt automatically when it
  dies (idle timeout, network switch, server restart).
- Android protects the QUIC UDP socket from the VPN, so the session does not
  loop through the TUN.
- UDP flows are carried by the connector, so DNS and other UDP traffic work
  through the tunnel.