import { useRef } from 'react';

export const PLAYBACK_BUFFER_CONFIG = Object.freeze({
  minBufferMs: 15000,
  maxBufferMs: 45000,
  bufferForPlaybackMs: 2000,
  bufferForPlaybackAfterRebufferMs: 4000,
});

/** Metadata, UI updates and completed-download notifications cannot change a playing source. */
export function usePlaybackSource(uri: string, identity: string | null) {
  const active = useRef<{ identity: string | null; source: { uri: string; bufferConfig: typeof PLAYBACK_BUFFER_CONFIG } }>({
    identity, source: { uri, bufferConfig: PLAYBACK_BUFFER_CONFIG },
  });
  if (active.current.identity !== identity) {
    active.current = { identity, source: { uri, bufferConfig: PLAYBACK_BUFFER_CONFIG } };
  }
  return active.current.source;
}
