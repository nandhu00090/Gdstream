const { patchIsoPlayer } = require('../scripts/patch-iso-player');

test('native ISO policy hooks are idempotent and fail loudly if upstream method signatures change', () => {
  const source = `    private void initializePlayerSource(Source runningSource) {
    }
    private void releasePlayer() {
    }
    public void onPlayerError(@NonNull PlaybackException e) {
    }
    private ArrayList<Track> getAudioTrackInfo() {
    }
    private ArrayList<Track> getBasicAudioTrackInfo() {
    }
    public void setSelectedTrack(int trackType, String type, String value) {
    }
    : DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS ,
new ProgressiveMediaSource.Factory(assetDataSourceFactory)
new ProgressiveMediaSource.Factory(
                            mediaDataSourceFactory
                    )
new ProgressiveMediaSource.Factory(
                            RNVSimpleCache.INSTANCE.getCacheFactory(buildHttpDataSourceFactory(true))
                    )`;
  const patched = patchIsoPlayer(source);
  expect(patchIsoPlayer(patched)).toBe(patched);
  expect(patched).toContain('attachIsoPolicy(runningSource);');
  expect(patched).toContain('if (recoverIsoAudio(e)) return;');
  expect(patched).toContain(': 2000');
  expect(patched.match(/getMethod\("audioTrackCatalog"/g)).toHaveLength(2);
  expect(patched).toContain('getMethod("selectAudioTrackFromCatalog"');
  expect(patched.match(/gdArchiveExtractors\(uri\)/g)).toHaveLength(3);
  expect(patched).toContain('getMethod("archiveExtractors", String.class)');
  expect(() => patchIsoPlayer('incompatible dependency')).toThrow('anchor missing');
});
