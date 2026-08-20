import React, { useState, useEffect } from 'react';
import { View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator, Alert } from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';
import IntentLauncher from 'react-native-intent-launcher';

const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin'; 
// UNGA UNMAIYANA PASSWORD-AH INGA MAATHUNGA:
const PASSWORD = '629175'; 

const App = () => {
  const [files, setFiles] = useState([]);
  const [loading, setLoading] = useState(true);
  
  const [selectedFile, setSelectedFile] = useState(null);
  const [playMode, setPlayMode] = useState(null);

  useEffect(() => {
    authenticateAndFetch();
  }, []);

  const authenticateAndFetch = async () => {
    try {
      const loginData = `username=${USERNAME}&password=${PASSWORD}`;
      await axios.post(`${BASE_URL}/login`, loginData, {
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
      });

      const response = await axios.post(`${BASE_URL}/0:/`, {
        page_index: 0
      });
      
      if (response.data && response.data.data && response.data.data.files) {
        setFiles(response.data.data.files);
      }
    } catch (error) {
      console.error("Error fetching data: ", error);
    } finally {
      setLoading(false);
    }
  };

  const handlePress = (item) => {
    if (item.mimeType === 'application/vnd.google-apps.folder') {
      console.log('Folder clicked:', item.name);
    } else {
      setSelectedFile(item);
      setPlayMode(null);
    }
  };

  const getFileUrl = (item) => {
    const videoPath = item.link ? item.link : `/0:/${encodeURIComponent(item.name)}`;
    return `${BASE_URL}${videoPath}`;
  };

  const openInExternalPlayer = async () => {
    if (!selectedFile) return;
    const url = getFileUrl(selectedFile);
    
    try {
      // FIX: Android OS-kitta ithu "video/*" nu explicit-ah solrom!
      await IntentLauncher.startActivity({
        action: 'android.intent.action.VIEW',
        data: url,
        type: 'video/*',
      });
    } catch (error) {
      Alert.alert("Error ⚠️", "Unga phone-la media players ethuvum kidaikkala!");
    }
  };

  if (selectedFile && playMode === 'internal') {
    return (
      <View style={styles.playerContainer}>
        <Video
          source={{ uri: getFileUrl(selectedFile) }}
          style={styles.videoPlayer}
          controls={true}
          resizeMode="contain"
          bufferConfig={{
            minBufferMs: 15000,
            maxBufferMs: 50000,
            bufferForPlaybackMs: 2500,
            bufferForPlaybackAfterRebufferMs: 5000
          }}
        />
        <TouchableOpacity style={styles.closeButton} onPress={() => setSelectedFile(null)}>
          <Text style={styles.buttonText}>Close Player ❌</Text>
        </TouchableOpacity>
      </View>
    );
  }

  if (selectedFile && playMode === null) {
    return (
      <View style={styles.selectionContainer}>
        <Text style={styles.selectionTitle}>{selectedFile.name}</Text>
        
        <TouchableOpacity style={styles.primaryButton} onPress={() => setPlayMode('internal')}>
          <Text style={styles.buttonText}>Play in App (Normal Files) 🍿</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.secondaryButton} onPress={openInExternalPlayer}>
          <Text style={styles.buttonText}>Play in External Player 🎦</Text>
        </TouchableOpacity>

        <TouchableOpacity style={styles.backButton} onPress={() => setSelectedFile(null)}>
          <Text style={styles.buttonText}>Back to List ⬅️</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.headerTitle}>GDStream 🍿</Text>

      {loading ? (
        <ActivityIndicator size="large" color="#E50914" style={{ marginTop: 50 }} />
      ) : (
        <FlatList
          data={files}
          keyExtractor={(item) => item.id}
          renderItem={({ item }) => (
            <TouchableOpacity style={styles.itemCard} onPress={() => handlePress(item)}>
              <Text style={styles.itemText}>
                {item.mimeType.includes('folder') ? '📁' : '🎬'} {item.name}
              </Text>
            </TouchableOpacity>
          )}
          contentContainerStyle={{ paddingBottom: 20 }}
        />
      )}
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#141414', paddingTop: 40 },
  headerTitle: { color: '#E50914', fontSize: 28, fontWeight: 'bold', textAlign: 'center', marginBottom: 20 },
  itemCard: { backgroundColor: '#222', padding: 18, marginVertical: 8, marginHorizontal: 16, borderRadius: 8 },
  itemText: { color: '#FFF', fontSize: 16 },
  
  playerContainer: { flex: 1, backgroundColor: '#000', justifyContent: 'center' },
  videoPlayer: { width: '100%', height: 300 },
  
  selectionContainer: { flex: 1, backgroundColor: '#141414', justifyContent: 'center', padding: 20 },
  selectionTitle: { color: '#FFF', fontSize: 20, fontWeight: 'bold', textAlign: 'center', marginBottom: 40 },
  primaryButton: { backgroundColor: '#E50914', padding: 15, borderRadius: 8, marginVertical: 10, alignItems: 'center' },
  secondaryButton: { backgroundColor: '#E06C00', padding: 15, borderRadius: 8, marginVertical: 10, alignItems: 'center' },
  backButton: { backgroundColor: '#333', padding: 15, borderRadius: 8, marginVertical: 10, alignItems: 'center', marginTop: 30 },
  
  closeButton: { padding: 15, backgroundColor: '#333', alignItems: 'center', marginTop: 20, marginHorizontal: 50, borderRadius: 8 },
  buttonText: { color: '#FFF', fontSize: 16, fontWeight: 'bold' }
});

export default App;
