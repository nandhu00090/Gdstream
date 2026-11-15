/** The native index restores process-local proxy registrations; ISO links must go through it on reopen. */
export function isIsoArchiveUrl(url: string): boolean {
  const [path, fragment = ''] = url.split('#');
  let name = fragment.startsWith('name=') ? fragment.slice(5) : '';
  try { name = decodeURIComponent(name); } catch { /* Use the literal name if malformed. */ }
  return /\.iso$/i.test(name) || /\.iso$/i.test(path.split('?')[0]);
}

export async function withArchiveTimeout<T>(request: Promise<T>, timeoutMs = 120000): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      request,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error('Opening the disc timed out. Please reopen it to retry.')), timeoutMs);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

/** Prefer the selected MPLS timeline over a stale 14-second probe or persisted duration. */
export function playbackDuration(file: { archiveFormat?: string; durationSec?: number } | null | undefined, reported: number, cached = 0): number {
  const known = Number(file?.durationSec);
  if (file?.archiveFormat === 'ISO' && Number.isFinite(known) && known > 0) return known;
  if (Number.isFinite(reported) && reported > 0) return reported;
  return Number.isFinite(cached) && cached > 0 ? cached : 0;
}
