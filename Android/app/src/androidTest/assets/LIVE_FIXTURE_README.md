These fixtures were captured on 2026-09-21 by submitting the included, computer-generated
768 × 768 dish JPEG to Roboflow's `https://serverless.roboflow.com/{project}/{version}`
endpoint using the same base64 request body, Authorization header, and query parameters
as the Android app. Both requests returned HTTP 200.

- Dish model: `moldez_dish_finder/4`, confidence 0.3.
- Culture model: `moldez_segmentation/6`, confidence 0.4.
- Each response retains only image dimensions and semantic prediction masks/class data.
- No API key, inference identifier, account information, or private photograph is included.
- The instrumentation test replays these responses locally; it makes no network request.

The two masks and their confidence maps are 8-bit grayscale PNG images. Independent PNG
decoding found 280,916 dish pixels, 104,863 culture pixels before clipping, and 104,518
culture pixels inside the dish. These fixtures validate API/decoder compatibility and
the processing pipeline, not biological detection accuracy.
