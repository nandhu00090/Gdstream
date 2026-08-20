import React, { useState, useEffect, useRef } from 'react';
import { View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator, Alert, NativeModules, TextInput, ScrollView } from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';
import Orientation from 'react-native-orientation-locker';
import Icon from 'react-native-vector-icons/MaterialIcons';

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
  
  // Progress States
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const videoRef = useRef(null);

  // Audio & Subtitle States
  const [audioTracks, setAudioTracks] = useState([]);
  const [textTracks, setTextTracks] = useState([]);
  const [selectedAudio, setSelectedAudio] = useState({ type: 'system' });
  const [selectedText, setSelectedText] = useState({ type: 'disabled' });
  const [activeMenu, setActiveMenu] = useState(null); // 'audio' | 'subtitle' | null

  useEffect(() => { fetchDirectory('/0:/'); }, []);

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
        setSearchQuery('');
      }
    } catch (error) { Alert.alert("Error", "Folder load aagala!"); } 
    finally { setLoading(false); }
  };

  const handlePress = (item) => {
    if (item.mimeType?.includes('folder')) {
      const newPath = `${currentPath}${item.name}/`;
      setPathStack([...pathStack, newPath]);
      fetchDirectory(newPath);
    } else {
      setSelectedFile(item);
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

  const handleVideoLoad = (meta) => {
    setDuration(meta.duration);
    if (meta.audioTracks) setAudioTracks(meta.audioTracks);
    if (meta.textTracks) setTextTracks(meta.textTracks);
  };

  const handleProgress = (progress) => {
    setCurrentTime(progress.currentTime);
  };

  const seekForward = () => { videoRef.current?.seek(currentTime + 10); };
  const seekBackward = () => { videoRef.current?.seek(currentTime - 10); };

  // --- JUST PLAYER STYLE FLOATING MENU ---
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
              <Icon name="check" size={20} color={selectedText.type === 'disabled' ? 'white' : 'transparent'} />
              <Text style={styles.menuItemText}>Disable Subtitles</Text>
            </TouchableOpacity>
          )}

          {tracks.map((track, index) => {
            const isSelected = isAudio ? selectedAudio.value === index : selectedText.value === index;
            return (
              <TouchableOpacity key={index} style={styles.menuItem} onPress={() => {
                if (isAudio) setSelectedAudio({ type: 'index', value: index });
                else setSelectedText({ type: 'index', value: index });
                setActiveMenu(null);
              }}>
                <Icon name="check" size={20} color={isSelected ? 'white' : 'transparent'} />
                <Text style={styles.menuItemText}>
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
    // Calculate progress bar width
    const progressPercent = duration > 0 ? (currentTime / duration) * 100 : 0;

    return (
      <View style={styles.playerWrapper}>
        
        {/* Layer 1: Hardware Accelerated Video. Background transparent is the MAGIC FIX! */}
        <Video 
          ref={videoRef}
          source={{ uri: `${BASE_URL}${selectedFile.link || `/0:/${encodeURIComponent(selectedFile.name)}`}` }} 
          style={styles.videoPlayer} 
          resizeMode={resizeMode}
          paused={isPaused}
          onLoad={handleVideoLoad}
          onProgress={handleProgress}
          selectedAudioTrack={selectedAudio}
          selectedTextTrack={selectedText}
          controls={false} 
          useTextureView={false} // Hardware Decoding for 10-bit Bluray!
          bufferConfig={{ minBufferMs: 15000, maxBufferMs: 30000, bufferForPlaybackMs: 2500, bufferForPlaybackAfterRebufferMs: 5000 }}
        />
        
        {/* Layer 2: Just Player UI Clone */}
        <TouchableOpacity style={StyleSheet.absoluteFill} activeOpacity={1} onPress={() => { setShowControls(!showControls); setActiveMenu(null); }}>
          {showControls && (
            <View style={styles.controlsOverlay}>
              
              {/* Top Bar: Title */}
              <View style={styles.playerTopBar}>
                <TouchableOpacity onPress={closeInternalPlayer} style={{padding: 10}}><Icon name="arrow-back" size={28} color="white" /></TouchableOpacity>
                <Text style={styles.playerTitle} numberOfLines={1}>{selectedFile.name}</Text>
              </View>

              {/* Center Bar: Play/Pause/Seek */}
              <View style={styles.centerControls}>
                <TouchableOpacity onPress={seekBackward} style={{marginRight: 40}}>
                  <Icon name="replay-10" size={45} color="white" />
                </TouchableOpacity>
                
                {/* Just Player White Circle Button */}
                <TouchableOpacity onPress={() => setIsPaused(!isPaused)} style={styles.whiteCircleBtn}>
                  <Icon name={isPaused ? "play-arrow" : "pause"} size={40} color="black" />
                </TouchableOpacity>
                
                <TouchableOpacity onPress={seekForward} style={{marginLeft: 40}}>
                  <Icon name="forward-10" size={45} color="white" />
                </TouchableOpacity>
              </View>

              {/* Bottom Bar: Progress & Icons */}
              <View style={styles.playerBottomArea}>
                {/* Progress Bar */}
                <View style={styles.progressBarBg}>
                  <View style={[styles.progressBarFill, { width: `${progressPercent}%` }]} />
                  <View style={[styles.progressDot, { left: `${progressPercent}%` }]} />
                </View>
                
                <View style={styles.playerBottomControls}>
                  {/* Time Text */}
                  <Text style={styles.timeText}>{formatTime(currentTime)} • {formatTime(duration)}</Text>
                  
                  {/* Just Player Style Icons Right Side */}
                  <View style={{flexDirection: 'row', alignItems: 'center'}}>
                    <TouchableOpacity style={styles.iconBtn} onPress={() => setActiveMenu(activeMenu === 'audio' ? null : 'audio')}>
                      <Icon name="audiotrack" size={26} color={activeMenu === 'audio' ? '#E50914' : 'white'} />
                    </TouchableOpacity>
                    
                    <TouchableOpacity style={styles.iconBtn} onPress={() => setActiveMenu(activeMenu === 'subtitle' ? null : 'subtitle')}>
                      <Icon name="closed-caption" size={26} color={activeMenu === 'subtitle' ? '#E50914' : 'white'} />
                    </TouchableOpacity>
                    
                    <TouchableOpacity style={styles.iconBtn} onPress={() => setResizeMode(prev => prev === 'contain' ? 'cover' : 'contain')}>
                      <Icon name={resizeMode === 'contain' ? "aspect-ratio" : "crop-free"} size={26} color="white" />
                    </TouchableOpacity>

                    <TouchableOpacity onPress={toggleFullscreen} style={styles.iconBtn}>
                      <Icon name={isFullscreen ? "fullscreen-exit" : "fullscreen"} size={26} color="white" />
                    </TouchableOpacity>
                  </View>
                </View>
              </View>

            </View>
          )}
        </TouchableOpacity>

        {/* Layer 3: Floating Menu (Just Player Style) */}
        {renderFloatingMenu()}
      </View>
    );
  }

  // --- SELECTION SCREEN ---
  if (selectedFile) {
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

  // --- MAIN SCREEN ---
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
  
  // 🔥 THE JUST PLAYER UI CLONE 🔥
  playerWrapper: { flex: 1, backgroundColor: 'transparent' }, // MAGIC FIX: Must be transparent for hardware decode!
  videoPlayer: { ...StyleSheet.absoluteFillObject, backgroundColor: 'black' }, // Video itself has black bg
  controlsOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'space-between' },
  
  playerTopBar: { flexDirection: 'row', alignItems: 'center', padding: 20, paddingTop: 40 },
  playerTitle: { color: 'white', fontSize: 16, fontWeight: '500', marginLeft: 15, flex: 1 },
  
  centerControls: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center' },
  whiteCircleBtn: { backgroundColor: 'white', width: 70, height: 70, borderRadius: 35, alignItems: 'center', justifyContent: 'center' },
  
  playerBottomArea: { paddingHorizontal: 20, paddingBottom: 30, width: '100%' },
  progressBarBg: { height: 4, backgroundColor: 'rgba(255,255,255,0.3)', borderRadius: 2, marginBottom: 15, width: '100%', position: 'relative' },
  progressBarFill: { height: '100%', backgroundColor: 'white', borderRadius: 2 },
  progressDot: { position: 'absolute', width: 14, height: 14, borderRadius: 7, backgroundColor: 'white', top: -5, marginLeft: -7 },
  
  playerBottomControls: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  timeText: { color: 'white', fontSize: 14, fontWeight: '500' },
  iconBtn: { padding: 10, marginLeft: 5 },
  
  // Floating Menu Styles (Just Player Style Popup)
  floatingMenu: { position: 'absolute', bottom: 90, right: 20, width: 280, backgroundColor: 'rgba(28, 28, 30, 0.95)', borderRadius: 12, padding: 15, zIndex: 100 },
  floatingMenuTitle: { color: '#888', fontSize: 14, marginBottom: 10, fontWeight: 'bold', borderBottomWidth: 1, borderBottomColor: '#444', paddingBottom: 10 },
  menuItem: { flexDirection: 'row', alignItems: 'center', paddingVertical: 12 },
  menuItemText: { color: 'white', fontSize: 16, marginLeft: 10 }
});

export default App;
