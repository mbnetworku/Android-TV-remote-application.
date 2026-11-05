# Technical Overview

## Summary

| | |
|---|---|
| Language | Java 17, plain Android SDK (no third-party libraries) |
| Min / target SDK | 21 (Android 5.0) / 34 |
| APK size | ~60 KB |
| Transport | ADB protocol over TCP (port 5555), implemented from scratch |
| Input latency | ~16 ms per key after setup (vs ~1.4 s for `adb shell input keyevent`) |

## Architecture

```
Phone app                                       Android TV
┌─────────────────────────┐   TCP 5555 (ADB)   ┌──────────────────────────┐
│ MainActivity (UI)       │                    │ adbd                     │
│  ├ DpadView/TouchpadView│                    │  ├ shell: getprop, am …  │
│  ├ DeviceStore (TVs)    │ ─────────────────▶ │  ├ sync: push helper APK │
│  └ NetworkScanner       │                    │  └ shell: app_process    │
│ TvController (per TV)   │                    │       └ Injector ───────▶ InputManager
│ adb/AdbConnection       │                    │                          │
│ adb/AdbStream, AdbCrypto│                    └──────────────────────────┘
└─────────────────────────┘
```

### Packages

| Path | Purpose |
|---|---|
| `adb/AdbConnection` | Handshake (CNXN/AUTH), stream multiplexing (OPEN/OKAY/WRTE/CLSE), `shell()`, `push()` via the sync service |
| `adb/AdbStream` | One logical stream with flow control and blocking/timeout reads |
| `adb/AdbCrypto` | RSA-2048 key creation, PKCS#1 token signing, Android public-key encoding |
| `inject/Injector` | Runs **on the TV** via `app_process`; reads `k CODE`, `l CODE`, `t TEXT` lines and injects `KeyEvent`s through `InputManager.injectInputEvent` (reflection; supports `InputManagerGlobal` on Android 14+) |
| `TvController` | One per TV: connect, install/start helper, send keys/text, list and launch apps, fall back to `input keyevent` if the helper can't start |
| `NetworkScanner` | Parallel scan of the phone's /24 subnet for port 5555 (48 threads, 500 ms timeout) |
| `DeviceStore` | Saved TVs and the selected one (SharedPreferences) |
| `ui/LiquidBackground` | Animated gradient background behind the glass controls |
| `ui/DpadView`, `ui/TouchpadView` | Custom-drawn controls with repeat, long-press and haptics |

## Connection flow

1. TCP connect → `CNXN` → TV replies `AUTH TOKEN`.
2. Phone signs the token (`AUTH SIGNATURE`). If the TV doesn't know the key, phone sends `AUTH RSAPUBLICKEY` and the TV shows "Allow debugging?".
3. After `CNXN` from the TV: `getprop ro.product.model` for the TV name.
4. `md5sum` of `/data/local/tmp/atvremote-helper.apk`; if it differs, the APK is pushed through `sync:`.
5. `CLASSPATH=… app_process / com.atvremote.app.inject.Injector` is started and kept open; it prints `READY`.
6. Each button writes one line (`k 19\n`) to the helper's stdin.

All saved TVs connect in parallel when the app opens, so switching is instant. Connections close when the app goes to the background.

## Apps & favorites

- App list: `cmd package query-activities -a MAIN -c LEANBACK_LAUNCHER`, parsed into `package/activity`.
- Launch: `am start -n 'package/activity'`.
- Favorites are stored per TV in SharedPreferences (`fav_<ip>`).

## Build

```bash
gradle assembleRelease   # Gradle 8.7, Android SDK 34, JDK 17
```

Output: `app/build/outputs/apk/release/app-release.apk`. Minification is off on purpose: the TV runs the `Injector` class straight from the APK.

## Tested

- Realtek "SMART_TV", Android 9: connect 50 ms, 300 KB push 130 ms, helper start ~2 s, key round-trip ~16 ms.
- "MBOX" (Amlogic), Android 5.1.1: helper, volume read, real app names + icons for all 25 apps.
