# Blu-ray playback verification

Registered ISO `.m2ts` URLs use `BluRayTsExtractor`; ZIP/RAR and other media keep
Media3's normal extractors. The local proxy strips only the four-byte M2TS prefix,
retaining the original HDMV PMT and audio payloads for external players.

Audio uses the bundled Media3 1.8.0 Jellyfin FFmpeg extension with renderer mode
PREFER for DTS/DTS-HD. The HDMV LPCM reader converts packetized PCM into standard
raw PCM. DTS core and HD extension bytes remain in one access unit. The selector
lists and selects tracks across both software and platform audio renderers, with
language, codec and channel labels from CLPI/elementary headers.

CLPI CPI coarse/fine entries supply 45 kHz timestamp-to-SPN points. The exposed
byte position is `SPN * 188`, using Long arithmetic; ISO extent mapping remains
in the proxy. An indexed extractor suppresses Media3's PCR duration scan and
binary-search seeker. A seek directly reopens the proxy at an access point;
normal bounded chunk prefetch still occurs after that reopen.

Indexes are installed for a selected single-clip feature with one timestamp
epoch. Missing/malformed CPI, spliced multi-clip playlists, trimmed play items
and multiple STC epochs retain PCR seeking instead of using an incorrect map.
The native ISO index cache is version 5 so older entries are rescanned. Signed
upstream redirect URLs are still not persisted.

External handoff unmounts the internal view, cancels its existing reads before
launch, and retains the listener and entry registration. The UI stays in
external mode to suppress fresh metadata scans. HEAD returns the exact full
virtual Content-Length and no body; OPTIONS returns 200 with Allow, Accept-Ranges
and Content-Length: 0 without reading the origin. Subtitle filename mismatches
remain 404. The service allows 120 seconds for the chooser/initial handshake;
once media bytes flow, 15 seconds without media shuts it down. Header-only
probes do not extend either deadline. Recents/Stop cleanup remains unchanged.

## Automated checks

```
node scripts/patch-rnv.js
npx tsc --noEmit
npm test -- --runInBand
cd android
./gradlew :app:compileDebugKotlin :react-native-video:compileDebugJavaWithJavac :app:testDebugUnitTest
./gradlew :app:mergeDebugNativeLibs
```

The JVM tests include real HDMV TS demuxing with both LPCM/DTS tracks, fragmented
LPCM frames, DTS-HD core/EXTSS assembly, selector switching across renderers,
CLPI pointers and >40 GB offsets, and a direct indexed seek with zero
`RESULT_SEEK` PCR probes. Loopback tests cover external HEAD/OPTIONS/404, retained
active registration, exact ranges, abort/permit release and ZIP/RAR regression.
Robolectric supplies Android framework behavior for extractor tests; it does not
execute Android JNI audio decoding on the host JVM. The sandbox used a temporary
Maven mirror and a writable test `user.home` for dependency downloads.

## Device validation

Use the original BD50 ISO and export `gdstream_debug.log.txt` after:

1. Selecting each Tamil track and hearing audio while the video clock advances.
   Expect `ISO_FFMPEG_READY available=true dtsHd=true`, two `ISO_AUDIO_FORMAT`
   records, and `ISO_AUDIO_USER_SELECT` when switching tracks.
2. Seeking near the start, middle and end. Expect `ISO_CLPI_OK`,
   `ISO_SEEK_MAP source=CLPI` and `ISO_CLPI_SEEK` followed by a direct proxy range,
   without repeated PCR search probes. A fallback is explicitly logged as PCR.
3. Launching VLC/MX/Just Player, including leaving the chooser open over 15
   seconds. Expect `EXTERNAL_PLAYER_LAUNCH` and incoming User-Agent/method/range
   records in `ARCHIVE_PROXY_REQUEST`, followed by body transfer. Verify video
   and audio, then close the player and confirm the service's idle shutdown.

Actual BD50 audio output, seek latency, and third-party application behavior
require this Android device check; they cannot be measured by the sandbox tests.
