import {
  buildTvSearchQueries,
  classifyFolderContents,
  hasExplicitSeriesFolderMarker,
  hasEpisodeMarker,
  highestResolutionLabel,
  isAmbiguousDriveFolder,
  isStaleMovieCache,
  isUsableCachedMetadata,
  legacyMetadataCacheKeys,
  metadataCacheKey,
  parseMediaFilename,
  processCatalogItems,
  selectBestTmdbMovieMatch,
  selectBestTmdbTvMatch,
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
    expect(titleWithYear.yearExplicit).toBe(true);
  });

  test('parses multi-year movie filenames like 2012.2009.1080p as title 2012 and year 2009 with yearExplicit', () => {
    const movie = parseMediaFilename('2012.2009.1080p.JIO.WEB-DL.DDP5.1.H264.mkv', false);
    expect(movie.cleanTitle).toBe('2012');
    expect(movie.searchTitle).toBe('2012');
    expect(movie.year).toBe('2009');
    expect(movie.yearExplicit).toBe(true);
    expect(movie.resolution).toBe('1080p');

    const unyeared = parseMediaFilename('The Hunting Party', true);
    expect(unyeared.year).toBe('');
    expect(unyeared.yearExplicit).toBe(false);

    const modifiedFallback = parseMediaFilename('C B Strike: Troubled Blood', true, '2025-04-12T10:00:00Z');
    expect(modifiedFallback.cleanTitle).toBe('C B Strike: Troubled Blood');
    expect(modifiedFallback.year).toBe('2025');
    expect(modifiedFallback.yearExplicit).toBe(false);
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
    expect(isUsableCachedMetadata({ title: 'Blue Lock', mediaType: 'tv' }, parseMediaFilename('Blue Lock', true))).toBe(false);
    expect(isUsableCachedMetadata({ title: '2012 2009', poster: 'https://img/p.jpg' }, parseMediaFilename('2012.2009.1080p.mkv', false))).toBe(false);
    expect(isUsableCachedMetadata({ title: '2012', poster: 'https://img/2012.jpg', year: '2009', mediaType: 'movie' }, parseMediaFilename('2012.2009.1080p.mkv', false))).toBe(true);
  });

  test('builds TV search fallbacks for colon/initials titles and ignores non-explicit modifiedTime years', () => {
    const queries = buildTvSearchQueries('C B Strike: Troubled Blood');
    expect(queries[0]).toBe('C B Strike: Troubled Blood');
    expect(queries).toContain('C B Strike');
    expect(queries).toContain('C.B. Strike');

    const tvResults = [
      { id: 73493, name: 'C.B. Strike', first_air_date: '2017-08-27', poster_path: '/strike.jpg', popularity: 35 },
    ];
    // Even if Drive modifiedTime fallback produced 2025 (yearExplicit = false), 2017 show still matches cleanly.
    const match = selectBestTmdbTvMatch(tvResults, 'C B Strike: Troubled Blood', '2025', false);
    expect(match?.id).toBe(73493);
  });
});

describe('processCatalogItems root and search flattening', () => {
  test('flattens 96 - 2018 multi-version movie folder into a single 2 VERSIONS card on homepage and search with no folder card duplicate', () => {
    const folderPath = '/0:/96%20-%202018/';
    const child720 = { id: 'f2', name: '96 (2018) Tamil 720p BluRay x265.mkv', mimeType: 'video/x-matroska', modifiedTime: '2026-10-01T10:00:00Z' };
    const child1080 = { id: 'f1', name: '96 (2018) Tamil 1080p WEB-DL x264.mkv', mimeType: 'video/x-matroska', modifiedTime: '2026-10-01T10:05:00Z' };
    const movieClassified = classifyFolderContents('96 - 2018', [child720, child1080], folderPath);

    const seriesClassified = classifyFolderContents(
      'Dark (2017)',
      [
        { id: 's1', name: 'Dark.S01E01.1080p.mkv', mimeType: 'video/x-matroska' },
        { id: 's2', name: 'Dark.S01E02.1080p.mkv', mimeType: 'video/x-matroska' },
      ],
      '/0:/Dark%20(2017)/',
    );
    const collectionClassified = classifyFolderContents(
      'Tamil Movies',
      [
        { id: 'm1', name: 'Leo (2023).mkv', mimeType: 'video/x-matroska' },
        { id: 'm2', name: 'Vikram (2022).mkv', mimeType: 'video/x-matroska' },
      ],
      '/0:/Tamil%20Movies/',
    );

    const rawHome = [
      { id: 'dir_96', name: '96 - 2018', mimeType: 'application/vnd.google-apps.folder', modifiedTime: '2026-10-01T10:05:00Z' },
      { id: 'dir_dark', name: 'Dark (2017)', mimeType: 'application/vnd.google-apps.folder', modifiedTime: '2026-09-01T10:00:00Z' },
      { id: 'dir_tamil', name: 'Tamil Movies', mimeType: 'application/vnd.google-apps.folder', modifiedTime: '2026-08-01T10:00:00Z' },
    ];
    const folderClassifications = {
      [folderPath]: movieClassified,
      '/0:/Dark%20(2017)/': seriesClassified,
      '/0:/Tamil%20Movies/': collectionClassified,
    };
    const getFolderPath = (item: any) => `/0:/${encodeURIComponent(item.name)}/`;

    const homeCards = processCatalogItems({
      rawList: rawHome,
      isEpFolder: false,
      searchQuery: '',
      activeCategory: 'all',
      sortBy: 'newest',
      folderClassifications,
      getFolderPath,
    });
    expect(homeCards).toHaveLength(3);
    const flattened96 = homeCards[0];
    expect(flattened96._movieFolder).toBe(true);
    expect(flattened96._folderName).toBe('96 - 2018');
    expect(flattened96.mimeType).toBe('video/x-matroska');
    expect(flattened96.name).toBe(child1080.name);
    expect(flattened96._versions).toHaveLength(2);
    expect(flattened96.versions).toHaveLength(2);
    expect(flattened96._versions[0]._parentPath).toBe(folderPath);
    expect(flattened96._versions[1]._parentPath).toBe(folderPath);
    expect(highestResolutionLabel(flattened96)).toBe('1080p');
    expect(parseMediaFilename(flattened96.name, false).badges).toContain('Tamil');
    expect(homeCards.filter(c => c._folderName === '96 - 2018' || c.name === '96 - 2018')).toHaveLength(1);

    // Search results for "96" return exactly 1 flattened movie card (no duplicate folder card).
    const searchCards = processCatalogItems({
      rawList: rawHome,
      isEpFolder: false,
      searchQuery: '96',
      activeCategory: 'all',
      sortBy: 'newest',
      folderClassifications,
      getFolderPath,
    });
    expect(searchCards).toHaveLength(1);
    expect(searchCards[0]._movieFolder).toBe(true);
    expect(searchCards[0]._versions).toHaveLength(2);

    // Category filters: appears in Movies, excluded from Series.
    const movieTab = processCatalogItems({
      rawList: rawHome,
      isEpFolder: false,
      searchQuery: '',
      activeCategory: 'movies',
      sortBy: 'newest',
      folderClassifications,
      getFolderPath,
    });
    expect(movieTab.map(c => c._folderName)).toEqual(['96 - 2018']);

    const seriesTab = processCatalogItems({
      rawList: rawHome,
      isEpFolder: false,
      searchQuery: '',
      activeCategory: 'series',
      sortBy: 'newest',
      folderClassifications,
      getFolderPath,
    });
    expect(seriesTab.map(c => c.name)).toEqual(['Dark (2017)']);
  });
});
