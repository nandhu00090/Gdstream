import React, { useState, useEffect, useRef } from 'react';
import { View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator, Alert, NativeModules, TextInput } from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';
import Orientation from 'react-native-orientation-locker';
import Icon from 'react-native-vector-icons/MaterialIcons';

const { VideoPlayerManager } = NativeModules;
const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin'; 
// UNGA ORIGINAL PASSWORD INGA PODUNGA:
const PASSWORD = '629175'; 

const App = () => {
  // File Manager States
  const [files, setFiles] = useState([]);
  const [filteredFiles, setFilteredFiles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [pathStack, setPathStack] = useState(['/0:/']); 
  const [currentPath, setCurrentPath] = useState('/0:/');
  const [searchQuery, setSearchQuery] = useState('');
  
  // Player States
  const [selectedFile, setSelectedFile] = useState(null);
  const [playMode, setPlayMode] = useState(null);
  const [resizeMode, setResizeMode] = useState('contain');
  const [showControls, setShowControls] = useState(true);
  const [isPaused, setIsPaused] = useState(false);
  const videoRef = useRef(null);

  // --- FILE MANAGER LOGIC ---
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

  // --- PLAYER LOGIC ---
  const openInExternalPlayer = async () => {
    const url = `${BASE_URL}${selectedFile.link || `/0:/${encodeURIComponent(selectedFile.name)}`}`;
    try { await VideoPlayerManager.playVideo(url); } catch (e) { Alert.alert("Error", "Player illai!"); }
  };

  const toggleFullscreen = () => {
    Orientation.getOrientation((orientation) => {
      if (orientation === 'LANDSCAPE') { Orientation.lockToPortrait(); } 
      else { Orientation.lockToLandscape(); }
    });
  };

  const closeInternalPlayer = () => {
    Orientation.lockToPortrait(); // Close panrappo thirumba portrait vandhudum
    setPlayMode(null);
    setSelectedFile(null);
  };

  // --- CUSTOM VIDEO PLAYER SCREEN ---
  if (selectedFile && playMode === 'internal') {
    return (
      <View style={styles.fullscreenContainer}>
        <Video 
          ref={videoRef}
          source={{ uri: `${BASE_URL}${selectedFile.link || `/0:/${encodeURIComponent(selectedFile.name)}`}` }} 
          style={StyleSheet.absoluteFill} 
          resizeMode={resizeMode}
          paused={isPaused}
          controls={false} // Namma sontha controls!
          bufferConfig={{ minBufferMs: 15000, maxBufferMs: 30000, bufferForPlaybackMs: 2500, bufferForPlaybackAfterRebufferMs: 5000 }}
        />
        
        {/* Modern Controls Overlay */}
        <TouchableOpacity style={StyleSheet.absoluteFill} activeOpacity={1} onPress={() => setShowControls(!showControls)}>
          {showControls && (
            <View style={styles.overlay}>
              {/* Top Bar */}
              <View style={styles.playerTopBar}>
                <TouchableOpacity onPress={closeInternalPlayer} style={{padding: 10}}>
                  <Icon name="arrow-back" size={28} color="white" />
                </TouchableOpacity>
                <Text style={styles.playerTitle} numberOfLines={1}>{selectedFile.name}</Text>
              </View>

              {/* Center Play/Pause */}
              <View style={styles.centerPlayButton}>
                <TouchableOpacity onPress={() => setIsPaused(!isPaused)}>
                  <Icon name={isPaused ? "play-circle-filled" : "pause-circle-filled"} size={70} color="white" />
                </TouchableOpacity>
              </View>

              {/* Bottom Bar (Icons exactly like your screenshot) */}
              <View style={styles.playerBottomBar}>
                <TouchableOpacity onPress={() => setResizeMode(prev => prev === 'contain' ? 'cover' : 'contain')} style={{padding: 10}}>
                  <Icon name={resizeMode === 'contain' ? "aspect-ratio" : "crop-free"} size={28} color="white" />
                </TouchableOpacity>
                
                <View style={{flexDirection: 'row'}}>
                  {/* CC / Subtitle Icon */}
                  <TouchableOpacity style={{padding: 10}} onPress={() => Alert.alert("Coming Soon", "Audio & Subtitle selector will be added in next update!")}>
                    <Icon name="closed-caption" size={28} color="white" />
                  </TouchableOpacity>
                  
                  {/* Fullscreen Icon */}
                  <TouchableOpacity onPress={toggleFullscreen} style={{padding: 10}}>
                    <Icon name="fullscreen" size={28} color="white" />
                  </TouchableOpacity>
                </View>
              </View>
            </View>
          )}
        </TouchableOpacity>
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

  // --- MAIN FOLDER LIST SCREEN ---
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
        <TouchableOpacity style={styles.backButton} onPress={goBack}><Text style={{color:'white'}}>⬅️ Back</Text></TouchableOpacity>
      )}
      
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
  
  // Custom Player Styles
  fullscreenContainer: { flex: 1, backgroundColor: 'black' },
  overlay: { ...StyleSheet.absoluteFillObject, backgroundColor: 'rgba(0,0,0,0.5)', justifyContent: 'space-between' },
  playerTopBar: { flexDirection: 'row', alignItems: 'center', padding: 20, paddingTop: 40 },
  playerTitle: { color: 'white', fontSize: 16, fontWeight: 'bold', marginLeft: 15, flex: 1 },
  centerPlayButton: { alignItems: 'center', justifyContent: 'center' },
  playerBottomBar: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: 20, paddingBottom: 30 }
});

export default App;
