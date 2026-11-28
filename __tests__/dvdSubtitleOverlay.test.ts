import {dvdCueAt, dvdCueRect} from '../DvdSubtitleOverlay';

const cue = {showUs: 12_000_000, stopUs: 14_000_000, x: 72, y: 360, width: 144, height: 48,
  canvasHeight: 480, uri: 'data:image/png;base64,...'};

it('uses an inclusive show time and exclusive stop time in either seek direction', () => {
  for (const time of [12_000_000, 13_999_999]) expect(dvdCueAt([cue], time)).toBe(cue);
  for (const time of [14_000_000, 11_999_999]) expect(dvdCueAt([cue], time)).toBeUndefined();
});

it('places DVD canvas pixels inside the displayed video on fit and zoom', () => {
  expect(dvdCueRect(cue, 1200, 600, 1.5, false)).toEqual({left: 240, top: 450, width: 180, height: 60});
  expect(dvdCueRect(cue, 1200, 600, 1.5, true)).toEqual({left: 120, top: 500, width: 240, height: 80});
});
