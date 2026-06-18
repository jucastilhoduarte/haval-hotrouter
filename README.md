# HotRouter

Tiny, single-purpose Android app for **my own Haval head unit**. It does exactly one
thing: run the **HotRouter** daemon that bridges the car's Wi-Fi hotspot out through the
external Starlink uplink (`wlan0`) when reachable, and falls back to the OEM 4G route
otherwise.

Extracted from the old `haval-app-tool-multimidia` project — only the HotRouter feature
survives. No Frida runtime, no cluster projection, no vehicle control, no Shizuku.

Not on any store. Installed only on my car, signed with my own key.

## What it looks like

One screen:

- A **big button** that says LIGADO / DESLIGADO — tap to toggle.
- A **chip** below it: `Trafegando via Starlink` or `Trafegando via 4G`.
- A **Ver logs** button.

It auto-starts on boot and restores the last on/off state — no need to open the app.

## How it works

- **Zero dependencies.** Android SDK only. Java. No AndroidX, no Compose, no Shizuku, no
  telnet library.
- Privileged work (the daemon, `ip rule`, `iptables`) runs through the head unit's root
  telnet shell on `127.0.0.1:23`, reached by a ~100-line raw-socket client
  ([`TelnetRoot.java`](app/src/main/java/com/castilhoduarte/hotrouter/TelnetRoot.java)).
- The shell is only reachable if the app's uid ≤ 10999, which is why it must be installed
  through the Frida exploit window — see [`scripts/install.sh`](scripts/install.sh).
- The daemon itself is [`hotrouter.sh`](app/src/main/assets/hotrouter.sh), pushed to
  `/data/local/tmp` and supervised by a 60s watchdog.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design.

## Build / release

- **Pull request → `assembleDebug`** (compile check only, no secrets).
- **Merge to `main` → signed `assembleRelease`**, published as a GitHub release with the
  APK attached.

Signing secrets live in repo Actions secrets: `KEYSTORE_BASE64`, `STORE_PASSWORD`,
`KEY_PASSWORD`, `KEY_ALIAS`. The keystore is never committed.

## Install on the car

Via telnet (`telnet 192.168.x.x 23`) ou direto no shell da multimídia, de qualquer pasta:

```sh
curl -fsSL https://raw.githubusercontent.com/jucastilhoduarte/haval-hotrouter/main/scripts/install.sh | sh
```

Isso baixa e executa o install.sh, que já puxa os binários do Frida e o APK do último release automaticamente.
