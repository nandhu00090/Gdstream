import React, {useEffect, useState} from 'react';
import {Image, NativeModules, StyleSheet, View} from 'react-native';

type Cue = {
  showUs: number; stopUs: number; x: number; y: number; width: number; height: number;
  canvasHeight: number; uri: string;
};
type Window = {startUs: number; endUs: number; cues: Cue[]};

export function dvdCueAt(cues: Cue[], timeUs: number) {
  return cues.find(cue => cue.showUs <= timeUs && timeUs < cue.stopUs);
}

export function dvdCueRect(cue: Cue, width: number, height: number, aspect: number, zoom: boolean) {
  const fitWidth = zoom ? width / height > aspect : width / height < aspect;
  const videoWidth = fitWidth ? width : height * aspect;
  const videoHeight = fitWidth ? width / aspect : height;
  return {
    left: (width - videoWidth) / 2 + cue.x * videoWidth / 720,
    top: (height - videoHeight) / 2 + cue.y * videoHeight / cue.canvasHeight,
    width: cue.width * videoWidth / 720,
    height: cue.height * videoHeight / cue.canvasHeight,
  };
}

export default function DvdSubtitleOverlay({url, track, clock, delay, aspect, zoom}: {
  url: string; track: number; clock: {current: number}; delay: number; aspect: number; zoom: boolean;
}) {
  const [size, setSize] = useState({width: 0, height: 0});
  const [cue, setCue] = useState<Cue>();
  useEffect(() => {
    let active = true;
    let loading = false;
    let windows: Window[] = [];
    const position = () => Math.round((clock.current - delay) * 1e6);
    const at = (us: number) => windows.find(w => w.startUs <= us && us < w.endUs);
    const update = async () => {
      const timeUs = position();
      const current = at(timeUs);
      setCue(current && dvdCueAt(current.cues, timeUs));
      if (loading || timeUs < 0) return;
      // Decode the next segment's PNGs before the cut so a continuing SPU stays visible.
      if (current && (current.endUs - timeUs > 500_000 || at(current.endUs))) return;
      loading = true;
      try {
        const result: Window | null = await NativeModules.SecurityVault.dvdSubtitleWindow(url, track, current?.endUs ?? timeUs);
        if (active && result) {
          windows = current ? [current, result] : [result];
          const now = position();
          const ready = at(now);
          setCue(ready && dvdCueAt(ready.cues, now));
        }
      } finally { loading = false; }
    };
    const tick = () => { update().catch(() => {}); };
    tick();
    const timer = setInterval(tick, 80);
    return () => { active = false; clearInterval(timer); };
  }, [url, track, clock, delay]);
  return (
    <View pointerEvents="none" style={[StyleSheet.absoluteFill, {overflow: 'hidden'}]}
      onLayout={({nativeEvent}) => setSize(nativeEvent.layout)}>
      {!!cue && size.width > 0 && size.height > 0 && aspect > 0 &&
        <Image source={{uri: cue.uri}} fadeDuration={0} resizeMode="stretch"
          style={[{position: 'absolute'}, dvdCueRect(cue, size.width, size.height, aspect, zoom)]} />}
    </View>
  );
}
