# Android validation

This file records verification of the Android implementation. It distinguishes local implementation tests from scientific validation of the remote models.

## Verification scope

- Kotlin/Android compilation and APK packaging.
- Unit tests for Windows and standard formulas, invalid inputs, comparisons, segmentation payloads, mask scaling/clipping, CLAHE, error sanitization, image normalization, session persistence and import/export safety.
- PDF/CSV report generation tests.
- Android lint.
- Emulator navigation and lifecycle tests on phone and tablet configurations, where available.

## Version 1.0.1 — preconfigured service

The privately retained Version 1.0.1 APK bundles the existing project Roboflow credential at build time and requires no API-key entry or service setup. The public preview contains source and documentation only; no APK is provided as a release asset or CI artifact. In a full repository checkout, the build reuses the existing desktop assignment unless `MOLDEZ_ROBOFLOW_API_KEY` is set. The public standalone source archive excludes that desktop file and the raw credential, so archive builds require that environment variable.

Local verification completed September 21, 2026:

- Debug and optimized unsigned release APKs build; all 32 unit tests pass; Android lint reports zero errors (23 advisory warnings).
- All six installed-app tests pass on both phone and tablet emulator configurations after clearing app data. Startup checks confirm bundled model access and the absence of API-key fields, save/remove-key controls, and setup prompts.
- Installing 1.0.1 over the privately delivered 1.0.0 APK preserves the seeded sessions file byte-for-byte. Version code is 2 and the development signing certificate is unchanged.
- An exact-byte check confirms the APK contains the same credential as the existing Python assignment, without displaying it. All three inference workflows use that build configuration; no new live cloud request was made for this update.
- Refreshed phone/tablet light, dark, and Settings screenshots confirm the removed setup UI. The source archive excludes generated build files and the raw credential; standalone builds use the documented environment variable.

## Version 1.0.0 results — September 21, 2026

- **32 unit tests passed:** 14 analysis-engine tests, 8 measurement/comparison tests, 9 storage tests, and 1 CSV test.
- **Android lint passed with zero errors.** Advisory warnings include style/dependency-update suggestions; dependency versions are pinned deliberately.
- **All six installed-app tests passed on both phone and tablet configurations of the Android 16 / API 36 emulator:** startup, navigation/calibration controls, session recreation, restore/edit/undo/redo/save workflow, replay of live segmentation responses, and native PDF export/rendering.
- Phone layout checked at 1080 × 2400 pixels / 420 dpi; tablet layout checked using the same emulator at 1920 × 1200 / 240 dpi. These are emulator configurations, not physical hardware.
- A 21-analysis session produced a **23-page PDF**. Native Android rendering and visual inspection checked the first/last pages and pagination. Pages have an explicit white background.
- Both Roboflow model endpoints returned **HTTP 200** for an authorized, computer-generated test photograph. No real laboratory photographs were uploaded.
- Actual response replay on Android matched independently decoded counts exactly: **280,916 dish pixels; 104,863 culture pixels; 104,518 culture pixels inside the dish**. This validates integration/decoding, not biological accuracy.
- During the initial 1.0.0 validation, the existing repository credential was used only in memory for those authorized requests. Exact-byte scans then found it absent from Android source, retained test fixtures, and the 1.0.0 APK. Version 1.0.1 deliberately changes the APK configuration to include the project credential, at the user's request.
- Debug APK and optimized unsigned release APK compile successfully. The locally tested APK uses a development signature.

The public release's `SHA256SUMS.txt` covers its source archive and source guide, not the private APK. Build and test reports remain under `app/build/reports/` in the local working copy. Screenshots are in `docs/screenshots/`.

## Hosted CI status

GitHub Actions explicitly installs the required Android SDK, then verifies the build, unit tests, and lint. Current hosted results are available on the [Android branch's Actions page](https://github.com/AhmadBukhari8966/MoldEZ4/actions?query=branch%3Aandroid-app). The recorded results above are local build and emulator results. CI exposes logs and check status without uploading APKs, report artifacts, or build caches.

## Boundaries

- Cloud predictions require internet and access to the configured Roboflow models. A generated APK contains its configured client credential, which is extractable from that APK. The preconfigured APK is kept private; the public source archive excludes the raw credential.
- Emulator testing cannot establish physical-camera performance, behavior on every manufacturer's Android build, or scientific accuracy across real laboratory photographs.
- The user selected Windows diameter/coverage adjustments. Android applies them consistently across all modes, while desktop batch/automation uses a different hardcoded diameter.
- CLAHE and image decoding are native Android implementations; precise mask equivalence with OpenCV/Pillow must be evaluated with a representative photograph set.
- Existing desktop pickle sessions are not imported. Android's portable session format preserves both photographs and edited masks and is intentionally data-only.
- Production signing and app-store publication are separate from the installable development build.
