# MoldEZ for Android

A native Kotlin/Jetpack Compose companion to MoldEZ Mark IV, built for phones and tablets. Android 8.0 (API 26) or later is required. Version 1.0.1 comes preconfigured for the project's existing Roboflow dish and culture models and stores photographs, masks, measurements, and sessions locally.

## Included workflows

- Import a photo or capture one with the device camera.
- Detect dish and culture segmentation using configurable Roboflow model IDs and confidence thresholds.
- Apply optional CLAHE contrast enhancement.
- Inspect overlays with pinch zoom and pan; draw/erase the culture mask inside the dish, with undo/redo.
- Calculate coverage and area using Windows adjustments by default.
- Save multiple named sessions, reopen saved masks, compare growth, and view a history chart.
- Analyze selected photographs as a batch, with a success/failure summary and cancellation.
- Capture a camera time series while the Capture screen remains open.
- Export a paginated PDF report, CSV measurements, a PNG overlay, or a portable session bundle.
- Import an Android session bundle with its source images and edited masks intact.
- Switch between light and dark appearance; layouts adapt to phone and tablet windows.

## First run

1. Install **MoldEZ-Android-1.0.1.apk**, then open **MoldEZ**. No API-key setup is needed.
2. Open **Analyze**, choose or capture a photograph, enter the dish diameter, and tap **Run detection**.
3. Review the overlay. Use **Edit mask** if needed, then **Save analysis**.
4. Open **Sessions** to compare, export, or organize your results.

Detection uploads the selected image to Roboflow over HTTPS and requires internet. The supplied APK uses the same shared project service as the desktop app, including access to `moldez_dish_finder/4` and `moldez_segmentation/6`. Requests use the project's inference allowance; model access and availability depend on that service. Credentials are excluded from session and report exports.

## Measurement behavior

The requested default is **Match Windows measurements**:

- Effective diameter = entered diameter + 3 mm.
- Dish area = 3.14159 × (effective diameter / 2)².
- Culture area = culture/dish pixel ratio × dish area.
- Reported coverage = culture/dish pixel ratio × 105.

As in Windows manual analysis, a fully covered dish reports 105%. Results and reports also retain the raw 0–100% pixel coverage. The entered diameter and Windows calibration are applied consistently to manual, batch, and timed analyses; the desktop application's separate hardcoded 100 mm batch/automation behavior is not reproduced. **Standard** calibration remains available in Settings.

Model versions, thresholds, contrast settings, diameter, and calibration are stored with each analysis. Altering model or preprocessing settings invalidates the current unsaved detection. Edited masks persist in saved analyses and exported sessions.

The same photo can produce small segmentation differences compared with desktop because Android normalizes orientation, caps the longest image side at 1600 pixels, compresses the request, and implements CLAHE natively. This app does not claim validated scientific equivalence across cameras or preprocessing implementations.

## Sessions and exports

Android sessions use versioned JSON inside `.moldez.zip` archives, including each source photo, dish mask, culture mask, and overlay. Imports validate paths, sizes, dimensions, and pixel counts before accepting data. No executable object serialization is used.

Desktop `.ez` / `.MoldEZ` pickle files are **not directly compatible** with this format. Existing desktop photos can be imported and reanalyzed. Desktop sessions do not consistently include masks or portable photographs; exact migration would need a separate desktop conversion workflow.

Comparison defaults to analysis timestamps. Use the manual elapsed-time option for photographs taken earlier. Compare measurements with the same diameter and calibration. The radial growth figure is the change in equivalent-circle radius, not direct tracking of a colony edge.

## Camera time series

Grant camera permission, set an interval, and tap **Start time series**. The first capture is immediate; each later interval begins after the preceding analysis finishes. The display stays awake while the schedule runs. Leaving the app or the Capture screen stops the schedule. Already completed analyses remain saved. This avoids unsupported background camera behavior and overlapping requests.

Photographs are copied into app-private storage; source files selected from the gallery are never deleted. Uninstalling the app removes its private data, so export sessions you want to keep. JPEG, PNG, and other formats supported by the device image decoder can be selected; HEIF support depends on the Android version/device.

## Build from source

Open this `Android` directory in Android Studio. Install Android SDK Platform 36 and Build Tools 36.0.0, and use JDK 17 or 21. The project pins Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21, and Compose BOM 2025.11.01.

The build uses `MOLDEZ_ROBOFLOW_API_KEY` when set. In the full repository, it otherwise reads the existing `ROBOFLOW_API_KEY` assignment from `../MacOS/MacOS.py` and injects it into the generated Android build configuration. The standalone source archive does not contain that desktop file, so maintainers building the archive must set `MOLDEZ_ROBOFLOW_API_KEY` to the project service key in their build environment. The supplied APK is already configured and requires no user credential entry.

There is no second hand-written key in the Android source files. A credential bundled into an APK can be extracted from that APK; it should be treated as a shared client credential.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

With an emulator or test device connected:

```sh
./gradlew connectedDebugAndroidTest
```

Installable test APK: `app/build/outputs/apk/debug/app-debug.apk`.

The debug APK uses a development signature. To publish or distribute a production release, configure signing with the project's own protected release key, build a signed release APK/AAB, and complete the chosen store's listing and account requirements. No production signing identity or store account is embedded in this project.

## Project layout

| Area | Responsibility |
| --- | --- |
| `core/Models.kt` | Immutable analysis records, settings, measurements and comparisons |
| `core/AnalysisEngine.kt` | Cloud inference, segmentation parsing, CLAHE and overlays |
| `data/SessionStore.kt` | Image import, atomic persistence, portable session archives |
| `data/AppPreferences.kt` | Persisted analysis settings and appearance |
| `data/ReportExporter.kt` | PDF and CSV reports |
| `ui/MoldEZViewModel.kt` | Cancellable workflows and consistent application state |
| `ui/MoldEZApp.kt` | Adaptive analysis, editing, sessions and settings screens |
| `ui/CaptureScreen.kt` | Camera preview and foreground time series |

See [validation notes](docs/VALIDATION.md) for the actual checks performed and remaining validation boundaries. The original repository's [license](../LICENSE) applies; original project credits appear in the app.
