# Deelife TPMS Reverse Engineering - Context Transfer

*This file contains the summarized context of our conversation. When you open this project on your laptop, ask the AI to read this file to instantly resume where we left off.*

## 1. Project Goal
Reverse engineer the USB protocol of a "Deelife" TPMS receiver and create a custom, stable Android app (with a background service) to replace the crash-prone "StoreBao" app.

## 2. Hardware Discovered
*   **USB Receiver:** Uses a standard `CH340` USB-to-Serial converter.
*   **Baud Rate:** `19200`
*   **Behavior:** The receiver maintains a cached state of all 4 tires and continuously blasts this state over the serial connection every 2 seconds. The sensors themselves only transmit to the receiver when they detect a sharp pressure change or wheel rotation (centrifugal force).

## 3. Protocol Decoded So Far
The USB receiver continuously sends 8-byte frames for each tire. It broadcasts its cached state every 2 seconds, which means it will repeat stale data indefinitely until a sensor wakes up (due to pressure change or driving) and updates the receiver.
Example captured frame: `55 AA 08 01 47 47 00 F6`

*   `55 AA`: Header / Start of frame
*   `08`: Frame Length / Type
*   `01`: **Tire Position** (`00`=Front Left, `01`=Front Right, `10`=Rear Left, `11`=Rear Right, `05`=Spare Tire)
*   `47`: **Pressure** (Hex `47` = Decimal `71`. Formula: `71 * 0.0345 = 2.45 BAR` or `71 * 0.5 = 35.5 PSI`)
*   `47`: **Temperature** (Hex `47` = Decimal `71`. Formula: `71 - 50 = 21 °C`)
*   `00`: **Status Byte** (`00`=Normal, `08`=Alarm/Low Pressure/Rapid Leak. Battery warning is almost certainly another bit in this byte, e.g., `01` or `02` meaning Low Battery).
*   `F6`: Checksum (XOR of all previous bytes from `55` to `00`)

## 4. Pairing Mode Notes
*   The USB receiver memory maps the unique hardware IDs of the sensors to the positions (00, 01, 10, 11).
*   The Android app does **not** handle raw sensor MAC addresses.
*   To pair a new sensor, the host must send a specific 6-byte command to the USB receiver (documented in the open-source `SamSkjord/TPMS` library). This commands the receiver to enter pairing mode for a specific tire position.

## 5. Current State & Next Steps
*   **Progress:** 
    *   Successfully tested the USB communication directly at the car using the Python script.
    *   Fully decoded the 8-byte protocol (Header, Position, Pressure, Temperature, Status flags, XOR Checksum).
    *   Developed a working native Android application (`tpms-app`) featuring a background service for continuous monitoring, sound/visual alarms, and a sensor calibration system.
    *   Enhanced the UI on the laptop:
        *   Tire cards now turn **yellow** and display a `"snoozed for X minutes"` countdown label when their alarm is snoozed.
        *   The settings screen's `"Return to Dashboard"` button has been moved to a fixed position at the **bottom-right corner** to eliminate scrolling.
*   **Next Steps:**
    1.  Test the updated snoozing UI and settings layout directly in the car on the laptop.
    2.  Sync the laptop's project folder back to the PC (use `changelog.md` to reference modified files).
