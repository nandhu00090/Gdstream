import React, { useState, useEffect } from 'react';
import { View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator, Alert, NativeModules, TextInput } from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';

const { VideoPlayerManager } = NativeModules;
const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin'; 
const PASSWORD = '629175'; 

const App = () => {
  const [files, setFiles] = useState([]);
  const [filteredFiles, setFilteredFiles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [pathStack, setPathStack] = useState(['/0:/']); // Stack to track folders
  const [currentPath, setCurrentPath] = useState('/0:/');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedFile, setSelectedFile] = useState(null);
  const [playMode, setPlayMode] = useState(null);

  useEffect(() => {
    fetchDirectory('/0:/');
  }, []);

  const fetchDirectory = async (path) => {
    setLoading(true);
    try {
      // Login first
      const loginData = `username=${USERNAME}&password=${PASSWORD}`;
      await axios.post(`${BASE_URL}/login`, loginData, { headers: { 'Content-Type': 'application/x-www-form-urlencoded' }});
      
      const response = await axios.post(`${BASE_URL}${path}`, { page_index: 0 });
      if (response.data?.data?.files) {
        setFiles(response.data.data.files);
        setFilteredFiles(response.data.data.files);
        setCurrentPath(path);
      }
    } catch (error) { Alert.alert("Error", "Folder-ah open panna mudiyala!"); } 
    finally { setLoading(false); }
  };

  const handlePress = (item) => {
    if (item.mimeType?.includes('folder')) {
      const newPath = `${currentPath}${item.name}/`;
      setPathStack([...pathStack, newPath]);
      fetchDirectory(newPath);
    } else {
      setSelectedFile(item);
      setPlayMode(null);
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

  // UI Components...
  if (selectedFile) {
    return (
      <View style={styles.selectionContainer}>
        <Text style={styles.selectionTitle}>{selectedFile.name}</Text>
        <TouchableOpacity style={styles.primaryButton} onPress={() => setPlayMode('internal')}><Text style={styles.buttonText}>Play in App (Exo)</Text></TouchableOpacity>
        <TouchableOpacity style={styles.secondaryButton} onPress={openInExternalPlayer}><Text style={styles.buttonText}>Play in External Player</Text></TouchableOpacity>
        <TouchableOpacity onPress={() => setSelectedFile(null)}><Text style={{color:'grey', marginTop:20}}>Back</Text></TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Text style={styles.headerTitle}>GDStream 🍿</Text>
      <TextInput style={styles.searchBar} placeholder="Search movies..." placeholderTextColor="#888" onChangeText={handleSearch} />
      
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
                <Text style={styles.itemText}>{item.name}</Text>
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
  headerTitle: { color: '#E50914', fontSize: 24, fontWeight: 'bold', textAlign: 'center' },
  searchBar: { backgroundColor: '#222', color: 'white', margin: 15, padding: 12, borderRadius: 8 },
  backButton: { backgroundColor: '#333', padding: 10, marginHorizontal: 15, borderRadius: 5, marginBottom: 10 },
  itemCard: { backgroundColor: '#222', padding: 18, marginVertical: 5, marginHorizontal: 15, borderRadius: 8 },
  itemText: { color: '#FFF', fontSize: 16 },
  selectionContainer: { flex: 1, backgroundColor: '#141414', justifyContent: 'center', alignItems: 'center' },
  selectionTitle: { color: '#FFF', fontSize: 18, textAlign: 'center', margin: 20 },
  primaryButton: { backgroundColor: '#E50914', padding: 15, width: 250, borderRadius: 8, alignItems: 'center' },
  secondaryButton: { backgroundColor: '#E06C00', padding: 15, width: 250, borderRadius: 8, marginTop: 15, alignItems: 'center' },
  buttonText: { color: '#FFF', fontWeight: 'bold' }
});

export default App;
