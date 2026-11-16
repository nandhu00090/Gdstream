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
the same 120-second deadline applies after media bytes flow. Idle shutdown retains
registrations and permits a later probe to reconnect. Header-only
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


PR #6 throughput settings: upstream windows are 8 MiB with six permits, a 64 MiB
LRU and four queued sequential windows ahead of delivery. Player buffer times
are 15000/45000/2000/4000 ms (minimum/maximum/start/rebuffer). ISO media responses
support persistent HTTP connections with exact lengths and keep-alive headers;
abort monitoring preserves the next request byte. External intents include the
movie title and video/mp2t MIME. APP_BOOT clears the session log and invalidates
the exported-file append cursor; subsequent size rotation keeps the newest lines.
These settings amortize origin TTFB; actual sustained bitrate and device memory
use still require testing on the original BD50 stream.

PR #8 catalogs one largest-by-bytes feature per ISO. All MPLS candidates are
inspected in batches of six; short-intro filtering is retained. If playlists
are unavailable, the largest raw M2TS (or DVD title/data-disc video) is used.
ISO cache schema 6 invalidates old multi-feature catalog entries.

MediaInfo accompanies the selected entry and comes from CLPI ProgramInfo,
MPLS STN descriptors, and the UDF logical volume label. It includes stream
languages, video format/rate, and HDR/DV evidence. A 2160p HEVC base plus the
1080p HEVC enhancement PID 0x1015, MPLS DV indication, HEVC dynamic-range flags,
or explicit disc-label tags identify DV; an unrelated secondary 1080p video
does not. The existing display-format chooser consumes these flags before
playback. This metadata change does not add a new Dolby Vision decoder.
Unknown channels/languages are not guessed as 5.1/English.

ISO GET/HEAD responses advertise full presentation duration in seconds through
X-Content-Duration and Content-Duration when known, and DLNA Streaming/OP=01/CI=0.
Byte ranges and Content-Length remain in the exposed 188-byte TS address space.
OP=01 advertises byte seeking, not DLNA TimeSeekRange support; VLC/MX time-seek
behavior and display-format rendering still need device validation.
