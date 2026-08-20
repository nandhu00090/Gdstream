import React, { useState, useEffect } from 'react';
import { View, Text, FlatList, TouchableOpacity, StyleSheet, ActivityIndicator, Alert, Linking } from 'react-native';
import Video from 'react-native-video';
import axios from 'axios';

const BASE_URL = 'https://movies-and-series.ambalartssb01.workers.dev';
const USERNAME = 'admin'; 
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
      const response = await axios.post(`${BASE_URL}/0:/`, { page_index: 0 });
      if (response.data?.data?.files) setFiles(response.data.data.files);
    } catch (error) { Alert.alert("Error", "Check your password/URL!"); } finally { setLoading(false); }
  };

  const openInExternalPlayer = async () => {
    const url = `${BASE_URL}${selectedFile.link}`;
    // Intent url for Android to open any media player
    const intentUrl = `intent://${url.replace(/^https?:\/\//, '')}#Intent;scheme=https;type=video/*;end`;
    try {
      await Linking.openURL(intentUrl);
    } catch {
      Alert.alert("Error", "Media player illai!");
    }
  };

  if (selectedFile && playMode === 'internal') {
    return (
      <View style={{flex:1, backgroundColor:'black', justifyContent:'center'}}>
        <Video source={{ uri: `${BASE_URL}${selectedFile.link}` }} style={{width:'100%', height:300}} controls={true} />
        <TouchableOpacity onPress={() => setSelectedFile(null)} style={{padding:20}}><Text style={{color:'white'}}>Close</Text></TouchableOpacity>
      </View>
    );
  }

  if (selectedFile) {
    return (
      <View style={styles.selectionContainer}>
        <Text style={{color:'white', fontSize:20}}>{selectedFile.name}</Text>
        <TouchableOpacity style={styles.primaryButton} onPress={() => setPlayMode('internal')}><Text style={{color:'white'}}>Play in App</Text></TouchableOpacity>
        <TouchableOpacity style={styles.secondaryButton} onPress={openInExternalPlayer}><Text style={{color:'white'}}>Play in Player (VLC/Just)</Text></TouchableOpacity>
        <TouchableOpacity onPress={() => setSelectedFile(null)}><Text style={{color:'grey', marginTop:20}}>Back</Text></TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <FlatList data={files} renderItem={({item}) => (
        <TouchableOpacity style={styles.itemCard} onPress={() => setSelectedFile(item)}>
          <Text style={{color:'white'}}>{item.name}</Text>
        </TouchableOpacity>
      )} />
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#141414', paddingTop: 40 },
  itemCard: { backgroundColor: '#222', padding: 20, margin: 10, borderRadius: 10 },
  selectionContainer: { flex: 1, backgroundColor: '#141414', justifyContent: 'center', alignItems: 'center' },
  primaryButton: { backgroundColor: 'red', padding: 15, margin: 10, borderRadius: 5 },
  secondaryButton: { backgroundColor: 'orange', padding: 15, margin: 10, borderRadius: 5 }
});
export default App;
