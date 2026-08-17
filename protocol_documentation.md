# Deelife TPMS USB Protocol Specification

This document contains the fully verified USB serial protocol for the Deelife TPMS receiver. This is the foundation for building the new Android Application.

## USB Connection Details
*   **Chip:** CH340 USB-to-Serial Converter
*   **Baud Rate:** `19200`
*   **Data Flow:** The receiver maintains a cache of the last known state of all paired sensors. It blasts this cached state over the serial connection continuously every 2 seconds. Sensors only transmit to the receiver when they detect a sharp pressure change or wheel rotation (centrifugal force).

## 8-Byte Frame Structure

The receiver sends exactly 8 bytes for each sensor in the cache. 
Example Frame: `55 AA 08 01 47 47 00 F6`

| Byte Index | Name | Description | Example (`55 AA 08 01 47 47 00 F6`) |
| :--- | :--- | :--- | :--- |
| **0** | **Header 1** | Always `55` (Start of Frame) | `55` |
| **1** | **Header 2** | Always `AA` (Start of Frame) | `AA` |
| **2** | **Length** | Always `08` (Payload length) | `08` |
| **3** | **Position** | `00`=FL, `01`=FR, `10`=RL, `11`=RR, `05`=Spare | `01` (Front Right) |
| **4** | **Pressure** | Raw Hex Value. <br>**Formula:** `Value * 0.0345 = BAR` <br>*(or `Value * 0.5 = PSI`)* | `47` (Hex) = 71 (Dec). <br>71 * 0.0345 = **2.45 BAR** |
| **5** | **Temperature**| Raw Hex Value. <br>**Formula:** `Value - 50 = °C` | `47` (Hex) = 71 (Dec). <br>71 - 50 = **21 °C** |
| **6** | **Status** | Hardware Flags. <br>`00` = Normal <br>`08` = Hardware Alarm (e.g., 0 psi / leak) <br>*Other bits (like `01` or `02`) are likely used for Low Battery flags.* | `00` (Normal State) |
| **7** | **Checksum** | XOR of Bytes 0 through 6 | `F6` |

## Alarm & Battery Architecture

### 1. Hardware Alarms (From the Sensor/Receiver)
The physical sensor triggers an immediate alert on critical events (like unscrewing it or a rapid puncture). This sets the **Status Byte** to `08`. Our tests confirmed that during a rapid pressure drop to `0.0 psi`, the status immediately changes to `08`.

### 2. Low Battery Flag
Because none of your sensors have a low battery, we did not observe the battery flag. However, it is standard for this flag to be another bit inside the **Status Byte** (Byte 6). In the Android app, any Status Byte `> 00` that isn't `08` should be treated as a Low Battery or Sensor Fault warning.

### 3. Software Alarms (In the Android App)
The new Android app will have the power to define custom thresholds that the original hardware doesn't support. The app will simply read the precise BAR and °C measurements and trigger software alarms if:
*   Pressure > User High Threshold (e.g., 3.0 BAR)
*   Pressure < User Low Threshold (e.g., 2.0 BAR)
*   Temperature > User High Threshold (e.g., 65 °C)
*   Status Byte != `00` (Hardware Leak or Battery Fault)

## Checksum Validation (Python Example)
```python
# To validate a frame:
calculated_checksum = 0
for byte in frame[0:7]:
    calculated_checksum ^= byte

if calculated_checksum == frame[7]:
    # Frame is valid
```
