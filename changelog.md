# Changelog - v1.12 In-App Updates (2026-09-26)

* **New `update/` package:** `UpdateManager` (check / download / install), `ReleaseParser` (Gitea JSON, version compare), `InstallResultReceiver` (PackageInstaller results). UI is in `ui/UpdateUI.kt` (dialog + Settings card).
* **Manifest:** `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_INSTALL_PACKAGES`, `CHANGE_NETWORK_STATE` (Android 14 FGS). `BootReceiver` also handles `MY_PACKAGE_REPLACED`.
* **Build:** new release signing key (outside the repo, via `keystore.properties`), `buildConfig` enabled, version 1.12 (12). Added the missing `gradle.properties` and `gradlew.bat`, and fixed the local unit tests.
* **Fixes:** permanent USB reconnect loop, Android 14 PendingIntent crash, snooze display during fast leak, pairing-step race, MediaPlayer leak, synchronised alarm evaluation.
* **Repo:** untracked the partial `tpms-app/jdk` and `.kotlin` session files, removed the unused `tung_sahur.png` (a JPEG that broke release builds). Added `scripts/publish-release.sh` and `release-notes/`.

---

# Changelog - UI Enhancements (2026-06-22)

This file documents the changes made to the laptop codebase so that you can align the PC folder when you transfer it back.

## Summary of Changes

### 1. Alarm Snooze UI Indicators
*   **File Modified:** `tpms-app/app/src/main/java/com/example/tpms/ui/TpmsUI.kt`
*   **Enhancements:**
    *   Added dynamic checking of snooze status on each `TireCard` by comparing current time with the alarm key snooze timestamps in `TpmsManager.snoozedAlarms`.
    *   Implemented an active count-down timer using a Compose `LaunchedEffect` loop that updates the `currentTime` every second, triggering card UI updates.
    *   **Yellow Color Theme:** Changed the card background and border animations to transition to a pulsing gold/yellow theme when an active alarm is snoozed, indicating to the user that the alarm has been acknowledged.
    *   **Remaining Snooze Time Label:** Added a label showing `"snoozed for X minutes"` (or `"snoozed (Restart)"` if permanent) below the status text inside the card.

### 2. Settings Layout Adjustment (Dashboard Return Button)
*   **File Modified:** `tpms-app/app/src/main/java/com/example/tpms/ui/SettingsUI.kt`
*   **Enhancements:**
    *   Removed the scroll-bound "Return to Dashboard" button from the bottom of the Left Column.
    *   Wrapped the Settings screen layout in a `Box`.
    *   Placed the "Return to Dashboard" button in a fixed position at the **bottom-right corner** (`Alignment.BottomEnd`) so it is always accessible without needing to scroll.
    *   Added `60.dp` bottom padding to the Right Column so that its scrollable contents (steppers and action buttons) do not overlap with the new fixed return button.

---
*Note: Make sure to copy these modifications to your PC workspace to keep them aligned.*
