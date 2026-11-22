// Keep native ISO policy on the app side of the dependency boundary. The library cannot import app classes.
function patchIsoPlayer(content) {
  content = content.replace(': 1500 , // ISO fast startup;', ': 2000 , // ISO fast startup;');
  content = content.replace(': DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,', ': 15000,');
  content = content.replace(': DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,', ': 45000,');
  content = content.replace(': DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,', ': 4000,');
  // The old fallback rebuilds the source. ISO recovery owns its renderer/clock without a reload loop.
  content = content.replace(
    'if (e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.getMessage()',
    'if (gdIsoPolicy == null && e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && e.getMessage()',
  );
  if (content.includes('GD_ISO_PLAYER_POLICY_V1')) return patchArchiveHttp(patchIsoAudioCatalog(patchBluRayFactory(content)));
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
  replace(': DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS ,', ': 2000 , // ISO fast startup; explicit source config still takes precedence');
  return patchArchiveHttp(patchIsoAudioCatalog(patchBluRayFactory(content)));
}
module.exports = { patchIsoPlayer };

// ProgressiveMediaSource creates its own extractors; changing DefaultMediaSourceFactory is insufficient.
function patchBluRayFactory(content) {
  if (content.includes('GD_BLURAY_EXTRACTORS_V1')) return content;
  const anchor = '    private void initializePlayerSource(Source runningSource) {';
  if (!content.includes(anchor)) throw new Error('Blu-ray extractor hook missing');
  content = content.replace(anchor, `    // GD_BLURAY_EXTRACTORS_V1
    private androidx.media3.extractor.ExtractorsFactory gdArchiveExtractors(Uri uri) {
        try {
            return (androidx.media3.extractor.ExtractorsFactory) Class.forName("com.gdstream.VideoPlayerModule")
                    .getMethod("archiveExtractors", String.class).invoke(null, uri.toString());
        } catch (Exception error) {
            DebugLog.e(TAG, "Blu-ray extractor factory: " + error);
            return new androidx.media3.extractor.DefaultExtractorsFactory();
        }
    }

` + anchor);
  const replacements = [
    ['new ProgressiveMediaSource.Factory(assetDataSourceFactory)', 'new ProgressiveMediaSource.Factory(assetDataSourceFactory, gdArchiveExtractors(uri))'],
    ['new ProgressiveMediaSource.Factory(\n                            mediaDataSourceFactory\n                    )', 'new ProgressiveMediaSource.Factory(\n                            mediaDataSourceFactory, gdArchiveExtractors(uri)\n                    )'],
    ['new ProgressiveMediaSource.Factory(\n                            RNVSimpleCache.INSTANCE.getCacheFactory(buildHttpDataSourceFactory(true))\n                    )', 'new ProgressiveMediaSource.Factory(\n                            RNVSimpleCache.INSTANCE.getCacheFactory(buildHttpDataSourceFactory(true)), gdArchiveExtractors(uri)\n                    )'],
  ];
  for (const [from, to] of replacements) {
    if (!content.includes(from)) throw new Error('Blu-ray ProgressiveMediaSource anchor missing: ' + from);
    content = content.replace(from, to);
  }
  return content;
}

function patchIsoAudioCatalog(content) {
  if (content.includes('GD_AUDIO_CATALOG_V1')) return content;
  const insert = (anchor, extra) => {
    if (!content.includes(anchor)) throw new Error('ISO audio catalog anchor missing: ' + anchor);
    content = content.replace(anchor, anchor + extra);
  };
  const listing = `
        // GD_AUDIO_CATALOG_V1: FFmpeg and raw PCM are mapped to different renderers.
        if (player != null) {
            try {
                return (ArrayList<Track>) Class.forName("com.gdstream.VideoPlayerModule")
                        .getMethod("audioTrackCatalog", Object.class).invoke(null, player);
            } catch (Exception error) { DebugLog.e(TAG, "ISO audio catalog: " + error); }
        }`;
  insert('    private ArrayList<Track> getAudioTrackInfo() {', listing);
  insert('    private ArrayList<Track> getBasicAudioTrackInfo() {', listing);
  insert('    public void setSelectedTrack(int trackType, String type, String value) {', `
        if (player != null && trackType == C.TRACK_TYPE_AUDIO) {
            try {
                boolean handled = Boolean.TRUE.equals(Class.forName("com.gdstream.VideoPlayerModule")
                        .getMethod("selectAudioTrackFromCatalog", Object.class, String.class, String.class, boolean.class)
                        .invoke(null, player, type, value, gdIsoPolicy != null));
                if (handled) return;
            } catch (Exception error) { DebugLog.e(TAG, "ISO audio selection: " + error); }
        }`);
  return content;
}

function patchArchiveHttp(content) {
  if (content.includes('GD_ARCHIVE_HTTP')) return content;
  const anchor = 'config.setDisableDisconnectError(this.disableDisconnectError);';
  const retry = 'config.buildLoadErrorHandlingPolicy(source.getMinLoadRetryCount())';
  const factory = 'case CONTENT_TYPE_OTHER:';
  for (const required of [anchor, retry, factory]) {
    if (!content.includes(required)) throw new Error('Archive HTTP patch anchor missing: ' + required);
  }
  return content.replace(anchor, anchor + `
        // GD_ARCHIVE_HTTP
        android.util.Pair<DataSource.Factory, androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy> archiveHttp = null;
        try {
            archiveHttp = (android.util.Pair<DataSource.Factory, androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy>)
                    Class.forName("com.gdstream.VideoPlayerModule")
                            .getMethod("archiveHttp", String.class, java.util.Map.class, int.class)
                            .invoke(null, uri.toString(), source.getHeaders(), source.getMinLoadRetryCount());
        } catch (ReflectiveOperationException error) { DebugLog.e(TAG, "Archive HTTP: " + error); }
`).replace(factory, factory + `
                if (archiveHttp != null) {
                    mediaSourceFactory = new ProgressiveMediaSource.Factory(archiveHttp.first, gdArchiveExtractors(uri));
                    break;
                }`).replace('new HlsMediaSource.Factory(\n                        dataSourceFactory',
                'new HlsMediaSource.Factory(\n                        archiveHttp != null ? archiveHttp.first : dataSourceFactory')
    .replace(retry, 'archiveHttp != null ? archiveHttp.second : ' + retry);
}
