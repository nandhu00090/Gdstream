import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  FlatList,
  Modal,
  NativeModules,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import Icon from 'react-native-vector-icons/MaterialIcons';
import { CinemaArcLoader, SlideUpSheet, TactileSpringPressable } from './LoadingAnimations';

const { SecurityVault } = NativeModules;

const INTERNAL_ROOT = '/storage/emulated/0';
const DEFAULT_START_PATH = '/storage/emulated/0/Download';

const QUICK_SHORTCUTS = [
  { label: 'Download', path: '/storage/emulated/0/Download', icon: 'file-download' },
  { label: 'Music', path: '/storage/emulated/0/Music', icon: 'library-music' },
  { label: 'Movies', path: '/storage/emulated/0/Movies', icon: 'movie' },
  { label: 'Internal', path: '/storage/emulated/0', icon: 'smartphone' },
];

export interface LocalDirEntry {
  name: string;
  path: string;
  uri?: string;
  isDirectory: boolean;
  size: number;
  ext?: string;
}

interface CodecStyle {
  label: string;
  bg: string;
  border: string;
  text: string;
}

const CODEC_BADGE_MAP: Record<string, CodecStyle> = {
  eac3: { label: 'EAC3', bg: 'rgba(56, 189, 248, 0.14)', border: 'rgba(56, 189, 248, 0.5)', text: '#38BDF8' },
  ec3: { label: 'EAC3', bg: 'rgba(56, 189, 248, 0.14)', border: 'rgba(56, 189, 248, 0.5)', text: '#38BDF8' },
  ac3: { label: 'AC3', bg: 'rgba(56, 189, 248, 0.12)', border: 'rgba(56, 189, 248, 0.45)', text: '#7DD3FC' },
  dtshd: { label: 'DTS-HD', bg: 'rgba(245, 197, 24, 0.16)', border: 'rgba(245, 197, 24, 0.55)', text: '#F5C518' },
  dts: { label: 'DTS', bg: 'rgba(245, 197, 24, 0.14)', border: 'rgba(245, 197, 24, 0.45)', text: '#FBBF24' },
  thd: { label: 'TRUEHD', bg: 'rgba(192, 132, 252, 0.16)', border: 'rgba(192, 132, 252, 0.55)', text: '#C084FC' },
  truehd: { label: 'TRUEHD', bg: 'rgba(192, 132, 252, 0.16)', border: 'rgba(192, 132, 252, 0.55)', text: '#C084FC' },
  flac: { label: 'FLAC', bg: 'rgba(70, 211, 105, 0.15)', border: 'rgba(70, 211, 105, 0.5)', text: '#46D369' },
  mka: { label: 'MKA', bg: 'rgba(229, 9, 20, 0.16)', border: 'rgba(229, 9, 20, 0.55)', text: '#F87171' },
  aac: { label: 'AAC', bg: 'rgba(96, 165, 250, 0.14)', border: 'rgba(96, 165, 250, 0.48)', text: '#60A5FA' },
  m4a: { label: 'AAC', bg: 'rgba(96, 165, 250, 0.14)', border: 'rgba(96, 165, 250, 0.48)', text: '#60A5FA' },
  opus: { label: 'OPUS', bg: 'rgba(167, 139, 250, 0.15)', border: 'rgba(167, 139, 250, 0.48)', text: '#A78BFA' },
  ogg: { label: 'OGG', bg: 'rgba(167, 139, 250, 0.14)', border: 'rgba(167, 139, 250, 0.45)', text: '#C4B5FD' },
  mp3: { label: 'MP3', bg: 'rgba(251, 146, 60, 0.14)', border: 'rgba(251, 146, 60, 0.48)', text: '#FB923C' },
  wav: { label: 'WAV', bg: 'rgba(52, 211, 153, 0.14)', border: 'rgba(52, 211, 153, 0.48)', text: '#34D399' },
};

const getCodecBadge = (name: string, ext?: string): CodecStyle => {
  const cleanExt = (ext || name.split('.').pop() || '').toLowerCase();
  if (CODEC_BADGE_MAP[cleanExt]) return CODEC_BADGE_MAP[cleanExt];
  return {
    label: (cleanExt || 'AUDIO').toUpperCase(),
    bg: 'rgba(148, 163, 184, 0.14)',
    border: 'rgba(148, 163, 184, 0.4)',
    text: '#CBD5E1',
  };
};

const formatFileSize = (bytes: number): string => {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1)} MB`;
  return `${(mb / 1024).toFixed(2)} GB`;
};

const parentOfPath = (dir: string): string => {
  const trimmed = dir.replace(/\/+$/, '');
  if (!trimmed || trimmed === INTERNAL_ROOT || trimmed === '/') return INTERNAL_ROOT;
  const idx = trimmed.lastIndexOf('/');
  if (idx <= 0) return INTERNAL_ROOT;
  const parent = trimmed.slice(0, idx);
  return parent.length < INTERNAL_ROOT.length ? INTERNAL_ROOT : parent;
};

interface InAppAudioFileBrowserProps {
  visible: boolean;
  initialPath?: string;
  currentAudioUri?: string;
  onSelectAudio: (track: { name: string; uri: string }) => void;
  onOpenSystemPicker: () => void;
  onClose: () => void;
}

const InAppAudioFileBrowser: React.FC<InAppAudioFileBrowserProps> = ({
  visible,
  initialPath = DEFAULT_START_PATH,
  currentAudioUri,
  onSelectAudio,
  onOpenSystemPicker,
  onClose,
}) => {
  const [currentDir, setCurrentDir] = useState(initialPath);
  const [historyStack, setHistoryStack] = useState<string[]>([]);
  const [entries, setEntries] = useState<LocalDirEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const requestIdRef = useRef(0);

  const loadDirectory = useCallback(async (targetPath: string, pushHistory = false) => {
    const reqId = ++requestIdRef.current;
    setLoading(true);
    setErrorMsg(null);

    try {
      if (!SecurityVault?.listLocalDirectory) {
        throw new Error('Local directory module unavailable. Use System Picker below.');
      }
      const res = await SecurityVault.listLocalDirectory(targetPath);
      if (reqId !== requestIdRef.current) return;

      const resolvedPath = typeof res?.path === 'string' && res.path ? res.path : targetPath;
      const rawEntries: LocalDirEntry[] = Array.isArray(res?.entries) ? res.entries : [];

      if (pushHistory && resolvedPath !== currentDir) {
        setHistoryStack(prev => [...prev, currentDir]);
      }
      setCurrentDir(resolvedPath);
      setEntries(rawEntries);
    } catch (err: any) {
      if (reqId !== requestIdRef.current) return;
      setEntries([]);
      setErrorMsg(err?.message || 'Unable to open folder. Storage access may be restricted.');
    } finally {
      if (reqId === requestIdRef.current) setLoading(false);
    }
  }, [currentDir]);

  useEffect(() => {
    if (!visible) return;
    setHistoryStack([]);
    void loadDirectory(initialPath, false);
    // Load target path cleanly each time modal becomes visible.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, initialPath]);

  const handleBack = () => {
    if (historyStack.length > 0) {
      const prev = historyStack[historyStack.length - 1];
      setHistoryStack(s => s.slice(0, -1));
      void loadDirectory(prev, false);
    } else if (currentDir !== INTERNAL_ROOT) {
      void loadDirectory(parentOfPath(currentDir), false);
    } else {
      onClose();
    }
  };

  const handleUp = () => {
    if (currentDir === INTERNAL_ROOT || currentDir === '/') return;
    void loadDirectory(parentOfPath(currentDir), true);
  };

  const handleHome = () => {
    if (currentDir === INTERNAL_ROOT) return;
    void loadDirectory(INTERNAL_ROOT, true);
  };

  const handleEntryPress = (item: LocalDirEntry) => {
    if (item.isDirectory) {
      void loadDirectory(item.path, true);
      return;
    }
    const uri = item.uri || (item.path.startsWith('content://') || item.path.startsWith('file://')
      ? item.path
      : `file://${item.path}`);
    onSelectAudio({ name: item.name, uri });
    onClose();
  };

  const audioCount = entries.filter(e => !e.isDirectory).length;
  const folderCount = entries.filter(e => e.isDirectory).length;

  return (
    <Modal
      visible={visible}
      transparent={true}
      animationType="fade"
      onRequestClose={onClose}>
      <View style={styles.backdrop}>
        <SlideUpSheet style={styles.card}>
          <View pointerEvents="none" style={styles.topSpecularLine} />

          {/* Header Bar */}
          <View style={styles.headerRow}>
            <View style={{ flex: 1 }}>
              <Text style={styles.headerTitle}>Select Audio Track</Text>
              <Text style={styles.headerPath} numberOfLines={1} ellipsizeMode="head">
                {currentDir}
              </Text>
            </View>
            <View style={styles.navActionsRow}>
              <TactileSpringPressable
                onPress={handleBack}
                activeScale={0.92}
                glowColor="rgba(255, 255, 255, 0.35)"
                glowBorderRadius={18}
                style={styles.navCircleBtn}
                accessibilityLabel="Back">
                <Icon name="arrow-back" size={18} color="#E2E8F0" />
              </TactileSpringPressable>
              <TactileSpringPressable
                onPress={handleHome}
                activeScale={0.92}
                glowColor="rgba(255, 255, 255, 0.35)"
                glowBorderRadius={18}
                style={styles.navCircleBtn}
                accessibilityLabel="Home folder">
                <Icon name="home" size={18} color="#E2E8F0" />
              </TactileSpringPressable>
              <TactileSpringPressable
                onPress={handleUp}
                disabled={currentDir === INTERNAL_ROOT || currentDir === '/'}
                activeScale={0.92}
                glowColor="rgba(255, 255, 255, 0.35)"
                glowBorderRadius={18}
                style={[styles.navCircleBtn, (currentDir === INTERNAL_ROOT || currentDir === '/') && { opacity: 0.4 }]}
                accessibilityLabel="Parent folder">
                <Icon name="arrow-upward" size={18} color="#E2E8F0" />
              </TactileSpringPressable>
              <TactileSpringPressable
                onPress={onClose}
                activeScale={0.92}
                glowColor="rgba(229, 9, 20, 0.65)"
                glowBorderRadius={18}
                style={[styles.navCircleBtn, styles.closeCircleBtn]}
                accessibilityLabel="Close browser">
                <Icon name="close" size={18} color="#FFFFFF" />
              </TactileSpringPressable>
            </View>
          </View>

          {/* Quick-Shortcut Pills */}
          <ScrollView
            horizontal={true}
            showsHorizontalScrollIndicator={false}
            style={styles.shortcutsScroller}
            contentContainerStyle={styles.shortcutsContent}>
            {QUICK_SHORTCUTS.map(sc => {
              const isActive = currentDir.replace(/\/+$/, '') === sc.path;
              return (
                <TactileSpringPressable
                  key={sc.path}
                  onPress={() => {
                    if (!isActive) void loadDirectory(sc.path, true);
                  }}
                  activeScale={0.95}
                  glowColor={isActive ? '#E50914' : 'rgba(255, 255, 255, 0.3)'}
                  glowBorderRadius={999}
                  style={[styles.shortcutPill, isActive && styles.shortcutPillActive]}>
                  <Icon name={sc.icon} size={14} color={isActive ? '#FFFFFF' : '#94A3B8'} />
                  <Text style={[styles.shortcutPillText, isActive && { color: '#FFFFFF' }]}>
                    {sc.label}
                  </Text>
                </TactileSpringPressable>
              );
            })}
          </ScrollView>

          {/* Directory & Audio File List */}
          <View style={styles.listBody}>
            {loading ? (
              <View style={styles.centerStateBox}>
                <CinemaArcLoader size="medium" statusText="Scanning folder..." />
              </View>
            ) : errorMsg ? (
              <View style={styles.centerStateBox}>
                <Icon name="folder-off" size={34} color="#64748B" />
                <Text style={styles.emptyTitle}>{errorMsg}</Text>
                <TactileSpringPressable
                  onPress={onOpenSystemPicker}
                  activeScale={0.96}
                  glowColor="#38BDF8"
                  glowBorderRadius={10}
                  style={styles.retrySystemBtn}>
                  <Icon name="folder-open" size={16} color="#38BDF8" />
                  <Text style={styles.retrySystemBtnText}>Open System Picker</Text>
                </TactileSpringPressable>
              </View>
            ) : entries.length === 0 ? (
              <View style={styles.centerStateBox}>
                <Icon name="audiotrack" size={34} color="#475569" />
                <Text style={styles.emptyTitle}>No subfolders or supported audio files</Text>
                <Text style={styles.emptySub}>
                  Supports .mka, .ac3, .eac3, .dts, .dtshd, .thd, .m4a, .aac, .mp3, .flac, .opus, .ogg, .wav
                </Text>
              </View>
            ) : (
              <FlatList
                data={entries}
                keyExtractor={(it, idx) => it.path || `${it.name}_${idx}`}
                showsVerticalScrollIndicator={true}
                contentContainerStyle={{ paddingBottom: 8 }}
                renderItem={({ item }) => {
                  if (item.isDirectory) {
                    return (
                      <TactileSpringPressable
                        onPress={() => handleEntryPress(item)}
                        activeScale={0.98}
                        glowColor="rgba(245, 197, 24, 0.55)"
                        glowBorderRadius={12}
                        style={styles.entryRow}>
                        <View style={styles.folderIconBox}>
                          <Icon name="folder" size={20} color="#F5C518" />
                        </View>
                        <View style={{ flex: 1 }}>
                          <Text style={styles.entryName} numberOfLines={1}>
                            {item.name}
                          </Text>
                          <Text style={styles.entrySub}>Folder</Text>
                        </View>
                        <Icon name="chevron-right" size={20} color="#64748B" />
                      </TactileSpringPressable>
                    );
                  }

                  const codec = getCodecBadge(item.name, item.ext);
                  const entryUri = item.uri || `file://${item.path}`;
                  const isSelected = Boolean(currentAudioUri && currentAudioUri === entryUri);

                  return (
                    <TactileSpringPressable
                      onPress={() => handleEntryPress(item)}
                      activeScale={0.98}
                      glowColor={codec.text}
                      glowBorderRadius={12}
                      style={[styles.entryRow, isSelected && styles.entryRowSelected]}>
                      <View style={[styles.audioIconBox, { borderColor: codec.border, backgroundColor: codec.bg }]}>
                        <Icon name="audiotrack" size={19} color={codec.text} />
                      </View>
                      <View style={{ flex: 1 }}>
                        <Text style={[styles.entryName, isSelected && { color: '#FFFFFF' }]} numberOfLines={1}>
                          {item.name}
                        </Text>
                        <View style={styles.audioMetaRow}>
                          <View style={[styles.codecBadge, { backgroundColor: codec.bg, borderColor: codec.border }]}>
                            <Text style={[styles.codecBadgeText, { color: codec.text }]}>{codec.label}</Text>
                          </View>
                          {item.size > 0 && (
                            <Text style={styles.entrySub}>{formatFileSize(item.size)}</Text>
                          )}
                        </View>
                      </View>
                      {isSelected ? (
                        <Icon name="check-circle" size={20} color="#E50914" />
                      ) : (
                        <Icon name="play-circle-outline" size={20} color="#94A3B8" />
                      )}
                    </TactileSpringPressable>
                  );
                }}
              />
            )}
          </View>

          {/* Subtle Footer */}
          <View style={styles.footerRow}>
            <Text style={styles.footerStats}>
              {`${folderCount} folders • ${audioCount} tracks`}
            </Text>
            <TactileSpringPressable
              onPress={onOpenSystemPicker}
              activeScale={0.95}
              glowColor="rgba(56, 189, 248, 0.55)"
              glowBorderRadius={8}
              style={styles.systemPickerPill}>
              <Icon name="launch" size={13} color="#94A3B8" />
              <Text style={styles.systemPickerText}>System Picker</Text>
            </TactileSpringPressable>
          </View>
        </SlideUpSheet>
      </View>
    </Modal>
  );
};

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.78)',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 14,
    paddingVertical: 16,
  },
  card: {
    width: '100%',
    maxWidth: 620,
    maxHeight: '90%',
    backgroundColor: '#10131B',
    borderRadius: 22,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.09)',
    paddingTop: 14,
    paddingHorizontal: 15,
    paddingBottom: 12,
    overflow: 'hidden',
    position: 'relative',
  },
  topSpecularLine: {
    position: 'absolute',
    top: 0,
    left: 22,
    right: 22,
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.12)',
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  headerTitle: {
    color: '#FFFFFF',
    fontSize: 17,
    fontWeight: '800',
  },
  headerPath: {
    color: '#94A3B8',
    fontSize: 11.5,
    fontWeight: '600',
    marginTop: 2,
  },
  navActionsRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  navCircleBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: '#171C28',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.1)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeCircleBtn: {
    backgroundColor: 'rgba(229, 9, 20, 0.22)',
    borderColor: 'rgba(229, 9, 20, 0.5)',
  },
  shortcutsScroller: {
    flexGrow: 0,
    marginTop: 11,
    marginBottom: 8,
  },
  shortcutsContent: {
    gap: 7,
    paddingRight: 8,
  },
  shortcutPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    backgroundColor: '#161A25',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.08)',
    paddingHorizontal: 11,
    paddingVertical: 6,
    borderRadius: 999,
  },
  shortcutPillActive: {
    backgroundColor: 'rgba(229, 9, 20, 0.22)',
    borderColor: '#E50914',
  },
  shortcutPillText: {
    color: '#CBD5E1',
    fontSize: 12,
    fontWeight: '700',
  },
  listBody: {
    minHeight: 175,
    maxHeight: 330,
    flexShrink: 1,
  },
  centerStateBox: {
    minHeight: 165,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 18,
    gap: 8,
  },
  emptyTitle: {
    color: '#CBD5E1',
    fontSize: 13.5,
    fontWeight: '700',
    textAlign: 'center',
  },
  emptySub: {
    color: '#64748B',
    fontSize: 11,
    textAlign: 'center',
    lineHeight: 16,
  },
  retrySystemBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginTop: 6,
    backgroundColor: 'rgba(56, 189, 248, 0.12)',
    borderWidth: 1,
    borderColor: 'rgba(56, 189, 248, 0.45)',
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 10,
  },
  retrySystemBtnText: {
    color: '#38BDF8',
    fontSize: 12.5,
    fontWeight: '700',
  },
  entryRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 11,
    backgroundColor: '#161B26',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.06)',
    borderRadius: 12,
    paddingHorizontal: 11,
    paddingVertical: 9,
    marginBottom: 7,
  },
  entryRowSelected: {
    backgroundColor: 'rgba(229, 9, 20, 0.14)',
    borderColor: 'rgba(229, 9, 20, 0.65)',
  },
  folderIconBox: {
    width: 36,
    height: 36,
    borderRadius: 10,
    backgroundColor: 'rgba(245, 197, 24, 0.12)',
    borderWidth: 1,
    borderColor: 'rgba(245, 197, 24, 0.35)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  audioIconBox: {
    width: 36,
    height: 36,
    borderRadius: 10,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  entryName: {
    color: '#E2E8F0',
    fontSize: 13.5,
    fontWeight: '700',
  },
  audioMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginTop: 3,
  },
  codecBadge: {
    borderWidth: 1,
    borderRadius: 5,
    paddingHorizontal: 6,
    paddingVertical: 1.5,
  },
  codecBadgeText: {
    fontSize: 9.5,
    fontWeight: '900',
    letterSpacing: 0.4,
  },
  entrySub: {
    color: '#8492A6',
    fontSize: 11,
    fontWeight: '600',
  },
  footerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 8,
    paddingTop: 8,
    borderTopWidth: 1,
    borderTopColor: 'rgba(255, 255, 255, 0.07)',
  },
  footerStats: {
    color: '#64748B',
    fontSize: 11,
    fontWeight: '600',
  },
  systemPickerPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    backgroundColor: '#161A25',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.09)',
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 8,
  },
  systemPickerText: {
    color: '#94A3B8',
    fontSize: 11.5,
    fontWeight: '700',
  },
});

export default InAppAudioFileBrowser;
