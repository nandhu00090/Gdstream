// A slot follows the actual transport through cancellation and body parsing.
const pending = new Map<string, Promise<unknown>>();
const waiters: Array<() => void> = [];
let active = 0;

export async function inCatalogSlot<T>(load: () => Promise<T>): Promise<T> {
  if (active === 3) await new Promise<void>(resolve => waiters.push(resolve));
  else active++;
  try { return await load(); }
  finally {
    const next = waiters.shift();
    if (next) next();
    else active--;
  }
}

function attempt<T>(load: (signal: AbortSignal) => Promise<T>): Promise<T> {
  return inCatalogSlot(async () => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const deadline = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        controller.abort();
        reject(new Error('Catalog request timed out'));
      }, 10_000);
    });
    try { return await Promise.race([load(controller.signal), deadline]); }
    finally { clearTimeout(timer); }
  });
}

export function catalogRequest<T>(key: string, load: (signal: AbortSignal) => Promise<T>): Promise<T> {
  const existing = pending.get(key);
  if (existing) return existing as Promise<T>;
  const request = (async () => {
    for (let retry = 0; ; retry++) {
      try { return await attempt(load); } catch (error) {
        if (retry === 2) throw error;
        await new Promise<void>(resolve => setTimeout(resolve, 400 + Math.floor(Math.random() * 801)));
      }
    }
  })().finally(() => pending.delete(key));
  pending.set(key, request);
  return request;
}

export function catalogJson(url: string) {
  return catalogRequest(url, async signal => {
    const response = await fetch(url, { signal });
    if (!response.ok) throw new Error(`Metadata request returned HTTP ${response.status}`);
    return response.json();
  });
}
