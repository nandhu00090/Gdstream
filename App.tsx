import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import {
  View,
  Text,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
  Alert,
  NativeModules,
  TextInput,
  ScrollView,
  BackHandler,
  Animated,
  Platform,
  DeviceEventEmitter,
  Image,
  Modal,
  PanResponder,
  Dimensions,
  StatusBar,
  RefreshControl,
} from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';
import Orientation from 'react-native-orientation-locker';
import Icon from 'react-native-vector-icons/MaterialIcons';
import AsyncStorage from '@react-native-async-storage/async-storage';

const { VideoPlayerManager, SubtitleFileModule, DownloadEngine } = NativeModules;
const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin';
const PASSWORD = '629175';
const IS_TV = Platform.isTV;

const DEFAULT_TMDB_API_KEY = '';

interface ParsedMediaInfo {
  rawName: string;
  cleanTitle: string;
  searchTitle: string;
  year: string;
  seasonEpisode: string;
  resolution: string;
  badges: string[];
  hasDV: boolean;
  hasHDR: boolean;
  isFolder: boolean;
}

interface MediaMetadata {
  poster?: string;
  backdrop?: string;
  rating?: string;
  overview?: string;
  year?: string;
  cast?: string;
}

interface DownloadItem {
  id: string;
  fileName: string;
  title: string;
  poster: string;
  url: string;
  filePath: string;
  totalBytes: number;
  downloadedBytes: number;
  speedBytesPerSec: number;
  progress: number;
  status: 'downloading' | 'paused' | 'completed' | 'error';
  threadsCount: number;
}

const TYPO_FIXES: Record<string, string> = {
  'attach on titan': 'Attack on Titan',
};

const formatBytes = (bytes: number) => {
  if (!bytes || bytes <= 0) return '0 MB';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let val = bytes;
  let idx = 0;
  while (val >= 1024 && idx < units.length - 1) {
    val /= 1024;
    idx++;
  }
  return `${val.toFixed(idx >= 3 ? 2 : 1)} ${units[idx]}`;
};

const parseMediaFilename = (name: string, isFolder = false): ParsedMediaInfo => {
  const withoutExt = isFolder
    ? name
    : name.replace(/\.(mkv|mp4|avi|mov|webm|m4v|ts)$/i, '');

  const normalized = withoutExt
    .replace(/[._]+/g, ' ')
    .replace(/\[.*?\]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

  let resolution = '';
  if (/\b(2160p|4k|uhd)\b/i.test(normalized)) resolution = '4K';
  else if (/\b1080p\b/i.test(normalized)) resolution = '1080p';
  else if (/\b720p\b/i.test(normalized)) resolution = '720p';
  else if (/\b480p\b/i.test(normalized)) resolution = '480p';

  const hasDV = /\b(dv|dovi|dolby\s*vision)\b/i.test(normalized);
  const hasHDR = /\b(hdr|hdr10|hdr10\+)\b/i.test(normalized);

  const badges: string[] = [];
  if (/\b(atmos)\b/i.test(normalized)) badges.push('Atmos');
  if (hasDV) badges.push('DV');
  if (hasHDR) badges.push('HDR');
  if (/\b(remux)\b/i.test(normalized)) badges.push('REMUX');
  else if (/\b(bluray|blu-ray)\b/i.test(normalized)) badges.push('BluRay');
  else if (/\b(web-dl|webdl|web\s*dl|webrip)\b/i.test(normalized))
    badges.push('WEB-DL');

  if (/\b(h\s*265|x265|hevc)\b/i.test(normalized)) badges.push('HEVC');
  if (/\b(multi|dual)\b/i.test(normalized)) badges.push('Multi Audio');
  else if (/\b(tam|tamil)\b/i.test(normalized)) badges.push('Tamil');
  else if (/\b(mal|malayalam)\b/i.test(normalized)) badges.push('Malayalam');
  else if (/\b(hin|hindi)\b/i.test(normalized)) badges.push('Hindi');
  else if (/\b(tel|telugu)\b/i.test(normalized)) badges.push('Telugu');

  const seMatch = normalized.match(
    /\b(S\d{1,2}(?:\s*E\d{1,3})?|E\d{1,3}|Season\s*\d{1,2})\b/i,
  );
  const seasonEpisode = seMatch
    ? seMatch[1].replace(/\s+/g, '').toUpperCase()
    : '';

  const yearMatch = normalized.match(/(?:^|[\s(])((?:19|20)\d{2})(?:[\s)]|$)/);
  const year = yearMatch ? yearMatch[1] : '';

  const cutPattern =
    /\b(2160p|1080p|720p|480p|4k|uhd|bluray|blu-ray|remux|web-dl|webdl|webrip|web|amzn|nf|snxt|it|hdtv|ddp\d|dd\+|aac|hevc|x264|x265|h\s*264|h\s*265|multi|dual|org)\b/i;

  let titlePart = normalized;

  if (seMatch && seMatch.index !== undefined && seMatch.index > 1) {
    titlePart = titlePart.slice(0, seMatch.index);
  }
  if (yearMatch && yearMatch.index !== undefined && yearMatch.index > 1) {
    const beforeYear = normalized.slice(0, yearMatch.index);
    if (beforeYear.length < titlePart.length) {
      titlePart = beforeYear;
    }
  }

  const cutMatch = titlePart.match(cutPattern);
  if (cutMatch && cutMatch.index !== undefined && cutMatch.index > 1) {
    titlePart = titlePart.slice(0, cutMatch.index);
  }

  titlePart = titlePart
    .replace(/\((?:19|20)\d{2}\)/g, '')
    .replace(
      /\s+\b(tamil|malayalam|telugu|hindi|kannada|english|tam|mal|tel|hin|eng)\b$/i,
      '',
    )
    .replace(/[-–—:([{\s]+$/, '')
    .trim();

  const cleanTitle = titlePart || normalized.slice(0, 40);
  const lowerClean = cleanTitle.toLowerCase();
  const searchTitle = TYPO_FIXES[lowerClean] || cleanTitle;

  return {
    rawName: name,
    cleanTitle,
    searchTitle,
    year,
    seasonEpisode,
    resolution,
    badges: badges.slice(0, 4),
    hasDV,
    hasHDR,
    isFolder,
  };
};

const FocusableButton = ({
  style,
  focusedStyle,
  onPress,
  children,
  hasTVPreferredFocus,
  activeOpacity = 0.8,
  ...rest
}: any) => {
  const [isFocused, setIsFocused] = useState(false);
  return (
    <TouchableOpacity
      focusable={IS_TV ? true : undefined}
      hasTVPreferredFocus={IS_TV ? hasTVPreferredFocus : undefined}
      activeOpacity={activeOpacity}
      onPress={onPress}
      onFocus={IS_TV ? () => setIsFocused(true) : undefined}
      onBlur={IS_TV ? () => setIsFocused(false) : undefined}
      style={[
        style,
        IS_TV && isFocused && styles.tvFocused,
        IS_TV && isFocused && focusedStyle,
      ]}
      {...rest}>
      {children}
    </TouchableOpacity>
  );
};

const formatTime = (seconds: number) => {
  if (isNaN(seconds) || seconds < 0) return '00:00';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
  return `${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
};

const parseCueTime = (ts: string) => {
  const m = ts.trim().match(/(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})/);
  if (!m) return -1;
  const h = parseInt(m[1] || '0', 10);
  const min = parseInt(m[2], 10);
  const sec = parseInt(m[3], 10);
  const ms = parseInt(m[4].padEnd(3, '0'), 10);
  return h * 3600 + min * 60 + sec + ms / 1000;
};

const parseSubtitleFile = (raw: string) => {
  const cues: Array<{ start: number; end: number; text: string }> = [];
  const blocks = raw
    .replace(/\r/g, '')
    .replace(/^WEBVTT.*\n/i, '')
    .split(/\n\n+/);
  for (const block of blocks) {
    const lines = block.split('\n').filter(l => l.trim() !== '');
    if (!lines.length) continue;
    const timeLineIdx = lines.findIndex(l => l.includes('-->'));
    if (timeLineIdx === -1) continue;
    const [startStr, endStr] = lines[timeLineIdx].split('-->');
    const start = parseCueTime(startStr);
    const end = parseCueTime(endStr);
    if (start < 0 || end < 0) continue;
    const text = lines
      .slice(timeLineIdx + 1)
      .map(l => l.replace(/<[^>]+>/g, '').trim())
      .filter(l => l !== '')
      .join('\n');
    if (text) cues.push({ start, end, text });
  }
  return cues;
};

const App = () => {
  const [files, setFiles] = useState<any[]>([]);
  const [filteredFiles, setFilteredFiles] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [pathStack, setPathStack] = useState(['/0:/']);
  const [currentPath, setCurrentPath] = useState('/0:/');
  const [searchQuery, setSearchQuery] = useState('');
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [activeTab, setActiveTab] = useState<'catalog' | 'downloads'>('catalog');

  const [metadataMap, setMetadataMap] = useState<Record<string, MediaMetadata>>(
    {},
  );
  const [savedTimesMap, setSavedTimesMap] = useState<Record<string, number>>(
    {},
  );
  const [tmdbKey, setTmdbKey] = useState(DEFAULT_TMDB_API_KEY);
  const [showKeyModal, setShowKeyModal] = useState(false);
  const [tempKeyInput, setTempKeyInput] = useState('');

  // Downloads State
  const [downloads, setDownloads] = useState<DownloadItem[]>([]);

  // Dolby Vision vs HDR10 Mode State
  const [activeHdrMode, setActiveHdrMode] = useState<'dv' | 'hdr' | 'sdr'>('dv');
  const [showHdrChoiceModal, setShowHdrChoiceModal] = useState(false);
  const [playerInstanceKey, setPlayerInstanceKey] = useState(0);

  const [selectedFile, setSelectedFile] = useState<any>(null);
  const [playMode, setPlayMode] = useState<string | null>(null);
  const [resizeMode, setResizeMode] = useState<'contain' | 'cover'>('contain');
  const [showControls, setShowControls] = useState(true);
  const [isLocked, setIsLocked] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [isVideoBuffering, setIsVideoBuffering] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(IS_TV);
  const [playbackRate, setPlaybackRate] = useState(1.0);

  // Brightness & Volume Swipe State
  const [brightnessLevel, setBrightnessLevel] = useState(0.65);
  const [volumeLevel, setVolumeLevel] = useState(0.8);
  const brightnessRef = useRef(0.65);
  const volumeRef = useRef(0.8);
  const [gestureIndicator, setGestureIndicator] = useState<{
    visible: boolean;
    type: 'brightness' | 'volume';
    value: number;
  }>({ visible: false, type: 'volume', value: 0.8 });
  const gestureHideTimer = useRef<any>(null);

  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [progressWidth, setProgressWidth] = useState(0);
  const videoRef = useRef<any>(null);
  const externalAudioRef = useRef<any>(null);
  const externalAudioTimeRef = useRef(0);
  const lastAudioSyncMs = useRef(0);
  const switchingEpisode = useRef(false);
  const isLoggedIn = useRef(false);
  const lastSavedSec = useRef(-1);
  const fetchedKeysRef = useRef<Set<string>>(new Set());

  const [audioTracks, setAudioTracks] = useState<any[]>([]);
  const [textTracks, setTextTracks] = useState<any[]>([]);
  const [selectedAudio, setSelectedAudio] = useState<any>(undefined);
  const [selectedText, setSelectedText] = useState<any>({ type: 'disabled' });
  const [activeMenu, setActiveMenu] = useState<string | null>(null);

  const [subtitleSize, setSubtitleSize] = useState(20);
  const [subtitleDelay, setSubtitleDelay] = useState(0);
  const [audioDelay, setAudioDelay] = useState(0);
  const [externalSubtitle, setExternalSubtitle] = useState<{
    name: string;
    content: string;
  } | null>(null);
  const [externalAudio, setExternalAudio] = useState<{
    name: string;
    uri: string;
  } | null>(null);

  const isExternalSubtitleSelected =
    selectedText?.type === 'external' && !!externalSubtitle;

  const isExternalAudioSelected =
    selectedAudio?.type === 'external' && !!externalAudio;

  const subtitlesActive =
    !!selectedText &&
    selectedText.type !== 'disabled' &&
    selectedText.type !== 'external';

  const parsedExternalCues = useMemo(() => {
    if (!externalSubtitle?.content) return [];
    return parseSubtitleFile(externalSubtitle.content);
  }, [externalSubtitle]);

  const activeExternalSubtitleText = useMemo(() => {
    if (!isExternalSubtitleSelected || !parsedExternalCues.length) return '';
    const effectiveTime = currentTime - subtitleDelay;
    const cue = parsedExternalCues.find(
      c => effectiveTime >= c.start && effectiveTime <= c.end,
    );
    return cue ? cue.text : '';
  }, [
    isExternalSubtitleSelected,
    parsedExternalCues,
    currentTime,
    subtitleDelay,
  ]);

  const [seekOverlay, setSeekOverlay] = useState({
    visible: false,
    icon: '',
    time: 0,
    position: 'center',
  });
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const accumulatedSeek = useRef(0);
  const seekBaseTime = useRef<number | null>(null);
  const multiTapTimer = useRef<any>(null);
  const singleTapTimer = useRef<any>(null);
  const lastTapTime = useRef(0);
  const lastTapDirection = useRef<boolean | null>(null);

  useEffect(() => {
    if (IS_TV) {
      Orientation.lockToLandscape();
    }
    AsyncStorage.getItem('gdstream_tmdb_key').then(savedKey => {
      if (savedKey) {
        setTmdbKey(savedKey);
        setTempKeyInput(savedKey);
      }
    });
    if (VideoPlayerManager?.getSystemLevels) {
      VideoPlayerManager.getSystemLevels()
        .then((res: any) => {
          if (typeof res?.brightness === 'number') {
            setBrightnessLevel(res.brightness);
            brightnessRef.current = res.brightness;
          }
          if (typeof res?.volume === 'number') {
            setVolumeLevel(res.volume);
            volumeRef.current = res.volume;
          }
        })
        .catch(() => {});
    }
    if (DownloadEngine?.getDownloads) {
      DownloadEngine.getDownloads()
        .then((list: DownloadItem[]) => {
          if (Array.isArray(list)) setDownloads(list);
        })
        .catch(() => {});
    }
    const dlSub = DeviceEventEmitter.addListener(
      'onDownloadUpdate',
      (list: DownloadItem[]) => {
        if (Array.isArray(list)) setDownloads([...list]);
      },
    );
    fetchDirectory('/0:/', false);
    return () => dlSub.remove();
  }, []);

  useEffect(() => {
    if (playMode === 'internal') {
      Orientation.lockToLandscape();
      setIsFullscreen(true);
      if (VideoPlayerManager?.getSystemLevels) {
        VideoPlayerManager.getSystemLevels()
          .then((res: any) => {
            if (typeof res?.brightness === 'number') {
              setBrightnessLevel(res.brightness);
              brightnessRef.current = res.brightness;
            }
            if (typeof res?.volume === 'number') {
              setVolumeLevel(res.volume);
              volumeRef.current = res.volume;
            }
          })
          .catch(() => {});
      }
    }
  }, [playMode]);

  useEffect(() => {
    if (isExternalAudioSelected && externalAudioRef.current) {
      const target = Math.max(0, currentTime - audioDelay);
      externalAudioRef.current.seek(target);
      externalAudioTimeRef.current = target;
      lastAudioSyncMs.current = Date.now();
    }
  }, [audioDelay, isExternalAudioSelected]);

  useEffect(() => {
    const backAction = () => {
      if (showHdrChoiceModal) {
        setShowHdrChoiceModal(false);
        return true;
      }
      if (showKeyModal) {
        setShowKeyModal(false);
        return true;
      }
      if (playMode === 'internal') {
        if (isLocked) {
          setShowControls(true);
          return true;
        }
        if (activeMenu) {
          setActiveMenu(null);
          return true;
        }
        closeInternalPlayer();
        return true;
      } else if (selectedFile) {
        setSelectedFile(null);
        return true;
      } else if (activeTab === 'downloads') {
        setActiveTab('catalog');
        return true;
      } else if (pathStack.length > 1) {
        goBack();
        return true;
      }
      return false;
    };
    const backHandler = BackHandler.addEventListener(
      'hardwareBackPress',
      backAction,
    );
    return () => backHandler.remove();
  }, [
    playMode,
    selectedFile,
    pathStack,
    activeMenu,
    showKeyModal,
    showHdrChoiceModal,
    isLocked,
    activeTab,
  ]);

  const loadSavedTimesForFiles = useCallback(async (items: any[]) => {
    try {
      const keys = items.map(f => `time_${f.name}`);
      const pairs = await AsyncStorage.multiGet(keys);
      const nextMap: Record<string, number> = {};
      pairs.forEach(([k, val]) => {
        if (val) {
          const parsed = parseFloat(val);
          if (parsed > 5) {
            nextMap[k.replace(/^time_/, '')] = parsed;
          }
        }
      });
      setSavedTimesMap(prev => ({ ...prev, ...nextMap }));
    } catch {}
  }, []);

  const fetchSingleMetadata = useCallback(
    async (
      info: ParsedMediaInfo,
      activeTmdbKey: string,
    ): Promise<MediaMetadata | null> => {
      const cacheKey = `meta_v3_${info.searchTitle.toLowerCase()}_${info.year}_${
        activeTmdbKey ? 'tmdb' : 'imdb'
      }`;
      try {
        const cached = await AsyncStorage.getItem(cacheKey);
        if (cached) {
          return JSON.parse(cached);
        }
      } catch {}

      if (activeTmdbKey && activeTmdbKey.trim().length >= 20) {
        try {
          const key = activeTmdbKey.trim();
          const endpoint = info.seasonEpisode ? 'tv' : 'multi';
          const url = `https://api.themoviedb.org/3/search/${endpoint}?api_key=${encodeURIComponent(
            key,
          )}&query=${encodeURIComponent(info.searchTitle)}${
            info.year && endpoint === 'movie' ? `&year=${info.year}` : ''
          }`;
          const res = await axios.get(url, { timeout: 6000 });
          const results = res.data?.results || [];
          const match =
            results.find((r: any) => {
              const rYear = (r.release_date || r.first_air_date || '').slice(
                0,
                4,
              );
              return info.year ? rYear === info.year : r.poster_path;
            }) ||
            results.find((r: any) => r.poster_path) ||
            results[0];

          if (match) {
            const meta: MediaMetadata = {
              poster: match.poster_path
                ? `https://image.tmdb.org/t/p/w500${match.poster_path}`
                : undefined,
              backdrop: match.backdrop_path
                ? `https://image.tmdb.org/t/p/w780${match.backdrop_path}`
                : undefined,
              rating:
                match.vote_average && match.vote_average > 0
                  ? Number(match.vote_average).toFixed(1)
                  : undefined,
              overview: match.overview || undefined,
              year:
                (match.release_date || match.first_air_date || '').slice(
                  0,
                  4,
                ) || info.year,
            };
            if (meta.poster || meta.overview) {
              await AsyncStorage.setItem(cacheKey, JSON.stringify(meta));
              return meta;
            }
          }
        } catch {}
      }

      try {
        const query = `${info.searchTitle} ${info.year}`.trim();
        const cleanQ = query
          .toLowerCase()
          .replace(/[^a-z0-9\s]/g, '')
          .trim();
        const firstChar = cleanQ.charAt(0) || 'a';
        const imdbUrl = `https://v3.sg.media-imdb.com/suggestion/${firstChar}/${encodeURIComponent(
          cleanQ.slice(0, 35),
        )}.json`;
        const res = await axios.get(imdbUrl, { timeout: 6000 });
        const list: any[] = res.data?.d || [];
        const validItems = list.filter((item: any) => item?.i?.imageUrl);
        const match =
          validItems.find(
            (item: any) => info.year && String(item.y) === String(info.year),
          ) || validItems[0];

        if (match && match.i?.imageUrl) {
          const rawImg: string = match.i.imageUrl;
          const poster = rawImg.replace(
            /\._V1_.*\.jpg$/i,
            '._V1_QL75_UX500_.jpg',
          );
          const backdrop = rawImg.replace(
            /\._V1_.*\.jpg$/i,
            '._V1_QL75_UX1000_.jpg',
          );
          const meta: MediaMetadata = {
            poster,
            backdrop,
            year: match.y ? String(match.y) : info.year,
            cast: match.s || undefined,
            overview: match.s
              ? `${match.q ? `${match.q.toUpperCase()} • ` : ''}Starring: ${
                  match.s
                }`
              : undefined,
          };
          await AsyncStorage.setItem(cacheKey, JSON.stringify(meta));
          return meta;
        }
      } catch {}

      return null;
    },
    [],
  );

  useEffect(() => {
    if (!files.length) return;
    let cancelled = false;

    const enrichAll = async () => {
      loadSavedTimesForFiles(files);
      for (const item of files) {
        if (cancelled) break;
        const itemKey = item.name;
        const cacheCheckKey = `${itemKey}_${tmdbKey}`;
        if (fetchedKeysRef.current.has(cacheCheckKey)) continue;
        fetchedKeysRef.current.add(cacheCheckKey);

        const isFolder = !!item.mimeType?.includes('folder');
        const info = parseMediaFilename(item.name, isFolder);
        const meta = await fetchSingleMetadata(info, tmdbKey);
        if (meta && !cancelled) {
          setMetadataMap(prev => ({ ...prev, [itemKey]: meta }));
        }
      }
    };

    enrichAll();
    return () => {
      cancelled = true;
    };
  }, [files, tmdbKey, fetchSingleMetadata, loadSavedTimesForFiles]);

  const saveTmdbKey = async () => {
    const cleaned = tempKeyInput.trim();
    setTmdbKey(cleaned);
    fetchedKeysRef.current.clear();
    await AsyncStorage.setItem('gdstream_tmdb_key', cleaned);
    setShowKeyModal(false);
  };

  const seekBothPlayers = useCallback(
    (targetTime: number) => {
      videoRef.current?.seek(targetTime);
      setCurrentTime(targetTime);
      if (isExternalAudioSelected && externalAudioRef.current) {
        const audioTarget = Math.max(0, targetTime - audioDelay);
        externalAudioRef.current.seek(audioTarget);
        externalAudioTimeRef.current = audioTarget;
        lastAudioSyncMs.current = Date.now();
      }
    },
    [isExternalAudioSelected, audioDelay],
  );

  const triggerStepSeek = useCallback(
    (isForward: boolean) => {
      clearTimeout(multiTapTimer.current);
      if (seekBaseTime.current === null) {
        seekBaseTime.current = currentTime;
        accumulatedSeek.current = 0;
      }
      accumulatedSeek.current += 10;
      const totalSeek = accumulatedSeek.current;

      setSeekOverlay({
        visible: true,
        icon: isForward ? 'replay-10' : 'replay-10',
        time: totalSeek,
        position: isForward ? 'right' : 'left',
      });

      fadeAnim.setValue(1);
      Animated.timing(fadeAnim, {
        toValue: 0,
        duration: 800,
        useNativeDriver: true,
      }).start();

      multiTapTimer.current = setTimeout(() => {
        const base = seekBaseTime.current ?? currentTime;
        const rawTarget = isForward ? base + totalSeek : base - totalSeek;
        const clampedTarget =
          duration > 0
            ? Math.max(0, Math.min(duration, rawTarget))
            : Math.max(0, rawTarget);
        seekBothPlayers(clampedTarget);
        seekBaseTime.current = null;
        accumulatedSeek.current = 0;
        lastTapDirection.current = null;
      }, 300);
    },
    [currentTime, duration, fadeAnim, seekBothPlayers],
  );

  useEffect(() => {
    if (playMode !== 'internal') return;

    const sub = DeviceEventEmitter.addListener('onTVRemoteKey', event => {
      const keyCode = event?.keyCode;
      if (keyCode === 85 || keyCode === 126 || keyCode === 127) {
        setIsPaused(p => !p);
        return;
      }
      if (keyCode === 89) {
        triggerStepSeek(false);
        return;
      }
      if (keyCode === 90) {
        triggerStepSeek(true);
        return;
      }

      if (activeMenu) return;

      if (!showControls) {
        if (keyCode === 21) {
          triggerStepSeek(false);
        } else if (keyCode === 22) {
          triggerStepSeek(true);
        } else if (
          keyCode === 23 ||
          keyCode === 66 ||
          keyCode === 19 ||
          keyCode === 20
        ) {
          setShowControls(true);
        }
      }
    });

    return () => sub.remove();
  }, [playMode, showControls, activeMenu, triggerStepSeek]);

  const ensureLoggedIn = async (force = false) => {
    if (isLoggedIn.current && !force) return;
    const loginData = `username=${USERNAME}&password=${PASSWORD}`;
    await axios.post(`${BASE_URL}/login`, loginData, {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    });
    isLoggedIn.current = true;
  };

  const fetchDirectory = async (path: string, isPullRefresh = false) => {
    if (isPullRefresh) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    try {
      await ensureLoggedIn(false);
      let response = await axios.post(`${BASE_URL}${path}`, { page_index: 0 });
      if (!response.data?.data?.files) {
        await ensureLoggedIn(true);
        response = await axios.post(`${BASE_URL}${path}`, { page_index: 0 });
      }
      if (response.data?.data?.files) {
        const incomingFiles = response.data.data.files;
        setFiles(incomingFiles);
        if (searchQuery.trim()) {
          const q = searchQuery.toLowerCase();
          setFilteredFiles(
            incomingFiles.filter((file: any) => {
              const info = parseMediaFilename(
                file.name,
                !!file.mimeType?.includes('folder'),
              );
              return (
                file.name.toLowerCase().includes(q) ||
                info.cleanTitle.toLowerCase().includes(q)
              );
            }),
          );
        } else {
          setFilteredFiles(incomingFiles);
        }
        setCurrentPath(path);
        if (!isPullRefresh) {
          setSearchQuery('');
        }
      }
    } catch (error) {
      Alert.alert('Error', 'Folder load aagala!');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  const handleRefresh = useCallback(() => {
    fetchDirectory(currentPath, true);
  }, [currentPath, searchQuery]);

  const getStreamUrl = (file: any) => {
    if (!file) return '';
    if (file.localPath) {
      return file.localPath.startsWith('file://')
        ? file.localPath
        : `file://${file.localPath}`;
    }
    const completedDl = downloads.find(
      d => d.fileName === file.name && d.status === 'completed' && d.filePath,
    );
    if (completedDl) {
      return `file://${completedDl.filePath}`;
    }
    if (file.link) {
      return file.link.startsWith('http')
        ? file.link
        : `${BASE_URL}${file.link.startsWith('/') ? '' : '/'}${file.link}`;
    }
    return `${BASE_URL}${currentPath}${encodeURIComponent(file.name)}`;
  };

  const getRemoteDownloadUrl = (file: any) => {
    if (!file) return '';
    if (file.link) {
      return file.link.startsWith('http')
        ? file.link
        : `${BASE_URL}${file.link.startsWith('/') ? '' : '/'}${file.link}`;
    }
    return `${BASE_URL}${currentPath}${encodeURIComponent(file.name)}`;
  };

  const openFile = (file: any) => {
    switchingEpisode.current = false;
    lastSavedSec.current = -1;
    setSelectedAudio(undefined);
    setSelectedText({ type: 'disabled' });
    setCurrentTime(0);
    setDuration(0);
    setResizeMode('contain');
    setSubtitleDelay(0);
    setAudioDelay(0);
    setPlaybackRate(1.0);
    setIsLocked(false);
    setExternalSubtitle(null);
    setExternalAudio(null);
    setSelectedFile(file);
  };

  const startPlaybackWithMode = async (mode: 'dv' | 'hdr' | 'sdr') => {
    setShowHdrChoiceModal(false);
    setActiveHdrMode(mode);
    try {
      await VideoPlayerManager?.setPreferredHdrMode?.(mode);
    } catch {}
    setPlayerInstanceKey(k => k + 1);
    setPlayMode('internal');
  };

  const handlePlayInAppPress = (fileToPlay = selectedFile) => {
    if (!fileToPlay) return;
    const info = parseMediaFilename(fileToPlay.name, false);
    if (info.hasDV) {
      setShowHdrChoiceModal(true);
    } else if (info.hasHDR) {
      startPlaybackWithMode('hdr');
    } else {
      startPlaybackWithMode('sdr');
    }
  };

  const toggleLiveHdrModeInPlayer = async () => {
    const nextMode = activeHdrMode === 'dv' ? 'hdr' : 'dv';
    const resumeSec = currentTime;
    if (resumeSec > 2 && selectedFile?.name) {
      await AsyncStorage.setItem(
        `time_${selectedFile.name}`,
        resumeSec.toString(),
      );
    }
    setActiveHdrMode(nextMode);
    try {
      await VideoPlayerManager?.setPreferredHdrMode?.(nextMode);
    } catch {}
    setPlayerInstanceKey(k => k + 1);
  };

  const handleStartDownload = async (file: any) => {
    if (!file) return;
    const info = parseMediaFilename(file.name, false);
    const meta = metadataMap[file.name];
    const url = getRemoteDownloadUrl(file);
    try {
      await DownloadEngine?.startDownload(
        url,
        file.name,
        info.cleanTitle,
        meta?.poster || '',
      );
      Alert.alert(
        'Download Started ⚡',
        `${info.cleanTitle} is downloading with 8x parallel turbo connections to Download/GDStream.`,
      );
    } catch (e) {
      Alert.alert('Error', 'Could not start download!');
    }
  };

  const handlePress = (item: any) => {
    if (item.mimeType?.includes('folder')) {
      const newPath = `${currentPath}${encodeURIComponent(item.name)}/`;
      setPathStack([...pathStack, newPath]);
      fetchDirectory(newPath, false);
    } else {
      openFile(item);
    }
  };

  const goBack = () => {
    if (pathStack.length > 1) {
      const newStack = pathStack.slice(0, -1);
      setPathStack(newStack);
      fetchDirectory(newStack[newStack.length - 1], false);
    }
  };

  const handleSearch = useCallback(
    (text: string) => {
      setSearchQuery(text);
      if (!text.trim()) {
        setFilteredFiles(files);
        return;
      }
      const q = text.toLowerCase();
      const filtered = files.filter(file => {
        const info = parseMediaFilename(
          file.name,
          !!file.mimeType?.includes('folder'),
        );
        return (
          file.name.toLowerCase().includes(q) ||
          info.cleanTitle.toLowerCase().includes(q)
        );
      });
      setFilteredFiles(filtered);
    },
    [files],
  );

  const openInExternalPlayer = async (targetFile = selectedFile) => {
    const url = getStreamUrl(targetFile);
    try {
      await VideoPlayerManager.playVideo(url);
    } catch (e) {
      Alert.alert('Error', 'Player illai!');
    }
  };

  const toggleFullscreen = () => {
    if (IS_TV) {
      Orientation.lockToLandscape();
      setIsFullscreen(true);
      return;
    }
    if (isFullscreen) {
      Orientation.lockToPortrait();
      setIsFullscreen(false);
    } else {
      Orientation.lockToLandscape();
      setIsFullscreen(true);
    }
  };

  const closeInternalPlayer = () => {
    if (!IS_TV) {
      Orientation.lockToPortrait();
      setIsFullscreen(false);
    }
    setIsLocked(false);
    setActiveMenu(null);
    setPlayMode(null);
    loadSavedTimesForFiles(files);
  };

  const handleZoomToggle = () => {
    setResizeMode(prev => (prev === 'contain' ? 'cover' : 'contain'));
  };

  const cyclePlaybackSpeed = () => {
    const speeds = [0.75, 1.0, 1.25, 1.5, 2.0];
    const idx = speeds.indexOf(playbackRate);
    const next = speeds[(idx + 1) % speeds.length];
    setPlaybackRate(next);
  };

  const handleSelectTextTrack = (track: any) => {
    setSelectedText(track);
    setActiveMenu(null);
  };

  const handlePickSubtitleFile = async () => {
    try {
      const res = await SubtitleFileModule.pickSubtitleFile();
      if (!res) return;
      const cues = parseSubtitleFile(res.content || '');
      if (!cues.length) {
        Alert.alert('Error', 'Could not read any subtitles from that file!');
        return;
      }
      setExternalSubtitle({ name: res.name, content: res.content });
      setSelectedText({ type: 'external', value: res.name });
      setActiveMenu(null);
    } catch (e) {
      Alert.alert('Error', 'Could not load subtitle file!');
    }
  };

  const handlePickAudioFile = async () => {
    try {
      const res = await SubtitleFileModule.pickAudioFile();
      if (!res || !res.uri) return;
      setExternalAudio({ name: res.name || 'External Audio', uri: res.uri });
      setSelectedAudio({
        type: 'external',
        value: res.name || 'External Audio',
      });
      setActiveMenu(null);
    } catch (e) {
      Alert.alert('Error', 'Could not load audio file!');
    }
  };

  const handleZoneTap = useCallback(
    (isForward: boolean) => {
      if (isLocked) {
        setShowControls(prev => !prev);
        return;
      }
      const now = Date.now();
      const DOUBLE_PRESS_DELAY = 280;

      if (
        now - lastTapTime.current < DOUBLE_PRESS_DELAY &&
        (lastTapDirection.current === null ||
          lastTapDirection.current === isForward)
      ) {
        clearTimeout(singleTapTimer.current);
        triggerStepSeek(isForward);
      } else {
        lastTapDirection.current = isForward;
        singleTapTimer.current = setTimeout(() => {
          setShowControls(prev => !prev);
          setActiveMenu(null);
          lastTapDirection.current = null;
        }, DOUBLE_PRESS_DELAY);
      }
      lastTapTime.current = now;
    },
    [isLocked, triggerStepSeek],
  );

  const gestureStartRef = useRef({
    side: 'left' as 'left' | 'right',
    startVal: 0.5,
    isSwiping: false,
  });

  const handleZoneTapRef = useRef(handleZoneTap);
  useEffect(() => {
    handleZoneTapRef.current = handleZoneTap;
  }, [handleZoneTap]);

  const isLockedRef = useRef(isLocked);
  useEffect(() => {
    isLockedRef.current = isLocked;
  }, [isLocked]);

  const playerPanResponder = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onMoveShouldSetPanResponder: () => true,
        onPanResponderGrant: evt => {
          const screenW = Dimensions.get('window').width || 800;
          const x = evt.nativeEvent.pageX;
          const side = x < screenW / 2 ? 'left' : 'right';
          gestureStartRef.current = {
            side,
            startVal:
              side === 'left' ? brightnessRef.current : volumeRef.current,
            isSwiping: false,
          };
        },
        onPanResponderMove: (_, gestureState) => {
          if (isLockedRef.current) return;
          const { dy, dx } = gestureState;
          if (
            !gestureStartRef.current.isSwiping &&
            Math.abs(dy) > 12 &&
            Math.abs(dy) > Math.abs(dx)
          ) {
            gestureStartRef.current.isSwiping = true;
            clearTimeout(singleTapTimer.current);
          }

          if (gestureStartRef.current.isSwiping) {
            const screenH = Dimensions.get('window').height || 400;
            const delta = -dy / (screenH * 0.65);
            const nextVal = Math.max(
              0,
              Math.min(1, gestureStartRef.current.startVal + delta),
            );

            clearTimeout(gestureHideTimer.current);

            if (gestureStartRef.current.side === 'left') {
              brightnessRef.current = nextVal;
              setBrightnessLevel(nextVal);
              VideoPlayerManager?.setSystemBrightness?.(nextVal);
              setGestureIndicator({
                visible: true,
                type: 'brightness',
                value: nextVal,
              });
            } else {
              volumeRef.current = nextVal;
              setVolumeLevel(nextVal);
              VideoPlayerManager?.setSystemVolume?.(nextVal);
              setGestureIndicator({
                visible: true,
                type: 'volume',
                value: nextVal,
              });
            }
          }
        },
        onPanResponderRelease: evt => {
          if (gestureStartRef.current.isSwiping) {
            gestureHideTimer.current = setTimeout(() => {
              setGestureIndicator(prev => ({ ...prev, visible: false }));
            }, 900);
          } else {
            const screenW = Dimensions.get('window').width || 800;
            const isForward = evt.nativeEvent.pageX >= screenW / 2;
            handleZoneTapRef.current(isForward);
          }
          gestureStartRef.current.isSwiping = false;
        },
      }),
    [],
  );

  const handleVideoLoad = async (meta: any) => {
    setDuration(meta.duration);
    if (meta.audioTracks) {
      setAudioTracks(meta.audioTracks);
      if (!selectedAudio && meta.audioTracks.length > 0) {
        const selectedIdx = meta.audioTracks.findIndex((t: any) => t.selected);
        setSelectedAudio({
          type: 'index',
          value: String(selectedIdx >= 0 ? selectedIdx : 0),
        });
      }
    }
    if (meta.textTracks) {
      setTextTracks(meta.textTracks);
    }
    try {
      const savedTimeStr = await AsyncStorage.getItem(
        `time_${selectedFile.name}`,
      );
      if (savedTimeStr) {
        const savedTime = parseFloat(savedTimeStr);
        if (savedTime > 0 && meta.duration - savedTime > 180) {
          seekBothPlayers(savedTime);
        }
      }
    } catch (e) {}
  };

  const nextEpisodeFile = useMemo(() => {
    if (!selectedFile) return null;
    const currentIndex = filteredFiles.findIndex(
      f => f.name === selectedFile.name,
    );
    if (currentIndex !== -1 && currentIndex + 1 < filteredFiles.length) {
      const candidate = filteredFiles[currentIndex + 1];
      if (!candidate.mimeType?.includes('folder')) {
        return candidate;
      }
    }
    return null;
  }, [selectedFile, filteredFiles]);

  const playNextEpisode = () => {
    if (!nextEpisodeFile) return;
    openFile(nextEpisodeFile);
    setPlayMode('internal');
  };

  const handleVideoEnd = async () => {
    if (switchingEpisode.current) return;
    switchingEpisode.current = true;
    try {
      await AsyncStorage.removeItem(`time_${selectedFile?.name}`);
    } catch (e) {}
    const episodeRegex = /[Ss]\d+[Ee]\d+/;
    if (episodeRegex.test(selectedFile?.name || '') && nextEpisodeFile) {
      openFile(nextEpisodeFile);
      setPlayMode('internal');
      return;
    }
    closeInternalPlayer();
    setSelectedFile(null);
  };

  const renderHdrChoiceModal = () => {
    if (!showHdrChoiceModal || !selectedFile) return null;
    const info = parseMediaFilename(selectedFile.name, false);
    return (
      <Modal
        visible={showHdrChoiceModal}
        transparent={true}
        animationType="fade"
        onRequestClose={() => setShowHdrChoiceModal(false)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.hdrModalCard}>
            <Text style={styles.hdrModalEyebrow}>SELECT DISPLAY FORMAT</Text>
            <Text style={styles.hdrModalTitle}>{info.cleanTitle}</Text>
            <Text style={styles.hdrModalSub}>
              This title contains both Dolby Vision and HDR10 streams. Choose
              which hardware engine you want to play with:
            </Text>

            <FocusableButton
              hasTVPreferredFocus={true}
              style={styles.dvOptionBtn}
              onPress={() => startPlaybackWithMode('dv')}>
              <View style={styles.dvOptionBadge}>
                <Text style={styles.dvOptionBadgeText}>DOLBY VISION</Text>
              </View>
              <View style={{ flex: 1 }}>
                <Text style={styles.hdrOptionTitle}>
                  Play in Dolby Vision (DV)
                </Text>
                <Text style={styles.hdrOptionDesc}>
                  Dynamic scene-by-scene metadata via hardware DV decoder
                </Text>
              </View>
              <Icon name="play-circle-fill" size={28} color="#E50914" />
            </FocusableButton>

            <FocusableButton
              style={styles.hdr10OptionBtn}
              onPress={() => startPlaybackWithMode('hdr')}>
              <View style={styles.hdr10OptionBadge}>
                <Text style={styles.hdr10OptionBadgeText}>HDR10</Text>
              </View>
              <View style={{ flex: 1 }}>
                <Text style={styles.hdrOptionTitle}>Play in HDR10</Text>
                <Text style={styles.hdrOptionDesc}>
                  10-bit HEVC High Dynamic Range base layer
                </Text>
              </View>
              <Icon name="play-circle-outline" size={28} color="#F5C518" />
            </FocusableButton>

            <TouchableOpacity
              style={styles.hdrCancelBtn}
              onPress={() => setShowHdrChoiceModal(false)}>
              <Text style={{ color: '#99A3B3', fontWeight: '700' }}>
                Cancel
              </Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    );
  };

  const renderFloatingMenu = () => {
    if (!activeMenu) return null;
    const isAudio = activeMenu === 'audio';
    const tracks = isAudio ? audioTracks : textTracks;
    const isSubtitleDisabled =
      !selectedText || selectedText.type === 'disabled';

    return (
      <View style={styles.netflixSheetOverlay}>
        <View style={styles.floatingMenu}>
          <View style={styles.floatingMenuHeader}>
            <Text style={styles.floatingMenuTitle}>
              {isAudio ? 'Audio & Playback Speed' : 'Subtitles & Captions'}
            </Text>
            <FocusableButton
              style={styles.menuCloseBtn}
              onPress={() => setActiveMenu(null)}>
              <Icon name="close" size={20} color="white" />
            </FocusableButton>
          </View>
          <ScrollView style={{ maxHeight: 250 }}>
            {!isAudio && (
              <FocusableButton
                hasTVPreferredFocus={true}
                style={styles.menuItem}
                onPress={() => handleSelectTextTrack({ type: 'disabled' })}>
                <Icon
                  name="check"
                  size={20}
                  color="#E50914"
                  style={{ opacity: isSubtitleDisabled ? 1 : 0 }}
                />
                <Text style={styles.menuItemText}>Off (Disable Subtitles)</Text>
              </FocusableButton>
            )}
            {!isAudio && (
              <FocusableButton
                style={styles.menuItem}
                onPress={handlePickSubtitleFile}>
                <Icon
                  name="folder-open"
                  size={20}
                  color={isExternalSubtitleSelected ? '#E50914' : 'white'}
                />
                <Text style={styles.menuItemText} numberOfLines={1}>
                  {externalSubtitle
                    ? `File: ${externalSubtitle.name}`
                    : 'Load External Subtitle (.srt / .vtt) 📂'}
                </Text>
              </FocusableButton>
            )}
            {!isAudio && !!externalSubtitle && !isExternalSubtitleSelected && (
              <FocusableButton
                style={styles.menuItem}
                onPress={() =>
                  handleSelectTextTrack({
                    type: 'external',
                    value: externalSubtitle.name,
                  })
                }>
                <Icon
                  name="check"
                  size={20}
                  color="#E50914"
                  style={{ opacity: 0 }}
                />
                <Text style={styles.menuItemText} numberOfLines={1}>
                  Use: {externalSubtitle.name}
                </Text>
              </FocusableButton>
            )}
            {isAudio && (
              <FocusableButton
                hasTVPreferredFocus={true}
                style={styles.menuItem}
                onPress={handlePickAudioFile}>
                <Icon
                  name="folder-open"
                  size={20}
                  color={isExternalAudioSelected ? '#E50914' : 'white'}
                />
                <Text style={styles.menuItemText} numberOfLines={1}>
                  {externalAudio
                    ? `File: ${externalAudio.name}`
                    : 'Load External Audio File 📂'}
                </Text>
              </FocusableButton>
            )}
            {isAudio && !!externalAudio && (
              <FocusableButton
                style={styles.menuItem}
                onPress={() => {
                  setSelectedAudio({
                    type: 'external',
                    value: externalAudio.name,
                  });
                  setActiveMenu(null);
                }}>
                <Icon
                  name="check"
                  size={20}
                  color="#E50914"
                  style={{ opacity: isExternalAudioSelected ? 1 : 0 }}
                />
                <Text style={styles.menuItemText} numberOfLines={1}>
                  External: {externalAudio.name}
                </Text>
              </FocusableButton>
            )}
            {!isAudio && (
              <View style={styles.adjustRow}>
                <Text style={styles.adjustLabel}>Font Size</Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() => setSubtitleSize(s => Math.max(10, s - 2))}>
                  <Icon name="remove" size={20} color="white" />
                </FocusableButton>
                <Text style={styles.adjustValue}>{subtitleSize}sp</Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() => setSubtitleSize(s => Math.min(40, s + 2))}>
                  <Icon name="add" size={20} color="white" />
                </FocusableButton>
              </View>
            )}
            {!isAudio && (
              <View style={styles.adjustRow}>
                <Text style={styles.adjustLabel}>Sync Delay</Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() =>
                    setSubtitleDelay(d => Math.round((d - 0.5) * 10) / 10)
                  }>
                  <Icon name="remove" size={20} color="white" />
                </FocusableButton>
                <Text style={styles.adjustValue}>
                  {subtitleDelay >= 0
                    ? `+${subtitleDelay.toFixed(1)}s`
                    : `${subtitleDelay.toFixed(1)}s`}
                </Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() =>
                    setSubtitleDelay(d => Math.round((d + 0.5) * 10) / 10)
                  }>
                  <Icon name="add" size={20} color="white" />
                </FocusableButton>
              </View>
            )}
            {isAudio && (
              <View style={styles.adjustRow}>
                <Text style={styles.adjustLabel}>Audio Sync</Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() =>
                    setAudioDelay(d => Math.round((d - 0.25) * 100) / 100)
                  }>
                  <Icon name="remove" size={20} color="white" />
                </FocusableButton>
                <Text style={styles.adjustValue}>
                  {audioDelay >= 0
                    ? `+${audioDelay.toFixed(2)}s`
                    : `${audioDelay.toFixed(2)}s`}
                </Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() =>
                    setAudioDelay(d => Math.round((d + 0.25) * 100) / 100)
                  }>
                  <Icon name="add" size={20} color="white" />
                </FocusableButton>
              </View>
            )}
            {isAudio && (
              <View style={styles.adjustRow}>
                <Text style={styles.adjustLabel}>Speed</Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() =>
                    setPlaybackRate(r =>
                      Math.max(0.5, Math.round((r - 0.25) * 100) / 100),
                    )
                  }>
                  <Icon name="remove" size={20} color="white" />
                </FocusableButton>
                <Text style={styles.adjustValue}>
                  {playbackRate.toFixed(2)}x
                </Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() =>
                    setPlaybackRate(r =>
                      Math.min(2.0, Math.round((r + 0.25) * 100) / 100),
                    )
                  }>
                  <Icon name="add" size={20} color="white" />
                </FocusableButton>
              </View>
            )}
            {tracks.map((track, index) => {
              const indexStr = String(index);
              const isSelected = isAudio
                ? selectedAudio?.type === 'index' &&
                  String(selectedAudio?.value) === indexStr
                : selectedText?.type === 'index' &&
                  String(selectedText?.value) === indexStr;
              return (
                <FocusableButton
                  key={index}
                  style={styles.menuItem}
                  onPress={() => {
                    if (isAudio) {
                      setSelectedAudio({ type: 'index', value: indexStr });
                      setActiveMenu(null);
                    } else {
                      handleSelectTextTrack({ type: 'index', value: indexStr });
                    }
                  }}>
                  <Icon
                    name="check"
                    size={20}
                    color="#E50914"
                    style={{ opacity: isSelected ? 1 : 0 }}
                  />
                  <Text style={styles.menuItemText} numberOfLines={1}>
                    {track.language
                      ? track.language.toUpperCase()
                      : `Track ${index + 1}`}{' '}
                    • {track.title || 'Default'}
                  </Text>
                </FocusableButton>
              );
            })}
          </ScrollView>
        </View>
      </View>
    );
  };

  // 🔥 1. INTERNAL VIDEO PLAYER SCREEN 🔥
  if (selectedFile && playMode === 'internal') {
    const progressPercent = duration > 0 ? (currentTime / duration) * 100 : 0;
    const remainingTime = Math.max(0, duration - currentTime);
    const playerInfo = parseMediaFilename(selectedFile.name, false);

    return (
      <View style={styles.playerWrapper}>
        <StatusBar hidden={true} />
        <Video
          key={`${selectedFile.link || selectedFile.name}-${playerInstanceKey}-${activeHdrMode}`}
          ref={videoRef}
          source={{
            uri: getStreamUrl(selectedFile),
          }}
          style={styles.videoPlayer}
          resizeMode={resizeMode}
          paused={isPaused}
          volume={volumeLevel}
          muted={isExternalAudioSelected}
          disableFocus={isExternalAudioSelected}
          rate={playbackRate}
          focusable={false}
          onLoad={handleVideoLoad}
          onBuffer={(b: any) => setIsVideoBuffering(!!b?.isBuffering)}
          onAudioTracks={(data: any) => {
            const list = Array.isArray(data) ? data : data?.audioTracks;
            if (list && list.length) setAudioTracks(list);
          }}
          onTextTracks={(data: any) => {
            const list = Array.isArray(data) ? data : data?.textTracks;
            if (list && list.length) setTextTracks(list);
          }}
          onProgress={(p: any) => {
            setCurrentTime(p.currentTime);
            const sec = Math.floor(p.currentTime);
            if (sec > 0 && sec % 5 === 0 && lastSavedSec.current !== sec) {
              lastSavedSec.current = sec;
              AsyncStorage.setItem(
                `time_${selectedFile?.name}`,
                p.currentTime.toString(),
              );
            }
            if (
              isExternalAudioSelected &&
              !isPaused &&
              !isVideoBuffering &&
              externalAudioRef.current
            ) {
              const expectedAudioTime = Math.max(0, p.currentTime - audioDelay);
              const drift = Math.abs(
                expectedAudioTime - externalAudioTimeRef.current,
              );
              const now = Date.now();
              if (drift > 0.45 && now - lastAudioSyncMs.current > 2000) {
                externalAudioRef.current.seek(expectedAudioTime);
                externalAudioTimeRef.current = expectedAudioTime;
                lastAudioSyncMs.current = now;
              }
            }
          }}
          onEnd={handleVideoEnd}
          selectedAudioTrack={
            isExternalAudioSelected ? { type: 'disabled' } : selectedAudio
          }
          subtitleStyle={{
            paddingBottom: resizeMode === 'cover' ? 70 : 20,
            fontSize: subtitleSize,
            subtitlesFollowVideo: resizeMode !== 'cover',
          }}
          selectedTextTrack={
            subtitlesActive ? selectedText : { type: 'disabled' }
          }
          viewType={0}
          controls={false}
        />

        {isExternalAudioSelected && !!externalAudio && (
          <Video
            key={externalAudio.uri}
            ref={externalAudioRef}
            source={{ uri: externalAudio.uri }}
            paused={isPaused || isVideoBuffering}
            rate={playbackRate}
            volume={volumeLevel}
            focusable={false}
            disableFocus={true}
            onLoad={() => {
              const target = Math.max(0, currentTime - audioDelay);
              externalAudioRef.current?.seek(target);
              externalAudioTimeRef.current = target;
              lastAudioSyncMs.current = Date.now();
            }}
            onProgress={(ap: any) => {
              externalAudioTimeRef.current = ap.currentTime;
            }}
            style={{ width: 0, height: 0 }}
          />
        )}

        {isExternalSubtitleSelected && !!activeExternalSubtitleText && (
          <View style={styles.externalSubtitleContainer} pointerEvents="none">
            <Text
              style={[
                styles.externalSubtitleText,
                { fontSize: subtitleSize },
              ]}>
              {activeExternalSubtitleText}
            </Text>
          </View>
        )}

        {!IS_TV && (
          <View
            style={StyleSheet.absoluteFill}
            {...playerPanResponder.panHandlers}
          />
        )}

        {gestureIndicator.visible && (
          <View
            style={[
              styles.gestureHudContainer,
              gestureIndicator.type === 'brightness'
                ? styles.gestureHudLeft
                : styles.gestureHudRight,
            ]}
            pointerEvents="none">
            <View style={styles.gestureHudPill}>
              <Icon
                name={
                  gestureIndicator.type === 'brightness'
                    ? 'wb-sunny'
                    : gestureIndicator.value === 0
                    ? 'volume-off'
                    : 'volume-up'
                }
                size={22}
                color="#E50914"
              />
              <View style={styles.gestureBarTrack}>
                <View
                  style={[
                    styles.gestureBarFill,
                    { height: `${Math.round(gestureIndicator.value * 100)}%` },
                  ]}
                />
              </View>
              <Text style={styles.gestureHudText}>
                {Math.round(gestureIndicator.value * 100)}%
              </Text>
            </View>
          </View>
        )}

        <Animated.View
          style={[
            styles.seekOverlay,
            {
              opacity: fadeAnim,
              alignItems:
                seekOverlay.position === 'right' ? 'flex-end' : 'flex-start',
            },
          ]}
          pointerEvents="none">
          <View style={styles.netflixSeekBubble}>
            <Icon
              name={
                seekOverlay.position === 'right' ? 'forward-10' : 'replay-10'
              }
              size={42}
              color="white"
            />
            <Text style={styles.seekText}>
              {seekOverlay.position === 'right'
                ? `+${seekOverlay.time}s`
                : `-${seekOverlay.time}s`}
            </Text>
          </View>
        </Animated.View>

        {showControls && isLocked && (
          <View style={styles.lockedOverlay} pointerEvents="box-none">
            <FocusableButton
              style={styles.unlockPillBtn}
              onPress={() => setIsLocked(false)}>
              <Icon name="lock" size={20} color="#E50914" />
              <Text style={styles.unlockPillText}>Tap to Unlock Screen</Text>
            </FocusableButton>
          </View>
        )}

        {showControls && !isLocked && (
          <View style={styles.controlsOverlay} pointerEvents="box-none">
            <View style={styles.netflixTopBar}>
              <FocusableButton
                style={styles.netflixCircleBtn}
                onPress={closeInternalPlayer}>
                <Icon name="arrow-back" size={24} color="white" />
              </FocusableButton>

              <View style={styles.netflixTitleCenter}>
                <Text style={styles.netflixMainTitle} numberOfLines={1}>
                  {playerInfo.cleanTitle}
                </Text>
                <View style={styles.netflixSubMetaRow}>
                  {!!playerInfo.seasonEpisode && (
                    <Text style={styles.netflixEpisodeTag}>
                      {playerInfo.seasonEpisode}
                    </Text>
                  )}
                  {!!playerInfo.resolution && (
                    <View style={styles.netflixMiniBadge}>
                      <Text style={styles.netflixMiniBadgeText}>
                        {playerInfo.resolution}
                      </Text>
                    </View>
                  )}
                  {/* LIVE DOLBY VISION / HDR10 STATUS BADGE IN PLAYER */}
                  {(playerInfo.hasDV || playerInfo.hasHDR) && (
                    <TouchableOpacity
                      onPress={
                        playerInfo.hasDV ? toggleLiveHdrModeInPlayer : undefined
                      }
                      style={[
                        styles.liveHdrStatusPill,
                        activeHdrMode === 'dv'
                          ? styles.liveDvPill
                          : styles.liveHdr10Pill,
                      ]}>
                      <Icon
                        name={
                          activeHdrMode === 'dv' ? 'auto-awesome' : 'wb-sunny'
                        }
                        size={11}
                        color={activeHdrMode === 'dv' ? '#FFD700' : '#000'}
                      />
                      <Text
                        style={[
                          styles.liveHdrStatusText,
                          activeHdrMode === 'hdr' && { color: '#000' },
                        ]}>
                        {activeHdrMode === 'dv' ? 'DOLBY VISION' : 'HDR10'}
                      </Text>
                    </TouchableOpacity>
                  )}
                  {playerInfo.badges
                    .filter(b => b !== 'DV' && b !== 'HDR')
                    .slice(0, 2)
                    .map((b, i) => (
                      <Text key={i} style={styles.netflixMetaDotText}>
                        • {b}
                      </Text>
                    ))}
                </View>
              </View>

              <View style={styles.netflixTopRightRow}>
                {!IS_TV && (
                  <FocusableButton
                    style={styles.netflixCircleBtn}
                    onPress={() => {
                      setIsLocked(true);
                      setActiveMenu(null);
                    }}>
                    <Icon name="lock-open" size={20} color="white" />
                  </FocusableButton>
                )}
                {IS_TV && (
                  <FocusableButton
                    style={styles.netflixCircleBtn}
                    onPress={() => {
                      setShowControls(false);
                      setActiveMenu(null);
                    }}>
                    <Icon name="visibility-off" size={20} color="white" />
                  </FocusableButton>
                )}
              </View>
            </View>

            <View style={styles.netflixCenterRow} pointerEvents="box-none">
              <FocusableButton
                style={styles.netflixSeekCircleBtn}
                onPress={() => triggerStepSeek(false)}>
                <Icon name="replay-10" size={40} color="white" />
              </FocusableButton>

              <FocusableButton
                hasTVPreferredFocus={true}
                onPress={() => setIsPaused(!isPaused)}
                style={styles.netflixMainPlayBtn}>
                {isVideoBuffering ? (
                  <ActivityIndicator size="large" color="#FFFFFF" />
                ) : (
                  <Icon
                    name={isPaused ? 'play-arrow' : 'pause'}
                    size={54}
                    color="white"
                  />
                )}
              </FocusableButton>

              <FocusableButton
                style={styles.netflixSeekCircleBtn}
                onPress={() => triggerStepSeek(true)}>
                <Icon name="forward-10" size={40} color="white" />
              </FocusableButton>
            </View>

            <View style={styles.netflixBottomContainer}>
              <View style={styles.netflixScrubberRow}>
                <Text style={styles.netflixTimeLabel}>
                  {formatTime(currentTime)}
                </Text>

                <TouchableOpacity
                  focusable={false}
                  activeOpacity={1}
                  style={styles.netflixProgressHitBox}
                  onLayout={e => setProgressWidth(e.nativeEvent.layout.width)}
                  onPress={e => {
                    if (progressWidth > 0 && duration > 0) {
                      const seekTime =
                        (e.nativeEvent.locationX / progressWidth) * duration;
                      seekBothPlayers(seekTime);
                    }
                  }}>
                  <View style={styles.netflixProgressTrack} />
                  <View
                    style={[
                      styles.netflixProgressFill,
                      { width: `${progressPercent}%` },
                    ]}
                    pointerEvents="none"
                  />
                  <View
                    style={[
                      styles.netflixProgressThumb,
                      { left: `${progressPercent}%` },
                    ]}
                    pointerEvents="none"
                  />
                </TouchableOpacity>

                <Text style={styles.netflixTimeLabel}>
                  -{formatTime(remainingTime)}
                </Text>
              </View>

              <View style={styles.netflixActionRow}>
                <FocusableButton
                  style={[
                    styles.netflixActionPill,
                    activeMenu === 'audio' && styles.netflixActionPillActive,
                  ]}
                  onPress={() =>
                    setActiveMenu(activeMenu === 'audio' ? null : 'audio')
                  }>
                  <Icon
                    name="audiotrack"
                    size={20}
                    color={activeMenu === 'audio' ? '#E50914' : 'white'}
                  />
                  <Text style={styles.netflixActionText}>Audio</Text>
                </FocusableButton>

                <FocusableButton
                  style={[
                    styles.netflixActionPill,
                    activeMenu === 'subtitle' && styles.netflixActionPillActive,
                  ]}
                  onPress={() =>
                    setActiveMenu(
                      activeMenu === 'subtitle' ? null : 'subtitle',
                    )
                  }>
                  <Icon
                    name="subtitles"
                    size={20}
                    color={activeMenu === 'subtitle' ? '#E50914' : 'white'}
                  />
                  <Text style={styles.netflixActionText}>Subtitles</Text>
                </FocusableButton>

                <FocusableButton
                  style={styles.netflixActionPill}
                  onPress={cyclePlaybackSpeed}>
                  <Icon name="speed" size={20} color="white" />
                  <Text style={styles.netflixActionText}>
                    Speed ({playbackRate}x)
                  </Text>
                </FocusableButton>

                <FocusableButton
                  style={styles.netflixActionPill}
                  onPress={handleZoomToggle}>
                  <Icon
                    name={
                      resizeMode === 'contain' ? 'aspect-ratio' : 'crop-free'
                    }
                    size={20}
                    color={resizeMode === 'cover' ? '#E50914' : 'white'}
                  />
                  <Text style={styles.netflixActionText}>
                    {resizeMode === 'contain' ? 'Fit' : 'Zoom'}
                  </Text>
                </FocusableButton>

                {playerInfo.hasDV && (
                  <FocusableButton
                    style={styles.netflixActionPill}
                    onPress={toggleLiveHdrModeInPlayer}>
                    <Icon name="hdr-on" size={20} color="#F5C518" />
                    <Text style={styles.netflixActionText}>
                      Mode: {activeHdrMode.toUpperCase()}
                    </Text>
                  </FocusableButton>
                )}

                {!!nextEpisodeFile && (
                  <FocusableButton
                    style={styles.netflixNextEpPill}
                    onPress={playNextEpisode}>
                    <Icon name="skip-next" size={22} color="black" />
                    <Text style={styles.netflixNextEpText}>Next Ep</Text>
                  </FocusableButton>
                )}
              </View>
            </View>
          </View>
        )}
        {renderFloatingMenu()}
      </View>
    );
  }

  // 🔥 2. MOVIE DETAIL SCREEN 🔥
  if (selectedFile && playMode === null) {
    const info = parseMediaFilename(selectedFile.name, false);
    const meta = metadataMap[selectedFile.name];
    const savedSec = savedTimesMap[selectedFile.name] || 0;
    const bannerImage = meta?.backdrop || meta?.poster;
    const dlTask = downloads.find(d => d.fileName === selectedFile.name);

    return (
      <ScrollView
        style={styles.detailScreen}
        contentContainerStyle={{ paddingBottom: 40 }}>
        <StatusBar hidden={false} barStyle="light-content" />
        <View style={styles.detailHero}>
          {bannerImage ? (
            <Image
              source={{ uri: bannerImage }}
              style={styles.detailBackdrop}
              resizeMode="cover"
            />
          ) : (
            <View style={styles.detailBackdropPlaceholder}>
              <Text style={styles.placeholderMonogram}>
                {info.cleanTitle.charAt(0).toUpperCase()}
              </Text>
            </View>
          )}
          <View style={styles.detailHeroShade} />

          <FocusableButton
            style={styles.detailBackTopBtn}
            onPress={() => setSelectedFile(null)}>
            <Icon name="arrow-back" size={24} color="white" />
          </FocusableButton>
        </View>

        <View style={styles.detailContent}>
          <View style={styles.detailHeaderRow}>
            {meta?.poster ? (
              <Image
                source={{ uri: meta.poster }}
                style={styles.detailPosterThumb}
                resizeMode="cover"
              />
            ) : null}

            <View style={{ flex: 1 }}>
              <Text style={styles.detailTitle}>{info.cleanTitle}</Text>
              <View style={styles.detailMetaRow}>
                {!!(meta?.year || info.year) && (
                  <Text style={styles.detailYearText}>
                    {meta?.year || info.year}
                  </Text>
                )}
                {!!info.seasonEpisode && (
                  <View style={styles.seasonBadge}>
                    <Text style={styles.seasonBadgeText}>
                      {info.seasonEpisode}
                    </Text>
                  </View>
                )}
                {!!meta?.rating && (
                  <View style={styles.ratingBadge}>
                    <Text style={styles.ratingBadgeText}>★ {meta.rating}</Text>
                  </View>
                )}
                {!!info.resolution && (
                  <View style={styles.qualityBadge}>
                    <Text style={styles.qualityBadgeText}>
                      {info.resolution}
                    </Text>
                  </View>
                )}
              </View>

              {info.badges.length > 0 && (
                <View style={styles.badgeWrapRow}>
                  {info.badges.map((b, idx) => (
                    <View key={idx} style={styles.techChip}>
                      <Text style={styles.techChipText}>{b}</Text>
                    </View>
                  ))}
                </View>
              )}
            </View>
          </View>

          {!!meta?.overview && (
            <Text style={styles.detailOverview}>{meta.overview}</Text>
          )}

          <View style={styles.detailActionButtons}>
            <FocusableButton
              hasTVPreferredFocus={true}
              style={styles.primaryPlayButton}
              onPress={() => handlePlayInAppPress(selectedFile)}>
              <Icon name="play-arrow" size={26} color="white" />
              <Text style={styles.primaryPlayButtonText}>
                {savedSec > 10
                  ? `Resume in App (${formatTime(savedSec)})`
                  : 'Play in App'}
              </Text>
            </FocusableButton>

            <FocusableButton
              style={styles.secondaryPlayButton}
              onPress={() => openInExternalPlayer(selectedFile)}>
              <Icon name="open-in-new" size={20} color="white" />
              <Text style={styles.secondaryPlayButtonText}>
                Open in External Player
              </Text>
            </FocusableButton>

            {!dlTask ? (
              <FocusableButton
                style={styles.downloadActionBtn}
                onPress={() => handleStartDownload(selectedFile)}>
                <Icon name="file-download" size={22} color="#46d369" />
                <Text style={styles.downloadActionText}>
                  Download Movie (8x Turbo)
                </Text>
              </FocusableButton>
            ) : dlTask.status === 'completed' ? (
              <View style={styles.downloadedReadyCard}>
                <Icon name="check-circle" size={20} color="#46d369" />
                <Text style={styles.downloadedReadyText}>
                  Downloaded to Phone Storage (Download/GDStream)
                </Text>
              </View>
            ) : (
              <FocusableButton
                style={styles.downloadProgressCard}
                onPress={() => {
                  setSelectedFile(null);
                  setActiveTab('downloads');
                }}>
                <View style={styles.dlCardHeaderRow}>
                  <Text style={styles.dlCardStatusText}>
                    {dlTask.status === 'downloading'
                      ? `Downloading (${dlTask.threadsCount}x Turbo) • ${formatBytes(
                          dlTask.speedBytesPerSec,
                        )}/s`
                      : `Download ${dlTask.status.toUpperCase()}`}
                  </Text>
                  <Text style={styles.dlCardPercentText}>
                    {dlTask.progress.toFixed(1)}%
                  </Text>
                </View>
                <View style={styles.dlProgressBarBg}>
                  <View
                    style={[
                      styles.dlProgressBarFill,
                      { width: `${dlTask.progress}%` },
                    ]}
                  />
                </View>
                <Text style={styles.dlCardSubText}>
                  {formatBytes(dlTask.downloadedBytes)} /{' '}
                  {formatBytes(dlTask.totalBytes)} • Tap to open Downloads
                </Text>
              </FocusableButton>
            )}
          </View>

          <View style={styles.rawFileBox}>
            <Text style={styles.rawFileLabel}>SOURCE FILE</Text>
            <Text style={styles.rawFileName}>{selectedFile.name}</Text>
          </View>
        </View>
        {renderHdrChoiceModal()}
      </ScrollView>
    );
  }

  // 🔥 3. DOWNLOADS TAB SCREEN 🔥
  if (activeTab === 'downloads') {
    return (
      <View style={styles.container}>
        <StatusBar hidden={false} barStyle="light-content" />
        <View style={styles.topHeaderBar}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
            <FocusableButton
              style={styles.headerIconBtn}
              onPress={() => setActiveTab('catalog')}>
              <Icon name="arrow-back" size={22} color="white" />
            </FocusableButton>
            <Text style={styles.headerTitle}>Downloads</Text>
          </View>
          <View style={styles.storageHintPill}>
            <Icon name="folder" size={14} color="#F5C518" />
            <Text style={styles.storageHintText}>Download/GDStream</Text>
          </View>
        </View>

        {downloads.length === 0 ? (
          <View style={styles.emptyDownloadsBox}>
            <Icon name="cloud-download" size={56} color="#3A4454" />
            <Text style={styles.emptyDownloadsTitle}>No Downloads Yet</Text>
            <Text style={styles.emptyDownloadsSub}>
              Movies you download are saved with 8x parallel turbo speed directly
              to your phone's Download/GDStream folder so you can watch offline
              in GDStream or any media player.
            </Text>
          </View>
        ) : (
          <FlatList
            data={downloads}
            contentContainerStyle={{ padding: 14, paddingBottom: 40 }}
            keyExtractor={item => item.id}
            renderItem={({ item }) => {
              const isRunning = item.status === 'downloading';
              const isDone = item.status === 'completed';
              return (
                <View style={styles.dlItemCard}>
                  <View style={{ flexDirection: 'row', gap: 12 }}>
                    {item.poster ? (
                      <Image
                        source={{ uri: item.poster }}
                        style={styles.dlPosterThumb}
                        resizeMode="cover"
                      />
                    ) : (
                      <View style={styles.dlPosterFallback}>
                        <Icon name="movie" size={28} color="#E50914" />
                      </View>
                    )}

                    <View style={{ flex: 1 }}>
                      <Text style={styles.dlItemTitle} numberOfLines={1}>
                        {item.title}
                      </Text>
                      <Text style={styles.dlItemFile} numberOfLines={1}>
                        {item.fileName}
                      </Text>

                      <View style={styles.dlProgressBarBg}>
                        <View
                          style={[
                            styles.dlProgressBarFill,
                            {
                              width: `${item.progress}%`,
                              backgroundColor: isDone ? '#46d369' : '#E50914',
                            },
                          ]}
                        />
                      </View>

                      <View style={styles.dlStatsRow}>
                        <Text style={styles.dlStatsText}>
                          {formatBytes(item.downloadedBytes)} /{' '}
                          {formatBytes(item.totalBytes)} (
                          {item.progress.toFixed(1)}%)
                        </Text>
                        <Text style={styles.dlSpeedText}>
                          {isRunning
                            ? `⚡ ${formatBytes(item.speedBytesPerSec)}/s`
                            : item.status.toUpperCase()}
                        </Text>
                      </View>
                    </View>
                  </View>

                  <View style={styles.dlActionsRow}>
                    {isDone ? (
                      <>
                        <FocusableButton
                          style={styles.dlPrimaryBtn}
                          onPress={() => {
                            const localFileObj = {
                              name: item.fileName,
                              localPath: item.filePath,
                            };
                            openFile(localFileObj);
                            handlePlayInAppPress(localFileObj);
                          }}>
                          <Icon name="play-arrow" size={20} color="white" />
                          <Text style={styles.dlBtnText}>Play in App</Text>
                        </FocusableButton>

                        <FocusableButton
                          style={styles.dlSecondaryBtn}
                          onPress={() =>
                            openInExternalPlayer({
                              name: item.fileName,
                              localPath: item.filePath,
                            })
                          }>
                          <Icon name="open-in-new" size={18} color="white" />
                          <Text style={styles.dlBtnText}>External Player</Text>
                        </FocusableButton>
                      </>
                    ) : isRunning ? (
                      <FocusableButton
                        style={styles.dlSecondaryBtn}
                        onPress={() => DownloadEngine?.pauseDownload(item.id)}>
                        <Icon name="pause" size={18} color="white" />
                        <Text style={styles.dlBtnText}>Pause</Text>
                      </FocusableButton>
                    ) : (
                      <FocusableButton
                        style={styles.dlPrimaryBtn}
                        onPress={() => DownloadEngine?.resumeDownload(item.id)}>
                        <Icon name="play-arrow" size={18} color="white" />
                        <Text style={styles.dlBtnText}>Resume</Text>
                      </FocusableButton>
                    )}

                    <FocusableButton
                      style={styles.dlDeleteBtn}
                      onPress={() => DownloadEngine?.deleteDownload(item.id)}>
                      <Icon name="delete-outline" size={20} color="#ff6b6b" />
                    </FocusableButton>
                  </View>
                </View>
              );
            }}
          />
        )}
        {renderHdrChoiceModal()}
      </View>
    );
  }

  // 🔥 4. POSTER CATALOG HOME SCREEN 🔥
  const numColumns = viewMode === 'grid' ? (IS_TV ? 5 : 2) : 1;
  const activeDownloadsCount = downloads.filter(
    d => d.status === 'downloading',
  ).length;

  return (
    <View style={styles.container}>
      <StatusBar hidden={false} barStyle="light-content" />
      <View style={styles.topHeaderBar}>
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <View style={styles.logoMark}>
            <Text style={styles.logoMarkText}>▶</Text>
          </View>
          <Text style={styles.headerTitle}>GDStream</Text>
        </View>

        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <FocusableButton
            style={styles.headerIconBtn}
            onPress={() => setActiveTab('downloads')}>
            <Icon
              name="file-download"
              size={22}
              color={activeDownloadsCount > 0 ? '#46d369' : 'white'}
            />
            {downloads.length > 0 && (
              <View style={styles.dlBadgeDot}>
                <Text style={styles.dlBadgeDotText}>{downloads.length}</Text>
              </View>
            )}
          </FocusableButton>
          <FocusableButton
            style={styles.headerIconBtn}
            onPress={handleRefresh}>
            <Icon name="refresh" size={21} color="white" />
          </FocusableButton>
          <FocusableButton
            style={styles.headerIconBtn}
            onPress={() =>
              setViewMode(prev => (prev === 'grid' ? 'list' : 'grid'))
            }>
            <Icon
              name={viewMode === 'grid' ? 'view-list' : 'grid-view'}
              size={22}
              color="white"
            />
          </FocusableButton>
          <FocusableButton
            style={styles.headerIconBtn}
            onPress={() => {
              setTempKeyInput(tmdbKey);
              setShowKeyModal(true);
            }}>
            <Icon
              name="vpn-key"
              size={20}
              color={tmdbKey ? '#46d369' : '#AAA'}
            />
          </FocusableButton>
        </View>
      </View>

      <View style={styles.searchRow}>
        <Icon
          name="search"
          size={20}
          color="#888"
          style={{ marginLeft: 12 }}
        />
        <TextInput
          style={styles.searchBarInput}
          placeholder="Search movies, series, 4K, Tamil..."
          placeholderTextColor="#777"
          value={searchQuery}
          onChangeText={handleSearch}
          autoCorrect={false}
          returnKeyType="search"
        />
        {!!searchQuery && (
          <TouchableOpacity
            onPress={() => handleSearch('')}
            style={{ padding: 8 }}>
            <Icon name="close" size={18} color="#AAA" />
          </TouchableOpacity>
        )}
      </View>

      {pathStack.length > 1 && (
        <FocusableButton style={styles.backFolderBar} onPress={goBack}>
          <Icon name="arrow-back" size={18} color="white" />
          <Text style={styles.backFolderText} numberOfLines={1}>
            Back • {decodeURIComponent(currentPath)}
          </Text>
        </FocusableButton>
      )}

      {loading ? (
        <ActivityIndicator
          size="large"
          color="#E50914"
          style={{ marginTop: 60 }}
        />
      ) : (
        <FlatList
          key={`catalog-${viewMode}-${numColumns}`}
          data={filteredFiles}
          numColumns={numColumns}
          keyboardShouldPersistTaps="handled"
          removeClippedSubviews={false}
          refreshControl={
            <RefreshControl
              refreshing={refreshing}
              onRefresh={handleRefresh}
              tintColor="#E50914"
              colors={['#E50914']}
              progressBackgroundColor="#181C24"
            />
          }
          contentContainerStyle={{ paddingHorizontal: 10, paddingBottom: 30 }}
          keyExtractor={(item, idx) => item.id || item.name || String(idx)}
          renderItem={({ item, index }) => {
            const isFolder = !!item.mimeType?.includes('folder');
            const info = parseMediaFilename(item.name, isFolder);
            const meta = metadataMap[item.name];
            const savedSec = savedTimesMap[item.name] || 0;

            if (viewMode === 'list') {
              return (
                <FocusableButton
                  hasTVPreferredFocus={index === 0 && !searchQuery}
                  style={styles.listCard}
                  onPress={() => handlePress(item)}>
                  {meta?.poster ? (
                    <Image
                      source={{ uri: meta.poster }}
                      style={styles.listPosterThumb}
                      resizeMode="cover"
                    />
                  ) : (
                    <View style={styles.listPosterFallback}>
                      <Icon
                        name={isFolder ? 'folder' : 'movie'}
                        size={26}
                        color={isFolder ? '#F5C518' : '#E50914'}
                      />
                    </View>
                  )}
                  <View style={styles.listCardBody}>
                    <Text style={styles.listCardTitle} numberOfLines={1}>
                      {info.cleanTitle}
                    </Text>
                    <View style={styles.listCardMetaRow}>
                      {!!(meta?.year || info.year) && (
                        <Text style={styles.cardSubText}>
                          {meta?.year || info.year}
                        </Text>
                      )}
                      {!!info.seasonEpisode && (
                        <Text style={styles.cardSeasonTag}>
                          {info.seasonEpisode}
                        </Text>
                      )}
                      {!!info.resolution && (
                        <Text style={styles.cardQualityTag}>
                          {info.resolution}
                        </Text>
                      )}
                      {info.badges.slice(0, 2).map((b, i) => (
                        <Text key={i} style={styles.cardBadgeMini}>
                          {b}
                        </Text>
                      ))}
                    </View>
                  </View>
                </FocusableButton>
              );
            }

            return (
              <FocusableButton
                hasTVPreferredFocus={index === 0 && !searchQuery}
                style={styles.posterCard}
                onPress={() => handlePress(item)}>
                <View style={styles.posterImageContainer}>
                  {meta?.poster ? (
                    <Image
                      source={{ uri: meta.poster }}
                      style={styles.posterImage}
                      resizeMode="cover"
                    />
                  ) : (
                    <View style={styles.posterFallback}>
                      <Icon
                        name={isFolder ? 'folder-special' : 'local-movies'}
                        size={38}
                        color={isFolder ? '#F5C518' : '#E50914'}
                      />
                      <Text
                        style={styles.posterFallbackTitle}
                        numberOfLines={3}>
                        {info.cleanTitle}
                      </Text>
                    </View>
                  )}

                  <View style={styles.posterTopBadges}>
                    {isFolder ? (
                      <View style={styles.folderPill}>
                        <Text style={styles.folderPillText}>
                          {info.seasonEpisode || 'SERIES'}
                        </Text>
                      </View>
                    ) : info.seasonEpisode ? (
                      <View style={styles.folderPill}>
                        <Text style={styles.folderPillText}>
                          {info.seasonEpisode}
                        </Text>
                      </View>
                    ) : meta?.rating ? (
                      <View style={styles.ratingPill}>
                        <Text style={styles.ratingPillText}>
                          ★ {meta.rating}
                        </Text>
                      </View>
                    ) : (
                      <View />
                    )}

                    {!!info.resolution && (
                      <View
                        style={[
                          styles.resPill,
                          info.resolution === '4K' && styles.resPill4K,
                        ]}>
                        <Text style={styles.resPillText}>
                          {info.resolution}
                        </Text>
                      </View>
                    )}
                  </View>

                  {savedSec > 10 && (
                    <View style={styles.resumeBarBg}>
                      <View style={styles.resumeBarFill} />
                    </View>
                  )}
                </View>

                <View style={styles.posterCardFooter}>
                  <Text style={styles.posterTitle} numberOfLines={1}>
                    {info.cleanTitle}
                  </Text>
                  <View style={styles.posterSubRow}>
                    <Text style={styles.posterYear}>
                      {meta?.year ||
                        info.year ||
                        (isFolder ? 'Folder' : 'Movie')}
                    </Text>
                    {info.badges.slice(0, 2).map((b, idx) => (
                      <Text key={idx} style={styles.posterMiniTag}>
                        • {b}
                      </Text>
                    ))}
                  </View>
                </View>
              </FocusableButton>
            );
          }}
        />
      )}

      <Modal
        visible={showKeyModal}
        transparent={true}
        animationType="fade"
        onRequestClose={() => setShowKeyModal(false)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>TMDb API Key (Optional)</Text>
            <Text style={styles.modalSub}>
              Posters work automatically out of the box! Add a 32-character TMDb
              API key if you also want 16:9 backdrops, ratings & movie plots.
            </Text>
            <TextInput
              style={styles.modalInput}
              placeholder="Paste TMDb API key..."
              placeholderTextColor="#666"
              value={tempKeyInput}
              onChangeText={setTempKeyInput}
              autoCapitalize="none"
            />
            <View style={styles.modalBtnRow}>
              <TouchableOpacity
                style={styles.modalCancelBtn}
                onPress={() => setShowKeyModal(false)}>
                <Text style={{ color: '#CCC' }}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={styles.modalSaveBtn}
                onPress={saveTmdbKey}>
                <Text style={{ color: 'white', fontWeight: 'bold' }}>Save</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0B0C10', paddingTop: 38 },
  topHeaderBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    marginBottom: 12,
  },
  logoMark: {
    width: 30,
    height: 30,
    borderRadius: 7,
    backgroundColor: '#E50914',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 9,
  },
  logoMarkText: { color: 'white', fontSize: 15, fontWeight: '900' },
  headerTitle: {
    color: '#FFFFFF',
    fontSize: 22,
    fontWeight: '800',
    letterSpacing: 0.4,
  },
  headerIconBtn: {
    padding: 8,
    borderRadius: 8,
    backgroundColor: '#1A1D24',
    borderWidth: 2,
    borderColor: 'transparent',
    position: 'relative',
  },
  dlBadgeDot: {
    position: 'absolute',
    top: -4,
    right: -4,
    backgroundColor: '#E50914',
    minWidth: 16,
    height: 16,
    borderRadius: 8,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 3,
  },
  dlBadgeDotText: { color: 'white', fontSize: 9, fontWeight: '900' },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#161920',
    marginHorizontal: 14,
    marginBottom: 12,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#262B36',
  },
  searchBarInput: {
    flex: 1,
    color: 'white',
    paddingVertical: 11,
    paddingHorizontal: 10,
    fontSize: 15,
  },
  backFolderBar: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#181C24',
    paddingVertical: 9,
    paddingHorizontal: 14,
    marginHorizontal: 14,
    borderRadius: 8,
    marginBottom: 10,
    gap: 8,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  backFolderText: { color: '#DDD', fontSize: 13, fontWeight: '600', flex: 1 },
  posterCard: {
    flex: 1,
    margin: 6,
    backgroundColor: '#14171F',
    borderRadius: 12,
    overflow: 'hidden',
    borderWidth: 2,
    borderColor: '#1F2430',
  },
  posterImageContainer: {
    width: '100%',
    aspectRatio: 2 / 3,
    backgroundColor: '#191D26',
    position: 'relative',
  },
  posterImage: { width: '100%', height: '100%' },
  posterFallback: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 14,
    backgroundColor: '#171B24',
  },
  posterFallbackTitle: {
    color: '#D5DCE6',
    fontSize: 14,
    fontWeight: '700',
    textAlign: 'center',
    marginTop: 10,
  },
  posterTopBadges: {
    position: 'absolute',
    top: 8,
    left: 8,
    right: 8,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  folderPill: {
    backgroundColor: 'rgba(245, 197, 24, 0.92)',
    paddingHorizontal: 7,
    paddingVertical: 3,
    borderRadius: 5,
  },
  folderPillText: { color: '#000', fontSize: 10, fontWeight: '900' },
  ratingPill: {
    backgroundColor: 'rgba(0, 0, 0, 0.78)',
    paddingHorizontal: 7,
    paddingVertical: 3,
    borderRadius: 5,
    borderWidth: 1,
    borderColor: 'rgba(245, 197, 24, 0.45)',
  },
  ratingPillText: { color: '#F5C518', fontSize: 10, fontWeight: '800' },
  resPill: {
    backgroundColor: 'rgba(15, 20, 28, 0.85)',
    paddingHorizontal: 6,
    paddingVertical: 3,
    borderRadius: 5,
    borderWidth: 1,
    borderColor: '#3A4454',
  },
  resPill4K: {
    backgroundColor: '#E50914',
    borderColor: '#FF4B55',
  },
  resPillText: { color: '#FFF', fontSize: 10, fontWeight: '900' },
  resumeBarBg: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: 4,
    backgroundColor: 'rgba(255,255,255,0.25)',
  },
  resumeBarFill: {
    width: '45%',
    height: '100%',
    backgroundColor: '#E50914',
  },
  posterCardFooter: {
    paddingHorizontal: 10,
    paddingVertical: 9,
  },
  posterTitle: {
    color: '#F2F5F8',
    fontSize: 14,
    fontWeight: '700',
  },
  posterSubRow: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    marginTop: 3,
    gap: 4,
  },
  posterYear: { color: '#8A94A6', fontSize: 11, fontWeight: '600' },
  posterMiniTag: { color: '#A5B0C2', fontSize: 11 },
  listCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#14171F',
    marginHorizontal: 6,
    marginVertical: 5,
    padding: 10,
    borderRadius: 10,
    borderWidth: 2,
    borderColor: '#1F2430',
  },
  listPosterThumb: {
    width: 52,
    height: 76,
    borderRadius: 6,
    backgroundColor: '#1E222B',
  },
  listPosterFallback: {
    width: 52,
    height: 76,
    borderRadius: 6,
    backgroundColor: '#1E222B',
    alignItems: 'center',
    justifyContent: 'center',
  },
  listCardBody: { flex: 1, marginLeft: 12 },
  listCardTitle: { color: '#FFF', fontSize: 16, fontWeight: '700' },
  listCardMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 6,
    marginTop: 6,
  },
  cardSubText: { color: '#99A3B3', fontSize: 12, fontWeight: '600' },
  cardSeasonTag: {
    backgroundColor: '#293242',
    color: '#FFD166',
    fontSize: 11,
    fontWeight: '700',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  cardQualityTag: {
    backgroundColor: '#E50914',
    color: '#FFF',
    fontSize: 11,
    fontWeight: '800',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  cardBadgeMini: {
    backgroundColor: '#1F2531',
    color: '#C2CBD8',
    fontSize: 11,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  tvFocused: {
    borderColor: '#E50914',
    borderWidth: 2,
    backgroundColor: 'rgba(229, 9, 20, 0.22)',
  },
  detailScreen: { flex: 1, backgroundColor: '#0B0C10' },
  detailHero: {
    width: '100%',
    height: 240,
    backgroundColor: '#141821',
    position: 'relative',
  },
  detailBackdrop: { width: '100%', height: '100%' },
  detailBackdropPlaceholder: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#181D29',
  },
  placeholderMonogram: {
    color: 'rgba(229, 9, 20, 0.35)',
    fontSize: 84,
    fontWeight: '900',
  },
  detailHeroShade: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    top: 0,
    backgroundColor: 'rgba(11, 12, 16, 0.45)',
  },
  detailBackTopBtn: {
    position: 'absolute',
    top: 40,
    left: 16,
    width: 42,
    height: 42,
    borderRadius: 21,
    backgroundColor: 'rgba(0,0,0,0.65)',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  detailContent: {
    paddingHorizontal: 18,
    marginTop: -36,
  },
  detailHeaderRow: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: 14,
  },
  detailPosterThumb: {
    width: 105,
    height: 156,
    borderRadius: 10,
    borderWidth: 2,
    borderColor: '#2A3140',
    backgroundColor: '#161922',
  },
  detailTitle: {
    color: '#FFFFFF',
    fontSize: 22,
    fontWeight: '800',
    lineHeight: 28,
  },
  detailMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: 8,
    marginTop: 8,
  },
  detailYearText: { color: '#B0BAC9', fontSize: 14, fontWeight: '700' },
  seasonBadge: {
    backgroundColor: '#F5C518',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 5,
  },
  seasonBadgeText: { color: '#000', fontSize: 12, fontWeight: '800' },
  ratingBadge: {
    backgroundColor: '#1E2533',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 5,
    borderWidth: 1,
    borderColor: '#F5C518',
  },
  ratingBadgeText: { color: '#F5C518', fontSize: 12, fontWeight: '800' },
  qualityBadge: {
    backgroundColor: '#E50914',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 5,
  },
  qualityBadgeText: { color: '#FFF', fontSize: 12, fontWeight: '900' },
  badgeWrapRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginTop: 10,
  },
  techChip: {
    backgroundColor: '#1A202C',
    borderWidth: 1,
    borderColor: '#2E3748',
    paddingHorizontal: 9,
    paddingVertical: 4,
    borderRadius: 6,
  },
  techChipText: { color: '#D1D9E6', fontSize: 12, fontWeight: '600' },
  detailOverview: {
    color: '#C4CDD9',
    fontSize: 14,
    lineHeight: 21,
    marginTop: 18,
  },
  detailActionButtons: {
    marginTop: 22,
    gap: 12,
  },
  primaryPlayButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#E50914',
    paddingVertical: 14,
    paddingHorizontal: 20,
    borderRadius: 10,
    gap: 8,
    borderWidth: 2,
    borderColor: 'transparent',
  },
  primaryPlayButtonText: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '800',
  },
  secondaryPlayButton: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#1E2430',
    paddingVertical: 13,
    paddingHorizontal: 20,
    borderRadius: 10,
    gap: 8,
    borderWidth: 2,
    borderColor: '#2E3748',
  },
  secondaryPlayButtonText: {
    color: '#E2E8F0',
    fontSize: 15,
    fontWeight: '700',
  },
  downloadActionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#141B24',
    paddingVertical: 13,
    paddingHorizontal: 20,
    borderRadius: 10,
    gap: 8,
    borderWidth: 1.5,
    borderColor: 'rgba(70, 211, 105, 0.45)',
  },
  downloadActionText: {
    color: '#46d369',
    fontSize: 15,
    fontWeight: '800',
  },
  downloadedReadyCard: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(70, 211, 105, 0.12)',
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 10,
    gap: 8,
    borderWidth: 1,
    borderColor: 'rgba(70, 211, 105, 0.4)',
  },
  downloadedReadyText: {
    color: '#46d369',
    fontSize: 13,
    fontWeight: '700',
  },
  downloadProgressCard: {
    backgroundColor: '#151922',
    padding: 14,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#2B3344',
  },
  dlCardHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  dlCardStatusText: { color: '#46d369', fontSize: 12, fontWeight: '800' },
  dlCardPercentText: { color: 'white', fontSize: 12, fontWeight: '800' },
  dlProgressBarBg: {
    height: 6,
    backgroundColor: '#252C3A',
    borderRadius: 3,
    overflow: 'hidden',
    marginVertical: 6,
  },
  dlProgressBarFill: {
    height: '100%',
    backgroundColor: '#E50914',
    borderRadius: 3,
  },
  dlCardSubText: { color: '#99A3B3', fontSize: 11, marginTop: 2 },
  rawFileBox: {
    marginTop: 24,
    padding: 12,
    borderRadius: 8,
    backgroundColor: '#13161E',
    borderWidth: 1,
    borderColor: '#222834',
  },
  rawFileLabel: {
    color: '#6C7789',
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1,
    marginBottom: 4,
  },
  rawFileName: { color: '#8F9BB0', fontSize: 12 },
  // HDR vs DV Modal
  hdrModalCard: {
    width: '100%',
    maxWidth: 400,
    backgroundColor: '#141821',
    borderRadius: 16,
    padding: 20,
    borderWidth: 1,
    borderColor: '#2D3546',
  },
  hdrModalEyebrow: {
    color: '#E50914',
    fontSize: 11,
    fontWeight: '900',
    letterSpacing: 1.2,
  },
  hdrModalTitle: {
    color: 'white',
    fontSize: 20,
    fontWeight: '800',
    marginTop: 4,
  },
  hdrModalSub: {
    color: '#9AA5B5',
    fontSize: 13,
    lineHeight: 19,
    marginTop: 6,
    marginBottom: 16,
  },
  dvOptionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1B1F2C',
    padding: 14,
    borderRadius: 12,
    borderWidth: 1.5,
    borderColor: '#7C4DFF',
    gap: 12,
    marginBottom: 12,
  },
  dvOptionBadge: {
    backgroundColor: '#5E35B1',
    paddingHorizontal: 8,
    paddingVertical: 5,
    borderRadius: 6,
  },
  dvOptionBadgeText: {
    color: '#FFD700',
    fontSize: 10,
    fontWeight: '900',
  },
  hdr10OptionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1B1F2C',
    padding: 14,
    borderRadius: 12,
    borderWidth: 1.5,
    borderColor: '#F5C518',
    gap: 12,
  },
  hdr10OptionBadge: {
    backgroundColor: '#F5C518',
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 6,
  },
  hdr10OptionBadgeText: {
    color: '#000',
    fontSize: 11,
    fontWeight: '900',
  },
  hdrOptionTitle: { color: 'white', fontSize: 15, fontWeight: '800' },
  hdrOptionDesc: { color: '#99A3B3', fontSize: 11, marginTop: 2 },
  hdrCancelBtn: {
    alignItems: 'center',
    paddingVertical: 12,
    marginTop: 8,
  },
  // Downloads Tab Styles
  storageHintPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: '#171B24',
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#283040',
  },
  storageHintText: { color: '#C2CBD8', fontSize: 11, fontWeight: '700' },
  emptyDownloadsBox: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 34,
  },
  emptyDownloadsTitle: {
    color: 'white',
    fontSize: 18,
    fontWeight: '800',
    marginTop: 14,
  },
  emptyDownloadsSub: {
    color: '#8C97A8',
    fontSize: 13,
    textAlign: 'center',
    lineHeight: 20,
    marginTop: 8,
  },
  dlItemCard: {
    backgroundColor: '#14171F',
    borderRadius: 12,
    padding: 14,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: '#232938',
  },
  dlPosterThumb: {
    width: 60,
    height: 88,
    borderRadius: 8,
    backgroundColor: '#1E222B',
  },
  dlPosterFallback: {
    width: 60,
    height: 88,
    borderRadius: 8,
    backgroundColor: '#1E222B',
    alignItems: 'center',
    justifyContent: 'center',
  },
  dlItemTitle: { color: 'white', fontSize: 16, fontWeight: '800' },
  dlItemFile: { color: '#8A94A6', fontSize: 11, marginTop: 2 },
  dlStatsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 4,
  },
  dlStatsText: { color: '#A5B0C2', fontSize: 11, fontWeight: '600' },
  dlSpeedText: { color: '#46d369', fontSize: 11, fontWeight: '800' },
  dlActionsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginTop: 12,
  },
  dlPrimaryBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#E50914',
    paddingVertical: 9,
    borderRadius: 8,
    gap: 6,
  },
  dlSecondaryBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#222836',
    paddingVertical: 9,
    borderRadius: 8,
    gap: 6,
  },
  dlDeleteBtn: {
    padding: 9,
    borderRadius: 8,
    backgroundColor: 'rgba(255, 107, 107, 0.12)',
  },
  dlBtnText: { color: 'white', fontSize: 13, fontWeight: '700' },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.78)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 20,
  },
  modalCard: {
    width: '100%',
    maxWidth: 380,
    backgroundColor: '#181C24',
    borderRadius: 14,
    padding: 20,
    borderWidth: 1,
    borderColor: '#2D3545',
  },
  modalTitle: { color: 'white', fontSize: 17, fontWeight: '800' },
  modalSub: { color: '#9AA5B5', fontSize: 13, marginTop: 6, lineHeight: 18 },
  modalInput: {
    backgroundColor: '#0E1117',
    color: 'white',
    borderRadius: 8,
    padding: 12,
    marginTop: 14,
    borderWidth: 1,
    borderColor: '#2E3748',
  },
  modalBtnRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 10,
    marginTop: 16,
  },
  modalCancelBtn: { paddingVertical: 10, paddingHorizontal: 16 },
  modalSaveBtn: {
    backgroundColor: '#E50914',
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 8,
  },
  // PLAYER STYLES
  playerWrapper: { flex: 1, backgroundColor: 'black' },
  videoPlayer: { position: 'absolute', top: 0, left: 0, bottom: 0, right: 0 },
  externalSubtitleContainer: {
    position: 'absolute',
    left: 24,
    right: 24,
    bottom: 52,
    alignItems: 'center',
    justifyContent: 'center',
  },
  externalSubtitleText: {
    color: '#FFFFFF',
    backgroundColor: 'transparent',
    fontWeight: '700',
    textAlign: 'center',
    textShadowColor: 'rgba(0, 0, 0, 0.95)',
    textShadowOffset: { width: 1.5, height: 1.5 },
    textShadowRadius: 4,
  },
  gestureHudContainer: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    justifyContent: 'center',
  },
  gestureHudLeft: {
    alignItems: 'flex-start',
    paddingLeft: 42,
  },
  gestureHudRight: {
    alignItems: 'flex-end',
    paddingRight: 42,
  },
  gestureHudPill: {
    width: 60,
    paddingVertical: 14,
    borderRadius: 30,
    backgroundColor: 'rgba(15, 17, 23, 0.88)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.14)',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
  },
  gestureBarTrack: {
    width: 7,
    height: 105,
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderRadius: 4,
    overflow: 'hidden',
    justifyContent: 'flex-end',
  },
  gestureBarFill: {
    width: '100%',
    backgroundColor: '#E50914',
    borderRadius: 4,
  },
  gestureHudText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '800',
  },
  controlsOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    bottom: 0,
    right: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.48)',
    justifyContent: 'space-between',
  },
  lockedOverlay: {
    position: 'absolute',
    bottom: 32,
    left: 0,
    right: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
  unlockPillBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: 'rgba(15, 17, 23, 0.9)',
    paddingVertical: 11,
    paddingHorizontal: 22,
    borderRadius: 999,
    borderWidth: 1.5,
    borderColor: '#E50914',
  },
  unlockPillText: {
    color: 'white',
    fontSize: 14,
    fontWeight: '700',
  },
  seekOverlay: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    justifyContent: 'center',
    paddingHorizontal: 60,
  },
  netflixSeekBubble: {
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.55)',
    width: 92,
    height: 92,
    borderRadius: 46,
  },
  seekText: { color: 'white', fontSize: 13, fontWeight: '800', marginTop: 2 },
  netflixTopBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 22,
    paddingTop: 18,
  },
  netflixCircleBtn: {
    width: 42,
    height: 42,
    borderRadius: 21,
    backgroundColor: 'rgba(20, 22, 30, 0.72)',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1.5,
    borderColor: 'rgba(255,255,255,0.12)',
  },
  netflixTitleCenter: {
    flex: 1,
    alignItems: 'center',
    paddingHorizontal: 14,
  },
  netflixMainTitle: {
    color: '#FFFFFF',
    fontSize: 17,
    fontWeight: '800',
    letterSpacing: 0.3,
  },
  netflixSubMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginTop: 4,
  },
  netflixEpisodeTag: {
    color: '#F5C518',
    fontSize: 12,
    fontWeight: '800',
  },
  netflixMiniBadge: {
    backgroundColor: '#E50914',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  netflixMiniBadgeText: {
    color: 'white',
    fontSize: 10,
    fontWeight: '900',
  },
  liveHdrStatusPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  liveDvPill: {
    backgroundColor: '#5E35B1',
    borderWidth: 1,
    borderColor: '#FFD700',
  },
  liveHdr10Pill: {
    backgroundColor: '#F5C518',
  },
  liveHdrStatusText: {
    color: '#FFD700',
    fontSize: 10,
    fontWeight: '900',
    letterSpacing: 0.4,
  },
  netflixMetaDotText: {
    color: '#D0D6E0',
    fontSize: 11,
    fontWeight: '600',
  },
  netflixTopRightRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  netflixCenterRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 54,
  },
  netflixSeekCircleBtn: {
    width: 64,
    height: 64,
    borderRadius: 32,
    backgroundColor: 'rgba(18, 20, 28, 0.6)',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1.5,
    borderColor: 'rgba(255,255,255,0.16)',
  },
  netflixMainPlayBtn: {
    width: 82,
    height: 82,
    borderRadius: 41,
    backgroundColor: 'rgba(229, 9, 20, 0.9)',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
    borderColor: 'rgba(255,255,255,0.25)',
  },
  netflixBottomContainer: {
    paddingHorizontal: 22,
    paddingBottom: 18,
    width: '100%',
  },
  netflixScrubberRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 10,
  },
  netflixTimeLabel: {
    color: '#E6ECF5',
    fontSize: 12,
    fontWeight: '700',
    minWidth: 52,
    textAlign: 'center',
  },
  netflixProgressHitBox: {
    flex: 1,
    height: 28,
    justifyContent: 'center',
  },
  netflixProgressTrack: {
    height: 4,
    backgroundColor: 'rgba(255,255,255,0.28)',
    borderRadius: 2,
  },
  netflixProgressFill: {
    height: 4,
    backgroundColor: '#E50914',
    borderRadius: 2,
    position: 'absolute',
  },
  netflixProgressThumb: {
    position: 'absolute',
    width: 14,
    height: 14,
    borderRadius: 7,
    backgroundColor: '#E50914',
    top: 7,
    marginLeft: -7,
    borderWidth: 2,
    borderColor: '#FFFFFF',
  },
  netflixActionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-around',
    flexWrap: 'wrap',
    gap: 8,
  },
  netflixActionPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingVertical: 7,
    paddingHorizontal: 13,
    borderRadius: 999,
    backgroundColor: 'rgba(22, 25, 34, 0.78)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
  },
  netflixActionPillActive: {
    borderColor: '#E50914',
    backgroundColor: 'rgba(229, 9, 20, 0.2)',
  },
  netflixActionText: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '700',
  },
  netflixNextEpPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingVertical: 7,
    paddingHorizontal: 14,
    borderRadius: 999,
    backgroundColor: '#FFFFFF',
  },
  netflixNextEpText: {
    color: '#000000',
    fontSize: 12,
    fontWeight: '900',
  },
  netflixSheetOverlay: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: 'rgba(0,0,0,0.55)',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 100,
  },
  floatingMenu: {
    width: 340,
    maxWidth: '90%',
    backgroundColor: '#14171F',
    borderRadius: 16,
    padding: 16,
    borderWidth: 1,
    borderColor: '#2B3242',
  },
  floatingMenuHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderBottomWidth: 1,
    borderBottomColor: '#262C3A',
    paddingBottom: 10,
    marginBottom: 10,
  },
  floatingMenuTitle: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '800',
  },
  menuCloseBtn: {
    padding: 4,
    borderRadius: 6,
  },
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    paddingHorizontal: 8,
    borderRadius: 8,
  },
  menuItemText: { color: 'white', fontSize: 14, marginLeft: 10, flex: 1 },
  adjustRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 8 },
  adjustLabel: { color: '#AAA', fontSize: 13, width: 95, fontWeight: '600' },
  adjustBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: '#232936',
    justifyContent: 'center',
    alignItems: 'center',
  },
  adjustValue: {
    color: 'white',
    fontSize: 14,
    fontWeight: 'bold',
    width: 68,
    textAlign: 'center',
  },
});

export default App;
