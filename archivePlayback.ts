/** File names from the directory may already contain percent-escaped bytes. */
export function encodeLibraryName(name: string): string {
  return encodeURIComponent(name).replace(/%25([\da-f]{2})/gi, '%$1');
}

export function getPlayableUrl(base: string, directory: string, name: string, link?: string): string {
  const path = link || `${directory.replace(/\/$/, '')}/${encodeLibraryName(name)}`;
  let url = /^(https?|file|content):/i.test(path) ? path
    : `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`;
  // Listings can return an unescaped file name. Its known final segment
  // disambiguates a literal # or ? from the link's optional suffix.
  if (link && name && /[?#]/.test(name) && /^https?:\/\//i.test(url)) {
    const at = url.indexOf('/' + name, url.indexOf('://') + 3);
    const suffix = url.slice(at + 1 + name.length);
    if (at >= 0 && !/[?#]/.test(url.slice(0, at)) && (!suffix || /^[?#]/.test(suffix))) {
      url = url.slice(0, at + 1) + encodeLibraryName(name) + suffix;
    }
  }
  // Keep authority, signed query and fragment verbatim. Preserve RFC 3986 path
  // delimiters (including the Worker's /0:/) and existing percent escapes.
  return url.replace(/^(https?:\/\/[^/?#]+)([^?#]*)/i, (_, origin: string, pathname: string) =>
    origin + pathname.replace(/%(?![\da-f]{2})|[^\w.!~*'()$&+,;=:@/%-]+/gi, encodeURIComponent));
}

export const FILE_NOT_FOUND_MESSAGE = 'File not found on server (404) — it may have been removed or the index cache is stale. Tap Refresh Library and try again.';

export function playbackErrorMessage(error: any, fallback: string): string {
  const detail = error?.error ?? error;
  const messages = [detail?.message, detail?.errorString, detail?.errorException, detail?.errorStackTrace, detail];
  return Number(detail?.status ?? detail?.statusCode ?? detail?.responseCode) === 404 ||
    messages.some(value => typeof value === 'string' && /\b(?:HTTP(?:\/\d\.\d)?|response code|status code)[:=\s]+404\b/i.test(value))
    ? FILE_NOT_FOUND_MESSAGE : fallback;
}

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

/** Use the authenticated library path to obtain a fresh redirect for EACH ISO range request.
 * Directory listings (and their disk cache) can contain already expired download.aspx links.
 * Keep local files and ZIP/RAR link selection unchanged.
 */
export function archiveSourceUrl(name: string, baseUrl: string, parentPath: string, fallback: string): string {
  if (!/\.iso$/i.test(name) || !/^https?:\/\//i.test(baseUrl) ||
      !/^\/\d+:\//.test(parentPath) || /^(file|content):/i.test(fallback)) return fallback;
  return getPlayableUrl(baseUrl, parentPath, name);
}

/** An ISO resolves to one feature. Preserve the outer card identity for artwork and resume. */
export function isoMovieDetails(item: any, entries: any[]): any | null {
  if (!/\.iso$/i.test(String(item?.name || '')) || entries.length !== 1 || !entries[0]?.link) return null;
  return { ...item, ...entries[0], name: item.name, outerId: item.id, innerName: entries[0].name,
    historyAliases: [item.name, item.id, item.link, item.outerUrl, entries[0].name, entries[0].id, entries[0].link].filter(Boolean),
    archiveFormat: 'ISO', isoPending: false, isZipEntry: true };
}

/** Require episode identity and the same normalized show, never archive membership alone. */
export function sameShowEpisodes(selected: any, files: any[], parse: (name: string, folder: boolean) => {
  searchTitle: string; episodeNum: number; seasonEpisode: string;
}): any[] {
  if (!selected || selected.archiveFormat === 'ISO' || /\.iso$/i.test(selected.name || '')) return [];
  const current = parse(selected.name, false);
  if (current.episodeNum <= 0) return [];
  const show = current.searchTitle.trim().toLowerCase();
  const episodes = files.filter(file => !file.mimeType?.includes('folder') && file.archiveFormat !== 'ISO' &&
    !/\.iso$/i.test(file.name || '') && (() => {
      const info = parse(file.name, false);
      return info.episodeNum > 0 && info.searchTitle.trim().toLowerCase() === show;
    })());
  return episodes.some(file => file.name === selected.name) && episodes.length > 1 ? episodes : [];
}
