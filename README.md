# dvrip-cam

**Local, cloud-free control for Xiongmai / LiteOS DVRIP battery IP cameras — no XMEye / iCSee cloud, no vendor app.**

A native Android app that talks **directly** to a cheap Chinese Wi-Fi IP camera over its
native **DVRIP / Sofia** protocol (TCP 34567) on your LAN: live video, SD-card archive playback,
configuration and Wi-Fi onboarding — with **no cloud account, no Firebase, no analytics**.

> **Status:** working app. Live video (SD/HD), live audio, SD-card archive browsing & playback,
> and a privacy screen (disable Xiongmai cloud/push) are implemented and build on GitHub Actions;
> install the signed APK from [Releases](../../releases). The DVRIP protocol is fully
> reverse-engineered in [`docs/PROTOCOL.md`](docs/PROTOCOL.md); Python helper tools are in
> [`tools/`](tools/).

## Why

These cameras only ship with an uncertified Chinese app that routes traffic through Chinese cloud
servers (`secu100.net`, `push.umeye.cn`). This project replaces it with a fully local client.

## Target device

A Xiongmai-family battery Wi-Fi PIR camera:

- SoC: HiSilicon **Hi3518EV200**, firmware **Huawei LiteOS**
- Video: **H.264** (Main 1080p / Extra D1), audio **G.711 A-law**
- Control + video: **DVRIP only** (TCP 34567) — **no RTSP/ONVIF**
- Battery + PIR; sleeps when idle (see notes below)

Other RTSP-based Xiongmai cams are **not** the target of this project (see `docs/SPEC.md` for why).

## What's here

| Path | Contents |
|---|---|
| [`docs/SPEC.md`](docs/SPEC.md) | Full technical specification for the Android app |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | Reverse-engineered **DVRIP protocol reference** (framing, sofia-hash, opcodes, config sections, live/playback/talk flows, discovery, Wi-Fi QR) |
| [`tools/`](tools/) | Python helper tools (RTSP bridge config, browser viewer, Wi-Fi-QR generator, minimal DVRIP client) |

## Features

Implemented:

- 🎥 Live view — native DVRIP `OPMonitor` decode via `MediaCodec`, aspect-correct, **SD/HD** (Extra D1 / Main 1080p) toggle
- 🔊 Live audio — Sofia/DHAV demux + **G.711** (A-law/µ-law) → `AudioTrack`
- 🗂️ SD-card archive — browse recordings by date (motion `[A]` / continuous `[R]`), play back over DVRIP `OPPlayBack`
- ⚙️ Privacy screen — disable Xiongmai cloud (`NetWork.Nat`, secu100.net) and push (`NetWork.PMS`, push.umeye.cn)
- 🖼️ Image controls — mirror/flip, day-night mode, anti-flicker, BLC, corridor, DIS, low-lux (`Camera.Param`/`ParamEx`)
- 🔧 Camera reboot (`OPMachine`), OSD name, PIR, time sync
- 🎚️ Recording mode & clip length (`Record`), motion level + human detection (`Detect.*`)
- 🎨 Color tuning (`AVEnc.VideoColor`) and main-stream quality FPS/bitrate (`Simplify.Encode`)
- ⏳ Battery-aware connect — waits for the sleeping camera to wake (trigger motion), then holds the stream
- ⬆️ In-app self-update from GitHub Releases

Planned:

- 🔎 LAN discovery (UDP 34569) + multiple cameras of the same model
- 🎙️ Two-way talk-back (`OPTalk`), clip download to MP4, more settings (Wi-Fi/OSD/motion/time)
- 📶 Wi-Fi onboarding: **QR code** or **SoftAP** (no BLE — the target camera has no Bluetooth)

## Battery / sleep — important

The camera **sleeps** when idle and **cannot be woken over the network** (verified: neither a DVRIP
connection nor Wake-on-LAN wakes it). It wakes on **motion (PIR)**, external power, or a cloud push.
The app is designed around this: it waits for the camera to become reachable (you trigger motion),
then holds a live stream open (which keeps it awake) for real-time viewing. See `docs/SPEC.md §3`.

## Tools quick start

The Python tools read configuration from environment variables (no hardcoded credentials):

```bash
export CAM_HOST=192.168.1.10      # your camera IP
export CAM_PASS=yourpassword      # camera admin password

python3 tools/dvrip_login.py      # login + dump config sections
python3 tools/camserver.py        # browser viewer (Live + Archive) at http://localhost:8090
python3 tools/wifi_qr.py "MySSID" "MyPassword"   # generate a Wi-Fi onboarding QR
```

`tools/go2rtc.yaml` is a config for [go2rtc](https://github.com/AlexxIT/go2rtc) (download the binary
separately) that bridges the camera's DVRIP stream to RTSP/WebRTC; it uses `${CAMERA_IP}` / `${CAMERA_PASS}`.

## Related / credits

- [OpenIPC/python-dvr](https://github.com/OpenIPC/python-dvr) — DVRIP protocol library
- [voidnullvalue/Icsee-android](https://github.com/voidnullvalue/Icsee-android) — FOSS iCSee app (targets RTSP-capable XM cams)
- [AlexxIT/go2rtc](https://github.com/AlexxIT/go2rtc) — DVRIP → RTSP/WebRTC bridge

## License

[MIT](LICENSE). Personal tool, provided as-is. Use only on cameras you own.
