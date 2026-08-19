import React, { useState, useEffect } from 'react';
import { View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator } from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';

const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin'; 
// KEEZHA IRUKKA EDATHULA UNGA UNMAIYANA PASSWORD-AH MAATHUNGA:
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
      // FIX: Folder illatha ellathaiyum play panna set panrom!
      const videoPath = item.link ? item.link : `/0:/${encodeURIComponent(item.name)}`;
      setCurrentVideo(`${BASE_URL}${videoPath}`);
    }
  };

  const renderItem = ({ item }) => (
    <TouchableOpacity style={styles.itemCard} onPress={() => handlePress(item)}>
      <Text style={styles.itemText}>
        {item.mimeType.includes('folder') ? '📁' : '🎬'} {item.name}
      </Text>
    </TouchableOpacity>
  );

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
            onEnd={() => setCurrentVideo(null)}
          />
          <TouchableOpacity style={styles.closeButton} onPress={() => setCurrentVideo(null)}>
            <Text style={styles.closeText}>Close Player ❌</Text>
          </TouchableOpacity>
        </View>
      ) : (
        loading ? (
          <ActivityIndicator size="large" color="#E50914" style={{ marginTop: 50 }} />
        ) : (
          <FlatList
            data={files}
            keyExtractor={(item) => item.id}
            renderItem={renderItem}
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
  closeButton: { padding: 15, backgroundColor: '#333', alignItems: 'center', marginTop: 20 },
  closeText: { color: '#FFF', fontSize: 16, fontWeight: 'bold' }
});

export default App;
