import React, { useState, useEffect, useRef } from 'react';
import { View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator, Alert, NativeModules, TextInput, ScrollView, BackHandler, Animated } from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';
import Orientation from 'react-native-orientation-locker';
import Icon from 'react-native-vector-icons/MaterialIcons';
import AsyncStorage from '@react-native-async-storage/async-storage';

const { VideoPlayerManager } = NativeModules;
const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin'; 
const PASSWORD = '629175'; 

const formatTime = (seconds) => {
  if (isNaN(seconds) || seconds < 0) return '00:00';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}:${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
  return `${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
};

const App = () => {
  const [files, setFiles] = useState([]);
  const [filteredFiles, setFilteredFiles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [pathStack, setPathStack] = useState(['/0:/']); 
  const [currentPath, setCurrentPath] = useState('/0:/');
  const [searchQuery, setSearchQuery] = useState('');
  
  const [selectedFile, setSelectedFile] = useState(null);
  const [playMode, setPlayMode] = useState(null);
  const [resizeMode, setResizeMode] = useState('contain');
  const [showControls, setShowControls] = useState(true);
  const [isPaused, setIsPaused] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [progressWidth, setProgressWidth] = useState(0);
  const videoRef = useRef(null);
  const switchingEpisode = useRef(false);

  const [audioTracks, setAudioTracks] = useState([]);
  const [textTracks, setTextTracks] = useState([]);
  const [selectedAudio, setSelectedAudio] = useState(undefined);
  const [selectedText, setSelectedText] = useState(undefined);
  const [activeMenu, setActiveMenu] = useState(null);

  // 🔥 GESTURE STATES (Real Double Tap)
  const [seekOverlay, setSeekOverlay] = useState({ visible: false, icon: '', time: 0 });
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const tapCount = useRef(0);
  const multiTapTimer = useRef(null);
  const singleTapTimer = useRef(null);
  const lastTapTime = useRef(0);

  useEffect(() => { fetchDirectory('/0:/'); }, []);

  useEffect(() => {
    const backAction = () => {
      if (playMode === 'internal') { closeInternalPlayer(); return true; }
      else if (selectedFile) { setSelectedFile(null); return true; }
      else if (pathStack.length > 1) { goBack(); return true; }
      return false; 
    };
    const backHandler = BackHandler.addEventListener("hardwareBackPress", backAction);
    return () => backHandler.remove();
  }, [playMode, selectedFile, pathStack]);

  const fetchDirectory = async (path) => {
    setLoading(true);
    try {
      const loginData = `username=${USERNAME}&password=${PASSWORD}`;
      await axios.post(`${BASE_URL}/login`, loginData, { headers: { 'Content-Type': 'application/x-www-form-urlencoded' }});
      const response = await axios.post(`${BASE_URL}${path}`, { page_index: 0 });
      if (response.data?.data?.files) {
        setFiles(response.data.data.files);
        setFilteredFiles(response.data.data.files);
        setCurrentPath(path);
      }
    } catch (error) { Alert.alert("Error", "Folder load aagala!"); } 
    finally { setLoading(false); }
  };

  const openFile = (file) => {
    switchingEpisode.current = false;
    setSelectedAudio(undefined);
    setSelectedText(undefined);
    setCurrentTime(0);
    setSelectedFile(file);
    // Note: We don't set playMode here to allow the selection screen to show!
  };

  const handlePress = (item) => {
    if (item.mimeType?.includes('folder')) {
      const newPath = `${currentPath}${item.name}/`;
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

  const handleSearch = (text) => {
    setSearchQuery(text);
    const filtered = files.filter(file => file.name.toLowerCase().includes(text.toLowerCase()));
    setFilteredFiles(filtered);
  };

  const openInExternalPlayer = async () => {
    const url = `${BASE_URL}${selectedFile.link || `/0:/${encodeURIComponent(selectedFile.name)}`}`;
    try { await VideoPlayerManager.playVideo(url); } catch (e) { Alert.alert("Error", "Player illai!"); }
  };

  const toggleFullscreen = () => {
    if (isFullscreen) { Orientation.lockToPortrait(); setIsFullscreen(false); } 
    else { Orientation.lockToLandscape(); setIsFullscreen(true); }
  };

  const closeInternalPlayer = () => { Orientation.lockToPortrait(); setIsFullscreen(false); setPlayMode(null); setSelectedFile(null); };

  // 🔥 TRUE DOUBLE-TAP LOGIC 🔥
  const handleZoneTap = (isForward) => {
    const now = Date.now();
    const DOUBLE_PRESS_DELAY = 300;

    if (now - lastTapTime.current < DOUBLE_PRESS_DELAY) {
      // Double tap confirmed
      clearTimeout(singleTapTimer.current);
      tapCount.current += 1;
      clearTimeout(multiTapTimer.current);

      const seekSeconds = tapCount.current * 10;
      setSeekOverlay({ visible: true, icon: isForward ? 'fast-forward' : 'fast-rewind', time: seekSeconds });
      
      Animated.sequence([
        Animated.timing(fadeAnim, { toValue: 1, duration: 100, useNativeDriver: true }),
        Animated.delay(500),
        Animated.timing(fadeAnim, { toValue: 0, duration: 300, useNativeDriver: true })
      ]).start();

      multiTapTimer.current = setTimeout(() => {
        const targetTime = isForward ? currentTime + seekSeconds : currentTime - seekSeconds;
        videoRef.current?.seek(targetTime);
        tapCount.current = 0; // Reset tap count after seeking
      }, 600);

    } else {
      // Single tap processing (waits to see if double tap happens)
      tapCount.current = 1; 
      singleTapTimer.current = setTimeout(() => {
        setShowControls(!showControls);
        setActiveMenu(null);
      }, DOUBLE_PRESS_DELAY);
    }
    lastTapTime.current = now;
  };

  const handleVideoLoad = async (meta) => {
    setDuration(meta.duration);
    if (meta.audioTracks) setAudioTracks(meta.audioTracks);
    if (meta.textTracks) setTextTracks(meta.textTracks);
    try {
      const savedTimeStr = await AsyncStorage.getItem(`time_${selectedFile.name}`);
      if (savedTimeStr) {
        const savedTime = parseFloat(savedTimeStr);
        if (savedTime > 0 && meta.duration - savedTime > 180) videoRef.current?.seek(savedTime);
      }
    } catch(e) {}
  };

  const handleVideoEnd = async () => {
    if (switchingEpisode.current) return;
    switchingEpisode.current = true;
    try { await AsyncStorage.removeItem(`time_${selectedFile?.name}`); } catch(e) {}
    const episodeRegex = /[Ss]\d+[Ee]\d+/;
    if (episodeRegex.test(selectedFile?.name || '')) {
      const currentIndex = filteredFiles.findIndex(f => f.name === selectedFile?.name);
      if (currentIndex !== -1 && currentIndex + 1 < filteredFiles.length) {
        openFile(filteredFiles[currentIndex + 1]);
        setPlayMode('internal'); // Continue playing next
        return;
      }
    }
    closeInternalPlayer();
  };

  const renderFloatingMenu = () => {
    if (!activeMenu) return null;
    const isAudio = activeMenu === 'audio';
    const tracks = isAudio ? audioTracks : textTracks;

    return (
      <View style={styles.floatingMenu}>
        <Text style={styles.floatingMenuTitle}>{isAudio ? "Audio Tracks 🎵" : "Subtitles 💬"}</Text>
        <ScrollView style={{maxHeight: 200}}>
          {!isAudio && (
            <TouchableOpacity style={styles.menuItem} onPress={() => { setSelectedText({ type: 'disabled' }); setActiveMenu(null); }}>
              <Icon name="check" size={20} color={selectedText?.type === 'disabled' ? 'white' : 'transparent'} />
              <Text style={styles.menuItemText}>Disable Subtitles</Text>
            </TouchableOpacity>
          )}
          {tracks.map((track, index) => {
            const isSelected = isAudio ? selectedAudio?.value === index : selectedText?.value === index;
            return (
              <TouchableOpacity key={index} style={styles.menuItem} onPress={() => {
                if (isAudio) setSelectedAudio({ type: 'index', value: index });
                else setSelectedText({ type: 'index', value: index });
                setActiveMenu(null);
              }}>
                <Icon name="check" size={20} color={isSelected ? 'white' : 'transparent'} />
                <Text style={styles.menuItemText} numberOfLines={1}>
                  {track.language ? track.language.toUpperCase() : `Track ${index + 1}`} - {track.title || "Auto"}
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
          key={selectedFile.link}
          ref={videoRef}
          source={{ uri: `${BASE_URL}${selectedFile.link || `/0:/${encodeURIComponent(selectedFile.name)}`}` }} 
          style={styles.videoPlayer} 
          resizeMode={resizeMode}
          paused={isPaused}
          onLoad={handleVideoLoad}
          onProgress={(p) => {
             setCurrentTime(p.currentTime);
             if (Math.floor(p.currentTime) % 5 === 0) AsyncStorage.setItem(`time_${selectedFile?.name}`, p.currentTime.toString());
             if (duration > 0 && (duration - p.currentTime) < 1.5) handleVideoEnd();
          }}
          onEnd={handleVideoEnd}
          {...(selectedAudio ? { selectedAudioTrack: selectedAudio } : {})}
          {...(selectedText ? { selectedTextTrack: selectedText } : {})}
          controls={false}
          useTextureView={false}
        />
        
        {/* TRUE DOUBLE TAP ZONES */}
        <View style={StyleSheet.absoluteFill} flexDirection="row">
          <TouchableOpacity style={{flex: 1}} activeOpacity={1} onPress={() => handleZoneTap(false)} />
          <TouchableOpacity style={{flex: 1}} activeOpacity={1} onPress={() => handleZoneTap(true)} />
        </View>

        {/* SEEK OVERLAY (Fade animation) */}
        <Animated.View style={[styles.seekOverlay, { opacity: fadeAnim }]} pointerEvents="none">
           <Icon name={seekOverlay.icon} size={50} color="white" />
           <Text style={styles.seekText}>{seekOverlay.time} seconds</Text>
        </Animated.View>

        {/* CONTROLS OVERLAY */}
        {showControls && (
          <View style={styles.controlsOverlay} pointerEvents="box-none">
            <View style={styles.playerTopBar}>
              <TouchableOpacity onPress={closeInternalPlayer}><Icon name="arrow-back" size={28} color="white" /></TouchableOpacity>
              <Text style={styles.playerTitle} numberOfLines={1}>{selectedFile.name}</Text>
            </View>
            <TouchableOpacity onPress={() => setIsPaused(!isPaused)} style={styles.centerPlayBtn}>
                <Icon name={isPaused ? "play-arrow" : "pause"} size={60} color="white" />
            </TouchableOpacity>
            <View style={styles.playerBottomArea}>
                <TouchableOpacity activeOpacity={1} style={styles.progressBarBg} onLayout={(e) => setProgressWidth(e.nativeEvent.layout.width)} onPress={(e) => {
                    const seekTime = (e.nativeEvent.locationX / progressWidth) * duration;
                    videoRef.current?.seek(seekTime);
                }}>
                  <View style={[styles.progressBarFill, { width: `${progressPercent}%` }]} pointerEvents="none" />
                  <View style={[styles.progressDot, { left: `${progressPercent}%` }]} pointerEvents="none" />
                </TouchableOpacity>
                <View style={styles.playerBottomControls}>
                    <Text style={styles.timeText}>{formatTime(currentTime)} • {formatTime(duration)}</Text>
                    <View style={{flexDirection: 'row', alignItems: 'center'}}>
                        <TouchableOpacity style={styles.iconBtn} onPress={() => setActiveMenu(activeMenu === 'audio' ? null : 'audio')}><Icon name="audiotrack" size={26} color={activeMenu === 'audio' ? '#E50914' : 'white'} /></TouchableOpacity>
                        <TouchableOpacity style={styles.iconBtn} onPress={() => setActiveMenu(activeMenu === 'subtitle' ? null : 'subtitle')}><Icon name="closed-caption" size={26} color={activeMenu === 'subtitle' ? '#E50914' : 'white'} /></TouchableOpacity>
                        <TouchableOpacity style={styles.iconBtn} onPress={() => setResizeMode(prev => prev === 'contain' ? 'cover' : 'contain')}><Icon name={resizeMode === 'contain' ? "aspect-ratio" : "crop-free"} size={26} color="white" /></TouchableOpacity>
                        <TouchableOpacity onPress={toggleFullscreen} style={styles.iconBtn}><Icon name={isFullscreen ? "fullscreen-exit" : "fullscreen"} size={26} color="white" /></TouchableOpacity>
                    </View>
                </View>
            </View>
          </View>
        )}
        
        {/* AUDIO & SUBTITLE MENU (RESTORED!) */}
        {renderFloatingMenu()}

      </View>
    );
  }

  // 🔥 EXTERNAL PLAYER SELECTION SCREEN (RESTORED!) 🔥
  if (selectedFile && playMode === null) {
    return (
      <View style={styles.selectionContainer}>
        <Text style={styles.selectionTitle}>{selectedFile.name}</Text>
        <TouchableOpacity style={styles.primaryButton} onPress={() => setPlayMode('internal')}>
          <Text style={styles.buttonText}>Play in App (Exo Player) 🍿</Text>
        </TouchableOpacity>
        <TouchableOpacity style={styles.secondaryButton} onPress={openInExternalPlayer}>
          <Text style={styles.buttonText}>Play in External Player 🎦</Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={() => setSelectedFile(null)}><Text style={{color:'grey', marginTop:20}}>Back</Text></TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.headerTitle}>GDStream 🍿</Text>
      <TextInput style={styles.searchBar} placeholder="Search movies / episodes..." placeholderTextColor="#888" value={searchQuery} onChangeText={handleSearch} />
      {pathStack.length > 1 && <TouchableOpacity style={styles.backButton} onPress={goBack}><Text style={{color:'white'}}>⬅️ Back</Text></TouchableOpacity>}
      {loading ? <ActivityIndicator size="large" color="#E50914" style={{ marginTop: 50 }} /> : (
        <FlatList 
          data={filteredFiles} 
          keyExtractor={(item) => item.id}
          renderItem={({item}) => (
            <TouchableOpacity style={styles.itemCard} onPress={() => handlePress(item)}>
              <View style={{flexDirection:'row', alignItems:'center'}}>
                <Text style={{fontSize:24, marginRight:10}}>{item.mimeType?.includes('folder') ? '📁' : '🎬'}</Text>
                <Text style={styles.itemText} numberOfLines={2}>{item.name}</Text>
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
  headerTitle: { color: '#E50914', fontSize: 24, fontWeight: 'bold', textAlign: 'center', marginBottom: 10 },
  searchBar: { backgroundColor: '#222', color: 'white', marginHorizontal: 15, marginBottom: 15, padding: 12, borderRadius: 8 },
  backButton: { backgroundColor: '#333', padding: 10, marginHorizontal: 15, borderRadius: 5, marginBottom: 10 },
  itemCard: { backgroundColor: '#222', padding: 18, marginVertical: 5, marginHorizontal: 15, borderRadius: 8 },
  itemText: { color: '#FFF', fontSize: 16, flex: 1 },
  
  selectionContainer: { flex: 1, backgroundColor: '#141414', justifyContent: 'center', alignItems: 'center', padding: 20 },
  selectionTitle: { color: '#FFF', fontSize: 18, textAlign: 'center', marginBottom: 40 },
  primaryButton: { backgroundColor: '#E50914', padding: 15, width: 250, borderRadius: 8, alignItems: 'center' },
  secondaryButton: { backgroundColor: '#E06C00', padding: 15, width: 250, borderRadius: 8, marginTop: 15, alignItems: 'center' },
  buttonText: { color: '#FFF', fontWeight: 'bold' },
  
  playerWrapper: { flex: 1, backgroundColor: 'black' },
  videoPlayer: { position: 'absolute', top: 0, left: 0, bottom: 0, right: 0 },
  controlsOverlay: { position: 'absolute', top: 0, left: 0, bottom: 0, right: 0, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'space-between' },
  seekOverlay: { position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, justifyContent: 'center', alignItems: 'center', backgroundColor: 'rgba(0,0,0,0.3)' },
  seekText: { color: 'white', fontSize: 18, marginTop: 10, fontWeight: 'bold' },
  playerTopBar: { padding: 20, paddingTop: 40, flexDirection: 'row', alignItems: 'center' },
  playerTitle: { color: 'white', marginLeft: 15, fontSize: 16, fontWeight: '500', flex: 1 },
  centerPlayBtn: { alignSelf: 'center', justifyContent: 'center', alignItems: 'center', backgroundColor: 'rgba(0,0,0,0.5)', borderRadius: 50, padding: 10 },
  playerBottomArea: { paddingHorizontal: 20, paddingBottom: 30, width: '100%' },
  progressBarBg: { height: 25, justifyContent: 'center', marginBottom: 5, width: '100%' },
  progressBarFill: { height: 4, backgroundColor: 'white', borderRadius: 2, position: 'absolute' },
  progressDot: { position: 'absolute', width: 14, height: 14, borderRadius: 7, backgroundColor: 'white', top: 5, marginLeft: -7 },
  playerBottomControls: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  timeText: { color: 'white', fontSize: 14, fontWeight: '500' },
  iconBtn: { padding: 10, marginLeft: 5 },
  floatingMenu: { position: 'absolute', bottom: 90, right: 20, width: 280, backgroundColor: 'rgba(28, 28, 30, 0.95)', borderRadius: 12, padding: 15, zIndex: 100 },
  floatingMenuTitle: { color: '#888', fontSize: 14, marginBottom: 10, fontWeight: 'bold', borderBottomWidth: 1, borderBottomColor: '#444', paddingBottom: 10 },
  menuItem: { flexDirection: 'row', alignItems: 'center', paddingVertical: 12 },
  menuItemText: { color: 'white', fontSize: 16, marginLeft: 10, flex: 1 }
});

export default App;
