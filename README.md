# Deelife USB TPMS — Android Monitor & Alert System

A modern, reliable, and lightweight native Android application and background monitoring service for **Deelife USB Tire Pressure Monitoring Systems (TPMS)**.

Designed specifically to replace the unstable, crash-prone "StoreBao" application on Android automotive head units (Android 10+) and mobile devices.

[![Latest Release](https://img.shields.io/badge/Release-latest-blue.svg)](https://git.vhelectronics.com/vhadmin/Deelife-TPMS/releases/latest)
[![Download APK](https://img.shields.io/badge/Download-APK-success.svg)](https://git.vhelectronics.com/vhadmin/Deelife-TPMS/releases/latest)
[![Bug Report](https://img.shields.io/badge/Report%20Bug-report.vhelectronics.com-orange.svg)](https://report.vhelectronics.com/)

📥 **APK Download:** get `Deelife-TPMS-vX.YY.apk` from the [latest release](https://git.vhelectronics.com/vhadmin/Deelife-TPMS/releases/latest). From v1.12 on, the app updates itself (see [App Updates](#6-app-updates)).

> **Upgrading from v1.11:** v1.12 is signed with the new permanent release key. Uninstall v1.11 once, then install v1.12. Sensor pairing is stored in the USB receiver and is kept.

---

## 🚀 Key Features

* **Continuous Background Monitoring:** Runs as a dedicated Android Foreground Service that monitors tire pressure and temperature even when the screen is off or other navigation/music apps are active.
* **Smart Audio & Visual Alarms:**
  * Prioritized emergency alerts: **Fast Leak / Hardware Puncture** > **Low Pressure** > **High Pressure** > **High Temperature** > **Slow Leak** > **Low Battery**.
  * Automatic audio ducking that lowers car music during alerts and instantly restores playback when the chime finishes.
  * Full-Screen wake-up intent for critical blowouts when the device is locked or backgrounded.
* **Interactive Dashboard:**
  * Live PSI / BAR pressure readings with dynamic trend arrows (▲ rising / ▼ falling).
  * Real-time temperature (°C / °F) with dynamic thermal color coding.
  * Sensor battery health indicators (🔋 / 🪫).
  * Sensor diagnostic status (Running, Idle, Signal Drop).
* **Sensor Calibration:** Fine-tune individual tire pressure offsets (±0.50 BAR / ±7.0 PSI) directly from the settings to calibrate against workshop pressure gauges.
* **Sensor ID Pairing Wizard:** Step-by-step on-screen pairing guide ("ID Study") to easily pair replacement sensors.
* **In-App Updates:** Checks this repository for new releases over the head unit's own internet connection and installs them after confirmation.
* **Alarm Snoozing & Management:** Quick snooze actions (10 min, 30 min, until next restart) with yellow dashboard indicators and early un-snooze capability. Safety-critical fast leak alarms are never suppressed.

---

## 🛠 Technology Stack

* **Language:** Kotlin 2.0
* **UI Framework:** Jetpack Compose with Material 3 design tokens
* **Asynchronous Architecture:** Kotlin Coroutines & `StateFlow` / `SharedFlow`
* **Hardware Interfacing:** [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android) (v3.7.3) with support for QinHeng `CH340` USB-to-UART bridge
* **Background Architecture:** Android Foreground Service with dedicated High & Low priority Notification Channels (`TPMS_ALERT_CHANNEL` and `TPMS_CHANNEL`)
* **Compatibility:** Android 5.0 (API 21) up to Android 14+ (API 34)
* **APK Signature:** V1 (JAR) + V2 + V3 signing with a dedicated release key (certificate SHA-256 `F5:75:78:43:…:47:FF`), required for in-app updates
* **Updates:** Gitea releases API + Android `PackageInstaller` sessions (no extra libraries)

---

## 📡 Protocol Specification

The Deelife TPMS receiver communicates over USB serial at **19200 baud, 8 data bits, no parity, 1 stop bit (8-N-1)**.

### 8-Byte Sensor Telemetry Frame
The receiver maintains an internal cache of all 4 paired sensors and broadcasts an 8-byte frame for each sensor every 2 seconds:

```
Example: 55 AA 08 01 47 47 00 F6
```

| Byte Index | Field | Description | Formula / Values |
|:---:|:---|:---|:---|
| **0** | Header 1 | Frame sync byte 1 | Always `0x55` |
| **1** | Header 2 | Frame sync byte 2 | Always `0xAA` |
| **2** | Length | Total frame length | Always `0x08` |
| **3** | Position | Wheel location | `0x00`: Front Left (FL)<br>`0x01`: Front Right (FR)<br>`0x10`: Rear Left (RL)<br>`0x11`: Rear Right (RR)<br>`0x05`: Spare Tire |
| **4** | Pressure | Raw sensor pressure | `Value * 0.0345 = BAR`<br>`Value * 0.5 = PSI` |
| **5** | Temperature | Raw sensor temperature | `Value - 50 = °C` |
| **6** | Status | Bitmask flags | `0x00`: Normal<br>`0x01`: Low Battery Flag<br>`0x08`: Rapid Leak / Blowout Alarm |
| **7** | Checksum | XOR Checksum | XOR of Bytes 0 through 6 |

### 6-Byte Hardware Commands

| Command | Frame Format | Description |
|:---|:---|:---|
| **Start Pairing** | `55 AA 06 01 <pos> <chk>` | Commands the dongle into pairing mode for position (`00`, `01`, `10`, `11`) |
| **Pairing ACK** | `55 AA 06 02 <pos> <chk>` | Dongle confirmation when a new sensor has paired |
| **Stop Pairing** | `55 AA 06 06 00 <chk>` | Exits hardware pairing mode |
| **Query Sensor IDs** | `55 AA 06 07 00 <chk>` | Requests stored sensor hardware IDs |
| **Dongle Reset** | `55 AA 06 58 55 <chk>` | Reboots the USB receiver |

---

## 📖 User Manual

### 1. Installation & Initial Setup
1. Copy `Deelife-TPMS-vX.YY.apk` from the latest release to a USB flash drive or directly to your Android device/head unit.
2. Open your File Manager and install the APK.
3. Plug the Deelife USB receiver dongle into one of the vehicle's USB ports.
4. When prompted by Android:
   - Check the box **"Always open TPMS when this USB device is connected"**.
   - Tap **OK / Allow** to grant USB device access.
5. Grant Notification permissions when requested (required for background alerts).

### 2. Reading the Dashboard
* **Tire Cards:** Display live pressure and temperature for each of the 4 tires around the vehicle silhouette.
* **Card Colors:**
  * **Dark Blue / Grey:** Normal operating conditions.
  * **Red (Pulsing):** Active alarm (low pressure, high pressure, extreme temperature, or fast leak).
  * **Gold / Yellow (Pulsing):** Alarm active but currently acknowledged/snoozed.
* **Trend Arrows:**
  * `▲` : Pressure has risen by >0.3 PSI over the last few minutes.
  * `▼` : Pressure has fallen by >0.3 PSI over the last few minutes.
* **USB Pill (Top Right):**
  * `● USB Connected` (Green): Dongle detected and active communication verified.
  * `○ Waiting for USB` (Yellow): Cable disconnected, car ignition off, or awaiting USB permission.

### 3. Managing Alarms & Snooze
* When an alarm sounds, tap the alerting tire card on the screen (or tap the **"Snooze 10 Min"** action on the Android notification banner).
* A snooze dialog will appear offering:
  * **Snooze 10 Min:** Silences warnings for 10 minutes.
  * **Snooze 30 Min:** Silences warnings for 30 minutes.
  * **Snooze Until Restart:** Silences warnings until the car is turned off and restarted.
  * **Un-snooze / Resume Alerts:** Immediately re-enables alerts if you have corrected the tire condition.
* *Note:* Safety-critical **Fast Leak** blowout alarms will always sound immediately regardless of active snoozes.

### 4. Customizing Settings
Tap the **Settings ⚙️** button at the bottom of the dashboard:
* **Display Units:** Toggle between **BAR** and **PSI**, and between **Celsius (°C)** and **Fahrenheit (°F)**.
* **Alarm Sound:** Select custom alarm ringtones from the Android system or use the built-in clean chime.
* **Sensor Calibration:** Adjust digital offsets for individual tires (e.g., if a sensor reads 0.05 BAR lower than your digital tire pressure gauge).
* **Threshold Steppers:** Configure custom limits for High Pressure, Low Pressure, and High Temperature.
* **Test Alarm:** Verify speaker volume and chime playback directly inside the vehicle.

### 5. Pairing a New Sensor ("ID Study")
1. Tap **ID Study 🔔** on the dashboard.
2. Select the tire position you wish to pair (`FL`, `FR`, `RL`, or `RR`).
3. Follow the wizard steps:
   * **Step 1:** Unscrew the sensor from the tire valve stem (the system will detect the rapid drop).
   * **Step 2:** Screw the sensor back onto the valve stem tightly.
4. When paired, a success chime sounds and the card highlights in green with `✅ Paired!`.

### 6. App Updates
* When the head unit is online (Wi-Fi or phone hotspot), the app checks for a new release at most every 6 hours.
* If one is found, a dialog shows the release notes with **Update now**, **Later** and **Skip version**. The dialog never covers an active tyre alarm.
* The first time, Android asks you to allow **"Install unknown apps"** for TPMS. Enable it, press Back, and the update continues.
* Android then asks you to confirm the install. Tyre monitoring pauses for a few seconds and restarts automatically.
* **Settings → App Updates** shows the installed version, has a **Check for Updates** button and an **Auto-check** switch.
* If the installer is unavailable on your head unit, use **Release page** in the dialog to download the APK manually.

---

## 🏗 Building & Releasing (maintainers)

* Build: `cd tpms-app && ./gradlew assembleRelease` (JDK 17, Android SDK path in `local.properties`).
* Release signing reads `tpms-app/keystore.properties` (git-ignored): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. **Keep the keystore backed up.** If it is lost, users cannot update any more and must reinstall.
* Publish: bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, write notes, then run
  `scripts/publish-release.sh release-notes/vX.YY.md`. The script builds, checks the signing certificate, tags, creates the Gitea release and uploads the APK plus `update.json` (`versionCode`, `versionName`, `apk`, `sha256`, `minSdk`), which the in-app updater reads.

---

## 🐛 Bug Reports & Community Feedback

Find a bug or have an enhancement request?
Submit reports directly without needing an account at:
👉 **[https://report.vhelectronics.com/](https://report.vhelectronics.com/)** (Select **Deelife TPMS** from the project list).

---

## 📜 License & Acknowledgments
* Reverse engineering and native Android application developed for vehicle integration.
* Uses the open-source [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android) library.
