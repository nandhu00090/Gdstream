import React, { useState, useEffect, useRef, useMemo } from 'react';
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
} from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';
import Orientation from 'react-native-orientation-locker';
import Icon from 'react-native-vector-icons/MaterialIcons';
import AsyncStorage from '@react-native-async-storage/async-storage';

const { VideoPlayerManager, SubtitleFileModule } = NativeModules;
const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin';
const PASSWORD = '629175';

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
  const [pathStack, setPathStack] = useState(['/0:/']);
  const [currentPath, setCurrentPath] = useState('/0:/');
  const [searchQuery, setSearchQuery] = useState('');

  const [selectedFile, setSelectedFile] = useState<any>(null);
  const [playMode, setPlayMode] = useState<string | null>(null);
  const [resizeMode, setResizeMode] = useState<'contain' | 'cover'>('contain');
  const [showControls, setShowControls] = useState(true);
  const [isPaused, setIsPaused] = useState(false);
  const [isVideoBuffering, setIsVideoBuffering] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [playbackRate, setPlaybackRate] = useState(1.0);

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
    fetchDirectory('/0:/');
  }, []);

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
      if (playMode === 'internal') {
        closeInternalPlayer();
        return true;
      } else if (selectedFile) {
        setSelectedFile(null);
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
  }, [playMode, selectedFile, pathStack]);

  const ensureLoggedIn = async (force = false) => {
    if (isLoggedIn.current && !force) return;
    const loginData = `username=${USERNAME}&password=${PASSWORD}`;
    await axios.post(`${BASE_URL}/login`, loginData, {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    });
    isLoggedIn.current = true;
  };

  const fetchDirectory = async (path: string) => {
    setLoading(true);
    try {
      await ensureLoggedIn(false);
      let response = await axios.post(`${BASE_URL}${path}`, { page_index: 0 });
      if (!response.data?.data?.files) {
        await ensureLoggedIn(true);
        response = await axios.post(`${BASE_URL}${path}`, { page_index: 0 });
      }
      if (response.data?.data?.files) {
        setFiles(response.data.data.files);
        setFilteredFiles(response.data.data.files);
        setCurrentPath(path);
        setSearchQuery('');
      }
    } catch (error) {
      Alert.alert('Error', 'Folder load aagala!');
    } finally {
      setLoading(false);
    }
  };

  const getStreamUrl = (file: any) => {
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
    setExternalSubtitle(null);
    setExternalAudio(null);
    setSelectedFile(file);
  };

  const handlePress = (item: any) => {
    if (item.mimeType?.includes('folder')) {
      const newPath = `${currentPath}${encodeURIComponent(item.name)}/`;
      setPathStack([...pathStack, newPath]);
      fetchDirectory(newPath);
    } else {
      openFile(item);
    }
  };

  const goBack = () => {
    if (pathStack.length > 1) {
      const newStack = pathStack.slice(0, -1);
      setPathStack(newStack);
      fetchDirectory(newStack[newStack.length - 1]);
    }
  };

  const handleSearch = (text: string) => {
    setSearchQuery(text);
    const filtered = files.filter(file =>
      file.name.toLowerCase().includes(text.toLowerCase()),
    );
    setFilteredFiles(filtered);
  };

  const openInExternalPlayer = async () => {
    const url = getStreamUrl(selectedFile);
    try {
      await VideoPlayerManager.playVideo(url);
    } catch (e) {
      Alert.alert('Error', 'Player illai!');
    }
  };

  const toggleFullscreen = () => {
    if (isFullscreen) {
      Orientation.lockToPortrait();
      setIsFullscreen(false);
    } else {
      Orientation.lockToLandscape();
      setIsFullscreen(true);
    }
  };

  const closeInternalPlayer = () => {
    Orientation.lockToPortrait();
    setIsFullscreen(false);
    setPlayMode(null);
    setSelectedFile(null);
  };

  const handleZoomToggle = () => {
    setResizeMode(prev => (prev === 'contain' ? 'cover' : 'contain'));
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
      setSelectedAudio({ type: 'external', value: res.name || 'External Audio' });
      setActiveMenu(null);
    } catch (e) {
      Alert.alert('Error', 'Could not load audio file!');
    }
  };

  const seekBothPlayers = (targetTime: number) => {
    videoRef.current?.seek(targetTime);
    setCurrentTime(targetTime);
    if (isExternalAudioSelected && externalAudioRef.current) {
      const audioTarget = Math.max(0, targetTime - audioDelay);
      externalAudioRef.current.seek(audioTarget);
      externalAudioTimeRef.current = audioTarget;
      lastAudioSyncMs.current = Date.now();
    }
  };

  const handleZoneTap = (isForward: boolean) => {
    const now = Date.now();
    const DOUBLE_PRESS_DELAY = 300;

    if (
      now - lastTapTime.current < DOUBLE_PRESS_DELAY &&
      (lastTapDirection.current === null ||
        lastTapDirection.current === isForward)
    ) {
      clearTimeout(singleTapTimer.current);
      clearTimeout(multiTapTimer.current);

      if (seekBaseTime.current === null) {
        seekBaseTime.current = currentTime;
        accumulatedSeek.current = 0;
      }

      accumulatedSeek.current += 10;
      const totalSeek = accumulatedSeek.current;

      setSeekOverlay({
        visible: true,
        icon: isForward ? 'fast-forward' : 'fast-rewind',
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
      }, 350);
    } else {
      lastTapDirection.current = isForward;
      singleTapTimer.current = setTimeout(() => {
        setShowControls(prev => !prev);
        setActiveMenu(null);
        lastTapDirection.current = null;
      }, DOUBLE_PRESS_DELAY);
    }
    lastTapTime.current = now;
  };

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

  const handleVideoEnd = async () => {
    if (switchingEpisode.current) return;
    switchingEpisode.current = true;
    try {
      await AsyncStorage.removeItem(`time_${selectedFile?.name}`);
    } catch (e) {}
    const episodeRegex = /[Ss]\d+[Ee]\d+/;
    if (episodeRegex.test(selectedFile?.name || '')) {
      const currentIndex = filteredFiles.findIndex(
        f => f.name === selectedFile?.name,
      );
      if (currentIndex !== -1 && currentIndex + 1 < filteredFiles.length) {
        openFile(filteredFiles[currentIndex + 1]);
        setPlayMode('internal');
        return;
      }
    }
    closeInternalPlayer();
  };

  const renderFloatingMenu = () => {
    if (!activeMenu) return null;
    const isAudio = activeMenu === 'audio';
    const tracks = isAudio ? audioTracks : textTracks;
    const isSubtitleDisabled =
      !selectedText || selectedText.type === 'disabled';

    return (
      <View style={styles.floatingMenu}>
        <Text style={styles.floatingMenuTitle}>
          {isAudio ? 'Audio Tracks 🎵' : 'Subtitles 💬'}
        </Text>
        <ScrollView style={{ maxHeight: 280 }}>
          {!isAudio && (
            <TouchableOpacity
              style={styles.menuItem}
              onPress={() => handleSelectTextTrack({ type: 'disabled' })}>
              <Icon
                name="check"
                size={20}
                color="white"
                style={{ opacity: isSubtitleDisabled ? 1 : 0 }}
              />
              <Text style={styles.menuItemText}>Disable Subtitles</Text>
            </TouchableOpacity>
          )}
          {!isAudio && (
            <TouchableOpacity
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
                  : 'Select subtitle file 📂'}
              </Text>
            </TouchableOpacity>
          )}
          {!isAudio && !!externalSubtitle && !isExternalSubtitleSelected && (
            <TouchableOpacity
              style={styles.menuItem}
              onPress={() =>
                handleSelectTextTrack({
                  type: 'external',
                  value: externalSubtitle.name,
                })
              }>
              <Icon name="check" size={20} color="white" style={{ opacity: 0 }} />
              <Text style={styles.menuItemText} numberOfLines={1}>
                Use: {externalSubtitle.name}
              </Text>
            </TouchableOpacity>
          )}
          {isAudio && (
            <TouchableOpacity
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
                  : 'Select audio file 📂'}
              </Text>
            </TouchableOpacity>
          )}
          {isAudio && !!externalAudio && (
            <TouchableOpacity
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
                color="white"
                style={{ opacity: isExternalAudioSelected ? 1 : 0 }}
              />
              <Text style={styles.menuItemText} numberOfLines={1}>
                External: {externalAudio.name}
              </Text>
            </TouchableOpacity>
          )}
          {!isAudio && (
            <View style={styles.adjustRow}>
              <Text style={styles.adjustLabel}>Size</Text>
              <TouchableOpacity
                style={styles.adjustBtn}
                onPress={() => setSubtitleSize(s => Math.max(10, s - 2))}>
                <Icon name="remove" size={20} color="white" />
              </TouchableOpacity>
              <Text style={styles.adjustValue}>{subtitleSize}</Text>
              <TouchableOpacity
                style={styles.adjustBtn}
                onPress={() => setSubtitleSize(s => Math.min(40, s + 2))}>
                <Icon name="add" size={20} color="white" />
              </TouchableOpacity>
            </View>
          )}
          {!isAudio && (
            <View style={styles.adjustRow}>
              <Text style={styles.adjustLabel}>Delay</Text>
              <TouchableOpacity
                style={styles.adjustBtn}
                onPress={() =>
                  setSubtitleDelay(d => Math.round((d - 0.5) * 10) / 10)
                }>
                <Icon name="remove" size={20} color="white" />
              </TouchableOpacity>
              <Text style={styles.adjustValue}>
                {subtitleDelay >= 0
                  ? `+${subtitleDelay.toFixed(1)}s`
                  : `${subtitleDelay.toFixed(1)}s`}
              </Text>
              <TouchableOpacity
                style={styles.adjustBtn}
                onPress={() =>
                  setSubtitleDelay(d => Math.round((d + 0.5) * 10) / 10)
                }>
                <Icon name="add" size={20} color="white" />
              </TouchableOpacity>
            </View>
          )}
          {isAudio && (
            <View style={styles.adjustRow}>
              <Text style={styles.adjustLabel}>Audio Delay</Text>
              <TouchableOpacity
                style={styles.adjustBtn}
                onPress={() =>
                  setAudioDelay(d => Math.round((d - 0.25) * 100) / 100)
                }>
                <Icon name="remove" size={20} color="white" />
              </TouchableOpacity>
              <Text style={styles.adjustValue}>
                {audioDelay >= 0
                  ? `+${audioDelay.toFixed(2)}s`
                  : `${audioDelay.toFixed(2)}s`}
              </Text>
              <TouchableOpacity
                style={styles.adjustBtn}
                onPress={() =>
                  setAudioDelay(d => Math.round((d + 0.25) * 100) / 100)
                }>
                <Icon name="add" size={20} color="white" />
              </TouchableOpacity>
            </View>
          )}
          {isAudio && (
            <View style={styles.adjustRow}>
              <Text style={styles.adjustLabel}>Speed</Text>
              <TouchableOpacity
                style={styles.adjustBtn}
                onPress={() =>
                  setPlaybackRate(r =>
                    Math.max(0.5, Math.round((r - 0.25) * 100) / 100),
                  )
                }>
                <Icon name="remove" size={20} color="white" />
              </TouchableOpacity>
              <Text style={styles.adjustValue}>{playbackRate.toFixed(2)}x</Text>
              <TouchableOpacity
                style={styles.adjustBtn}
                onPress={() =>
                  setPlaybackRate(r =>
                    Math.min(2.0, Math.round((r + 0.25) * 100) / 100),
                  )
                }>
                <Icon name="add" size={20} color="white" />
              </TouchableOpacity>
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
              <TouchableOpacity
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
                  color="white"
                  style={{ opacity: isSelected ? 1 : 0 }}
                />
                <Text style={styles.menuItemText} numberOfLines={1}>
                  {track.language
                    ? track.language.toUpperCase()
                    : `Track ${index + 1}`}{' '}
                  - {track.title || 'Auto'}
                </Text>
              </TouchableOpacity>
            );
          })}
        </ScrollView>
      </View>
    );
  };

  if (selectedFile && playMode === 'internal') {
    const progressPercent = duration > 0 ? (currentTime / duration) * 100 : 0;
    return (
      <View style={styles.playerWrapper}>
        <Video
          key={selectedFile.link || selectedFile.name}
          ref={videoRef}
          source={{
            uri: getStreamUrl(selectedFile),
          }}
          style={styles.videoPlayer}
          resizeMode={resizeMode}
          paused={isPaused}
          muted={isExternalAudioSelected}
          disableFocus={isExternalAudioSelected}
          rate={playbackRate}
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
            paddingBottom: resizeMode === 'cover' ? 70 : 18,
            fontSize: subtitleSize,
            subtitlesFollowVideo: resizeMode !== 'cover',
          }}
          selectedTextTrack={
            subtitlesActive ? selectedText : { type: 'disabled' }
          }
          viewType={1}
          controls={false}
        />

        {isExternalAudioSelected && !!externalAudio && (
          <Video
            key={externalAudio.uri}
            ref={externalAudioRef}
            source={{ uri: externalAudio.uri }}
            paused={isPaused || isVideoBuffering}
            rate={playbackRate}
            volume={1.0}
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

        <View style={[StyleSheet.absoluteFill, { flexDirection: 'row' }]}>
          <TouchableOpacity
            style={{ flex: 1 }}
            activeOpacity={1}
            onPress={() => handleZoneTap(false)}
          />
          <TouchableOpacity
            style={{ flex: 1 }}
            activeOpacity={1}
            onPress={() => handleZoneTap(true)}
          />
        </View>

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
          <View style={styles.overlayBox}>
            <Icon
              name={seekOverlay.icon || 'fast-forward'}
              size={40}
              color="white"
            />
            <Text style={styles.seekText}>{seekOverlay.time} seconds</Text>
          </View>
        </Animated.View>

        {showControls && (
          <View style={styles.controlsOverlay} pointerEvents="box-none">
            <View style={styles.playerTopBar}>
              <TouchableOpacity onPress={closeInternalPlayer}>
                <Icon name="arrow-back" size={28} color="white" />
              </TouchableOpacity>
              <Text style={styles.playerTitle} numberOfLines={1}>
                {selectedFile.name}
              </Text>
            </View>
            <TouchableOpacity
              onPress={() => setIsPaused(!isPaused)}
              style={styles.centerPlayBtn}>
              <Icon
                name={isPaused ? 'play-arrow' : 'pause'}
                size={60}
                color="white"
              />
            </TouchableOpacity>
            <View style={styles.playerBottomArea}>
              <TouchableOpacity
                activeOpacity={1}
                style={styles.progressBarBg}
                onLayout={e => setProgressWidth(e.nativeEvent.layout.width)}
                onPress={e => {
                  if (progressWidth > 0 && duration > 0) {
                    const seekTime =
                      (e.nativeEvent.locationX / progressWidth) * duration;
                    seekBothPlayers(seekTime);
                  }
                }}>
                <View style={styles.progressBarTrack} />
                <View
                  style={[
                    styles.progressBarFill,
                    { width: `${progressPercent}%` },
                  ]}
                  pointerEvents="none"
                />
                <View
                  style={[styles.progressDot, { left: `${progressPercent}%` }]}
                  pointerEvents="none"
                />
              </TouchableOpacity>
              <View style={styles.playerBottomControls}>
                <Text style={styles.timeText}>
                  {formatTime(currentTime)} • {formatTime(duration)}
                </Text>
                <View style={{ flexDirection: 'row' }}>
                  <TouchableOpacity
                    style={styles.iconBtn}
                    onPress={() =>
                      setActiveMenu(activeMenu === 'audio' ? null : 'audio')
                    }>
                    <Icon
                      name="audiotrack"
                      size={26}
                      color={activeMenu === 'audio' ? '#E50914' : 'white'}
                    />
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={styles.iconBtn}
                    onPress={() =>
                      setActiveMenu(
                        activeMenu === 'subtitle' ? null : 'subtitle',
                      )
                    }>
                    <Icon
                      name="closed-caption"
                      size={26}
                      color={activeMenu === 'subtitle' ? '#E50914' : 'white'}
                    />
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={styles.iconBtn}
                    onPress={handleZoomToggle}>
                    <Icon
                      name={
                        resizeMode === 'contain' ? 'aspect-ratio' : 'crop-free'
                      }
                      size={26}
                      color={resizeMode === 'cover' ? '#E50914' : 'white'}
                    />
                  </TouchableOpacity>
                  <TouchableOpacity
                    onPress={toggleFullscreen}
                    style={styles.iconBtn}>
                    <Icon
                      name={isFullscreen ? 'fullscreen-exit' : 'fullscreen'}
                      size={26}
                      color="white"
                    />
                  </TouchableOpacity>
                </View>
              </View>
            </View>
          </View>
        )}
        {renderFloatingMenu()}
      </View>
    );
  }

  if (selectedFile && playMode === null) {
    return (
      <View style={styles.selectionContainer}>
        <Text style={styles.selectionTitle}>{selectedFile.name}</Text>
        <TouchableOpacity
          style={styles.primaryButton}
          onPress={() => setPlayMode('internal')}>
          <Text style={styles.buttonText}>Play in App (Exo Player) 🍿</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={styles.secondaryButton}
          onPress={openInExternalPlayer}>
          <Text style={styles.buttonText}>Play in External Player 🎦</Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={() => setSelectedFile(null)}>
          <Text style={{ color: 'grey', marginTop: 20 }}>Back</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.headerTitle}>GDStream 🍿</Text>
      <TextInput
        style={styles.searchBar}
        placeholder="Search movies / episodes..."
        placeholderTextColor="#888"
        value={searchQuery}
        onChangeText={handleSearch}
      />
      {pathStack.length > 1 && (
        <TouchableOpacity style={styles.backButton} onPress={goBack}>
          <Text style={{ color: 'white' }}>⬅️ Back</Text>
        </TouchableOpacity>
      )}
      {loading ? (
        <ActivityIndicator
          size="large"
          color="#E50914"
          style={{ marginTop: 50 }}
        />
      ) : (
        <FlatList
          data={filteredFiles}
          keyExtractor={(item, idx) => item.id || item.name || String(idx)}
          renderItem={({ item }) => (
            <TouchableOpacity
              style={styles.itemCard}
              onPress={() => handlePress(item)}>
              <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                <Text style={{ fontSize: 24, marginRight: 10 }}>
                  {item.mimeType?.includes('folder') ? '📁' : '🎬'}
                </Text>
                <Text style={styles.itemText} numberOfLines={2}>
                  {item.name}
                </Text>
              </View>
            </TouchableOpacity>
          )}
        />
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#141414', paddingTop: 40 },
  headerTitle: {
    color: '#E50914',
    fontSize: 24,
    fontWeight: 'bold',
    textAlign: 'center',
    marginBottom: 10,
  },
  searchBar: {
    backgroundColor: '#222',
    color: 'white',
    marginHorizontal: 15,
    marginBottom: 15,
    padding: 12,
    borderRadius: 8,
  },
  backButton: {
    backgroundColor: '#333',
    padding: 10,
    marginHorizontal: 15,
    borderRadius: 5,
    marginBottom: 10,
  },
  itemCard: {
    backgroundColor: '#222',
    padding: 18,
    marginVertical: 5,
    marginHorizontal: 15,
    borderRadius: 8,
  },
  itemText: { color: '#FFF', fontSize: 16, flex: 1 },
  selectionContainer: {
    flex: 1,
    backgroundColor: '#141414',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  selectionTitle: {
    color: '#FFF',
    fontSize: 18,
    textAlign: 'center',
    marginBottom: 40,
  },
  primaryButton: {
    backgroundColor: '#E50914',
    padding: 15,
    width: 250,
    borderRadius: 8,
    alignItems: 'center',
  },
  secondaryButton: {
    backgroundColor: '#E06C00',
    padding: 15,
    width: 250,
    borderRadius: 8,
    marginTop: 15,
    alignItems: 'center',
  },
  buttonText: { color: '#FFF', fontWeight: 'bold' },
  playerWrapper: { flex: 1, backgroundColor: 'black' },
  videoPlayer: { position: 'absolute', top: 0, left: 0, bottom: 0, right: 0 },
  externalSubtitleContainer: {
    position: 'absolute',
    left: 24,
    right: 24,
    bottom: 42,
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
  controlsOverlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    bottom: 0,
    right: 0,
    backgroundColor: 'rgba(0,0,0,0.4)',
    justifyContent: 'space-between',
  },
  seekOverlay: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    justifyContent: 'center',
    paddingHorizontal: 50,
  },
  overlayBox: { alignItems: 'center' },
  seekText: { color: 'white', fontSize: 16, fontWeight: 'bold' },
  playerTopBar: {
    padding: 20,
    paddingTop: 40,
    flexDirection: 'row',
    alignItems: 'center',
  },
  playerTitle: {
    color: 'white',
    marginLeft: 15,
    fontSize: 16,
    fontWeight: '500',
    flex: 1,
  },
  centerPlayBtn: {
    alignSelf: 'center',
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.5)',
    borderRadius: 50,
    padding: 10,
  },
  playerBottomArea: { paddingHorizontal: 20, paddingBottom: 30, width: '100%' },
  progressBarBg: {
    height: 30,
    justifyContent: 'center',
    marginBottom: 5,
    width: '100%',
  },
  progressBarTrack: {
    height: 6,
    backgroundColor: 'rgba(255,255,255,0.3)',
    borderRadius: 3,
  },
  progressBarFill: {
    height: 6,
    backgroundColor: '#E50914',
    borderRadius: 3,
    position: 'absolute',
  },
  progressDot: {
    position: 'absolute',
    width: 16,
    height: 16,
    borderRadius: 8,
    backgroundColor: 'white',
    top: 7,
    marginLeft: -8,
  },
  playerBottomControls: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  timeText: { color: 'white', fontSize: 14, fontWeight: '500' },
  iconBtn: { padding: 10, marginLeft: 5 },
  floatingMenu: {
    position: 'absolute',
    bottom: 90,
    right: 20,
    width: 280,
    backgroundColor: 'rgba(28, 28, 30, 0.95)',
    borderRadius: 12,
    padding: 15,
    zIndex: 100,
  },
  floatingMenuTitle: {
    color: '#888',
    fontSize: 14,
    marginBottom: 10,
    fontWeight: 'bold',
    borderBottomWidth: 1,
    borderBottomColor: '#444',
    paddingBottom: 10,
  },
  menuItem: { flexDirection: 'row', alignItems: 'center', paddingVertical: 12 },
  menuItemText: { color: 'white', fontSize: 16, marginLeft: 10, flex: 1 },
  adjustRow: { flexDirection: 'row', alignItems: 'center', paddingVertical: 8 },
  adjustLabel: { color: '#AAA', fontSize: 14, width: 90 },
  adjustBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: '#333',
    justifyContent: 'center',
    alignItems: 'center',
  },
  adjustValue: {
    color: 'white',
    fontSize: 15,
    fontWeight: 'bold',
    width: 60,
    textAlign: 'center',
  },
  adjustHint: { color: '#777', fontSize: 12, paddingVertical: 6 },
});

export default App;
