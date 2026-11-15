import { isIsoArchiveUrl, withArchiveTimeout } from '../archivePlayback';

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
