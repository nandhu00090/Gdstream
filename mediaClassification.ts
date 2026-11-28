export interface ParsedMediaInfo {
  rawName: string;
  cleanTitle: string;
  searchTitle: string;
  year: string;
  seasonEpisode: string;
  seasonNum: number;
  episodeNum: number;
  resolution: string;
  badges: string[];
  hasDV: boolean;
  hasHDR: boolean;
  langCode: string;
  langName: string;
  isFolder: boolean;
}

export interface FolderClassification {
  kind: 'movie' | 'series' | 'collection';
  folderPath: string;
  folderName: string;
  title: string;
  searchTitle: string;
  year: string;
  langCode: string;
  langName: string;
  versions: any[];
  minSeason: number;
  maxSeason: number;
}

export const TYPO_FIXES: Record<string, string> = {
  'attach on titan': 'Attack on Titan',
};

const VIDEO_EXT_RE = /\.(mkv|mp4|avi|mov|webm|m4v|ts|m2ts|vob|mpg|mpeg|flv|wmv|iso)$/i;
const MEDIA_EXT_STRIP_RE = /\.(mkv|mp4|avi|mov|webm|m4v|ts|m2ts|vob|mpg|mpeg|flv|wmv|zip|rar|iso)$/i;
const EDITION_WORDS_RE = /\b(director'?s?\s*cut|extended(?:\s*cut)?|theatrical(?:\s*cut)?|uncut|unrated|remastered|special\s*edition|imax|proper|repack)\b/gi;
const QUALITY_STRIP_RE = /\b(2160p|1080p|720p|480p|4k|uhd|bluray|blu\s*ray|remux|web\s*dl|webdl|webrip|web|hevc|avc|x264|x265|h\s*264|h\s*265|dv|dovi|dolby\s*vision|hdr10\+?|hdr|atmos|ddp\d*|dd\+?|aac|eac3|ac3|truehd|dts(?:hd)?|multi|dual|org|fhd|tam|tamil|tel|telugu|mal|malayalam|hin|hindi|kan|kannada|eng|english)\b/gi;

export const extractSeasonNumberFromFolder = (name: string): number | null => {
  const norm = name.replace(/[._-]+/g, ' ').trim();
  const m1 = norm.match(/\bSeason\s*(\d{1,2})\b/i);
  if (m1) return parseInt(m1[1], 10);
  const m2 = norm.match(/\bS(\d{1,2})\b/i);
  if (m2 && !/\bS\d{1,2}\s*[-–]\s*S?\d{1,2}\b/i.test(norm)) return parseInt(m2[1], 10);
  return null;
};

export const hasEpisodeMarker = (name: string): boolean => {
  const cleaned = String(name || '')
    .replace(MEDIA_EXT_STRIP_RE, '')
    .replace(/[._[\]()]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (!cleaned) return false;
  return (
    /\bS\d{1,2}\s*E\d{1,3}\b/i.test(cleaned) ||
    /\bE\d{1,3}\b/i.test(cleaned) ||
    /\bEp\s*\d{1,3}\b/i.test(cleaned) ||
    /\bEpisode\s*\d{1,3}\b/i.test(cleaned) ||
    /\bSeason\s*\d{1,2}\b/i.test(cleaned) ||
    /\b\d{1,2}x\d{1,3}\b/i.test(cleaned)
  );
};

export const hasExplicitSeriesFolderMarker = (name: string): boolean => {
  const cleaned = String(name || '')
    .replace(/[._[\]()]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (!cleaned) return false;
  return (
    /\b(?:Season\s*\d{1,2}|S\d{1,2}(?:\s*[-–]\s*S?\d{1,2})?)\b/i.test(cleaned) ||
    /\b(?:Complete\s+Series|Web\s*Series|TV\s*Series|Mini[\s-]?Series)\b/i.test(cleaned) ||
    hasEpisodeMarker(name)
  );
};

export const parseMediaFilename = (name: string, isFolder = false): ParsedMediaInfo => {
  const withoutExt = isFolder ? name : name.replace(MEDIA_EXT_STRIP_RE, '');
  const normalized = withoutExt
    .replace(/[._]+/g, ' ')
    .replace(/\[.*?\]/g, ' ')
    .replace(/\((?!19\d{2}|20\d{2}).*?\)/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  let resolution = '';
  if (/\b(2160p|4k|uhd)\b/i.test(normalized)) resolution = '4K';
  else if (/\b1080p\b/i.test(normalized)) resolution = '1080p';
  else if (/\b720p\b/i.test(normalized)) resolution = '720p';
  else if (/\b480p\b/i.test(normalized)) resolution = '480p';

  const hasDV = /\b(dv|dovi|dolby\s*vision)\b/i.test(normalized);
  const hasHDR = /\b(hdr|hdr10|hdr10[+])\b/i.test(normalized);

  let langCode = '';
  let langName = '';
  if (/\b(tam|tamil)\b/i.test(normalized)) { langCode = 'ta'; langName = 'Tamil'; }
  else if (/\b(mal|malayalam)\b/i.test(normalized)) { langCode = 'ml'; langName = 'Malayalam'; }
  else if (/\b(tel|telugu)\b/i.test(normalized)) { langCode = 'te'; langName = 'Telugu'; }
  else if (/\b(hin|hindi)\b/i.test(normalized)) { langCode = 'hi'; langName = 'Hindi'; }
  else if (/\b(kan|kannada)\b/i.test(normalized)) { langCode = 'kn'; langName = 'Kannada'; }
  else if (/\b(kor|korean)\b/i.test(normalized)) { langCode = 'ko'; langName = 'Korean'; }

  const badges: string[] = [];
  if (/\b(atmos)\b/i.test(normalized)) badges.push('Atmos');
  if (hasDV) badges.push('DV');
  if (hasHDR) badges.push('HDR');
  if (/\b(remux)\b/i.test(normalized)) badges.push('REMUX');
  else if (/\b(bluray|blu-ray)\b/i.test(normalized)) badges.push('BluRay');
  else if (/\b(web-dl|webdl|web\s*dl|webrip)\b/i.test(normalized)) badges.push('WEB-DL');
  if (/\b(h\s*265|x265|hevc)\b/i.test(normalized)) badges.push('HEVC');
  if (/\b(multi|dual)\b/i.test(normalized)) badges.push('Multi Audio');
  else if (langName) badges.push(langName);

  let seasonNum = 0;
  let episodeNum = 0;
  const sMatch = normalized.match(/\b(?:S|Season\s*)(\d{1,2})\b/i) || normalized.match(/\b(\d{1,2})x\d{1,3}\b/i);
  if (sMatch) seasonNum = parseInt(sMatch[1], 10);
  const eMatch = normalized.match(/\b(?:S\d{1,2}\s*E|E|Ep\s*|Episode\s*|\d{1,2}x)(\d{1,3})\b/i);
  if (eMatch && !isFolder) episodeNum = parseInt(eMatch[1], 10);

  const seMatch = normalized.match(/\b(S\d{1,2}(?:\s*E\d{1,3})?|E\d{1,3}|Ep\s*\d{1,3}|Episode\s*\d{1,3}|Season\s*\d{1,2}|\d{1,2}x\d{1,3})\b/i);
  let seasonEpisode = seMatch ? seMatch[1].replace(/\s+/g, '').toUpperCase() : '';
  if (isFolder) {
    seasonEpisode = seasonNum > 0 ? `S${seasonNum < 10 ? `0${seasonNum}` : seasonNum}` : '';
  }

  const yearMatches = Array.from(normalized.matchAll(/(?:^|[\s(\-–—])((?:19|20)\d{2})(?:[\s)\-–—]|$)/g));
  const yearMatch = yearMatches.length > 0 ? yearMatches[yearMatches.length - 1] : null;
  const year = yearMatch ? yearMatch[1] : '';

  const cutPattern = /\b(2160p|1080p|720p|480p|4k|uhd|bluray|blu-ray|remux|web-dl|webdl|webrip|web|amzn|nf|snxt|sonyliv|jiohotstar|zee5|aha|it|hdtv|ddp\d|dd[+]|\bddp\b|aac|eac3|ac3|truehd|dts|dtshd|hevc|avc|x264|x265|h\s*264|h\s*265|multi|dual|org|fhd|jpn|kor|tam|tel|mal|hin|kan|eng|esub|esubs|msub|msubs|subs|sub|uncut|remastered|imax)\b/i;
  let titlePart = normalized;
  if (seMatch && seMatch.index !== undefined && seMatch.index > 1) titlePart = titlePart.slice(0, seMatch.index);
  if (yearMatch && yearMatch.index !== undefined && yearMatch.index >= 1) {
    const beforeYear = normalized.slice(0, yearMatch.index);
    if (beforeYear.replace(/[-–—:([{\s]+$/, '').trim().length >= 1 && beforeYear.length < titlePart.length) {
      titlePart = beforeYear;
    }
  }
  const cutMatch = titlePart.match(cutPattern);
  if (cutMatch && cutMatch.index !== undefined && cutMatch.index > 1) titlePart = titlePart.slice(0, cutMatch.index);

  titlePart = titlePart
    .replace(/\((?:19|20)\d{2}\)/g, '')
    .replace(/\b(movie|jpn|kor|tam|mal|tel|hin|eng|tamil|malayalam|telugu|hindi|kannada|english|japanese|korean)\b$/gi, '')
    .replace(/[-–—:([{\s]+$/, '')
    .trim();

  const cleanTitle = titlePart || normalized.slice(0, 40);
  const searchTitle = TYPO_FIXES[cleanTitle.toLowerCase()] || cleanTitle;

  return {
    rawName: name, cleanTitle, searchTitle, year, seasonEpisode,
    seasonNum, episodeNum, resolution, badges: badges.slice(0, 4),
    hasDV, hasHDR, langCode, langName, isFolder,
  };
};

export const isVideoFileItem = (file: any): boolean => {
  if (!file || file.mimeType?.includes('folder')) return false;
  const name = String(file.name || '');
  return VIDEO_EXT_RE.test(name) || String(file.mimeType || '').startsWith('video/');
};

export const isAmbiguousDriveFolder = (item: any): boolean =>
  Boolean(item && !item.isZipEntry && item.mimeType?.includes('folder') && !hasExplicitSeriesFolderMarker(String(item.name || '')));

const normalizeComparisonTitle = (raw: string): string =>
  raw
    .replace(EDITION_WORDS_RE, ' ')
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

const isQualityOnlyTitle = (title: string): boolean => {
  const cleaned = title.replace(/[-–—\s]+/g, ' ').replace(QUALITY_STRIP_RE, ' ').replace(/\s+/g, ' ').trim();
  return cleaned.length === 0;
};

export const classifyFolderContents = (
  folderName: string,
  subFiles: any[],
  folderPath: string,
): FolderClassification => {
  const folderInfo = parseMediaFilename(folderName, false);
  const folderBase = normalizeComparisonTitle(folderInfo.cleanTitle);
  const subFolders = subFiles.filter(f => f?.mimeType?.includes('folder'));
  const rawVideos = subFiles.filter(isVideoFileItem);
  const nonSampleVideos = rawVideos.filter(f => !/\b(sample|trailer)\b/i.test(String(f.name || '')));
  const videos = nonSampleVideos.length > 0 ? nonSampleVideos : rawVideos;

  const seasonNumbers: number[] = [];
  const collectSeason = (n: number | null) => {
    if (n !== null && n > 0) seasonNumbers.push(n);
  };
  for (const sf of subFolders) collectSeason(extractSeasonNumberFromFolder(String(sf.name || '')));

  const folderExplicitSeries = hasExplicitSeriesFolderMarker(folderName);
  const subFolderExplicitSeries = subFolders.some(sf => hasExplicitSeriesFolderMarker(String(sf.name || '')));
  const videoHasEpisode = videos.some(v => hasEpisodeMarker(String(v.name || '')));

  const buildSeriesResult = (): FolderClassification => {
    for (const v of videos) {
      const p = parseMediaFilename(String(v.name || ''), false);
      if (p.seasonNum > 0) seasonNumbers.push(p.seasonNum);
    }
    collectSeason(extractSeasonNumberFromFolder(folderName));
    const minSeason = seasonNumbers.length > 0 ? Math.min(...seasonNumbers) : 1;
    const maxSeason = seasonNumbers.length > 0 ? Math.max(...seasonNumbers) : 1;
    return {
      kind: 'series',
      folderPath,
      folderName,
      title: folderInfo.cleanTitle,
      searchTitle: folderInfo.searchTitle,
      year: folderInfo.year,
      langCode: folderInfo.langCode,
      langName: folderInfo.langName,
      versions: [],
      minSeason,
      maxSeason,
    };
  };

  if (folderExplicitSeries || subFolderExplicitSeries || seasonNumbers.length > 0 || videoHasEpisode) {
    return buildSeriesResult();
  }

  if (videos.length === 0) {
    return {
      kind: 'collection',
      folderPath,
      folderName,
      title: folderInfo.cleanTitle,
      searchTitle: folderInfo.searchTitle,
      year: folderInfo.year,
      langCode: folderInfo.langCode,
      langName: folderInfo.langName,
      versions: [],
      minSeason: 0,
      maxSeason: 0,
    };
  }

  const parsedVideos = videos.map(v => ({
    file: v,
    info: parseMediaFilename(String(v.name || ''), false),
  }));

  const videoTitleBases = parsedVideos.map(({ info }) =>
    isQualityOnlyTitle(info.cleanTitle) ? folderBase : normalizeComparisonTitle(info.cleanTitle),
  );
  const nonEmptyBases = videoTitleBases.filter(Boolean);
  const uniqueBases = Array.from(new Set(nonEmptyBases));
  const uniqueYears = Array.from(new Set(parsedVideos.map(({ info }) => info.year).filter(Boolean)));

  const sameTitleGroup =
    uniqueBases.length <= 1 ||
    (Boolean(folderBase) && nonEmptyBases.every(b => b === folderBase || b.startsWith(`${folderBase} `)));

  if (videos.length === 1 && subFolders.length === 0) {
    const only = parsedVideos[0].info;
    return {
      kind: 'movie',
      folderPath,
      folderName,
      title: folderInfo.cleanTitle,
      searchTitle: folderInfo.searchTitle,
      year: folderInfo.year || only.year,
      langCode: folderInfo.langCode || only.langCode,
      langName: folderInfo.langName || only.langName,
      versions: videos.map(v => ({
        ...v,
        _parentPath: folderPath,
        _folderName: folderName,
        _folderYear: folderInfo.year || only.year,
        _folderLangCode: folderInfo.langCode || only.langCode,
      })),
      minSeason: 0,
      maxSeason: 0,
    };
  }

  if (videos.length >= 2 && sameTitleGroup && subFolders.length === 0) {
    const inferredYear = folderInfo.year || uniqueYears[0] || '';
    const langMatch = parsedVideos.find(({ info }) => Boolean(info.langCode))?.info;
    const inferredLangCode = folderInfo.langCode || langMatch?.langCode || '';
    const inferredLangName = folderInfo.langName || langMatch?.langName || '';
    return {
      kind: 'movie',
      folderPath,
      folderName,
      title: folderInfo.cleanTitle,
      searchTitle: folderInfo.searchTitle,
      year: inferredYear,
      langCode: inferredLangCode,
      langName: inferredLangName,
      versions: videos.map(v => ({
        ...v,
        _parentPath: folderPath,
        _folderName: folderName,
        _folderYear: inferredYear,
        _folderLangCode: inferredLangCode,
      })),
      minSeason: 0,
      maxSeason: 0,
    };
  }

  // Multiple distinct titles inside a folder without season/episode markers:
  // If files carry separate release years or subfolders exist, treat as a browsable collection folder;
  // otherwise distinct episode titles (e.g. "01 - Pilot", "02 - ...") belong to a series.
  if (subFolders.length > 0 || uniqueYears.length > 1) {
    return {
      kind: 'collection',
      folderPath,
      folderName,
      title: folderInfo.cleanTitle,
      searchTitle: folderInfo.searchTitle,
      year: folderInfo.year,
      langCode: folderInfo.langCode,
      langName: folderInfo.langName,
      versions: [],
      minSeason: 0,
      maxSeason: 0,
    };
  }

  return buildSeriesResult();
};

export const metadataCacheKey = (info: ParsedMediaInfo): string => {
  const isTv = Boolean(info.seasonEpisode || info.seasonNum > 0 || info.isFolder);
  const ver = /\.m2ts$/i.test(info.rawName) ? 'v9' : 'v8';
  const base = `${info.searchTitle.toLowerCase()}_${info.year}_${info.langCode}`;
  return isTv ? `meta_tv_${ver}_${base}` : `meta_${ver}_${base}`;
};

export const legacyMetadataCacheKeys = (info: ParsedMediaInfo): string[] => {
  const base = `${info.searchTitle.toLowerCase()}_${info.year}_${info.langCode}`;
  return [
    `meta_tv_v8_${base}`,
    `meta_tv_v9_${base}`,
  ];
};

export const isStaleMovieCache = (cached: any, info: ParsedMediaInfo): boolean => {
  if (!cached || info.isFolder || info.seasonEpisode || info.seasonNum > 0) return false;
  if (cached.mediaType === 'tv') return true;
  const infoYear = info.year ? parseInt(info.year, 10) : 0;
  const cachedYear = cached.year ? parseInt(String(cached.year).slice(0, 4), 10) : 0;
  return infoYear > 0 && cachedYear > 0 && Math.abs(cachedYear - infoYear) > 2;
};

export const selectBestTmdbMovieMatch = (
  results: any[],
  searchTitle: string,
  year: string,
  langCode = '',
): any | null => {
  const movies = (results || []).filter(
    (r: any) => r && r.poster_path && r.media_type !== 'person' && r.media_type !== 'tv',
  );
  if (movies.length === 0) return null;
  const normalize = (val: string) =>
    String(val || '')
      .toLowerCase()
      .replace(/[^a-z0-9\s]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  const cleanTarget = normalize(searchTitle);
  const targetYear = year ? parseInt(year, 10) : 0;

  const score = (r: any): number => {
    const rYear = parseInt(String(r.release_date || '').slice(0, 4), 10) || 0;
    const titleMain = normalize(r.title || '');
    const titleOrig = normalize(r.original_title || '');
    const exactTitle = titleMain === cleanTarget || titleOrig === cleanTarget;
    const partialTitle = !exactTitle && (titleMain.includes(cleanTarget) || titleOrig.includes(cleanTarget));
    let total = 0;
    if (exactTitle) total += 100;
    else if (partialTitle) total += 30;
    if (targetYear > 0 && rYear > 0) {
      const diff = Math.abs(rYear - targetYear);
      if (diff === 0) total += 80;
      else if (diff === 1) total += 40;
      else total -= 40;
    }
    if (langCode && r.original_language === langCode) total += 50;
    total += Math.min(10, Number(r.popularity || 0) / 20);
    return total;
  };

  let best = movies[0];
  let bestScore = score(best);
  for (let i = 1; i < movies.length; i++) {
    const s = score(movies[i]);
    if (s > bestScore) {
      best = movies[i];
      bestScore = s;
    }
  }
  return best;
};
