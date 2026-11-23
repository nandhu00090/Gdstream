import React from 'react';
import Renderer from 'react-test-renderer';
import { NativeModules } from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import App from '../App';

jest.mock('react-native', () => {
  const rn = jest.requireActual('react-native');
  rn.NativeModules.SecurityVault = {
    getVaultConfig: jest.fn().mockResolvedValue({ baseUrl: 'https://library' }),
    securePostDirectory: jest.fn().mockResolvedValue(JSON.stringify({ data: { files: [
      { name: 'Example.2009.iso', size: '50000000000', id: 'outer', mimeType: 'application/octet-stream' },
    ] } })),
    listZipMediaEntries: jest.fn(),
    getArchiveWatchAliases: jest.fn().mockResolvedValue(['inner', 'Example.2009 [Main Movie - 2h].m2ts', 'http://127.0.0.1:99/zip/inner/movie.m2ts']),
    writeDebugLog: jest.fn(),
    dvdPlaybackUrl: jest.fn(),
  };
  rn.NativeModules.MediaInfoModule = { inspectMedia: jest.fn() };
  rn.NativeModules.VideoPlayerManager = {
    getAllWatchProgress: jest.fn().mockResolvedValue({ times: {}, durations: {} }),
    playVideo: jest.fn().mockResolvedValue(true),
    removeWatchProgress: jest.fn().mockResolvedValue(true),
  };
  return rn;
});
jest.mock('react-native-orientation-locker', () => ({ lockToLandscape: jest.fn(), lockToPortrait: jest.fn() }));
jest.mock('react-native-vector-icons/MaterialIcons', () => 'Icon');
jest.mock('axios', () => ({ get: jest.fn().mockRejectedValue(new Error('offline')) }));
jest.mock('@react-native-async-storage/async-storage', () => {
  const values: Record<string, string> = {};
  const meta = JSON.stringify({ title: 'TMDB Movie Title', year: '2009', poster: 'https://image/poster', backdrop: 'https://image/backdrop', overview: 'Card overview' });
  const get = (key: string) => values[key] ?? (key.startsWith('meta_v8_') ? meta : null);
  return {
    getItem: jest.fn(async (key: string) => get(key)),
    getMany: jest.fn(async (keys: string[]) => Object.fromEntries(keys.map(key => [key, get(key)]))),
    setItem: jest.fn(async (key: string, value: string) => { values[key] = value; }),
    removeItem: jest.fn(async (key: string) => { delete values[key]; }),
    removeMany: jest.fn(async (keys: string[]) => keys.forEach(key => { delete values[key]; })),
    getAllKeys: jest.fn(async () => Object.keys(values)),
    clear: jest.fn(async () => Object.keys(values).forEach(key => { delete values[key]; })),
  };
});

function renderedText(node: any): string {
  if (typeof node === 'string') return node;
  if (Array.isArray(node)) return node.map(renderedText).join(' ');
  return node?.children ? renderedText(node.children) : '';
}
let renderer: Renderer.ReactTestRenderer;
async function mount() {
  await Renderer.act(async () => { renderer = Renderer.create(<App />); });
  await Renderer.act(async () => { jest.advanceTimersByTime(500); });
}
beforeEach(async () => { jest.useFakeTimers(); await AsyncStorage.clear(); jest.clearAllMocks(); });
afterEach(async () => { await Renderer.act(async () => renderer?.unmount()); jest.useRealTimers(); });

test('uncached ISO opens details before inspection resolves; back rejects late completion', async () => {
  let finish!: (json: string) => void;
  NativeModules.SecurityVault.listZipMediaEntries.mockImplementation(() => new Promise<string>(resolve => { finish = resolve; }));
  await mount();
  await Renderer.act(async () => renderer.root.findByProps({ focusId: 'card_0' }).props.onPress());
  expect(renderer.root.findByProps({ focusId: 'detail_play_btn' }).props.disabled).toBe(true);
  expect(renderedText(renderer.toJSON())).toContain('Inspecting Blu-ray disc...');
  expect(renderedText(renderer.toJSON())).toContain('TMDB Movie Title');
  expect(renderedText(renderer.toJSON())).toContain('Card overview');
  expect(renderedText(renderer.toJSON())).not.toContain('Opening disc');
  expect(NativeModules.MediaInfoModule.inspectMedia).not.toHaveBeenCalled();
  await Renderer.act(async () => renderer.root.findByProps({ focusId: 'detail_back_btn' }).props.onPress());
  await Renderer.act(async () => finish(JSON.stringify([{ name: 'feature.m2ts', link: 'http://127.0.0.1/zip/id/feature.m2ts' }])));
  expect(renderer.root.findAllByProps({ focusId: 'detail_play_btn' })).toHaveLength(0);
});

test('inspection completion enables playback and failures keep details with retry', async () => {
  NativeModules.SecurityVault.listZipMediaEntries.mockRejectedValueOnce(new Error('scan failed')).mockResolvedValueOnce(JSON.stringify([
    { name: 'feature.m2ts', link: 'http://127.0.0.1/zip/id/feature.m2ts', durationSec: 7200, mediaInfo: { resolution: '2160p', audioSpecs: [], subtitleSpecs: [], languages: [] } },
  ]));
  await mount();
  await Renderer.act(async () => renderer.root.findByProps({ focusId: 'card_0' }).props.onPress());
  expect(renderedText(renderer.toJSON())).toContain('Retry disc inspection');
  await Renderer.act(async () => renderer.root.findByProps({ focusId: 'detail_play_btn' }).props.onPress());
  expect(renderer.root.findByProps({ focusId: 'detail_play_btn' }).props.disabled).toBe(false);
  expect(renderedText(renderer.toJSON())).not.toContain('Retry disc inspection');
  expect(renderedText(renderer.toJSON())).toContain('2160p');
});

test('X removes outer and persisted inner progress and remains removed after remount with stale native history', async () => {
  const inner = 'Example.2009 [Main Movie - 2h].m2ts';
  const times = { 'Example.2009.iso': 100, [inner]: 120, 'http://127.0.0.1:42/zip/inner/movie.m2ts': 150 };
  NativeModules.VideoPlayerManager.getAllWatchProgress.mockResolvedValue({ times, durations: {} });
  await AsyncStorage.setItem('gdstream_cw_history_v2', JSON.stringify({ times, durations: {} }));
  await AsyncStorage.setItem(`time_${inner}`, '120');
  await mount();
  await Renderer.act(async () => renderer.root.findAllByProps({ accessibilityLabel: 'Remove Example from Continue Watching' })[0].props.onPress());
  const saved = JSON.parse((await AsyncStorage.getItem('gdstream_cw_history_v2'))!);
  expect(saved.times).toEqual({});
  expect(saved.removed).toEqual(expect.arrayContaining(Object.keys(times)));
  expect(await AsyncStorage.getItem(`time_${inner}`)).toBeNull();
  expect(NativeModules.VideoPlayerManager.removeWatchProgress).toHaveBeenCalledWith(inner);
  await Renderer.act(async () => renderer.unmount());
  await mount();
  expect(renderer.root.findAllByProps({ accessibilityLabel: 'Remove Example from Continue Watching' })).toHaveLength(0);
  // Resolving the ISO after removal must not migrate the stale inner position back to the outer name.
  NativeModules.SecurityVault.listZipMediaEntries.mockResolvedValue(JSON.stringify([{ name: inner, link: 'http://127.0.0.1:99/zip/inner/movie.m2ts' }]));
  await Renderer.act(async () => renderer.root.findByProps({ focusId: 'card_0' }).props.onPress());
  expect(renderedText(renderer.toJSON())).not.toContain('Resume Watching');
});

test('external DVD chooser gets the virtual VOB after ISO resolution', async () => {
  const direct = 'http://127.0.0.1:99/zip/disc/Example%20%5BDVD%20Main%5D.vob';
  NativeModules.SecurityVault.listZipMediaEntries.mockResolvedValue(JSON.stringify([
    { name: 'Example [DVD Main].vob', link: direct, durationSec: 7200 },
  ]));
  await mount();
  await Renderer.act(async () => renderer.root.findByProps({ focusId: 'card_0' }).props.onPress());
  await Renderer.act(async () => {
    const launch = renderer.root.findByProps({ focusId: 'detail_ext_btn' }).props.onPress();
    jest.advanceTimersByTime(32);
    await launch;
  });
  expect(NativeModules.VideoPlayerManager.playVideo).toHaveBeenCalledWith(direct, expect.any(String));
  expect(NativeModules.SecurityVault.dvdPlaybackUrl).not.toHaveBeenCalled();
});

test('a warm homepage renders its last listing while the worker is still pending', async () => {
  await mount();
  expect(renderer.root.findByProps({ focusId: 'card_0' })).toBeTruthy();
  await Renderer.act(async () => renderer.unmount());
  let refresh!: (value: string) => void;
  NativeModules.SecurityVault.securePostDirectory.mockImplementationOnce(() => new Promise<string>(resolve => { refresh = resolve; }));
  await Renderer.act(async () => { renderer = Renderer.create(<App />); });
  expect(renderer.root.findByProps({ focusId: 'card_0' })).toBeTruthy();
  await Renderer.act(async () => refresh(JSON.stringify({ data: { files: [] } })));
  expect(renderer.root.findAllByProps({ focusId: 'card_0' })).toHaveLength(0);
});
