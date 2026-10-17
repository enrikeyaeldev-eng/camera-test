# Galaxy A56 Camera App — Build Plan

As of Oct 17, 2026. Source of truth for Claude Code. The owner also keeps an editable copy as a Claude doc.

> **Project status: closed (Oct 17, 2026).** All six phases are built or deliberately skipped (Phase 2: the A56 offers no Night or HDR extension). The last build adds background merging, GPU merging, frames from before the shutter press and zoom. Open ideas are kept under "Ideas for later" for a possible restart, together with the move to the `CameraA56` repository.

## Summary

The app will be a **multi-frame YUV stacking camera**, not a GCam-style RAW camera, because the A56's back camera exposes no RAW capture to third-party apps. The probe shows Camera2 level **LIMITED**, **RAW not supported**, and **BURST_CAPTURE supported**.

The goal is better processed images than a single frame: less noise in low light, more dynamic range and a more natural look. The final edit happens in Lightroom, so the app should hand over the cleanest, highest bit-depth file it can.

There are three ways to get image quality, tried in this order:

1. **Samsung's own Night and HDR processing** through the Camera Extensions API, if the A56 exposes it. Cheapest win.
2. **Our own burst stacking** of full-resolution YUV frames: align, merge, tone map. This is the core project.
3. **A clean single frame** with Samsung's sharpening and noise reduction turned down where the API allows.

Work starts with **Phase 0, a diagnostic app** that measures what the probe could not: burst speed, Extensions support, whether manual exposure is honoured, and hidden camera IDs. Every later phase depends on its results.

## Device facts

Measured with Camera2 API Probe on the owner's phone: **SM-A566E, Android 16 (API 36)**, One UI current as of October 2026. Only camera IDs 0 (back) and 1 (front) appeared; the ultrawide and macro were not listed.

| Fact (back camera, ID 0) | Value | What it means for the app |
| --- | --- | --- |
| Hardware level | LIMITED | No guarantee of per-frame manual control. Everything must be tested, not assumed. |
| RAW capture | Not supported | No DNG from the sensor. GCam-style RAW stacking is impossible. |
| Capabilities | BACKWARD_COMPATIBLE, BURST_CAPTURE, CONSTRAINED_HIGH_SPEED_VIDEO, STREAM_USE_CASE | BURST_CAPTURE promises at least 20 fps at max YUV size, which is what stacking needs. |
| Manual sensor capability | Not listed | Shutter and ISO control is not promised, yet AE mode "off" is offered. Phase 0 tests whether it is honoured. |
| Exposure compensation | -20 to +20, step 1/10 | ±2 EV bracketing works even if manual exposure does not. |
| Focus | Manual, auto, continuous, macro | Manual focus distance is available. |
| White balance | Off, auto and presets | White balance can be locked across a burst. |
| Locks | AE lock and AWB lock | Exposure and colour can be frozen for the burst. |
| Max output size | 4080 × 3060 (12.5 MP) | The 50 MP mode is not exposed; frames are binned 12.5 MP. |
| FPS ranges | 10 to 30 | A fixed 30 fps range is available for fast bursts. |
| Stabilization | Optical yes, video no | OIS helps each frame; alignment still needed between frames. |

Still unknown, measured in Phase 0: real burst fps at full size, Camera Extensions support, whether exposure time and ISO are honoured, available noise-reduction and edge modes, tonemap modes, Ultra HDR (JPEG_R) support, hidden physical camera IDs, and the front camera's details.

## Strategy

Without RAW, the best achievable result is merging several already-processed YUV frames, which beats any single frame on noise and dynamic range. The ceiling is lower than GCam on a Pixel, because each frame has already passed through Samsung's noise reduction, sharpening and tone curve.

| Path | Quality gain | Effort | Depends on |
| --- | --- | --- | --- |
| Samsung Extensions (Night, HDR) | Matches the stock app's night and HDR modes | Low: days | A56 exposing extensions to third-party apps |
| Own YUV burst stacking | Less noise, more highlight and shadow detail, our own look | High: weeks to months | Burst speed of at least about 10 fps at 4080 × 3060 |
| Clean single frame | Less oversharpening than the stock app | Low | Noise-reduction and edge modes we can turn down |
| RAW stacking (GCam style) | Highest | Not possible | RAW capture, which the A56 does not expose |

**Output for Lightroom.** A stacked result has more than 8 bits of real precision, so saving it as an 8-bit JPEG throws detail away. The app should save a 16-bit TIFF or a linear DNG of the merged image, plus a JPEG for quick sharing. A linear DNG built from YUV data is not a true sensor RAW: it is already demosaiced and tone mapped. It still gives Lightroom far more headroom than a JPEG. Ultra HDR JPEG is an option if Phase 0 shows JPEG_R support.

**Decision rule after Phase 0.** If Extensions are supported, build them first as a quick win. If full-size burst is at least about 10 fps, stacking is the main project. If burst is slow, stack at a lower resolution or accept fewer frames, and lean on Extensions.

## Tech stack and architecture

Kotlin with Jetpack Compose for the app, Camera2 for capture, and C++ through the NDK for image processing. Camera2 is required because stacking needs exact control of bursts and frame buffers that CameraX hides.

| Layer | Choice | Why | Trade-off |
| --- | --- | --- | --- |
| Language and UI | Kotlin, Jetpack Compose, single activity | Modern default; least boilerplate | Compose camera previews need an `AndroidView` wrapper for the `TextureView` or `SurfaceView` |
| SDK levels | minSdk 31, target and compile SDK 36 | Camera2 Extensions need API 31; phone is API 36 | Excludes very old phones, irrelevant here |
| Capture | Camera2 directly | Full control of burst requests, `ImageReader` buffers and per-frame settings | More code than CameraX |
| Samsung processing | Camera2 `CameraExtensionSession` | Night and HDR from Samsung, if exposed | Opaque: no control over its look |
| Processing | C++ (NDK, CMake, arm64-v8a), NEON | 12.5 MP × 8 to 15 frames is too heavy for Kotlin | JNI glue and native debugging are harder |
| Vision library | OpenCV for Android from Maven Central (prototype) | Pyramids, remapping and fusion ready-made | Adds roughly 20 to 30 MB to the APK; replace hot paths later |
| GPU (later) | OpenGL ES 3.2 or Vulkan compute | Faster merge, less heat | Only after the CPU version is correct |
| Saving | MediaStore into DCIM/A56Cam; own minimal 16-bit TIFF and linear-DNG writer in C++ | `DngCreator` only accepts real RAW frames, so it cannot be used | Small amount of file-format code to test carefully |

**Gradle modules.**

- `:app`: UI, navigation, settings, permissions, gallery hand-off.
- `:camera`: camera discovery, session and request building, preview, the frame ring buffer, burst capture, Extensions.
- `:processing`: JNI bridge plus C++ alignment, merge, tone mapping, sharpening and file writers.
- `:diagnostics`: the Phase 0 tests, kept in the final app as a hidden "Device report" screen.

**Memory budget.** One 4080 × 3060 YUV_420_888 frame is about 18.7 MB, so 8 frames are about 150 MB and 15 frames about 280 MB. Frames are copied out of `Image` buffers at once into native memory, because `ImageReader` holds only a few images and stalls the camera when full.

**Stacking pipeline (Phase 3).**

```
Camera stream ──► Ring buffer ──► Shutter press ──► Pick reference
(full-size YUV,   (recent frames,  (lock AE/AWB,     (sharpest frame)
 30 fps)           native memory)   take N frames)          │
                                                            ▼
Save ◄──────────── Tone map ◄────── MERGE ◄────────────── Align
(16-bit TIFF       (lift shadows,   (drop tiles         (tiles on a
 + JPEG)            sharpen)         that moved)          pyramid)
```

The merge is where the image-quality gain comes from; the steps before it exist to feed it well-aligned, identically exposed frames.

## Development workflow

Code is written and test-built in GitHub Codespaces by Claude Code; GitHub Actions builds the signed APK on every push and publishes it to a GitHub Release the phone can download directly. No computer, no Vercel.

**Codespace setup.**

- A `.devcontainer` with JDK 17 and the Android command-line tools, installed by a `postCreateCommand` script: platform 36, build-tools 36, a pinned NDK and CMake.
- Claude Code runs `./gradlew assembleDebug` in the Codespace to catch build errors before committing.
- A 4-core Codespace is enough. Gradle daemon memory set in `gradle.properties`.
- `.gitignore` excludes `build/`, `.gradle/`, `local.properties`, `*.jks`, `*.keystore` and `*.apk`. Build output is never committed.

**Cloud build (GitHub Actions).**

1. Trigger on push to `main`, on pull requests and manually.
2. Set up JDK 17, the Android SDK and NDK, with Gradle caching.
3. Decode the signing keystore from a repository secret into a temporary file.
4. Build the release APK, with `versionCode` taken from the workflow run number so each build installs over the last.
5. Upload the APK as a workflow artifact, and on `main` publish it to a rolling pre-release named `latest` with a stable download link.

**Signing.** All builds must be signed with the same key, or Android refuses to install an update over the previous version.

- Claude Code generates the keystore once in the Codespace with `keytool`.
- The owner adds four secrets in GitHub: repository **Settings → Secrets and variables → Actions → New repository secret**: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Alternatively `gh secret set` from the Codespace terminal, if the Codespace token allows it.
- Credentials are never pasted into any chat and never committed. The owner keeps a private backup of the keystore; losing it means uninstalling the app before the next install (photos in DCIM are kept).

**Installing on the phone.**

1. Open the repo's Releases page in the phone browser and download the APK from `latest`.
2. First time only: Settings → Apps → (the browser) → Install unknown apps → Allow.
3. If Play Protect warns about an unknown developer, choose to install anyway.

**Logs and debugging.**

- The app keeps its own rolling log file and a crash handler that saves the stack trace. A "Share logs" button sends them through the share sheet, so the owner can attach them to a Claude chat.
- Optional live logs without a computer: Developer options → Wireless debugging → Pair device with pairing code. In Termux: `pkg install android-tools`, then `adb pair localhost:<pair-port> <code>`, `adb connect localhost:<port>` and `adb logcat -s A56Cam`. Use split screen, because the pairing dialog closes when you switch apps.

## Roadmap

Six phases, each ending in an installable APK. Phase 0 ends with a hard stop: the owner runs the report on the phone and shares it before any camera features are built, because its results decide Phases 2 to 4.

### Phase 0: Project skeleton and diagnostic app

**Goal:** a working build pipeline and an app that measures everything the probe could not.

Setup:

- [x] Gradle project with the four modules, Kotlin, Compose, NDK and CMake wired up with a trivial JNI call that proves native code runs.
- [x] `.devcontainer`, `.gitignore`, GitHub Actions workflow, signing from secrets, rolling `latest` release.
- [x] In-app log file, crash handler and "Share logs" button.
- [x] Camera permission flow.

Static report (no capture needed), for every ID from `getCameraIdList()` and every physical ID under logical cameras:

- [x] Every `CameraCharacteristics` key and value, including vendor keys, written as JSON.
- [x] Stream configuration map: output sizes and minimum frame durations for YUV_420_888, JPEG, PRIVATE, HEIC, JPEG_R and RAW formats.
- [x] Available AE, AF, AWB, noise-reduction, edge, tonemap, hot-pixel and lens-shading modes.
- [x] Available capture-request and capture-result keys.
- [x] `CameraExtensionCharacteristics`: supported extensions per camera, their output sizes, capture latency range, and supported request keys.
- [x] Hidden IDs: try opening IDs 0 to 20 and a few high numbers not in the list; record which open and stream.

Live tests on the back camera, each saving frames and results:

- [x] **Burst speed:** request 15 full-size YUV frames with `captureBurst` and with a repeating request; record real fps from sensor timestamps and any dropped frames.
- [x] **Manual exposure:** AE off with fixed exposure times (1/1000 s to the longest allowed) and ISO values; check `CaptureResult` and image brightness to see whether they were honoured.
- [x] **EV bracketing:** compensation at -2, 0 and +2 EV; confirm brightness changes and how many frames it takes to settle.
- [x] **Processing modes:** capture with noise reduction and edge modes at off or minimal versus fast or high quality; save crops to compare.
- [x] **Extensions:** one capture in each supported extension, saved as JPEG.
- [x] **Ultra HDR:** one JPEG_R capture if supported.

Output: a `report.json`, a readable `report.txt` summary and a folder of test images, all in Documents/A56Cam/diagnostics, with a Share button.

**Done when:** the APK installs from the `latest` release, the report runs without crashing, and the owner has shared the report. **Stop here and wait.**

Status (Oct 17, 2026): **Done when met.** Build 5 installed from `latest`, the report ran in 129 s without crashing or logged errors, and the owner shared `report.json`, `report.txt` and the images.

#### Phase 0 results (run 20261016-195634, back camera ID 0)

| Question | Answer | Consequence |
| --- | --- | --- |
| Camera IDs | `0` back main, `1` front, **`2` back ultrawide** (1.74 mm, fixed focus, 4000 × 3000), `3` front (3472 × 2388). No hidden IDs among the 33 others tried. | The ultrawide is reachable after all; all four are LIMITED. |
| Burst speed at 4080 × 3060 YUV | ~29.8 fps with `captureBurst` or repeating PREVIEW (33.6 ms per frame); ~26–28 fps with the STILL_CAPTURE template. 0 capture failures. | Fast enough: **stacking is the main project** (decision rule). Use the PREVIEW template for burst frames. |
| Camera Extensions | Only FACE_RETOUCH and BOKEH. Both capture fine (~400 ms). **No NIGHT or HDR.** | **Skip Phase 2.** |
| Manual exposure | AE OFF is honoured where frames arrived: 66.7 ms and 100 ms at ISO 200, ISO 100 and 200 at 1/60 s, exact values in the results, brightness follows. Range 34.7 µs to **100 ms max**, ISO 50 to 2500. | Night mode can use manual exposure, but no single frame is longer than 1/10 s, so low light must come from more frames. |
| RAW, JPEG_R (Ultra HDR), HEIC | Not supported on any camera. | Output stays 16-bit TIFF, linear DNG and JPEG. |
| NR and edge modes | OFF, FAST and HIGH_QUALITY listed for both. | Whether they are honoured is **not yet measured**: see the bug below. |
| High-speed video | Back main: 1280 × 720 up to 240 fps. Ultrawide: none. | Relevant to the video idea below. |

**Second run (build 9, run 20261016-202217, all four cameras, 0 errors):** the 30 fps pairing bug is fixed (0 unpaired frames or results). The cause was per-byte YUV reads in Kotlin. Frame analysis still averages 41 to 50 ms per full-size frame even with bulk row reads, so a few images are still dropped in some burst variants. **Lesson for Phase 3:** never touch frame pixels from Kotlin; copy buffers straight into native memory and process in C++.

| Question (all cameras unless noted) | Answer | Consequence |
| --- | --- | --- |
| Manual exposure and ISO | Honoured exactly on **all four cameras**, from the first frame after the change (1 ms to 100 ms, ISO up to each camera's max). Brightness tracks both. | Per-frame manual exposure is usable for night stacking and for HDR brackets. Check it per frame inside one `captureBurst` in Phase 3. |
| EV compensation | Works but is slow: 8 to 17 frames (0.3 to 0.6 s) to settle. Back main: -2 EV gives 0.33× luma, +2 EV 1.52×. Front cameras at +2 EV did not brighten, because auto AE was already at ISO 3200 and 1/30 s indoors. | Bracket with manual exposure, not EV compensation. |
| Auto AE in dim light | Stays at 1/30 s and raises ISO (back main ISO 538 at 0 EV indoors, front ISO 3200). | Night mode should pick its own exposure: longer (up to 100 ms) at lower ISO. |
| Noise reduction | OFF and FAST give the same result (back main crop gradient 16.0 vs 16.1). HIGH_QUALITY smooths strongly (9.6). | Stacking input: NR OFF. FAST is no cleaner, so it is free. |
| Edge (sharpening) | OFF and FAST are the same; HIGH_QUALITY sharpens hard (gradient 16 → 36). | Stacking input and the clean single frame: edge OFF. Sharpen ourselves after the merge. |
| Burst | 29.7 to 29.9 fps on every camera with the PREVIEW template; front ID 3 also with STILL_CAPTURE. | Confirms the PREVIEW template for burst frames. |
| Extensions on front cameras | FACE_RETOUCH and BOKEH work but take ~2.9 s (back: ~0.4 to 0.5 s). | Not needed; Phase 2 stays skipped. |

### Phase 1: Basic camera

- [x] Full-screen preview with correct aspect ratio and orientation.
- [x] Tap to focus and meter; exposure-compensation slider; AE and AWB lock.
- [x] Shutter button saving a single JPEG through MediaStore into DCIM/A56Cam.
- [x] Settings screen; front and back switching.

**Done when:** the app takes reliable single photos and survives pause, resume and rotation.

Status (Oct 17, 2026): built and passing CI (with unit tests for orientation, tap mapping and preview size), **waiting for the owner's phone test**. Notes:
- The UI is locked to portrait like the stock camera; photo orientation follows how the phone is held (JPEG orientation from the device sensor).
- Lenses: back 1x (ID 0) and 0.6x (ID 2); front main (ID 1) and 1.2x (ID 3), labelled from 35 mm-equivalent focal lengths.
- Settings: sharpening (default **Off**, per Phase 0), noise reduction (camera default), JPEG quality (95). The Phase 0 report lives in Settings > Device report.

### Phase 2: Samsung Extensions (only if Phase 0 found them) — skipped: no NIGHT or HDR extension

- [ ] Night and HDR modes through `CameraExtensionSession`, with a progress indicator while Samsung processes.

**Done when:** results visibly match the stock app's equivalent modes.

### Phase 3: Burst stacking, first version

- [x] Frame ring buffer fed by a repeating full-size YUV request, so frames from just before the shutter press can be used. (The last 3 preview frames stay in the ImageReader, no copying, with their exposure from the capture results. They are brightness-matched to the darker burst in linear light, tiles they had clipped get no weight, and the reference is always a burst frame; the burst takes 3 fewer frames after the press. Host test: 2 pre-press frames cut noise 23 % with highlights intact. Burst frames are matched by sensor timestamp so stray preview frames are never merged. Setting: on by default.)
- [x] On shutter: lock AE and AWB, take N frames (start with 8), underexposed by about 1 EV to protect highlights. (Manual exposure per frame, from the preview's auto exposure; camera NR and sharpening off.)
- [x] Pick the sharpest frame as reference, measured by gradient energy. (Noise-floor-corrected, weaker-direction, 75th-percentile block energy, so noise and a single moving object don't decide.)
- [x] Align the others with a coarse-to-fine tile search on a luma pyramid, as in Google's published HDR+ approach. (5 levels, 16 px tiles, parent and neighbour candidates; integer-pixel.)
- [x] Merge with a robust per-tile weighted average that rejects tiles that moved. (32 px tiles with sin² overlap; weight from mismatch vs. the median noise level.)
- [x] Tone map to lift shadows back, light sharpening, save a 16-bit TIFF plus a JPEG.
- [x] Debug mode that saves the source frames and an alignment overlay. (Merge map PNG: green = merged, red = rejected as moved; stats in Documents/A56Cam/debug.)

**Done when:** side-by-side with a single frame, the stacked image has visibly less shadow noise with no ghosting on still scenes, and processing takes under about 5 seconds.

Status (Oct 17, 2026): built, waiting for the owner's phone test. Host tests on synthetic frames: hand-shake recovered exactly up to 40 px, noise 8.0 → 2.7 with 8 frames (√8 predicts 2.8), no ghost from a moving object. Processing 8 × 12.5 MP takes 1.9 s on a 4-core desktop; on the phone it is measured in the logs (`alignMs`, `mergeMs`). Owner feedback before stacking: our single-frame colour is preferred to Samsung's, Samsung's noise reduction is better; stacking is the answer to that.

### Phase 4: Night and HDR modes of our own

- [x] Night: more frames (12 to 20), longer exposures if Phase 0 shows manual exposure works, motion-aware frame rejection. (8/12/16 frames at -0.5 EV; the exposure grows only until ISO 200, at most 1/10 s, so lit rooms do not blur; frames under 70 % of the sharpest are dropped.)
- [x] HDR: bracketed frames merged with exposure fusion. (Built as -2/0/+1.5 EV Mertens fusion, then replaced after the owner's test by the HDR+ approach: 10 identical frames at -2 EV merged like Stack, 4× shadow lift with local tone mapping. The fusion code stays in the native core, tested.)
- [x] Linear DNG writer for Lightroom; Ultra HDR JPEG if supported. (LinearRaw DNG with sRGB colour matrix and BaselineExposure; Ultra HDR is not supported by the camera, per Phase 0.)

**Done when:** in low light the result beats the single frame and is competitive with the stock Night mode on noise.

Status (Oct 17, 2026): built and tested by the owner indoors (backlit and front-lit selfies, tuned against Samsung shots). A dark-room comparison with the stock Night mode was not done before the project closed.

### Phase 5: Polish

- [x] Manual focus, white balance and EV controls; grid; timer; volume-key shutter. (Volume, camera, Enter and media keys, so most Bluetooth remotes work too.)
- [x] Background processing so the camera is ready for the next shot. (Captured bursts are merged on one background thread; the shutter is free right after capture, up to 2 bursts queued, about 300 MB each; the thumbnail shows a spinner.)
- [x] GPU acceleration of the merge if speed or heat is a problem. (OpenGL ES 3.1 compute for the tile mismatch and weighted accumulation; same result as the CPU to 0.0001 levels on Mesa in CI. A self-test against the CPU runs at start; any failure falls back to the CPU. Setting: on by default. Phone timings: `engine` and `mergeMs` in the logs.)
- [x] Zoom. (Pinch to zoom and a 2x chip next to the lens chips, through CONTROL_ZOOM_RATIO for preview, Photo and bursts.)

## Known issues

- **Green photos with "High quality" noise reduction or sharpening (Photo mode).** One owner photo had green 19× blue and was 2.5 stops darker than a normal shot, with auto WB in EXIF. Samsung's HQ still path seems to skip white balance in some light. Every Photo-mode shot now logs AWB/colour metadata and the photo's mean RGB; the owner should take an HQ and an Off shot of the same scene and share the logs. Burst modes always request NR and edge Off, so they are not affected.

- **HDR glitches (fixed, build after 16):** owner photos showed double images (laptop screen) and blocky patches (lamp glow, bedsheet). Causes: no deghosting in exposure fusion, and per-tile shifts on surfaces without detail. Fixes: HDR uses one shift per bracket from detailed, well-exposed tiles; flat tiles in Stack/Night take the frame's overall shift; ghosted areas take the reference's content (brightness-matched) before fusion weights are computed. Host test: a moving bright object now adds 0.04 levels instead of 27.

- **HDR redesigned (owner test, Oct 17):** bracket fusion gave halos and heavy grain indoors: the +1.5 EV bracket hit 1/10 s at ISO 2,700–3,200 on the front camera. HDR now uses the HDR+ approach: 10 identical frames at -2 EV, merged like Stack, then 4× shadow lift with highlight roll-off. The bracket-fusion code stays in the native core (tested) for a possible daylight mode.
- **Green photo, follow-up:** the logs show that shot was taken with the EV slider at -2.0 (and HQ sharpening), which explains its darkness; the colour cast is still unconfirmed. Later Photo-mode shots were all normal.
- **Owner goal (Oct 17): the iPhone look of the photo itself.** The new "Bright" look (default for burst modes): lifted shadows and midtones, mild S-curve, +8 % saturation, white balance pulled 25 % toward neutral (warm light stays warm, less orange). Stack is now the default mode, since iPhones always shoot multi-frame.
- **Build 21 look tuning (Oct 17), against a Samsung shot of a backlit selfie (lamp behind the subject).** Measurements showed our faces were as bright as Samsung's, but the global Reinhard curve darkened midtones 15–50 % (grey walls and shirt), the lamp was blown, and skin was too red (R−G 50 vs 27). Changes to the Bright look: local tone mapping (fast guided filter on log luma, 16 px cells: areas below 0.3 lifted up to 1 stop, areas above 0.25 held back by at most the burst's headroom, log2(gain), so clipped lamps stay a white glow instead of turning grey); a global curve that stays linear up to 0.4 with a shoulder reaching white at the gain; saturation 0.92; WB pull 50 %. Tuned offline on the owner's debug frames (HDR, Stack, Night). Tried and dropped: per-pixel and per-area protection of clipped highlights, which made hard-edged white blobs.
- **Build 23 tuning (Oct 17), front-lit selfie on the front 1.2x camera vs the Samsung shot.** Build 22 made front-lit scenes flat: faces over-lifted (cheek 173 vs Samsung 113), marble walls grey (163 vs 182), and the white shirt blue (B 205 > R 191) because grey-world WB over the warm wall over-corrected. Changes: lift 40 % below 0.3, hold back 60 % above 0.45; the bright look sits ¼ stop under auto (only where the burst has headroom); saturation 0.82; WB from bright near-neutral pixels (Y 170–235, chroma < 12), 70 % pulled. Result: cheek 154, wall 180/168/155, shirt neutral; the backlit lamp scene keeps the face at Samsung's level (132).
- **Night blur fix (build 23).** In a lit room Night chose 88 ms at ISO 40; 6 of 16 hand-held selfie frames were rejected and the rest smeared the marble. Night now lengthens the exposure only until ISO 200 (never below auto, at most 100 ms).

## Ideas for later (not scheduled)

Kept for a possible restart of the project (closed Oct 17, 2026).

- **Video recording.** The roadmap is stills-only so far. Phase 0 shows 1280 × 720 high-speed video up to 240 fps on the back main camera, no video stabilisation mode (OIS only), and full-size YUV at 30 fps. A basic video mode (MediaRecorder or MediaCodec on a Camera2 session, with the same AE/AWB locks) fits naturally after Phase 1. Stacked or denoised video is a much larger project.
- **Bluetooth remote shutter.** Done through the Phase 5 key handling: volume, camera, Enter and media keys trigger the shutter, which covers remotes that act as a keyboard or media button. Still to test with the owner's remote. Only if a remote needs its own protocol: BLE (Bluetooth permissions, pairing UI), as a separate task.

- **Moving to the CameraA56 repository.** The owner created `enrikeyaeldev-eng/CameraA56` as the follow-up home of this project. Work stays in `camera-test` until the app is finished, then the move is reconsidered. Moving needs the same four signing secrets re-added there from the owner's private keystore backup (never a new key), and the download link changes.
- **Warm lamp look.** The owner's Lightroom edit of the Samsung backlit shot: about 0.7 stops darker overall, the lampshade a warm cream (~204) with visible texture, a warm glow on the wall. Our frames show the shade as neutral white (the camera's white balance neutralises the lamp), so the warmth must be added as a look (warming the brightest areas, like split toning); darkening the whole picture made the shirt muddy.
- **Finer detail.** Samsung's shots still show crisper marble veins and hair. Candidates: sub-pixel alignment and a merge that keeps more detail (HDR+'s frequency-domain merge), then sharpening tuned on the merged image.
- **Dark-room Night test** against the stock Night mode, the one Phase 4 "done when" check not yet made.
- **Phone timings for the GPU merge** (`engine` and `mergeMs` in the logs) and the battery cost of keeping full-size preview frames for the pre-shutter ring.

## Risks, effort and skills

The biggest risk is that the finished app does not clearly beat Samsung's own camera, which processes the sensor's RAW data internally. Realistic wins are less noise in dim scenes, a less oversharpened look, and files with more headroom for Lightroom.

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| Stacked YUV not clearly better than the stock app in daylight | High | Judge success on low light and natural look, not daylight |
| Full-size burst too slow for stacking | Medium | Stack fewer frames or at a lower size; rely on Extensions |
| No Camera Extensions on the A56 | Confirmed: only FACE_RETOUCH and BOKEH | Phase 2 is skipped; no other work depends on it |
| Manual exposure ignored (LIMITED device) | Medium | Bracket with exposure compensation instead |
| Ultrawide and macro unreachable | Resolved for the ultrawide (ID 2, Phase 0); no macro found | Ultrawide can be offered as a second back camera |
| Memory pressure or heat during merges | Medium | Native buffers, fewer frames, GPU path in Phase 5 |
| One UI update changes camera behaviour | Low to medium | Re-run the diagnostic report after each major update |
| Ghosting on moving subjects | High at first | Robust merge with tile rejection; fewer frames for moving scenes |

**Effort, with Claude Code doing most of the coding.** Phase 0: a few sessions. Phases 1 and 2: one to two weeks of evenings. Phase 3: several weeks, mostly testing and tuning on real scenes. Phase 4 onward: open-ended. Image-quality tuning takes far longer than writing the code.

**Skills the owner needs.** Reading Kotlin well enough to review changes; basic Git; patience to shoot the same test scenes repeatedly and compare results carefully. Helpful but learnable along the way: image-processing basics (noise, dynamic range, alignment) and how to read a crash log.

## Rules for Claude Code

Work one phase at a time, keep `main` always installable, and stop at every phase boundary for the owner to test on the phone.

- Read `docs/PLAN.md` before each session and tick tasks off in it as they are completed.
- Small commits with clear messages; push to `main` only when `./gradlew assembleDebug` succeeds in the Codespace (or, if the SDK cannot be installed there, when the GitHub Actions build passes on the branch).
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, with types `feat`, `fix`, `docs`, `build`, `ci`, `refactor`, `test`, `chore` and scopes such as `app`, `camera`, `processing`, `diagnostics`. Work directly on `main`; do not create feature branches.
- Never commit build output, keystores, passwords or `local.properties`. Never ask the owner to paste credentials into a chat.
- Never assume a camera feature exists: check `CameraCharacteristics` at runtime and degrade gracefully, logging what was missing.
- All camera work on a dedicated background thread; never block the UI thread; close cameras and sessions in `onPause`.
- Copy frames out of `Image` objects immediately and close them; never hold more than the `ImageReader` limit.
- No GPL code (for example Open Camera). Permissive licences only (Apache 2.0, MIT, BSD), recorded in a `THIRD_PARTY.md` file.
- Log every camera error with the camera ID and request settings, under the log tag `A56Cam`.
- At the end of each phase, report: what was built, the release link, what the owner should test on the phone, and anything that did not work.
