import React, { useState, useEffect, useRef, useMemo, useCallback, useContext, createContext } from 'react';
import {
  View, Text, FlatList, TouchableOpacity, StyleSheet,
  Alert, NativeModules, TextInput, ScrollView, BackHandler, Animated,
  Platform, DeviceEventEmitter, Image, Modal, PanResponder, Dimensions,
  StatusBar, RefreshControl, AppState, Share,
} from 'react-native';
import Video from 'react-native-video';
import DvdSubtitleOverlay from './DvdSubtitleOverlay';
import InAppAudioFileBrowser from './InAppAudioFileBrowser';
import {
  ShimmerCardGrid,
  FadeInPoster,
  CinemaArcLoader,
  DiscInspectLoader,
  CascadeSpecPill,
  TopSyncProgressBar,
  TactileSpringPressable,
  StaggeredSection,
  SlideUpSheet,
  TierRimGlowCard,
  CardGlowTier,
  GlintBadge,
  WaveCascadeCard,
  KenBurnsBackdrop,
  SeekBurstOverlay,
  DownloadPulseBadge,
  ProgressGlowTip,
  BitrateMeterBar,
  MiniEqualizerPulse,
  CopySpecPill,
  PlayHeroButton,
  ExternalLaunchButton,
  MyListHeartButton,
  DownloadActionButton,
  TrailerGlowButton,
  SeriesCycleBadge,
  VersionOptionCard,
  HeroPortraitPoster,
  SeasonTriggerPill,
  SeasonBottomSheet,
  SeasonOptionRow,
  ZoomHudPill,
} from './LoadingAnimations';
import { catalogRequest, catalogJson, inCatalogSlot } from './catalogRequests';
import Orientation from 'react-native-orientation-locker';
import Icon from 'react-native-vector-icons/MaterialIcons';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { usePlaybackSource } from './playbackSession';
import { isIsoArchiveUrl, withArchiveTimeout, playbackDuration, playbackErrorMessage, getPlayableUrl, encodeLibraryName, archiveSourceUrl, isoMovieDetails, sameShowEpisodes } from './archivePlayback';
import {
  ParsedMediaInfo,
  FolderClassification,
  parseMediaFilename,
  extractSeasonNumberFromFolder,
  hasExplicitSeriesFolderMarker,
  isAmbiguousDriveFolder,
  isSeriesContainerItem,
  highestResolutionLabel,
  classifyFolderContents,
  processCatalogItems,
  metadataCacheKey,
  legacyMetadataCacheKeys,
  isStaleMovieCache,
  isUsableCachedMetadata,
  buildTvSearchQueries,
  selectBestTmdbMovieMatch,
  selectBestTmdbTvMatch,
} from './mediaClassification';

import { linkedWatchKeys, watchAliases } from './watchHistory';

const {
  VideoPlayerManager, SubtitleFileModule, DownloadEngine, MediaInfoModule, SecurityVault,
} = NativeModules;

const IS_TV = Platform.isTV;
const CW_STORAGE_KEY = 'gdstream_cw_history_v2';

// AsyncStorage v3 returns a record from getMany; callers below consume ordered pairs.
const readStoragePairs = async (keys: string[]) => {
  const values = await AsyncStorage.getMany(keys);
  return keys.map(key => [key, values[key]] as const);
};

const standaloneProbeCandidate = (file: any) => !file.isZipEntry && (!/\.(mkv|mp4|avi|mov|webm|m4v|iso)$/i.test(file.name) || /mp2t/i.test(file.mimeType || ''));

let directoryAttempt = 0;

interface MediaMetadata {
  title?: string;
  tmdbId?: number;
  originalLanguage?: string;
  mediaType?: 'movie' | 'tv';
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
  videoBitrateMbps?: number;
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

const ActiveLayerContext = createContext<string>('home');
const FocusLayerContext = createContext<string>('home');
const HeaderFocusableContext = createContext<boolean>(true);

const tvFocusRegistry: Record<string, any> = {};
let currentFocusedId = '';
let previousFocusedId = '';
let lastFocusChangeTime = 0;
let pendingInitialFocusId = '';
let onGlobalTvActivity: (() => void) | null = null;

const focusById = (id: string): boolean => {
  if (!id) return false;
  let triggered = false;
  if (IS_TV && VideoPlayerManager?.focusByNativeId) {
    try {
      VideoPlayerManager.focusByNativeId(id);
      triggered = true;
    } catch (_e) {}
  }
  const target = tvFocusRegistry[id];
  if (target && typeof target.focus === 'function') {
    try {
      target.focus();
      triggered = true;
    } catch (_e) {}
  }
  return triggered;
};

const requestInitialScreenFocus = (id: string) => {
  if (!IS_TV || !id) return;
  pendingInitialFocusId = id;
  focusById(id);
};

const copyTextSafely = (value: string) => {
  try {
    const nativeClip = (NativeModules as any).Clipboard || (NativeModules as any).RNCClipboard;
    if (nativeClip && typeof nativeClip.setString === 'function') {
      nativeClip.setString(value);
      return;
    }
  } catch (_e) {}
  Share.share({ message: value }).catch(() => {});
};

const parseSeasonFolderSpecs = (folderName: string): string[] => {
  const raw = String(folderName || '');
  const specs: string[] = [];
  if (/(2160p|4K|UHD)/i.test(raw)) specs.push('4K UHD');
  else if (/1080p/i.test(raw)) specs.push('1080p');
  else if (/720p/i.test(raw)) specs.push('720p');

  if (/REMUX/i.test(raw)) specs.push('REMUX');
  else if (/Blu-?Ray|BDRip|BRRip/i.test(raw)) specs.push('BluRay');
  else if (/WEB-?DL|WEBRip/i.test(raw)) specs.push('WEB-DL');

  if (/\b(DoVi|DV|Dolby\.?Vision)\b/i.test(raw)) specs.push('DV');
  else if (/HDR10\+?|HDR/i.test(raw)) specs.push('HDR10');

  if (/Atmos/i.test(raw)) specs.push('Atmos');
  else if (/TrueHD/i.test(raw)) specs.push('TrueHD');
  else if (/DDP|DD\+|EAC3/i.test(raw)) specs.push('DDP 5.1');
  else if (/DTS-?HD/i.test(raw)) specs.push('DTS-HD');
  else if (/\bDTS\b/i.test(raw)) specs.push('DTS');

  if (/Dual|Multi|\b(HIN|TAM|TEL|ENG)[.\-_+](HIN|TAM|TEL|ENG)\b/i.test(raw)) {
    specs.push('Multi Audio');
  } else if (/\b(Tamil|Tam)\b/i.test(raw)) {
    specs.push('Tamil');
  } else if (/\b(Hindi|Hin)\b/i.test(raw)) {
    specs.push('Hindi');
  }
  return specs.slice(0, 4);
};

const zoomPctToScale = (pct: number): number => {
  const clamped = Math.max(-100, Math.min(300, pct));
  return clamped >= 0 ? 1 + clamped / 100 : 1 + (clamped / 100) * 0.75;
};

const formatCompactAudioLabel = (
  track: { language: string; codec: string; channels: string; bitrateKbps: number; summary: string },
  hasAtmos?: boolean,
) => {
  const shortCodec = (track.codec || '')
    .replace(/Dolby TrueHD.*/i, hasAtmos ? 'TrueHD Atmos' : 'TrueHD')
    .replace(/Dolby Digital Plus.*/i, 'DD+')
    .replace(/Dolby Digital.*/i, 'DD')
    .replace(/\s+/g, ' ')
    .trim();
  const parts = [
    track.language && track.language !== 'Undetermined' ? track.language : '',
    shortCodec ? (track.channels ? `${shortCodec} ${track.channels}` : shortCodec) : track.channels,
    track.bitrateKbps > 0 ? `${track.bitrateKbps}k` : '',
  ].filter(Boolean);
  return parts.length > 0 ? parts.join(' • ') : track.summary;
};

const FocusableButton = ({
  style,
  focusedStyle,
  onPress,
  onFocus: userOnFocus,
  onBlur: userOnBlur,
  children,
  hasTVPreferredFocus,
  activeOpacity = 0.8,
  disabled = false,
  focusId,
  isHeaderControl = false,
  enableSheen = false,
  Component,
  ...rest
}: any) => {
  const [isFocused, setIsFocused] = useState(false);
  const btnRef = useRef<any>(null);
  const activeLayer = useContext(ActiveLayerContext);
  const myLayer = useContext(FocusLayerContext);
  const headerAllowed = useContext(HeaderFocusableContext);
  const isLayerActive = !disabled && (myLayer === activeLayer) && (!isHeaderControl || headerAllowed);

  useEffect(() => {
    if (focusId && isLayerActive && btnRef.current) {
      tvFocusRegistry[focusId] = btnRef.current;
      if (IS_TV && pendingInitialFocusId === focusId) {
        const targetToFocus = focusId;
        const t = setTimeout(() => {
          if (pendingInitialFocusId === targetToFocus) {
            focusById(targetToFocus);
          }
        }, 25);
        return () => {
          clearTimeout(t);
          if (tvFocusRegistry[focusId] === btnRef.current) {
            delete tvFocusRegistry[focusId];
          }
        };
      }
      return () => {
        if (tvFocusRegistry[focusId] === btnRef.current) {
          delete tvFocusRegistry[focusId];
        }
      };
    }
  }, [focusId, isLayerActive]);

  useEffect(() => {
    if (!isLayerActive && isFocused) {
      setIsFocused(false);
    }
  }, [isLayerActive, isFocused]);

  const showFocus = isFocused && isLayerActive;
  const BtnComp: React.ComponentType<any> = Component || TactileSpringPressable;

  return (
    <BtnComp
      innerRef={btnRef}
      nativeID={isLayerActive && focusId ? focusId : undefined}
      disabled={disabled || !isLayerActive}
      focusable={isLayerActive}
      accessible={isLayerActive}
      highlighted={showFocus}
      enableSheen={enableSheen}
      glowColor={showFocus ? '#FFD700' : 'rgba(229, 9, 20, 0.72)'}
      onPress={(e: any) => {
        if (onGlobalTvActivity) onGlobalTvActivity();
        if (onPress) onPress(e);
      }}
      onFocus={(e: any) => {
        if (onGlobalTvActivity) onGlobalTvActivity();
        if (focusId) {
          if (pendingInitialFocusId === focusId) {
            pendingInitialFocusId = '';
          }
          previousFocusedId = currentFocusedId;
          currentFocusedId = focusId;
          lastFocusChangeTime = Date.now();
        }
        setIsFocused(true);
        if (userOnFocus) userOnFocus(e);
      }}
      onBlur={(e: any) => {
        setIsFocused(false);
        if (userOnBlur) userOnBlur(e);
      }}
      style={[
        style,
        showFocus && styles.tvFocused,
        showFocus && focusedStyle,
      ]}
      {...rest}>
      {children}
    </BtnComp>
  );
};

const DiscSpecsSkeleton = () => {
  const phase = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    const loop = Animated.loop(Animated.timing(phase, { toValue: 1, duration: 1400, useNativeDriver: true }));
    loop.start();
    return () => loop.stop();
  }, [phase]);
  return <View accessibilityLabel="Inspecting Blu-ray media specifications" style={{ gap: 14, marginTop: 16 }}>
    {[0.95, 0.8, 0.9, 0.65].map((width, index) => <View key={index} style={{ height: 16, width: `${width * 100}%`, backgroundColor: '#232936', borderRadius: 6, overflow: 'hidden' }}>
      <Animated.View style={{ height: 16, width: 110, backgroundColor: '#414B5E', opacity: 0.5,
        transform: [{ translateX: phase.interpolate({ inputRange: [0, 1], outputRange: [-110, 700] }) }] }} />
    </View>)}
  </View>;
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

const normalizeAudioTrackSpec = (rawSpec: string, index: number, allSpecs: string[], fileName: string): string => {
  if (!rawSpec) return '';
  let spec = rawSpec;
  const brMatch = spec.match(/(\d+)\s*kbps/i);
  const kbps = brMatch ? parseInt(brMatch[1], 10) : 0;

  if (kbps > 0 && kbps < 384) {
    spec = spec
      .replace(/Dolby Atmos \(DD\+\)/gi, 'Dolby Digital Plus (DD+)')
      .replace(/Dolby Digital Plus with Dolby Atmos/gi, 'Dolby Digital Plus (DD+)')
      .replace(/\bAtmos\b/gi, 'DD+');
    return spec;
  }

  if (allSpecs.length > 1 && /Atmos/i.test(spec)) {
    const allKbps = allSpecs.map(s => {
      const m = s.match(/(\d+)\s*kbps/i);
      return m ? parseInt(m[1], 10) : 0;
    });
    const maxKbps = Math.max(...allKbps);
    if (maxKbps >= 448 && kbps > 0 && kbps < maxKbps) {
      spec = spec
        .replace(/Dolby Atmos \(DD\+\)/gi, 'Dolby Digital Plus (DD+)')
        .replace(/Dolby Digital Plus with Dolby Atmos/gi, 'Dolby Digital Plus (DD+)');
    }
  }
  return spec;
};

const App = () => {
  const vaultRef = useRef<{ baseUrl: string; username: string; password: string; tmdbKey: string }>({
    baseUrl: '', username: '', password: '', tmdbKey: '',
  });
  const [vaultReady, setVaultReady] = useState(false);
  const [securityCompromised, setSecurityCompromised] = useState(false);

  // Separate Home Catalog vs Series/Subfolder Files so Home FlatList never unmounts or loses scroll position!
  const directoryRequest = useRef(0);
  const [homeFiles, setHomeFiles] = useState<any[]>([]);
  const homeFilesRef = useRef<any[]>([]);
  homeFilesRef.current = homeFiles;
  const [folderFiles, setFolderFiles] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [folderLoading, setFolderLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [syncingCatalog, setSyncingCatalog] = useState(false);
  const [isEnrichingMetadata, setIsEnrichingMetadata] = useState(false);
  const [pathStack, setPathStack] = useState(['/0:/']);
  const [currentPath, setCurrentPath] = useState('/0:/');
  const [searchQuery, setSearchQuery] = useState('');
  const searchInputRef = useRef<any>(null);
  const lastHomeCardIdRef = useRef<string>('card_0');
  const lastFolderCardIdRef = useRef<string>('fcard_0');
  const lastPlayerActivityRef = useRef<number>(Date.now());
  const [headerFocusUnlocked, setHeaderFocusUnlocked] = useState(!IS_TV);
  const [isTvSearchModalOpen, setIsTvSearchModalOpen] = useState(false);
  const lastFocusedScreenKeyRef = useRef<string>('');
  const [activeCategory, setActiveCategory] = useState('all');
  const [sortBy, setSortBy] = useState<'newest' | 'az'>('newest');
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [activeTab, setActiveTab] = useState<'catalog' | 'downloads'>('catalog');

  const isInsideFolder = pathStack.length > 1;
  const activeRawFiles = isInsideFolder ? folderFiles : homeFiles;

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
  const savedTimesRef = useRef<Record<string, number>>({});
  const savedDurationsRef = useRef<Record<string, number>>({});

  const [downloads, setDownloads] = useState<DownloadItem[]>([]);
  const [activeHdrMode, setActiveHdrMode] = useState<'dv' | 'hdr' | 'sdr'>('dv');
  const [showHdrChoiceModal, setShowHdrChoiceModal] = useState(false);
  const [playerInstanceKey, setPlayerInstanceKey] = useState(0);

  const archiveListing = useRef<{ path: string; url: string; size: number } | null>(null);
  const [navigationReady, setNavigationReady] = useState(true);
  const [selectedFile, setSelectedFile] = useState<any>(null);

  
  const [seriesSeasonRangeMap, setSeriesSeasonRangeMap] = React.useState<Record<string, { min: number; max: number }>>({});
  const [folderClassifications, setFolderClassifications] = React.useState<Record<string, FolderClassification>>({});
  const folderClassificationsRef = React.useRef<Record<string, FolderClassification>>({});
  folderClassificationsRef.current = folderClassifications;
  const folderClassifyingRef = React.useRef<Set<string>>(new Set());

  const folderPathForItem = useCallback((item: any): string => {
    const baseFolderPath = currentPath.startsWith('/0:/') ? currentPath : '/0:/';
    return `${baseFolderPath}${encodeLibraryName(String(item?.name || ''))}/`;
  }, [currentPath]);

  const extractSeasonRangeFromName = useCallback((raw: string): { min: number; max: number } | null => {
    const rangeMatch = raw.match(/(?:[.\s_-]|\b)S(\d{1,2})\s*[-–]\s*S?(\d{1,2})(?:[.\s_-]|\b)/i)
      || raw.match(/\bSeason[\s._-]*(\d{1,2})\s*[-–to]+\s*(?:Season[\s._-]*)?(\d{1,2})\b/i);
    if (rangeMatch) {
      const a = parseInt(rangeMatch[1], 10);
      const b = parseInt(rangeMatch[2], 10);
      if (a > 0 && b > 0) return { min: Math.min(a, b), max: Math.max(a, b) };
    }
    const singleMatch = raw.match(/(?:[.\s_-]|\b)S(\d{1,2})(?:[\s._-]*E\d{1,3}|[.\s_-]|\b)/i)
      || raw.match(/\bSeason[\s._-]*(\d{1,2})\b/i);
    if (singleMatch) {
      const s = parseInt(singleMatch[1], 10);
      if (s > 0) return { min: s, max: s };
    }
    const fromHelper = extractSeasonNumberFromFolder(raw);
    if (fromHelper !== null && fromHelper > 0) {
      return { min: fromHelper, max: fromHelper };
    }
    return null;
  }, []);

  // Discover actual owned season range (e.g., S04 or S02-S03) strictly from Drive files/folders in memory
  React.useEffect(() => {
    const rawList: any[] = Array.isArray(activeRawFiles) ? activeRawFiles : [];
    if (rawList.length === 0) return;

    const localRangeMap: Record<string, { min: number; max: number }> = {};
    for (const f of rawList) {
      const name = String(f?.name || f?.title || '');
      const parsedRange = extractSeasonRangeFromName(name);
      if (parsedRange) {
        const isDir = isSeriesContainerItem(f);
        const fInfo = parseMediaFilename(name, isDir);
        const clean = String(fInfo?.searchTitle || fInfo?.cleanTitle || '').trim().toUpperCase();
        if (clean) {
          const existing = localRangeMap[clean];
          localRangeMap[clean] = existing
            ? { min: Math.min(existing.min, parsedRange.min), max: Math.max(existing.max, parsedRange.max) }
            : parsedRange;
        }
      }
    }

    if (Object.keys(localRangeMap).length > 0) {
      setSeriesSeasonRangeMap(prev => {
        const merged = { ...prev };
        for (const [k, rng] of Object.entries(localRangeMap)) {
          merged[k] = merged[k]
            ? { min: Math.min(merged[k].min, rng.min), max: Math.max(merged[k].max, rng.max) }
            : rng;
        }
        return merged;
      });
    }
  }, [activeRawFiles, extractSeasonRangeFromName]);

  const seriesMetaCacheRef = React.useRef<Record<string, { minSeason: number; maxSeason: number; rating: string }>>({});
  const seriesPendingRef = React.useRef<Set<string>>(new Set());
  const [, forceSeriesBadgeUpdate] = React.useState(0);

  const ensureSeriesMetaLoaded = (itemObj: any, infoObj: any) => {
    const classified = itemObj?.mimeType?.includes('folder') ? folderClassifications[folderPathForItem(itemObj)] : undefined;
    if (classified && classified.kind !== 'series') return null;
    if (isAmbiguousDriveFolder(itemObj) && !classified) return null;
    const searchName = String(infoObj?.searchTitle || infoObj?.cleanTitle || itemObj?.name || '').trim();
    const cleanKey = searchName.toUpperCase();
    if (!cleanKey || cleanKey.length < 2) return null;
    if (seriesMetaCacheRef.current[cleanKey]) {
      return seriesMetaCacheRef.current[cleanKey];
    }
    if (seriesPendingRef.current.has(cleanKey)) return null;

    seriesPendingRef.current.add(cleanKey);
    setTimeout(async () => {
      const foundSeasons: number[] = [];
      let tmdbRating = '';

      const ownRange = extractSeasonRangeFromName(String(itemObj?.name || ''));
      if (ownRange) {
        foundSeasons.push(ownRange.min, ownRange.max);
      }

      // Inspect actual season subfolders/episodes inside this Drive Series Folder
      try {
        if (itemObj?.mimeType?.includes('folder') && itemObj?.name) {
          const baseFolderPath = currentPath.startsWith('/0:/') ? currentPath : '/0:/';
          const subPath = `${baseFolderPath}${encodeLibraryName(itemObj.name)}/`;
          const already = folderClassificationsRef.current[subPath];
          if (already && already.kind === 'series' && already.minSeason > 0) {
            foundSeasons.push(already.minSeason, already.maxSeason);
          } else {
            const subFiles = await requestRawDirectory(subPath);
            if (Array.isArray(subFiles) && subFiles.length > 0) {
              for (const sf of subFiles) {
                const subRange = extractSeasonRangeFromName(String(sf?.name || ''));
                if (subRange) {
                  foundSeasons.push(subRange.min, subRange.max);
                }
              }
            }
          }
        }
      } catch (_e) {}

      // Fetch TMDb rating only (never override owned seasons with total released TMDb seasons)
      try {
        const existingRating = metadataMap[itemObj?.name]?.rating;
        if (existingRating) {
          tmdbRating = existingRating;
        } else {
          const tmdbKey = vaultRef.current?.tmdbKey || '';
          if (tmdbKey && !IS_TV) {
            const hit = await searchTmdbTv(tmdbKey.trim(), searchName, infoObj?.year || '', Boolean(infoObj?.yearExplicit), infoObj?.langCode || '');
            const voteAvg = Number(hit?.vote_average || 0);
            if (voteAvg > 0) tmdbRating = voteAvg.toFixed(1);
          }
        }
      } catch (_e) {}

      const minS = foundSeasons.length > 0 ? Math.min(...foundSeasons) : 1;
      const maxS = foundSeasons.length > 0 ? Math.max(...foundSeasons) : 1;

      seriesMetaCacheRef.current[cleanKey] = {
        minSeason: minS,
        maxSeason: maxS,
        rating: tmdbRating,
      };
      setSeriesSeasonRangeMap(prev => ({
        ...prev,
        [cleanKey]: prev[cleanKey]
          ? { min: Math.min(prev[cleanKey].min, minS), max: Math.max(prev[cleanKey].max, maxS) }
          : { min: minS, max: maxS },
      }));
      forceSeriesBadgeUpdate(v => v + 1);
    }, 20);

    return null;
  };

  const getHomeSeriesBadgeText = (itemObj: any, infoObj: any, metaObj: any): { seasonText: string; rating: number | null } => {
    const cached = ensureSeriesMetaLoaded(itemObj, infoObj);
    const cleanKey = String(infoObj?.searchTitle || infoObj?.cleanTitle || '').trim().toUpperCase();
    const mappedRange = cleanKey ? seriesSeasonRangeMap[cleanKey] : undefined;
    const ownRange = extractSeasonRangeFromName(String(itemObj?.name || ''));

    const candidatesMin: number[] = [];
    const candidatesMax: number[] = [];
    if (cached && (cached.minSeason > 0 || cached.maxSeason > 0)) {
      candidatesMin.push(cached.minSeason);
      candidatesMax.push(cached.maxSeason);
    }
    if (mappedRange) {
      candidatesMin.push(mappedRange.min);
      candidatesMax.push(mappedRange.max);
    }
    if (ownRange) {
      candidatesMin.push(ownRange.min);
      candidatesMax.push(ownRange.max);
    }

    const minS = candidatesMin.length > 0 ? Math.min(...candidatesMin) : 1;
    const maxS = candidatesMax.length > 0 ? Math.max(...candidatesMax) : 1;

    const seasonText = maxS > minS
      ? `S${String(minS).padStart(2, '0')}-S${String(maxS).padStart(2, '0')}`
      : `S${String(minS).padStart(2, '0')}`;

    const rawRating = metaObj?.rating ?? cached?.rating ?? null;
    const parsedRating = typeof rawRating === 'number' ? rawRating : parseFloat(String(rawRating ?? ''));
    const validRating = typeof parsedRating === 'number' && Number.isFinite(parsedRating) && parsedRating > 0.1
      ? parsedRating
      : null;

    return { seasonText, rating: validRating };
  };

  const [episodeTmdbMeta, setEpisodeTmdbMeta] = React.useState<{
    rating: string | null;
    overview: string | null;
    epTitle: string | null;
    code: string | null;
  } | null>(null);
  const [episodeCardsMetaMap, setEpisodeCardsMetaMap] = React.useState<Record<string, {
    epTitle: string;
    stillUrl: string | null;
    runtimeMin: number;
  }>>({});
  const fetchedEpCardKeysRef = React.useRef<Set<string>>(new Set());
  const [subsExpanded, setSubsExpanded] = React.useState(false);

  React.useEffect(() => {
    setSubsExpanded(false);
  }, [selectedFile?.name]);

  const [trailerState, setTrailerState] = React.useState<{
    loading: boolean;
    available: boolean;
    cacheKey: string;
    bestQuality: string;
    title: string;
  }>({ loading: false, available: false, cacheKey: '', bestQuality: '', title: '' });

  React.useEffect(() => {
    let cancelled = false;
    const rawName = (typeof selectedFile !== 'undefined' && selectedFile?.name) ? String(selectedFile.name) : '';
    if (!rawName) {
      setEpisodeTmdbMeta(null);
      setTrailerState({ loading: false, available: false, cacheKey: '', bestQuality: '', title: '' });
      return;
    }

    const fetchEpisodeAndVerifiedTrailer = async () => {
      const tmdbKey = vaultRef.current?.tmdbKey || '';
      const parsed = parseMediaFilename(rawName, false);
      const epMatch = rawName.match(/(?:[.\s_-]|\b)S(\d{1,2})[\s._-]*E(\d{1,3})\b/i)
        || (parsed.seasonEpisode ? parsed.seasonEpisode.match(/S(\d{1,2})E(\d{1,3})/i) : null);

      const isSeriesEp = Boolean(epMatch || parsed.episodeNum > 0 || seasonFolders.length > 0);
      const searchTitle = (isSeriesEp && seriesParentTitle)
        ? seriesParentTitle
        : (parsed.searchTitle || parsed.cleanTitle || rawName.replace(/\.[^.]+$/, ''));
      const year = parsed.year || selectedFile?._folderYear || '';
      const effectiveLangCode = parsed.langCode || selectedFile?._folderLangCode || '';
      const cacheKey = `${searchTitle}_${year}_${isSeriesEp ? 'tv' : 'mv'}`.toLowerCase();

      setTrailerState({ loading: true, available: false, cacheKey, bestQuality: '', title: searchTitle || 'Official Trailer' });
      if (!isSeriesEp) {
        setEpisodeTmdbMeta(null);
      }

      const tmdbYtVideos: Array<{ key: string; lang: string; name: string; official: boolean }> = [];
      let imdbId = '';

      try {
        if (tmdbKey && searchTitle) {
          if (isSeriesEp) {
            const sNum = epMatch ? parseInt(epMatch[1], 10) : (activeSeason?.seasonNum || 1);
            const eNum = epMatch ? parseInt(epMatch[2], 10) : (parsed.episodeNum || 1);
            const codeStr = `S${String(sNum).padStart(2, '0')}E${String(eNum).padStart(2, '0')}`;

            const tvHit = await searchTmdbTv(tmdbKey.trim(), searchTitle, parsed.year, parsed.yearExplicit, parsed.langCode);
            const tvId = tvHit?.id;
            if (tvHit) propagateShowMetadataToParent(tvHit, searchTitle);

            if (tvId && !cancelled) {
              try {
                const epUrl = `https://api.themoviedb.org/3/tv/${tvId}/season/${sNum}/episode/${eNum}?api_key=${encodeURIComponent(tmdbKey)}`;
                const epData = await catalogJson(epUrl);
                if (!cancelled && epData && (epData.vote_average || epData.overview || epData.name)) {
                  setEpisodeTmdbMeta({
                    rating: epData.vote_average > 0 ? Number(epData.vote_average).toFixed(1) : null,
                    overview: epData.overview || null,
                    epTitle: epData.name || null,
                    code: codeStr,
                  });
                }
              } catch (_e) {}

              try {
                const tvDetailUrl = `https://api.themoviedb.org/3/tv/${tvId}?api_key=${encodeURIComponent(tmdbKey)}&append_to_response=videos,external_ids&include_video_language=en,ta,ml,hi,te,kn,ja,ko,null`;
                const tvDetail = await catalogJson(tvDetailUrl);
                const origLang = String(tvDetail?.original_language || tvHit?.original_language || parsed.langCode || '').toLowerCase();
                imdbId = `${tvId}|tv|${origLang}`;
                const vids = (tvDetail?.videos?.results || []).filter(
                  (v: any) => v.site === 'YouTube' && v.key && (v.type === 'Trailer' || v.type === 'Teaser')
                );
                vids.sort((a: any, b: any) => {
                  const aOrig = origLang && String(a.iso_639_1 || '').toLowerCase() === origLang ? 25 : 0;
                  const bOrig = origLang && String(b.iso_639_1 || '').toLowerCase() === origLang ? 25 : 0;
                  const aScore = aOrig + (a.type === 'Trailer' ? 10 : 0) + (a.official ? 5 : 0);
                  const bScore = bOrig + (b.type === 'Trailer' ? 10 : 0) + (b.official ? 5 : 0);
                  return bScore - aScore;
                });
                for (const v of vids) {
                  if (v.key && !tmdbYtVideos.some(x => x.key === v.key)) {
                    tmdbYtVideos.push({
                      key: v.key,
                      lang: String(v.iso_639_1 || origLang || 'en').toLowerCase(),
                      name: String(v.name || ''),
                      official: Boolean(v.official),
                    });
                  }
                }
              } catch (_e) {}
            }
          } else {
            const mvSearchUrl = `https://api.themoviedb.org/3/search/movie?api_key=${encodeURIComponent(tmdbKey)}&query=${encodeURIComponent(searchTitle)}${year ? '&year=' + year : ''}`;
            const mvRes = await catalogJson(mvSearchUrl);
            const mvResults: any[] = mvRes?.results || [];
            const mvHit = selectBestTmdbMovieMatch(mvResults, searchTitle, year, effectiveLangCode) ||
              (effectiveLangCode ? mvResults.find(r => r.original_language === effectiveLangCode) : null) ||
              mvResults[0];
            const movieId = mvHit?.id;
            if (movieId && !cancelled) {
              try {
                const mvDetailUrl = `https://api.themoviedb.org/3/movie/${movieId}?api_key=${encodeURIComponent(tmdbKey)}&append_to_response=videos,external_ids&include_video_language=en,ta,ml,hi,te,kn,ja,ko,null`;
                const mvDetail = await catalogJson(mvDetailUrl);
                const origLang = String(mvDetail?.original_language || mvHit?.original_language || parsed.langCode || '').toLowerCase();
                imdbId = `${movieId}|movie|${origLang}`;
                const vids = (mvDetail?.videos?.results || []).filter(
                  (v: any) => v.site === 'YouTube' && v.key && (v.type === 'Trailer' || v.type === 'Teaser')
                );
                vids.sort((a: any, b: any) => {
                  const aOrig = origLang && String(a.iso_639_1 || '').toLowerCase() === origLang ? 25 : 0;
                  const bOrig = origLang && String(b.iso_639_1 || '').toLowerCase() === origLang ? 25 : 0;
                  const aScore = aOrig + (a.type === 'Trailer' ? 10 : 0) + (a.official ? 5 : 0);
                  const bScore = bOrig + (b.type === 'Trailer' ? 10 : 0) + (b.official ? 5 : 0);
                  return bScore - aScore;
                });
                for (const v of vids) {
                  if (v.key && !tmdbYtVideos.some(x => x.key === v.key)) {
                    tmdbYtVideos.push({
                      key: v.key,
                      lang: String(v.iso_639_1 || origLang || 'en').toLowerCase(),
                      name: String(v.name || ''),
                      official: Boolean(v.official),
                    });
                  }
                }
              } catch (_e) {}
            }
          }
        }

        if (!cancelled && SecurityVault?.resolveVerifiedTrailer && searchTitle) {
          const verified = await SecurityVault.resolveVerifiedTrailer(
            cacheKey,
            searchTitle,
            year,
            imdbId,
            JSON.stringify(tmdbYtVideos)
          );
          if (!cancelled) {
            setTrailerState({
              loading: false,
              available: Boolean(verified?.available),
              cacheKey: verified?.cacheKey || cacheKey,
              bestQuality: verified?.bestQuality || '1080p FHD',
              title: searchTitle || 'Official Trailer',
            });
            return;
          }
        }
      } catch (_err) {}

      if (!cancelled) {
        setTrailerState({
          loading: false,
          available: false,
          cacheKey,
          bestQuality: '',
          title: searchTitle || 'Official Trailer',
        });
      }
    };

    const deferTimer = setTimeout(fetchEpisodeAndVerifiedTrailer, 350);
    return () => { cancelled = true; clearTimeout(deferTimer); };
  }, [selectedFile?.id, selectedFile?.name]);

  const selectedFileRef = useRef<any>(null);
  useEffect(() => { selectedFileRef.current = selectedFile; }, [selectedFile]);
  const [versionGroup, setVersionGroup] = useState<any[]>([]);
  const [playMode, setPlayMode] = useState<string | null>(null);
  useEffect(() => {
    if (!selectedFile && playMode === 'external') setPlayMode(null);
  }, [selectedFile, playMode]);
  const [resizeMode, setResizeMode] = useState<'contain' | 'cover'>('contain');
  const [zoomPct, setZoomPct] = useState(0);
  const [zoomHudVisible, setZoomHudVisible] = useState(false);
  const [showAudioBrowserModal, setShowAudioBrowserModal] = useState(false);
  const zoomPctRef = useRef(0);
  const pinchActiveRef = useRef(false);
  const pinchStartDistRef = useRef(1);
  const pinchStartZoomRef = useRef(0);
  const panXRef = useRef(0);
  const panYRef = useRef(0);
  const panStartRef = useRef({ x: 0, y: 0 });
  const zoomHideTimerRef = useRef<any>(null);
  const videoScaleAnim = useRef(new Animated.Value(1)).current;
  const videoPanXAnim = useRef(new Animated.Value(0)).current;
  const videoPanYAnim = useRef(new Animated.Value(0)).current;
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
  const zipRawJsonCacheRef = useRef<Record<string, string>>({});
  const zipPrewarmQueuedRef = useRef<Set<string>>(new Set());

  const fetchZipRawJsonFast = useCallback(async (url: string, sizeHint: number, cacheId?: string) => {
    const idMatch = url.match(/[?&]id=([a-zA-Z0-9_-]{15,})/) || url.match(/\/d\/([a-zA-Z0-9_-]{15,})/);
    const key = idMatch ? `gd_${idMatch[1]}` : (cacheId || `${url.split('?')[0]}|${sizeHint}`);
    const isIso = isIsoArchiveUrl(url);
    if (!isIso && zipRawJsonCacheRef.current[key]) {
      return zipRawJsonCacheRef.current[key];
    }
    const scan = (): Promise<string> => SecurityVault.listZipMediaEntries(url, sizeHint);
    const rawJson = isIso ? await withArchiveTimeout(scan()) : await inCatalogSlot(scan);
    if (typeof rawJson === 'string' && rawJson.startsWith('[') && rawJson.length > 2) {
      zipRawJsonCacheRef.current[key] = rawJson;
    }
    return rawJson;
  }, []);

  const progressBarRef = useRef<any>(null);
  const progressBarMetrics = useRef<{ pageX: number; width: number }>({ pageX: 0, width: 0 });
  const [isScrubbing, setIsScrubbing] = useState(false);
  const isScrubbingRef = useRef(false);
  const [scrubTime, setScrubTime] = useState(0);
  const scrubTimeRef = useRef(0);
  const [previewFrameUri, setPreviewFrameUri] = useState<string | null>(null);
  const previewCacheRef = useRef<Record<number, string>>({});
  const prewarmedUrlRef = useRef<string>('');
  const lastPreviewBucketRef = useRef<number>(-1);
  const lastScrubPageXRef = useRef<number>(-1);

  const findNearestCachedPreview = useCallback((sec: number): string | null => {
    const cache = previewCacheRef.current;
    const targetBucket = Math.floor(sec / 4);
    if (cache[targetBucket]) return cache[targetBucket];
    let bestUri: string | null = null;
    let bestDist = Infinity;
    for (const k of Object.keys(cache)) {
      const b = Number(k);
      const dist = Math.abs(b - targetBucket);
      if (dist < bestDist) {
        bestDist = dist;
        bestUri = cache[b];
      }
    }
    return bestUri;
  }, []);

  const getStreamUrl = useCallback((file: any) => {
    if (!file) return '';
    if (file.isZipEntry && file.archiveFormat === 'ISO' && file.link) return file.link;
    if (file.localPath) return file.localPath.startsWith('file://') ? file.localPath : `file://${file.localPath}`;
    const completedDl = downloads.find(d => d.fileName === file.name && d.status === 'completed' && d.filePath);
    if (completedDl) return `file://${completedDl.filePath}`;
    const baseUrl = vaultRef.current.baseUrl;
    return getPlayableUrl(baseUrl, file._parentPath || currentPath, file.name, file.link);
  }, [downloads, currentPath]);

  const [preparedUrl, setPreparedUrl] = useState('');
  const playbackIdentity = playMode === 'internal'
    ? `${selectedFile?.id || selectedFile?.name || ''}:${playerInstanceKey}` : null;
  const playbackSource = usePlaybackSource(preparedUrl || getStreamUrl(selectedFile), playbackIdentity);

  const requestPreviewAtTime = useCallback((sec: number) => {
    const active = selectedFileRef.current;
    if (!active) return;
    const bucket = Math.floor(sec / 4);
    const instantUri = findNearestCachedPreview(sec);
    if (instantUri) {
      setPreviewFrameUri(instantUri);
    }
    if (bucket === lastPreviewBucketRef.current) return;
    lastPreviewBucketRef.current = bucket;
    if (previewCacheRef.current[bucket]) {
      setPreviewFrameUri(previewCacheRef.current[bucket]);
      return;
    }
    const streamUrl = getStreamUrl(active);
    if (!streamUrl || !MediaInfoModule?.extractPreviewFrame) return;
    MediaInfoModule.extractPreviewFrame(streamUrl, sec * 1000)
      .then((uri: string | null) => {
        if (uri) {
          previewCacheRef.current[bucket] = uri;
          if (isScrubbingRef.current && lastPreviewBucketRef.current === bucket) {
            setPreviewFrameUri(uri);
          }
        }
      })
      .catch(() => {});
  }, [getStreamUrl, findNearestCachedPreview]);

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
  const dvdPlayerUrl = playMode === 'internal' && selectedFile?.archiveFormat === 'ISO' &&
    /\/(?:dvd\.m3u8|[^/?]+\.vob)(?:$|[?#])/i.test(playbackSource.uri) ? playbackSource.uri : '';
  const [dvdCatalog, setDvdCatalog] = useState<{url: string; tracks: string[]}>();
  const dvdTracks = dvdCatalog?.url === dvdPlayerUrl ? dvdCatalog.tracks : [];
  const [dvdAspect, setDvdAspect] = useState(0);
  useEffect(() => {
    let active = true;
    if (dvdPlayerUrl) SecurityVault.dvdSubtitleTracks(dvdPlayerUrl)
      .then((tracks: string[]) => { if (active) setDvdCatalog({url: dvdPlayerUrl, tracks}); }).catch(() => {});
    return () => { active = false; };
  }, [dvdPlayerUrl]);
  const dvdSelected = !!dvdPlayerUrl && selectedText?.type === 'dvd';
  const subtitlesActive = !dvdSelected && !!selectedText && selectedText.type !== 'disabled' && selectedText.type !== 'external';

  const parsedExternalCues = useMemo(() => externalSubtitle?.content ? parseSubtitleFile(externalSubtitle.content) : [], [externalSubtitle]);
  const activeExternalSubtitleText = useMemo(() => {
    if (!isExternalSubtitleSelected || !parsedExternalCues.length) return '';
    const effectiveTime = currentTime - subtitleDelay;
    const cue = parsedExternalCues.find(c => effectiveTime >= c.start && effectiveTime <= c.end);
    return cue ? cue.text : '';
  }, [isExternalSubtitleSelected, parsedExternalCues, currentTime, subtitleDelay]);

  const [seekOverlay, setSeekOverlay] = useState({ visible: false, icon: '', time: 0, position: 'center', burstKey: 0 });
  const seekBurstCounterRef = useRef(0);
  const detailScrollY = useRef(new Animated.Value(0)).current;
  const accumulatedSeek = useRef(0);
  const seekBaseTime = useRef<number | null>(null);
  const multiTapTimer = useRef<any>(null);
  const singleTapTimer = useRef<any>(null);
  const lastTapTime = useRef(0);
  const lastTapDirection = useRef<boolean | null>(null);

  const historyAliasesRef = useRef<Record<string, string[]>>({});
  const removedHistoryRef = useRef(new Set<string>());
  const historyWriteRef = useRef(Promise.resolve());
  const queueHistoryWrite = useCallback((write: () => Promise<void>) => {
    const pending = historyWriteRef.current.then(write);
    historyWriteRef.current = pending.catch(error => {
      SecurityVault?.writeDebugLog?.('WATCH_HISTORY_ERR', String(error));
    });
    return pending;
  }, []);
  const writeHistorySnapshot = useCallback(() => AsyncStorage.setItem(CW_STORAGE_KEY, JSON.stringify({
    times: savedTimesRef.current, durations: savedDurationsRef.current,
    aliases: historyAliasesRef.current, removed: [...removedHistoryRef.current],
  })), []);

  // Serialize native + JS writes so an older progress update cannot overtake removal.
  const persistPlaybackProgress = useCallback((fileName: string | undefined, sec: number, dur: number) => {
    if (!fileName || sec <= 3 || removedHistoryRef.current.has(fileName)) return;
    if (dur > 0 && dur - sec < 90) {
      delete savedTimesRef.current[fileName];
      setSavedTimesMap({ ...savedTimesRef.current });
      void queueHistoryWrite(async () => {
        await VideoPlayerManager?.removeWatchProgress?.(fileName);
        await AsyncStorage.removeItem(`time_${fileName}`);
        await writeHistorySnapshot();
      }).catch(() => {});
      return;
    }
    savedTimesRef.current = { ...savedTimesRef.current, [fileName]: sec };
    setSavedTimesMap(savedTimesRef.current);
    if (dur > 0) {
      savedDurationsRef.current = { ...savedDurationsRef.current, [fileName]: dur };
      setSavedDurationsMap(savedDurationsRef.current);
    }
    void queueHistoryWrite(async () => {
      if (removedHistoryRef.current.has(fileName)) return;
      await VideoPlayerManager?.saveWatchProgress?.(fileName, sec, dur);
      await AsyncStorage.setItem(`time_${fileName}`, sec.toString());
      if (dur > 0) await AsyncStorage.setItem(`dur_${fileName}`, dur.toString());
      await writeHistorySnapshot();
    }).catch(() => {});
  }, [queueHistoryWrite, writeHistorySnapshot]);

  const removeContinueWatchingItem = useCallback((fileToRemove: any) => {
    const item = { ...fileToRemove, metadata: metadataMap[fileToRemove.name] };
    const purge = (keys: string[]) => {
      for (const key of keys) {
        removedHistoryRef.current.add(key);
        delete savedTimesRef.current[key];
        delete savedDurationsRef.current[key];
      }
      setSavedTimesMap({ ...savedTimesRef.current });
      setSavedDurationsMap({ ...savedDurationsRef.current });
    };
    purge(linkedWatchKeys(item, Object.keys(savedTimesRef.current), historyAliasesRef.current));
    void queueHistoryWrite(async () => {
      const source = archiveSourceUrl(item.name || '', vaultRef.current.baseUrl, currentPath, item.link || item.url || '');
      const [stored, native, storageKeys, nativeAliases] = await Promise.all([
        AsyncStorage.getItem(CW_STORAGE_KEY), VideoPlayerManager?.getAllWatchProgress?.(), AsyncStorage.getAllKeys(),
        /\.iso$/i.test(item.name || '') ? SecurityVault?.getArchiveWatchAliases?.(source, Number(item.size || 0)) : [],
      ]);
      item.historyAliases = [...(item.historyAliases || []), ...(nativeAliases || [])];
      let old: any = {};
      try { old = JSON.parse(stored || '{}'); } catch { /* empty/corrupt legacy index */ }
      historyAliasesRef.current = { ...old.aliases, ...historyAliasesRef.current };
      const known = [...Object.keys(old.times || {}), ...Object.keys(old.durations || {}),
        ...Object.keys(native?.times || {}), ...Object.keys(native?.durations || {}),
        ...storageKeys.filter(key => /^(time_|dur_)/.test(key)).map(key => key.replace(/^(time_|dur_)/, ''))];
      const keys = linkedWatchKeys(item, known, historyAliasesRef.current);
      purge(keys);
      // Commit tombstones before deleting legacy stores; interrupted cleanup cannot resurrect them.
      await writeHistorySnapshot();
      for (const key of keys) await VideoPlayerManager?.removeWatchProgress?.(key);
      await AsyncStorage.removeMany(keys.flatMap(key => [`time_${key}`, `dur_${key}`]));
    }).catch(error => Alert.alert('Watch history', `Could not finish clearing saved progress: ${String(error)}`));
  }, [metadataMap, currentPath, queueHistoryWrite, writeHistorySnapshot]);

  // Load Watch Progress from Native Disk + AsyncStorage on startup
  const restoreAllSavedWatchHistory = useCallback(async () => {
    try {
      await historyWriteRef.current;
      const mergedTimes: Record<string, number> = { ...savedTimesRef.current };
      const mergedDurs: Record<string, number> = { ...savedDurationsRef.current };

      const cwRaw = await AsyncStorage.getItem(CW_STORAGE_KEY);
      if (cwRaw) {
        try {
          const parsed = JSON.parse(cwRaw);
          historyAliasesRef.current = { ...parsed.aliases, ...historyAliasesRef.current };
          for (const key of parsed.removed || []) removedHistoryRef.current.add(key);
          if (parsed?.times) Object.assign(mergedTimes, parsed.times);
          if (parsed?.durations) Object.assign(mergedDurs, parsed.durations);
        } catch {}
      }

      if (VideoPlayerManager?.getAllWatchProgress) {
        try {
          const nativeRes = await VideoPlayerManager.getAllWatchProgress();
          if (nativeRes?.times) Object.assign(mergedTimes, nativeRes.times);
          if (nativeRes?.durations) Object.assign(mergedDurs, nativeRes.durations);
        } catch {}
      }

      for (const key of removedHistoryRef.current) { delete mergedTimes[key]; delete mergedDurs[key]; }
      savedTimesRef.current = mergedTimes;
      savedDurationsRef.current = mergedDurs;
      setSavedTimesMap({ ...mergedTimes });
      setSavedDurationsMap({ ...mergedDurs });
    } catch {}
  }, []);

  useEffect(() => {
    if (IS_TV) Orientation.lockToLandscape();
    restoreAllSavedWatchHistory();
    AsyncStorage.getItem('gdstream_watchlist_v1').then(s => { if (s) setWatchlist(JSON.parse(s)); }).catch(() => {});
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

    const appStateSub = AppState.addEventListener('change', nextState => {
      if (nextState === 'active' || nextState === 'background') {
        VideoPlayerManager?.setArchiveBridgeAppForeground?.(nextState === 'active');
      }
      const activeFile = selectedFileRef.current;
      if ((nextState === 'background' || nextState === 'inactive') && activeFile?.name && currentTimeRef.current > 3) {
        persistPlaybackProgress(activeFile.name, currentTimeRef.current, durationRef.current);
      }
    });

    let mounted = true;
    const hydrateColdBootCache = async () => {
      try {
        const lastRootKey = await AsyncStorage.getItem('gdstream_last_root_dir_key_v1');
        if (!mounted || !lastRootKey) return;
        const raw = await AsyncStorage.getItem(lastRootKey);
        if (!mounted || !raw) return;
        const cache = JSON.parse(raw);
        if (!Array.isArray(cache?.files) || cache.files.length === 0) return;

        setHomeFiles(cache.files);
        setLoading(false);
        setSyncingCatalog(true);

        const metaKeys: string[] = Array.from(new Set<string>(
          cache.files
            .filter((f: any) => f?.name)
            .map((f: any) => metadataCacheKey(parseMediaFilename(String(f.name), isSeriesContainerItem(f)))),
        ));
        if (metaKeys.length > 0) {
          const pairs = await readStoragePairs(metaKeys);
          if (!mounted) return;
          const hydratedMeta: Record<string, MediaMetadata> = {};
          const lookup: Record<string, MediaMetadata> = {};
          for (const [k, v] of pairs) {
            if (v) {
              try { lookup[k] = JSON.parse(v); } catch {}
            }
          }
          for (const f of cache.files) {
            if (!f?.name) continue;
            const info = parseMediaFilename(String(f.name), isSeriesContainerItem(f));
            const hit = lookup[metadataCacheKey(info)];
            if (hit && isUsableCachedMetadata(hit, info)) {
              hydratedMeta[f.name] = hit;
            }
          }
          if (Object.keys(hydratedMeta).length > 0) {
            setMetadataMap(prev => ({ ...hydratedMeta, ...prev }));
          }
        }
      } catch {}
    };

    const initVaultAndFetch = async () => {
      const cacheHydration = hydrateColdBootCache();
      try {
        if (SecurityVault?.getVaultConfig) {
          const cfg = await SecurityVault.getVaultConfig();
          if (cfg?.s || cfg?.compromised) { setSecurityCompromised(true); setLoading(false); return; }
          vaultRef.current = {
            baseUrl: cfg?.a || cfg?.baseUrl || '', username: cfg?.b || cfg?.username || '',
            password: cfg?.c || cfg?.password || '', tmdbKey: cfg?.d || cfg?.tmdbKey || '',
          };
        }
        await cacheHydration;
        if (!mounted) return;
        setVaultReady(true);
        setNavigationReady(true);
        void fetchDirectory('/0:/');
      } catch {
        if (mounted) { setNavigationReady(true); setLoading(false); setSyncingCatalog(false); }
      }
    };
    initVaultAndFetch();
    return () => {
      mounted = false;
      archiveOpenRequestRef.current++;
      directoryRequest.current++;
      dlSub.remove();
      appStateSub.remove();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
    await restoreAllSavedWatchHistory();
    if (!items.length) return;
    try {
      const timeKeys = items.map(f => `time_${f.name}`);
      const durKeys = items.map(f => `dur_${f.name}`);
      const miKeys = items.filter(f => !standaloneProbeCandidate(f)).map(f => `mediainfo_v8_${f.name}`);
      const [timePairs, durPairs, miPairs] = await Promise.all([
        readStoragePairs(timeKeys),
        readStoragePairs(durKeys),
        readStoragePairs(miKeys),
      ]);
      let changedTimes = false;
      timePairs.forEach(([k, val]) => {
        if (val) {
          const parsed = parseFloat(val);
          const fName = k.replace(/^time_/, '');
          if (parsed > 3 && !removedHistoryRef.current.has(fName) && !savedTimesRef.current[fName]) {
            savedTimesRef.current[fName] = parsed;
            changedTimes = true;
          }
        }
      });
      if (changedTimes) setSavedTimesMap({ ...savedTimesRef.current });

      let changedDurs = false;
      durPairs.forEach(([k, val]) => {
        if (val) {
          const parsed = parseFloat(val);
          const fName = k.replace(/^dur_/, '');
          if (parsed > 0 && !removedHistoryRef.current.has(fName) && !savedDurationsRef.current[fName]) {
            savedDurationsRef.current[fName] = parsed;
            changedDurs = true;
          }
        }
      });
      if (changedDurs) setSavedDurationsMap({ ...savedDurationsRef.current });

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
  }, [restoreAllSavedWatchHistory]);

  const resetPlayerZoom = useCallback((immediate = false) => {
    clearTimeout(zoomHideTimerRef.current);
    zoomPctRef.current = 0;
    panXRef.current = 0;
    panYRef.current = 0;
    pinchActiveRef.current = false;
    setZoomPct(0);
    setZoomHudVisible(false);
    if (immediate) {
      videoScaleAnim.setValue(1);
      videoPanXAnim.setValue(0);
      videoPanYAnim.setValue(0);
    } else {
      Animated.parallel([
        Animated.spring(videoScaleAnim, { toValue: 1, speed: 24, bounciness: 5, useNativeDriver: true }),
        Animated.spring(videoPanXAnim, { toValue: 0, speed: 24, bounciness: 5, useNativeDriver: true }),
        Animated.spring(videoPanYAnim, { toValue: 0, speed: 24, bounciness: 5, useNativeDriver: true }),
      ]).start();
    }
  }, [videoScaleAnim, videoPanXAnim, videoPanYAnim]);

  const playerVisible = playMode === 'internal' && selectedFile != null;
  useEffect(() => {
    if (playerVisible) return () => VideoPlayerManager?.resetWindowBrightness?.();
  }, [playerVisible]);

  const closeInternalPlayer = useCallback((cancelProxy: unknown = true) => {
    setIsPaused(true);
    // clearSrc stops ExoPlayer and clears its media items before the view drops.
    videoRef.current?.setSource?.();
    externalAudioRef.current?.setSource?.();
    VideoPlayerManager?.releasePlaybackAudioSession?.();
    if (cancelProxy !== false) SecurityVault?.stopArchivePlayback?.(playbackSource.uri);
    if (selectedFile?.name && currentTimeRef.current > 3) {
      persistPlaybackProgress(selectedFile.name, currentTimeRef.current, durationRef.current);
    }
    if (!IS_TV) {
      Orientation.lockToPortrait();
      setIsFullscreen(false);
    }
    setShowAudioBrowserModal(false);
    resetPlayerZoom(true);
    setIsLocked(false);
    isScrubbingRef.current = false;
    setIsScrubbing(false);
    setPreviewFrameUri(null);
    previewCacheRef.current = {};
    prewarmedUrlRef.current = '';
    try { MediaInfoModule?.releasePreviewExtractor?.(); } catch {}
    setActiveMenu(null);
    setIsVideoBuffering(false);
    setPlayMode(null);
  }, [selectedFile, persistPlaybackProgress, playbackSource.uri]);

  const isoProgressAtRef = useRef(0);
  const isIsoPlayback = selectedFile?.archiveFormat === 'ISO';
  const failIsoPlayback = useCallback((message: string) => {
    closeInternalPlayer();
    Alert.alert('Disc playback failed', message);
  }, [closeInternalPlayer]);

  useEffect(() => {
    if (!isIsoPlayback || playMode !== 'internal' || isPaused) return;
    isoProgressAtRef.current = Date.now();
    const timer = setInterval(() => {
      if (Date.now() - isoProgressAtRef.current > 150000) {
        clearInterval(timer);
        failIsoPlayback('The disc stream stopped responding. Reopen the disc and try again.');
      }
    }, 5000);
    return () => clearInterval(timer);
  }, [isIsoPlayback, playMode, isPaused, playerInstanceKey, failIsoPlayback]);

  useEffect(() => {
    const backAction = () => {
      if (showSeasonModal) { setShowSeasonModal(false); return true; }
      if (showHdrChoiceModal) { setShowHdrChoiceModal(false); return true; }
      if (playMode === 'internal') {
        if (showAudioBrowserModal) { setShowAudioBrowserModal(false); return true; }
        if (isLocked) { setShowControls(true); return true; }
        if (activeMenu) { setActiveMenu(null); return true; }
        closeInternalPlayer();
        return true;
      } else if (selectedFile) {
        closeDetails();
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
  }, [playMode, selectedFile, pathStack, activeMenu, showHdrChoiceModal, showSeasonModal, showAudioBrowserModal, isLocked, activeTab, closeInternalPlayer]);

  const searchTmdbTv = useCallback(async (
    key: string,
    searchTitle: string,
    year = '',
    yearExplicit = false,
    langCode = '',
  ): Promise<any | null> => {
    const queries = buildTvSearchQueries(searchTitle);
    for (let i = 0; i < queries.length; i++) {
      const q = queries[i];
      if (i === 0 && yearExplicit && year) {
        try {
          const withYearUrl = `https://api.themoviedb.org/3/search/tv?api_key=${encodeURIComponent(key)}&query=${encodeURIComponent(q)}&first_air_date_year=${encodeURIComponent(year)}`;
          const yearRes = await catalogJson(withYearUrl);
          const yearHit = selectBestTmdbTvMatch(yearRes?.results || [], searchTitle, year, true, langCode);
          if (yearHit) return yearHit;
        } catch {}
      }
      try {
        const tvUrl = `https://api.themoviedb.org/3/search/tv?api_key=${encodeURIComponent(key)}&query=${encodeURIComponent(q)}`;
        const res = await catalogJson(tvUrl);
        const hit = selectBestTmdbTvMatch(res?.results || [], searchTitle, year, yearExplicit, langCode);
        if (hit) return hit;
      } catch {}
    }
    return null;
  }, []);

  const propagateShowMetadataToParent = useCallback((tvObj: any, showTitle: string) => {
    if (!tvObj?.poster_path) return;
    const showMeta: MediaMetadata = {
      title: tvObj.name || tvObj.original_name || showTitle,
      tmdbId: tvObj.id,
      originalLanguage: tvObj.original_language,
      mediaType: 'tv',
      poster: `https://image.tmdb.org/t/p/w342${tvObj.poster_path}`,
      backdrop: tvObj.backdrop_path ? `https://image.tmdb.org/t/p/w780${tvObj.backdrop_path}` : undefined,
      rating: tvObj.vote_average && tvObj.vote_average > 0 ? Number(tvObj.vote_average).toFixed(1) : undefined,
      overview: tvObj.overview || undefined,
      year: String(tvObj.first_air_date || '').slice(0, 4) || undefined,
    };

    const candidateNames = new Set<string>();
    const cleanShow = String(showTitle || '').trim();
    if (cleanShow) candidateNames.add(cleanShow);

    const pathCandidates = [...pathStack, currentPath];
    for (const p of pathCandidates) {
      const segs = String(p || '').split('/').filter(Boolean);
      const rootIdx = segs.findIndex(s => /^\d+:$/.test(s));
      const rel = rootIdx >= 0 ? segs.slice(rootIdx + 1) : segs;
      for (const seg of rel) {
        try {
          const decoded = decodeURIComponent(seg);
          if (decoded && extractSeasonNumberFromFolder(decoded) === null) {
            candidateNames.add(decoded);
          }
        } catch {}
      }
    }

    const showNorm = cleanShow.toLowerCase();
    for (const hf of homeFiles) {
      if (!hf?.mimeType?.includes('folder') || !hf?.name) continue;
      const p = parseMediaFilename(String(hf.name), true);
      if (p.searchTitle.toLowerCase() === showNorm || p.cleanTitle.toLowerCase() === showNorm) {
        candidateNames.add(String(hf.name));
      }
    }

    const updates: Record<string, MediaMetadata> = {};
    for (const name of candidateNames) {
      updates[name] = showMeta;
      const parentInfo = parseMediaFilename(name, true);
      void AsyncStorage.setItem(metadataCacheKey(parentInfo), JSON.stringify(showMeta)).catch(() => {});
    }
    if (Object.keys(updates).length > 0) {
      setMetadataMap(prev => ({ ...prev, ...updates }));
    }
  }, [pathStack, currentPath, homeFiles]);

  const fetchSingleMetadata = useCallback(async (info: ParsedMediaInfo, needLanguage = false): Promise<MediaMetadata | null> => {
    const isTvQuery = Boolean(info.seasonEpisode || info.seasonNum > 0 || info.isFolder);
    const cacheKey = metadataCacheKey(info);
    try {
      const cached = await AsyncStorage.getItem(cacheKey);
      if (cached) {
        const previous = JSON.parse(cached);
        if (isUsableCachedMetadata(previous, info) && (!needLanguage || previous.originalLanguage !== undefined)) {
          return previous;
        }
      }
    } catch {}
    if (!isTvQuery) {
      void AsyncStorage.removeMany(legacyMetadataCacheKeys(info)).catch(() => {});
    }

    const explicitYear = info.yearExplicit ? info.year : '';
    const infoYearNum = explicitYear ? parseInt(explicitYear, 10) : 0;
    const tmdbKey = vaultRef.current.tmdbKey;

    if (tmdbKey && tmdbKey.trim().length >= 20) {
      try {
        const key = tmdbKey.trim();
        let match: any = null;

        if (!isTvQuery) {
          const mvYearUrl = `https://api.themoviedb.org/3/search/movie?api_key=${encodeURIComponent(key)}&query=${encodeURIComponent(info.searchTitle)}${explicitYear ? `&year=${encodeURIComponent(explicitYear)}` : ''}`;
          const mvRes = await catalogJson(mvYearUrl);
          match = selectBestTmdbMovieMatch(mvRes?.results || [], info.searchTitle, explicitYear, info.langCode);

          if (!match && infoYearNum > 0) {
            const primaryYearUrl = `https://api.themoviedb.org/3/search/movie?api_key=${encodeURIComponent(key)}&query=${encodeURIComponent(info.searchTitle)}&primary_release_year=${encodeURIComponent(explicitYear)}`;
            const primaryRes = await catalogJson(primaryYearUrl);
            match = selectBestTmdbMovieMatch(primaryRes?.results || [], info.searchTitle, explicitYear, info.langCode);
          }

          if (!match && infoYearNum > 0) {
            const anyYearUrl = `https://api.themoviedb.org/3/search/movie?api_key=${encodeURIComponent(key)}&query=${encodeURIComponent(info.searchTitle)}`;
            const anyYearRes = await catalogJson(anyYearUrl);
            match = selectBestTmdbMovieMatch(anyYearRes?.results || [], info.searchTitle, explicitYear, info.langCode);
          }
        } else {
          match = await searchTmdbTv(key, info.searchTitle, explicitYear, info.yearExplicit, info.langCode);
        }

        if (match) {
          const meta: MediaMetadata = {
            title: match.title || match.name,
            tmdbId: match.id,
            originalLanguage: match.original_language,
            mediaType: isTvQuery ? 'tv' : 'movie',
            poster: match.poster_path ? `https://image.tmdb.org/t/p/w342${match.poster_path}` : undefined,
            backdrop: match.backdrop_path ? `https://image.tmdb.org/t/p/w780${match.backdrop_path}` : undefined,
            rating: match.vote_average && match.vote_average > 0 ? Number(match.vote_average).toFixed(1) : undefined,
            overview: match.overview || undefined,
            year: (match.release_date || match.first_air_date || '').slice(0, 4) || explicitYear,
          };
          if (meta.poster) {
            await AsyncStorage.setItem(cacheKey, JSON.stringify(meta));
            return meta;
          }
          if (meta.overview) return meta;
        }
      } catch {}
    }

    if (needLanguage) return null;
    try {
      const query = `${info.searchTitle} ${explicitYear}`.trim();
      const cleanQ = query.toLowerCase().replace(/[^a-z0-9\s]/g, '').trim();
      const firstChar = cleanQ.charAt(0) || 'a';
      const imdbUrl = `https://v3.sg.media-imdb.com/suggestion/${firstChar}/${encodeURIComponent(cleanQ.slice(0, 35))}.json`;
      const res = await catalogJson(imdbUrl);
      const list: any[] = res?.d || [];
      const validItems = list.filter((item: any) => item?.id && String(item.id).startsWith('tt') && item?.i?.imageUrl);
      const match =
        validItems.find((item: any) => infoYearNum > 0 && item.y && Math.abs(Number(item.y) - infoYearNum) <= 1) ||
        validItems[0];

      if (match && match.i?.imageUrl) {
        const rawImg: string = match.i.imageUrl;
        const meta: MediaMetadata = {
          mediaType: isTvQuery ? 'tv' : 'movie',
          poster: rawImg.replace(/\._V1_.*\.jpg$/i, '._V1_QL75_UX500_.jpg'),
          backdrop: rawImg.replace(/\._V1_.*\.jpg$/i, '._V1_QL75_UX1000_.jpg'),
          year: match.y ? String(match.y) : explicitYear,
          cast: match.s || undefined,
          overview: match.s ? `${match.q ? `${match.q.toUpperCase()} • ` : ''}Starring: ${match.s}` : undefined,
        };
        if (meta.poster) {
          await AsyncStorage.setItem(cacheKey, JSON.stringify(meta));
        }
        return meta;
      }
    } catch {}
    return null;
  }, [searchTmdbTv]);

  const standaloneSources = useRef(new Map<string, Promise<string>>());
  const resolvePlaybackUrl = useCallback(async (file: any) => {
    const listedUrl = getStreamUrl(file);
    const signedDisc = !file?.isZipEntry && /\/download\.aspx(?:[?#]|$)/i.test(listedUrl) &&
      (/\.(m2ts|vob)$/i.test(file?.name || '') || /mp2t/i.test(file?.mimeType || ''));
    const url = signedDisc && vaultRef.current.baseUrl
      ? getPlayableUrl(vaultRef.current.baseUrl, file._parentPath || currentPath, file.name) : listedUrl;
    if (file?.isZipEntry || !/^(https?|file):/i.test(url) || !SecurityVault?.resolveStandaloneDisc) return url;
    const name = String(file?.name || '');
    // Opaque download endpoints sometimes have no extension in either the path or title.
    const namedDisc = /\.(m2ts|vob)(?:$|[?#&])/i.test(url) || /\.(m2ts|vob)$/i.test(name);
    if (!namedDisc && !/mp2t/i.test(file?.mimeType || '') && /\.(mkv|mp4|avi|mov|webm|m4v|iso)$/i.test(name)) return url;
    const meta = file?.metadata ?? metadataMap[name];
    const originalLanguage = meta?.originalLanguage ?? (name.toLowerCase().endsWith('.m2ts') || /mp2t/i.test(file?.mimeType || '')
      ? (await fetchSingleMetadata(parseMediaFilename(name), true))?.originalLanguage : '') ?? '';
    const key = JSON.stringify([url, file?.size, name, originalLanguage]);
    let pending = standaloneSources.current.get(key);
    if (!pending) {
      pending = SecurityVault.resolveStandaloneDisc(url, name, Number(file?.size || 0), originalLanguage) as Promise<string>;
      standaloneSources.current.set(key, pending);
      pending.catch(() => standaloneSources.current.delete(key));
    }
    try { return await pending; } catch (error) {
      if (namedDisc) throw error;
      return url;
    }
  }, [getStreamUrl, currentPath, metadataMap, fetchSingleMetadata]);
  useEffect(() => {
    const rawList: any[] = Array.isArray(activeRawFiles) ? activeRawFiles : [];
    if (rawList.length === 0 || !vaultReady || playMode !== null) return;

    const pendingFolders = rawList.filter((f: any) => {
      if (!isAmbiguousDriveFolder(f) || !f?.name) return false;
      const subPath = folderPathForItem(f);
      return !folderClassificationsRef.current[subPath] && !folderClassifyingRef.current.has(subPath);
    });
    if (pendingFolders.length === 0) return;

    let cancelled = false;
    const qLower = searchQuery.trim().toLowerCase();

    const applyClassified = (item: any, subPath: string, classified: FolderClassification, batch: Record<string, FolderClassification>) => {
      if (classified.kind === 'movie') {
        const movieInfo = parseMediaFilename(String(item.name), false);
        const enrichedInfo: ParsedMediaInfo = {
          ...movieInfo,
          year: movieInfo.year || classified.year,
          langCode: movieInfo.langCode || classified.langCode,
          langName: movieInfo.langName || classified.langName,
        };
        const staleKeys = [
          ...legacyMetadataCacheKeys(movieInfo),
          ...legacyMetadataCacheKeys(enrichedInfo),
        ];
        void AsyncStorage.removeMany(Array.from(new Set(staleKeys))).catch(() => {});
        fetchedKeysRef.current.delete(String(item.name));
        setMetadataMap(prev => {
          if (prev[item.name]?.mediaType !== 'tv') return prev;
          const next = { ...prev };
          delete next[item.name];
          return next;
        });
      } else if (classified.kind === 'series') {
        const clean = String(classified.searchTitle || classified.title || '').trim().toUpperCase();
        if (clean && classified.minSeason > 0) {
          setSeriesSeasonRangeMap(prev => ({
            ...prev,
            [clean]: prev[clean]
              ? { min: Math.min(prev[clean].min, classified.minSeason), max: Math.max(prev[clean].max, classified.maxSeason) }
              : { min: classified.minSeason, max: classified.maxSeason },
          }));
        }
      }
      folderClassificationsRef.current = { ...folderClassificationsRef.current, [subPath]: classified };
      batch[subPath] = classified;
    };

    const inspectFolders = async () => {
      const toFetch: any[] = [];
      const cachedBatch: Record<string, FolderClassification> = {};

      try {
        const dirKeys = pendingFolders.map(f => directoryKey(folderPathForItem(f)));
        const cachedPairs = await readStoragePairs(dirKeys);
        if (cancelled) return;
        for (let i = 0; i < pendingFolders.length; i++) {
          const item = pendingFolders[i];
          const subPath = folderPathForItem(item);
          const raw = cachedPairs[i]?.[1];
          if (raw) {
            try {
              const parsed = JSON.parse(raw);
              if (Array.isArray(parsed?.files)) {
                const classified = classifyFolderContents(String(item.name), parsed.files, subPath);
                folderClassifyingRef.current.add(subPath);
                applyClassified(item, subPath, classified, cachedBatch);
                continue;
              }
            } catch {}
          }
          toFetch.push(item);
        }
      } catch {
        toFetch.push(...pendingFolders);
      }

      if (!cancelled && Object.keys(cachedBatch).length > 0) {
        setFolderClassifications(prev => ({ ...prev, ...cachedBatch }));
      }
      if (cancelled || toFetch.length === 0) return;

      toFetch.sort((a, b) => {
        if (qLower) {
          const aMatch = String(a?.name || '').toLowerCase().includes(qLower) ? 1 : 0;
          const bMatch = String(b?.name || '').toLowerCase().includes(qLower) ? 1 : 0;
          if (aMatch !== bMatch) return bMatch - aMatch;
        }
        const ma = a?.modifiedTime ? Date.parse(a.modifiedTime) || 0 : 0;
        const mb = b?.modifiedTime ? Date.parse(b.modifiedTime) || 0 : 0;
        return mb - ma;
      });

      let cursor = 0;
      const CONCURRENCY = 3;
      const runWorker = async () => {
        while (!cancelled && cursor < toFetch.length) {
          const item = toFetch[cursor++];
          if (!item?.name) continue;
          const subPath = folderPathForItem(item);
          if (folderClassificationsRef.current[subPath] || folderClassifyingRef.current.has(subPath)) continue;
          folderClassifyingRef.current.add(subPath);
          let committed = false;
          try {
            const subFiles = await requestRawDirectory(subPath);
            if (cancelled || !Array.isArray(subFiles)) continue;
            const classified = classifyFolderContents(String(item.name), subFiles, subPath);
            const singleBatch: Record<string, FolderClassification> = {};
            applyClassified(item, subPath, classified, singleBatch);
            committed = true;
            if (!cancelled) {
              setFolderClassifications(prev => ({ ...prev, ...singleBatch }));
            }
          } catch {
            // Allow retry on transient directory failure.
          } finally {
            if (!committed) folderClassifyingRef.current.delete(subPath);
          }
        }
      };
      await Promise.all(Array.from({ length: Math.min(CONCURRENCY, toFetch.length) }, () => runWorker()));
    };
    void inspectFolders();
    return () => { cancelled = true; };
  }, [activeRawFiles, vaultReady, playMode, folderPathForItem, searchQuery]);

  useEffect(() => {
    if (!activeRawFiles.length || !vaultReady || selectedFile || playMode !== null) return;
    let cancelled = false;

    const enrichAllFast = async () => {
      await loadSavedTimesForFiles(activeRawFiles);
      if (cancelled) return;

      const orderedItems = [...activeRawFiles].sort((a, b) => {
        const ma = a?.modifiedTime ? Date.parse(a.modifiedTime) || 0 : 0;
        const mb = b?.modifiedTime ? Date.parse(b.modifiedTime) || 0 : 0;
        return mb - ma;
      });

      const pendingItems: Array<{ itemKey: string; info: ParsedMediaInfo; cacheKey: string; versions?: any[] }> = [];
      for (const item of orderedItems) {
        if (!item?.name) continue;
        const itemKey = item.name;
        const classified = item?.mimeType?.includes('folder') ? folderClassifications[folderPathForItem(item)] : undefined;
        if (isAmbiguousDriveFolder(item)) {
          if (!classified || classified.kind === 'collection') continue;
        }
        if (classified?.kind === 'movie' && metadataMap[itemKey]?.mediaType === 'tv') {
          fetchedKeysRef.current.delete(itemKey);
        }
        if (fetchedKeysRef.current.has(itemKey) && metadataMap[itemKey]?.poster) continue;
        const isFolder = isSeriesContainerItem(item, classified);
        const rawInfo = parseMediaFilename(itemKey, isFolder);
        const info: ParsedMediaInfo = classified?.kind === 'movie'
          ? {
              ...rawInfo,
              year: rawInfo.year || classified.year,
              yearExplicit: rawInfo.yearExplicit || Boolean(classified.year),
              langCode: rawInfo.langCode || classified.langCode,
              langName: rawInfo.langName || classified.langName,
            }
          : rawInfo;
        const cacheKey = metadataCacheKey(info);
        pendingItems.push({ itemKey, info, cacheKey, versions: classified?.kind === 'movie' ? classified.versions : undefined });
      }

      if (!pendingItems.length) return;

      const uncachedQueue: Array<{ itemKey: string; info: ParsedMediaInfo; cacheKey: string; versions?: any[] }> = [];
      try {
        const uniqueCacheKeys = Array.from(new Set(pendingItems.map(p => p.cacheKey)));
        const cachedPairs = await readStoragePairs(uniqueCacheKeys);
        const cacheLookup: Record<string, MediaMetadata> = {};
        for (const [k, v] of cachedPairs) {
          if (v) {
            try { cacheLookup[k] = JSON.parse(v); } catch {}
          }
        }
        const instantBatch: Record<string, MediaMetadata> = {};
        for (const p of pendingItems) {
          const hit = cacheLookup[p.cacheKey];
          if (hit && isUsableCachedMetadata(hit, p.info)) {
            instantBatch[p.itemKey] = hit;
            if (p.versions) {
              for (const v of p.versions) {
                if (v?.name) instantBatch[v.name] = hit;
              }
            }
            fetchedKeysRef.current.add(p.itemKey);
          } else {
            uncachedQueue.push(p);
          }
        }
        if (!cancelled && Object.keys(instantBatch).length > 0) {
          setMetadataMap(prev => ({ ...prev, ...instantBatch }));
        }
      } catch {
        uncachedQueue.push(...pendingItems);
      }

      if (cancelled || !uncachedQueue.length) return;

      if (!cancelled) setIsEnrichingMetadata(true);
      const inflightByCacheKey = new Map<string, Promise<MediaMetadata | null>>();
      let stagedUpdates: Record<string, MediaMetadata> = {};
      let lastFlushTime = Date.now();

      const flushStaged = (force = false) => {
        if (cancelled || selectedFileRef.current) return;
        const keys = Object.keys(stagedUpdates);
        if (!keys.length) return;
        const now = Date.now();
        if (force || keys.length >= 8 || now - lastFlushTime >= 350) {
          const toApply = stagedUpdates;
          stagedUpdates = {};
          lastFlushTime = now;
          setMetadataMap(prev => ({ ...prev, ...toApply }));
        }
      };

      let cursor = 0;
      const CONCURRENCY = 3;
      const runWorker = async () => {
        while (!cancelled && cursor < uncachedQueue.length) {
          if (selectedFileRef.current) break;
          const current = uncachedQueue[cursor++];
          if (!current) break;
          let p = inflightByCacheKey.get(current.cacheKey);
          if (!p) {
            p = fetchSingleMetadata(current.info);
            inflightByCacheKey.set(current.cacheKey, p);
          }
          const meta = await p;
          if (meta && !cancelled) {
            if (meta.poster) fetchedKeysRef.current.add(current.itemKey);
            stagedUpdates[current.itemKey] = meta;
            if (current.versions) {
              for (const v of current.versions) {
                if (v?.name) stagedUpdates[v.name] = meta;
              }
            }
            flushStaged(false);
          }
        }
      };

      try {
        await Promise.all(Array.from({ length: Math.min(CONCURRENCY, uncachedQueue.length) }, () => runWorker()));
        flushStaged(true);
      } finally {
        if (!cancelled) setIsEnrichingMetadata(false);
      }
    };

    enrichAllFast();
    return () => {
      cancelled = true;
      setIsEnrichingMetadata(false);
    };
  }, [activeRawFiles, vaultReady, selectedFile, playMode, fetchSingleMetadata, loadSavedTimesForFiles, folderClassifications, folderPathForItem]);

  React.useEffect(() => {
    if (!vaultReady || playMode !== null || !isInsideFolder || !Array.isArray(folderFiles) || folderFiles.length === 0) return;
    const tmdbKey = (vaultRef.current?.tmdbKey || '').trim();
    if (tmdbKey.length < 20) return;

    const epEntries: Array<{ fileName: string; sNum: number; eNum: number; showTitle: string }> = [];
    for (const f of folderFiles) {
      if (!f?.name || f?.mimeType?.includes('folder')) continue;
      if (fetchedEpCardKeysRef.current.has(f.name)) continue;
      const p = parseMediaFilename(f.name, false);
      const m = f.name.match(/(?:[.\s_-]|\b)S(\d{1,2})[\s._-]*E(\d{1,3})\b/i)
        || (p.seasonEpisode ? p.seasonEpisode.match(/S(\d{1,2})E(\d{1,3})/i) : null);
      const sNum = m ? parseInt(m[1], 10) : (activeSeason?.seasonNum || p.seasonNum || 1);
      const eNum = m ? parseInt(m[2], 10) : (p.episodeNum || 0);
      const showTitle = (seriesParentTitle || p.searchTitle || p.cleanTitle || '').trim();
      if (eNum > 0 && showTitle) {
        epEntries.push({ fileName: f.name, sNum, eNum, showTitle });
      }
    }
    if (epEntries.length === 0) return;

    let cancelled = false;
    const fetchEpisodeStills = async () => {
      try {
        const primaryShow = epEntries[0].showTitle;
        const tvHit = await searchTmdbTv(tmdbKey, primaryShow);
        const tvId = tvHit?.id;
        if (!tvId || cancelled) return;
        propagateShowMetadataToParent(tvHit, primaryShow);

        const neededSeasons = Array.from(new Set(epEntries.map(e => e.sNum)));
        const seasonLookup: Record<string, { epTitle: string; stillUrl: string | null; runtimeMin: number }> = {};

        for (const sNum of neededSeasons) {
          if (cancelled) break;
          try {
            const sUrl = `https://api.themoviedb.org/3/tv/${tvId}/season/${sNum}?api_key=${encodeURIComponent(tmdbKey)}`;
            const sData = await catalogJson(sUrl);
            const eps: any[] = Array.isArray(sData?.episodes) ? sData.episodes : [];
            for (const ep of eps) {
              const epNo = Number(ep?.episode_number || 0);
              if (epNo > 0) {
                seasonLookup[`${sNum}_${epNo}`] = {
                  epTitle: String(ep?.name || '').trim(),
                  stillUrl: ep?.still_path ? `https://image.tmdb.org/t/p/w500${ep.still_path}` : null,
                  runtimeMin: Number(ep?.runtime || 0),
                };
              }
            }
          } catch {}
        }

        if (cancelled) return;
        const updates: Record<string, { epTitle: string; stillUrl: string | null; runtimeMin: number }> = {};
        for (const item of epEntries) {
          fetchedEpCardKeysRef.current.add(item.fileName);
          const hit = seasonLookup[`${item.sNum}_${item.eNum}`];
          if (hit && (hit.epTitle || hit.stillUrl)) {
            updates[item.fileName] = hit;
          }
        }
        if (Object.keys(updates).length > 0 && !cancelled) {
          setEpisodeCardsMetaMap(prev => ({ ...prev, ...updates }));
        }
      } catch {}
    };

    fetchEpisodeStills();
    return () => { cancelled = true; };
  }, [isInsideFolder, folderFiles, seriesParentTitle, activeSeason, vaultReady, playMode]);

  const buildProcessedList = useCallback((rawList: any[], isEpFolder: boolean) => {
    return processCatalogItems({
      rawList,
      isEpFolder,
      searchQuery,
      activeCategory,
      sortBy,
      folderClassifications,
      getFolderPath: folderPathForItem,
      mediaInfoMap,
      watchlist,
    });
  }, [searchQuery, activeCategory, sortBy, mediaInfoMap, watchlist, folderClassifications, folderPathForItem]);

  const homeDisplayedFiles = useMemo(() => buildProcessedList(homeFiles, false), [homeFiles, buildProcessedList]);
  const folderDisplayedFiles = useMemo(() => {
    const isEp = seasonFolders.length > 0 || (folderFiles.length > 1 && folderFiles.every(f => parseMediaFilename(f.name, false).episodeNum > 0));
    return buildProcessedList(folderFiles, isEp);
  }, [folderFiles, seasonFolders, buildProcessedList]);

  const displayedFiles = isInsideFolder ? folderDisplayedFiles : homeDisplayedFiles;

  const continueWatchingItems = useMemo(() => {
    const sourcePool = isInsideFolder ? folderFiles : homeFiles;
    return sourcePool.filter(f => !f.mimeType?.includes('folder') && (savedTimesMap[f.name] || 0) > 3);
  }, [isInsideFolder, folderFiles, homeFiles, savedTimesMap]);

  const playableFolderEpisodes = useMemo(() => sameShowEpisodes(selectedFile, displayedFiles, parseMediaFilename),
    [displayedFiles, selectedFile]);


  const zipParentBackupRef = React.useRef<{
    pathStack: string[];
    folderFiles: any[];
    currentPath: string;
    seriesParentTitle: string;
    seasonFolders: any[];
    activeSeason: any;
  } | null>(null);

  useEffect(() => {
    let cancelled = false;
    const isoFiles = [...homeFiles, ...folderFiles].filter((file: any) => /\.iso$/i.test(file.name || ''));
    void Promise.all(isoFiles.map(async (file: any) => {
      const cached = await AsyncStorage.getItem(`iso_card_v2_${file.name}|${file.size || 0}`).catch(() => null);
      if (!cancelled && cached) {
        try { setMediaInfoMap(prev => ({ ...prev, [file.name]: JSON.parse(cached) })); } catch {}
      }
    }));
    return () => { cancelled = true; };
  }, [homeFiles, folderFiles]);

  const archiveOpenRequestRef = useRef(0);
  const [openingIso, setOpeningIso] = useState(false);
  const [isoInspectionError, setIsoInspectionError] = useState<string | null>(null);
  const retryInspection = useRef<(() => void) | null>(null);
  const closeDetails = () => {
    archiveOpenRequestRef.current++;
    setOpeningIso(false);
    setIsoInspectionError(null);
    detailScrollY.setValue(0);
    setSelectedFile(null);
    if (archiveListing.current) setLoading(false);
    retryInspection.current = null;
    setVersionGroup([]);
  };

  const openZipArchiveFile = async (zipItem: any, basePathOverride?: string) => {
    directoryRequest.current++;
    const requestId = ++archiveOpenRequestRef.current;
    lastFolderCardIdRef.current = 'fcard_0';
    const effectiveBasePath = basePathOverride || zipItem?._parentPath || (currentPath.startsWith('/0:/') ? currentPath : '/0:/');
    const prevBackup = {
      pathStack: [...pathStack],
      folderFiles: [...folderFiles],
      currentPath: effectiveBasePath,
      seriesParentTitle,
      seasonFolders: [...seasonFolders],
      activeSeason,
    };

    try {
      const info = parseMediaFilename(zipItem.name, false);
      const rawZipUrl = archiveSourceUrl(String(zipItem.name || ''), vaultRef.current.baseUrl, effectiveBasePath, getStreamUrl(zipItem));
      const zipUrl = zipItem?.name ? `${rawZipUrl.split("#")[0]}#name=${encodeURIComponent(zipItem.name)}` : rawZipUrl;
      const sizeHint = Number(zipItem.size || 0);
      const virtualZipPath = `${effectiveBasePath}${encodeLibraryName(zipItem.name)}/`;

      const isIso = /\.iso$/i.test(zipItem.name);
      setOpeningIso(isIso);
      setIsoInspectionError(null);
      if (isIso) {
        retryInspection.current = () => { void openZipArchiveFile(zipItem, effectiveBasePath); };
        const pending = { ...zipItem, archiveFormat: 'ISO', isoPending: true };
        setVersionGroup([pending]);
        openFile(pending); // No await: render the card's artwork and metadata immediately.
      }
      if (!isIso) {
        zipParentBackupRef.current = prevBackup;
        setFolderLoading(true);
        setFolderFiles([]);
        setSeasonFolders([]);
        setActiveSeason(null);
        setSeriesParentTitle(info.cleanTitle || zipItem.name.replace(/\.(zip|rar|iso)$/i, ''));
        setCurrentPath(virtualZipPath);
        // CRITICAL FIX: Push onto pathStack so isInsideFolder (pathStack.length > 1) becomes true IMMEDIATELY!
        setPathStack(prev => [...prev, virtualZipPath]);
      }

      if (SecurityVault?.listZipMediaEntries) {
        const rawJson = await fetchZipRawJsonFast(zipUrl, sizeHint);
        if (requestId !== archiveOpenRequestRef.current) return;
        const rawEntries = rawJson ? JSON.parse(rawJson) : [];
        const cleanDiscBase = zipItem.name.replace(/\.(iso|zip|rar)$/i, '');
        const entries = Array.isArray(rawEntries)
          ? rawEntries.map((ent: any) =>
              ent?.name && /^download\.aspx/i.test(String(ent.name))
                ? { ...ent, name: String(ent.name).replace(/^download\.aspx/i, cleanDiscBase) }
                : ent
            )
          : [];
        const movie = isoMovieDetails({ ...zipItem, outerUrl: zipUrl, outerSize: sizeHint, metadata: metadataMap[zipItem.name] }, entries);
        if (movie) {
          if (movie.mediaInfo) {
            setMediaInfoMap(prev => ({ ...prev, [movie.name]: movie.mediaInfo }));
            void AsyncStorage.setItem(`iso_card_v2_${zipItem.name}|${zipItem.size || 0}`, JSON.stringify(movie.mediaInfo)).catch(() => {});
          }
          historyAliasesRef.current[movie.name] = [...new Set([
            ...(historyAliasesRef.current[movie.name] || []), ...watchAliases(movie),
          ])];
          queueHistoryWrite(async () => { await writeHistorySnapshot(); });
          // Migrate progress saved against PR 8/9's generated inner-entry name.
          const previousName = entries[0].name;
          if (!removedHistoryRef.current.has(movie.name) && !removedHistoryRef.current.has(previousName) &&
              !savedTimesRef.current[movie.name] && savedTimesRef.current[previousName]) {
            persistPlaybackProgress(movie.name, savedTimesRef.current[previousName], movie.durationSec || savedDurationsRef.current[previousName] || 0);
          }
          setVersionGroup([movie]);
          setSelectedFile(movie);
          return;
        }
        if (entries.length > 0 && !isIso) {
          archiveListing.current = { path: virtualZipPath, url: zipUrl, size: sizeHint };
          setFolderFiles(entries);
          setFolderLoading(false);
          return;
        }
      }

      if (isIso) throw new Error('No playable Blu-ray feature was found. Tap Retry to inspect the disc again.');
      // Restore previous view cleanly if archive had no playable video files
      zipParentBackupRef.current = null;
      setFolderLoading(false);
      setPathStack(prevBackup.pathStack);
      setCurrentPath(prevBackup.currentPath);
      setFolderFiles(prevBackup.folderFiles);
      setSeriesParentTitle(prevBackup.seriesParentTitle);
      setSeasonFolders(prevBackup.seasonFolders);
      setActiveSeason(prevBackup.activeSeason);
      Alert.alert('Archive / Disc Info', 'Indha .zip / .rar / .iso file-kulla playable video streams edhuvum kidaikala.');
    } catch (e: any) {
      if (requestId !== archiveOpenRequestRef.current) return;
      if (/\.iso$/i.test(zipItem.name)) {
        setIsoInspectionError(playbackErrorMessage(e, e?.message || 'Could not inspect this Blu-ray disc.'));
        SecurityVault?.writeDebugLog?.('APP_ARCHIVE_ERR', `${zipItem.name}: ${e?.message || e}`);
        return;
      }
      zipParentBackupRef.current = null;
      setFolderLoading(false);
      setPathStack(prevBackup.pathStack);
      setCurrentPath(prevBackup.currentPath);
      setFolderFiles(prevBackup.folderFiles);
      setSeriesParentTitle(prevBackup.seriesParentTitle);
      setSeasonFolders(prevBackup.seasonFolders);
      setActiveSeason(prevBackup.activeSeason);
      SecurityVault?.writeDebugLog?.('APP_ARCHIVE_ERR', `${zipItem?.name || ''}: ${e?.message || e}`);
      Alert.alert('Archive Error', String(e?.message || 'Indha archive file-a open panna mudiyala.'));
    } finally {
      if (requestId === archiveOpenRequestRef.current) setOpeningIso(false);
    }
  };


  useEffect(() => {
    if (playMode === null && selectedFile && !selectedFile.isZipEntry &&
        (/\.m2ts$/i.test(selectedFile.name) || /mp2t/i.test(selectedFile.mimeType || ''))) {
      void resolvePlaybackUrl(selectedFile).catch(() => {});
    }
  }, [selectedFile, playMode, resolvePlaybackUrl]);

  useEffect(() => {
    if (!selectedFile || selectedFile.isoPending || playMode !== null) return;
    const targets = versionGroup.length > 1 ? versionGroup : [selectedFile];
    let cancelled = false;

    const runMultiMediaInfo = async () => {
      for (const fileObj of targets) {
        if (cancelled || !fileObj?.name) break;
        const fName = fileObj.name;
        if (fileObj.archiveFormat === 'ISO' && fileObj.mediaInfo) {
          if (mediaInfoMap[fName] !== fileObj.mediaInfo) setMediaInfoMap(prev => ({ ...prev, [fName]: fileObj.mediaInfo }));
          continue;
        }
        if (mediaInfoMap[fName] && (mediaInfoMap[fName].durationSec > 0 || mediaInfoMap[fName].width > 0 || (mediaInfoMap[fName].audioTracks && mediaInfoMap[fName].audioTracks.length > 0))) {
          if (mediaInfoMap[fName].durationSec > 0) {
            savedDurationsRef.current[fName] = mediaInfoMap[fName].durationSec;
            setSavedDurationsMap({ ...savedDurationsRef.current });
          }
          continue;
        }
        const discProbe = standaloneProbeCandidate(fileObj);
        const cacheKey = discProbe ? `mediainfo_v11_${getStreamUrl(fileObj)}_${fileObj.size || 0}` : `mediainfo_v8_${fName}`;
        try {
          const cached = await AsyncStorage.getItem(cacheKey);
          if (cached) {
            const parsed: RealMediaInfo = JSON.parse(cached);
            if (!cancelled) {
              setMediaInfoMap(prev => ({ ...prev, [fName]: parsed }));
              if (parsed.durationSec > 0) {
                savedDurationsRef.current[fName] = parsed.durationSec;
                setSavedDurationsMap({ ...savedDurationsRef.current });
              }
            }
            continue;
          }
        } catch {}

        if (cancelled) break; // The chooser handoff may have happened during the cache lookup.
        if (!MediaInfoModule?.inspectMedia) continue;
        if (fName === selectedFile.name) setScanningMediaInfo(true);
        try {
          const streamUrl = await resolvePlaybackUrl(fileObj);
          if (cancelled) break;
          const originalLanguage = discProbe ? fileObj.metadata?.originalLanguage ?? metadataMap[fName]?.originalLanguage ?? '' : '';
          const res: RealMediaInfo | null = await MediaInfoModule.inspectMedia(streamUrl, fName, originalLanguage);
          if (res && !cancelled && (res.width > 0 || res.audioTracks?.length > 0 || (discProbe && res.durationSec > 0 && !!res.videoCodec))) {
            setMediaInfoMap(prev => ({ ...prev, [fName]: res }));
            if (res.durationSec > 0) {
              savedDurationsRef.current[fName] = res.durationSec;
              setSavedDurationsMap({ ...savedDurationsRef.current });
              AsyncStorage.setItem(`dur_${fName}`, res.durationSec.toString()).catch(() => {});
            }
            if (res.totalBytes > 0 && res.audioTracks && res.audioTracks.length > 0) {
              await AsyncStorage.setItem(cacheKey, JSON.stringify(res));
            }
          }
        } catch {} finally {
          if (!cancelled && fName === selectedFile.name) setScanningMediaInfo(false);
        }
      }
    };
    const miTimer = setTimeout(runMultiMediaInfo, 350);
    return () => { cancelled = true; clearTimeout(miTimer); };
  }, [selectedFile, versionGroup, playMode, mediaInfoMap, metadataMap, getStreamUrl, resolvePlaybackUrl]);

  const seekBothPlayers = useCallback((targetTime: number) => {
    if (isIsoPlayback) SecurityVault.seekIsoPlayback(playbackSource.uri, targetTime);
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
  }, [isIsoPlayback, playbackSource.uri, isExternalAudioSelected, audioDelay, selectedFile, persistPlaybackProgress]);

  const seekbarPanResponder = useMemo(() => PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder: () => true,
    onPanResponderTerminationRequest: () => false,
    onPanResponderGrant: evt => {
      const dur = durationRef.current;
      if (dur <= 0) return;
      lastPlayerActivityRef.current = Date.now();
      isScrubbingRef.current = true;
      setIsScrubbing(true);
      lastPreviewBucketRef.current = -1;
      const calcAndApply = (px: number, barX: number, barW: number) => {
        if (barW <= 0) return;
        const ratio = Math.max(0, Math.min(1, (px - barX) / barW));
        const target = ratio * dur;
        scrubTimeRef.current = target;
        setScrubTime(target);
        requestPreviewAtTime(target);
      };
      const pageX = evt.nativeEvent.pageX;
      lastScrubPageXRef.current = pageX;
      if (progressBarRef.current?.measureInWindow) {
        progressBarRef.current.measureInWindow((x: number, _y: number, w: number) => {
          if (w > 0) progressBarMetrics.current = { pageX: x, width: w };
          calcAndApply(pageX, progressBarMetrics.current.pageX, progressBarMetrics.current.width);
        });
      } else {
        calcAndApply(pageX, progressBarMetrics.current.pageX, progressBarMetrics.current.width);
      }
    },
    onPanResponderMove: evt => {
      const dur = durationRef.current;
      const { pageX: barX, width: barW } = progressBarMetrics.current;
      if (dur <= 0 || barW <= 0) return;
      const curPageX = evt.nativeEvent.pageX;
      if (lastScrubPageXRef.current >= 0 && Math.abs(curPageX - lastScrubPageXRef.current) < 5) return;
      lastScrubPageXRef.current = curPageX;
      lastPlayerActivityRef.current = Date.now();
      const ratio = Math.max(0, Math.min(1, (curPageX - barX) / barW));
      const target = ratio * dur;
      scrubTimeRef.current = target;
      setScrubTime(target);
      requestPreviewAtTime(target);
    },
    onPanResponderRelease: () => {
      if (isScrubbingRef.current) {
        seekBothPlayers(scrubTimeRef.current);
      }
      isScrubbingRef.current = false;
      setIsScrubbing(false);
      setPreviewFrameUri(null);
      lastPreviewBucketRef.current = -1;
      lastScrubPageXRef.current = -1;
      lastPlayerActivityRef.current = Date.now();
    },
    onPanResponderTerminate: () => {
      isScrubbingRef.current = false;
      setIsScrubbing(false);
      setPreviewFrameUri(null);
      lastPreviewBucketRef.current = -1;
    },
  }), [requestPreviewAtTime, seekBothPlayers]);

  const triggerStepSeek = useCallback((isForward: boolean) => {
    clearTimeout(multiTapTimer.current);
    if (seekBaseTime.current === null) {
      seekBaseTime.current = currentTimeRef.current;
      accumulatedSeek.current = 0;
    }
    accumulatedSeek.current += 10;
    const totalSeek = accumulatedSeek.current;

    seekBurstCounterRef.current += 1;
    setSeekOverlay({
      visible: true,
      icon: 'replay-10',
      time: totalSeek,
      position: isForward ? 'right' : 'left',
      burstKey: seekBurstCounterRef.current,
    });

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
  }, [seekBothPlayers]);


  const currentActiveLayer = isTvSearchModalOpen
    ? 'searchModal'
    : showHdrChoiceModal
    ? 'hdrModal'
    : showSeasonModal
    ? 'seasonModal'
    : playMode === 'internal'
    ? (activeMenu ? 'playerMenu' : 'player')
    : selectedFile
    ? 'detail'
    : activeTab === 'downloads'
    ? 'downloads'
    : isInsideFolder
    ? 'folder'
    : 'home';

  // Run initial focus ONLY ONCE when entering a new screen (never re-runs while moving D-Pad on that screen!)
  useEffect(() => {
    if (!IS_TV) return;

    if (currentActiveLayer === 'home') {
      if (loading || homeDisplayedFiles.length === 0) {
        setHeaderFocusUnlocked(false);
        return;
      }
      const screenKey = 'home';
      if (lastFocusedScreenKeyRef.current === screenKey) return;
      lastFocusedScreenKeyRef.current = screenKey;

      setHeaderFocusUnlocked(false);
      const rawTarget = lastHomeCardIdRef.current || 'card_0';
      const targetIdx = parseInt(rawTarget.replace('card_', ''), 10);
      const validId = (!isNaN(targetIdx) && targetIdx < homeDisplayedFiles.length) ? rawTarget : 'card_0';

      requestInitialScreenFocus(validId);
      const t1 = setTimeout(() => focusById(validId), 60);
      const t2 = setTimeout(() => {
        if (pendingInitialFocusId === validId) focusById(validId);
        setHeaderFocusUnlocked(true);
      }, 180);
      return () => { clearTimeout(t1); clearTimeout(t2); };
    }

    if (currentActiveLayer === 'folder') {
      if (folderLoading) {
        setHeaderFocusUnlocked(false);
        return;
      }
      const screenKey = `folder:${currentPath}`;
      if (lastFocusedScreenKeyRef.current === screenKey) return;
      lastFocusedScreenKeyRef.current = screenKey;

      if (folderDisplayedFiles.length === 0) {
        setHeaderFocusUnlocked(true);
        requestInitialScreenFocus('f_back');
        return;
      }
      setHeaderFocusUnlocked(false);
      const rawTarget = lastFolderCardIdRef.current || 'fcard_0';
      const targetIdx = parseInt(rawTarget.replace('fcard_', ''), 10);
      const validId = (!isNaN(targetIdx) && targetIdx < folderDisplayedFiles.length) ? rawTarget : 'fcard_0';

      requestInitialScreenFocus(validId);
      const t1 = setTimeout(() => focusById(validId), 60);
      const t2 = setTimeout(() => {
        if (pendingInitialFocusId === validId) focusById(validId);
        setHeaderFocusUnlocked(true);
      }, 180);
      return () => { clearTimeout(t1); clearTimeout(t2); };
    }

    if (currentActiveLayer === 'detail' && selectedFile) {
      const screenKey = `detail:${selectedFile.name}`;
      if (lastFocusedScreenKeyRef.current === screenKey) return;
      lastFocusedScreenKeyRef.current = screenKey;

      requestInitialScreenFocus('detail_play_btn');
      const t1 = setTimeout(() => {
        if (pendingInitialFocusId === 'detail_play_btn') focusById('detail_play_btn');
      }, 70);
      return () => clearTimeout(t1);
    }

    if (currentActiveLayer === 'downloads') {
      const screenKey = 'downloads';
      if (lastFocusedScreenKeyRef.current === screenKey) return;
      lastFocusedScreenKeyRef.current = screenKey;

      const targetDlId = downloads.length > 0 ? 'dl_item_0' : 'dl_back_btn';
      requestInitialScreenFocus(targetDlId);
      return;
    }

    if (currentActiveLayer === 'seasonModal') {
      lastFocusedScreenKeyRef.current = 'seasonModal';
      const selIdx = Math.max(0, seasonFolders.findIndex(s => s.seasonNum === activeSeason?.seasonNum));
      requestInitialScreenFocus(`season_opt_${selIdx}`);
      return;
    }

    if (currentActiveLayer === 'hdrModal') {
      lastFocusedScreenKeyRef.current = 'hdrModal';
      requestInitialScreenFocus('hdr_dv_btn');
      return;
    }
  }, [
    currentActiveLayer,
     currentPath,
    loading,
    folderLoading,
    homeDisplayedFiles.length,
    folderDisplayedFiles.length,
    selectedFile,
    downloads.length,
    seasonFolders,
    activeSeason,
  ]);

  // Smart 7-Second Auto-Hide for Player Controls
  useEffect(() => {
    if (playMode !== 'internal') {
      onGlobalTvActivity = null;
      return;
    }
    lastPlayerActivityRef.current = Date.now();
    onGlobalTvActivity = () => {
      lastPlayerActivityRef.current = Date.now();
    };
    const checkTimer = setInterval(() => {
      if (!showControls || isPaused || isVideoBuffering || !!activeMenu || isLocked || isScrubbingRef.current) {
        lastPlayerActivityRef.current = Date.now();
        return;
      }
      if (Date.now() - lastPlayerActivityRef.current >= 7000) {
        setShowControls(false);
      }
    }, 500);
    return () => {
      clearInterval(checkTimer);
      onGlobalTvActivity = null;
    };
  }, [playMode, showControls, isPaused, isVideoBuffering, activeMenu, isLocked]);

  useEffect(() => {
    if (playMode === 'internal' && showControls && !activeMenu && !isLocked) {
      lastPlayerActivityRef.current = Date.now();
      const t = setTimeout(() => focusById('player_play_btn'), 45);
      return () => clearTimeout(t);
    }
  }, [playMode, showControls, activeMenu, isLocked]);

  // Flicker-Free Boundary Router:
  // ONLY routes across row boundaries (Search <-> All <-> CW Remove <-> CW Play <-> Row 0 Cards, and Ceiling/Floor stops).
  // Normal card-to-card and Detail button-to-button movement uses native Android focus with ZERO double-focus flicker!
  useEffect(() => {
    if (!IS_TV) return;
    const sub = DeviceEventEmitter.addListener('onTVRemoteKey', event => {
      const keyCode = event?.keyCode;
      if (keyCode !== 19 && keyCode !== 20 && keyCode !== 21 && keyCode !== 22) return;

      // Clear any pending initial focus the moment the user presses a D-Pad arrow key!
      pendingInitialFocusId = '';

      const justChanged = (Date.now() - lastFocusChangeTime) < 80;
      const sourceId = justChanged ? previousFocusedId : currentFocusedId;

      if (currentActiveLayer === 'home' || currentActiveLayer === 'folder') {
        const isFolder = currentActiveLayer === 'folder';
        const prefix = isFolder ? 'f' : '';
        const cols = viewMode === 'grid' ? 5 : 1;
        const listLen = isFolder ? folderDisplayedFiles.length : homeDisplayedFiles.length;
        const hasCW = !searchQuery && activeCategory === 'all' && continueWatchingItems.length > 0;
        const hasSeasons = isFolder && seasonFolders.length > 0 && !!activeSeason;

        if (keyCode === 19) {
          // UP ARROW at top boundaries only:
          if (sourceId && sourceId.startsWith(`${prefix}card_`)) {
            const idx = parseInt(sourceId.replace(`${prefix}card_`, ''), 10);
            // ONLY intercept Row 0 (idx < cols) so 4th/5th column never jumps into empty space!
            // Row 1+ (idx >= cols) moves UP natively with zero flicker!
            if (!isNaN(idx) && idx < cols) {
              setTimeout(() => {
                if (hasCW) {
                  const cwIdx = Math.min(idx, continueWatchingItems.length - 1);
                  focusById(`${prefix}cw_play_${cwIdx}`);
                } else if (isFolder) {
                  if (hasSeasons && idx >= 3) focusById('f_season');
                  else focusById('f_back');
                } else {
                  focusById(`chip_${activeCategory}`);
                }
              }, 5);
            }
          } else if (sourceId && sourceId.startsWith(`${prefix}cw_play_`)) {
            const cwIdx = sourceId.replace(`${prefix}cw_play_`, '');
            setTimeout(() => focusById(`${prefix}cw_remove_${cwIdx}`), 5);
          } else if (sourceId && sourceId.startsWith(`${prefix}cw_remove_`)) {
            setTimeout(() => {
              if (isFolder) focusById('f_back');
              else focusById(`chip_${activeCategory}`);
            }, 5);
          } else if (isFolder && (sourceId === 'f_back' || sourceId === 'f_season')) {
            setTimeout(() => focusById(sourceId), 5);
          } else if (!isFolder && sourceId && (sourceId.startsWith('chip_') || sourceId === 'hdr_sort')) {
            setTimeout(() => focusById('search_btn'), 5);
          } else if (!isFolder && (sourceId === 'search_btn' || sourceId === 'search_clear')) {
            setTimeout(() => focusById('hdr_dl'), 5);
          } else if (!isFolder && sourceId && sourceId.startsWith('hdr_')) {
            setTimeout(() => focusById(sourceId), 5);
          }
        } else if (keyCode === 20) {
          // DOWN ARROW at header/CW boundaries only (Card-to-Card Down moves natively with zero flicker!):
          if (!isFolder && sourceId && sourceId.startsWith('hdr_') && sourceId !== 'hdr_sort') {
            setTimeout(() => focusById('search_btn'), 5);
          } else if (!isFolder && (sourceId === 'search_btn' || sourceId === 'search_clear')) {
            setTimeout(() => focusById('chip_all'), 5);
          } else if (!isFolder && sourceId && sourceId.startsWith('chip_')) {
            setTimeout(() => {
              if (hasCW) focusById('cw_remove_0');
              else if (listLen > 0) focusById('card_0');
            }, 5);
          } else if (isFolder && (sourceId === 'f_back' || sourceId === 'f_season')) {
            setTimeout(() => {
              if (hasCW) focusById('fcw_remove_0');
              else if (listLen > 0) focusById('fcard_0');
            }, 5);
          } else if (sourceId && sourceId.startsWith(`${prefix}cw_remove_`)) {
            const cwIdx = sourceId.replace(`${prefix}cw_remove_`, '');
            setTimeout(() => focusById(`${prefix}cw_play_${cwIdx}`), 5);
          } else if (sourceId && sourceId.startsWith(`${prefix}cw_play_`)) {
            setTimeout(() => {
              if (listLen > 0) focusById(`${prefix}card_0`);
            }, 5);
          }
        }
      }

      // Details Page: Let native ScrollView D-Pad move smoothly between Play in App -> External Player -> Download -> Trailer -> Source!
      // Only guard the top ceiling (detail_back_btn) and bottom floor (detail_source_btn)!
      if (currentActiveLayer === 'detail') {
        if (keyCode === 19 && (sourceId === 'detail_back_btn')) {
          setTimeout(() => focusById(sourceId), 5);
        } else if (keyCode === 20 && (sourceId === 'detail_back_btn')) {
          setTimeout(() => {
            if (versionGroup.length > 1) focusById('detail_ver_0');
            else focusById('detail_play_btn');
          }, 5);
        } else if (keyCode === 20 && sourceId === 'detail_source_btn') {
          setTimeout(() => focusById('detail_source_btn'), 5);
        }
      }

      // Anti-Disappear Watchdog: Only rescues focus if currentFocus became null!
      setTimeout(() => {
        const fallback = currentFocusedId || (
          currentActiveLayer === 'detail' ? 'detail_play_btn' :
          currentActiveLayer === 'folder' ? (lastFolderCardIdRef.current || 'fcard_0') :
          currentActiveLayer === 'home' ? (lastHomeCardIdRef.current || 'card_0') : ''
        );
        if (fallback && VideoPlayerManager?.ensureValidFocus) {
          VideoPlayerManager.ensureValidFocus(fallback);
        }
      }, 75);
    });
    return () => sub.remove();
  }, [
    currentActiveLayer,
    viewMode,
    folderDisplayedFiles.length,
    homeDisplayedFiles.length,
    searchQuery,
    activeCategory,
    continueWatchingItems.length,
    seasonFolders.length,
    activeSeason,
    versionGroup.length,
  ]);

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

  const directoryKey = (path: string) =>
    JSON.stringify(['directory_v1', vaultRef.current.baseUrl, vaultRef.current.username, path]);

  const requestRawDirectory = (path: string, refreshAt?: number) => {
    const key = directoryKey(path) + (refreshAt === undefined ? '' : '/refresh');
    return catalogRequest<any[]>(key, async signal => {
      const id = String(++directoryAttempt);
      const abort = () => SecurityVault.cancelDirectoryRequest(id);
      signal.addEventListener('abort', abort);
      try {
        const url = refreshAt === undefined ? path : `${path}${path.includes('?') ? '&' : '?'}_t=${refreshAt}`;
        const raw = await SecurityVault.securePostDirectory(url, 0, id);
        const files = JSON.parse(raw)?.data?.files;
        if (!Array.isArray(files)) throw new Error('Invalid folder listing');
        return files;
      } finally {
        signal.removeEventListener('abort', abort);
      }
    });
  };

  const fetchDirectory = async (path: string, isPullRefresh = false, isSeasonSwitch = false) => {
    const { baseUrl } = vaultRef.current;
    if (!baseUrl) { setLoading(false); setFolderLoading(false); return; }
    const isRoot = path === '/0:/';
    archiveListing.current = null;
    const generation = ++directoryRequest.current;
    const current = () => generation === directoryRequest.current;
    const key = directoryKey(path);
    let showingCache = false;
    const remember = (files: any[], displayPath = path, seasons: SeasonFolderItem[] = [],
      season: SeasonFolderItem | null = null, title = '') => {
      void AsyncStorage.setItem(key, JSON.stringify({ files, displayPath, seasons, season, title })).catch(() => {});
      if (isRoot) {
        void AsyncStorage.setItem('gdstream_last_root_dir_key_v1', key).catch(() => {});
      }
    };

    if (isPullRefresh) {
      setRefreshing(true);
      setSyncingCatalog(true);
      fetchedKeysRef.current.clear();
      for (const k of Array.from(folderClassifyingRef.current)) {
        if (k.startsWith(path)) folderClassifyingRef.current.delete(k);
      }
      for (const k of Object.keys(folderClassificationsRef.current)) {
        if (k.startsWith(path)) delete folderClassificationsRef.current[k];
      }
      setFolderClassifications(prev => {
        const next: Record<string, FolderClassification> = {};
        for (const [k, v] of Object.entries(prev)) {
          if (!k.startsWith(path)) next[k] = v;
        }
        return next;
      });
    }
    else if (isRoot) {
      if (homeFilesRef.current.length > 0) setSyncingCatalog(true);
      else setLoading(true);
    }
    else setFolderLoading(true);

    try {
      try {
        const raw = await AsyncStorage.getItem(key);
        if (!current()) return;
        const cache = raw && JSON.parse(raw);
        if (Array.isArray(cache?.files)) {
          if (isRoot) setHomeFiles(cache.files);
          else setFolderFiles(cache.files);
          if (!isSeasonSwitch) {
            setSeasonFolders(cache.seasons);
            setActiveSeason(cache.season);
            setSeriesParentTitle(cache.title);
          }
          setCurrentPath(cache.displayPath);
          setLoading(false);
          setFolderLoading(false);
          setSyncingCatalog(true);
          showingCache = true;
        }
      } catch {}
      if (!current()) return;
      const refreshAt = isPullRefresh ? Date.now() : undefined;
      const incomingFiles = await requestRawDirectory(path, refreshAt);
      if (!current()) return;

      if (!isSeasonSwitch && !isRoot) {
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
              path: `${path}${encodeLibraryName(sf.name)}/`,
            });
          }
        }

        if (detectedSeasons.length > 0 && videoFiles.length === 0) {
          detectedSeasons.sort((a, b) => a.seasonNum - b.seasonNum);
          const targetSeason = (isPullRefresh && activeSeason)
            ? (detectedSeasons.find(s => s.seasonNum === activeSeason.seasonNum) || detectedSeasons[0])
            : detectedSeasons[0];

          const rawFolderTitle = decodeURIComponent(path.split('/').filter(Boolean).pop() || '');
          const cleanSeriesName = parseMediaFilename(rawFolderTitle, true).cleanTitle;

          const seasonEpisodes = await requestRawDirectory(targetSeason.path, refreshAt);
          if (!current()) return;
          remember(seasonEpisodes, targetSeason.path, detectedSeasons, targetSeason, cleanSeriesName);
          setSeriesParentTitle(cleanSeriesName);
          setSeasonFolders(detectedSeasons);
          setActiveSeason(targetSeason);

          setFolderFiles(seasonEpisodes);
          setCurrentPath(targetSeason.path);
          if (!isPullRefresh) setSearchQuery('');
          return;
        }
      }

      if (isRoot) {
        setSeasonFolders([]);
        setActiveSeason(null);
        setSeriesParentTitle('');
        setHomeFiles(incomingFiles);
      } else {
        const archiveFiles = incomingFiles.filter((f: any) => /\.(zip|rar|iso)$/i.test(String(f?.name || '')));
        const normalVideos = incomingFiles.filter((f: any) => !f.mimeType?.includes('folder') && !/\.(zip|rar|iso)$/i.test(String(f?.name || '')));
        if (archiveFiles.length === 1 && normalVideos.length === 0 && SecurityVault?.listZipMediaEntries) {
          try {
            const archItem = archiveFiles[0];
            const listedUrl = getPlayableUrl(baseUrl, path, archItem.name, archItem.link);
            const archUrl = archiveSourceUrl(archItem.name, baseUrl, path, listedUrl);
            const namedArchiveUrl = `${archUrl.split('#')[0]}#name=${encodeURIComponent(archItem.name)}`;
            const rawJson = await fetchZipRawJsonFast(namedArchiveUrl, Number(archItem.size || 0));
            if (!current()) return;
            const entries = rawJson ? JSON.parse(rawJson) : [];
            if (Array.isArray(entries) && entries.length > 0) {
              const rawFolderTitle = decodeURIComponent(path.split('/').filter(Boolean).pop() || '');
              archiveListing.current = { path, url: namedArchiveUrl, size: Number(archItem.size || 0) };
              remember(incomingFiles); // Archive proxy URLs are scoped to the running process.
              setSeriesParentTitle(parseMediaFilename(rawFolderTitle, true).cleanTitle);
              setFolderFiles(entries);
              setCurrentPath(path);
              if (!isPullRefresh) setSearchQuery('');
              return;
            }
          } catch (_archErr) {}
        }
        if (!current()) return;
        setFolderFiles(incomingFiles);
      }
      remember(incomingFiles);
      setCurrentPath(path);
      if (!isPullRefresh) setSearchQuery('');
    } catch (error) {
      if (current() && !showingCache) Alert.alert('Error', `Folder load aagala! (${(error as any)?.message || 'Network'})`);
    } finally {
      if (current()) {
        setLoading(false);
        setFolderLoading(false);
        setRefreshing(false);
        setSyncingCatalog(false);
      }
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
    return getPlayableUrl(baseUrl, file._parentPath || currentPath, file.name, file.link);
  };

  const openFile = (file: any) => {
    switchingEpisode.current = false;
    lastSavedSec.current = -1;
    hasRestoredResumeRef.current = false;
    setSelectedAudio(undefined);
    setSelectedText({ type: 'disabled' });
    setCurrentTime(0);
    const knownDuration = playbackDuration(file, 0);
    setDuration(knownDuration);
    currentTimeRef.current = 0;
    durationRef.current = knownDuration;
    setResizeMode('contain');
    resetPlayerZoom(true);
    setShowAudioBrowserModal(false);
    setSubtitleDelay(0);
    setAudioDelay(0);
    setPlaybackRate(1.0);
    setIsLocked(false);
    setExternalSubtitle(null);
    setExternalAudio(null);
    selectedFileRef.current = file;
    setSelectedFile(file);
  };

  const startPlaybackWithMode = async (mode: 'dv' | 'hdr' | 'sdr', file = selectedFile) => {
    if (!file || file.isoPending) return;
    removedHistoryRef.current.delete(file.name); // Only an intentional new play can restore this movie.
    queueHistoryWrite(async () => { await writeHistorySnapshot(); });
    try {
      const source = await resolvePlaybackUrl(file);
      const url = /\.vob$/i.test(source) && SecurityVault?.dvdPlaybackUrl ? await SecurityVault.dvdPlaybackUrl(source) : source;
      if (selectedFileRef.current !== file) return;
      await SecurityVault?.prepareArchivePlayback?.(url);
      await VideoPlayerManager?.setPreferredHdrMode?.(mode);
      if (selectedFileRef.current !== file) return;
      setPreparedUrl(url);
    } catch (error) {
      if ((error as any)?.code === 'DVD_MPEG2_UNSUPPORTED') { showDvdFallback(file); return; }
      Alert.alert('Playback error', playbackErrorMessage(error, error instanceof Error ? error.message : 'Could not open the stream.'));
      return;
    }
    hasRestoredResumeRef.current = false;
    setShowHdrChoiceModal(false);
    setActiveHdrMode(mode);
    setShowControls(true);
    lastPlayerActivityRef.current = Date.now();
    setPlayerInstanceKey(k => k + 1);
    setIsPaused(false);
    setIsVideoBuffering(true);
    setPlayMode('internal');
  };

  const handlePlayInAppPress = (fileToPlay = selectedFile) => {
    if (!fileToPlay || fileToPlay.isoPending) return;
    if (/\.iso$/i.test(fileToPlay.name || '') && !fileToPlay.isZipEntry) { void openZipArchiveFile(fileToPlay); return; }
    const info = parseMediaFilename(fileToPlay.name, false);
    const realMi: RealMediaInfo | undefined = fileToPlay.mediaInfo ?? mediaInfoMap[fileToPlay.name];
    const effectiveHasDV = realMi ? realMi.hasDV : info.hasDV;
    const effectiveHasHDR = realMi ? realMi.hasHDR : info.hasHDR;

    if (effectiveHasDV && effectiveHasHDR) setShowHdrChoiceModal(true);
    else if (effectiveHasDV) startPlaybackWithMode('dv', fileToPlay);
    else if (effectiveHasHDR) startPlaybackWithMode('hdr', fileToPlay);
    else startPlaybackWithMode('sdr', fileToPlay);
  };

  const toggleLiveHdrModeInPlayer = async () => {
    const nextMode = activeHdrMode === 'dv' ? 'hdr' : 'dv';
    if (currentTimeRef.current > 3 && selectedFile?.name) {
      persistPlaybackProgress(selectedFile.name, currentTimeRef.current, durationRef.current);
    }
    hasRestoredResumeRef.current = false;
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
    if (!file || file.isoPending) return;
    const info = parseMediaFilename(file.name, false);
    const meta = metadataMap[file.name];
    const movie = file.archiveFormat === 'ISO' && /\.m2ts$/i.test(file.innerName || '');
    const url = movie
      ? `${file.link}?offline=m2ts#${file.outerSize || 0}:${encodeURIComponent(file.outerUrl)}`
      : getRemoteDownloadUrl(file);
    try {
      await DownloadEngine?.startDownload(url, file.innerName || file.name, info.cleanTitle, meta?.poster || '');
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
    if (item?._movieFolder && Array.isArray(item._versions) && item._versions.length > 0) {
      const vers = item._versions;
      if (vers.length === 1 && /\.iso$/i.test(String(vers[0]?.name || '')) && !vers[0]?.isZipEntry) {
        openZipArchiveFile(vers[0], vers[0]._parentPath);
        return;
      }
      setVersionGroup(vers);
      const resumedVer = vers.find((v: any) => (savedTimesMap[v.name] || 0) > 3) || vers[0];
      openFile(resumedVer);
      return;
    }
    if (item?.name && /\.(zip|rar|iso)$/i.test(String(item.name)) && !item?.isZipEntry) {
      openZipArchiveFile(item, item._parentPath);
      return;
    }
    if (item?.mimeType?.includes('folder')) {
      zipParentBackupRef.current = null;
    }
    if (item?.isZipEntry && item?.zipCompression === 'RAR_COMPRESSED') {
      Alert.alert(
        'Compressed RAR Detected',
        'Indha episode RAR compression-la irukku. Google Drive-la irundhu direct instant streaming panna .ZIP format alladhu Store (Uncompressed) .RAR format use pannunga!'
      );
      return;
    }
    if (item.mimeType?.includes('folder')) {
      lastFolderCardIdRef.current = 'fcard_0';
      const newPath = `${currentPath}${encodeLibraryName(item.name)}/`;
      setPathStack([...pathStack, newPath]);
      setCurrentPath(newPath);
      fetchDirectory(newPath, false, false);
    } else {
      const vers = item._versions && item._versions.length > 1 ? item._versions : [item];
      setVersionGroup(vers);
      const resumedVer = vers.find((v: any) => (savedTimesMap[v.name] || 0) > 3) || vers[0];
      openFile(resumedVer);
    }
  };

  // Instant Back from Series to Homepage: Home FlatList stays mounted at exact scroll position!
  const goBack = () => {
    directoryRequest.current++;
    setRefreshing(false);
    setFolderLoading(false);
    archiveOpenRequestRef.current++;
    if (zipParentBackupRef.current) {
      const prev = zipParentBackupRef.current;
      zipParentBackupRef.current = null;
      setPathStack(prev.pathStack.length > 0 ? prev.pathStack : ['/0:/']);
      setCurrentPath(prev.currentPath || '/0:/');
      setFolderFiles(prev.folderFiles);
      setSeriesParentTitle(prev.seriesParentTitle);
      setSeasonFolders(prev.seasonFolders);
      setActiveSeason(prev.activeSeason);
      setFolderLoading(false);
      return;
    }
    if (pathStack.length > 1) {
      const newStack = pathStack.slice(0, -1);
      const targetPath = newStack[newStack.length - 1];
      setPathStack(newStack);
      setCurrentPath(targetPath);
      setSeasonFolders([]);
      setActiveSeason(null);
      setSeriesParentTitle('');
      if (targetPath === '/0:/' && homeFiles.length > 0) {
        setCurrentPath('/0:/');
        setFolderFiles([]);
        setSearchQuery('');
      } else {
        fetchDirectory(targetPath, false, false);
      }
    }
  };

  const openInExternalPlayer = async (targetFile = selectedFile, vlc = false) => {
    if (!targetFile || targetFile.isoPending) return;
    closeInternalPlayer(false);
    setPlayMode('external'); // Suppress metadata probes while the external player owns the stream.
    // Commit the paused/unmounted native view before handing the proxy to the external app.
    await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
    try {
      await SecurityVault?.stopArchivePlayback?.(playbackSource.uri);
      const url = await resolvePlaybackUrl(targetFile);
      if (vlc) await VideoPlayerManager.playVideoInVlc(url);
      else if (await VideoPlayerManager.playVideo(url, 'Open with external player') === false) setPlayMode(null);
    } catch (error) {
      setPlayMode(null);
      if ((error as any)?.code === 'DVD_MPEG2_UNSUPPORTED') { showDvdFallback(targetFile); return; }
      Alert.alert('External player error', playbackErrorMessage(error, error instanceof Error ? error.message : 'Could not open the player.'));
    }
  };

  const showDvdFallback = (file: any) => {
    Alert.alert('DVD playback', 'This device cannot decode MPEG-2 video.', [
      { text: 'Close', style: 'cancel' },
      { text: 'Open in VLC', onPress: () => openInExternalPlayer(file, true) },
    ]);
  };

  useEffect(() => {
    if (playMode !== 'internal') return;
    const sub = DeviceEventEmitter.addListener('DVD_MPEG2_UNSUPPORTED', (url: string) => {
      if (url !== playbackSource.uri) return;
      closeInternalPlayer();
      showDvdFallback(selectedFile);
    });
    return () => sub.remove();
  }, [playMode, playbackSource.uri, selectedFile, closeInternalPlayer]);

  const handleZoomToggle = () => {
    resetPlayerZoom(false);
    setResizeMode(prev => (prev === 'contain' ? 'cover' : 'contain'));
  };

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

  const gestureStartRef = useRef({ side: 'left' as 'left' | 'right', startVal: 0.5, isSwiping: false, isPanning: false });
  const handleZoneTapRef = useRef(handleZoneTap);
  useEffect(() => { handleZoneTapRef.current = handleZoneTap; }, [handleZoneTap]);
  const isLockedRef = useRef(isLocked);
  useEffect(() => { isLockedRef.current = isLocked; }, [isLocked]);

  const playerPanResponder = useMemo(() => {
    const getPinchDistance = (touches: readonly any[]) => {
      const dx = (touches[0]?.pageX || 0) - (touches[1]?.pageX || 0);
      const dy = (touches[0]?.pageY || 0) - (touches[1]?.pageY || 0);
      return Math.max(12, Math.sqrt(dx * dx + dy * dy));
    };

    const clampPan = (pct: number, rawX: number, rawY: number) => {
      if (pct <= 0) return { x: 0, y: 0 };
      const { width: sw, height: sh } = Dimensions.get('window');
      const s = zoomPctToScale(pct);
      const maxX = Math.max(0, ((s - 1) * (sw || 800)) / 2);
      const maxY = Math.max(0, ((s - 1) * (sh || 400)) / 2);
      return {
        x: Math.max(-maxX, Math.min(maxX, rawX)),
        y: Math.max(-maxY, Math.min(maxY, rawY)),
      };
    };

    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: evt => {
        const touches = evt.nativeEvent.touches || [];
        if (touches.length >= 2) {
          pinchActiveRef.current = true;
          pinchStartDistRef.current = getPinchDistance(touches);
          pinchStartZoomRef.current = zoomPctRef.current;
          clearTimeout(singleTapTimer.current);
          clearTimeout(multiTapTimer.current);
          return;
        }
        pinchActiveRef.current = false;
        const screenW = Dimensions.get('window').width || 800;
        const side = evt.nativeEvent.pageX < screenW / 2 ? 'left' : 'right';
        panStartRef.current = { x: panXRef.current, y: panYRef.current };
        gestureStartRef.current = {
          side,
          startVal: side === 'left' ? brightnessRef.current : volumeRef.current,
          isSwiping: false,
          isPanning: false,
        };
      },
      onPanResponderMove: (evt, gestureState) => {
        if (isLockedRef.current) return;
        const touches = evt.nativeEvent.touches || [];
        if (touches.length >= 2) {
          clearTimeout(singleTapTimer.current);
          clearTimeout(multiTapTimer.current);
          clearTimeout(zoomHideTimerRef.current);
          if (!pinchActiveRef.current) {
            pinchActiveRef.current = true;
            pinchStartDistRef.current = getPinchDistance(touches);
            pinchStartZoomRef.current = zoomPctRef.current;
            gestureStartRef.current.isSwiping = false;
            gestureStartRef.current.isPanning = false;
            setGestureIndicator(prev => ({ ...prev, visible: false }));
          }
          const dist = getPinchDistance(touches);
          const ratio = dist / Math.max(12, pinchStartDistRef.current);
          const baseScale = zoomPctToScale(pinchStartZoomRef.current);
          const targetScale = Math.max(0.25, Math.min(4.0, baseScale * ratio));
          const nextPct = targetScale >= 1
            ? Math.round((targetScale - 1) * 100)
            : Math.round(((targetScale - 1) / 0.75) * 100);
          const clampedPct = Math.max(-100, Math.min(300, nextPct));
          const snappedPct = Math.abs(clampedPct) <= 3 ? 0 : clampedPct;
          const nextScale = zoomPctToScale(snappedPct);
          const clampedPan = clampPan(snappedPct, panXRef.current, panYRef.current);

          zoomPctRef.current = snappedPct;
          panXRef.current = clampedPan.x;
          panYRef.current = clampedPan.y;
          videoScaleAnim.setValue(nextScale);
          videoPanXAnim.setValue(clampedPan.x);
          videoPanYAnim.setValue(clampedPan.y);
          setZoomPct(snappedPct);
          setZoomHudVisible(true);
          return;
        }

        if (pinchActiveRef.current) return;
        const { dy, dx } = gestureState;

        if (zoomPctRef.current > 0) {
          if (!gestureStartRef.current.isPanning && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
            gestureStartRef.current.isPanning = true;
            clearTimeout(singleTapTimer.current);
          }
          if (gestureStartRef.current.isPanning) {
            const clamped = clampPan(
              zoomPctRef.current,
              panStartRef.current.x + dx,
              panStartRef.current.y + dy,
            );
            panXRef.current = clamped.x;
            panYRef.current = clamped.y;
            videoPanXAnim.setValue(clamped.x);
            videoPanYAnim.setValue(clamped.y);
            return;
          }
        }

        if (!gestureStartRef.current.isSwiping && Math.abs(dy) > 12 && Math.abs(dy) > Math.abs(dx)) {
          gestureStartRef.current.isSwiping = true;
          clearTimeout(singleTapTimer.current);
        }
        if (gestureStartRef.current.isSwiping) {
          const screenH = Dimensions.get('window').height || 400;
          clearTimeout(gestureHideTimer.current);
          if (gestureStartRef.current.side === 'left') {
            const nextVal = Math.max(0, Math.min(1, gestureStartRef.current.startVal + (-dy / (screenH * 0.65))));
            brightnessRef.current = nextVal;
            setBrightnessLevel(nextVal);
            VideoPlayerManager?.setSystemBrightness?.(nextVal);
            setGestureIndicator({ visible: true, type: 'brightness', value: nextVal });
          } else {
            // 0% to 200% Real Hardware LoudnessEnhancer Sound Boost
            const boostVal = Math.max(0, Math.min(2.0, gestureStartRef.current.startVal + (-dy / (screenH * 0.42))));
            volumeRef.current = boostVal;
            setVolumeLevel(boostVal);
            VideoPlayerManager?.setSystemVolume?.(boostVal);
            setGestureIndicator({ visible: true, type: 'volume', value: boostVal });
          }
        }
      },
      onPanResponderRelease: evt => {
        if (pinchActiveRef.current) {
          pinchActiveRef.current = false;
          clearTimeout(zoomHideTimerRef.current);
          zoomHideTimerRef.current = setTimeout(() => setZoomHudVisible(false), 1200);
          gestureStartRef.current.isSwiping = false;
          gestureStartRef.current.isPanning = false;
          return;
        }
        if (gestureStartRef.current.isPanning) {
          gestureStartRef.current.isPanning = false;
          return;
        }
        if (gestureStartRef.current.isSwiping) {
          gestureHideTimer.current = setTimeout(() => setGestureIndicator(prev => ({ ...prev, visible: false })), 900);
        } else {
          const screenW = Dimensions.get('window').width || 800;
          handleZoneTapRef.current(evt.nativeEvent.pageX >= screenW / 2);
        }
        gestureStartRef.current.isSwiping = false;
      },
      onPanResponderTerminate: () => {
        if (pinchActiveRef.current) {
          pinchActiveRef.current = false;
          clearTimeout(zoomHideTimerRef.current);
          zoomHideTimerRef.current = setTimeout(() => setZoomHudVisible(false), 1200);
        }
        gestureStartRef.current.isSwiping = false;
        gestureStartRef.current.isPanning = false;
      },
    });
  }, [videoScaleAnim, videoPanXAnim, videoPanYAnim]);

  const handleVideoLoad = async (meta: any) => {
    if (dvdPlayerUrl && meta?.naturalSize?.height > 0) setDvdAspect(meta.naturalSize.width / meta.naturalSize.height);
    const loadedDur = playbackDuration(selectedFile, Number(meta?.duration), savedDurationsRef.current[selectedFile?.name] || 0);
    if (loadedDur > 0) {
      setDuration(loadedDur);
      durationRef.current = loadedDur;
      if (!isIsoPlayback && selectedFile && MediaInfoModule?.prewarmTimelinePreviews) {
        const sUrl = getStreamUrl(selectedFile);
        if (sUrl && prewarmedUrlRef.current !== sUrl) {
          prewarmedUrlRef.current = sUrl;
          previewCacheRef.current = {};
          setTimeout(() => {
            if (prewarmedUrlRef.current === sUrl) {
              MediaInfoModule.prewarmTimelinePreviews(sUrl, loadedDur)
                .then((items: Array<{ bucket: number; uri: string }>) => {
                  if (Array.isArray(items) && prewarmedUrlRef.current === sUrl) {
                    for (const it of items) {
                      if (it && typeof it.bucket === 'number' && it.uri) {
                        previewCacheRef.current[it.bucket] = it.uri;
                      }
                    }
                  }
                })
                .catch(() => {});
            }
          }, 1200);
        }
      }
      if (selectedFile?.name) {
        savedDurationsRef.current[selectedFile.name] = loadedDur;
        setSavedDurationsMap({ ...savedDurationsRef.current });
        AsyncStorage.setItem(`dur_${selectedFile.name}`, loadedDur.toString()).catch(() => {});
      }
    }
    if (meta.audioTracks) {
      setAudioTracks(meta.audioTracks);
      if (!isIsoPlayback && !selectedAudio && meta.audioTracks.length > 0) {
        const selectedIdx = meta.audioTracks.findIndex((t: any) => t.selected);
        setSelectedAudio({ type: 'index', value: String(selectedIdx >= 0 ? selectedIdx : 0) });
      }
    }
    setTextTracks(Array.isArray(meta.textTracks) ? meta.textTracks : []);
    if (hasRestoredResumeRef.current) return;
    hasRestoredResumeRef.current = true;
    try {
      let savedTime = savedTimesRef.current[selectedFile?.name] || 0;
      if (savedTime <= 3 && selectedFile?.name) {
        const savedTimeStr = await AsyncStorage.getItem(`time_${selectedFile.name}`);
        if (savedTimeStr) savedTime = parseFloat(savedTimeStr);
      }
      if (savedTime > 3 && (loadedDur <= 0 || loadedDur - savedTime > 90)) {
        if (isIsoPlayback) SecurityVault.seekIsoPlayback(playbackSource.uri, savedTime);
        videoRef.current?.seek(savedTime);
        setCurrentTime(savedTime);
        currentTimeRef.current = savedTime;
      }
    } catch {}
  };

  const nextEpisodeFile = useMemo(() => {
    if (!selectedFile) return null;
    const currentIndex = playableFolderEpisodes.findIndex(f => f.name === selectedFile.name);
    if (currentIndex !== -1 && currentIndex + 1 < playableFolderEpisodes.length) {
      return playableFolderEpisodes[currentIndex + 1];
    }
    return null;
  }, [selectedFile, playableFolderEpisodes]);

  const playEpisode = (file: any) => {
    setPlayMode(null);
    openFile(file);
    void startPlaybackWithMode(activeHdrMode, file);
  };

  const playNextEpisode = () => {
    if (!nextEpisodeFile) return;
    if (selectedFile?.name && currentTimeRef.current > 3) {
      persistPlaybackProgress(selectedFile.name, currentTimeRef.current, durationRef.current);
    }
    playEpisode(nextEpisodeFile);
  };

  const handleVideoEnd = async () => {
    if (switchingEpisode.current) return;
    switchingEpisode.current = true;
    if (selectedFile?.name) {
      removeContinueWatchingItem(selectedFile);
    }
    if (nextEpisodeFile) {
      playEpisode(nextEpisodeFile);
      return;
    }
    closeInternalPlayer();
  };

  const renderSeasonModal = () => {
    if (!showSeasonModal || seasonFolders.length === 0) return null;
    return (
      <Modal
        visible={showSeasonModal}
        transparent={true}
        animationType="fade"
        onRequestClose={() => setShowSeasonModal(false)}>
        <FocusLayerContext.Provider value="seasonModal">
        <SeasonBottomSheet
          seriesTitle={seriesParentTitle || 'SERIES'}
          seasonCount={seasonFolders.length}
          onClose={() => setShowSeasonModal(false)}
          closeIcon={<Icon name="close" size={18} color="#E2E8F0" />}>
          <ScrollView style={{ maxHeight: 360 }} showsVerticalScrollIndicator={false}>
            {seasonFolders.map((sItem, sIdx) => {
              const isSelected = activeSeason?.seasonNum === sItem.seasonNum;
              return (
                <FocusableButton
                  key={sItem.seasonNum}
                  focusId={`season_opt_${sIdx}`}
                  Component={SeasonOptionRow}
                  index={sIdx}
                  seasonNumber={sItem.seasonNum}
                  label={sItem.label}
                  specs={parseSeasonFolderSpecs(sItem.folderName)}
                  active={isSelected}
                  onPress={() => handleSelectSeason(sItem)}
                  rightIcon={
                    <Icon
                      name={isSelected ? 'check-circle' : 'chevron-right'}
                      size={22}
                      color={isSelected ? '#F5C518' : '#64748B'}
                    />
                  }
                />
              );
            })}
          </ScrollView>
        </SeasonBottomSheet>
        </FocusLayerContext.Provider>
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
        <FocusLayerContext.Provider value="hdrModal">
        <View style={styles.modalBackdrop}>
          <SlideUpSheet style={styles.hdrModalCard}>
            <Text style={styles.hdrModalEyebrow}>SELECT DISPLAY FORMAT</Text>
            <Text style={styles.hdrModalTitle}>{info.cleanTitle}</Text>
            <Text style={styles.hdrModalSub}>
              This title contains both Dolby Vision and HDR10 streams. Choose which hardware engine you want to play with:
            </Text>
            <FocusableButton focusId="hdr_dv_btn" style={styles.dvOptionBtn} onPress={() => startPlaybackWithMode('dv')}>
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
            <FocusableButton style={styles.hdrCancelBtn} onPress={() => setShowHdrChoiceModal(false)}>
              <Text style={{ color: '#99A3B3', fontWeight: '700' }}>Cancel</Text>
            </FocusableButton>
          </SlideUpSheet>
        </View>
        </FocusLayerContext.Provider>
      </Modal>
    );
  };



  useEffect(() => {
    if (!vaultReady || playMode !== null || !Array.isArray(activeRawFiles) || activeRawFiles.length === 0) return;
    const archives = activeRawFiles.filter(
      (f: any) => f && !f.isZipEntry && /\.(zip|rar)$/i.test(String(f.name || ''))
    );
    if (archives.length === 0) return;

    let cancelled = false;
    const timer = setTimeout(() => {
      const queue = archives.filter((a: any) => {
        const sUrl = getStreamUrl(a);
        const idMatch = sUrl.match(/[?&]id=([a-zA-Z0-9_-]{15,})/) || sUrl.match(/\/d\/([a-zA-Z0-9_-]{15,})/);
        const k = idMatch ? `gd_${idMatch[1]}` : (a.id || a.name);
        if (zipRawJsonCacheRef.current[k] || zipPrewarmQueuedRef.current.has(k)) return false;
        zipPrewarmQueuedRef.current.add(k);
        return true;
      }).slice(0, 35);

      if (queue.length === 0) return;
      let cursor = 0;

      const worker = async () => {
        while (!cancelled && cursor < queue.length) {
          const item = queue[cursor++];
          try {
            const sUrl = getStreamUrl(item);
            if (sUrl) {
              await fetchZipRawJsonFast(sUrl, Number(item.size || 0), item.id || item.name);
            }
          } catch {}
          await new Promise<void>(r => setTimeout(r, 150));
        }
      };

      worker();
      worker();
    }, 900);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [activeRawFiles, vaultReady, playMode, getStreamUrl, fetchZipRawJsonFast]);

  const renderFloatingMenu = () => {
    if (!activeMenu) return null;
    if (activeMenu === 'episodes' && playableFolderEpisodes.length > 1) {
      return (
        <FocusLayerContext.Provider value="playerMenu">
        <View style={styles.netflixSheetOverlay}>
          <SlideUpSheet style={[styles.floatingMenu, { width: 400 }]}>
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
                        playEpisode(ep);
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
          </SlideUpSheet>
        </View>
        </FocusLayerContext.Provider>
      );
    }

    const isAudio = activeMenu === 'audio';
    const tracks = isAudio ? audioTracks : textTracks;
    const isSubtitleDisabled = !selectedText || selectedText.type === 'disabled';

    return (
      <FocusLayerContext.Provider value="playerMenu">
      <View style={styles.netflixSheetOverlay}>
        <SlideUpSheet style={styles.floatingMenu}>
          <View style={styles.floatingMenuHeader}>
            <Text style={styles.floatingMenuTitle}>{isAudio ? 'Audio & Sound Boost' : 'Subtitles & Captions'}</Text>
            <FocusableButton style={styles.menuCloseBtn} onPress={() => setActiveMenu(null)}>
              <Icon name="close" size={20} color="white" />
            </FocusableButton>
          </View>
          <ScrollView style={{ maxHeight: 270 }}>
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
              <FocusableButton
                hasTVPreferredFocus={true}
                style={styles.menuItem}
                onPress={() => {
                  setActiveMenu(null);
                  setShowAudioBrowserModal(true);
                }}>
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
                <Text style={styles.adjustLabel}>Audio Sync</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setAudioDelay(d => Math.round((d - 0.25) * 100) / 100)}><Icon name="remove" size={20} color="white" /></FocusableButton>
                <Text style={styles.adjustValue}>{audioDelay >= 0 ? `+${audioDelay.toFixed(2)}s` : `${audioDelay.toFixed(2)}s`}</Text>
                <FocusableButton style={styles.adjustBtn} onPress={() => setAudioDelay(d => Math.round((d + 0.25) * 100) / 100)}><Icon name="add" size={20} color="white" /></FocusableButton>
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
            {!isAudio && dvdTracks.map((label, index) => (
              <FocusableButton key={`dvd-${index}`} style={styles.menuItem}
                onPress={() => handleSelectTextTrack({ type: 'dvd', value: index })}>
                <Icon name="check" size={20} color="#E50914" style={{ opacity: dvdSelected && selectedText.value === index ? 1 : 0 }} />
                <Text style={styles.menuItemText}>{label} • DVD {index + 1}</Text>
              </FocusableButton>
            ))}
            {tracks.map((track, index) => {
              const indexStr = String(index);
              const title = track.language?.trim().toUpperCase() || `Track ${index + 1}`;
              const detail = track.title?.trim() || 'Default';
              const label = title.toLowerCase() === detail.toLowerCase() ? title : `${title} • ${detail}`;
              const isSelected = isAudio
                ? (selectedAudio?.type === 'index' ? String(selectedAudio.value) === indexStr : !selectedAudio && !!track.selected)
                : selectedText?.type === 'index' && String(selectedText?.value) === indexStr;
              return (
                <FocusableButton
                  key={index}
                  hasTVPreferredFocus={isAudio && isSelected}
                  style={styles.menuItem}
                  onPress={() => {
                    if (isAudio) { setSelectedAudio({ type: 'index', value: indexStr }); setActiveMenu(null); }
                    else handleSelectTextTrack({ type: 'index', value: indexStr });
                  }}>
                  <Icon name="check" size={20} color="#E50914" style={{ opacity: isSelected ? 1 : 0 }} />
                  <Text style={styles.menuItemText} numberOfLines={1}>
                    {track.title && (isAudio && isIsoPlayback || /\.m2ts(?:$|[?#])/i.test(playbackSource.uri)) ? track.title :
                      label}
                  </Text>
                </FocusableButton>
              );
            })}
          </ScrollView>
        </SlideUpSheet>
      </View>
      </FocusLayerContext.Provider>
    );
  };

  // 🔥 1. INTERNAL VIDEO PLAYER OVERLAY (Keeps Home & Series FlatLists mounted underneath!) 🔥
  const renderPlayerOverlay = () => {
    if (!selectedFile || playMode !== 'internal') return null;
    const effectiveDisplayTime = isScrubbing ? scrubTime : currentTime;
    const progressPercent = duration > 0 ? Math.min(100, Math.max(0, (effectiveDisplayTime / duration) * 100)) : 0;
    const remainingTime = Math.max(0, duration - effectiveDisplayTime);
    const previewBoxWidth = 160;
    const safeBarWidth = progressWidth > 0 ? progressWidth : 300;
    const thumbPixelX = (progressPercent / 100) * safeBarWidth;
    const previewLeftPx = Math.max(0, Math.min(safeBarWidth - previewBoxWidth, thumbPixelX - previewBoxWidth / 2));
    const playerInfo = parseMediaFilename(selectedFile.name, false);
    const realMi: RealMediaInfo | undefined = selectedFile.mediaInfo ?? mediaInfoMap[selectedFile.name];
    const effectiveHasDV = realMi ? realMi.hasDV : playerInfo.hasDV;
    const effectiveHasHDR = realMi ? realMi.hasHDR : playerInfo.hasHDR;
    const canSwitchDvHdr = effectiveHasDV && effectiveHasHDR;

    return (
      <FocusLayerContext.Provider value="player">
      <View style={styles.playerWrapper}>
        <StatusBar hidden={true} />
        <Animated.View
          pointerEvents="none"
          style={[
            styles.videoPlayer,
            {
              transform: [
                { translateX: videoPanXAnim },
                { translateY: videoPanYAnim },
                { scale: videoScaleAnim },
              ],
            },
          ]}>
        <Video
          key={playbackIdentity}
          ref={videoRef}
          source={playbackSource}
          style={styles.videoPlayer}
          resizeMode={resizeMode}
          paused={isPaused}
          volume={Math.min(1.0, volumeLevel)}
          muted={isExternalAudioSelected}
          disableFocus={isExternalAudioSelected}
          rate={playbackRate}
          focusable={false}
          progressUpdateInterval={dvdSelected ? 80 : 500}
          onLoad={handleVideoLoad}
          onBuffer={(b: any) => setIsVideoBuffering(!!b?.isBuffering)}
          onAudioTracks={(data: any) => {
            const list = Array.isArray(data) ? data : data?.audioTracks;
            if (list && list.length) setAudioTracks(list);
          }}
          onTextTracks={(data: any) => {
            const list = Array.isArray(data) ? data : data?.textTracks;
            if (Array.isArray(list)) setTextTracks(list);
          }}
          onProgress={(p: any) => {
            if (p.currentTime !== currentTimeRef.current) isoProgressAtRef.current = Date.now();
            currentTimeRef.current = p.currentTime;
            if (showControls || isExternalSubtitleSelected) {
              setCurrentTime(p.currentTime);
            }
            if (p.seekableDuration > 0 && durationRef.current <= 0) {
              const effectiveDuration = playbackDuration(selectedFile, p.seekableDuration);
              durationRef.current = effectiveDuration;
              setDuration(effectiveDuration);
            }
            const sec = Math.floor(p.currentTime);
            if (hasRestoredResumeRef.current && sec > 3 && sec % 2 === 0 && lastSavedSec.current !== sec) {
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
          onError={(err: any) => {
            const errDetail = JSON.stringify(err?.error || err || 'Unknown playback error');
            SecurityVault?.writeDebugLog?.('EXO_PLAY_ERR', `${selectedFile?.name || ''}: ${errDetail}`);
            if (isIsoPlayback) {
              failIsoPlayback(playbackErrorMessage(err, 'The disc could not be played. Reopen it to retry, or check that its audio and video formats are supported.'));
            } else {
              closeInternalPlayer();
              Alert.alert('Playback error', playbackErrorMessage(err, err?.error?.errorString || 'Could not play the stream. Please try again.'));
            }
          }}
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
        </Animated.View>

        {isExternalAudioSelected && !!externalAudio && (
          <Video
            key={externalAudio.uri}
            ref={externalAudioRef}
            source={{ uri: externalAudio.uri }}
            paused={isPaused || isVideoBuffering}
            rate={playbackRate}
            volume={Math.min(1.0, volumeLevel)}
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

        {dvdSelected && (
          <DvdSubtitleOverlay key={`${dvdPlayerUrl}/${selectedText.value}`} url={dvdPlayerUrl} track={selectedText.value}
            clock={currentTimeRef} delay={subtitleDelay} aspect={dvdAspect} zoom={resizeMode === 'cover'} />
        )}

        {isExternalSubtitleSelected && !!activeExternalSubtitleText && (
          <View style={styles.externalSubtitleContainer} pointerEvents="none">
            <Text style={[styles.externalSubtitleText, { fontSize: subtitleSize }]}>{activeExternalSubtitleText}</Text>
          </View>
        )}

        {!IS_TV && <View style={StyleSheet.absoluteFill} {...playerPanResponder.panHandlers} />}

        <ZoomHudPill visible={zoomHudVisible} zoomPct={zoomPct} />

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

        <SeekBurstOverlay
          visible={seekOverlay.visible}
          position={seekOverlay.position}
          time={seekOverlay.time}
          burstKey={seekOverlay.burstKey}
        />

        {isVideoBuffering && !showControls && (
          <View style={styles.playerBufferingOverlay} pointerEvents="none">
            <CinemaArcLoader size="large" statusText="Buffering stream..." />
          </View>
        )}

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
                {zoomPct !== 0 && (
                  <FocusableButton
                    style={styles.zoomResetTopPill}
                    onPress={() => resetPlayerZoom(false)}>
                    <Icon name="center-focus-weak" size={15} color="#38BDF8" />
                    <Text style={styles.zoomResetTopPillText}>Reset 0%</Text>
                  </FocusableButton>
                )}
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
              <FocusableButton focusId="player_play_btn" hasTVPreferredFocus={true} onPress={() => {
                const nextPaused = !isPaused;
                setIsPaused(nextPaused);
                if (nextPaused && selectedFile?.name && currentTimeRef.current > 3) {
                  persistPlaybackProgress(selectedFile.name, currentTimeRef.current, durationRef.current);
                }
              }} style={styles.netflixMainPlayBtn}>
                {isVideoBuffering ? <CinemaArcLoader size="medium" compact variant="onCrimson" /> : <Icon name={isPaused ? 'play-arrow' : 'pause'} size={54} color="white" />}
              </FocusableButton>
              <FocusableButton style={styles.netflixSeekCircleBtn} onPress={() => triggerStepSeek(true)}>
                <Icon name="forward-10" size={40} color="white" />
              </FocusableButton>
            </View>

            <View style={styles.netflixBottomContainer}>
              <View style={styles.netflixScrubberRow}>
                <Text style={styles.netflixTimeLabel}>{formatTime(effectiveDisplayTime)}</Text>
                <View
                  ref={progressBarRef}
                  style={styles.netflixProgressHitBox}
                  onLayout={e => {
                    const w = e.nativeEvent.layout.width;
                    setProgressWidth(w);
                    progressBarRef.current?.measureInWindow?.((x: number, _y: number, mw: number) => {
                      progressBarMetrics.current = { pageX: x, width: mw || w };
                    });
                  }}
                  {...seekbarPanResponder.panHandlers}>
                  {isScrubbing && duration > 0 && (
                    <View style={[styles.ytPreviewCard, { left: previewLeftPx }]} pointerEvents="none">
                      <View style={styles.ytPreviewFrameBox}>
                        {previewFrameUri ? (
                          <Image source={{ uri: previewFrameUri }} style={styles.ytPreviewImage} resizeMode="cover" />
                        ) : (
                          <View style={styles.ytPreviewPlaceholder}>
                            <Icon name="movie" size={24} color="#E50914" />
                          </View>
                        )}
                      </View>
                      <View style={styles.ytPreviewTimePill}>
                        <Text style={styles.ytPreviewTimeText}>
                          {formatTime(scrubTime)} / {formatTime(duration)}
                        </Text>
                      </View>
                    </View>
                  )}
                  <View style={[styles.netflixProgressTrack, isScrubbing && { height: 6, borderRadius: 3 }]} pointerEvents="none" />
                  <View style={[styles.netflixProgressFill, { width: `${progressPercent}%` }, isScrubbing && { height: 6, borderRadius: 3 }]} pointerEvents="none" />
                  <View
                    style={[
                      styles.netflixProgressThumb,
                      { left: `${progressPercent}%` },
                      isScrubbing && styles.netflixProgressThumbActive,
                    ]}
                    pointerEvents="none"
                  />
                </View>
                <Text style={styles.netflixTimeLabel}>-{formatTime(remainingTime)}</Text>
              </View>

              <View style={styles.netflixActionRow}>
                <FocusableButton style={[styles.netflixActionPill, activeMenu === 'audio' && styles.netflixActionPillActive]} onPress={() => setActiveMenu(activeMenu === 'audio' ? null : 'audio')}>
                  <Icon name="audiotrack" size={20} color={activeMenu === 'audio' ? '#E50914' : volumeLevel > 1.0 ? '#F5C518' : 'white'} />
                  <Text style={styles.netflixActionText}>
                    {volumeLevel > 1.0 ? `Audio (${Math.round(volumeLevel * 100)}%)` : 'Audio'}
                  </Text>
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
      </FocusLayerContext.Provider>
    );
  };

  // 🔥 2. MOVIE / EPISODE DETAIL OVERLAY (Keeps Catalog FlatLists mounted underneath!) 🔥
  const renderDetailOverlay = () => {
    if (!selectedFile || (playMode !== null && playMode !== 'external')) return null;
    const info = parseMediaFilename(selectedFile.name, false);
    const meta = metadataMap[selectedFile.name] || (selectedFile._folderName ? metadataMap[selectedFile._folderName] : undefined);
    const displayYear = meta?.year || info.year || selectedFile._folderYear || '';
    const realMi: RealMediaInfo | undefined = selectedFile.mediaInfo ?? mediaInfoMap[selectedFile.name];
    const savedSec = savedTimesMap[selectedFile.name] || 0;
    const totalDur = playbackDuration(selectedFile, realMi?.durationSec || 0, savedDurationsMap[selectedFile.name]);
    const resumePct = totalDur > 0 && savedSec > 0 ? Math.min(98, Math.max(3, (savedSec / totalDur) * 100)) : 45;
    const bannerImage = meta?.backdrop || meta?.poster;
    const dlTask = downloads.find(d => d.fileName === (selectedFile.innerName || selectedFile.name));
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

    const vItem = selectedFile;
    const vMi = realMi;
    const vRes = effectiveResolution;
    const stickyBgOpacity = detailScrollY.interpolate({
      inputRange: [80, 165],
      outputRange: [0, 1],
      extrapolate: 'clamp',
    });
    const stickyTitleOpacity = detailScrollY.interpolate({
      inputRange: [110, 180],
      outputRange: [0, 1],
      extrapolate: 'clamp',
    });
    const stickyTitleTranslateY = detailScrollY.interpolate({
      inputRange: [110, 180],
      outputRange: [6, 0],
      extrapolate: 'clamp',
    });
    const detailHeaderTitle = meta?.title || info.cleanTitle;

    return (
      <FocusLayerContext.Provider value="detail">
      <View style={styles.detailScreen}>
        <StatusBar hidden={false} barStyle="light-content" />
        <View style={styles.detailStickyBar} pointerEvents="box-none">
          <Animated.View
            pointerEvents="none"
            style={[styles.detailStickyBg, { opacity: stickyBgOpacity }]}
          />
          <View style={styles.detailStickyRow} pointerEvents="box-none">
            <FocusableButton focusId="detail_back_btn" style={styles.detailStickyBackBtn} onPress={closeDetails}>
              <Icon name="arrow-back" size={22} color="white" />
            </FocusableButton>
            <Animated.Text
              numberOfLines={1}
              style={[
                styles.detailStickyTitle,
                { opacity: stickyTitleOpacity, transform: [{ translateY: stickyTitleTranslateY }] },
              ]}>
              {detailHeaderTitle}
            </Animated.Text>
          </View>
        </View>

        <Animated.ScrollView
          focusable={false}
          style={{ flex: 1 }}
          contentContainerStyle={{ paddingBottom: 40 }}
          scrollEventThrottle={16}
          onScroll={Animated.event(
            [{ nativeEvent: { contentOffset: { y: detailScrollY } } }],
            { useNativeDriver: true },
          )}>
        <View style={styles.detailHero}>
          {bannerImage ? (
            <KenBurnsBackdrop source={{ uri: bannerImage }} style={styles.detailBackdrop} resizeMode="cover" durationMs={280} />
          ) : (
            <View style={styles.detailBackdropPlaceholder}>
              <Text style={styles.placeholderMonogram}>{info.cleanTitle.charAt(0).toUpperCase()}</Text>
            </View>
          )}
          <View style={styles.detailHeroShade} />
        </View>

        <View style={styles.detailContent}>
          <View style={styles.detailHeaderRow}>
            {meta?.poster ? (() => {
              const detailHas4k = Boolean(
                effectiveResolution === '4K' ||
                effectiveResolution === '2160p' ||
                effectiveHasDV ||
                effectiveHasHDR,
              );
              const detailIs1080 = Boolean(
                effectiveResolution === '1080p' ||
                effectiveResolution === 'FHD' ||
                /\b(1080p|fhd)\b/i.test(selectedFile?.name || ''),
              );
              const detailTier: CardGlowTier = detailHas4k ? '4k' : detailIs1080 ? '1080p' : 'sd';
              return (
                <HeroPortraitPoster
                  source={{ uri: meta.poster }}
                  tier={detailTier}
                  isDv={effectiveHasDV}
                  style={styles.detailPosterThumb}
                />
              );
            })() : null}
            <StaggeredSection delayMs={40} style={{ flex: 1 }}>
              <Text style={styles.detailTitle}>{meta?.title || info.cleanTitle}</Text>
              {!!episodeTmdbMeta?.epTitle && (
                <Text style={{ color: '#D1D5DB', fontSize: 14, fontWeight: '600', marginTop: 2, marginBottom: 4 }} numberOfLines={2}>
                  {episodeTmdbMeta.code ? `${episodeTmdbMeta.code} • ` : ''}{episodeTmdbMeta.epTitle}
                </Text>
              )}
              <View style={styles.detailMetaRow}>
                {!!displayYear && <Text style={styles.detailYearText}>{displayYear}</Text>}
                {!!info.seasonEpisode && <View style={styles.seasonBadge}><Text style={styles.seasonBadgeText}>{info.seasonEpisode}</Text></View>}
                {!!(episodeTmdbMeta?.rating || meta?.rating) && <View style={styles.ratingBadge}><Text style={styles.ratingBadgeText}>★ {(episodeTmdbMeta?.rating || meta.rating)}</Text></View>}
                {!!effectiveResolution && <View style={styles.qualityBadge}><Text style={styles.qualityBadgeText}>{effectiveResolution}</Text></View>}
              </View>
              {topChips.length > 0 && (
                <View style={styles.badgeWrapRow}>
                  {topChips.map((b, idx) => (
                    <CascadeSpecPill key={idx} index={idx} style={styles.techChip}><Text style={styles.techChipText}>{b}</Text></CascadeSpecPill>
                  ))}
                </View>
              )}
            </StaggeredSection>
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

          {!!meta?.overview && <Text style={styles.detailOverview}>{episodeTmdbMeta?.overview || meta.overview}</Text>}

          {versionGroup.length > 1 && (
            <View style={styles.versionSelectorBox}>
              <Text style={styles.versionSelectorLabel}>
                AVAILABLE VERSIONS ({versionGroup.length}) • TAP TO SWITCH
              </Text>
              <View style={styles.versionPillsRow}>
                {versionGroup.map((vFile, vIdx) => {
                  const isCurrentVer = vFile.name === selectedFile.name;
                  const verMi = mediaInfoMap[vFile.name];
                  return (
                    <FocusableButton
                      key={vFile.name || vIdx}
                      focusId={`detail_ver_${vIdx}`}
                      Component={VersionOptionCard}
                      active={isCurrentVer}
                      style={[styles.versionPillBtn, isCurrentVer && styles.versionPillBtnActive]}
                      onPress={() => openFile(vFile)}
                      renderRadioIcon={() => (
                        <Icon
                          name={isCurrentVer ? 'radio-button-checked' : 'radio-button-unchecked'}
                          size={18}
                          color={isCurrentVer ? '#E50914' : '#8A94A6'}
                        />
                      )}>
                      <Text style={[styles.versionPillTitle, isCurrentVer && { color: '#FFF' }]}>
                        {getVersionTag(vFile.name)}
                      </Text>
                      <Text style={[styles.versionPillSub, { marginLeft: 0 }]} numberOfLines={1}>
                        {verMi?.totalBytes ? `${formatBytes(verMi.totalBytes)} • ` : ''}{vFile.name}
                      </Text>
                    </FocusableButton>
                  );
                })}
              </View>
            </View>
          )}

          {!!isoInspectionError && <Text accessibilityRole="alert" style={{ color: '#FCA5A5', marginBottom: 12 }}>{isoInspectionError}</Text>}
          <StaggeredSection delayMs={80} style={styles.detailActionButtons}>
            {(() => {
              const isDvdDisc =
                selectedFile?.videoExtension === 'vob' ||
                /\b(dvd|dvd5|dvd9|dvdrip|video_ts|vob)\b/i.test(String(selectedFile?.name || ''));
              const discKind: 'dvd' | 'bluray' = isDvdDisc ? 'dvd' : 'bluray';
              const playLabel = isoInspectionError
                ? 'Retry disc inspection'
                : savedSec > 3
                ? `Resume ${versionGroup.length > 1 ? getVersionTag(selectedFile.name).split(' • ')[0] : 'Watching'} (${formatRemainingLabel(savedSec, totalDur)})`
                : versionGroup.length > 1
                ? `Play ${getVersionTag(selectedFile.name)} in App`
                : 'Play in App';
              return (
                <FocusableButton
                  focusId="detail_play_btn"
                  Component={PlayHeroButton}
                  inspecting={openingIso}
                  pulseTrigger={selectedFile.name}
                  hasTVPreferredFocus={true}
                  style={[
                    styles.primaryPlayButton,
                    openingIso && {
                      backgroundColor: '#121622',
                      borderColor: isDvdDisc ? '#F5C518' : '#38BDF8',
                      overflow: 'hidden',
                      paddingVertical: 11,
                    },
                  ]}
                  disabled={openingIso}
                  onPress={() => isoInspectionError ? retryInspection.current?.() : handlePlayInAppPress(selectedFile)}
                  renderContent={(nudgeX: Animated.Value) =>
                    openingIso ? (
                      <DiscInspectLoader discType={discKind} />
                    ) : (
                      <>
                        <Animated.View style={{ transform: [{ translateX: nudgeX }] }}>
                          <Icon name="play-arrow" size={26} color="white" />
                        </Animated.View>
                        <Text style={styles.primaryPlayButtonText}>{playLabel}</Text>
                      </>
                    )
                  }
                />
              );
            })()}

            <View style={{ flexDirection: 'row', gap: 10 }}>
              <FocusableButton
                focusId="detail_ext_btn"
                Component={ExternalLaunchButton}
                disabled={!!selectedFile.isoPending}
                style={[styles.secondaryPlayButton, { flex: 1 }]}
                onPress={() => openInExternalPlayer(selectedFile)}
                renderContent={(nx: Animated.Value, ny: Animated.Value) => (
                  <>
                    <Animated.View style={{ transform: [{ translateX: nx }, { translateY: ny }] }}>
                      <Icon name="open-in-new" size={20} color="#38BDF8" />
                    </Animated.View>
                    <Text style={styles.secondaryPlayButtonText}>External Player</Text>
                  </>
                )}
              />
              <FocusableButton
                focusId="detail_fav_btn"
                Component={MyListHeartButton}
                saved={isFav}
                style={[styles.secondaryPlayButton, { paddingHorizontal: 16 }, isFav && { borderColor: '#E50914' }]}
                onPress={() => toggleWatchlist(selectedFile.name)}
                renderContent={(heartScale: Animated.Value, rippleScale: Animated.Value, rippleOpacity: Animated.Value) => (
                  <>
                    <View style={styles.heartBurstWrap}>
                      <Animated.View
                        pointerEvents="none"
                        style={[
                          styles.heartBurstRing,
                          { opacity: rippleOpacity, transform: [{ scale: rippleScale }] },
                        ]}
                      />
                      <Animated.View style={{ transform: [{ scale: heartScale }] }}>
                        <Icon name={isFav ? 'favorite' : 'favorite-border'} size={20} color={isFav ? '#E50914' : 'white'} />
                      </Animated.View>
                    </View>
                    <Text style={styles.secondaryPlayButtonText}>{isFav ? 'In My List' : 'My List'}</Text>
                  </>
                )}
              />
            </View>

            {!dlTask ? (
              <FocusableButton
                focusId="detail_dl_btn"
                Component={DownloadActionButton}
                pulseTrigger={selectedFile.name}
                disabled={!!selectedFile.isoPending}
                style={styles.downloadActionBtn}
                onPress={() => handleStartDownload(selectedFile)}
                renderContent={(arrowNudgeY: Animated.Value) => (
                  <>
                    <Animated.View style={{ transform: [{ translateY: arrowNudgeY }] }}>
                      <Icon name="file-download" size={22} color="#46d369" />
                    </Animated.View>
                    <Text style={styles.downloadActionText}>
                      {versionGroup.length > 1 ? `Download (${getVersionTag(selectedFile.name)})` : 'Download'}
                    </Text>
                  </>
                )}
              />
            ) : dlTask.status === 'completed' ? (
              <FocusableButton
                focusId="detail_dl_btn"
                Component={DownloadActionButton}
                style={styles.downloadedReadyCard}
                onPress={handleOpenDownloadFolder}
                renderContent={(arrowNudgeY: Animated.Value) => (
                  <>
                    <Animated.View style={{ transform: [{ translateY: arrowNudgeY }] }}>
                      <Icon name="check-circle" size={20} color="#46d369" />
                    </Animated.View>
                    <Text style={styles.downloadedReadyText}>Saved in Download/GDStream • Tap to Open Folder</Text>
                  </>
                )}
              />
            ) : (
              <FocusableButton
                focusId="detail_dl_btn"
                Component={DownloadActionButton}
                isActiveDownload={true}
                progress={dlTask.progress}
                style={styles.downloadProgressCard}
                onPress={() => { closeDetails(); setActiveTab('downloads'); }}
                renderContent={(arrowNudgeY: Animated.Value) => (
                  <View style={{ width: '100%' }}>
                    <View style={styles.dlCardHeaderRow}>
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                        <Animated.View style={{ transform: [{ translateY: arrowNudgeY }] }}>
                          <Icon name="downloading" size={16} color="#46d369" />
                        </Animated.View>
                        <Text style={styles.dlCardStatusText}>
                          {dlTask.status === 'downloading' ? `Downloading • ${formatBytes(dlTask.speedBytesPerSec)}/s` : `Download ${dlTask.status.toUpperCase()}`}
                        </Text>
                      </View>
                      <Text style={styles.dlCardPercentText}>{dlTask.progress.toFixed(1)}%</Text>
                    </View>
                    <Text style={styles.dlCardSubText}>
                      {formatBytes(dlTask.downloadedBytes)} / {formatBytes(dlTask.totalBytes)} • Tap to open Downloads
                    </Text>
                  </View>
                )}
              />
            )}

            {(() => {
              const curEpIdx = playableFolderEpisodes.findIndex((ep: any) => ep.name === selectedFile.name);
              const nextEpFile = curEpIdx >= 0 && curEpIdx + 1 < playableFolderEpisodes.length
                ? playableFolderEpisodes[curEpIdx + 1]
                : null;
              if (!nextEpFile) return null;
              const nextInfo = parseMediaFilename(nextEpFile.name, false);
              const nextEpCard = episodeCardsMetaMap[nextEpFile.name];
              const nextLabel = nextEpCard?.epTitle
                ? `${nextInfo.seasonEpisode || `E${nextInfo.episodeNum}`} • ${nextEpCard.epTitle}`
                : (nextInfo.seasonEpisode ? `${nextInfo.seasonEpisode} • ${nextInfo.cleanTitle}` : nextInfo.cleanTitle);
              return (
                <FocusableButton
                  focusId="detail_next_ep_btn"
                  style={{
                    flexDirection: 'row',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    backgroundColor: '#161B26',
                    borderWidth: 1,
                    borderColor: '#2E384D',
                    borderRadius: 10,
                    paddingVertical: 11,
                    paddingHorizontal: 14,
                    marginTop: 2,
                  }}
                  onPress={() => {
                    setVersionGroup([nextEpFile]);
                    openFile(nextEpFile);
                  }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10, flex: 1 }}>
                    <Icon name="skip-next" size={22} color="#F5C518" />
                    <View style={{ flex: 1 }}>
                      <Text style={{ color: '#9CA3AF', fontSize: 10, fontWeight: '800', letterSpacing: 0.6 }}>NEXT EPISODE</Text>
                      <Text style={{ color: '#FFF', fontSize: 13, fontWeight: '700', marginTop: 1 }} numberOfLines={1}>
                        {nextLabel}
                      </Text>
                    </View>
                  </View>
                  <Icon name="chevron-right" size={20} color="#9CA3AF" />
                </FocusableButton>
              );
            })()}
          </StaggeredSection>

          {/* Standalone Official Trailer Cinema Card */}
          <StaggeredSection delayMs={105} style={styles.trailerStripSection}>
            <FocusableButton
              focusId="detail_trailer_btn"
              Component={TrailerGlowButton}
              available={trailerState.available}
              disabled={trailerState.loading || !trailerState.available}
              onPress={() => {
                if (trailerState.available && trailerState.cacheKey && SecurityVault?.playYoutubeTrailer) {
                  SecurityVault.playYoutubeTrailer(trailerState.cacheKey, trailerState.title);
                }
              }}
              style={{
                backgroundColor: trailerState.available ? '#151226' : '#11131A',
                borderColor: trailerState.available ? 'rgba(139, 92, 246, 0.35)' : '#252936',
                borderWidth: 1,
                borderRadius: 12,
                paddingVertical: 13,
                paddingHorizontal: 16,
                flexDirection: 'row',
                alignItems: 'center',
                justifyContent: 'center',
                opacity: (trailerState.loading || !trailerState.available) ? 0.45 : 1,
              }}
            >
              <Text style={{
                color: trailerState.available ? '#EDE9FE' : '#71717A',
                fontWeight: '700',
                fontSize: 14,
                letterSpacing: 0.2,
              }}>
                {trailerState.loading
                  ? '⏳ Verifying Official Trailer...'
                  : trailerState.available
                  ? `🎬 Watch Official Trailer (In-App • ${trailerState.bestQuality})`
                  : '🎬 Trailer Unavailable'}
              </Text>
            </FocusableButton>
          </StaggeredSection>

          {/* Standalone Obsidian-Glass MediaInfo Telemetry Card */}
          <StaggeredSection delayMs={135} style={styles.mediaInfoCard}>
            <View pointerEvents="none" style={styles.mediaInfoTopHighlight} />
            <View style={styles.mediaInfoHeaderRow}>
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 7, flex: 1 }}>
                <Icon name="memory" size={16} color="#F5C518" />
                <Text style={[styles.mediaInfoHeaderTitle, { color: '#F1F5F9' }]}>
                  {versionGroup.length > 1 ? `MEDIAINFO • ${getVersionTag(vItem.name).toUpperCase()}` : 'MEDIAINFO TECHNICAL SPECS'}
                </Text>
              </View>
              {scanningMediaInfo && !vMi && (
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
                  <CinemaArcLoader size="small" compact />
                  <Text style={styles.scanningText}>Scanning...</Text>
                </View>
              )}
            </View>

            {openingIso ? <DiscSpecsSkeleton /> : vMi ? (() => {
              const resDisplay = vRes === '4K' || vRes === '2160p' ? '4K UHD' : vRes || 'HD';
              const codecShort = vMi.videoCodec === 'AVC' ? 'AVC' : vMi.videoCodec || 'Video';
              const dynamicRangeTag = vMi.hasDV && vMi.hasHDR ? 'DV + HDR10' : vMi.hasDV ? 'DV' : vMi.hasHDR ? 'HDR10' : 'SDR';
              const preferredAudioIdx = Math.max(
                0,
                vMi.audioTracks ? vMi.audioTracks.findIndex(t => /tamil/i.test(t.language || t.summary || '')) : 0,
              );
              const lowerName = String(vItem.name || '').toLowerCase();
              const isDvdContainer = vItem?.videoExtension === 'vob' || /\b(dvd|video_ts|vob)\b/.test(lowerName);
              const isIsoContainer = vItem?.archiveFormat === 'ISO' || /\.iso$|m2ts$/.test(lowerName);
              const containerLabel = isDvdContainer
                ? 'DVD • VIDEO_TS'
                : isIsoContainer
                ? 'BDMV • ISO'
                : /\.mkv$/.test(lowerName)
                ? 'MKV • Matroska'
                : /\.mp4$|\.m4v$/.test(lowerName)
                ? 'MP4 • MPEG-4'
                : 'Digital Stream';
              const fpsClean = vMi.frameRate > 0 ? vMi.frameRate.toFixed(3).replace(/0+$/, '').replace(/\.$/, '') : '';
              const explicitMbps = vMi.videoBitrateMbps && vMi.videoBitrateMbps > 0 ? vMi.videoBitrateMbps : 0;
              const bitrateTitle = explicitMbps > 0
                ? `${explicitMbps >= 10 ? explicitMbps.toFixed(1) : explicitMbps.toFixed(2).replace(/\.?0+$/, '')} Mbps`
                : '—';
              const bitrateSub = fpsClean
                ? `${fpsClean} fps`
                : explicitMbps > 0
                ? 'Video stream bitrate'
                : 'Frame rate';
              return (
                <View style={{ gap: 12, marginTop: 10 }}>
                  <View style={styles.miTelemetryGrid}>
                    <View style={styles.miTelemetryTile}>
                      <Text style={styles.miTileLabel}>RESOLUTION & CODEC</Text>
                      <View style={styles.miTileBadgesRow}>
                        <View style={[styles.miMiniBadge, (vRes === '4K' || vRes === '2160p') && styles.miMiniBadge4K]}>
                          <Text style={styles.miMiniBadgeText}>{resDisplay}</Text>
                        </View>
                        {!!codecShort && (
                          <View style={styles.miMiniBadge}>
                            <Text style={styles.miMiniBadgeText}>{codecShort}</Text>
                          </View>
                        )}
                        <View style={[styles.miMiniBadge, (vMi.hasDV || vMi.hasHDR) && styles.miMiniBadgeHdr]}>
                          <Text style={[styles.miMiniBadgeText, (vMi.hasDV || vMi.hasHDR) && { color: '#000' }]}>
                            {dynamicRangeTag}
                          </Text>
                        </View>
                      </View>
                      <Text style={styles.miTileSub}>
                        {vMi.width > 0 ? `${vMi.width}×${vMi.height}` : containerLabel}
                      </Text>
                    </View>

                    <View style={styles.miTelemetryTile}>
                      <Text style={styles.miTileLabel}>BITRATE & FPS</Text>
                      <Text style={styles.miTileValue}>{bitrateTitle}</Text>
                      <Text style={styles.miTileSub}>{bitrateSub}</Text>
                      <BitrateMeterBar
                        bitrateMbps={explicitMbps}
                        color={vMi.hasDV ? '#C084FC' : (vRes === '4K' || vRes === '2160p') ? '#F5C518' : '#38BDF8'}
                      />
                    </View>

                    <View style={styles.miTelemetryTile}>
                      <Text style={styles.miTileLabel}>FILE SIZE</Text>
                      <Text style={styles.miTileValue}>
                        {vMi.totalBytes > 0 ? formatBytes(vMi.totalBytes) : '—'}
                      </Text>
                      <Text style={styles.miTileSub}>Total stream size</Text>
                    </View>

                    <View style={styles.miTelemetryTile}>
                      <Text style={styles.miTileLabel}>RUNTIME</Text>
                      <Text style={styles.miTileValue}>
                        {vMi.durationSec > 0 ? formatRuntime(vMi.durationSec) : '—'}
                      </Text>
                      <Text style={styles.miTileSub}>
                        {fpsClean ? `${containerLabel} • ${fpsClean} fps` : containerLabel}
                      </Text>
                    </View>
                  </View>

                  {vMi.audioTracks && vMi.audioTracks.length > 0 && (
                    <View style={styles.miSpecRow}>
                      <Text style={styles.miSpecLabel}>AUDIO ({vMi.audioTracks.length})</Text>
                      <View style={styles.miTrackPillWrap}>
                        {vMi.audioTracks.map((at, idx) => {
                          const isPreferred = idx === preferredAudioIdx;
                          return (
                            <CascadeSpecPill
                              key={idx}
                              index={idx}
                              style={[styles.miAudioPill, isPreferred && styles.miAudioPillPreferred]}>
                              <View style={styles.miAudioRowInner}>
                                {isPreferred && <MiniEqualizerPulse active={true} color="#F5C518" />}
                                <Text style={[styles.miAudioPillText, isPreferred && { color: '#FFF', fontWeight: '800' }]}>
                                  {formatCompactAudioLabel(at, vMi.hasAtmos)}
                                </Text>
                              </View>
                            </CascadeSpecPill>
                          );
                        })}
                      </View>
                    </View>
                  )}

                  {vMi.subtitleTracks && vMi.subtitleTracks.length > 0 && (() => {
                    const groupedSubs: Array<{ label: string; count: number }> = [];
                    const subIdxMap = new Map<string, number>();
                    for (const st of vMi.subtitleTracks) {
                      const lbl = `${st.language} • ${st.format}`;
                      const existingIdx = subIdxMap.get(lbl);
                      if (existingIdx !== undefined) {
                        groupedSubs[existingIdx].count += 1;
                      } else {
                        subIdxMap.set(lbl, groupedSubs.length);
                        groupedSubs.push({ label: lbl, count: 1 });
                      }
                    }
                    const INITIAL_SUB_LIMIT = 6;
                    const visibleSubs = subsExpanded ? groupedSubs : groupedSubs.slice(0, INITIAL_SUB_LIMIT);
                    const hiddenCount = groupedSubs.length - visibleSubs.length;
                    return (
                      <View style={styles.miSpecRow}>
                        <Text style={styles.miSpecLabel}>SUBTITLES ({vMi.subtitleTracks.length})</Text>
                        <View style={styles.miTrackPillWrap}>
                          {visibleSubs.map((gs, idx) => (
                            <CascadeSpecPill key={idx} index={idx} style={styles.miSubPill}>
                              <Text style={styles.miSubPillText}>
                                {gs.count > 1 ? `${gs.label} ×${gs.count}` : gs.label}
                              </Text>
                            </CascadeSpecPill>
                          ))}
                          {groupedSubs.length > INITIAL_SUB_LIMIT && (
                            <FocusableButton
                              focusId="detail_subs_toggle"
                              style={[styles.miSubPill, { backgroundColor: '#1E2433', borderColor: '#4B5563' }]}
                              onPress={() => setSubsExpanded(prev => !prev)}>
                              <Text style={[styles.miSubPillText, { color: '#F5C518', fontWeight: '800' }]}>
                                {subsExpanded ? 'Show less' : `+${hiddenCount} more`}
                              </Text>
                            </FocusableButton>
                          )}
                        </View>
                      </View>
                    );
                  })()}
                </View>
              );
            })() : !scanningMediaInfo ? (
              <Text style={styles.rawFileName}>{vItem.name}</Text>
            ) : null}

            <CopySpecPill
              label="SOURCE FILE"
              text={vItem.name}
              onCopy={copyTextSafely}
              renderIcon={(copied: boolean) => (
                <Icon name={copied ? 'check' : 'content-copy'} size={13} color={copied ? '#46D369' : '#94A3B8'} />
              )}
            />
          </StaggeredSection>
        </View>
        </Animated.ScrollView>
      </View>
      </FocusLayerContext.Provider>
    );
  };

  // 🔥 3. DOWNLOADS TAB OVERLAY 🔥
  const renderDownloadsOverlay = () => {
    if (activeTab !== 'downloads') return null;
    return (
      <FocusLayerContext.Provider value="downloads">
      <View style={styles.downloadsOverlayScreen}>
        <StatusBar hidden={false} barStyle="light-content" />
        <View style={styles.topHeaderBar}>
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
            <FocusableButton focusId="dl_back_btn" style={styles.headerIconBtn} onPress={() => setActiveTab('catalog')}>
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
            renderItem={({ item, index }) => {
              const isRunning = item.status === 'downloading';
              const isDone = item.status === 'completed';
              return (
                <View style={styles.dlItemCard}>
                  <View style={{ flexDirection: 'row', gap: 12 }}>
                    {item.poster ? (
                      <FadeInPoster source={{ uri: item.poster }} style={styles.dlPosterThumb} resizeMode="cover" />
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
                        <FocusableButton focusId={`dl_item_${index}`} style={styles.dlPrimaryBtn} onPress={() => { const lf = { name: item.fileName, localPath: item.filePath }; setVersionGroup([lf]); openFile(lf); handlePlayInAppPress(lf); }}>
                          <Icon name="play-arrow" size={20} color="white" />
                          <Text style={styles.dlBtnText}>Play in App</Text>
                        </FocusableButton>
                        <FocusableButton style={styles.dlSecondaryBtn} onPress={() => openInExternalPlayer({ name: item.fileName, localPath: item.filePath })}>
                          <Icon name="open-in-new" size={18} color="white" />
                          <Text style={styles.dlBtnText}>External Player</Text>
                        </FocusableButton>
                      </>
                    ) : isRunning ? (
                      <FocusableButton focusId={`dl_item_${index}`} style={styles.dlSecondaryBtn} onPress={() => DownloadEngine?.pauseDownload(item.id)}>
                        <Icon name="pause" size={18} color="white" />
                        <Text style={styles.dlBtnText}>Pause</Text>
                      </FocusableButton>
                    ) : (
                      <FocusableButton focusId={`dl_item_${index}`} style={styles.dlPrimaryBtn} onPress={() => DownloadEngine?.resumeDownload(item.id)}>
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
      </View>
      </FocusLayerContext.Provider>
    );
  };

  // 🔥 4. POSTER CATALOG HOME + SERIES OVERLAY SCREEN 🔥
  const numColumns = viewMode === 'grid' ? (IS_TV ? 5 : 2) : 1;
  const activeDownloadsCount = downloads.filter(d => d.status === 'downloading').length;

  const renderCatalogCard = ({ item, index, isFolderCard = false }: { item: any; index: number; isFolderCard?: boolean }) => {
    const classified = item?.mimeType?.includes('folder') ? folderClassifications[folderPathForItem(item)] : undefined;
    const isFolder = isSeriesContainerItem(item, classified);
    const isPhysicalFolder = Boolean(item?.mimeType?.includes('folder'));
    const isMovieFolder = Boolean(item?._movieFolder || classified?.kind === 'movie');
    const rawInfo = parseMediaFilename(item.name, isFolder);
    const effectiveBadges = rawInfo.badges.length > 0
      ? rawInfo.badges
      : (item._folderLangName ? [item._folderLangName] : (classified?.langName ? [classified.langName] : []));
    const info: ParsedMediaInfo = isMovieFolder
      ? {
          ...rawInfo,
          year: rawInfo.year || item._folderYear || classified?.year || '',
          langCode: rawInfo.langCode || item._folderLangCode || classified?.langCode || '',
          langName: rawInfo.langName || item._folderLangName || classified?.langName || '',
          badges: effectiveBadges,
        }
      : rawInfo;
    const meta = metadataMap[item.name] || (item._folderName ? metadataMap[item._folderName] : undefined);
    const realMi: RealMediaInfo | undefined = item.mediaInfo ?? mediaInfoMap[item.name];
    const activeVerForCard = item._versions?.find((v: any) => (savedTimesMap[v.name] || 0) > 3) || item._versions?.[0] || item;
    const savedSec = savedTimesMap[activeVerForCard.name] || 0;
    const totalDur = savedDurationsMap[activeVerForCard.name] || mediaInfoMap[activeVerForCard.name]?.durationSec || 0;
    const resumePct = totalDur > 0 && savedSec > 0 ? Math.min(98, Math.max(5, (savedSec / totalDur) * 100)) : 45;
    const cardRes = highestResolutionLabel(item, mediaInfoMap) || realMi?.resolution || info.resolution || '';
    const isFav = Boolean(
      watchlist[item.name] ||
      (item._folderName && watchlist[item._folderName]) ||
      item._versions?.some((v: any) => watchlist[v.name]),
    );
    const versionCount = item._versions?.length || 1;
    const showSeriesBadge = isFolder || (!isInsideFolder && !!info.seasonEpisode);

    const cardFocusId = isFolderCard ? `fcard_${index}` : `card_${index}`;
    const recordCardFocus = () => {
      if (isFolderCard) lastFolderCardIdRef.current = cardFocusId;
      else lastHomeCardIdRef.current = cardFocusId;
    };
    const catalogTransitionKey = `${activeCategory}|${sortBy}|${viewMode}|${currentPath}`;
    const has4kOrHdr = Boolean(
      cardRes === '4K' ||
      cardRes === '2160p' ||
      realMi?.hasDV ||
      realMi?.hasHDR ||
      info.hasDV ||
      info.hasHDR,
    );
    const is1080Card = Boolean(
      cardRes === '1080p' ||
      cardRes === 'FHD' ||
      /\b(1080p|fhd|full\s*hd)\b/i.test(item.name || ''),
    );
    const isSdCard = Boolean(
      cardRes === '720p' ||
      cardRes === '480i' ||
      cardRes === '576i' ||
      cardRes === 'SD' ||
      cardRes === 'DVD' ||
      /\b(720p|480p|480i|576i|dvd|dvd5|dvd9|dvdrip|vob)\b/i.test(`${cardRes} ${item.name || ''}`),
    );
    const cardGlowTier: CardGlowTier = versionCount > 1 ? 'multi' : has4kOrHdr ? '4k' : is1080Card ? '1080p' : isSdCard ? 'sd' : 'none';
    const isDvCard = Boolean(realMi?.hasDV || info.hasDV);
    const ratingNumeric = parseFloat(String(meta?.rating || '0'));
    const hasHighRatingGlint = Number.isFinite(ratingNumeric) && ratingNumeric >= 7.5;

    if (viewMode === 'list') {
      return (
        <WaveCascadeCard index={index} transitionKey={catalogTransitionKey}>
          <FocusableButton
            focusId={cardFocusId}
            enableSheen={true}
            style={styles.listCard}
            onFocus={recordCardFocus}
            onPress={() => {
              recordCardFocus();
              handlePress(item);
            }}>
            <View style={styles.listPosterFallback}>
              <Icon name={!isMovieFolder && isPhysicalFolder ? 'folder' : 'movie'} size={26} color={!isMovieFolder && isPhysicalFolder ? '#F5C518' : '#E50914'} />
              {!!meta?.poster && (
                <FadeInPoster source={{ uri: meta.poster }} style={[styles.listPosterThumb, StyleSheet.absoluteFill]} resizeMode="cover" />
              )}
            </View>
            <View style={styles.listCardBody}>
              <Text style={styles.listCardTitle} numberOfLines={1}>
                {!isFolder && info.episodeNum > 0 ? `E${info.episodeNum < 10 ? `0${info.episodeNum}` : info.episodeNum} • ${info.cleanTitle}` : info.cleanTitle}
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
        </WaveCascadeCard>
      );
    }

    const epCardMeta = !isFolder ? episodeCardsMetaMap[item.name] : undefined;
    const isEpisodeStillCard = Boolean(isFolderCard && !isFolder && (info.episodeNum > 0 || !!info.seasonEpisode || !!epCardMeta));
    const cardImageUri = isEpisodeStillCard
      ? (epCardMeta?.stillUrl || meta?.backdrop || meta?.poster)
      : meta?.poster;

    return (
      <WaveCascadeCard index={index} transitionKey={catalogTransitionKey} style={styles.posterGridSlot}>
        <TierRimGlowCard tier={cardGlowTier} isDv={isDvCard} borderRadius={12}>
          <FocusableButton
            focusId={cardFocusId}
            enableSheen={true}
            style={[styles.posterCard, { maxWidth: '100%', margin: 0 }]}
            onFocus={recordCardFocus}
            onPress={() => {
              recordCardFocus();
              handlePress(item);
            }}>
            <View style={[styles.posterImageContainer, isEpisodeStillCard && { aspectRatio: 16 / 9 }]}>
              <View style={styles.posterFallback}>
                <Icon name={!isMovieFolder && isPhysicalFolder ? 'folder-special' : 'local-movies'} size={isEpisodeStillCard ? 30 : 38} color={!isMovieFolder && isPhysicalFolder ? '#F5C518' : '#E50914'} />
                <Text style={styles.posterFallbackTitle} numberOfLines={2}>{epCardMeta?.epTitle || info.cleanTitle}</Text>
              </View>
              {!!cardImageUri && (
                <FadeInPoster source={{ uri: cardImageUri }} style={[styles.posterImage, StyleSheet.absoluteFill]} resizeMode="cover" />
              )}

              <View style={styles.posterTopBadges}>
                {showSeriesBadge ? (() => {
                  const badgeInfo = getHomeSeriesBadgeText(item, info, meta);
                  return (
                    <SeriesCycleBadge
                      seasonText={badgeInfo.seasonText}
                      rating={badgeInfo.rating}
                    />
                  );
                })() : info.seasonEpisode ? (
                  <View style={styles.folderPill}><Text style={styles.folderPillText}>{info.seasonEpisode}</Text></View>
                ) : meta?.rating ? (
                  <GlintBadge
                    active={hasHighRatingGlint}
                    tintColor="rgba(253, 230, 138, 0.52)"
                    style={styles.ratingPill}>
                    <Text style={styles.ratingPillText}>★ {meta.rating}</Text>
                  </GlintBadge>
                ) : <View />}

                {!!cardRes && (
                  <GlintBadge
                    active={cardRes === '4K' || cardRes === '2160p' || cardRes === '1080p' || cardRes === 'FHD'}
                    tintColor={cardRes === '1080p' || cardRes === 'FHD' ? 'rgba(56, 189, 248, 0.45)' : undefined}
                    style={[styles.resPill, cardRes === '4K' && styles.resPill4K]}>
                    <Text style={styles.resPillText}>{cardRes}</Text>
                  </GlintBadge>
                )}
              </View>

              {versionCount > 1 && (
                <GlintBadge
                  active={true}
                  pulse={true}
                  tintColor="rgba(56, 189, 248, 0.55)"
                  style={[styles.multiVersionBadge, { borderWidth: 1, borderColor: 'rgba(56, 189, 248, 0.65)' }]}>
                  <Text style={styles.multiVersionBadgeText}>{versionCount} VERSIONS</Text>
                </GlintBadge>
              )}

              {isFav && (
                <View style={styles.posterFavBadge}><Icon name="favorite" size={13} color="#E50914" /></View>
              )}

              {savedSec > 3 && (
                <View style={styles.resumeBarBg}>
                  <View style={[styles.resumeBarFill, { width: `${resumePct}%` }]}>
                    <ProgressGlowTip />
                  </View>
                </View>
              )}
            </View>

        <View style={styles.posterCardFooter}>
          <Text style={styles.posterTitle} numberOfLines={1}>
            {!isFolder && info.episodeNum > 0
              ? (epCardMeta?.epTitle ? `E${String(info.episodeNum).padStart(2, '0')} • ${epCardMeta.epTitle}` : `Episode ${info.episodeNum} • ${info.cleanTitle}`)
              : info.cleanTitle}
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
        </TierRimGlowCard>
      </WaveCascadeCard>
    );
  };

  const renderContinueWatchingHeader = () => {
    if (searchQuery || activeCategory !== 'all' || continueWatchingItems.length === 0) return null;
    const prefix = isInsideFolder ? 'f' : '';
    return (
      <View style={styles.cwSection}>
        <Text style={styles.cwSectionTitle}>Continue Watching ⏯️</Text>
        <ScrollView focusable={false} horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: 10, paddingHorizontal: 6 }}>
          {continueWatchingItems.map((cwItem, idx) => {
            const cwInfo = parseMediaFilename(cwItem.name, false);
            const cwMeta = metadataMap[cwItem.name];
            const cwSec = savedTimesMap[cwItem.name] || 0;
            const cwDur = savedDurationsMap[cwItem.name] || mediaInfoMap[cwItem.name]?.durationSec || 0;
            const cwPct = cwDur > 0 ? Math.min(98, Math.max(5, (cwSec / cwDur) * 100)) : 45;
            const imgUri = cwMeta?.backdrop || cwMeta?.poster;
            return (
              <View key={cwItem.id || cwItem.name || idx} style={styles.cwCard}>
                {IS_TV && (
                  <FocusableButton
                    focusId={`${prefix}cw_remove_${idx}`}
                    style={{
                      flexDirection: 'row',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      backgroundColor: '#1B202C',
                      paddingHorizontal: 10,
                      paddingVertical: 5,
                      borderBottomWidth: 1,
                      borderBottomColor: '#262D3D',
                      borderTopLeftRadius: 8,
                      borderTopRightRadius: 8,
                      borderWidth: 2,
                      borderColor: 'transparent',
                    }}
                    onPress={() => removeContinueWatchingItem(cwItem)}>
                    <Text style={{ color: '#9BA6B8', fontSize: 10, fontWeight: '800' }} numberOfLines={1}>
                      Remove
                    </Text>
                    <Icon name="close" size={14} color="#FFFFFF" />
                  </FocusableButton>
                )}
                {!IS_TV && (
                  <TouchableOpacity
                    style={styles.cwRemoveBtn}
                    accessibilityLabel={`Remove ${cwInfo.cleanTitle} from Continue Watching`}
                    hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                    onPress={() => removeContinueWatchingItem(cwItem)}>
                    <Icon name="close" size={15} color="#FFFFFF" />
                  </TouchableOpacity>
                )}
                <FocusableButton
                  focusId={`${prefix}cw_play_${idx}`}
                  style={{ borderWidth: 2, borderColor: 'transparent', borderBottomLeftRadius: 8, borderBottomRightRadius: 8 }}
                  onPress={() => {
                    if (/\.iso$/i.test(cwItem.name || '') && !cwItem.isZipEntry) { void openZipArchiveFile(cwItem); return; }
                    const parentGroup = displayedFiles.find((df: any) => df._versions?.some((v: any) => v.name === cwItem.name));
                    setVersionGroup(parentGroup?._versions || [cwItem]);
                    openFile(cwItem);
                    handlePlayInAppPress(cwItem);
                  }}>
                  <View style={styles.cwImageWrap}>
                    {imgUri ? (
                      <FadeInPoster source={{ uri: imgUri }} style={styles.cwImage} resizeMode="cover" />
                    ) : (
                      <View style={styles.cwFallback}><Icon name="play-circle-outline" size={32} color="#E50914" /></View>
                    )}
                    <View style={styles.cwPlayBadge}><Icon name="play-arrow" size={18} color="white" /></View>
                    <View style={styles.resumeBarBg}>
                      <View style={[styles.resumeBarFill, { width: `${cwPct}%` }]}>
                        <ProgressGlowTip />
                      </View>
                    </View>
                  </View>
                  <View style={styles.cwFooter}>
                    <Text style={styles.cwTitle} numberOfLines={1}>
                      {cwInfo.seasonEpisode ? `${cwInfo.seasonEpisode} • ${cwInfo.cleanTitle}` : cwInfo.cleanTitle}
                    </Text>
                    <Text style={styles.cwTimeText}>Resume • {formatRemainingLabel(cwSec, cwDur)}</Text>
                  </View>
                </FocusableButton>
              </View>
            );
          })}
        </ScrollView>
      </View>
    );
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

  if (!navigationReady) return <View style={styles.container}><ShimmerCardGrid columns={numColumns} count={numColumns >= 5 ? 10 : 6} /></View>;

  const isCatalogCovered = !!selectedFile || playMode === 'internal' || activeTab === 'downloads';

  return (
    <ActiveLayerContext.Provider value={currentActiveLayer}>
    <HeaderFocusableContext.Provider value={headerFocusUnlocked}>
    <FocusLayerContext.Provider value="home">
    <View style={styles.container}>
      <StatusBar hidden={false} barStyle="light-content" />

      <View
        style={{ flex: 1 }}
        importantForAccessibility={isCatalogCovered ? 'no-hide-descendants' : 'auto'}
        pointerEvents={isCatalogCovered ? 'none' : 'auto'}>
        <View style={styles.topHeaderBar}>
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <View style={styles.logoMark}><Text style={styles.logoMarkText}>▶</Text></View>
            <Text style={styles.headerTitle}>GDStream</Text>
          </View>

          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <FocusableButton focusId="hdr_dl" isHeaderControl={true} style={styles.headerIconBtn} onPress={() => setActiveTab('downloads')}>
              <DownloadPulseBadge active={activeDownloadsCount > 0}>
                <Icon name="file-download" size={22} color={activeDownloadsCount > 0 ? '#46d369' : 'white'} />
                {downloads.length > 0 && (
                  <View style={styles.dlBadgeDot}><Text style={styles.dlBadgeDotText}>{downloads.length}</Text></View>
                )}
              </DownloadPulseBadge>
            </FocusableButton>
            <FocusableButton focusId="hdr_refresh" accessibilityLabel="Refresh Library" isHeaderControl={true} style={styles.headerIconBtn} onPress={handleRefresh}>
              <Icon name="refresh" size={21} color="white" />
            </FocusableButton>
            <FocusableButton focusId="hdr_view" isHeaderControl={true} style={styles.headerIconBtn} onPress={() => setViewMode(prev => (prev === 'grid' ? 'list' : 'grid'))}>
              <Icon name={viewMode === 'grid' ? 'view-list' : 'grid-view'} size={22} color="white" />
            </FocusableButton>
          </View>
        </View>
        <TopSyncProgressBar visible={syncingCatalog || refreshing || isEnrichingMetadata} style={{ marginTop: -8, marginBottom: 8 }} />

        <View style={styles.searchRow}>
          {IS_TV ? (
            <FocusableButton
              focusId="search_btn"
              isHeaderControl={true}
              style={{ flex: 1, flexDirection: 'row', alignItems: 'center', borderRadius: 10, paddingVertical: 10, paddingHorizontal: 12, borderWidth: 2, borderColor: 'transparent' }}
              onPress={() => setIsTvSearchModalOpen(true)}>
              <Icon name="search" size={20} color="#888" />
              <Text style={{ color: searchQuery ? '#FFF' : '#777', fontSize: 15, marginLeft: 10 }} numberOfLines={1}>
                {searchQuery || 'Search movies, series, 4K, Tamil...'}
              </Text>
            </FocusableButton>
          ) : (
            <>
              <Icon name="search" size={20} color="#888" style={{ marginLeft: 12 }} />
              <TextInput
                ref={searchInputRef}
                style={styles.searchBarInput}
                placeholder="Search movies, series, 4K, Tamil..."
                placeholderTextColor="#777"
                value={searchQuery}
                onChangeText={setSearchQuery}
                autoCorrect={false}
                returnKeyType="search"
              />
            </>
          )}
          {!!searchQuery && (
            <FocusableButton focusId="search_clear" isHeaderControl={true} onPress={() => setSearchQuery('')} style={{ padding: 8, borderRadius: 8, marginLeft: 'auto' }}>
              <Icon name="close" size={18} color="#AAA" />
            </FocusableButton>
          )}
        </View>

        <View style={{ marginBottom: 10 }}>
          <ScrollView focusable={false} horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ paddingHorizontal: 14, gap: 8, alignItems: 'center' }}>
            <FocusableButton
              focusId="hdr_sort" isHeaderControl={true}
              style={[styles.sortToggleBtn, { paddingVertical: 7, paddingHorizontal: 11 }]}
              onPress={() => setSortBy(prev => (prev === 'newest' ? 'az' : 'newest'))}>
              <Icon name="sort" size={15} color="#E50914" />
              <Text style={styles.sortToggleText}>{sortBy === 'newest' ? 'Newest' : 'A - Z'}</Text>
            </FocusableButton>
            {FILTER_CATEGORIES.map(cat => {
              const isSelected = activeCategory === cat.id;
              return (
                <FocusableButton
                  key={cat.id}
                  focusId={`chip_${cat.id}`} isHeaderControl={true}
                  style={[styles.filterChip, isSelected && styles.filterChipActive]}
                  onPress={() => setActiveCategory(cat.id)}>
                  <Text style={[styles.filterChipText, isSelected && styles.filterChipTextActive]}>{cat.label}</Text>
                </FocusableButton>
              );
            })}
          </ScrollView>
        </View>

        <View style={{ flex: 1, position: 'relative' }}>
          {/* Home Catalog FlatList stays permanently mounted so scroll position & card focus ref NEVER reset! */}
          <FocusLayerContext.Provider value="home">
          <View
            style={{ flex: 1 }}
            importantForAccessibility={isInsideFolder ? 'no-hide-descendants' : 'auto'}
            pointerEvents={isInsideFolder ? 'none' : 'auto'}>
            {loading ? (
              <ShimmerCardGrid columns={numColumns} count={numColumns >= 5 ? 10 : 6} />
            ) : (
              <FlatList
                focusable={false}
                key={`home-catalog-${viewMode}-${numColumns}`}
                data={homeDisplayedFiles}
                numColumns={numColumns}
                initialNumToRender={15}
                maxToRenderPerBatch={10}
                windowSize={7}
                keyboardShouldPersistTaps="handled"
                removeClippedSubviews={false}
                ListHeaderComponent={!isInsideFolder ? renderContinueWatchingHeader() ?? undefined : undefined}
                refreshControl={
                  !IS_TV ? <RefreshControl refreshing={refreshing && !isInsideFolder} onRefresh={handleRefresh} tintColor="#E50914" colors={['#E50914']} progressBackgroundColor="#181C24" /> : undefined
                }
                contentContainerStyle={{ paddingHorizontal: 10, paddingBottom: 30 }}
                keyExtractor={(item, idx) => item.id || item.name || String(idx)}
                renderItem={({ item, index }) => renderCatalogCard({ item, index, isFolderCard: false })}
              />
            )}
          </View>
          </FocusLayerContext.Provider>

          {/* Series / Folder Overlay Layer */}
          {isInsideFolder && (
            <FocusLayerContext.Provider value="folder">
            <View style={styles.seriesFolderOverlay}>
              <View style={styles.seriesNavRow}>
                <FocusableButton focusId="f_back" isHeaderControl={true} style={[styles.backFolderBar, { flex: 1, marginHorizontal: 0, marginBottom: 0 }]} onPress={goBack}>
                  <Icon name="arrow-back" size={18} color="white" />
                  <Text style={styles.backFolderText} numberOfLines={1}>
                    {seriesParentTitle ? `Back • ${seriesParentTitle}` : `Back • ${decodeURIComponent(currentPath)}`}
                  </Text>
                </FocusableButton>

                {seasonFolders.length > 0 && activeSeason && (() => {
                  const activeSeasonIdx = Math.max(
                    0,
                    seasonFolders.findIndex(sf => sf.seasonNum === activeSeason.seasonNum),
                  );
                  return (
                    <FocusableButton
                      focusId="f_season"
                      Component={SeasonTriggerPill}
                      isHeaderControl={true}
                      label={activeSeason.label}
                      currentIndex={activeSeasonIdx}
                      totalCount={seasonFolders.length}
                      isOpen={showSeasonModal}
                      onPress={() => setShowSeasonModal(true)}
                      chevronIcon={<Icon name="keyboard-arrow-down" size={20} color="#E2E8F0" />}
                    />
                  );
                })()}
              </View>
              <TopSyncProgressBar visible={refreshing && isInsideFolder} />

              {folderLoading ? (
                <ShimmerCardGrid columns={numColumns} count={numColumns >= 5 ? 10 : 6} />
              ) : (
                <FlatList
                  focusable={false}
                  key={`folder-catalog-${currentPath}-${viewMode}-${numColumns}`}
                  data={folderDisplayedFiles}
                  numColumns={numColumns}
                  initialNumToRender={15}
                  maxToRenderPerBatch={10}
                  windowSize={7}
                  keyboardShouldPersistTaps="handled"
                  removeClippedSubviews={false}
                  ListHeaderComponent={renderContinueWatchingHeader() ?? undefined}
                  refreshControl={
                    !IS_TV ? <RefreshControl refreshing={refreshing && isInsideFolder} onRefresh={handleRefresh} tintColor="#E50914" colors={['#E50914']} progressBackgroundColor="#181C24" /> : undefined
                  }
                  contentContainerStyle={{ paddingHorizontal: 10, paddingBottom: 30 }}
                  keyExtractor={(item, idx) => item.id || item.name || String(idx)}
                  renderItem={({ item, index }) => renderCatalogCard({ item, index, isFolderCard: true })}
                />
              )}
            </View>
            </FocusLayerContext.Provider>
          )}
        </View>
      </View>

      {renderDownloadsOverlay()}
      {renderDetailOverlay()}
      {renderPlayerOverlay()}
      {renderSeasonModal()}
      {renderHdrChoiceModal()}
      {showAudioBrowserModal && playMode === 'internal' && (
        <InAppAudioFileBrowser
          visible={true}
          currentAudioUri={externalAudio?.uri}
          onSelectAudio={track => {
            setExternalAudio({ name: track.name, uri: track.uri });
            setSelectedAudio({ type: 'external', value: track.name });
          }}
          onOpenSystemPicker={() => {
            setShowAudioBrowserModal(false);
            void handlePickAudioFile();
          }}
          onClose={() => setShowAudioBrowserModal(false)}
        />
      )}
      {IS_TV && isTvSearchModalOpen && (
        <Modal
          visible={isTvSearchModalOpen}
          transparent={true}
          animationType="fade"
          onRequestClose={() => {
            setIsTvSearchModalOpen(false);
            setTimeout(() => focusById(homeDisplayedFiles.length > 0 ? 'card_0' : 'search_btn'), 80);
          }}>
          <FocusLayerContext.Provider value="searchModal">
            <View style={styles.modalBackdrop}>
              <SlideUpSheet style={styles.hdrModalCard}>
                <Text style={styles.hdrModalEyebrow}>SEARCH CATALOG</Text>
                <Text style={styles.hdrModalTitle}>Type Movie or Series Name</Text>
                <View style={{ backgroundColor: '#1B1F2C', borderRadius: 10, borderWidth: 2, borderColor: '#FFD700', marginTop: 12, paddingHorizontal: 10 }}>
                  <TextInput
                    autoFocus={true}
                    style={{ color: 'white', fontSize: 16, paddingVertical: 10 }}
                    placeholder="Search movies, series, 4K, Tamil..."
                    placeholderTextColor="#777"
                    value={searchQuery}
                    onChangeText={(txt) => {
                      lastHomeCardIdRef.current = 'card_0';
                      setSearchQuery(txt);
                    }}
                    autoCorrect={false}
                    returnKeyType="search"
                    onSubmitEditing={() => {
                      setIsTvSearchModalOpen(false);
                      setTimeout(() => focusById(homeDisplayedFiles.length > 0 ? 'card_0' : 'search_btn'), 80);
                    }}
                  />
                </View>
                <View style={{ flexDirection: 'row', gap: 10, marginTop: 14 }}>
                  <FocusableButton
                    focusId="tv_search_done"
                    style={[styles.primaryPlayButton, { flex: 1, paddingVertical: 10 }]}
                    onPress={() => {
                      setIsTvSearchModalOpen(false);
                      setTimeout(() => focusById(homeDisplayedFiles.length > 0 ? 'card_0' : 'search_btn'), 80);
                    }}>
                    <Icon name="search" size={20} color="white" />
                    <Text style={{ color: 'white', fontWeight: '800' }}>View Results</Text>
                  </FocusableButton>
                  {!!searchQuery && (
                    <FocusableButton
                      focusId="tv_search_clear"
                      style={[styles.secondaryPlayButton, { paddingVertical: 10, paddingHorizontal: 14 }]}
                      onPress={() => {
                        lastHomeCardIdRef.current = 'card_0';
                        setSearchQuery('');
                      }}>
                      <Text style={{ color: 'white', fontWeight: '700' }}>Clear</Text>
                    </FocusableButton>
                  )}
                </View>
              </SlideUpSheet>
            </View>
          </FocusLayerContext.Provider>
        </Modal>
      )}
    </View>
    </FocusLayerContext.Provider>
    </HeaderFocusableContext.Provider>
    </ActiveLayerContext.Provider>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0B0C10', paddingTop: 38 },
  seriesFolderOverlay: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: '#0B0C10', zIndex: 20 },
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
  cwCard: { width: 190, backgroundColor: '#14171F', borderRadius: 10, overflow: 'hidden', borderWidth: 1.5, borderColor: '#222836', position: 'relative' },
  cwImageWrap: { width: '100%', height: 106, backgroundColor: '#191D26', position: 'relative' },
  cwImage: { width: '100%', height: '100%' },
  cwFallback: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  cwRemoveBtn: { position: 'absolute', top: 6, right: 6, width: 24, height: 24, borderRadius: 12, backgroundColor: 'rgba(0,0,0,0.78)', borderWidth: 1, borderColor: 'rgba(255,255,255,0.28)', alignItems: 'center', justifyContent: 'center', zIndex: 10 },
  cwPlayBadge: { position: 'absolute', right: 8, bottom: 10, width: 28, height: 28, borderRadius: 14, backgroundColor: 'rgba(229,9,20,0.92)', alignItems: 'center', justifyContent: 'center' },
  cwFooter: { paddingHorizontal: 9, paddingVertical: 7 },
  cwTitle: { color: 'white', fontSize: 13, fontWeight: '700' },
  cwTimeText: { color: '#E50914', fontSize: 11, fontWeight: '700', marginTop: 2 },
  posterGridSlot: { flex: 1, maxWidth: IS_TV ? '18.6%' : '47%', margin: '1.5%' },
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
  tvFocused: { borderColor: '#FFD700', borderWidth: 2, backgroundColor: 'rgba(255, 215, 0, 0.22)' },
  detailScreen: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 50, backgroundColor: '#0B0C10' },
  detailStickyBar: { position: 'absolute', top: 0, left: 0, right: 0, zIndex: 60 },
  detailStickyBg: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(11, 11, 15, 0.97)', borderBottomWidth: 1, borderBottomColor: 'rgba(255, 255, 255, 0.08)' },
  detailStickyRow: { paddingTop: (StatusBar.currentHeight || (Platform.OS === 'android' ? 28 : 44)) + 6, paddingBottom: 10, paddingHorizontal: 14, flexDirection: 'row', alignItems: 'center', gap: 12 },
  detailStickyBackBtn: { width: 38, height: 38, borderRadius: 19, backgroundColor: 'rgba(12, 14, 20, 0.72)', alignItems: 'center', justifyContent: 'center', borderWidth: 1.5, borderColor: 'rgba(255, 255, 255, 0.14)' },
  detailStickyTitle: { flex: 1, color: '#FFFFFF', fontSize: 16, fontWeight: '800', letterSpacing: 0.2 },
  detailHero: { width: '100%', height: 240, backgroundColor: '#141821', position: 'relative', overflow: 'hidden' },
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
  trailerStripSection: { marginTop: 18 },
  mediaInfoCard: { marginTop: 12, padding: 15, borderRadius: 18, backgroundColor: '#10131B', borderWidth: 1, borderColor: 'rgba(255, 255, 255, 0.08)', overflow: 'hidden', position: 'relative' },
  mediaInfoTopHighlight: { position: 'absolute', top: 0, left: 0, right: 0, height: 1, backgroundColor: 'rgba(255, 255, 255, 0.14)' },
  mediaInfoHeaderRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 },
  mediaInfoHeaderTitle: { color: '#9BA6B8', fontSize: 11, fontWeight: '900', letterSpacing: 0.9 },
  scanningText: { color: '#F5C518', fontSize: 11, fontWeight: '700' },
  heartBurstWrap: { position: 'relative', width: 22, height: 22, alignItems: 'center', justifyContent: 'center' },
  heartBurstRing: { position: 'absolute', width: 28, height: 28, borderRadius: 14, borderWidth: 2, borderColor: '#E50914' },
  miTelemetryGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  miTelemetryTile: { width: '48.5%', backgroundColor: '#171B26', borderRadius: 12, padding: 12, borderWidth: 1, borderColor: 'rgba(255, 255, 255, 0.07)' },
  miTileLabel: { color: '#78849A', fontSize: 9.5, fontWeight: '800', letterSpacing: 0.8, marginBottom: 5 },
  miTileValue: { color: '#F8FAFC', fontSize: 15, fontWeight: '800' },
  miTileSub: { color: '#94A3B8', fontSize: 11, fontWeight: '600', marginTop: 3 },
  miTileBadgesRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 4 },
  miMiniBadge: { backgroundColor: '#222A3A', paddingHorizontal: 6, paddingVertical: 2.5, borderRadius: 5, borderWidth: 1, borderColor: '#374258' },
  miMiniBadge4K: { backgroundColor: '#E50914', borderColor: '#FF4B55' },
  miMiniBadgeHdr: { backgroundColor: '#F5C518', borderColor: '#FDE047' },
  miMiniBadgeText: { color: '#F1F5F9', fontSize: 10, fontWeight: '900' },
  miSpecRow: { marginTop: 4 },
  miSpecLabel: { color: '#6C7789', fontSize: 10, fontWeight: '800', letterSpacing: 0.8, marginBottom: 4 },
  miSpecValue: { color: '#E2E8F0', fontSize: 13, fontWeight: '700' },
  miTrackPillWrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  miAudioPill: { backgroundColor: '#171B26', borderWidth: 1, borderColor: 'rgba(255, 255, 255, 0.07)', paddingHorizontal: 10, paddingVertical: 5.5, borderRadius: 8 },
  miAudioPillPreferred: { backgroundColor: 'rgba(245, 197, 24, 0.12)', borderColor: 'rgba(245, 197, 24, 0.45)' },
  miAudioRowInner: { flexDirection: 'row', alignItems: 'center' },
  miAudioPillText: { color: '#CBD5E1', fontSize: 11.5, fontWeight: '600' },
  miSubPill: { backgroundColor: '#171B26', borderWidth: 1, borderColor: 'rgba(255, 255, 255, 0.07)', paddingHorizontal: 9, paddingVertical: 4.5, borderRadius: 7 },
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
  downloadsOverlayScreen: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 40, backgroundColor: '#0B0C10', paddingTop: 38 },
  playerWrapper: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 120, backgroundColor: 'black', overflow: 'hidden' },
  zoomResetTopPill: { flexDirection: 'row', alignItems: 'center', gap: 5, backgroundColor: 'rgba(15, 19, 28, 0.86)', borderWidth: 1, borderColor: 'rgba(56, 189, 248, 0.5)', paddingHorizontal: 11, paddingVertical: 6, borderRadius: 999 },
  zoomResetTopPillText: { color: '#E2E8F0', fontSize: 12, fontWeight: '800' },
  videoPlayer: { position: 'absolute', top: 0, left: 0, bottom: 0, right: 0 },
  playerBufferingOverlay: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, alignItems: 'center', justifyContent: 'center', backgroundColor: 'rgba(0,0,0,0.35)' },
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
  netflixProgressThumbActive: { width: 22, height: 22, borderRadius: 11, top: 3, marginLeft: -11, borderWidth: 2.5, borderColor: '#FFFFFF', elevation: 6 },
  ytPreviewCard: { position: 'absolute', bottom: 30, width: 160, alignItems: 'center', zIndex: 110 },
  ytPreviewFrameBox: { width: 160, height: 90, borderRadius: 8, overflow: 'hidden', backgroundColor: '#0F1117', borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.4)', elevation: 8 },
  ytPreviewImage: { width: '100%', height: '100%' },
  ytPreviewPlaceholder: { flex: 1, alignItems: 'center', justifyContent: 'center', backgroundColor: '#161922' },
  ytPreviewTimePill: { marginTop: 5, backgroundColor: 'rgba(10, 12, 18, 0.88)', paddingHorizontal: 8, paddingVertical: 2, borderRadius: 5, borderWidth: 1, borderColor: 'rgba(255,255,255,0.18)' },
  ytPreviewTimeText: { color: '#FFFFFF', fontSize: 11, fontWeight: '800' },
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
