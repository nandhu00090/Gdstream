# GDStream

GDStream is a personal Android and Android TV / Fire TV streaming app built to play high-bitrate movies and TV series directly from a Google Drive index.

Instead of downloading 20GB+ REMUX files or manually extracting multi-episode `.zip` and `.rar` archives just to watch something, GDStream parses file headers on the fly, pulls metadata from TMDB, inspects actual audio/video tracks before playback, and streams directly over HTTP range requests.

---

## Why I Built This

Most cloud media players either choke on large `.zip`/`.rar` packs, fail to pass through Dolby Vision / TrueHD Atmos properly, or require running a heavy 24/7 server like Plex or Jellyfin. I wanted a lightweight native Android client that works smoothly on both a phone and an Amazon Fire TV Stick remote, handles raw Drive folders and archives natively, and shows real technical specs before hitting play.

---

## What It Does

### 1. Direct `.ZIP` & `.RAR` Streaming (No Extraction Needed)
* **Remote Header Inspection:** Reads only the Central Directory (ZIP) or block headers (RAR5 / STORE) using HTTP `Range` requests instead of downloading the entire archive.
* **Local Loopback Proxy (`127.0.0.1`):** Runs a lightweight multi-threaded HTTP proxy inside the app. Both the built-in ExoPlayer and external players (Just Player, VLC, MX Player) stream and seek directly through this local endpoint.
* **Compressed ZIP MKV Fix:** Neutralizes tail `Cues` and `Tags` seek pointers in the opening bytes of Matroska streams inside `DEFLATE` ZIPs so ExoPlayer starts playback immediately instead of trying to seek to the end of a multi-gigabyte compressed stream.
* **Instant Re-Opens:** Caches parsed archive file tables on disk (`gdstream_zip_index_v2`) so previously opened archives load in 0ms.

### 2. Real MediaInfo Specs Before Playback
Before you start streaming, GDStream probes the file header and displays:
* **Video:** Resolution (`4K`, `1080p`, etc.), codec (`HEVC`, `AVC`), HDR profile (`Dolby Vision`, `HDR10`, `SDR`), and frame rate.
* **File & Runtime:** Exact file size and duration.
* **Audio Tracks:** Lists every embedded audio stream with channel layout and codec (`Dolby TrueHD with Atmos`, `DD+ 5.1`, `DTS-HD MA`, etc.).
* **Compact Subtitles:** Groups duplicate language/format tracks (e.g., `Spanish (SRT) ×4`) and collapses long lists with a `+N more` toggle so 40+ subtitle tracks don't clutter the screen.

### 3. Smart Series & Multi-Version Library
* **Owned-Season Badges:** Calculates season tags (`S04`, `S02-S03`) strictly from the folders and files actually present in your Drive—ignoring unreleased or missing seasons on TMDB.
* **16:9 Episode Cards:** Inside series folders and archives, cards switch to 16:9 scene thumbnails (`still_path`) from TMDB along with actual episode titles, synopses, and a **Next Episode** quick-switch button on the detail screen.
* **Multi-Version Grouping:** Automatically groups multiple encodes of the same movie (for example, a `4K DV` version and a `1080p WEB-DL` version) under one poster card with an interactive version picker.

### 4. Dual Phone & Fire TV Remote Support
* **Adaptive Layout:** Renders a 2-column touch layout on phones and a 5-column grid on Android TV / Fire TV.
* **D-Pad Focus Management:** Uses a custom layer-based focus tracker (`FocusLayerContext`) and focus watchdog so Fire TV remote navigation stays predictable across overlays, modals, and horizontal rows.

### 5. Playback, Trailers & Offline Downloads
* **In-App & External Players:** Built on ExoPlayer (`react-native-video`) with audio/subtitle track switching, playback speed, and resume tracking (`Continue Watching`), plus one-tap handoff to external players.
* **Verified Official Trailers:** Resolves official trailers in-app in a chromeless landscape player while filtering out fan edits, reviews, and shorts.
* **Watchlist & Downloads:** Includes a local `My List` bookmark system and a background multi-threaded downloader that saves files straight to `Download/GDStream`.

---

## How It's Built (Codebase & Stack)

The project is written in **TypeScript**, **Kotlin**, and **C++**:

* **`App.tsx` (React Native + TypeScript):** Handles the entire UI, filename parser (`parseMediaFilename`), multi-version grouping, owned-season calculation, TMDB metadata & episode still caching, Continue Watching state, and the TV D-Pad focus system.
* **`android/app/src/main/java/com/gdstream/SecurityVaultModule.kt` (Kotlin):** The native backbone of the app. Runs the `127.0.0.1` archive HTTP range proxy, parses ZIP/RAR structures, probes MediaInfo tracks, verifies YouTube trailer streams, and manages background file downloads.
* **`android/app/src/main/cpp/` (C++ JNI — `libgdcore.so`):** Native library that keeps endpoint URLs, shield tokens, and session cookies isolated from the JavaScript runtime.
* **`scripts/patch-rnv.js` (Node.js):** Post-install script that patches `react-native-video`'s `ReactExoplayerView.java` for better Dolby Vision / HDR track selection and subtitle handling, and wires up CMake for `libgdcore.so`.
* **`AsyncStorage` + Local Disk Cache:** Persists TMDB metadata (`meta_v8`), watch progress, watchlist items, and archive directory indexes locally for fast startup.

---

## Project Layout

    Gdstream/
    ├── App.tsx                                    # UI, filename parser, TMDB enrichment, player & TV D-Pad focus
    ├── android/
    │   └── app/
    │       ├── src/main/
    │       │   ├── java/com/gdstream/
    │       │   │   └── SecurityVaultModule.kt     # Local HTTP proxy, ZIP/RAR parser, MediaInfo & download engine
    │       │   └── cpp/                           # Native C++ JNI module (libgdcore.so)
    │       └── build.gradle                       # Android NDK & release signing config
    ├── scripts/
    │   └── patch-rnv.js                           # Post-install ExoPlayer DV/HDR & CMake patcher
    └── .github/workflows/                         # GitHub Actions CI workflow for release APKs

---

## Building

### GitHub Actions (Automated)
Pushing to `main` automatically runs the Gradle release workflow, compiles `arm64-v8a` and `armeabi-v7a` native binaries, signs the APK, and uploads the build.

### Local Build

    # Install packages and apply native ExoPlayer patches
    npm install

    # Compile release APK
    cd android && ./gradlew assembleRelease
