import React from 'react';
import ReactTestRenderer, { act } from 'react-test-renderer';
import { usePlaybackSource, PLAYBACK_BUFFER_CONFIG } from '../playbackSession';

it('keeps the native source identical through metadata/UI updates and changes it only for explicit playback sessions', async () => {
  let source: ReturnType<typeof usePlaybackSource>;
  function Probe({ uri, identity }: { uri: string; identity: string | null }) {
    source = usePlaybackSource(uri, identity);
    return null;
  }
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await act(async () => { renderer = ReactTestRenderer.create(<Probe uri="http://localhost/disc.m2ts" identity="disc:1" />); });
  const original = source!;
  await act(async () => { renderer.update(<Probe uri="http://localhost/disc.m2ts?metadata=changed" identity="disc:1" />); });
  expect(source!).toBe(original);
  expect(source!.bufferConfig).toBe(PLAYBACK_BUFFER_CONFIG);
  expect(source!.bufferConfig.bufferForPlaybackMs).toBe(2000);
  await act(async () => { renderer.update(<Probe uri="http://localhost/episode2.m2ts" identity="episode2:1" />); });
  expect(source!).not.toBe(original);
  expect(source!.uri).toBe('http://localhost/episode2.m2ts');
  await act(async () => renderer.unmount());
});
