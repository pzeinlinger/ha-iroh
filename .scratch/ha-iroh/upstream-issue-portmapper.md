Title: Portmapper never falls back to NAT-PMP when UPnP fails (MikroTik)

<!-- Attention agents and contributors using AI, please see our AI policy before opening an issue: https://github.com/n0-computer/iroh/blob/main/AI_POLICY.md -->
<!-- Any issues violating this policy will be closed. -->

**Describe the bug**

My endpoint sits behind a MikroTik (RouterOS 7.24). The router rejects iroh's UPnP request because it only accepts permanent leases (error 725) while portmapper asks for 2 hours. It does speak NAT-PMP, and Tailscale on the same host gets a NAT-PMP mapping, but iroh never tries it.

From reading the code I think there are two reasons:

1. iroh never calls `portmapper::Client::probe()`, so the service never learns which protocols the router supports.
2. Without a probe result, `Service::get_mapping` goes straight to UPnP (`enable_upnp` defaults to true) and `on_mapping_result` only logs the failure. There is no fallback to NAT-PMP/PCP.

So NAT-PMP and PCP exist in the crate but aren't reachable from an iroh endpoint unless UPnP is turned off, and `PortmapperConfig::Enabled {}` has no field for that (#3811 only added `Disabled`).

Would you take a change that (a) runs the probe on startup / network change, or (b) moves to the next protocol when a mapping attempt fails? Either fixes this router. Retrying UPnP with lease 0 on error 725 would also help, but net-tools #52 went from 0 to 2 h for a different router, so a retry rather than a new constant seems safer.

**Relevant Logs**

`RUST_LOG=portmapper=trace`, UPnP and NAT-PMP both enabled on the router:

```
portmapper: portmap starting
portmapper: tick: msg Some(UpdateLocalPort { local_port: Some(11205) })
portmapper: getting a port mapping for 192.168.1.2:11205 -> None
portmapper: tick: msg Some(ProcureMapping)
portmapper: getting a port mapping for 192.168.1.2:11205 -> None
portmapper: tick: mapping ready
portmapper: failed to get a port mapping UPnP mapping failed
```

No `pmp` or `pcp` span ever appears and no probe runs. Local IP and gateway are correct.

<details>
<summary>Manual checks against the router (written up with AI assistance; I ran the commands)</summary>

UPnP `AddPortMapping` by hand against the router's `WANIPConnection:1` control URL from the same host:

| `NewLeaseDuration` | reply |
|---|---|
| 3600 | `errorCode 725 OnlyPermanentLeasesSupported` |
| 0 | `AddPortMappingResponse` |

NAT-PMP external-address request (opcode 0, UDP 5351) to the gateway from the same host returns a valid v0 response. Tailscale's NAT-PMP mappings show as dynamic dstnat rules on the router.

Code pointers (same on current `main` of iroh and net-tools as of 2026-10-11):

- `iroh/src/socket.rs`: calls on the mapper are `update_local_port`, `procure_mapping`, `deactivate`, `watch_external_address`. No `probe()`.
- `portmapper/src/lib.rs`, `Service::get_mapping`: `if pcp … else if nat_pmp … else if upnp || self.config.enable_upnp … else if !recently_probed && enable_pcp … else if !recently_probed && enable_nat_pmp`. With an empty `full_probe` only the UPnP branch is reachable.
- `Service::on_mapping_result`: on `Err` logs `failed to get a port mapping`, bumps `mapping_failures`, nothing else.
- `portmapper/src/upnp.rs`: `PORT_MAPPING_LEASE_DURATION_SECONDS = 2 * 60 * 60`, no retry with another lease.

</details>

**Expected behavior**

A NAT-PMP mapping, since the router supports it. Or at least an attempt at NAT-PMP/PCP after UPnP fails.

**Iroh**

Version: 1.3.0 (portmapper 0.19.3)

```
iroh v1.3.0
├── iroh feature "default"
│   └── ha_iroh_bridge v0.1.0
├── iroh feature "fast-apple-datapath"
│   └── iroh feature "default" (*)
├── iroh feature "metrics"
│   └── iroh feature "default" (*)
├── iroh feature "portmapper"
│   └── iroh feature "default" (*)
└── iroh feature "tls-ring"
    └── iroh feature "default" (*)
```

Endpoint configuration:

`Endpoint::builder(presets::N0)` with default relays and discovery, plus `.clear_ip_transports().bind_addr("0.0.0.0:<port>").bind_addr("[::]:<port>")` for a fixed UDP port. Default `PortmapperConfig`.

**Platform(s)**
Desktop:
 - OS: Linux (NixOS 26.05), x86_64
 - Version: kernel 6.18

Smartphone: n/a (the mapping is requested by the server side; the peer is an Android client, not relevant here)

**Additional Context / Screenshots / GIFs**

Router: MikroTik RouterOS 7.24.5, `/ip upnp` and `/ip nat-pmp` enabled. Workaround for anyone hitting this: a static port forward, or patching `portmapper` with `enable_upnp: false` in `Config::default()`.

**lolwut**

- [ ] This issue was created by a human that thought critically about
      the issue being reported and wrote this as concisely and clearly
      as they could. Taking full responsibility for the issue being
      accurate.
- [ ] This issue isn't slop, and is backed up by evidence.
