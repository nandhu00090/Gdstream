const fs = require('fs');
const path = require('path');

const targets = [
  path.join(
    __dirname,
    '../node_modules/react-native-video/android/src/main/java/com/brentvatne/exoplayer/ReactExoplayerView.java',
  ),
  path.join(__dirname, '../rnv-inspect/ReactExoplayerView.java'),
];

const oldTrackSnippet = `                    for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                        TrackGroup group = groups.get(groupIndex);
                        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                            Format format = group.getFormat(trackIndex);
                            
                            boolean isMatch = false;
                            if ("language".equals(type) && format.language != null && format.language.equals(value)) {
                                isMatch = true;
                            } else if ("title".equals(type) && format.label != null && format.label.equals(value)) {
                                isMatch = true;
                            } else if ("index".equals(type)) {
                                int targetIndex = ReactBridgeUtils.safeParseInt(value, -1);
                                if (targetIndex == trackIndex) {
                                    isMatch = true;
                                }
                            }`;

const newTrackSnippet = `                    int flatTrackIndex = 0;
                    for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                        TrackGroup group = groups.get(groupIndex);
                        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                            Format format = group.getFormat(trackIndex);
                            
                            boolean isMatch = false;
                            if ("language".equals(type) && format.language != null && format.language.equals(value)) {
                                isMatch = true;
                            } else if ("title".equals(type) && format.label != null && format.label.equals(value)) {
                                isMatch = true;
                            } else if ("index".equals(type)) {
                                int targetIndex = ReactBridgeUtils.safeParseInt(value, -1);
                                if (targetIndex == flatTrackIndex) {
                                    isMatch = true;
                                }
                            }
                            flatTrackIndex++;`;

const renderersReplacement = `        DefaultRenderersFactory renderersFactory =
                new DefaultRenderersFactory(getContext())
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                        .setEnableDecoderFallback(true)
                        .setMediaCodecSelector((mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
                            String hdrPref = "auto";
                            try {
                                Class<?> modCls = Class.forName("com.gdstream.VideoPlayerModule");
                                java.lang.reflect.Field f = modCls.getDeclaredField("preferredHdrMode");
                                Object val = f.get(null);
                                if (val instanceof String) hdrPref = (String) val;
                            } catch (Exception ignored) {}

                            if (androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION.equals(mimeType)) {
                                if ("hdr".equalsIgnoreCase(hdrPref)) {
                                    return MediaCodecUtil.getDecoderInfos(
                                            androidx.media3.common.MimeTypes.VIDEO_H265,
                                            requiresSecureDecoder,
                                            requiresTunnelingDecoder);
                                }
                                List<MediaCodecInfo> dvCodecs = MediaCodecUtil.getDecoderInfos(
                                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                                if (dvCodecs.isEmpty()) {
                                    return MediaCodecUtil.getDecoderInfos(
                                            androidx.media3.common.MimeTypes.VIDEO_H265,
                                            requiresSecureDecoder,
                                            requiresTunnelingDecoder);
                                }
                                return dvCodecs;
                            }
                            return MediaCodecUtil.getDecoderInfos(
                                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                        })
                        .forceEnableMediaCodecAsynchronousQueueing();`;

const oldSubtitleMethod = `    public void setSubtitleStyle(SubtitleStyle style) {
        exoPlayerView.setSubtitleStyle(style);
    }`;

const newSubtitleMethod = `    private void applyTransparentSubtitleStyle(View view) {
        if (view == null) return;
        try {
            if (view instanceof androidx.media3.ui.SubtitleView) {
                androidx.media3.ui.SubtitleView sv = (androidx.media3.ui.SubtitleView) view;
                sv.setApplyEmbeddedStyles(false);
                sv.setStyle(new androidx.media3.ui.CaptionStyleCompat(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        android.graphics.Color.BLACK,
                        android.graphics.Typeface.DEFAULT_BOLD
                ));
            } else if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    applyTransparentStyleToSubtitleViews(vg.getChildAt(i));
                }
            }
        } catch (Exception ignored) {}
    }

    private void applyTransparentStyleToSubtitleViews(View view) {
        applyTransparentSubtitleStyle(view);
    }

    public void setSubtitleStyle(SubtitleStyle style) {
        exoPlayerView.setSubtitleStyle(style);
        applyTransparentSubtitleStyle(exoPlayerView);
    }`;

for (const file of targets) {
  if (fs.existsSync(file)) {
    let content = fs.readFileSync(file, 'utf8');

    if (content.includes(oldTrackSnippet)) {
      content = content.replace(oldTrackSnippet, newTrackSnippet);
    }

    content = content.replace(
      /DefaultRenderersFactory renderersFactory =[\s\S]*?\.forceEnableMediaCodecAsynchronousQueueing\(\);/,
      renderersReplacement,
    );

    if (content.includes(oldSubtitleMethod)) {
      content = content.replace(oldSubtitleMethod, newSubtitleMethod);
    }

    if (
      content.includes('updateSubtitleButtonVisibility();') &&
      !content.includes('applyTransparentSubtitleStyle(exoPlayerView);\n        updateSubtitleButtonVisibility();')
    ) {
      content = content.replace(
        /updateSubtitleButtonVisibility\(\);/g,
        'applyTransparentSubtitleStyle(exoPlayerView);\n        updateSubtitleButtonVisibility();',
      );
    }

    fs.writeFileSync(file, content, 'utf8');
    console.log('Patched Dolby Vision / HDR selector & Subtitles in:', file);
  }
}
