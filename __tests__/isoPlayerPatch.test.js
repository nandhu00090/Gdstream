const { patchIsoPlayer } = require('../scripts/patch-iso-player');

test('native ISO policy hooks are idempotent and fail loudly if upstream method signatures change', () => {
  const source = `    private void initializePlayerSource(Source runningSource) {
    }
    private void releasePlayer() {
    }
    public void onPlayerError(@NonNull PlaybackException e) {
    }
    : DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS ,`;
  const patched = patchIsoPlayer(source);
  expect(patchIsoPlayer(patched)).toBe(patched);
  expect(patched).toContain('attachIsoPolicy(runningSource);');
  expect(patched).toContain('if (recoverIsoAudio(e)) return;');
  expect(patched).toContain(': 1500');
  expect(() => patchIsoPlayer('incompatible dependency')).toThrow('anchor missing');
});
