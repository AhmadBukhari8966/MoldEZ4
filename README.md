# MoldEZ — Professional Culture Analysis Solution
*Truman State University · Version 4.0 · 2026*

MoldEZ is a desktop application for automated fungal culture analysis, developed under the TruScholars Summer Undergraduate Research Program at Truman State University. It performs computer vision-based detection and segmentation of mold colonies in petri dish images, quantifies growth, and produces structured PDF reports suitable for laboratory documentation.

---

## Download

**[https://moldez.vercel.app](https://moldez.vercel.app)**

The installer for Windows and macOS is available on the MoldEZ website above. No manual setup required.

---

## Overview

The application accepts standard laboratory dish photographs, applies CLAHE preprocessing to normalize image quality, and runs a trained segmentation model to identify colony boundaries and estimate coverage. Sequential samples can be analyzed as a time series to track growth progression. Results are exported as formatted PDF reports.

Supported image formats: JPEG, PNG, HEIC/HEIF. Images can be loaded via drag-and-drop or file selection.

## Dependencies

tkinter, Roboflow, OpenCV, Pillow, NumPy, SciPy, Matplotlib, ReportLab, pillow-heif

## Android

A native Android implementation for phones and tablets is available in [`Android/`](Android/README.md). It includes image and camera analysis, mask editing, named sessions, growth comparison, batch analysis, foreground timed capture, and PDF/CSV/session export. The supplied **MoldEZ-Android-1.0.1.apk** is preconfigured for the same shared Roboflow service as the desktop app; no API-key entry is needed. Detection requires internet and sends the selected photograph over HTTPS. See the Android README for build instructions and measurement compatibility details. The macOS desktop source additionally requires PySide6.

## Authors & Contributors

Mohammed Ayan Mahmood - Primary Developer, Department of Chemistry  
Dr. Kafi R. Rahman - Department of Computer and Data Sciences  
Dr. Hajeewaka C. Mendis - Department of Agricultural and Biological Sciences  

Contributors: M. Raahim, M. T. Ibn Alam, A. Bukhari, M. McGowin, H. Momeni, E. Thompson

## License

© 2026 Office of Student Research, Truman State University. See [LICENSE](LICENSE) for full terms.
