import { catalogRequest } from '../catalogRequests';

test('twelve distinct requests share three slots and duplicate callers share the result', async () => {
  let active = 0;
  let peak = 0;
  let calls = 0;
  const load = async () => {
    calls++;
    peak = Math.max(peak, ++active);
    await new Promise<void>(resolve => setTimeout(resolve, 2));
    active--;
    return 'listing';
  };
  const requests = Array.from({ length: 12 }, (_, i) => {
    const request = catalogRequest(String(i), load);
    expect(catalogRequest(String(i), load)).toBe(request);
    return request;
  });
  expect(await Promise.all(requests)).toEqual(Array(12).fill('listing'));
  expect([calls, peak]).toEqual([12, 3]);
});

test('a stalled transport aborts after ten seconds and gets two bounded retries', async () => {
  jest.useFakeTimers();
  const signals: AbortSignal[] = [];
  try {
    const request = catalogRequest('stalled', signal => {
      signals.push(signal);
      return new Promise(() => {});
    });
    const rejected = expect(request).rejects.toThrow('timed out');
    await jest.advanceTimersByTimeAsync(9999);
    expect(signals[0].aborted).toBe(false);
    await jest.advanceTimersByTimeAsync(1);
    expect(signals[0].aborted).toBe(true);
    await jest.advanceTimersByTimeAsync(399);
    expect(signals).toHaveLength(1);
    await jest.advanceTimersByTimeAsync(801);
    expect(signals).toHaveLength(2);
    await jest.advanceTimersByTimeAsync(22_000);
    await rejected;
    expect(signals).toHaveLength(3);
    expect(signals.every(signal => signal.aborted)).toBe(true);
    expect(await catalogRequest('stalled', async () => 'recovered')).toBe('recovered');
  } finally { jest.useRealTimers(); }
});
