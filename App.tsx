import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import {
  View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator,
  Alert, NativeModules, TextInput, ScrollView, BackHandler, Animated,
  Platform, DeviceEventEmitter, Image, Modal, PanResponder, Dimensions,
  StatusBar, RefreshControl,
} from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';
import Orientation from 'react-native-orientation-locker';
import Icon from 'react-native-vector-icons/MaterialIcons';
import AsyncStorage from '@react-native-async-storage/async-storage';

const {
  VideoPlayerManager, SubtitleFileModule, DownloadEngine, MediaInfoModule, SecurityVault,
} = NativeModules;

const IS_TV = Platform.isTV;

interface ParsedMediaInfo {
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

interface MediaMetadata {
  poster?: string;
  backdrop?: string;
  rating?: string;
  overview?: string;
  year?: string;
  cast?: string;
}

interface RealMediaInfo {
  width: number;
  height: number;
  resolution: string;
  videoCodec: string;
  videoBitrate?: string;
  overallBitrate?: string;
  frameRate: number;
  durationSec: number;
  totalBytes: number;
  hasDV: boolean;
  hasHDR: boolean;
  dvProfile: number;
  hasAtmos: boolean;
  audioTracks: Array<{ language: string; codec: string; channels: string; bitrateKbps: number; summary: string }>;
  subtitleTracks: Array<{ language: string; format: string }>;
  languages: string[];
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

interface SeasonFolderItem {
  seasonNum: number;
  label: string;
  folderName: string;
  path: string;
}

const TYPO_FIXES: Record<string, string> = {
  'attach on titan': 'Attack on Titan',
};

const FILTER_CATEGORIES = [
  { id: 'all', label: 'All' },
  { id: 'movies', label: 'Movies 🎬' },
  { id: 'series', label: 'Series 📁' },
  { id: '4k', label: '4K UHD' },
  { id: 'dv', label: 'Dolby Vision' },
  { id: 'watchlist', label: 'My List ❤️' },
];

const formatBytes = (bytes: number) => {
  if (!bytes || bytes <= 0) return '0 MB';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let val = bytes;
  let idx = 0;
  while (val >= 1024 && idx < units.length - 1) { val /= 1024; idx++; }
  return `${val.toFixed(idx >= 3 ? 2 : 1)} ${units[idx]}`;
};

const formatRuntime = (seconds: number) => {
  if (!seconds || seconds <= 0) return '';
  const totalMin = Math.max(1, Math.round(seconds / 60));
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
};

const formatTime = (seconds: number) => {
  if (isNaN(seconds) || seconds < 0) return '00:00';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
  return `${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
};

const formatRemainingLabel = (savedSec: number, totalDur: number) => {
  if (totalDur > savedSec + 30) {
    const remSec = totalDur - savedSec;
    const totalMin = Math.max(1, Math.round(remSec / 60));
    const h = Math.floor(totalMin / 60);
    const m = totalMin % 60;
    return h > 0 ? `${h}h ${m}m left` : `${m}m left`;
  }
  return `at ${formatTime(savedSec)}`;
};

const getVersionTag = (name: string): string => {
  const n = name.toUpperCase();
  const tags: string[] = [];
  if (/\bREMUX\b/.test(n)) tags.push('BluRay REMUX');
  else if (/\b(BLURAY|BLU-RAY|BDRIP|BRRIP)\b/.test(n)) tags.push('BluRay');
  else if (/\b(WEB-DL|WEBDL|WEB\s*DL)\b/.test(n)) tags.push('WEB-DL');
  else if (/\bWEBRIP\b/.test(n)) tags.push('WEBRip');
  else tags.push('Standard');

  if (/\b(2160P|4K|UHD)\b/.test(n)) tags.push('4K');
  else if (/\b1080P\b/.test(n)) tags.push('1080p');
  else if (/\b720P\b/.test(n)) tags.push('720p');

  if (/\b(DV|DOVI|DOLBY\s*VISION)\b/.test(n)) tags.push('DV');
  if (/\b(HDR10\+|HDR10|HDR)\b/.test(n)) tags.push('HDR');
  return tags.join(' • ');
};

const parseMediaFilename = (name: string, isFolder = false): ParsedMediaInfo => {
  const withoutExt = isFolder ? name : name.replace(/\.(mkv|mp4|avi|mov|webm|m4v|ts)$/i, '');
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
  const sMatch = normalized.match(/\b(?:S|Season\s*)(\d{1,2})\b/i);
  if (sMatch) seasonNum = parseInt(sMatch[1], 10);
  const eMatch = normalized.match(/\b(?:S\d{1,2}\s*E|E|Ep\s*|Episode\s*)(\d{1,3})\b/i);
  if (eMatch) episodeNum = parseInt(eMatch[1], 10);

  const seMatch = normalized.match(/\b(S\d{1,2}(?:\s*E\d{1,3})?|E\d{1,3}|Season\s*\d{1,2})\b/i);
  const seasonEpisode = seMatch ? seMatch[1].replace(/\s+/g, '').toUpperCase() : '';

  const yearMatch = normalized.match(/(?:^|[\s(])((?:19|20)\d{2})(?:[\s)]|$)/);
  const year = yearMatch ? yearMatch[1] : '';

  const cutPattern = /\b(2160p|1080p|720p|480p|4k|uhd|bluray|blu-ray|remux|web-dl|webdl|webrip|web|amzn|nf|snxt|sonyliv|jiohotstar|zee5|aha|it|hdtv|ddp\d|dd[+]|ddp|aac|eac3|ac3|truehd|dts|dtshd|hevc|avc|x264|x265|h\s*264|h\s*265|multi|dual|org|fhd|jpn|kor|tam|tel|mal|hin|kan|eng|esub|esubs|msub|msubs|subs|sub|uncut|remastered|imax)\b/i;
  let titlePart = normalized;
  if (seMatch && seMatch.index !== undefined && seMatch.index > 1) titlePart = titlePart.slice(0, seMatch.index);
  if (yearMatch && yearMatch.index !== undefined && yearMatch.index > 1) {
    const beforeYear = normalized.slice(0, yearMatch.index);
    if (beforeYear.length < titlePart.length) titlePart = beforeYear;
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

const extractSeasonNumberFromFolder = (name: string): number | null => {
  const norm = name.replace(/[._-]+/g, ' ').trim();
  const m1 = norm.match(/\bSeason\s*(\d{1,2})\b/i);
  if (m1) return parseInt(m1[1], 10);
  const m2 = norm.match(/\bS(\d{1,2})\b/i);
  if (m2 && !/\bS\d{1,2}\s*[-–]\s*S\d{1,2}\b/i.test(norm)) return parseInt(m2[1], 10);
  return null;
};

const FocusableButton = ({ style, focusedStyle, onPress, children, hasTVPreferredFocus, activeOpacity = 0.8, ...rest }: any) => {
  const [isFocused, setIsFocused] = useState(false);
  return (
    <TouchableOpacity
      focusable={IS_TV ? true : undefined}
      hasTVPreferredFocus={IS_TV ? hasTVPreferredFocus : undefined}
      activeOpacity={activeOpacity}
      onPress={onPress}
      onFocus={IS_TV ? () => setIsFocused(true) : undefined}
      onBlur={IS_TV ? () => setIsFocused(false) : undefined}
      style={[style, IS_TV && isFocused && styles.tvFocused, IS_TV && isFocused && focusedStyle]}
      {...rest}>
      {children}
    </TouchableOpacity>
  );
};

const parseCueTime = (ts: string) => {
  const m = ts.trim().match(/(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})/);
  if (!m) return -1;
  return parseInt(m[1] || '0', 10) * 3600 + parseInt(m[2], 10) * 60 + parseInt(m[3], 10) + parseInt(m[4].padEnd(3, '0'), 10) / 1000;
};

const parseSubtitleFile = (raw: string) => {
  const cues: Array<{ start: number; end: number; text: string }> = [];
  const blocks = raw.replace(/\r/g, '').replace(/^WEBVTT.*\n/i, '').split(/\n\n+/);
  for (const block of blocks) {
    const lines = block.split('\n').filter(l => l.trim() !== '');
    if (!lines.length) continue;
    const timeLineIdx = lines.findIndex(l => l.includes('-->'));
    if (timeLineIdx === -1) continue;
    const [startStr, endStr] = lines[timeLineIdx].split('-->');
    const start = parseCueTime(startStr);
    const end = parseCueTime(endStr);
    if (start < 0 || end < 0) continue;
    const text = lines.slice(timeLineIdx + 1).map(l => l.replace(/<[^>]+>/g, '').trim()).filter(Boolean).join('\n');
    if (text) cues.push({ start, end, text });
  }
  return cues;
};

const App = () => {
  const vaultRef = useRef<{ baseUrl: string; username: string; password: string; tmdbKey: string }>({
    baseUrl: '', username: '', password: '', tmdbKey: '',
  });
  const [vaultReady, setVaultReady] = useState(false);
  const [securityCompromised, setSecurityCompromised] = useState(false);

  const [files, setFiles] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [pathStack, setPathStack] = useState(['/0:/']);
  const [currentPath, setCurrentPath] = useState('/0:/');
  const [searchQuery, setSearchQuery] = useState('');
  const [activeCategory, setActiveCategory] = useState('all');
  const [sortBy, setSortBy] = useState<'newest' | 'az'>('newest');
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [activeTab, setActiveTab] = useState<'catalog' | 'downloads'>('catalog');

  const [seasonFolders, setSeasonFolders] = useState<SeasonFolderItem[]>([]);
  const [activeSeason, setActiveSeason] = useState<SeasonFolderItem | null>(null);
  const [seriesParentTitle, setSeriesParentTitle] = useState('');
  const [showSeasonModal, setShowSeasonModal] = useState(false);

  const [watchlist, setWatchlist] = useState<Record<string, boolean>>({});
  const [metadataMap, setMetadataMap] = useState<Record<string, MediaMetadata>>({});
  const [mediaInfoMap, setMediaInfoMap] = useState<Record<string, RealMediaInfo>>({});
  const [scanningMediaInfo, setScanningMediaInfo] = useState(false);
  const [savedTimesMap, setSavedTimesMap] = useState<Record<string, number>>({});
  const [savedDurationsMap, setSavedDurationsMap] = useState<Record<string, number>>({});

  const [downloads, setDownloads] = useState<DownloadItem[]>([]);
  const [activeHdrMode, setActiveHdrMode] = useState<'dv' | 'hdr' | 'sdr'>('dv');
  const [showHdrChoiceModal, setShowHdrChoiceModal] = useState(false);
  const [playerInstanceKey, setPlayerInstanceKey] = useState(0);

  const [selectedFile, setSelectedFile] = useState<any>(null);
  const [versionGroup, setVersionGroup] = useState<any[]>([]);
  const [playMode, setPlayMode] = useState<string | null>(null);
  const [resizeMode, setResizeMode] = useState<'contain' | 'cover'>('contain');
  const [showControls, setShowControls] = useState(true);
  const [isLocked, setIsLocked] = useState(false);
  const [isPaused, setIsPaused] = useState(false);
  const [isVideoBuffering, setIsVideoBuffering] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(IS_TV);
  const [playbackRate, setPlaybackRate] = useState(1.0);

  const [brightnessLevel, setBrightnessLevel] = useState(0.65);
  const [volumeLevel, setVolumeLevel] = useState(0.8);
  const brightnessRef = useRef(0.65);
  const volumeRef = useRef(0.8);
  const [gestureIndicator, setGestureIndicator] = useState<{ visible: boolean; type: 'brightness' | 'volume'; value: number }>({
    visible: false, type: 'volume', value: 0.8,
  });
  const gestureHideTimer = useRef<any>(null);

  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const currentTimeRef = useRef(0);
  const durationRef = useRef(0);
  const [progressWidth, setProgressWidth] = useState(0);
  const videoRef = useRef<any>(null);
  const externalAudioRef = useRef<any>(null);
  const externalAudioTimeRef = useRef(0);
  const lastAudioSyncMs = useRef(0);
  const switchingEpisode = useRef(false);
  const isLoggedIn = useRef(false);
  const lastSavedSec = useRef(-1);
  const hasRestoredResumeRef = useRef(false);
  const fetchedKeysRef = useRef<Set<string>>(new Set());

  const [audioTracks, setAudioTracks] = useState<any[]>([]);
  const [textTracks, setTextTracks] = useState<any[]>([]);
  const [selectedAudio, setSelectedAudio] = useState<any>(undefined);
  const [selectedText, setSelectedText] = useState<any>({ type: 'disabled' });
  const [activeMenu, setActiveMenu] = useState<string | null>(null);

  const [subtitleSize, setSubtitleSize] = useState(20);
  const [subtitleDelay, setSubtitleDelay] = useState(0);
  const [audioDelay, setAudioDelay] = useState(0);
  const [externalSubtitle, setExternalSubtitle] = useState<{ name: string; content: string } | null>(null);
  const [externalAudio, setExternalAudio] = useState<{ name: string; uri: string } | null>(null);

  const isExternalSubtitleSelected = selectedText?.type === 'external' && !!externalSubtitle;
  const isExternalAudioSelected = selectedAudio?.type === 'external' && !!externalAudio;
  const subtitlesActive = !!selectedText && selectedText.type !== 'disabled' && selectedText.type !== 'external';

  const parsedExternalCues = useMemo(() => externalSubtitle?.content ? parseSubtitleFile(externalSubtitle.content) : [], [externalSubtitle]);
  const activeExternalSubtitleText = useMemo(() => {
    if (!isExternalSubtitleSelected || !parsedExternalCues.length) return '';
    const effectiveTime = currentTime - subtitleDelay;
    const cue = parsedExternalCues.find(c => effectiveTime >= c.start && effectiveTime <= c.end);
    return cue ? cue.text : '';
  }, [isExternalSubtitleSelected, parsedExternalCues, currentTime, subtitleDelay]);

  const [seekOverlay, setSeekOverlay] = useState({ visible: false, icon: '', time: 0, position: 'center' });
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const accumulatedSeek = useRef(0);
  const seekBaseTime = useRef<number | null>(null);
  const multiTapTimer = useRef<any>(null);
  const singleTapTimer = useRef<any>(null);
  const lastTapTime = useRef(0);
  const lastTapDirection = useRef<boolean | null>(null);

  const persistPlaybackProgress = useCallback((fileName: string | undefined, sec: number, dur: number) => {
    if (!fileName || sec <= 3) return;
    if (dur > 0 && dur - sec < 90) {
      AsyncStorage.removeItem(`time_${fileName}`).catch(() => {});
      setSavedTimesMap(prev => {
        const next = { ...prev };
        delete next[fileName];
        return next;
      });
      return;
    }
    AsyncStorage.setItem(`time_${fileName}`, sec.toString()).catch(() => {});
    setSavedTimesMap(prev => ({ ...prev, [fileName]: sec }));
    if (dur > 0) {
      AsyncStorage.setItem(`dur_${fileName}`, dur.toString()).catch(() => {});
      setSavedDurationsMap(prev => ({ ...prev, [fileName]: dur }));
    }
  }, []);

  useEffect(() => {
    if (IS_TV) Orientation.lockToLandscape();
    AsyncStorage.getItem('gdstream_watchlist_v1').then(s => { if (s) setWatchlist(JSON.parse(s)); }).catch(() => {});
    if (VideoPlayerManager?.getAllWatchProgress) {
      VideoPlayerManager.getAllWatchProgress().then((res: any) => {
        if (res?.times) setSavedTimesMap(prev => ({ ...prev, ...res.times }));
        if (res?.durations) setSavedDurationsMap(prev => ({ ...prev, ...res.durations }));
      }).catch(() => {});
    }
    if (VideoPlayerManager?.getSystemLevels) {
      VideoPlayerManager.getSystemLevels().then((res: any) => {
        if (typeof res?.brightness === 'number') { setBrightnessLevel(res.brightness); brightnessRef.current = res.brightness; }
        if (typeof res?.volume === 'number') { setVolumeLevel(res.volume); volumeRef.current = res.volume; }
      }).catch(() => {});
    }
    if (DownloadEngine?.getDownloads) {
      DownloadEngine.getDownloads().then((list: DownloadItem[]) => { if (Array.isArray(list)) setDownloads(list); }).catch(() => {});
    }
    const dlSub = DeviceEventEmitter.addListener('onDownloadUpdate', (list: DownloadItem[]) => {
      if (Array.isArray(list)) setDownloads([...list]);
    });

    const initVaultAndFetch = async () => {
      try {
        if (SecurityVault?.getVaultConfig) {
          const cfg = await SecurityVault.getVaultConfig();
          if (cfg?.compromised) { setSecurityCompromised(true); setLoading(false); return; }
          vaultRef.current = {
            baseUrl: cfg?.baseUrl || '', username: cfg?.username || '',
            password: cfg?.password || '', tmdbKey: cfg?.tmdbKey || '',
          };
        }
        setVaultReady(true);
        fetchDirectory('/0:/', false);
      } catch {
        setLoading(false);
      }
    };
    initVaultAndFetch();
    return () => dlSub.remove();
  }, []);

  const toggleWatchlist = useCallback((fileName: string) => {
    setWatchlist(prev => {
      const next = { ...prev };
      if (next[fileName]) delete next[fileName];
      else next[fileName] = true;
      AsyncStorage.setItem('gdstream_watchlist_v1', JSON.stringify(next)).catch(() => {});
      return next;
    });
  }, []);

  useEffect(() => {
    if (playMode === 'internal') {
      Orientation.lockToLandscape();
      setIsFullscreen(true);
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

  const loadSavedTimesForFiles = useCallback(async (items: any[]) => {
    try {
      if (VideoPlayerManager?.getAllWatchProgress) {
        const nativeRes = await VideoPlayerManager.getAllWatchProgress();
        if (nativeRes?.times) {
          setSavedTimesMap(prev => ({ ...prev, ...nativeRes.times }));
        }
        if (nativeRes?.durations) {
          setSavedDurationsMap(prev => ({ ...prev, ...nativeRes.durations }));
        }
      }
      if (!items.length) return;
      const timeKeys = items.map(f => `time_${f.name}`);
      const durKeys = items.map(f => `dur_${f.name}`);
      const miKeys = items.map(f => `mediainfo_v8_${f.name}`);
      const [timePairs, durPairs, miPairs] = await Promise.all([
        AsyncStorage.multiGet(timeKeys),
        AsyncStorage.multiGet(durKeys),
        AsyncStorage.multiGet(miKeys),
      ]);
      const nextTimes: Record<string, number> = {};
      timePairs.forEach(([k, val]) => {
        if (val) {
          const parsed = parseFloat(val);
          if (parsed > 3) nextTimes[k.replace(/^time_/, '')] = parsed;
        }
      });
      if (Object.keys(nextTimes).length > 0) {
        setSavedTimesMap(prev => ({ ...nextTimes, ...prev }));
      }
      const nextDurs: Record<string, number> = {};
      durPairs.forEach(([k, val]) => {
        if (val) {
          const parsed = parseFloat(val);
          if (parsed > 0) nextDurs[k.replace(/^dur_/, '')] = parsed;
        }
      });
      if (Object.keys(nextDurs).length > 0) {
        setSavedDurationsMap(prev => ({ ...nextDurs, ...prev }));
      }
      const nextMiMap: Record<string, RealMediaInfo> = {};
      miPairs.forEach(([k, val]) => {
        if (val) {
          try { nextMiMap[k.replace(/^mediainfo_v8_/, '')] = JSON.parse(val); } catch {}
        }
      });
      if (Object.keys(nextMiMap).length > 0) {
        setMediaInfoMap(prev => ({ ...prev, ...nextMiMap }));
      }
    } catch {}
  }, []);

  const closeInternalPlayer = useCallback(() => {
    if (selectedFile?.name && currentTimeRef.current > 3) {
      persistPlaybackProgress(selectedFile.name, currentTimeRef.current, durationRef.current);
    }
    if (!IS_TV) {
      Orientation.lockToPortrait();
      setIsFullscreen(false);
    }
    setIsLocked(false);
    setActiveMenu(null);
    setPlayMode(null);
  }, [selectedFile, persistPlaybackProgress]);

  useEffect(() => {
    const backAction = () => {
      if (showSeasonModal) { setShowSeasonModal(false); return true; }
      if (showHdrChoiceModal) { setShowHdrChoiceModal(false); return true; }
      if (playMode === 'internal') {
        if (isLocked) { setShowControls(true); return true; }
        if (activeMenu) { setActiveMenu(null); return true; }
        closeInternalPlayer();
        return true;
      } else if (selectedFile) {
        setSelectedFile(null);
        setVersionGroup([]);
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
    const backHandler = BackHandler.addEventListener('hardwareBackPress', backAction);
    return () => backHandler.remove();
  }, [playMode, selectedFile, pathStack, activeMenu, showHdrChoiceModal, showSeasonModal, isLocked, activeTab, closeInternalPlayer]);

  const fetchSingleMetadata = useCallback(async (info: ParsedMediaInfo): Promise<MediaMetadata | null> => {
    const cacheKey = `meta_v7_${info.searchTitle.toLowerCase()}_${info.year}_${info.langCode}`;
    try {
      const cached = await AsyncStorage.getItem(cacheKey);
      if (cached) return JSON.parse(cached);
    } catch {}

    const targetLower = info.searchTitle.toLowerCase().trim();
    const infoYearNum = info.year ? parseInt(info.year, 10) : 0;
    const tmdbKey = vaultRef.current.tmdbKey;

    if (tmdbKey && tmdbKey.trim().length >= 20) {
      try {
        const key = tmdbKey.trim();
        const endpoint = info.seasonEpisode ? 'tv' : 'multi';
        // Step 1: Search without strict year filter first (so 2025 movies ripped in 2026 like Balti match naturally!)
        const multiUrl = `https://api.themoviedb.org/3/search/${endpoint}?api_key=${encodeURIComponent(key)}&query=${encodeURIComponent(info.searchTitle)}`;
        const res = await axios.get(multiUrl, { timeout: 6000 });
        let results: any[] = (res.data?.results || []).filter((r: any) => r.media_type !== 'person' && r.poster_path);

        // Check if any result matches the year (within ±1 year tolerance for OTT releases like Balti)
        let match = results.find((r: any) => {
          const rYear = parseInt(String(r.release_date || r.first_air_date || '').slice(0, 4), 10);
          if (!infoYearNum || !rYear) return false;
          const rTitle = String(r.title || r.name || r.original_title || '').toLowerCase().trim();
          return Math.abs(rYear - infoYearNum) <= 1 && (rTitle === targetLower || rTitle.includes(targetLower));
        });

        // Step 2: If movie has a specific older year (like Run 2002) not in top multi results, query with exact year!
        if (!match && infoYearNum > 0 && !info.seasonEpisode) {
          const yearUrl = `https://api.themoviedb.org/3/search/movie?api_key=${encodeURIComponent(key)}&query=${encodeURIComponent(info.searchTitle)}&primary_release_year=${info.year}`;
          const yearRes = await axios.get(yearUrl, { timeout: 6000 });
          const yearList: any[] = (yearRes.data?.results || []).filter((r: any) => r.poster_path);
          if (yearList.length > 0) {
            match =
              (info.langCode ? yearList.find((r: any) => r.original_language === info.langCode) : null) ||
              yearList.find((r: any) => String(r.title || r.original_title || '').toLowerCase().trim() === targetLower) ||
              yearList[0];
          }
        }

        // Step 3: Fallback to best title/language match from multi results (e.g. Vikram where year isn't in filename)
        if (!match && results.length > 0) {
          match =
            (info.langCode ? results.find((r: any) => r.original_language === info.langCode && String(r.title || r.name || '').toLowerCase().trim() === targetLower) : null) ||
            results.find((r: any) => String(r.title || r.name || '').toLowerCase().trim() === targetLower) ||
            results[0];
        }

        if (match) {
          const meta: MediaMetadata = {
            poster: match.poster_path ? `https://image.tmdb.org/t/p/w500${match.poster_path}` : undefined,
            backdrop: match.backdrop_path ? `https://image.tmdb.org/t/p/w780${match.backdrop_path}` : undefined,
            rating: match.vote_average && match.vote_average > 0 ? Number(match.vote_average).toFixed(1) : undefined,
            overview: match.overview || undefined,
            year: (match.release_date || match.first_air_date || '').slice(0, 4) || info.year,
          };
          if (meta.poster || meta.overview) {
            await AsyncStorage.setItem(cacheKey, JSON.stringify(meta));
            return meta;
          }
        }
      } catch {}
    }

    // IMDb Fallback (Strictly 'tt' movie/series IDs only, with ±1 year tolerance)
    try {
      const query = `${info.searchTitle} ${info.year}`.trim();
      const cleanQ = query.toLowerCase().replace(/[^a-z0-9\s]/g, '').trim();
      const firstChar = cleanQ.charAt(0) || 'a';
      const imdbUrl = `https://v3.sg.media-imdb.com/suggestion/${firstChar}/${encodeURIComponent(cleanQ.slice(0, 35))}.json`;
      const res = await axios.get(imdbUrl, { timeout: 6000 });
      const list: any[] = res.data?.d || [];
      const validItems = list.filter((item: any) => item?.id && String(item.id).startsWith('tt') && item?.i?.imageUrl);
      const match =
        validItems.find((item: any) => infoYearNum > 0 && item.y && Math.abs(Number(item.y) - infoYearNum) <= 1) ||
        validItems[0];

      if (match && match.i?.imageUrl) {
        const rawImg: string = match.i.imageUrl;
        const meta: MediaMetadata = {
          poster: rawImg.replace(/\._V1_.*\.jpg$/i, '._V1_QL75_UX500_.jpg'),
          backdrop: rawImg.replace(/\._V1_.*\.jpg$/i, '._V1_QL75_UX1000_.jpg'),
          year: match.y ? String(match.y) : info.year,
          cast: match.s || undefined,
          overview: match.s ? `${match.q ? `${match.q.toUpperCase()} • ` : ''}Starring: ${match.s}` : undefined,
        };
        await AsyncStorage.setItem(cacheKey, JSON.stringify(meta));
        return meta;
      }
    } catch {}
    return null;
  }, []);

  useEffect(() => {
    if (!files.length || !vaultReady) return;
    let cancelled = false;
    const enrichAll = async () => {
      loadSavedTimesForFiles(files);
      for (const item of files) {
        if (cancelled) break;
        const itemKey = item.name;
        if (fetchedKeysRef.current.has(itemKey)) continue;
        fetchedKeysRef.current.add(itemKey);
        const isFolder = !!item.mimeType?.includes('folder');
        const info = parseMediaFilename(item.name, isFolder);
        const meta = await fetchSingleMetadata(info);
        if (meta && !cancelled) setMetadataMap(prev => ({ ...prev, [itemKey]: meta }));
      }
    };
    enrichAll();
    return () => { cancelled = true; };
  }, [files, vaultReady, fetchSingleMetadata, loadSavedTimesForFiles]);

  // Filtered + Sorted + Multi-Version Grouped Catalog
  const displayedFiles = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    const filtered = files.filter((file) => {
      const isFolder = !!file.mimeType?.includes('folder');
      const info = parseMediaFilename(file.name, isFolder);
      const realMi = mediaInfoMap[file.name];

      if (q) {
        // Match ONLY Movie Title, Year, or Episode Tag (Never match raw filename codecs like HEVC)
        const titleLower = info.cleanTitle.toLowerCase();
        const titleWords = titleLower.split(/\s+/);
        const queryWords = q.split(/\s+/).filter(Boolean);
        const matchesSearch =
          titleLower.includes(q) ||
          queryWords.every(qw => titleWords.some(tw => tw.startsWith(qw))) ||
          info.year.toLowerCase().startsWith(q) ||
          info.seasonEpisode.toLowerCase().includes(q);
        if (!matchesSearch) return false;
      }
      switch (activeCategory) {
        case 'movies': return !isFolder && !info.seasonEpisode;
        case 'series': return isFolder || !!info.seasonEpisode;
        case '4k': return (realMi?.resolution || info.resolution) === '4K';
        case 'dv': return realMi ? realMi.hasDV : info.hasDV;
        case 'watchlist': return !!watchlist[file.name];
        default: return true;
      }
    });

    const indexed = filtered.map((item, originalIndex) => ({
      item,
      originalIndex,
      info: parseMediaFilename(item.name, !!item.mimeType?.includes('folder')),
      modMs: item.modifiedTime ? Date.parse(item.modifiedTime) || 0 : 0,
    }));

    const isEpisodeFolder = seasonFolders.length > 0 || (indexed.length > 1 && indexed.every(x => x.info.episodeNum > 0));
    if (isEpisodeFolder && sortBy === 'newest') {
      indexed.sort((a, b) => {
        if (a.info.episodeNum !== b.info.episodeNum) return a.info.episodeNum - b.info.episodeNum;
        return a.item.name.localeCompare(b.item.name);
      });
      return indexed.map(x => x.item);
    }

    if (sortBy === 'az') {
      indexed.sort((a, b) => a.info.cleanTitle.localeCompare(b.info.cleanTitle));
    } else {
      indexed.sort((a, b) => {
        if (a.modMs > 0 && b.modMs > 0 && a.modMs !== b.modMs) {
          return b.modMs - a.modMs;
        }
        return b.originalIndex - a.originalIndex;
      });
    }

    // Group multiple versions of the same movie (e.g. Evil Dead Burn WEB-DL + BluRay) into 1 Card
    const groupedMap = new Map<string, any[]>();
    const mergedOrder: any[] = [];
    for (const entry of indexed) {
      const { item, info } = entry;
      if (info.isFolder || info.seasonEpisode || info.episodeNum > 0) {
        mergedOrder.push(item);
      } else {
        const key = `${info.cleanTitle.toLowerCase()}|${info.year}`;
        if (!groupedMap.has(key)) {
          groupedMap.set(key, [item]);
          mergedOrder.push(item);
        } else {
          groupedMap.get(key)!.push(item);
        }
      }
    }

    return mergedOrder.map(item => {
      const isFolder = !!item.mimeType?.includes('folder');
      const info = parseMediaFilename(item.name, isFolder);
      if (info.isFolder || info.seasonEpisode || info.episodeNum > 0) return item;
      const key = `${info.cleanTitle.toLowerCase()}|${info.year}`;
      const vers = groupedMap.get(key) || [item];
      return vers.length > 1 ? { ...item, _versions: vers } : item;
    });
  }, [files, searchQuery, activeCategory, sortBy, seasonFolders, mediaInfoMap, watchlist]);

  const continueWatchingItems = useMemo(() => {
    return files.filter(f => !f.mimeType?.includes('folder') && (savedTimesMap[f.name] || 0) > 5);
  }, [files, savedTimesMap]);

  const playableFolderEpisodes = useMemo(() => {
    if (!selectedFile) return [];
    const currentInfo = parseMediaFilename(selectedFile.name, false);
    if (!currentInfo.seasonEpisode && seasonFolders.length === 0) return [];
    return displayedFiles.filter(f => {
      if (f.mimeType?.includes('folder')) return false;
      const fInfo = parseMediaFilename(f.name, false);
      return !!fInfo.seasonEpisode || seasonFolders.length > 0;
    });
  }, [displayedFiles, selectedFile, seasonFolders]);

  const getStreamUrl = useCallback((file: any) => {
    if (!file) return '';
    if (file.localPath) return file.localPath.startsWith('file://') ? file.localPath : `file://${file.localPath}`;
    const completedDl = downloads.find(d => d.fileName === file.name && d.status === 'completed' && d.filePath);
    if (completedDl) return `file://${completedDl.filePath}`;
    const baseUrl = vaultRef.current.baseUrl;
    if (file.link) return file.link.startsWith('http') ? file.link : `${baseUrl}${file.link.startsWith('/') ? '' : '/'}${file.link}`;
    return `${baseUrl}${currentPath}${encodeURIComponent(file.name)}`;
  }, [downloads, currentPath]);

  // Automatically scan MediaInfo for ALL versions in versionGroup when Detail Page opens
  useEffect(() => {
    if (!selectedFile || playMode !== null) return;
    const targets = versionGroup.length > 1 ? versionGroup : [selectedFile];
    let cancelled = false;

    const runMultiMediaInfo = async () => {
      for (const fileObj of targets) {
        if (cancelled || !fileObj?.name) break;
        const fName = fileObj.name;
        if (mediaInfoMap[fName]) {
          if (mediaInfoMap[fName].durationSec > 0) {
            setSavedDurationsMap(prev => ({ ...prev, [fName]: mediaInfoMap[fName].durationSec }));
          }
          continue;
        }
        const cacheKey = `mediainfo_v8_${fName}`;
        try {
          const cached = await AsyncStorage.getItem(cacheKey);
          if (cached) {
            const parsed: RealMediaInfo = JSON.parse(cached);
            if (!cancelled) {
              setMediaInfoMap(prev => ({ ...prev, [fName]: parsed }));
              if (parsed.durationSec > 0) {
                setSavedDurationsMap(prev => ({ ...prev, [fName]: parsed.durationSec }));
              }
            }
            continue;
          }
        } catch {}

        if (!MediaInfoModule?.inspectMedia) continue;
        if (fName === selectedFile.name) setScanningMediaInfo(true);
        try {
          const streamUrl = getStreamUrl(fileObj);
          const res: RealMediaInfo = await MediaInfoModule.inspectMedia(streamUrl, fName);
          if (res && !cancelled) {
            setMediaInfoMap(prev => ({ ...prev, [fName]: res }));
            if (res.durationSec > 0) {
              setSavedDurationsMap(prev => ({ ...prev, [fName]: res.durationSec }));
              AsyncStorage.setItem(`dur_${fName}`, res.durationSec.toString()).catch(() => {});
            }
            if (res.totalBytes > 0 && res.audioTracks && res.audioTracks.length > 0) { await AsyncStorage.setItem(cacheKey, JSON.stringify(res)); }
          }
        } catch {} finally {
          if (!cancelled && fName === selectedFile.name) setScanningMediaInfo(false);
        }
      }
    };
    runMultiMediaInfo();
    return () => { cancelled = true; };
  }, [selectedFile, versionGroup, playMode, mediaInfoMap, getStreamUrl]);

  const seekBothPlayers = useCallback((targetTime: number) => {
    videoRef.current?.seek(targetTime);
    setCurrentTime(targetTime);
    currentTimeRef.current = targetTime;
    if (selectedFile?.name && targetTime > 3) {
      persistPlaybackProgress(selectedFile.name, targetTime, durationRef.current);
    }
    if (isExternalAudioSelected && externalAudioRef.current) {
      const audioTarget = Math.max(0, targetTime - audioDelay);
      externalAudioRef.current.seek(audioTarget);
      externalAudioTimeRef.current = audioTarget;
      lastAudioSyncMs.current = Date.now();
    }
  }, [isExternalAudioSelected, audioDelay, selectedFile, persistPlaybackProgress]);

  const triggerStepSeek = useCallback((isForward: boolean) => {
    clearTimeout(multiTapTimer.current);
    if (seekBaseTime.current === null) {
      seekBaseTime.current = currentTimeRef.current;
      accumulatedSeek.current = 0;
    }
    accumulatedSeek.current += 10;
    const totalSeek = accumulatedSeek.current;

    setSeekOverlay({ visible: true, icon: 'replay-10', time: totalSeek, position: isForward ? 'right' : 'left' });
    fadeAnim.setValue(1);
    Animated.timing(fadeAnim, { toValue: 0, duration: 800, useNativeDriver: true }).start();

    multiTapTimer.current = setTimeout(() => {
      const base = seekBaseTime.current ?? currentTimeRef.current;
      const rawTarget = isForward ? base + totalSeek : base - totalSeek;
      const dur = durationRef.current;
      const clampedTarget = dur > 0 ? Math.max(0, Math.min(dur, rawTarget)) : Math.max(0, rawTarget);
      seekBothPlayers(clampedTarget);
      seekBaseTime.current = null;
      accumulatedSeek.current = 0;
      lastTapDirection.current = null;
    }, 300);
  }, [fadeAnim, seekBothPlayers]);

  useEffect(() => {
    if (playMode !== 'internal') return;
    const sub = DeviceEventEmitter.addListener('onTVRemoteKey', event => {
      const keyCode = event?.keyCode;
      if (keyCode === 85 || keyCode === 126 || keyCode === 127) { setIsPaused(p => !p); return; }
      if (keyCode === 89) { triggerStepSeek(false); return; }
      if (keyCode === 90) { triggerStepSeek(true); return; }
      if (activeMenu) return;
      if (!showControls) {
        if (keyCode === 21) triggerStepSeek(false);
        else if (keyCode === 22) triggerStepSeek(true);
        else if (keyCode === 23 || keyCode === 66 || keyCode === 19 || keyCode === 20) setShowControls(true);
      }
    });
    return () => sub.remove();
  }, [playMode, showControls, activeMenu, triggerStepSeek]);

  const ensureLoggedIn = async (force = false) => {
    if (isLoggedIn.current && !force) return;
    const { baseUrl, username, password } = vaultRef.current;
    if (!baseUrl) return;
    const loginData = `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`;
    await axios.post(`${baseUrl}/login`, loginData, { headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
    isLoggedIn.current = true;
  };

  const requestRawDirectory = async (path: string) => {
    const { baseUrl } = vaultRef.current;
    await ensureLoggedIn(false);
    let response = await axios.post(`${baseUrl}${path}`, { page_index: 0 });
    if (!response.data?.data?.files) {
      await ensureLoggedIn(true);
      response = await axios.post(`${baseUrl}${path}`, { page_index: 0 });
    }
    return response.data?.data?.files || [];
  };

  const fetchDirectory = async (path: string, isPullRefresh = false, isSeasonSwitch = false) => {
    const { baseUrl } = vaultRef.current;
    if (!baseUrl) { setLoading(false); return; }
    if (isPullRefresh) setRefreshing(true);
    else setLoading(true);

    try {
      const incomingFiles = await requestRawDirectory(path);

      if (!isSeasonSwitch) {
        const subFolders = incomingFiles.filter((f: any) => f.mimeType?.includes('folder'));
        const videoFiles = incomingFiles.filter((f: any) => !f.mimeType?.includes('folder'));
        const detectedSeasons: SeasonFolderItem[] = [];

        for (const sf of subFolders) {
          const sNum = extractSeasonNumberFromFolder(sf.name);
          if (sNum !== null) {
            detectedSeasons.push({
              seasonNum: sNum,
              label: `Season ${sNum}`,
              folderName: sf.name,
              path: `${path}${encodeURIComponent(sf.name)}/`,
            });
          }
        }

        if (path !== '/0:/' && detectedSeasons.length > 0 && videoFiles.length === 0) {
          detectedSeasons.sort((a, b) => a.seasonNum - b.seasonNum);
          const targetSeason = (isPullRefresh && activeSeason)
            ? (detectedSeasons.find(s => s.seasonNum === activeSeason.seasonNum) || detectedSeasons[0])
            : detectedSeasons[0];

          const rawFolderTitle = decodeURIComponent(path.split('/').filter(Boolean).pop() || '');
          const cleanSeriesName = parseMediaFilename(rawFolderTitle, true).cleanTitle;

          setSeriesParentTitle(cleanSeriesName);
          setSeasonFolders(detectedSeasons);
          setActiveSeason(targetSeason);

          const seasonEpisodes = await requestRawDirectory(targetSeason.path);
          setFiles(seasonEpisodes);
          setCurrentPath(targetSeason.path);
          if (!isPullRefresh) setSearchQuery('');
          return;
        } else if (path === '/0:/') {
          setSeasonFolders([]);
          setActiveSeason(null);
          setSeriesParentTitle('');
        }
      }

      setFiles(incomingFiles);
      setCurrentPath(path);
      if (!isPullRefresh) setSearchQuery('');
    } catch (error) {
      Alert.alert('Error', 'Folder load aagala!');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  const handleSelectSeason = async (seasonItem: SeasonFolderItem) => {
    setShowSeasonModal(false);
    setActiveSeason(seasonItem);
    await fetchDirectory(seasonItem.path, false, true);
  };

  const handleRefresh = useCallback(() => {
    if (seasonFolders.length > 0 && activeSeason) {
      fetchDirectory(activeSeason.path, true, true);
    } else {
      fetchDirectory(currentPath, true, false);
    }
  }, [currentPath, seasonFolders, activeSeason]);

  const getRemoteDownloadUrl = (file: any) => {
    if (!file) return '';
    const baseUrl = vaultRef.current.baseUrl;
    if (file.link) return file.link.startsWith('http') ? file.link : `${baseUrl}${file.link.startsWith('/') ? '' : '/'}${file.link}`;
    return `${baseUrl}${currentPath}${encodeURIComponent(file.name)}`;
  };

  const openFile = (file: any) => {
    switchingEpisode.current = false;
    lastSavedSec.current = -1;
    hasRestoredResumeRef.current = false;
    setSelectedAudio(undefined);
    setSelectedText({ type: 'disabled' });
    setCurrentTime(0);
    setDuration(0);
    currentTimeRef.current = 0;
    durationRef.current = 0;
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
    try { await VideoPlayerManager?.setPreferredHdrMode?.(mode); } catch {}
    setPlayerInstanceKey(k => k + 1);
    setPlayMode('internal');
  };

  const handlePlayInAppPress = (fileToPlay = selectedFile) => {
    if (!fileToPlay) return;
    const info = parseMediaFilename(fileToPlay.name, false);
    const realMi = mediaInfoMap[fileToPlay.name];
    const effectiveHasDV = realMi ? realMi.hasDV : info.hasDV;
    const effectiveHasHDR = realMi ? realMi.hasHDR : info.hasHDR;

    if (effectiveHasDV && effectiveHasHDR) setShowHdrChoiceModal(true);
    else if (effectiveHasDV) startPlaybackWithMode('dv');
    else if (effectiveHasHDR) startPlaybackWithMode('hdr');
    else startPlaybackWithMode('sdr');
  };

  const toggleLiveHdrModeInPlayer = async () => {
    const nextMode = activeHdrMode === 'dv' ? 'hdr' : 'dv';
    if (currentTimeRef.current > 3 && selectedFile?.name) {
      persistPlaybackProgress(selectedFile.name, currentTimeRef.current, durationRef.current);
    }
    setActiveHdrMode(nextMode);
    try { await VideoPlayerManager?.setPreferredHdrMode?.(nextMode); } catch {}
    setPlayerInstanceKey(k => k + 1);
  };

  const handleEnterPiP = async () => {
    try {
      setActiveMenu(null);
      setShowControls(false);
      await VideoPlayerManager?.enterPiP?.();
    } catch {}
  };

  const handleStartDownload = async (file: any) => {
    if (!file) return;
    const info = parseMediaFilename(file.name, false);
    const meta = metadataMap[file.name];
    const url = getRemoteDownloadUrl(file);
    try {
      await DownloadEngine?.startDownload(url, file.name, info.cleanTitle, meta?.poster || '');
    } catch {
      Alert.alert('Error', 'Could not start download!');
    }
  };

  const handleOpenDownloadFolder = async () => {
    try { await DownloadEngine?.openDownloadFolder?.(); } catch {
      Alert.alert('Folder Path', 'Internal Storage / Download / GDStream');
    }
  };

  const handlePress = (item: any) => {
    if (item.mimeType?.includes('folder')) {
      const newPath = `${currentPath}${encodeURIComponent(item.name)}/`;
      setPathStack([...pathStack, newPath]);
      fetchDirectory(newPath, false, false);
    } else {
      const vers = item._versions && item._versions.length > 1 ? item._versions : [item];
      setVersionGroup(vers);
      const resumedVer = vers.find((v: any) => (savedTimesMap[v.name] || 0) > 3) || vers[0];
      openFile(resumedVer);
    }
  };

  const goBack = () => {
    if (pathStack.length > 1) {
      const newStack = pathStack.slice(0, -1);
      setPathStack(newStack);
      setSeasonFolders([]);
      setActiveSeason(null);
      setSeriesParentTitle('');
      fetchDirectory(newStack[newStack.length - 1], false, false);
    }
  };

  const openInExternalPlayer = async (targetFile = selectedFile) => {
    const url = getStreamUrl(targetFile);
    try { await VideoPlayerManager.playVideo(url); } catch {
      Alert.alert('Error', 'Player illai!');
    }
  };

  const handleZoomToggle = () => setResizeMode(prev => (prev === 'contain' ? 'cover' : 'contain'));

  const cyclePlaybackSpeed = () => {
    const speeds = [0.75, 1.0, 1.25, 1.5, 2.0];
    setPlaybackRate(speeds[(speeds.indexOf(playbackRate) + 1) % speeds.length]);
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
      if (!cues.length) { Alert.alert('Error', 'Could not read any subtitles from that file!'); return; }
      setExternalSubtitle({ name: res.name, content: res.content });
      setSelectedText({ type: 'external', value: res.name });
      setActiveMenu(null);
    } catch {
      Alert.alert('Error', 'Could not load subtitle file!');
    }
  };

  const handlePickAudioFile = async () => {
    try {
      const res = await SubtitleFileModule.pickAudioFile();
      if (!res || !res.uri) return;
      setExternalAudio({ name: res.name || 'External Audio', uri: res.uri });
      setSelectedAudio({ type: 'external', value: res.name || 'External Audio' });
      setActiveMenu(null);
    } catch {
      Alert.alert('Error', 'Could not load audio file!');
    }
  };

  const handleZoneTap = useCallback((isForward: boolean) => {
    if (isLocked) { setShowControls(prev => !prev); return; }
    const now = Date.now();
    const DOUBLE_PRESS_DELAY = 280;
    if (now - lastTapTime.current < DOUBLE_PRESS_DELAY && (lastTapDirection.current === null || lastTapDirection.current === isForward)) {
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
  }, [isLocked, triggerStepSeek]);

  const gestureStartRef = useRef({ side: 'left' as 'left' | 'right', startVal: 0.5, isSwiping: false });
  const handleZoneTapRef = useRef(handleZoneTap);
  useEffect(() => { handleZoneTapRef.current = handleZoneTap; }, [handleZoneTap]);
  const isLockedRef = useRef(isLocked);
  useEffect(() => { isLockedRef.current = isLocked; }, [isLocked]);

  const playerPanResponder = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder: () => true,
    onPanResponderGrant: evt => {
      const screenW = Dimensions.get('window').width || 800;
      const side = evt.nativeEvent.pageX < screenW / 2 ? 'left' : 'right';
      gestureStartRef.current = {
        side,
        startVal: side === 'left' ? brightnessRef.current : volumeRef.current,
        isSwiping: false,
      };
    },
    onPanResponderMove: (_, gestureState) => {
      if (isLockedRef.current) return;
      const { dy, dx } = gestureState;
      if (!gestureStartRef.current.isSwiping && Math.abs(dy) > 12 && Math.abs(dy) > Math.abs(dx)) {
        gestureStartRef.current.isSwiping = true;
        clearTimeout(singleTapTimer.current);
      }
      if (gestureStartRef.current.isSwiping) {
        const screenH = Dimensions.get('window').height || 400;
        const nextVal = Math.max(0, Math.min(1, gestureStartRef.current.startVal + (-dy / (screenH * 0.65))));
        clearTimeout(gestureHideTimer.current);
        if (gestureStartRef.current.side === 'left') {
          brightnessRef.current = nextVal;
          setBrightnessLevel(nextVal);
          VideoPlayerManager?.setSystemBrightness?.(nextVal);
          setGestureIndicator({ visible: true, type: 'brightness', value: nextVal });
        } else {
          // Allow volume swipe up to 2.0 (200% Real LoudnessEnhancer Sound Boost)
          const boostVal = Math.max(0, Math.min(2.0, gestureStartRef.current.startVal + (-dy / (screenH * 0.42))));
          volumeRef.current = boostVal;
          setVolumeLevel(boostVal);
          VideoPlayerManager?.setSystemVolume?.(boostVal);
          setGestureIndicator({ visible: true, type: 'volume', value: boostVal });
        }
      }
    },
    onPanResponderRelease: evt => {
      if (gestureStartRef.current.isSwiping) {
        gestureHideTimer.current = setTimeout(() => setGestureIndicator(prev => ({ ...prev, visible: false })), 900);
      } else {
        const screenW = Dimensions.get('window').width || 800;
        handleZoneTapRef.current(evt.nativeEvent.pageX >= screenW / 2);
      }
      gestureStartRef.current.isSwiping = false;
    },
  }), []);

  const handleVideoLoad = async (meta: any) => {
    setDuration(meta.duration);
    durationRef.current = meta.duration;
    if (selectedFile?.name && meta.duration > 0) {
      AsyncStorage.setItem(`dur_${selectedFile.name}`, meta.duration.toString()).catch(() => {});
      setSavedDurationsMap(prev => ({ ...prev, [selectedFile.name]: meta.duration }));
    }
    if (meta.audioTracks) {
      setAudioTracks(meta.audioTracks);
      if (!selectedAudio && meta.audioTracks.length > 0) {
        const selectedIdx = meta.audioTracks.findIndex((t: any) => t.selected);
        setSelectedAudio({ type: 'index', value: String(selectedIdx >= 0 ? selectedIdx : 0) });
      }
    }
    if (meta.textTracks) setTextTracks(meta.textTracks);
    try {
      let savedTime = savedTimesMap[selectedFile.name] || 0;
      if (savedTime <= 3) {
        const savedTimeStr = await AsyncStorage.getItem(`time_${selectedFile.name}`);
        if (savedTimeStr) savedTime = parseFloat(savedTimeStr);
      }
      if (savedTime > 3 && (meta.duration <= 0 || meta.duration - savedTime > 90)) {
        videoRef.current?.seek(savedTime);
        setCurrentTime(savedTime);
        currentTimeRef.current = savedTime;
      }
    } catch {} finally {
      setTimeout(() => {
        hasRestoredResumeRef.current = true;
      }, 600);
    }
  };

  const nextEpisodeFile = useMemo(() => {
    if (!selectedFile) return null;
    const currentIndex = playableFolderEpisodes.findIndex(f => f.name === selectedFile.name);
    if (currentIndex !== -1 && currentIndex + 1 < playableFolderEpisodes.length) {
      return playableFolderEpisodes[currentIndex + 1];
    }
    return null;
  }, [selectedFile, playableFolderEpisodes]);

  const playNextEpisode = () => {
    if (!nextEpisodeFile) return;
    if (selectedFile?.name && currentTimeRef.current > 3) {
      persistPlaybackProgress(selectedFile.name, currentTimeRef.current, durationRef.current);
    }
    openFile(nextEpisodeFile);
    setPlayMode('internal');
  };

  const handleVideoEnd = async () => {
    if (switchingEpisode.current) return;
    switchingEpisode.current = true;
    if (selectedFile?.name) {
      AsyncStorage.removeItem(`time_${selectedFile.name}`).catch(() => {});
      setSavedTimesMap(prev => {
        const next = { ...prev };
        delete next[selectedFile.name];
        return next;
      });
    }
    if (nextEpisodeFile) {
      openFile(nextEpisodeFile);
      setPlayMode('internal');
      return;
    }
    closeInternalPlayer();
    setSelectedFile(null);
    setVersionGroup([]);
  };

  if (securityCompromised) {
    return (
      <View style={styles.securityAlertScreen}>
        <Icon name="security" size={58} color="#E50914" />
        <Text style={styles.securityAlertTitle}>Security Shield Active</Text>
        <Text style={styles.securityAlertSub}>
          Debugger or hooking environment detected. Please close any debugging or packet inspection tools and restart GDStream.
        </Text>
      </View>
    );
  }

  const renderSeasonModal = () => {
    if (!showSeasonModal || seasonFolders.length === 0) return null;
    return (
      <Modal
        visible={showSeasonModal}
        transparent={true}
        animationType="fade"
        onRequestClose={() => setShowSeasonModal(false)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.hdrModalCard}>
            <Text style={styles.hdrModalEyebrow}>{seriesParentTitle.toUpperCase() || 'SERIES'}</Text>
            <Text style={styles.hdrModalTitle}>Select Season</Text>
            <ScrollView style={{ maxHeight: 300, marginTop: 12 }}>
              {seasonFolders.map((sItem) => {
                const isSelected = activeSeason?.seasonNum === sItem.seasonNum;
                return (
                  <FocusableButton
                    key={sItem.seasonNum}
                    hasTVPreferredFocus={isSelected}
                    style={[styles.seasonOptionItem, isSelected && styles.seasonOptionItemActive]}
                    onPress={() => handleSelectSeason(sItem)}>
                    <View style={styles.seasonNumberCircle}>
                      <Text style={styles.seasonNumberText}>S{sItem.seasonNum < 10 ? `0${sItem.seasonNum}` : sItem.seasonNum}</Text>
                    </View>
                    <View style={{ flex: 1 }}>
                      <Text style={[styles.hdrOptionTitle, isSelected && { color: '#E50914' }]}>{sItem.label}</Text>
                      <Text style={styles.hdrOptionDesc} numberOfLines={1}>{sItem.folderName}</Text>
                    </View>
                    <Icon name={isSelected ? 'check-circle' : 'chevron-right'} size={22} color={isSelected ? '#E50914' : '#888'} />
                  </FocusableButton>
                );
              })}
            </ScrollView>
            <TouchableOpacity style={styles.hdrCancelBtn} onPress={() => setShowSeasonModal(false)}>
              <Text style={{ color: '#99A3B3', fontWeight: '700' }}>Close</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    );
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
              This title contains both Dolby Vision and HDR10 streams. Choose which hardware engine you want to play with:
            </Text>
            <FocusableButton hasTVPreferredFocus={true} style={styles.dvOptionBtn} onPress={() => startPlaybackWithMode('dv')}>
              <View style={styles.dvOptionBadge}><Text style={styles.dvOptionBadgeText}>DOLBY VISION</Text></View>
              <View style={{ flex: 1 }}>
                <Text style={styles.hdrOptionTitle}>Play in Dolby Vision (DV)</Text>
                <Text style={styles.hdrOptionDesc}>Dynamic scene-by-scene metadata via hardware DV decoder</Text>
              </View>
              <Icon name="play-circle-fill" size={28} color="#E50914" />
            </FocusableButton>
            <FocusableButton style={styles.hdr10OptionBtn} onPress={() => startPlaybackWithMode('hdr')}>
              <View style={styles.hdr10OptionBadge}><Text style={styles.hdr10OptionBadgeText}>HDR10</Text></View>
              <View style={{ flex: 1 }}>
                <Text style={styles.hdrOptionTitle}>Play in HDR10</Text>
                <Text style={styles.hdrOptionDesc}>10-bit HEVC High Dynamic Range base layer</Text>
              </View>
              <Icon name="play-circle-outline" size={28} color="#F5C518" />
            </FocusableButton>
            <TouchableOpacity style={styles.hdrCancelBtn} onPress={() => setShowHdrChoiceModal(false)}>
              <Text style={{ color: '#99A3B3', fontWeight: '700' }}>Cancel</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    );
  };

  const renderFloatingMenu = () => {
    if (!activeMenu) return null;
    if (activeMenu === 'episodes') {
      return (
        <View style={styles.netflixSheetOverlay}>
          <View style={[styles.floatingMenu, { width: 400 }]}>
            <View style={styles.floatingMenuHeader}>
              <Text style={styles.floatingMenuTitle}>Episodes ({playableFolderEpisodes.length})</Text>
              <FocusableButton style={styles.menuCloseBtn} onPress={() => setActiveMenu(null)}>
                <Icon name="close" size={20} color="white" />
              </FocusableButton>
            </View>
            <ScrollView style={{ maxHeight: 270 }}>
              {playableFolderEpisodes.map((ep, idx) => {
                const epInfo = parseMediaFilename(ep.name, false);
                const isCurrent = ep.name === selectedFile?.name;
                return (
                  <FocusableButton
                    key={ep.id || ep.name || idx}
                    hasTVPreferredFocus={isCurrent}
                    style={[styles.menuItem, isCurrent && { backgroundColor: 'rgba(229,9,20,0.18)' }]}
                    onPress={() => {
                      setActiveMenu(null);
                      if (!isCurrent) {
                        if (selectedFile?.name && currentTimeRef.current > 3) {
                          persistPlaybackProgress(selectedFile.name, currentTimeRef.current, durationRef.current);
                        }
                        openFile(ep);
                        setPlayMode('internal');
                      }
                    }}>
                    <Icon name={isCurrent ? 'play-circle-fill' : 'play-circle-outline'} size={22} color={isCurrent ? '#E50914' : '#AAA'} />
                    <View style={{ flex: 1, marginLeft: 10 }}>
                      <Text style={[styles.menuItemText, { marginLeft: 0 }, isCurrent && { color: '#E50914', fontWeight: '800' }]} numberOfLines={1}>
                        {epInfo.seasonEpisode ? `${epInfo.seasonEpisode} • ${epInfo.cleanTitle}` : ep.name}
                      </Text>
                      <Text style={{ color: '#7D8799', fontSize: 11 }} numberOfLines={1}>{ep.name}</Text>
                    </View>
                  </FocusableButton>
                );
              })}
            </ScrollView>
          </View>
        </View>
      );
    }

    const isAudio = activeMenu === 'audio';
    const tracks = isAudio ? audioTracks : textTracks;
    const isSubtitleDisabled = !selectedText || selectedText.type === 'disabled';

    return (
      <View style={styles.netflixSheetOverlay}>
        <View style={styles.floatingMenu}>
          <View style={styles.floatingMenuHeader}>
            <Text style={styles.floatingMenuTitle}>{isAudio ? 'Audio & Playback Speed' : 'Subtitles & Captions'}</Text>
            <FocusableButton style={styles.menuCloseBtn} onPress={() => setActiveMenu(null)}>
              <Icon name="close" size={20} color="white" />
            </FocusableButton>
          </View>
          <ScrollView style={{ maxHeight: 250 }}>
            {!isAudio && (
              <FocusableButton hasTVPreferredFocus={true} style={styles.menuItem} onPress={() => handleSelectTextTrack({ type: 'disabled' })}>
                <Icon name="check" size={20} color="#E50914" style={{ opacity: isSubtitleDisabled ? 1 : 0 }} />
                <Text style={styles.menuItemText}>Off (Disable Subtitles)</Text>
              </FocusableButton>
            )}
            {!isAudio && (
              <FocusableButton style={styles.menuItem} onPress={handlePickSubtitleFile}>
                <Icon name="folder-open" size={20} color={isExternalSubtitleSelected ? '#E50914' : 'white'} />
                <Text style={styles.menuItemText} numberOfLines={1}>
                  {externalSubtitle ? `File: ${externalSubtitle.name}` : 'Load External Subtitle (.srt / .vtt) 📂'}
                </Text>
              </FocusableButton>
            )}
            {isAudio && (
              <FocusableButton hasTVPreferredFocus={true} style={styles.menuItem} onPress={handlePickAudioFile}>
                <Icon name="folder-open" size={20} color={isExternalAudioSelected ? '#E50914' : 'white'} />
                <Text style={styles.menuItemText} numberOfLines={1}>
                  {externalAudio ? `File: ${externalAudio.name}` : 'Load External Audio File 📂'}
                </Text>
              </FocusableButton>
            )}
            {!isAudio && (
              <View style={styles.adjustRow}>
                <Text style={styles.adjustLabel}>Font Size</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setSubtitleSize(s => Math.max(10, s - 2))}><Icon name="remove" size={20} color="white" /></FocusableButton>
                <Text style={styles.adjustValue}>{subtitleSize}sp</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setSubtitleSize(s => Math.min(40, s + 2))}><Icon name="add" size={20} color="white" /></FocusableButton>
              </View>
            )}
            {!isAudio && (
              <View style={styles.adjustRow}>
                <Text style={styles.adjustLabel}>Sync Delay</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setSubtitleDelay(d => Math.round((d - 0.5) * 10) / 10)}><Icon name="remove" size={20} color="white" /></FocusableButton>
                <Text style={styles.adjustValue}>{subtitleDelay >= 0 ? `+${subtitleDelay.toFixed(1)}s` : `${subtitleDelay.toFixed(1)}s`}</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setSubtitleDelay(d => Math.round((d + 0.5) * 10) / 10)}><Icon name="add" size={20} color="white" /></FocusableButton>
              </View>
            )}
            {isAudio && (
              <View style={styles.adjustRow}>
                <Text style={styles.adjustLabel}>Audio Sync</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setAudioDelay(d => Math.round((d - 0.25) * 100) / 100)}><Icon name="remove" size={20} color="white" /></FocusableButton>
                <Text style={styles.adjustValue}>{audioDelay >= 0 ? `+${audioDelay.toFixed(2)}s` : `${audioDelay.toFixed(2)}s`}</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setAudioDelay(d => Math.round((d + 0.25) * 100) / 100)}><Icon name="add" size={20} color="white" /></FocusableButton>
              </View>
            )}
            {isAudio && (
              <View style={styles.adjustRow}>
                <Text style={styles.adjustLabel}>Sound Boost</Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() => {
                    const next = Math.max(0, Math.round((volumeLevel - 0.2) * 10) / 10);
                    volumeRef.current = next;
                    setVolumeLevel(next);
                    VideoPlayerManager?.setSystemVolume?.(next);
                  }}>
                  <Icon name="remove" size={20} color="white" />
                </FocusableButton>
                <Text style={[styles.adjustValue, volumeLevel > 1.0 && { color: '#F5C518' }]}>
                  {Math.round(volumeLevel * 100)}%
                </Text>
                <FocusableButton
                  style={styles.adjustBtn}
                  onPress={() => {
                    const next = Math.min(2.0, Math.round((volumeLevel + 0.2) * 10) / 10);
                    volumeRef.current = next;
                    setVolumeLevel(next);
                    VideoPlayerManager?.setSystemVolume?.(next);
                  }}>
                  <Icon name="add" size={20} color="white" />
                </FocusableButton>
              </View>
            )}
            {isAudio && (
              <View style={styles.adjustRow}>
                <Text style={styles.adjustLabel}>Speed</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setPlaybackRate(r => Math.max(0.5, Math.round((r - 0.25) * 100) / 100))}><Icon name="remove" size={20} color="white" /></FocusableButton>
                <Text style={styles.adjustValue}>{playbackRate.toFixed(2)}x</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setPlaybackRate(r => Math.min(2.0, Math.round((r + 0.25) * 100) / 100))}><Icon name="add" size={20} color="white" /></FocusableButton>
              </View>
            )}
            {tracks.map((track, index) => {
              const indexStr = String(index);
              const isSelected = isAudio
                ? selectedAudio?.type === 'index' && String(selectedAudio?.value) === indexStr
                : selectedText?.type === 'index' && String(selectedText?.value) === indexStr;
              return (
                <FocusableButton
                  key={index}
                  style={styles.menuItem}
                  onPress={() => {
                    if (isAudio) { setSelectedAudio({ type: 'index', value: indexStr }); setActiveMenu(null); }
                    else handleSelectTextTrack({ type: 'index', value: indexStr });
                  }}>
                  <Icon name="check" size={20} color="#E50914" style={{ opacity: isSelected ? 1 : 0 }} />
                  <Text style={styles.menuItemText} numberOfLines={1}>
                    {track.language ? track.language.toUpperCase() : `Track ${index + 1}`} • {track.title || 'Default'}
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
    const realMi = mediaInfoMap[selectedFile.name];
    const effectiveHasDV = realMi ? realMi.hasDV : playerInfo.hasDV;
    const effectiveHasHDR = realMi ? realMi.hasHDR : playerInfo.hasHDR;
    const canSwitchDvHdr = effectiveHasDV && effectiveHasHDR;

    return (
      <View style={styles.playerWrapper}>
        <StatusBar hidden={true} />
        <Video
          key={`${selectedFile.link || selectedFile.name}-${playerInstanceKey}-${activeHdrMode}`}
          ref={videoRef}
          source={{ uri: getStreamUrl(selectedFile) }}
          style={styles.videoPlayer}
          resizeMode={resizeMode}
          paused={isPaused}
          volume={Math.min(1.0, volumeLevel)}
          muted={isExternalAudioSelected}
          disableFocus={isExternalAudioSelected}
          rate={playbackRate}
          focusable={false}
          bufferConfig={{
            minBufferMs: 3000,
            maxBufferMs: 8000,
            bufferForPlaybackMs: 1000,
            bufferForPlaybackAfterRebufferMs: 2000,
            maxHeapAllocationPercent: 0.12,
          }}
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
            currentTimeRef.current = p.currentTime;
            if (p.seekableDuration > 0 && durationRef.current <= 0) {
              durationRef.current = p.seekableDuration;
              setDuration(p.seekableDuration);
            }
            const sec = Math.floor(p.currentTime);
            if (hasRestoredResumeRef.current && sec > 3 && sec % 3 === 0 && lastSavedSec.current !== sec) {
              lastSavedSec.current = sec;
              persistPlaybackProgress(selectedFile?.name, p.currentTime, durationRef.current);
            }
            if (isExternalAudioSelected && !isPaused && !isVideoBuffering && externalAudioRef.current) {
              const expectedAudioTime = Math.max(0, p.currentTime - audioDelay);
              const drift = Math.abs(expectedAudioTime - externalAudioTimeRef.current);
              const now = Date.now();
              if (drift > 0.45 && now - lastAudioSyncMs.current > 2000) {
                externalAudioRef.current.seek(expectedAudioTime);
                externalAudioTimeRef.current = expectedAudioTime;
                lastAudioSyncMs.current = now;
              }
            }
          }}
          onEnd={handleVideoEnd}
          selectedAudioTrack={isExternalAudioSelected ? { type: 'disabled' } : selectedAudio}
          subtitleStyle={{
            paddingBottom: resizeMode === 'cover' ? 70 : 20,
            fontSize: subtitleSize,
            subtitlesFollowVideo: resizeMode !== 'cover',
          }}
          selectedTextTrack={subtitlesActive ? selectedText : { type: 'disabled' }}
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
              const target = Math.max(0, currentTimeRef.current - audioDelay);
              externalAudioRef.current?.seek(target);
              externalAudioTimeRef.current = target;
              lastAudioSyncMs.current = Date.now();
            }}
            onProgress={(ap: any) => { externalAudioTimeRef.current = ap.currentTime; }}
            style={{ width: 0, height: 0 }}
          />
        )}

        {isExternalSubtitleSelected && !!activeExternalSubtitleText && (
          <View style={styles.externalSubtitleContainer} pointerEvents="none">
            <Text style={[styles.externalSubtitleText, { fontSize: subtitleSize }]}>{activeExternalSubtitleText}</Text>
          </View>
        )}

        {!IS_TV && <View style={StyleSheet.absoluteFill} {...playerPanResponder.panHandlers} />}

        {gestureIndicator.visible && (
          <View style={[styles.gestureHudContainer, gestureIndicator.type === 'brightness' ? styles.gestureHudLeft : styles.gestureHudRight]} pointerEvents="none">
            <View style={styles.gestureHudPill}>
              <Icon
                name={gestureIndicator.type === 'brightness' ? 'wb-sunny' : gestureIndicator.value === 0 ? 'volume-off' : 'volume-up'}
                size={22}
                color={gestureIndicator.type === 'volume' && gestureIndicator.value > 1.0 ? '#F5C518' : '#E50914'}
              />
              <View style={styles.gestureBarTrack}>
                <View
                  style={[
                    styles.gestureBarFill,
                    {
                      height: `${Math.min(100, Math.round((gestureIndicator.type === 'volume' ? gestureIndicator.value / 2.0 : gestureIndicator.value) * 100))}%`,
                      backgroundColor: gestureIndicator.type === 'volume' && gestureIndicator.value > 1.0 ? '#F5C518' : '#E50914',
                    },
                  ]}
                />
              </View>
              <Text style={[styles.gestureHudText, gestureIndicator.type === 'volume' && gestureIndicator.value > 1.0 && { color: '#F5C518' }]}>
                {Math.round(gestureIndicator.value * 100)}%
              </Text>
              {gestureIndicator.type === 'volume' && gestureIndicator.value > 1.0 && (
                <Text style={{ color: '#F5C518', fontSize: 9, fontWeight: '900' }}>BOOST</Text>
              )}
            </View>
          </View>
        )}

        <Animated.View style={[styles.seekOverlay, { opacity: fadeAnim, alignItems: seekOverlay.position === 'right' ? 'flex-end' : 'flex-start' }]} pointerEvents="none">
          <View style={styles.netflixSeekBubble}>
            <Icon name={seekOverlay.position === 'right' ? 'forward-10' : 'replay-10'} size={42} color="white" />
            <Text style={styles.seekText}>{seekOverlay.position === 'right' ? `+${seekOverlay.time}s` : `-${seekOverlay.time}s`}</Text>
          </View>
        </Animated.View>

        {showControls && isLocked && (
          <View style={styles.lockedOverlay} pointerEvents="box-none">
            <FocusableButton style={styles.unlockPillBtn} onPress={() => setIsLocked(false)}>
              <Icon name="lock" size={20} color="#E50914" />
              <Text style={styles.unlockPillText}>Tap to Unlock Screen</Text>
            </FocusableButton>
          </View>
        )}

        {showControls && !isLocked && (
          <View style={styles.controlsOverlay} pointerEvents="box-none">
            <View style={styles.netflixTopBar}>
              <FocusableButton style={styles.netflixCircleBtn} onPress={closeInternalPlayer}>
                <Icon name="arrow-back" size={24} color="white" />
              </FocusableButton>
              <View style={styles.netflixTitleCenter}>
                <Text style={styles.netflixMainTitle} numberOfLines={1}>{playerInfo.cleanTitle}</Text>
                <View style={styles.netflixSubMetaRow}>
                  {!!playerInfo.seasonEpisode && <Text style={styles.netflixEpisodeTag}>{playerInfo.seasonEpisode}</Text>}
                  {!!(realMi?.resolution || playerInfo.resolution) && (
                    <View style={styles.netflixMiniBadge}>
                      <Text style={styles.netflixMiniBadgeText}>{realMi?.resolution || playerInfo.resolution}</Text>
                    </View>
                  )}
                  {(effectiveHasDV || effectiveHasHDR) && (
                    <TouchableOpacity
                      onPress={canSwitchDvHdr ? toggleLiveHdrModeInPlayer : undefined}
                      style={[styles.liveHdrStatusPill, activeHdrMode === 'dv' ? styles.liveDvPill : styles.liveHdr10Pill]}>
                      <Icon name={activeHdrMode === 'dv' ? 'auto-awesome' : 'wb-sunny'} size={11} color={activeHdrMode === 'dv' ? '#FFD700' : '#000'} />
                      <Text style={[styles.liveHdrStatusText, activeHdrMode === 'hdr' && { color: '#000' }]}>
                        {activeHdrMode === 'dv' ? 'DOLBY VISION' : 'HDR10'}
                      </Text>
                    </TouchableOpacity>
                  )}
                </View>
              </View>
              <View style={styles.netflixTopRightRow}>
                {!IS_TV && (
                  <FocusableButton style={styles.netflixCircleBtn} onPress={handleEnterPiP}>
                    <Icon name="picture-in-picture-alt" size={20} color="white" />
                  </FocusableButton>
                )}
                {!IS_TV && (
                  <FocusableButton style={styles.netflixCircleBtn} onPress={() => { setIsLocked(true); setActiveMenu(null); }}>
                    <Icon name="lock-open" size={20} color="white" />
                  </FocusableButton>
                )}
              </View>
            </View>

            <View style={styles.netflixCenterRow} pointerEvents="box-none">
              <FocusableButton style={styles.netflixSeekCircleBtn} onPress={() => triggerStepSeek(false)}>
                <Icon name="replay-10" size={40} color="white" />
              </FocusableButton>
              <FocusableButton hasTVPreferredFocus={true} onPress={() => setIsPaused(!isPaused)} style={styles.netflixMainPlayBtn}>
                {isVideoBuffering ? <ActivityIndicator size="large" color="#FFFFFF" /> : <Icon name={isPaused ? 'play-arrow' : 'pause'} size={54} color="white" />}
              </FocusableButton>
              <FocusableButton style={styles.netflixSeekCircleBtn} onPress={() => triggerStepSeek(true)}>
                <Icon name="forward-10" size={40} color="white" />
              </FocusableButton>
            </View>

            <View style={styles.netflixBottomContainer}>
              <View style={styles.netflixScrubberRow}>
                <Text style={styles.netflixTimeLabel}>{formatTime(currentTime)}</Text>
                <TouchableOpacity
                  focusable={false}
                  activeOpacity={1}
                  style={styles.netflixProgressHitBox}
                  onLayout={e => setProgressWidth(e.nativeEvent.layout.width)}
                  onPress={e => {
                    if (progressWidth > 0 && duration > 0) {
                      seekBothPlayers((e.nativeEvent.locationX / progressWidth) * duration);
                    }
                  }}>
                  <View style={styles.netflixProgressTrack} />
                  <View style={[styles.netflixProgressFill, { width: `${progressPercent}%` }]} pointerEvents="none" />
                  <View style={[styles.netflixProgressThumb, { left: `${progressPercent}%` }]} pointerEvents="none" />
                </TouchableOpacity>
                <Text style={styles.netflixTimeLabel}>-{formatTime(remainingTime)}</Text>
              </View>

              <View style={styles.netflixActionRow}>
                <FocusableButton style={[styles.netflixActionPill, activeMenu === 'audio' && styles.netflixActionPillActive]} onPress={() => setActiveMenu(activeMenu === 'audio' ? null : 'audio')}>
                  <Icon name="audiotrack" size={20} color={activeMenu === 'audio' ? '#E50914' : 'white'} />
                  <Text style={styles.netflixActionText}>Audio</Text>
                </FocusableButton>
                <FocusableButton style={[styles.netflixActionPill, activeMenu === 'subtitle' && styles.netflixActionPillActive]} onPress={() => setActiveMenu(activeMenu === 'subtitle' ? null : 'subtitle')}>
                  <Icon name="subtitles" size={20} color={activeMenu === 'subtitle' ? '#E50914' : 'white'} />
                  <Text style={styles.netflixActionText}>Subtitles</Text>
                </FocusableButton>
                {playableFolderEpisodes.length > 1 && (
                  <FocusableButton style={[styles.netflixActionPill, activeMenu === 'episodes' && styles.netflixActionPillActive]} onPress={() => setActiveMenu(activeMenu === 'episodes' ? null : 'episodes')}>
                    <Icon name="video-library" size={19} color={activeMenu === 'episodes' ? '#E50914' : 'white'} />
                    <Text style={styles.netflixActionText}>Episodes</Text>
                  </FocusableButton>
                )}
                <FocusableButton style={styles.netflixActionPill} onPress={cyclePlaybackSpeed}>
                  <Icon name="speed" size={20} color="white" />
                  <Text style={styles.netflixActionText}>Speed ({playbackRate}x)</Text>
                </FocusableButton>
                <FocusableButton style={styles.netflixActionPill} onPress={handleZoomToggle}>
                  <Icon name={resizeMode === 'contain' ? 'aspect-ratio' : 'crop-free'} size={20} color={resizeMode === 'cover' ? '#E50914' : 'white'} />
                  <Text style={styles.netflixActionText}>{resizeMode === 'contain' ? 'Fit' : 'Zoom'}</Text>
                </FocusableButton>
                {canSwitchDvHdr && (
                  <FocusableButton style={styles.netflixActionPill} onPress={toggleLiveHdrModeInPlayer}>
                    <Icon name="hdr-on" size={20} color="#F5C518" />
                    <Text style={styles.netflixActionText}>Mode: {activeHdrMode.toUpperCase()}</Text>
                  </FocusableButton>
                )}
                {!!nextEpisodeFile && (
                  <FocusableButton style={styles.netflixNextEpPill} onPress={playNextEpisode}>
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

  // 🔥 2. MOVIE / EPISODE DETAIL OVERLAY 🔥
  const renderDetailOverlay = () => {
    if (!selectedFile || playMode !== null) return null;
    const info = parseMediaFilename(selectedFile.name, false);
    const meta = metadataMap[selectedFile.name];
    const realMi = mediaInfoMap[selectedFile.name];
    const savedSec = savedTimesMap[selectedFile.name] || 0;
    const totalDur = savedDurationsMap[selectedFile.name] || realMi?.durationSec || 0;
    const resumePct = totalDur > 0 && savedSec > 0 ? Math.min(98, Math.max(3, (savedSec / totalDur) * 100)) : 45;
    const bannerImage = meta?.backdrop || meta?.poster;
    const dlTask = downloads.find(d => d.fileName === selectedFile.name);
    const isFav = !!watchlist[selectedFile.name];

    const effectiveResolution = realMi?.resolution || info.resolution;
    const effectiveHasDV = realMi ? realMi.hasDV : info.hasDV;
    const effectiveHasHDR = realMi ? realMi.hasHDR : info.hasHDR;

    const topChips: string[] = [];
    if (realMi?.hasAtmos || info.badges.includes('Atmos')) topChips.push('Atmos');
    if (effectiveHasDV) topChips.push('DV');
    if (effectiveHasHDR) topChips.push('HDR10');
    if (realMi?.videoCodec) topChips.push(realMi.videoCodec.includes('HEVC') ? 'HEVC' : realMi.videoCodec);
    else if (info.badges.includes('HEVC')) topChips.push('HEVC');
    info.badges.forEach(b => {
      if (b !== 'DV' && b !== 'HDR' && b !== 'Atmos' && b !== 'HEVC' && !topChips.includes(b)) topChips.push(b);
    });

    return (
      <ScrollView style={styles.detailScreen} contentContainerStyle={{ paddingBottom: 40 }}>
        <StatusBar hidden={false} barStyle="light-content" />
        <View style={styles.detailHero}>
          {bannerImage ? (
            <Image source={{ uri: bannerImage }} style={styles.detailBackdrop} resizeMode="cover" />
          ) : (
            <View style={styles.detailBackdropPlaceholder}>
              <Text style={styles.placeholderMonogram}>{info.cleanTitle.charAt(0).toUpperCase()}</Text>
            </View>
          )}
          <View style={styles.detailHeroShade} />
          <FocusableButton style={styles.detailBackTopBtn} onPress={() => { setSelectedFile(null); setVersionGroup([]); }}>
            <Icon name="arrow-back" size={24} color="white" />
          </FocusableButton>
          <FocusableButton style={styles.detailFavTopBtn} onPress={() => toggleWatchlist(selectedFile.name)}>
            <Icon name={isFav ? 'favorite' : 'favorite-border'} size={22} color={isFav ? '#E50914' : 'white'} />
          </FocusableButton>
        </View>

        <View style={styles.detailContent}>
          <View style={styles.detailHeaderRow}>
            {meta?.poster ? <Image source={{ uri: meta.poster }} style={styles.detailPosterThumb} resizeMode="cover" /> : null}
            <View style={{ flex: 1 }}>
              <Text style={styles.detailTitle}>{info.cleanTitle}</Text>
              <View style={styles.detailMetaRow}>
                {!!(meta?.year || info.year) && <Text style={styles.detailYearText}>{meta?.year || info.year}</Text>}
                {!!info.seasonEpisode && <View style={styles.seasonBadge}><Text style={styles.seasonBadgeText}>{info.seasonEpisode}</Text></View>}
                {!!meta?.rating && <View style={styles.ratingBadge}><Text style={styles.ratingBadgeText}>★ {meta.rating}</Text></View>}
                {!!effectiveResolution && <View style={styles.qualityBadge}><Text style={styles.qualityBadgeText}>{effectiveResolution}</Text></View>}
              </View>
              {topChips.length > 0 && (
                <View style={styles.badgeWrapRow}>
                  {topChips.map((b, idx) => (
                    <View key={idx} style={styles.techChip}><Text style={styles.techChipText}>{b}</Text></View>
                  ))}
                </View>
              )}
            </View>
          </View>

          {savedSec > 3 && (
            <View style={styles.detailResumeBanner}>
              <View style={styles.detailResumeHeader}>
                <Text style={styles.detailResumeTitle}>Continue Watching</Text>
                <Text style={styles.detailResumeRemaining}>{formatRemainingLabel(savedSec, totalDur)}</Text>
              </View>
              <View style={styles.detailResumeTrack}>
                <View style={[styles.detailResumeFill, { width: `${resumePct}%` }]} />
              </View>
              <Text style={styles.detailResumeSub}>
                Watched {formatTime(savedSec)}{totalDur > 0 ? ` of ${formatTime(totalDur)}` : ''}
              </Text>
            </View>
          )}

          {!!meta?.overview && <Text style={styles.detailOverview}>{meta.overview}</Text>}

          {/* Multi-Version Selector (e.g. WEB-DL vs BluRay REMUX) */}
          {versionGroup.length > 1 && (
            <View style={styles.versionSelectorBox}>
              <Text style={styles.versionSelectorLabel}>
                AVAILABLE VERSIONS ({versionGroup.length}) • TAP TO SWITCH
              </Text>
              <View style={styles.versionPillsRow}>
                {versionGroup.map((vFile, vIdx) => {
                  const isCurrentVer = vFile.name === selectedFile.name;
                  const vMi = mediaInfoMap[vFile.name];
                  return (
                    <FocusableButton
                      key={vFile.name || vIdx}
                      style={[styles.versionPillBtn, isCurrentVer && styles.versionPillBtnActive]}
                      onPress={() => openFile(vFile)}>
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                        <Icon
                          name={isCurrentVer ? 'radio-button-checked' : 'radio-button-unchecked'}
                          size={16}
                          color={isCurrentVer ? '#E50914' : '#8A94A6'}
                        />
                        <Text style={[styles.versionPillTitle, isCurrentVer && { color: '#FFF' }]}>
                          {getVersionTag(vFile.name)}
                        </Text>
                      </View>
                      <Text style={styles.versionPillSub} numberOfLines={1}>
                        {vMi?.totalBytes ? `${formatBytes(vMi.totalBytes)} • ` : ''}{vFile.name}
                      </Text>
                    </FocusableButton>
                  );
                })}
              </View>
            </View>
          )}

          <View style={styles.detailActionButtons}>
            <FocusableButton hasTVPreferredFocus={true} style={styles.primaryPlayButton} onPress={() => handlePlayInAppPress(selectedFile)}>
              <Icon name="play-arrow" size={26} color="white" />
              <Text style={styles.primaryPlayButtonText}>
                {savedSec > 3
                  ? `Resume ${versionGroup.length > 1 ? getVersionTag(selectedFile.name).split(' • ')[0] : 'Watching'} (${formatRemainingLabel(savedSec, totalDur)})`
                  : versionGroup.length > 1
                  ? `Play ${getVersionTag(selectedFile.name)} in App`
                  : 'Play in App'}
              </Text>
            </FocusableButton>

            <View style={{ flexDirection: 'row', gap: 10 }}>
              <FocusableButton style={[styles.secondaryPlayButton, { flex: 1 }]} onPress={() => openInExternalPlayer(selectedFile)}>
                <Icon name="open-in-new" size={20} color="white" />
                <Text style={styles.secondaryPlayButtonText}>External Player</Text>
              </FocusableButton>
              <FocusableButton
                style={[styles.secondaryPlayButton, { paddingHorizontal: 16 }, isFav && { borderColor: '#E50914' }]}
                onPress={() => toggleWatchlist(selectedFile.name)}>
                <Icon name={isFav ? 'favorite' : 'favorite-border'} size={20} color={isFav ? '#E50914' : 'white'} />
                <Text style={styles.secondaryPlayButtonText}>{isFav ? 'Saved' : 'My List'}</Text>
              </FocusableButton>
            </View>

            {!dlTask ? (
              <FocusableButton style={styles.downloadActionBtn} onPress={() => handleStartDownload(selectedFile)}>
                <Icon name="file-download" size={22} color="#46d369" />
                <Text style={styles.downloadActionText}>
                  {versionGroup.length > 1 ? `Download (${getVersionTag(selectedFile.name)})` : 'Download'}
                </Text>
              </FocusableButton>
            ) : dlTask.status === 'completed' ? (
              <FocusableButton style={styles.downloadedReadyCard} onPress={handleOpenDownloadFolder}>
                <Icon name="check-circle" size={20} color="#46d369" />
                <Text style={styles.downloadedReadyText}>Saved in Download/GDStream • Tap to Open Folder</Text>
              </FocusableButton>
            ) : (
              <FocusableButton style={styles.downloadProgressCard} onPress={() => { setSelectedFile(null); setVersionGroup([]); setActiveTab('downloads'); }}>
                <View style={styles.dlCardHeaderRow}>
                  <Text style={styles.dlCardStatusText}>
                    {dlTask.status === 'downloading' ? `Downloading • ${formatBytes(dlTask.speedBytesPerSec)}/s` : `Download ${dlTask.status.toUpperCase()}`}
                  </Text>
                  <Text style={styles.dlCardPercentText}>{dlTask.progress.toFixed(1)}%</Text>
                </View>
                <View style={styles.dlProgressBarBg}>
                  <View style={[styles.dlProgressBarFill, { width: `${dlTask.progress}%` }]} />
                </View>
                <Text style={styles.dlCardSubText}>
                  {formatBytes(dlTask.downloadedBytes)} / {formatBytes(dlTask.totalBytes)} • Tap to open Downloads
                </Text>
              </FocusableButton>
            )}
          </View>

          {/* Dual / Multi-Version MediaInfo Cards on the same Detail Page */}
          {[selectedFile].map((vItem, vIdx) => {
            const vMi = mediaInfoMap[vItem.name];
            const vInfo = parseMediaFilename(vItem.name, false);
            const vRes = vMi?.resolution || vInfo.resolution;
            const isSelectedVer = vItem.name === selectedFile.name;
            return (
              <TouchableOpacity
                key={vItem.name || vIdx}
                activeOpacity={0.9}
                onPress={() => { if (versionGroup.length > 1 && !isSelectedVer) openFile(vItem); }}
                style={[
                  styles.mediaInfoCard,
                  versionGroup.length > 1 && isSelectedVer && { borderColor: '#E50914', borderWidth: 1.5 },
                ]}>
                <View style={styles.mediaInfoHeaderRow}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6, flex: 1 }}>
                    <Icon name="memory" size={16} color={isSelectedVer ? '#E50914' : '#8A94A6'} />
                    <Text style={[styles.mediaInfoHeaderTitle, isSelectedVer && { color: '#FFF' }]}>
                      {versionGroup.length > 1 ? `MEDIAINFO • ${getVersionTag(vItem.name).toUpperCase()}` : 'MEDIAINFO TECHNICAL SPECS'}
                    </Text>
                  </View>
                  {scanningMediaInfo && !vMi && (
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                      <ActivityIndicator size="small" color="#E50914" />
                      <Text style={styles.scanningText}>Scanning...</Text>
                    </View>
                  )}
                </View>

                {vMi ? (
                  <View style={{ gap: 10, marginTop: 8 }}>
                    <View style={styles.miSpecRow}>
                      <Text style={styles.miSpecLabel}>VIDEO</Text>
                      <Text style={styles.miSpecValue}>
                        {[
                          vMi.width > 0 ? `${vMi.width}×${vMi.height} (${vMi.resolution})` : vRes,
                          vMi.videoCodec,
                          vMi.hasDV && vMi.hasHDR ? 'Dolby Vision + HDR10' : vMi.hasDV ? 'Dolby Vision (Single Layer)' : vMi.hasHDR ? 'HDR10' : 'SDR',
                          vMi.frameRate > 0 ? `${vMi.frameRate.toFixed(2).replace(/\.00$/, '')} fps` : null,
                        ].filter(Boolean).join(' • ')}
                      </Text>
                    </View>
                    {(vMi.totalBytes > 0 || vMi.durationSec > 0) && (
                      <View style={styles.miSpecRow}>
                        <Text style={styles.miSpecLabel}>FILE & TIME</Text>
                        <Text style={styles.miSpecValue}>
                          {[
                            vMi.totalBytes > 0 ? formatBytes(vMi.totalBytes) : null,
                            vMi.durationSec > 0 ? formatRuntime(vMi.durationSec) : null,
                          ].filter(Boolean).join(' • ')}
                        </Text>
                      </View>
                    )}
                    {vMi.audioTracks && vMi.audioTracks.length > 0 && (
                      <View style={styles.miSpecRow}>
                        <Text style={styles.miSpecLabel}>AUDIO ({vMi.audioTracks.length})</Text>
                        <View style={styles.miTrackPillWrap}>
                          {vMi.audioTracks.map((at, idx) => (
                            <View key={idx} style={styles.miAudioPill}><Text style={styles.miAudioPillText}>{vMi.hasAtmos ? at.summary.replace('Dolby TrueHD', 'Dolby TrueHD with Dolby Atmos').replace('Dolby Digital Plus (DD+)', 'Dolby Digital Plus with Dolby Atmos') : at.summary}</Text></View>
                          ))}
                        </View>
                      </View>
                    )}
                    {vMi.subtitleTracks && vMi.subtitleTracks.length > 0 && (
                      <View style={styles.miSpecRow}>
                        <Text style={styles.miSpecLabel}>SUBTITLES ({vMi.subtitleTracks.length})</Text>
                        <View style={styles.miTrackPillWrap}>
                          {vMi.subtitleTracks.map((st, idx) => (
                            <View key={idx} style={styles.miSubPill}><Text style={styles.miSubPillText}>{st.language} ({st.format})</Text></View>
                          ))}
                        </View>
                      </View>
                    )}
                  </View>
                ) : !scanningMediaInfo ? (
                  <Text style={styles.rawFileName}>{vItem.name}</Text>
                ) : null}

                <View style={styles.sourceFileDivider}>
                  <Text style={styles.rawFileLabel}>SOURCE FILE</Text>
                  <Text style={styles.rawFileName}>{vItem.name}</Text>
                </View>
              </TouchableOpacity>
            );
          })}
        </View>
        {renderHdrChoiceModal()}
      </ScrollView>
    );
  };

  // 🔥 3. DOWNLOADS TAB SCREEN 🔥
  if (activeTab === 'downloads') {
    return (
      <View style={styles.container}>
        <StatusBar hidden={false} barStyle="light-content" />
        <View style={styles.topHeaderBar}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
            <FocusableButton style={styles.headerIconBtn} onPress={() => setActiveTab('catalog')}>
              <Icon name="arrow-back" size={22} color="white" />
            </FocusableButton>
            <Text style={styles.headerTitle}>Downloads</Text>
          </View>
          <FocusableButton style={styles.storageHintPill} onPress={handleOpenDownloadFolder}>
            <Icon name="folder-open" size={16} color="#F5C518" />
            <Text style={styles.storageHintText}>Download/GDStream</Text>
            <Icon name="open-in-new" size={13} color="#99A3B3" />
          </FocusableButton>
        </View>

        {downloads.length === 0 ? (
          <View style={styles.emptyDownloadsBox}>
            <Icon name="cloud-download" size={56} color="#3A4454" />
            <Text style={styles.emptyDownloadsTitle}>No Downloads Yet</Text>
            <Text style={styles.emptyDownloadsSub}>
              Movies you download are saved directly to your phone's Download/GDStream folder so you can watch offline in GDStream or any media player.
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
                      <Image source={{ uri: item.poster }} style={styles.dlPosterThumb} resizeMode="cover" />
                    ) : (
                      <View style={styles.dlPosterFallback}><Icon name="movie" size={28} color="#E50914" /></View>
                    )}
                    <View style={{ flex: 1 }}>
                      <Text style={styles.dlItemTitle} numberOfLines={1}>{item.title}</Text>
                      <Text style={styles.dlItemFile} numberOfLines={1}>{item.fileName}</Text>
                      <View style={styles.dlProgressBarBg}>
                        <View style={[styles.dlProgressBarFill, { width: `${item.progress}%`, backgroundColor: isDone ? '#46d369' : '#E50914' }]} />
                      </View>
                      <View style={styles.dlStatsRow}>
                        <Text style={styles.dlStatsText}>
                          {formatBytes(item.downloadedBytes)} / {formatBytes(item.totalBytes)} ({item.progress.toFixed(1)}%)
                        </Text>
                        <Text style={styles.dlSpeedText}>
                          {isRunning ? `⚡ ${formatBytes(item.speedBytesPerSec)}/s` : item.status.toUpperCase()}
                        </Text>
                      </View>
                    </View>
                  </View>
                  <View style={styles.dlActionsRow}>
                    {isDone ? (
                      <>
                        <FocusableButton style={styles.dlPrimaryBtn} onPress={() => { const lf = { name: item.fileName, localPath: item.filePath }; setVersionGroup([lf]); openFile(lf); handlePlayInAppPress(lf); }}>
                          <Icon name="play-arrow" size={20} color="white" />
                          <Text style={styles.dlBtnText}>Play in App</Text>
                        </FocusableButton>
                        <FocusableButton style={styles.dlSecondaryBtn} onPress={() => openInExternalPlayer({ name: item.fileName, localPath: item.filePath })}>
                          <Icon name="open-in-new" size={18} color="white" />
                          <Text style={styles.dlBtnText}>External Player</Text>
                        </FocusableButton>
                      </>
                    ) : isRunning ? (
                      <FocusableButton style={styles.dlSecondaryBtn} onPress={() => DownloadEngine?.pauseDownload(item.id)}>
                        <Icon name="pause" size={18} color="white" />
                        <Text style={styles.dlBtnText}>Pause</Text>
                      </FocusableButton>
                    ) : (
                      <FocusableButton style={styles.dlPrimaryBtn} onPress={() => DownloadEngine?.resumeDownload(item.id)}>
                        <Icon name="play-arrow" size={18} color="white" />
                        <Text style={styles.dlBtnText}>Resume</Text>
                      </FocusableButton>
                    )}
                    <FocusableButton style={styles.dlDeleteBtn} onPress={() => DownloadEngine?.deleteDownload(item.id)}>
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
  const activeDownloadsCount = downloads.filter(d => d.status === 'downloading').length;

  return (
    <View style={styles.container}>
      <StatusBar hidden={false} barStyle="light-content" />
      <View style={styles.topHeaderBar}>
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <View style={styles.logoMark}><Text style={styles.logoMarkText}>▶</Text></View>
          <Text style={styles.headerTitle}>GDStream</Text>
        </View>

        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <FocusableButton
            style={styles.sortToggleBtn}
            onPress={() => setSortBy(prev => (prev === 'newest' ? 'az' : 'newest'))}>
            <Icon name="sort" size={16} color="#E50914" />
            <Text style={styles.sortToggleText}>{sortBy === 'newest' ? 'Newest' : 'A - Z'}</Text>
          </FocusableButton>

          <FocusableButton style={styles.headerIconBtn} onPress={() => setActiveTab('downloads')}>
            <Icon name="file-download" size={22} color={activeDownloadsCount > 0 ? '#46d369' : 'white'} />
            {downloads.length > 0 && (
              <View style={styles.dlBadgeDot}><Text style={styles.dlBadgeDotText}>{downloads.length}</Text></View>
            )}
          </FocusableButton>
          <FocusableButton style={styles.headerIconBtn} onPress={handleRefresh}>
            <Icon name="refresh" size={21} color="white" />
          </FocusableButton>
          <FocusableButton style={styles.headerIconBtn} onPress={() => setViewMode(prev => (prev === 'grid' ? 'list' : 'grid'))}>
            <Icon name={viewMode === 'grid' ? 'view-list' : 'grid-view'} size={22} color="white" />
          </FocusableButton>
        </View>
      </View>

      <View style={styles.searchRow}>
        <Icon name="search" size={20} color="#888" style={{ marginLeft: 12 }} />
        <TextInput
          style={styles.searchBarInput}
          placeholder="Search movies, series, 4K, Tamil..."
          placeholderTextColor="#777"
          value={searchQuery}
          onChangeText={setSearchQuery}
          autoCorrect={false}
          returnKeyType="search"
        />
        {!!searchQuery && (
          <TouchableOpacity onPress={() => setSearchQuery('')} style={{ padding: 8 }}>
            <Icon name="close" size={18} color="#AAA" />
          </TouchableOpacity>
        )}
      </View>

      <View style={{ marginBottom: 10 }}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: 14, gap: 8 }}>
          {FILTER_CATEGORIES.map(cat => {
            const isSelected = activeCategory === cat.id;
            return (
              <FocusableButton
                key={cat.id}
                style={[styles.filterChip, isSelected && styles.filterChipActive]}
                onPress={() => setActiveCategory(cat.id)}>
                <Text style={[styles.filterChipText, isSelected && styles.filterChipTextActive]}>{cat.label}</Text>
              </FocusableButton>
            );
          })}
        </ScrollView>
      </View>

      {pathStack.length > 1 && (
        <View style={styles.seriesNavRow}>
          <FocusableButton style={[styles.backFolderBar, { flex: 1, marginHorizontal: 0, marginBottom: 0 }]} onPress={goBack}>
            <Icon name="arrow-back" size={18} color="white" />
            <Text style={styles.backFolderText} numberOfLines={1}>
              {seriesParentTitle ? `Back • ${seriesParentTitle}` : `Back • ${decodeURIComponent(currentPath)}`}
            </Text>
          </FocusableButton>

          {seasonFolders.length > 0 && activeSeason && (
            <FocusableButton style={styles.seasonDropdownBtn} onPress={() => setShowSeasonModal(true)}>
              <Text style={styles.seasonDropdownBtnText}>{activeSeason.label}</Text>
              <Icon name="arrow-drop-down" size={22} color="white" />
            </FocusableButton>
          )}
        </View>
      )}

      {loading ? (
        <ActivityIndicator size="large" color="#E50914" style={{ marginTop: 60 }} />
      ) : (
        <FlatList
          key={`catalog-${viewMode}-${numColumns}`}
          data={displayedFiles}
          numColumns={numColumns}
          keyboardShouldPersistTaps="handled"
          removeClippedSubviews={false}
          ListHeaderComponent={
            !searchQuery && activeCategory === 'all' && continueWatchingItems.length > 0 ? (
              <View style={styles.cwSection}>
                <Text style={styles.cwSectionTitle}>Continue Watching ⏯️</Text>
                <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 10, paddingHorizontal: 6 }}>
                  {continueWatchingItems.map((cwItem, idx) => {
                    const cwInfo = parseMediaFilename(cwItem.name, false);
                    const cwMeta = metadataMap[cwItem.name];
                    const cwSec = savedTimesMap[cwItem.name] || 0;
                    const cwDur = savedDurationsMap[cwItem.name] || mediaInfoMap[cwItem.name]?.durationSec || 0;
                    const cwPct = cwDur > 0 ? Math.min(98, Math.max(5, (cwSec / cwDur) * 100)) : 45;
                    const imgUri = cwMeta?.backdrop || cwMeta?.poster;
                    return (
                      <View key={cwItem.id || cwItem.name || idx} style={styles.cwCard}>
                        <FocusableButton
                          onPress={() => {
                            const parentGroup = displayedFiles.find((df: any) => df._versions?.some((v: any) => v.name === cwItem.name));
                            setVersionGroup(parentGroup?._versions || [cwItem]);
                            openFile(cwItem);
                            handlePlayInAppPress(cwItem);
                          }}>
                          <View style={styles.cwImageWrap}>
                            {imgUri ? (
                              <Image source={{ uri: imgUri }} style={styles.cwImage} resizeMode="cover" />
                            ) : (
                              <View style={styles.cwFallback}><Icon name="play-circle-outline" size={32} color="#E50914" /></View>
                            )}
                            <View style={styles.cwPlayBadge}><Icon name="play-arrow" size={18} color="white" /></View>
                            <View style={styles.resumeBarBg}>
                              <View style={[styles.resumeBarFill, { width: `${cwPct}%` }]} />
                            </View>
                          </View>
                          <View style={styles.cwFooter}>
                            <Text style={styles.cwTitle} numberOfLines={1}>
                              {cwInfo.seasonEpisode ? `${cwInfo.seasonEpisode} • ${cwInfo.cleanTitle}` : cwInfo.cleanTitle}
                            </Text>
                            <Text style={styles.cwTimeText}>Resume • {formatRemainingLabel(cwSec, cwDur)}</Text>
                          </View>
                        </FocusableButton>
                        <TouchableOpacity
                          style={styles.cwRemoveBtn}
                          hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                          onPress={() => removeContinueWatchingItem(cwItem)}>
                          <Icon name="close" size={15} color="#FFFFFF" />
                        </TouchableOpacity>
                      </View>
                    );
                  })}
                </ScrollView>
              </View>
            ) : null
          }
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={handleRefresh} tintColor="#E50914" colors={['#E50914']} progressBackgroundColor="#181C24" />
          }
          contentContainerStyle={{ paddingHorizontal: 10, paddingBottom: 30 }}
          keyExtractor={(item, idx) => item.id || item.name || String(idx)}
          renderItem={({ item, index }) => {
            const isFolder = !!item.mimeType?.includes('folder');
            const info = parseMediaFilename(item.name, isFolder);
            const meta = metadataMap[item.name];
            const realMi = mediaInfoMap[item.name];
            const activeVerForCard = item._versions?.find((v: any) => (savedTimesMap[v.name] || 0) > 3) || item;
            const savedSec = savedTimesMap[activeVerForCard.name] || 0;
            const totalDur = savedDurationsMap[activeVerForCard.name] || mediaInfoMap[activeVerForCard.name]?.durationSec || 0;
            const resumePct = totalDur > 0 && savedSec > 0 ? Math.min(98, Math.max(5, (savedSec / totalDur) * 100)) : 45;
            const cardRes = realMi?.resolution || info.resolution;
            const isFav = !!watchlist[item.name];
            const versionCount = item._versions?.length || 1;

            if (viewMode === 'list') {
              return (
                <FocusableButton hasTVPreferredFocus={index === 0 && !searchQuery} style={styles.listCard} onPress={() => handlePress(item)}>
                  {meta?.poster ? (
                    <Image source={{ uri: meta.poster }} style={styles.listPosterThumb} resizeMode="cover" />
                  ) : (
                    <View style={styles.listPosterFallback}>
                      <Icon name={isFolder ? 'folder' : 'movie'} size={26} color={isFolder ? '#F5C518' : '#E50914'} />
                    </View>
                  )}
                  <View style={styles.listCardBody}>
                    <Text style={styles.listCardTitle} numberOfLines={1}>
                      {info.episodeNum > 0 ? `E${info.episodeNum < 10 ? `0${info.episodeNum}` : info.episodeNum} • ${info.cleanTitle}` : info.cleanTitle}
                    </Text>
                    <View style={styles.listCardMetaRow}>
                      {!!(meta?.year || info.year) && <Text style={styles.cardSubText}>{meta?.year || info.year}</Text>}
                      {!!info.seasonEpisode && <Text style={styles.cardSeasonTag}>{info.seasonEpisode}</Text>}
                      {!!cardRes && <Text style={styles.cardQualityTag}>{cardRes}</Text>}
                      {versionCount > 1 && <Text style={styles.cardSeasonTag}>{versionCount} Versions</Text>}
                      {savedSec > 3 && <Text style={styles.cardResumeTag}>{formatRemainingLabel(savedSec, totalDur)}</Text>}
                    </View>
                  </View>
                </FocusableButton>
              );
            }

            return (
              <FocusableButton hasTVPreferredFocus={index === 0 && !searchQuery} style={styles.posterCard} onPress={() => handlePress(item)}>
                <View style={styles.posterImageContainer}>
                  {meta?.poster ? (
                    <Image source={{ uri: meta.poster }} style={styles.posterImage} resizeMode="cover" />
                  ) : (
                    <View style={styles.posterFallback}>
                      <Icon name={isFolder ? 'folder-special' : 'local-movies'} size={38} color={isFolder ? '#F5C518' : '#E50914'} />
                      <Text style={styles.posterFallbackTitle} numberOfLines={3}>{info.cleanTitle}</Text>
                    </View>
                  )}

                  <View style={styles.posterTopBadges}>
                    {isFolder ? (
                      <View style={styles.folderPill}><Text style={styles.folderPillText}>{info.seasonEpisode || 'SERIES'}</Text></View>
                    ) : info.seasonEpisode ? (
                      <View style={styles.folderPill}><Text style={styles.folderPillText}>{info.seasonEpisode}</Text></View>
                    ) : meta?.rating ? (
                      <View style={styles.ratingPill}><Text style={styles.ratingPillText}>★ {meta.rating}</Text></View>
                    ) : <View />}

                    {!!cardRes && (
                      <View style={[styles.resPill, cardRes === '4K' && styles.resPill4K]}>
                        <Text style={styles.resPillText}>{cardRes}</Text>
                      </View>
                    )}
                  </View>

                  {versionCount > 1 && (
                    <View style={styles.multiVersionBadge}>
                      <Text style={styles.multiVersionBadgeText}>{versionCount} VERSIONS</Text>
                    </View>
                  )}

                  {isFav && (
                    <View style={styles.posterFavBadge}><Icon name="favorite" size={13} color="#E50914" /></View>
                  )}

                  {savedSec > 3 && (
                    <View style={styles.resumeBarBg}>
                      <View style={[styles.resumeBarFill, { width: `${resumePct}%` }]} />
                    </View>
                  )}
                </View>

                <View style={styles.posterCardFooter}>
                  <Text style={styles.posterTitle} numberOfLines={1}>
                    {info.episodeNum > 0 ? `Episode ${info.episodeNum} • ${info.cleanTitle}` : info.cleanTitle}
                  </Text>
                  <View style={styles.posterSubRow}>
                    {savedSec > 3 ? (
                      <Text style={styles.posterResumeText}>▶ {formatRemainingLabel(savedSec, totalDur)}</Text>
                    ) : (
                      <>
                        <Text style={styles.posterYear}>{meta?.year || info.year || (isFolder ? 'Series' : 'Movie')}</Text>
                        {(realMi?.languages?.length ? realMi.languages.slice(0, 2) : info.badges.slice(0, 2)).map((b, idx) => (
                          <Text key={idx} style={styles.posterMiniTag}>• {b}</Text>
                        ))}
                      </>
                    )}
                  </View>
                </View>
              </FocusableButton>
            );
          }}
        />
      )}
      {renderDetailOverlay()}
      {renderSeasonModal()}
      {renderHdrChoiceModal()}
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0B0C10', paddingTop: 38 },
  securityAlertScreen: { flex: 1, backgroundColor: '#0B0C10', alignItems: 'center', justifyContent: 'center', padding: 32 },
  securityAlertTitle: { color: 'white', fontSize: 20, fontWeight: '800', marginTop: 14 },
  securityAlertSub: { color: '#99A3B3', fontSize: 13, textAlign: 'center', lineHeight: 20, marginTop: 8 },
  topHeaderBar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, marginBottom: 12 },
  logoMark: { width: 30, height: 30, borderRadius: 7, backgroundColor: '#E50914', alignItems: 'center', justifyContent: 'center', marginRight: 9 },
  logoMarkText: { color: 'white', fontSize: 15, fontWeight: '900' },
  headerTitle: { color: '#FFFFFF', fontSize: 21, fontWeight: '800', letterSpacing: 0.4 },
  sortToggleBtn: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 10, paddingVertical: 7, borderRadius: 8, backgroundColor: '#1A1D24', borderWidth: 1, borderColor: '#2E3646' },
  sortToggleText: { color: '#F2F5F8', fontSize: 12, fontWeight: '800' },
  headerIconBtn: { padding: 8, borderRadius: 8, backgroundColor: '#1A1D24', borderWidth: 2, borderColor: 'transparent', position: 'relative' },
  dlBadgeDot: { position: 'absolute', top: -4, right: -4, backgroundColor: '#E50914', minWidth: 16, height: 16, borderRadius: 8, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 3 },
  dlBadgeDotText: { color: 'white', fontSize: 9, fontWeight: '900' },
  searchRow: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#161920', marginHorizontal: 14, marginBottom: 10, borderRadius: 10, borderWidth: 1, borderColor: '#262B36' },
  searchBarInput: { flex: 1, color: 'white', paddingVertical: 11, paddingHorizontal: 10, fontSize: 15 },
  filterChip: { paddingHorizontal: 14, paddingVertical: 7, borderRadius: 999, backgroundColor: '#151821', borderWidth: 1, borderColor: '#262C3A' },
  filterChipActive: { backgroundColor: '#E50914', borderColor: '#FF4B55' },
  filterChipText: { color: '#B0BAC9', fontSize: 12, fontWeight: '700' },
  filterChipTextActive: { color: '#FFFFFF', fontWeight: '800' },
  seriesNavRow: { flexDirection: 'row', alignItems: 'center', marginHorizontal: 14, marginBottom: 10, gap: 8 },
  backFolderBar: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#181C24', paddingVertical: 9, paddingHorizontal: 14, marginHorizontal: 14, borderRadius: 8, marginBottom: 10, gap: 8, borderWidth: 2, borderColor: 'transparent' },
  backFolderText: { color: '#DDD', fontSize: 13, fontWeight: '600', flex: 1 },
  seasonDropdownBtn: { flexDirection: 'row', alignItems: 'center', gap: 4, backgroundColor: '#E50914', paddingVertical: 8, paddingHorizontal: 14, borderRadius: 8, borderWidth: 1, borderColor: '#FF4B55' },
  seasonDropdownBtnText: { color: 'white', fontSize: 13, fontWeight: '900' },
  seasonOptionItem: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#1B1F2C', padding: 12, borderRadius: 10, borderWidth: 1.5, borderColor: '#283042', gap: 12, marginBottom: 8 },
  seasonOptionItemActive: { borderColor: '#E50914', backgroundColor: 'rgba(229,9,20,0.12)' },
  seasonNumberCircle: { width: 38, height: 38, borderRadius: 19, backgroundColor: '#252B3B', alignItems: 'center', justifyContent: 'center' },
  seasonNumberText: { color: '#F5C518', fontSize: 12, fontWeight: '900' },
  cwSection: { marginBottom: 14 },
  cwSectionTitle: { color: '#F2F5F8', fontSize: 15, fontWeight: '800', marginLeft: 6, marginBottom: 8 },
  cwCard: { width: 190, backgroundColor: '#14171F', borderRadius: 10, overflow: 'hidden', borderWidth: 1.5, borderColor: '#222836' },
  cwImageWrap: { width: '100%', height: 106, backgroundColor: '#191D26', position: 'relative' },
  cwImage: { width: '100%', height: '100%' },
  cwFallback: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  cwRemoveBtn: { position: 'absolute', top: 6, right: 6, width: 24, height: 24, borderRadius: 12, backgroundColor: 'rgba(0,0,0,0.78)', borderWidth: 1, borderColor: 'rgba(255,255,255,0.28)', alignItems: 'center', justifyContent: 'center', zIndex: 10 },
  cwPlayBadge: { position: 'absolute', right: 8, bottom: 10, width: 28, height: 28, borderRadius: 14, backgroundColor: 'rgba(229,9,20,0.92)', alignItems: 'center', justifyContent: 'center' },
  cwFooter: { paddingHorizontal: 9, paddingVertical: 7 },
  cwTitle: { color: 'white', fontSize: 13, fontWeight: '700' },
  cwTimeText: { color: '#E50914', fontSize: 11, fontWeight: '700', marginTop: 2 },
  posterCard: { flex: 1, maxWidth: IS_TV ? '18.6%' : '47%', margin: '1.5%', backgroundColor: '#14171F', borderRadius: 12, overflow: 'hidden', borderWidth: 2, borderColor: '#1F2430' },
  posterImageContainer: { width: '100%', aspectRatio: 2 / 3, backgroundColor: '#191D26', position: 'relative' },
  posterImage: { width: '100%', height: '100%' },
  posterFallback: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 14, backgroundColor: '#171B24' },
  posterFallbackTitle: { color: '#D5DCE6', fontSize: 14, fontWeight: '700', textAlign: 'center', marginTop: 10 },
  posterTopBadges: { position: 'absolute', top: 8, left: 8, right: 8, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  multiVersionBadge: { position: 'absolute', bottom: 8, left: 8, backgroundColor: 'rgba(229, 9, 20, 0.92)', paddingHorizontal: 7, paddingVertical: 3, borderRadius: 5 },
  multiVersionBadgeText: { color: '#FFF', fontSize: 9, fontWeight: '900', letterSpacing: 0.4 },
  posterFavBadge: { position: 'absolute', bottom: 8, right: 8, width: 24, height: 24, borderRadius: 12, backgroundColor: 'rgba(11,12,16,0.82)', alignItems: 'center', justifyContent: 'center' },
  folderPill: { backgroundColor: 'rgba(245, 197, 24, 0.92)', paddingHorizontal: 7, paddingVertical: 3, borderRadius: 5 },
  folderPillText: { color: '#000', fontSize: 10, fontWeight: '900' },
  ratingPill: { backgroundColor: 'rgba(0, 0, 0, 0.78)', paddingHorizontal: 7, paddingVertical: 3, borderRadius: 5, borderWidth: 1, borderColor: 'rgba(245, 197, 24, 0.45)' },
  ratingPillText: { color: '#F5C518', fontSize: 10, fontWeight: '800' },
  resPill: { backgroundColor: 'rgba(15, 20, 28, 0.85)', paddingHorizontal: 6, paddingVertical: 3, borderRadius: 5, borderWidth: 1, borderColor: '#3A4454' },
  resPill4K: { backgroundColor: '#E50914', borderColor: '#FF4B55' },
  resPillText: { color: '#FFF', fontSize: 10, fontWeight: '900' },
  resumeBarBg: { position: 'absolute', bottom: 0, left: 0, right: 0, height: 4, backgroundColor: 'rgba(255,255,255,0.25)' },
  resumeBarFill: { width: '45%', height: '100%', backgroundColor: '#E50914' },
  posterCardFooter: { paddingHorizontal: 10, paddingVertical: 9 },
  posterTitle: { color: '#F2F5F8', fontSize: 14, fontWeight: '700' },
  posterSubRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', marginTop: 3, gap: 4 },
  posterYear: { color: '#8A94A6', fontSize: 11, fontWeight: '600' },
  posterMiniTag: { color: '#A5B0C2', fontSize: 11 },
  posterResumeText: { color: '#E50914', fontSize: 11, fontWeight: '800' },
  listCard: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#14171F', marginHorizontal: 6, marginVertical: 5, padding: 10, borderRadius: 10, borderWidth: 2, borderColor: '#1F2430' },
  listPosterThumb: { width: 52, height: 76, borderRadius: 6, backgroundColor: '#1E222B' },
  listPosterFallback: { width: 52, height: 76, borderRadius: 6, backgroundColor: '#1E222B', alignItems: 'center', justifyContent: 'center' },
  listCardBody: { flex: 1, marginLeft: 12 },
  listCardTitle: { color: '#FFF', fontSize: 16, fontWeight: '700' },
  listCardMetaRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 6, marginTop: 6 },
  cardSubText: { color: '#99A3B3', fontSize: 12, fontWeight: '600' },
  cardSeasonTag: { backgroundColor: '#293242', color: '#FFD166', fontSize: 11, fontWeight: '700', paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4 },
  cardQualityTag: { backgroundColor: '#E50914', color: '#FFF', fontSize: 11, fontWeight: '800', paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4 },
  cardResumeTag: { backgroundColor: 'rgba(229,9,20,0.18)', color: '#FF6B72', fontSize: 11, fontWeight: '800', paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4 },
  tvFocused: { borderColor: '#E50914', borderWidth: 2, backgroundColor: 'rgba(229, 9, 20, 0.22)' },
  detailScreen: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 50, backgroundColor: '#0B0C10' },
  detailHero: { width: '100%', height: 240, backgroundColor: '#141821', position: 'relative' },
  detailBackdrop: { width: '100%', height: '100%' },
  detailBackdropPlaceholder: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#181D29' },
  placeholderMonogram: { color: 'rgba(229, 9, 20, 0.35)', fontSize: 84, fontWeight: '900' },
  detailHeroShade: { position: 'absolute', left: 0, right: 0, bottom: 0, top: 0, backgroundColor: 'rgba(11, 12, 16, 0.45)' },
  detailBackTopBtn: { position: 'absolute', top: 40, left: 16, width: 42, height: 42, borderRadius: 21, backgroundColor: 'rgba(0,0,0,0.65)', alignItems: 'center', justifyContent: 'center', borderWidth: 2, borderColor: 'transparent' },
  detailFavTopBtn: { position: 'absolute', top: 40, right: 16, width: 42, height: 42, borderRadius: 21, backgroundColor: 'rgba(0,0,0,0.65)', alignItems: 'center', justifyContent: 'center', borderWidth: 2, borderColor: 'transparent' },
  detailContent: { paddingHorizontal: 18, marginTop: -36 },
  detailHeaderRow: { flexDirection: 'row', alignItems: 'flex-end', gap: 14 },
  detailPosterThumb: { width: 105, height: 156, borderRadius: 10, borderWidth: 2, borderColor: '#2A3140', backgroundColor: '#161922' },
  detailTitle: { color: '#FFFFFF', fontSize: 22, fontWeight: '800', lineHeight: 28 },
  detailMetaRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 8, marginTop: 8 },
  detailYearText: { color: '#B0BAC9', fontSize: 14, fontWeight: '700' },
  seasonBadge: { backgroundColor: '#F5C518', paddingHorizontal: 8, paddingVertical: 3, borderRadius: 5 },
  seasonBadgeText: { color: '#000', fontSize: 12, fontWeight: '800' },
  ratingBadge: { backgroundColor: '#1E2533', paddingHorizontal: 8, paddingVertical: 3, borderRadius: 5, borderWidth: 1, borderColor: '#F5C518' },
  ratingBadgeText: { color: '#F5C518', fontSize: 12, fontWeight: '800' },
  qualityBadge: { backgroundColor: '#E50914', paddingHorizontal: 8, paddingVertical: 3, borderRadius: 5 },
  qualityBadgeText: { color: '#FFF', fontSize: 12, fontWeight: '900' },
  badgeWrapRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 10 },
  techChip: { backgroundColor: '#1A202C', borderWidth: 1, borderColor: '#2E3748', paddingHorizontal: 9, paddingVertical: 4, borderRadius: 6 },
  techChipText: { color: '#D1D9E6', fontSize: 12, fontWeight: '600' },
  detailResumeBanner: { marginTop: 16, backgroundColor: '#141822', padding: 12, borderRadius: 10, borderWidth: 1, borderColor: '#283042' },
  detailResumeHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 },
  detailResumeTitle: { color: '#FFFFFF', fontSize: 12, fontWeight: '800' },
  detailResumeRemaining: { color: '#E50914', fontSize: 12, fontWeight: '900' },
  detailResumeTrack: { height: 5, backgroundColor: '#252C3A', borderRadius: 3, overflow: 'hidden' },
  detailResumeFill: { height: '100%', backgroundColor: '#E50914' },
  detailResumeSub: { color: '#8A94A6', fontSize: 11, marginTop: 5, fontWeight: '600' },
  detailOverview: { color: '#C4CDD9', fontSize: 14, lineHeight: 21, marginTop: 18 },
  versionSelectorBox: { marginTop: 18, backgroundColor: '#131722', padding: 12, borderRadius: 12, borderWidth: 1, borderColor: '#262F42' },
  versionSelectorLabel: { color: '#9BA6B8', fontSize: 11, fontWeight: '900', letterSpacing: 0.8, marginBottom: 8 },
  versionPillsRow: { gap: 8 },
  versionPillBtn: { backgroundColor: '#1A1F2E', paddingVertical: 10, paddingHorizontal: 12, borderRadius: 9, borderWidth: 1.5, borderColor: '#2B3346' },
  versionPillBtnActive: { backgroundColor: 'rgba(229, 9, 20, 0.14)', borderColor: '#E50914' },
  versionPillTitle: { color: '#D0D8E8', fontSize: 14, fontWeight: '800' },
  versionPillSub: { color: '#7D899E', fontSize: 11, marginTop: 3, marginLeft: 22 },
  detailActionButtons: { marginTop: 20, gap: 12 },
  primaryPlayButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: '#E50914', paddingVertical: 14, paddingHorizontal: 20, borderRadius: 10, gap: 8, borderWidth: 2, borderColor: 'transparent' },
  primaryPlayButtonText: { color: '#FFFFFF', fontSize: 16, fontWeight: '800' },
  secondaryPlayButton: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: '#1E2430', paddingVertical: 13, paddingHorizontal: 20, borderRadius: 10, gap: 8, borderWidth: 2, borderColor: '#2E3748' },
  secondaryPlayButtonText: { color: '#E2E8F0', fontSize: 15, fontWeight: '700' },
  downloadActionBtn: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: '#141B24', paddingVertical: 13, paddingHorizontal: 20, borderRadius: 10, gap: 8, borderWidth: 1.5, borderColor: 'rgba(70, 211, 105, 0.45)' },
  downloadActionText: { color: '#46d369', fontSize: 15, fontWeight: '800' },
  downloadedReadyCard: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(70, 211, 105, 0.12)', paddingVertical: 12, paddingHorizontal: 16, borderRadius: 10, gap: 8, borderWidth: 1, borderColor: 'rgba(70, 211, 105, 0.4)' },
  downloadedReadyText: { color: '#46d369', fontSize: 13, fontWeight: '700' },
  downloadProgressCard: { backgroundColor: '#151922', padding: 14, borderRadius: 10, borderWidth: 1, borderColor: '#2B3344' },
  dlCardHeaderRow: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 8 },
  dlCardStatusText: { color: '#46d369', fontSize: 12, fontWeight: '800' },
  dlCardPercentText: { color: 'white', fontSize: 12, fontWeight: '800' },
  dlProgressBarBg: { height: 6, backgroundColor: '#252C3A', borderRadius: 3, overflow: 'hidden', marginVertical: 6 },
  dlProgressBarFill: { height: '100%', backgroundColor: '#E50914', borderRadius: 3 },
  dlCardSubText: { color: '#99A3B3', fontSize: 11, marginTop: 2 },
  mediaInfoCard: { marginTop: 18, padding: 14, borderRadius: 12, backgroundColor: '#12151E', borderWidth: 1, borderColor: '#232A38' },
  mediaInfoHeaderRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 },
  mediaInfoHeaderTitle: { color: '#9BA6B8', fontSize: 11, fontWeight: '900', letterSpacing: 0.9 },
  scanningText: { color: '#E50914', fontSize: 11, fontWeight: '700' },
  miSpecRow: { marginTop: 4 },
  miSpecLabel: { color: '#6C7789', fontSize: 10, fontWeight: '800', letterSpacing: 0.8, marginBottom: 4 },
  miSpecValue: { color: '#E2E8F0', fontSize: 13, fontWeight: '700' },
  miTrackPillWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  miAudioPill: { backgroundColor: '#1A202E', borderWidth: 1, borderColor: '#2E384D', paddingHorizontal: 9, paddingVertical: 5, borderRadius: 6 },
  miAudioPillText: { color: '#D8E1EE', fontSize: 12, fontWeight: '600' },
  miSubPill: { backgroundColor: '#171C26', borderWidth: 1, borderColor: '#283040', paddingHorizontal: 8, paddingVertical: 4, borderRadius: 6 },
  miSubPillText: { color: '#A8B3C5', fontSize: 11, fontWeight: '600' },
  sourceFileDivider: { marginTop: 14, paddingTop: 10, borderTopWidth: 1, borderTopColor: '#1E2430' },
  rawFileLabel: { color: '#6C7789', fontSize: 10, fontWeight: '800', letterSpacing: 1, marginBottom: 4 },
  rawFileName: { color: '#8F9BB0', fontSize: 12 },
  hdrModalCard: { width: '100%', maxWidth: 400, backgroundColor: '#141821', borderRadius: 16, padding: 20, borderWidth: 1, borderColor: '#2D3546' },
  hdrModalEyebrow: { color: '#E50914', fontSize: 11, fontWeight: '900', letterSpacing: 1.2 },
  hdrModalTitle: { color: 'white', fontSize: 20, fontWeight: '800', marginTop: 4 },
  hdrModalSub: { color: '#9AA5B5', fontSize: 13, lineHeight: 19, marginTop: 6, marginBottom: 16 },
  dvOptionBtn: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#1B1F2C', padding: 14, borderRadius: 12, borderWidth: 1.5, borderColor: '#7C4DFF', gap: 12, marginBottom: 12 },
  dvOptionBadge: { backgroundColor: '#5E35B1', paddingHorizontal: 8, paddingVertical: 5, borderRadius: 6 },
  dvOptionBadgeText: { color: '#FFD700', fontSize: 10, fontWeight: '900' },
  hdr10OptionBtn: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#1B1F2C', padding: 14, borderRadius: 12, borderWidth: 1.5, borderColor: '#F5C518', gap: 12 },
  hdr10OptionBadge: { backgroundColor: '#F5C518', paddingHorizontal: 10, paddingVertical: 5, borderRadius: 6 },
  hdr10OptionBadgeText: { color: '#000', fontSize: 11, fontWeight: '900' },
  hdrOptionTitle: { color: 'white', fontSize: 15, fontWeight: '800' },
  hdrOptionDesc: { color: '#99A3B3', fontSize: 11, marginTop: 2 },
  hdrCancelBtn: { alignItems: 'center', paddingVertical: 12, marginTop: 8 },
  storageHintPill: { flexDirection: 'row', alignItems: 'center', gap: 6, backgroundColor: '#171B24', paddingHorizontal: 11, paddingVertical: 7, borderRadius: 8, borderWidth: 1, borderColor: '#2E3748' },
  storageHintText: { color: '#E2E8F0', fontSize: 12, fontWeight: '700' },
  emptyDownloadsBox: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 34 },
  emptyDownloadsTitle: { color: 'white', fontSize: 18, fontWeight: '800', marginTop: 14 },
  emptyDownloadsSub: { color: '#8C97A8', fontSize: 13, textAlign: 'center', lineHeight: 20, marginTop: 8 },
  dlItemCard: { backgroundColor: '#14171F', borderRadius: 12, padding: 14, marginBottom: 12, borderWidth: 1, borderColor: '#232938' },
  dlPosterThumb: { width: 60, height: 88, borderRadius: 8, backgroundColor: '#1E222B' },
  dlPosterFallback: { width: 60, height: 88, borderRadius: 8, backgroundColor: '#1E222B', alignItems: 'center', justifyContent: 'center' },
  dlItemTitle: { color: 'white', fontSize: 16, fontWeight: '800' },
  dlItemFile: { color: '#8A94A6', fontSize: 11, marginTop: 2 },
  dlStatsRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 },
  dlStatsText: { color: '#A5B0C2', fontSize: 11, fontWeight: '600' },
  dlSpeedText: { color: '#46d369', fontSize: 11, fontWeight: '800' },
  dlActionsRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 12 },
  dlPrimaryBtn: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: '#E50914', paddingVertical: 9, borderRadius: 8, gap: 6 },
  dlSecondaryBtn: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', backgroundColor: '#222836', paddingVertical: 9, borderRadius: 8, gap: 6 },
  dlDeleteBtn: { padding: 9, borderRadius: 8, backgroundColor: 'rgba(255, 107, 107, 0.12)' },
  dlBtnText: { color: 'white', fontSize: 13, fontWeight: '700' },
  modalBackdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.78)', alignItems: 'center', justifyContent: 'center', padding: 20 },
  playerWrapper: { flex: 1, backgroundColor: 'black' },
  videoPlayer: { position: 'absolute', top: 0, left: 0, bottom: 0, right: 0 },
  externalSubtitleContainer: { position: 'absolute', left: 24, right: 24, bottom: 52, alignItems: 'center', justifyContent: 'center' },
  externalSubtitleText: { color: '#FFFFFF', backgroundColor: 'transparent', fontWeight: '700', textAlign: 'center', textShadowColor: 'rgba(0, 0, 0, 0.95)', textShadowOffset: { width: 1.5, height: 1.5 }, textShadowRadius: 4 },
  gestureHudContainer: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, justifyContent: 'center' },
  gestureHudLeft: { alignItems: 'flex-start', paddingLeft: 42 },
  gestureHudRight: { alignItems: 'flex-end', paddingRight: 42 },
  gestureHudPill: { width: 60, paddingVertical: 14, borderRadius: 30, backgroundColor: 'rgba(15, 17, 23, 0.88)', borderWidth: 1, borderColor: 'rgba(255,255,255,0.14)', alignItems: 'center', justifyContent: 'center', gap: 10 },
  gestureBarTrack: { width: 7, height: 105, backgroundColor: 'rgba(255,255,255,0.2)', borderRadius: 4, overflow: 'hidden', justifyContent: 'flex-end' },
  gestureBarFill: { width: '100%', backgroundColor: '#E50914', borderRadius: 4 },
  gestureHudText: { color: '#FFFFFF', fontSize: 12, fontWeight: '800' },
  controlsOverlay: { position: 'absolute', top: 0, left: 0, bottom: 0, right: 0, backgroundColor: 'rgba(0, 0, 0, 0.48)', justifyContent: 'space-between' },
  lockedOverlay: { position: 'absolute', bottom: 32, left: 0, right: 0, alignItems: 'center', justifyContent: 'center' },
  unlockPillBtn: { flexDirection: 'row', alignItems: 'center', gap: 8, backgroundColor: 'rgba(15, 17, 23, 0.9)', paddingVertical: 11, paddingHorizontal: 22, borderRadius: 999, borderWidth: 1.5, borderColor: '#E50914' },
  unlockPillText: { color: 'white', fontSize: 14, fontWeight: '700' },
  seekOverlay: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, justifyContent: 'center', paddingHorizontal: 60 },
  netflixSeekBubble: { alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(0, 0, 0, 0.55)', width: 92, height: 92, borderRadius: 46 },
  seekText: { color: 'white', fontSize: 13, fontWeight: '800', marginTop: 2 },
  netflixTopBar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 22, paddingTop: 18 },
  netflixCircleBtn: { width: 42, height: 42, borderRadius: 21, backgroundColor: 'rgba(20, 22, 30, 0.72)', alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.12)' },
  netflixTitleCenter: { flex: 1, alignItems: 'center', paddingHorizontal: 14 },
  netflixMainTitle: { color: '#FFFFFF', fontSize: 17, fontWeight: '800', letterSpacing: 0.3 },
  netflixSubMetaRow: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 4 },
  netflixEpisodeTag: { color: '#F5C518', fontSize: 12, fontWeight: '800' },
  netflixMiniBadge: { backgroundColor: '#E50914', paddingHorizontal: 6, paddingVertical: 2, borderRadius: 4 },
  netflixMiniBadgeText: { color: 'white', fontSize: 10, fontWeight: '900' },
  liveHdrStatusPill: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 8, paddingVertical: 2, borderRadius: 4 },
  liveDvPill: { backgroundColor: '#5E35B1', borderWidth: 1, borderColor: '#FFD700' },
  liveHdr10Pill: { backgroundColor: '#F5C518' },
  liveHdrStatusText: { color: '#FFD700', fontSize: 10, fontWeight: '900', letterSpacing: 0.4 },
  netflixTopRightRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  netflixCenterRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 54 },
  netflixSeekCircleBtn: { width: 64, height: 64, borderRadius: 32, backgroundColor: 'rgba(18, 20, 28, 0.6)', alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.16)' },
  netflixMainPlayBtn: { width: 82, height: 82, borderRadius: 41, backgroundColor: 'rgba(229, 9, 20, 0.9)', alignItems: 'center', justifyContent: 'center', borderWidth: 2, borderColor: 'rgba(255,255,255,0.25)' },
  netflixBottomContainer: { paddingHorizontal: 22, paddingBottom: 18, width: '100%' },
  netflixScrubberRow: { flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 10 },
  netflixTimeLabel: { color: '#E6ECF5', fontSize: 12, fontWeight: '700', minWidth: 52, textAlign: 'center' },
  netflixProgressHitBox: { flex: 1, height: 28, justifyContent: 'center' },
  netflixProgressTrack: { height: 4, backgroundColor: 'rgba(255,255,255,0.28)', borderRadius: 2 },
  netflixProgressFill: { height: 4, backgroundColor: '#E50914', borderRadius: 2, position: 'absolute' },
  netflixProgressThumb: { position: 'absolute', width: 14, height: 14, borderRadius: 7, backgroundColor: '#E50914', top: 7, marginLeft: -7, borderWidth: 2, borderColor: '#FFFFFF' },
  netflixActionRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-around', flexWrap: 'wrap', gap: 8 },
  netflixActionPill: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingVertical: 7, paddingHorizontal: 13, borderRadius: 999, backgroundColor: 'rgba(22, 25, 34, 0.78)', borderWidth: 1, borderColor: 'rgba(255,255,255,0.12)' },
  netflixActionPillActive: { borderColor: '#E50914', backgroundColor: 'rgba(229, 9, 20, 0.2)' },
  netflixActionText: { color: '#FFFFFF', fontSize: 12, fontWeight: '700' },
  netflixNextEpPill: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingVertical: 7, paddingHorizontal: 14, borderRadius: 999, backgroundColor: '#FFFFFF' },
  netflixNextEpText: { color: '#000000', fontSize: 12, fontWeight: '900' },
  netflixSheetOverlay: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, backgroundColor: 'rgba(0,0,0,0.55)', alignItems: 'center', justifyContent: 'center', zIndex: 100 },
  floatingMenu: { width: 340, maxWidth: '90%', backgroundColor: '#14171F', borderRadius: 16, padding: 16, borderWidth: 1, borderColor: '#2B3242' },
  floatingMenuHeader: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderBottomWidth: 1, borderBottomColor: '#262C3A', paddingBottom: 10, marginBottom: 10 },
  floatingMenuTitle: { color: '#FFFFFF', fontSize: 15, fontWeight: '800' },
  menuCloseBtn: { padding: 4, borderRadius: 6 },
  menuItem: { flexDirection: 'row', alignItems: 'center', paddingVertical: 10, paddingHorizontal: 8, borderRadius: 8 },
  menuItemText: { color: 'white', fontSize: 14, marginLeft: 10, flex: 1 },
  adjustRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 8 },
  adjustLabel: { color: '#AAA', fontSize: 13, width: 95, fontWeight: '600' },
  adjustBtn: { width: 34, height: 34, borderRadius: 17, backgroundColor: '#232936', justifyContent: 'center', alignItems: 'center' },
  adjustValue: { color: 'white', fontSize: 14, fontWeight: 'bold', width: 68, textAlign: 'center' },
});

export default App;
