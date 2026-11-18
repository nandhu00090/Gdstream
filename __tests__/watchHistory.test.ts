import { linkedWatchKeys, watchAliases } from '../watchHistory';
import { isoMovieDetails } from '../archivePlayback';

test('removing an ISO follows persisted outer, inner and movie aliases transitively', () => {
  const card = { name: 'Movie.2009.iso', id: 'outer', link: 'https://library/Movie.2009.iso', metadata: { tmdbId: 42 } };
  const movie = isoMovieDetails(card, [{ name: 'Movie.2009 [Main Movie - 2h].m2ts', id: 'entry', link: 'http://127.0.0.1:99/zip/entry/feature.m2ts' }]);
  const aliases = { [card.name]: watchAliases(movie), 'movie:42': ['old-playlist-id'] };
  const legacy = 'Movie.2009 [Main Stream - 00001.M2TS].m2ts';
  const keys = linkedWatchKeys(card, [legacy, 'Other.iso', 'Movie.2012 [Main Movie - 2h].m2ts'], aliases);
  expect(keys).toEqual(expect.arrayContaining([...watchAliases(movie), legacy, 'old-playlist-id', 'tmdb:42']));
  expect(keys).not.toContain('Other.iso');
  expect(keys).not.toContain('Movie.2012 [Main Movie - 2h].m2ts');
  expect(linkedWatchKeys({ id: 'entry' }, [], aliases)).toContain(card.name);
});

test('version removal includes every linked version without matching unrelated movies', () => {
  const item = { name: 'Movie.iso', _versions: [{ name: 'Movie.iso' }, { name: 'Movie.4K.iso' }] };
  const keys = linkedWatchKeys(item, ['Movie.4K [Main Movie - 120m].m2ts', 'Movies [Main Movie].m2ts'], {});
  expect(keys).toContain('Movie.4K [Main Movie - 120m].m2ts');
  expect(keys).not.toContain('Movies [Main Movie].m2ts');
});
