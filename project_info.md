# Deelife TPMS USB Reverse Engineering

## User Request Description
The goal is to reverse engineer a "deelife" USB TPMS device. Currently, it uses an Android application called "StoreBao" to display tire pressure and temperature on an Android car stereo.
However, the app is unreliable and crashes on Android 10.

### Objectives
1. Reverse engineer the USB port protocol (identify speed, communication format).
2. Create a new Android app with a UI.
3. Include a background service in the app to detect and identify issues even when the main app is not open.

## Initial Research Notes
- "Deelife" USB TPMS devices are plug-and-play peripherals that typically communicate via a proprietary serial-over-USB protocol, not standard Bluetooth or Wi-Fi.
- Reverse engineering often involves intercepting the USB traffic using tools like Wireshark/USBPcap (on Windows) or `usbmon` (on Linux) while the original "StoreBao" app is running.
- There are community-driven projects on GitHub (like `SamSkjord/TPMS`) that have already reverse-engineered similar generic USB TPMS receivers. Many of these use identical chipsets.
- The protocol typically uses a fixed frame structure containing the Device ID (sensor), Pressure, Temperature, and a Checksum/CRC.
