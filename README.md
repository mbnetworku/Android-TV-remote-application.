<div align="center">

<img src="docs/remote.svg" width="260" alt="TV Remote app screen"/>

# 📺 TV Remote

### Your phone becomes a fast, beautiful remote for **any** Android TV.

No IP typing · Many TVs · Instant response · Liquid glass design

![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)
![Size](https://img.shields.io/badge/size-~60%20KB-5B8CFF)
![Ads](https://img.shields.io/badge/ads-none-00C2B8)
![Tracking](https://img.shields.io/badge/tracking-none-00C2B8)
![License](https://img.shields.io/badge/license-proprietary-E5484D)

**[⬇️ Download the APK](../../releases/latest)** · [How it works](TECHNICAL.md) · [Security](SECURITY.md)

</div>

---

## 🤔 What is it?

TV Remote turns your phone into a remote control for your Android TV or TV box over Wi-Fi.
It also works on TVs that **can't use the Google TV remote** (many Realtek, Amlogic and other non-certified TVs and boxes).

> 💡 **Lost your remote? Batteries dead?** Just open the app.

---

## ✨ Why you'll love it

<table>
<tr>
<td width="50%">

### 🔎 Finds your TV by itself
Open the app and every TV on your Wi-Fi shows up. **No IP addresses to type.**

</td>
<td width="50%">

### 👆 Swipe between TVs
Living room, bedroom, kitchen… save them all and **swipe the TV name** to switch.

</td>
</tr>
<tr>
<td>

### ⚡ Instant response
Key presses arrive in about **16 ms**, close to a real remote.

</td>
<td>

### ⭐ Favorite apps with real icons
Pick any app installed on your TV. It shows with its **real icon**, one tap opens it.

</td>
</tr>
<tr>
<td>

### ⌨️ Real keyboard
Type searches and passwords with your **phone keyboard**.

</td>
<td>

### 🖐️ Touchpad or D-pad
Swipe like a laptop touchpad, or use the classic round D-pad.

</td>
</tr>
<tr>
<td>

### 🔊 See the TV volume
The remote shows the **TV volume in %**. Tap it for a slider, or use **+ / −** and your phone's volume buttons.

</td>
<td>

### 🔒 100% private
No account, no internet, no ads, no tracking. Everything stays on your Wi-Fi.

</td>
</tr>
</table>

---

## 🚀 Get started in 3 steps

```mermaid
flowchart LR
    A["📥 1. Install the app<br/>on your phone"] --> B["📺 2. Turn on<br/>network debugging<br/>on the TV"]
    B --> C["✅ 3. Open the app<br/>your TV appears"]
```

<details>
<summary><b>📥 Step 1: Install the app</b> (click to open)</summary>
<br/>

1. Open **[Releases](../../releases/latest)** on your phone.
2. Tap **`TVRemote.apk`** to download it.
3. Open the file. If Android asks, allow **"Install unknown apps"**.

Needs **Android 5.0** or newer.
</details>

<details>
<summary><b>📺 Step 2: Prepare your TV</b> (one time only)</summary>
<br/>

1. On the TV open **Settings → Device Preferences → About**.
2. Click **Build** 7 times until you see *"You are now a developer"*.
3. Go back to **Developer options** and turn on **USB debugging** / **Network debugging**.
4. Make sure the TV and phone use the **same Wi-Fi**.
</details>

<details>
<summary><b>✅ Step 3: Connect</b></summary>
<br/>

1. Open **TV Remote**. It searches your Wi-Fi and lists your TVs.
2. If the TV shows **"Allow debugging?"**, tick **Always allow** and press **Allow**.
3. Done. The dot next to the TV name turns 🟢 green.
</details>

---

## 🎮 How to use it

| You do this | It does this |
|---|---|
| 👉 **Swipe** the TV name | Switch to your next TV |
| 👆 **Tap** the TV name | See all TVs · switch · remove · scan · add by IP |
| ⭕ **D-pad** | Move · hold to repeat · hold **OK** for options |
| ▢ **Touchpad button** | Switch between D-pad and touchpad |
| ▦ **Apps** | Open any app · **hold** an app to add it to ★ favorites |
| ★ **Favorite** | Opens that app · hold to remove |
| ⌨️ **Keyboard** | Types on the TV while you type on the phone |
| 🔊 **VOL %** label | Tap for a volume slider |
| 🎚️ **Settings button** | Turn vibration and tick sound on or off |

**Status dots:** 🟢 connected · 🟠 connecting · ⚪ offline

---

## ❓ Questions

<details>
<summary><b>My TV doesn't show up</b></summary>
<br/>

- Check that **network debugging** is on (Step 2).
- Check that the phone and TV are on the **same Wi-Fi** (not a guest network).
- Tap the TV name → **Scan**, or type the TV's IP (TV: *Settings → Network → About*).
</details>

<details>
<summary><b>Is it safe?</b></summary>
<br/>

The app talks only to your TV on your home Wi-Fi. It has no internet features, ads or tracking. Your TV asks you to approve the phone first.
Read [SECURITY.md](SECURITY.md) for details.
</details>

<details>
<summary><b>Does it work with Google TV / Chromecast / Sony / TCL / Xiaomi?</b></summary>
<br/>

Yes. It works with any Android TV or Google TV device that has network (ADB) debugging. Fire TV sticks work too (*Settings → My Fire TV → Developer options → ADB debugging*).
</details>

<details>
<summary><b>Some keys like Input or Channel do nothing</b></summary>
<br/>

That depends on the TV. Not every TV supports every key.
</details>

---

## 📚 More

| Document | What's inside |
|---|---|
| 🛠️ [TECHNICAL.md](TECHNICAL.md) | Architecture and how it works inside |
| 🔒 [SECURITY.md](SECURITY.md) | Privacy, permissions and how to report problems |
| ⚖️ [LICENSE](LICENSE) | Usage terms |

---

<div align="center">

### ⚖️ License

**This is proprietary software. © 2026 mbnetworku. All rights reserved.**

✅ You may download and use the app on your own devices.<br/>
❌ You may **not** copy, modify, fork for reuse, or redistribute the code or the APK.

See [LICENSE](LICENSE).

</div>
