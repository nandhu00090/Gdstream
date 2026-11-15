// Keep native ISO policy on the app side of the dependency boundary. The library cannot import app classes.
function patchIsoPlayer(content) {
  // The old fallback rebuilds the source. ISO recovery owns its renderer/clock without a reload loop.
  content = content.replace(
    'if (e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.getMessage()',
    'if (gdIsoPolicy == null && e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.getMessage()',
  );
  if (content.includes('GD_ISO_PLAYER_POLICY_V1')) return content;
  const replace = (anchor, replacement) => {
    if (!content.includes(anchor)) throw new Error(`react-native-video ISO patch anchor missing: ${anchor}`);
    content = content.replace(anchor, replacement);
  };
  replace('    private void initializePlayerSource(Source runningSource) {', `    // GD_ISO_PLAYER_POLICY_V1
    private java.lang.AutoCloseable gdIsoPolicy;
    private void closeIsoPolicy() {
        if (gdIsoPolicy != null) {
            try { gdIsoPolicy.close(); } catch (Exception error) { DebugLog.e(TAG, "ISO policy close: " + error); }
            gdIsoPolicy = null;
        }
    }
    private void attachIsoPolicy(Source runningSource) {
        closeIsoPolicy();
        try {
            Class<?> bridge = Class.forName("com.gdstream.VideoPlayerModule");
            gdIsoPolicy = (java.lang.AutoCloseable) bridge.getMethod("attachArchivePlayer", Object.class, String.class)
                    .invoke(null, player, runningSource.getUri() == null ? null : runningSource.getUri().toString());
        } catch (Exception error) { DebugLog.e(TAG, "ISO policy attach: " + error); }
    }
    private boolean recoverIsoAudio(PlaybackException error) {
        if (gdIsoPolicy == null) return false;
        try {
            return Boolean.TRUE.equals(Class.forName("com.gdstream.VideoPlayerModule")
                    .getMethod("recoverArchiveAudio", Object.class, Object.class).invoke(null, gdIsoPolicy, error));
        } catch (Exception failure) { DebugLog.e(TAG, "ISO audio recovery: " + failure); return false; }
    }

    private void initializePlayerSource(Source runningSource) {
        attachIsoPolicy(runningSource);`);
  replace('    private void releasePlayer() {', '    private void releasePlayer() {\n        closeIsoPolicy();');
  replace('    public void onPlayerError(@NonNull PlaybackException e) {', '    public void onPlayerError(@NonNull PlaybackException e) {\n        if (recoverIsoAudio(e)) return;');
  // RNVLoadControl subclasses DefaultLoadControl. Set its default as well as the stable JS source config.
  replace(': DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS ,', ': 1500 , // ISO fast startup; explicit source config still takes precedence');
  return content;
}
module.exports = { patchIsoPlayer };
