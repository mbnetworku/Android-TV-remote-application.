# Security & Privacy

## What the app can access

| Item | Used for |
|---|---|
| `INTERNET` permission | Opening a local connection to the TV on your Wi-Fi (port 5555). |
| `ACCESS_NETWORK_STATE` | Finding your Wi-Fi address so it can search for TVs. |

The app has **no** location, camera, microphone, contacts or storage permissions. It contains **no** analytics, ads, trackers or cloud services, and it never sends data off your local network.

## How the connection is protected

- The phone creates its **own private RSA-2048 key** the first time it runs. The key is stored in the app's private storage and never leaves the phone.
- The TV must approve that key ("Allow debugging?") before the phone can control it. You can revoke it anytime on the TV: **Developer options → Revoke USB debugging authorizations**.
- To make key presses fast, the app copies a small helper (its own APK, ~60 KB) to `/data/local/tmp` on the TV and runs it with ADB shell rights. It only injects key presses and text. It is removed if you revoke debugging or reset the TV.

## Important: understand network debugging

Network debugging (ADB) gives powerful control over the TV to **any approved device**. Please:

1. Only press **Allow** for devices you own.
2. Use it on a **trusted home Wi-Fi**, not public networks.
3. Turn network debugging off if you stop using the app.
4. Keep the TV's firmware up to date.

Some TVs ship with debugging that accepts any device without asking. On those TVs, anyone on the same Wi-Fi could connect. This is a TV setting, not caused by this app.

## Supported versions

Only the latest release on the [Releases](../../releases) page receives security fixes.

## Reporting a vulnerability

Please **do not** open a public issue for security problems. Use GitHub's **"Report a vulnerability"** (Security tab → Advisories) for this repository. You'll get a reply within 7 days.

## Connecting without developer options

- **Google TV pairing:** the phone and TV pair with a code shown on the TV and use encrypted TLS. You can remove the phone on the TV under *Settings → Remotes & Accessories* (name differs by TV).
- **TV Receiver app:** only phones that typed the **4-digit PIN** shown on the TV can control it. The PIN window appears on the TV every time a new phone tries to connect. To remove all phones, clear the receiver's storage, or uninstall it. The receiver uses Android Accessibility only to move focus, press buttons, type text and open apps. It does not read or send your screen content anywhere.
