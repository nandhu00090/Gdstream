/** Identities are explicit; legacy generated entry names are matched to the exact disc basename. */
export function watchAliases(item: any): string[] {
  const values = [item?.name, item?.id, item?.link, item?.url, item?.outerUrl, item?.outerId,
    item?.innerName, ...(item?.historyAliases || [])];
  const tmdb = item?.tmdbId ?? item?.metadata?.tmdbId;
  if (tmdb != null) values.push(`tmdb:${tmdb}`, `movie:${tmdb}`, `movie:tmdb:${tmdb}`);
  return [...new Set(values.filter((v): v is string => typeof v === 'string' && v.length > 0))];
}

export function linkedWatchKeys(item: any, knownKeys: string[], aliases: Record<string, string[]>): string[] {
  const versions = item?._versions?.length ? [item, ...item._versions] : [item];
  const keys = new Set(versions.flatMap(watchAliases));
  // Transitive closure also handles an inner URL/id being removed before its outer card is loaded.
  let changed = true;
  while (changed) {
    changed = false;
    for (const [name, links] of Object.entries(aliases)) {
      if ([name, ...links].some(key => keys.has(key))) {
        for (const key of [name, ...links]) if (!keys.has(key)) { keys.add(key); changed = true; }
      }
    }
  }
  const discs = [...keys].filter(key => /\.iso$/i.test(key) && !key.includes('://')).map(key => key.slice(0, -4).toLowerCase());
  for (const key of knownKeys) {
    let decoded = key;
    try { decoded = decodeURIComponent(key); } catch { /* literal malformed key */ }
    const name = decoded.split('#name=').pop()!.split('?')[0].split('/').pop()!.toLowerCase();
    // Proxy ports change after restarts; a registered stream ID remains a linked identity.
    const proxyId = decoded.match(/^https?:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?\/(?:zip|hls)\/([^/]+)/)?.[1];
    if (proxyId && keys.has(proxyId)) keys.add(key);
    if (discs.some(base => name === `${base}.iso` ||
      name.startsWith(`${base} [main movie`) && name.endsWith('].m2ts') ||
      name.startsWith(`${base} [main stream`) && name.endsWith('].m2ts'))) keys.add(key);
  }
  return [...keys];
}
