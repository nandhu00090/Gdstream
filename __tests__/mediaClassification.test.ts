import {
  classifyFolderContents,
  hasExplicitSeriesFolderMarker,
  hasEpisodeMarker,
  isAmbiguousDriveFolder,
  isStaleMovieCache,
  legacyMetadataCacheKeys,
  metadataCacheKey,
  parseMediaFilename,
  selectBestTmdbMovieMatch,
} from '../mediaClassification';

describe('parseMediaFilename title and year extraction', () => {
  test('extracts title and year from hyphenated folder names like 96 - 2018', () => {
    const info = parseMediaFilename('96 - 2018', false);
    expect(info.cleanTitle).toBe('96');
    expect(info.searchTitle).toBe('96');
    expect(info.year).toBe('2018');
    expect(info.seasonEpisode).toBe('');
    expect(info.seasonNum).toBe(0);
    expect(info.episodeNum).toBe(0);

    const compact = parseMediaFilename('96-2018', false);
    expect(compact.cleanTitle).toBe('96');
    expect(compact.year).toBe('2018');

    const titleWithYear = parseMediaFilename('1917 - 2019', false);
    expect(titleWithYear.cleanTitle).toBe('1917');
    expect(titleWithYear.year).toBe('2019');
  });

  test('detects episode markers across SxxExx, E01, Ep 1, Episode 1, Season 1, and 1x01', () => {
    expect(hasEpisodeMarker('Show.S01E01.1080p.mkv')).toBe(true);
    expect(hasEpisodeMarker('Show E02.mkv')).toBe(true);
    expect(hasEpisodeMarker('Show.Ep.03.mkv')).toBe(true);
    expect(hasEpisodeMarker('Show Episode 4.mp4')).toBe(true);
    expect(hasEpisodeMarker('Show Season 1.mkv')).toBe(true);
    expect(hasEpisodeMarker('Show.1x05.mkv')).toBe(true);
    expect(hasEpisodeMarker('96.2018.1080p.WEB-DL.mkv')).toBe(false);
    expect(hasEpisodeMarker('96.2018.1920x1080.BluRay.mkv')).toBe(false);
  });

  test('only flags explicit series/season folder markers', () => {
    expect(hasExplicitSeriesFolderMarker('96 - 2018')).toBe(false);
    expect(hasExplicitSeriesFolderMarker('Dark (2017)')).toBe(false);
    expect(hasExplicitSeriesFolderMarker('Breaking Bad S01-S05')).toBe(true);
    expect(hasExplicitSeriesFolderMarker('Wednesday Season 1')).toBe(true);
    expect(hasExplicitSeriesFolderMarker('Chernobyl Complete Series')).toBe(true);
    expect(hasExplicitSeriesFolderMarker('Tamil Web Series')).toBe(true);
    expect(isAmbiguousDriveFolder({ name: '96 - 2018', mimeType: 'application/vnd.google-apps.folder' })).toBe(true);
    expect(isAmbiguousDriveFolder({ name: 'Season 1', mimeType: 'application/vnd.google-apps.folder' })).toBe(false);
  });
});

describe('classifyFolderContents multi-version movie vs series', () => {
  test('classifies 96 - 2018 with 2 quality encodes as a multi-version Movie, not S01 Series', () => {
    const folderPath = '/0:/96%20-%202018/';
    const files = [
      { id: 'f1', name: '96 (2018) Tamil 1080p WEB-DL DDP5.1 x264.mkv', mimeType: 'video/x-matroska' },
      { id: 'f2', name: '96.2018.720p.BluRay.x265.HEVC.mkv', mimeType: 'video/x-matroska' },
      { id: 's1', name: '96.2018.eng.srt', mimeType: 'application/x-subrip' },
    ];
    const classified = classifyFolderContents('96 - 2018', files, folderPath);
    expect(classified.kind).toBe('movie');
    expect(classified.title).toBe('96');
    expect(classified.year).toBe('2018');
    expect(classified.langCode).toBe('ta');
    expect(classified.versions).toHaveLength(2);
    expect(classified.versions[0]._parentPath).toBe(folderPath);
    expect(classified.versions[0]._folderYear).toBe('2018');
    expect(classified.versions[0]._folderLangCode).toBe('ta');
  });

  test('classifies quality-only filenames inside a movie folder as versions of that movie', () => {
    const classified = classifyFolderContents(
      'Vikram (2022)',
      [
        { name: '2160p.DV.HDR.mkv', mimeType: 'video/x-matroska' },
        { name: '1080p.WEB-DL.mkv', mimeType: 'video/x-matroska' },
      ],
      '/0:/Vikram%20(2022)/',
    );
    expect(classified.kind).toBe('movie');
    expect(classified.title).toBe('Vikram');
    expect(classified.year).toBe('2022');
    expect(classified.versions).toHaveLength(2);
  });

  test('classifies folders with season subfolders or episode files as Series', () => {
    const withSeasons = classifyFolderContents(
      'Dark (2017)',
      [
        { name: 'Season 1', mimeType: 'application/vnd.google-apps.folder' },
        { name: 'Season 2', mimeType: 'application/vnd.google-apps.folder' },
      ],
      '/0:/Dark%20(2017)/',
    );
    expect(withSeasons.kind).toBe('series');
    expect(withSeasons.minSeason).toBe(1);
    expect(withSeasons.maxSeason).toBe(2);

    const withEpisodes = classifyFolderContents(
      'Dark (2017)',
      [
        { name: 'Dark.S01E01.1080p.mkv', mimeType: 'video/x-matroska' },
        { name: 'Dark.S01E02.1080p.mkv', mimeType: 'video/x-matroska' },
      ],
      '/0:/Dark%20(2017)/',
    );
    expect(withEpisodes.kind).toBe('series');
    expect(withEpisodes.minSeason).toBe(1);
    expect(withEpisodes.maxSeason).toBe(1);

    const withEpTag = classifyFolderContents(
      '96 - 2018',
      [
        { name: '96 Ep01.mkv', mimeType: 'video/x-matroska' },
        { name: '96 Ep02.mkv', mimeType: 'video/x-matroska' },
      ],
      '/0:/96%20-%202018/',
    );
    expect(withEpTag.kind).toBe('series');
  });

  test('treats folders containing different movies as browsable collections, not S01 series', () => {
    const collection = classifyFolderContents(
      'Tamil Movies',
      [
        { name: '96 (2018).mkv', mimeType: 'video/x-matroska' },
        { name: 'Vikram (2022).mkv', mimeType: 'video/x-matroska' },
      ],
      '/0:/Tamil%20Movies/',
    );
    expect(collection.kind).toBe('collection');
  });
});

describe('TMDB movie matching and cache invalidation', () => {
  test('selects 2018 Tamil movie 96 over unrelated or newer results', () => {
    const results = [
      { id: 999, title: '96', release_date: '2025-02-01', original_language: 'en', poster_path: '/2025.jpg', popularity: 50 },
      { id: 523915, title: '96', release_date: '2018-10-04', original_language: 'ta', poster_path: '/96_ta.jpg', popularity: 25 },
      { id: 888, title: '96', release_date: '2018-05-10', original_language: 'en', poster_path: '/96_en.jpg', popularity: 10 },
    ];
    const match = selectBestTmdbMovieMatch(results, '96', '2018', 'ta');
    expect(match?.id).toBe(523915);
    expect(match?.release_date).toBe('2018-10-04');
  });

  test('separates movie and tv cache keys and detects stale cached TV entries', () => {
    const movieInfo = parseMediaFilename('96 - 2018', false);
    const tvInfo = parseMediaFilename('96 - 2018', true);
    expect(metadataCacheKey(movieInfo)).toBe('meta_v8_96_2018_');
    expect(metadataCacheKey(tvInfo)).toBe('meta_tv_v8_96_2018_');
    expect(legacyMetadataCacheKeys(movieInfo)).toContain('meta_tv_v8_96_2018_');
    expect(isStaleMovieCache({ title: '96', year: '2025', mediaType: 'tv' }, movieInfo)).toBe(true);
    expect(isStaleMovieCache({ title: '96', year: '2025' }, movieInfo)).toBe(true);
    expect(isStaleMovieCache({ title: '96', year: '2018', mediaType: 'movie' }, movieInfo)).toBe(false);
  });
});
