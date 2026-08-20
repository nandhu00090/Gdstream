import React, { useState, useEffect, useRef } from 'react';
import { View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator, Alert, NativeModules, TextInput, Modal, ScrollView } from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';
import Orientation from 'react-native-orientation-locker';
import Icon from 'react-native-vector-icons/MaterialIcons';

const { VideoPlayerManager } = NativeModules;
const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin'; 
const PASSWORD = '629175'; 

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
  const videoRef = useRef(null);

  const [audioTracks, setAudioTracks] = useState([]);
  const [textTracks, setTextTracks] = useState([]);
  const [selectedAudio, setSelectedAudio] = useState({ type: 'system' });
  const [selectedText, setSelectedText] = useState({ type: 'disabled' });
  const [activeModal, setActiveModal] = useState(null); 

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
    if (meta.audioTracks) setAudioTracks(meta.audioTracks);
    if (meta.textTracks) setTextTracks(meta.textTracks);
  };

  const renderTrackModal = () => {
    const isAudio = activeModal === 'audio';
    const tracks = isAudio ? audioTracks : textTracks;
    
    return (
      <Modal visible={!!activeModal} transparent={true} animationType="fade">
        <View style={styles.modalBackground}>
          <View style={styles.modalContainer}>
            <Text style={styles.modalTitle}>{isAudio ? "Audio Tracks 🎵" : "Subtitles 💬"}</Text>
            <ScrollView style={{width: '100%'}}>
              
              {!isAudio && (
                <TouchableOpacity 
                  style={styles.trackItem} 
                  onPress={() => { setSelectedText({ type: 'disabled' }); setActiveModal(null); }}>
                  <Text style={[styles.trackText, selectedText.type === 'disabled' && styles.trackSelected]}>
                    Disable Subtitles
                  </Text>
                </TouchableOpacity>
              )}

              {tracks.map((track, index) => (
                <TouchableOpacity 
                  key={index} 
                  style={styles.trackItem}
                  onPress={() => {
                    if (isAudio) setSelectedAudio({ type: 'index', value: index });
                    else setSelectedText({ type: 'index', value: index });
                    setActiveModal(null);
                  }}
                >
                  <Text style={[
                    styles.trackText, 
                    (isAudio ? selectedAudio.value === index : selectedText.value === index) && styles.trackSelected
                  ]}>
                    {track.language ? track.language.toUpperCase() : `Track ${index + 1}`} - {track.title || "Unknown"}
                  </Text>
                </TouchableOpacity>
              ))}
              
              {tracks.length === 0 && <Text style={{color: 'grey', textAlign: 'center'}}>No tracks found</Text>}
            </ScrollView>
            <TouchableOpacity onPress={() => setActiveModal(null)} style={styles.modalCloseBtn}>
              <Text style={{color: 'white', fontWeight: 'bold'}}>Close</Text>
            </TouchableOpacity>
          </View>
        </View>
      </Modal>
    );
  };

  if (selectedFile && playMode === 'internal') {
    return (
      <View style={styles.playerWrapper}>
        
        {/* Hardware Accelerated Video */}
        <Video 
          ref={videoRef}
          source={{ uri: `${BASE_URL}${selectedFile.link || `/0:/${encodeURIComponent(selectedFile.name)}`}` }} 
          style={StyleSheet.absoluteFill} 
          resizeMode={resizeMode}
          paused={isPaused}
          onLoad={handleVideoLoad}
          selectedAudioTrack={selectedAudio}
          selectedTextTrack={selectedText}
          controls={false} 
          useTextureView={false} // 🔥 Fixes BluRay Remux Hardware decoding
          bufferConfig={{ minBufferMs: 15000, maxBufferMs: 30000, bufferForPlaybackMs: 2500, bufferForPlaybackAfterRebufferMs: 5000 }}
        />
        
        {/* Bulletproof UI Overlay without Elevation/Z-Index */}
        <TouchableOpacity 
          style={StyleSheet.absoluteFill} 
          activeOpacity={1} 
          onPress={() => setShowControls(!showControls)}
        >
          {showControls && (
            <View style={styles.controlsOverlay}>
              {/* Top */}
              <View style={styles.playerTopBar}>
                <TouchableOpacity onPress={closeInternalPlayer} style={{padding: 10}}><Icon name="arrow-back" size={28} color="white" /></TouchableOpacity>
                <Text style={styles.playerTitle} numberOfLines={1}>{selectedFile.name}</Text>
              </View>

              {/* Center */}
              <View style={{alignItems: 'center'}}>
                <TouchableOpacity onPress={() => setIsPaused(!isPaused)}>
                  <Icon name={isPaused ? "play-circle-filled" : "pause-circle-filled"} size={70} color="white" />
                </TouchableOpacity>
              </View>

              {/* Bottom */}
              <View style={styles.playerBottomBar}>
                <TouchableOpacity onPress={() => setResizeMode(prev => prev === 'contain' ? 'cover' : 'contain')} style={{padding: 10}}>
                  <Icon name={resizeMode === 'contain' ? "aspect-ratio" : "crop-free"} size={28} color="white" />
                </TouchableOpacity>
                
                <View style={{flexDirection: 'row'}}>
                  <TouchableOpacity style={{padding: 10, marginRight: 10}} onPress={() => setActiveModal('audio')}>
                    <Icon name="audiotrack" size={28} color={selectedAudio.type !== 'system' ? '#E50914' : 'white'} />
                  </TouchableOpacity>
                  
                  <TouchableOpacity style={{padding: 10, marginRight: 10}} onPress={() => setActiveModal('subtitle')}>
                    <Icon name="subtitles" size={28} color={selectedText.type !== 'disabled' ? '#E50914' : 'white'} />
                  </TouchableOpacity>
                  
                  <TouchableOpacity onPress={toggleFullscreen} style={{padding: 10}}>
                    <Icon name={isFullscreen ? "fullscreen-exit" : "fullscreen"} size={28} color="white" />
                  </TouchableOpacity>
                </View>
              </View>
            </View>
          )}
        </TouchableOpacity>
        
        {renderTrackModal()}
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
  
  // 🔥 CLEANED PLAYER STYLES (No more crashes)
  playerWrapper: { flex: 1, backgroundColor: 'black' },
  controlsOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.4)', justifyContent: 'space-between' },
  playerTopBar: { flexDirection: 'row', alignItems: 'center', padding: 20, paddingTop: 40 },
  playerTitle: { color: 'white', fontSize: 16, fontWeight: 'bold', marginLeft: 15, flex: 1 },
  playerBottomBar: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 20, paddingBottom: 30 },
  
  // Modal Styles
  modalBackground: { flex: 1, backgroundColor: 'rgba(0,0,0,0.8)', justifyContent: 'center', alignItems: 'center' },
  modalContainer: { width: '85%', maxHeight: '70%', backgroundColor: '#222', borderRadius: 12, padding: 20, alignItems: 'center' },
  modalTitle: { color: 'white', fontSize: 20, fontWeight: 'bold', marginBottom: 20 },
  trackItem: { paddingVertical: 15, width: '100%', borderBottomWidth: 1, borderBottomColor: '#444' },
  trackText: { color: '#CCC', fontSize: 16, textAlign: 'center' },
  trackSelected: { color: '#E50914', fontWeight: 'bold', fontSize: 18 },
  modalCloseBtn: { marginTop: 20, backgroundColor: '#E50914', padding: 15, borderRadius: 8, width: '100%', alignItems: 'center' }
});

export default App;
