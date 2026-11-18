import { isIsoArchiveUrl, withArchiveTimeout, playbackDuration, archiveSourceUrl, isoMovieDetails } from '../archivePlayback';

test('selected feature duration overrides disclaimer probes and stale cached runtime', () => {
  const feature = { archiveFormat: 'ISO', durationSec: 9995 };
  expect(playbackDuration(feature, 14, 14)).toBe(9995);
  expect(playbackDuration(feature, -14)).toBe(9995);
  expect(playbackDuration(feature, NaN)).toBe(9995);
  expect(playbackDuration({ archiveFormat: 'ZIP' }, 3600, 14)).toBe(3600);
  expect(playbackDuration(undefined, -14)).toBe(0);
});

describe('ISO archive opening', () => {
  afterEach(() => jest.useRealTimers());

  test('recognizes direct URLs and named download.aspx links without treating ZIP/RAR as ISO', () => {
    expect(isIsoArchiveUrl('https://example/disc.ISO?token=abc')).toBe(true);
    expect(isIsoArchiveUrl('https://example/download.aspx?id=123#name=Aadhavan%20(2009).iso')).toBe(true);
    expect(isIsoArchiveUrl('https://example/disc.zip#name=disc.zip')).toBe(false);
    expect(isIsoArchiveUrl('https://example/disc.rar')).toBe(false);
  });

  test('reports a stalled native scan and ignores its late result', async () => {
    jest.useFakeTimers();
    let resolve!: (value: string) => void;
    const request = withArchiveTimeout(new Promise<string>(r => { resolve = r; }), 100);
    const failure = expect(request).rejects.toThrow('timed out');
    jest.advanceTimersByTime(100);
    await failure;
    resolve('late index');
    await expect(request).rejects.toThrow('timed out');
    expect(jest.getTimerCount()).toBe(0);
  });

  test('cleans up timeout on success and preserves native failures', async () => {
    jest.useFakeTimers();
    await expect(withArchiveTimeout(Promise.resolve('[]'))).resolves.toBe('[]');
    await expect(withArchiveTimeout(Promise.reject(new Error('Bad FSD')))).rejects.toThrow('Bad FSD');
    expect(jest.getTimerCount()).toBe(0);
  });
});


test('ISO reopening replaces cached signed links with a stable library source', () => {
  const expired = 'https://origin/download.aspx?expiry=1&mac=old%2Bsignature';
  const source = archiveSourceUrl('Aadhavan (2009).iso', 'https://library/', '/0:/Tamil/', expired);
  expect(source).toBe('https://library/0:/Tamil/Aadhavan%20(2009).iso');
  expect(isIsoArchiveUrl(source)).toBe(true);
  expect(archiveSourceUrl('disc.iso', 'https://library', '/0:/Tamil/', 'file:///disc.iso')).toBe('file:///disc.iso');
  expect(archiveSourceUrl('disc.iso', 'https://library', '/0:/Tamil/', 'content://disc')).toBe('content://disc');
  expect(archiveSourceUrl('disc.iso', 'https://library', 'unknown', expired)).toBe(expired);
  for (const name of ['movie.zip', 'movie.rar', '00006.m2ts']) {
    expect(archiveSourceUrl(name, 'https://library', '/0:/Tamil/', expired)).toBe(expired);
  }
});


test('single ISO opens details with homepage identity, quality and resume key', () => {
  const card = { name: 'Movie.iso', id: 'outer', size: '50000000000' };
  const entry = { name: 'Movie [Main Movie].m2ts', link: 'http://127.0.0.1:9999/zip/entry/movie.m2ts',
    id: 'entry', durationSec: 7200, mediaInfo: { resolution: '2160p', hasDV: true } };
  const movie = isoMovieDetails(card, [entry]);
  expect(movie.name).toBe(card.name);
  expect(movie.link).toBe(entry.link);
  expect(movie.archiveFormat).toBe('ISO');
  expect(movie.isZipEntry).toBe(true);
  expect(movie.mediaInfo.resolution).toBe('2160p');
  expect({ [card.name]: 1800 }[movie.name]).toBe(1800);
  expect(playbackDuration(movie, 14)).toBe(7200);
  expect(isoMovieDetails({ name: 'Series.zip' }, [entry])).toBeNull();
  expect(isoMovieDetails({ name: 'Series.rar' }, [entry, entry])).toBeNull();
  expect(isoMovieDetails(card, [])).toBeNull();
  expect(isoMovieDetails(card, [entry, entry])).toBeNull();
});
