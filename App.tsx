import React, { useState, useEffect } from 'react';
import { View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator, Alert, Linking } from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';

const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin'; 
// UNGA UNMAIYANA PASSWORD-AH INGA MAATHUNGA:
const PASSWORD = '629175'; 

const App = () => {
  const [files, setFiles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [currentVideo, setCurrentVideo] = useState(null);

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
      const videoPath = item.link ? item.link : `/0:/${encodeURIComponent(item.name)}`;
      setCurrentVideo(`${BASE_URL}${videoPath}`);
    }
  };

  const openInExternalPlayer = async () => {
    if (!currentVideo) return;
    // Android-oda default video player chooser-ah thirakka intent
    const cleanUrl = currentVideo.replace(/^https?:\/\//, '');
    const intentUrl = `intent://${cleanUrl}#Intent;scheme=https;action=android.intent.action.VIEW;type=video/*;end`;
    
    try {
      await Linking.openURL(intentUrl);
    } catch (error) {
      Alert.alert("Error", "VLC allathu MX Player install panni irukka nu check pannunga!");
    }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.headerTitle}>GDStream 🍿</Text>

      {currentVideo ? (
        <View style={styles.playerContainer}>
          <Video
            source={{ uri: currentVideo }}
            style={styles.videoPlayer}
            controls={true}
            resizeMode="contain"
            onError={(e) => {
              console.log("Video Error: ", e);
              Alert.alert("Heavy File ⚠️", "Ithu REMUX file. Keela irukka 'Play in External Player' button-ah use pannunga.");
            }}
          />
          
          <View style={styles.buttonRow}>
            <TouchableOpacity style={styles.externalButton} onPress={openInExternalPlayer}>
              <Text style={styles.buttonText}>Open in VLC / MX 🎦</Text>
            </TouchableOpacity>
            
            <TouchableOpacity style={styles.closeButton} onPress={() => setCurrentVideo(null)}>
              <Text style={styles.buttonText}>Close ❌</Text>
            </TouchableOpacity>
          </View>
        </View>
      ) : (
        loading ? (
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
        )
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
  buttonRow: { flexDirection: 'row', justifyContent: 'space-evenly', marginTop: 20 },
  externalButton: { padding: 15, backgroundColor: '#E50914', borderRadius: 8 },
  closeButton: { padding: 15, backgroundColor: '#333', borderRadius: 8 },
  buttonText: { color: '#FFF', fontSize: 16, fontWeight: 'bold' }
});

export default App;
