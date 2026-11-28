import React, { useEffect, useRef } from 'react';
import {
  Animated,
  Easing,
  Image,
  ImageProps,
  ImageStyle,
  Platform,
  Pressable,
  PressableProps,
  StyleProp,
  StyleSheet,
  Text,
  View,
  ViewStyle,
} from 'react-native';

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);
const AnimatedImage = Animated.createAnimatedComponent(Image);

// ---------------------------------------------------------------------------
// 1a. ShimmerCardGrid — Metallic Shimmer Skeleton (2-col / TV 5-col grid)
// ---------------------------------------------------------------------------
interface ShimmerCardGridProps {
  columns?: number;
  count?: number;
  style?: StyleProp<ViewStyle>;
}

const ShimmerSkeletonCard: React.FC<{
  index: number;
  columns: number;
  shimmerProgress: Animated.Value;
}> = ({ index, columns, shimmerProgress }) => {
  const fadeAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(fadeAnim, {
      toValue: 1,
      duration: 240,
      delay: index * 45,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [fadeAnim, index]);

  const sweepTranslateX = shimmerProgress.interpolate({
    inputRange: [0, 1],
    outputRange: [-180, 260],
  });

  const cardWidthStyle: ViewStyle =
    columns >= 5 ? { width: '18.6%', margin: '0.7%' } : { width: '47%', margin: '1.5%' };

  return (
    <Animated.View style={[styles.skeletonCard, cardWidthStyle, { opacity: fadeAnim }]}>
      <View style={styles.skeletonPoster}>
        <View style={styles.skeletonTopRow}>
          <View style={styles.skeletonPillLeft} />
          <View style={styles.skeletonPillRight} />
        </View>
        <Animated.View
          pointerEvents="none"
          style={[
            styles.shimmerSweep,
            { transform: [{ translateX: sweepTranslateX }, { skewX: '-18deg' }] },
          ]}>
          <View style={styles.shimmerEdge} />
          <View style={styles.shimmerCore} />
          <View style={styles.shimmerEdge} />
        </Animated.View>
      </View>

      <View style={styles.skeletonFooter}>
        <View style={styles.skeletonTitleBar}>
          <Animated.View
            pointerEvents="none"
            style={[
              styles.shimmerSweepSmall,
              { transform: [{ translateX: sweepTranslateX }] },
            ]}
          />
        </View>
        <View style={styles.skeletonMetaRow}>
          <View style={styles.skeletonYearBar} />
          <View style={styles.skeletonTagBar} />
        </View>
      </View>
    </Animated.View>
  );
};

export const ShimmerCardGrid: React.FC<ShimmerCardGridProps> = ({
  columns = 2,
  count = 6,
  style,
}) => {
  const shimmerProgress = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const loop = Animated.loop(
      Animated.timing(shimmerProgress, {
        toValue: 1,
        duration: 1350,
        easing: Easing.inOut(Easing.ease),
        useNativeDriver: true,
      }),
    );
    loop.start();
    return () => loop.stop();
  }, [shimmerProgress]);

  return (
    <View style={[styles.shimmerGrid, style]} accessibilityLabel="Loading catalog">
      {Array.from({ length: count }).map((_, idx) => (
        <ShimmerSkeletonCard
          key={idx}
          index={idx}
          columns={columns}
          shimmerProgress={shimmerProgress}
        />
      ))}
    </View>
  );
};

// ---------------------------------------------------------------------------
// 1b. FadeInPoster — Smooth 250ms Poster Settle (Opacity 0->1, Scale 1.03->1)
// ---------------------------------------------------------------------------
export interface FadeInPosterProps extends ImageProps {
  durationMs?: number;
}

export const FadeInPoster: React.FC<FadeInPosterProps> = ({
  style,
  onLoad,
  source,
  durationMs = 250,
  ...rest
}) => {
  const opacity = useRef(new Animated.Value(0)).current;
  const scale = useRef(new Animated.Value(1.03)).current;
  const uriKey = typeof source === 'object' && source !== null && !Array.isArray(source)
    ? (source as { uri?: string }).uri || ''
    : String(source || '');

  useEffect(() => {
    opacity.setValue(0);
    scale.setValue(1.03);
  }, [uriKey, opacity, scale]);

  const handleLoad: ImageProps['onLoad'] = (e) => {
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 1,
        duration: durationMs,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.timing(scale, {
        toValue: 1,
        duration: durationMs,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
    ]).start();
    if (onLoad) onLoad(e);
  };

  return (
    <AnimatedImage
      {...rest}
      source={source}
      onLoad={handleLoad}
      style={[style, { opacity, transform: [{ scale }] }]}
    />
  );
};

// ---------------------------------------------------------------------------
// 2. CinemaArcLoader — Dual Counter-Rotating Arcs + 3-Bar Equalizer Pulse
// ---------------------------------------------------------------------------
interface CinemaArcLoaderProps {
  size?: 'small' | 'medium' | 'large';
  statusText?: string;
  messages?: string[];
  compact?: boolean;
  variant?: 'default' | 'onCrimson';
  style?: StyleProp<ViewStyle>;
}

export const CinemaArcLoader: React.FC<CinemaArcLoaderProps> = ({
  size = 'medium',
  statusText,
  messages,
  compact = false,
  variant = 'default',
  style,
}) => {
  const outerSpin = useRef(new Animated.Value(0)).current;
  const innerSpin = useRef(new Animated.Value(0)).current;
  const bar1 = useRef(new Animated.Value(0.35)).current;
  const bar2 = useRef(new Animated.Value(0.85)).current;
  const bar3 = useRef(new Animated.Value(0.5)).current;
  const textOpacity = useRef(new Animated.Value(1)).current;
  const [msgIdx, setMsgIdx] = React.useState(0);

  useEffect(() => {
    const outerLoop = Animated.loop(
      Animated.timing(outerSpin, {
        toValue: 1,
        duration: 1100,
        easing: Easing.linear,
        useNativeDriver: true,
      }),
    );
    const innerLoop = Animated.loop(
      Animated.timing(innerSpin, {
        toValue: 1,
        duration: 1550,
        easing: Easing.linear,
        useNativeDriver: true,
      }),
    );
    const pulseBar = (val: Animated.Value, delay: number) =>
      Animated.loop(
        Animated.sequence([
          Animated.timing(val, {
            toValue: 1,
            duration: 340,
            delay,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
          Animated.timing(val, {
            toValue: 0.3,
            duration: 340,
            easing: Easing.inOut(Easing.ease),
            useNativeDriver: true,
          }),
        ]),
      );

    const b1 = pulseBar(bar1, 0);
    const b2 = pulseBar(bar2, 110);
    const b3 = pulseBar(bar3, 220);

    outerLoop.start();
    innerLoop.start();
    b1.start();
    b2.start();
    b3.start();

    return () => {
      outerLoop.stop();
      innerLoop.stop();
      b1.stop();
      b2.stop();
      b3.stop();
    };
  }, [outerSpin, innerSpin, bar1, bar2, bar3]);

  useEffect(() => {
    if (!messages || messages.length <= 1) return;
    const timer = setInterval(() => {
      Animated.timing(textOpacity, {
        toValue: 0,
        duration: 160,
        useNativeDriver: true,
      }).start(() => {
        setMsgIdx(prev => (prev + 1) % messages.length);
        Animated.timing(textOpacity, {
          toValue: 1,
          duration: 200,
          useNativeDriver: true,
        }).start();
      });
    }, 2200);
    return () => clearInterval(timer);
  }, [messages, textOpacity]);

  const dim = size === 'small' ? 20 : size === 'large' ? 54 : 38;
  const innerDim = Math.round(dim * 0.72);
  const stroke = size === 'small' ? 2 : size === 'large' ? 3 : 2.5;
  const barWidth = size === 'small' ? 2 : 3;
  const barHeight = Math.round(dim * 0.38);

  const outerRotate = outerSpin.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '360deg'] });
  const innerRotate = innerSpin.interpolate({ inputRange: [0, 1], outputRange: ['360deg', '0deg'] });
  const activeText = messages && messages.length > 0 ? messages[msgIdx % messages.length] : statusText;
  const onCrimson = variant === 'onCrimson';
  const outerColor = onCrimson ? '#FFFFFF' : '#E50914';
  const outerTrackColor = onCrimson ? 'rgba(255,255,255,0.24)' : 'rgba(229,9,20,0.18)';
  const innerColor = '#F5C518';
  const innerTrackColor = onCrimson ? 'rgba(245,197,24,0.28)' : 'rgba(245,197,24,0.16)';
  const barPrimary = onCrimson ? '#FFFFFF' : '#E50914';
  const barCenter = onCrimson ? '#FFFFFF' : '#F5C518';

  return (
    <View style={[compact ? styles.arcCompactWrap : styles.arcCenterWrap, style]}>
      <View
        style={{
          width: dim,
          height: dim,
          borderRadius: dim / 2,
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: onCrimson ? 'rgba(11, 11, 15, 0.35)' : 'transparent',
        }}>
        <Animated.View
          style={{
            position: 'absolute',
            width: dim,
            height: dim,
            borderRadius: dim / 2,
            borderWidth: stroke,
            borderColor: outerTrackColor,
            borderTopColor: outerColor,
            borderRightColor: outerColor,
            transform: [{ rotate: outerRotate }],
          }}
        />
        <Animated.View
          style={{
            position: 'absolute',
            width: innerDim,
            height: innerDim,
            borderRadius: innerDim / 2,
            borderWidth: Math.max(1.5, stroke - 0.5),
            borderColor: innerTrackColor,
            borderBottomColor: innerColor,
            borderLeftColor: innerColor,
            transform: [{ rotate: innerRotate }],
          }}
        />
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: size === 'small' ? 1.5 : 2.5 }}>
          <Animated.View
            style={{
              width: barWidth,
              height: barHeight,
              borderRadius: barWidth,
              backgroundColor: barPrimary,
              transform: [{ scaleY: bar1 }],
            }}
          />
          <Animated.View
            style={{
              width: barWidth,
              height: barHeight,
              borderRadius: barWidth,
              backgroundColor: barCenter,
              transform: [{ scaleY: bar2 }],
            }}
          />
          <Animated.View
            style={{
              width: barWidth,
              height: barHeight,
              borderRadius: barWidth,
              backgroundColor: barPrimary,
              transform: [{ scaleY: bar3 }],
            }}
          />
        </View>
      </View>
      {!!activeText && (
        <Animated.Text style={[styles.arcStatusText, { opacity: textOpacity }]}>
          {activeText}
        </Animated.Text>
      )}
    </View>
  );
};

// ---------------------------------------------------------------------------
// 2b. DiscInspectLoader — High-Contrast Blu-ray / DVD Laser & Telemetry Loader
// ---------------------------------------------------------------------------
const BLURAY_TELEMETRY = [
  'Reading UDF 2.50 volume...',
  'Scanning BDMV playlists...',
  'Indexing CLPI seek map...',
  'Syncing lossless audio tracks...',
];

const DVD_TELEMETRY = [
  'Reading ISO9660 / UDF table...',
  'Parsing VIDEO_TS IFO...',
  'Indexing VOB cell map...',
  'Syncing audio & subtitles...',
];

export const DiscInspectLoader: React.FC<{
  discType: 'bluray' | 'dvd';
  headline?: string;
}> = ({ discType, headline }) => {
  const spinAnim = useRef(new Animated.Value(0)).current;
  const laserAnim = useRef(new Animated.Value(0)).current;
  const textFade = useRef(new Animated.Value(1)).current;
  const [stepIdx, setStepIdx] = React.useState(0);

  const steps = discType === 'dvd' ? DVD_TELEMETRY : BLURAY_TELEMETRY;
  const accentColor = discType === 'dvd' ? '#F5C518' : '#38BDF8';
  const laserTint = discType === 'dvd' ? 'rgba(245, 197, 24, 0.24)' : 'rgba(56, 189, 248, 0.24)';
  const defaultHeadline = discType === 'dvd' ? 'Inspecting DVD disc...' : 'Inspecting Blu-ray disc...';

  useEffect(() => {
    const spinLoop = Animated.loop(
      Animated.timing(spinAnim, {
        toValue: 1,
        duration: 1050,
        easing: Easing.linear,
        useNativeDriver: true,
      }),
    );
    const laserLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(laserAnim, {
          toValue: 1,
          duration: 1350,
          easing: Easing.inOut(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.delay(350),
        Animated.timing(laserAnim, {
          toValue: 0,
          duration: 0,
          useNativeDriver: true,
        }),
      ]),
    );
    spinLoop.start();
    laserLoop.start();

    const interval = setInterval(() => {
      Animated.timing(textFade, {
        toValue: 0,
        duration: 140,
        useNativeDriver: true,
      }).start(() => {
        setStepIdx(prev => (prev + 1) % steps.length);
        Animated.timing(textFade, {
          toValue: 1,
          duration: 190,
          useNativeDriver: true,
        }).start();
      });
    }, 850);

    return () => {
      spinLoop.stop();
      laserLoop.stop();
      clearInterval(interval);
    };
  }, [spinAnim, laserAnim, textFade, steps.length]);

  const spin = spinAnim.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '360deg'] });
  const laserX = laserAnim.interpolate({ inputRange: [0, 1], outputRange: [-120, 420] });

  return (
    <View style={styles.discInspectRow}>
      <Animated.View
        pointerEvents="none"
        style={[
          styles.discLaserSweep,
          { backgroundColor: laserTint, transform: [{ translateX: laserX }, { rotate: '18deg' }] },
        ]}
      />
      <Animated.View
        style={[
          styles.discRing,
          { borderColor: accentColor, transform: [{ rotate: spin }] },
        ]}>
        <View style={[styles.discNotch, { backgroundColor: '#FFFFFF' }]} />
        <View style={[styles.discInnerHub, { borderColor: accentColor }]} />
      </Animated.View>
      <View style={styles.discTextCol}>
        <Text style={styles.discHeadlineText} numberOfLines={1}>
          {headline || defaultHeadline}
        </Text>
        <Animated.Text
          numberOfLines={1}
          style={[styles.discTelemetryText, { color: accentColor, opacity: textFade }]}>
          {steps[stepIdx]}
        </Animated.Text>
      </View>
    </View>
  );
};

// ---------------------------------------------------------------------------
// 2c. CascadeSpecPill — 35ms Staggered Spring Pop-in for MediaInfo Pills
// ---------------------------------------------------------------------------
export const CascadeSpecPill: React.FC<{
  index?: number;
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}> = ({ index = 0, style, children }) => {
  const opacity = useRef(new Animated.Value(0)).current;
  const scale = useRef(new Animated.Value(0.86)).current;
  const translateY = useRef(new Animated.Value(6)).current;

  useEffect(() => {
    const delay = Math.min(index, 12) * 35;
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 1,
        duration: 210,
        delay,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.spring(scale, {
        toValue: 1,
        delay,
        speed: 24,
        bounciness: 6,
        useNativeDriver: true,
      }),
      Animated.timing(translateY, {
        toValue: 0,
        duration: 220,
        delay,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
    ]).start();
  }, [index, opacity, scale, translateY]);

  return (
    <Animated.View style={[style, { opacity, transform: [{ scale }, { translateY }] }]}>
      {children}
    </Animated.View>
  );
};

// ---------------------------------------------------------------------------
// 3. TopSyncProgressBar — Sleek 2.5px Crimson Progress Line Under Header
// ---------------------------------------------------------------------------
export const TopSyncProgressBar: React.FC<{ visible: boolean; style?: StyleProp<ViewStyle> }> = ({
  visible,
  style,
}) => {
  const slideAnim = useRef(new Animated.Value(0)).current;
  const opacityAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(opacityAnim, {
      toValue: visible ? 1 : 0,
      duration: 180,
      useNativeDriver: true,
    }).start();

    if (!visible) {
      slideAnim.stopAnimation();
      return;
    }

    slideAnim.setValue(0);
    const loop = Animated.loop(
      Animated.timing(slideAnim, {
        toValue: 1,
        duration: 1150,
        easing: Easing.inOut(Easing.cubic),
        useNativeDriver: true,
      }),
    );
    loop.start();
    return () => loop.stop();
  }, [visible, slideAnim, opacityAnim]);

  const translateX = slideAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [-160, 440],
  });

  return (
    <Animated.View
      pointerEvents="none"
      style={[styles.topProgressTrack, { opacity: opacityAnim }, style]}>
      <Animated.View
        style={[
          styles.topProgressBar,
          { transform: [{ translateX }] },
        ]}
      />
    </Animated.View>
  );
};

// ---------------------------------------------------------------------------
// 4. TactileSpringPressable — Spring Press (0.96 -> 1.0) + GPU Rim & Sheen
// ---------------------------------------------------------------------------
export interface TactileSpringPressableProps extends Omit<PressableProps, 'style'> {
  style?: StyleProp<ViewStyle> | ((state: { pressed: boolean }) => StyleProp<ViewStyle>);
  innerRef?: React.Ref<any>;
  activeScale?: number;
  glowColor?: string;
  glowBorderRadius?: number;
  highlighted?: boolean;
  enableSheen?: boolean;
  children?: React.ReactNode;
}

export const TactileSpringPressable: React.FC<TactileSpringPressableProps> = ({
  style,
  innerRef,
  activeScale = 0.96,
  glowColor = 'rgba(229, 9, 20, 0.72)',
  glowBorderRadius = 12,
  highlighted = false,
  enableSheen = false,
  onPressIn,
  onPressOut,
  children,
  ...rest
}) => {
  const scale = useRef(new Animated.Value(1)).current;
  const glowOpacity = useRef(new Animated.Value(highlighted ? 1 : 0)).current;
  const rimOpacity = useRef(new Animated.Value(0)).current;
  const sheenProgress = useRef(new Animated.Value(0)).current;
  const sheenOpacity = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(glowOpacity, {
      toValue: highlighted ? 1 : 0,
      duration: 140,
      useNativeDriver: true,
    }).start();
  }, [highlighted, glowOpacity]);

  const handlePressIn: PressableProps['onPressIn'] = (e) => {
    const anims: Animated.CompositeAnimation[] = [
      Animated.spring(scale, {
        toValue: activeScale,
        speed: 36,
        bounciness: 5,
        useNativeDriver: true,
      }),
      Animated.timing(glowOpacity, {
        toValue: 1,
        duration: 90,
        useNativeDriver: true,
      }),
    ];

    if (enableSheen) {
      sheenProgress.setValue(0);
      sheenOpacity.setValue(0.85);
      anims.push(
        Animated.timing(rimOpacity, {
          toValue: 1,
          duration: 90,
          useNativeDriver: true,
        }),
        Animated.timing(sheenProgress, {
          toValue: 1,
          duration: 360,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.timing(sheenOpacity, {
          toValue: 0,
          duration: 360,
          easing: Easing.in(Easing.quad),
          useNativeDriver: true,
        }),
      );
    }

    Animated.parallel(anims).start();
    if (onPressIn) onPressIn(e);
  };

  const handlePressOut: PressableProps['onPressOut'] = (e) => {
    const anims: Animated.CompositeAnimation[] = [
      Animated.spring(scale, {
        toValue: 1,
        speed: 28,
        bounciness: 7,
        useNativeDriver: true,
      }),
      Animated.timing(glowOpacity, {
        toValue: highlighted ? 1 : 0,
        duration: 180,
        useNativeDriver: true,
      }),
    ];
    if (enableSheen) {
      anims.push(
        Animated.timing(rimOpacity, {
          toValue: 0,
          duration: 200,
          useNativeDriver: true,
        }),
      );
    }
    Animated.parallel(anims).start();
    if (onPressOut) onPressOut(e);
  };

  const resolvedStyle = typeof style === 'function' ? style({ pressed: false }) : style;
  const sheenX = sheenProgress.interpolate({
    inputRange: [0, 1],
    outputRange: [-140, 260],
  });

  return (
    <AnimatedPressable
      ref={innerRef}
      onPressIn={handlePressIn}
      onPressOut={handlePressOut}
      style={[resolvedStyle, { transform: [{ scale }] }]}
      {...rest}>
      {children}
      {enableSheen && (
        <View
          pointerEvents="none"
          style={[StyleSheet.absoluteFill, { borderRadius: glowBorderRadius, overflow: 'hidden' }]}>
          <Animated.View
            style={[
              styles.touchSheenStreak,
              {
                opacity: sheenOpacity,
                transform: [{ translateX: sheenX }, { rotate: '22deg' }],
              },
            ]}
          />
          <Animated.View
            style={[
              StyleSheet.absoluteFill,
              {
                borderRadius: glowBorderRadius,
                borderWidth: 1.5,
                borderColor: 'rgba(255, 255, 255, 0.55)',
                opacity: rimOpacity,
              },
            ]}
          />
        </View>
      )}
      <Animated.View
        pointerEvents="none"
        style={[
          StyleSheet.absoluteFill,
          {
            borderRadius: glowBorderRadius,
            borderWidth: 1.5,
            borderColor: glowColor,
            opacity: glowOpacity,
          },
        ]}
      />
    </AnimatedPressable>
  );
};

// ---------------------------------------------------------------------------
// 5a. StaggeredSection — Fade + Slide Up (translateY: 14->0, 240ms)
// ---------------------------------------------------------------------------
export const StaggeredSection: React.FC<{
  delayMs?: number;
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}> = ({ delayMs = 0, style, children }) => {
  const opacity = useRef(new Animated.Value(0)).current;
  const translateY = useRef(new Animated.Value(14)).current;

  useEffect(() => {
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 1,
        duration: 240,
        delay: delayMs,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.timing(translateY, {
        toValue: 0,
        duration: 240,
        delay: delayMs,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
    ]).start();
  }, [delayMs, opacity, translateY]);

  return (
    <Animated.View style={[style, { opacity, transform: [{ translateY }] }]}>
      {children}
    </Animated.View>
  );
};

// ---------------------------------------------------------------------------
// 5b. SlideUpSheet — Smooth Modal / Sheet Slide-Up Entrance
// ---------------------------------------------------------------------------
export const SlideUpSheet: React.FC<{
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}> = ({ style, children }) => {
  const opacity = useRef(new Animated.Value(0)).current;
  const translateY = useRef(new Animated.Value(32)).current;

  useEffect(() => {
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 1,
        duration: 250,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.spring(translateY, {
        toValue: 0,
        speed: 22,
        bounciness: 4,
        useNativeDriver: true,
      }),
    ]).start();
  }, [opacity, translateY]);

  return (
    <Animated.View style={[style, { opacity, transform: [{ translateY }] }]}>
      {children}
    </Animated.View>
  );
};

// ---------------------------------------------------------------------------
// 6. TierRimGlowCard — Tier-Aware Breathing Edge Glow (4K / 1080p / SD-720p)
// ---------------------------------------------------------------------------
export type CardGlowTier = 'multi' | '4k' | '1080p' | 'sd' | 'none';

const TIER_GLOW_CONFIG: Record<
  Exclude<CardGlowTier, 'none'>,
  { primary: string; secondary?: string; minOpacity: number; maxOpacity: number; halfDurationMs: number }
> = {
  multi: {
    primary: '#38BDF8',
    secondary: '#E50914',
    minOpacity: 0.24,
    maxOpacity: 0.78,
    halfDurationMs: 1350,
  },
  '4k': {
    primary: '#F5C518',
    secondary: '#E50914',
    minOpacity: 0.2,
    maxOpacity: 0.75,
    halfDurationMs: 1400,
  },
  '1080p': {
    primary: '#38BDF8',
    secondary: '#0284C7',
    minOpacity: 0.16,
    maxOpacity: 0.52,
    halfDurationMs: 1600,
  },
  sd: {
    primary: '#F59E0B',
    minOpacity: 0.14,
    maxOpacity: 0.44,
    halfDurationMs: 1800,
  },
};

export const TierRimGlowCard: React.FC<{
  tier?: CardGlowTier;
  isDv?: boolean;
  borderRadius?: number;
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}> = ({ tier = 'none', isDv = false, borderRadius = 12, style, children }) => {
  const pulseAnim = useRef(new Animated.Value(0)).current;
  const active = tier !== 'none';
  const cfg = tier !== 'none' ? TIER_GLOW_CONFIG[tier] : null;

  useEffect(() => {
    if (!active || !cfg) {
      pulseAnim.stopAnimation();
      return;
    }
    pulseAnim.setValue(0);
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 1,
          duration: cfg.halfDurationMs,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
        Animated.timing(pulseAnim, {
          toValue: 0,
          duration: cfg.halfDurationMs,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [active, cfg, pulseAnim]);

  const primaryOpacity = pulseAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [cfg?.minOpacity ?? 0.2, cfg?.maxOpacity ?? 0.75],
  });
  const secondaryOpacity = pulseAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [Math.min(0.55, (cfg?.maxOpacity ?? 0.55) * 0.8), cfg?.minOpacity ?? 0.16],
  });

  const primaryColor = tier === '4k' && isDv ? '#C084FC' : cfg?.primary;

  return (
    <View style={style}>
      {children}
      {active && cfg && (
        <>
          <Animated.View
            pointerEvents="none"
            style={[
              StyleSheet.absoluteFill,
              {
                borderRadius,
                borderWidth: 1.5,
                borderColor: primaryColor,
                opacity: primaryOpacity,
              },
            ]}
          />
          {!!cfg.secondary && (
            <Animated.View
              pointerEvents="none"
              style={[
                StyleSheet.absoluteFill,
                {
                  borderRadius,
                  borderWidth: 1,
                  borderColor: cfg.secondary,
                  opacity: secondaryOpacity,
                },
              ]}
            />
          )}
        </>
      )}
    </View>
  );
};

export const Ambient4KGlowCard: React.FC<{
  active?: boolean;
  isDv?: boolean;
  borderRadius?: number;
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}> = ({ active = false, isDv = false, borderRadius = 12, style, children }) => (
  <TierRimGlowCard tier={active ? '4k' : 'none'} isDv={isDv} borderRadius={borderRadius} style={style}>
    {children}
  </TierRimGlowCard>
);

// ---------------------------------------------------------------------------
// 7. GlintBadge — Periodic Metallic / Gold Glint Sweep Every 4.2s
// ---------------------------------------------------------------------------
export const GlintBadge: React.FC<{
  active?: boolean;
  pulse?: boolean;
  tintColor?: string;
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}> = ({ active = true, pulse = false, tintColor, style, children }) => {
  const glintProgress = useRef(new Animated.Value(0)).current;
  const pulseAnim = useRef(new Animated.Value(1)).current;

  useEffect(() => {
    if (!active) {
      glintProgress.stopAnimation();
      return;
    }
    glintProgress.setValue(0);
    const loop = Animated.loop(
      Animated.sequence([
        Animated.delay(3450),
        Animated.timing(glintProgress, {
          toValue: 1,
          duration: 750,
          easing: Easing.inOut(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.timing(glintProgress, {
          toValue: 0,
          duration: 0,
          useNativeDriver: true,
        }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [active, glintProgress]);

  useEffect(() => {
    if (!pulse) {
      pulseAnim.setValue(1);
      return;
    }
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 1.05,
          duration: 1100,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
        Animated.timing(pulseAnim, {
          toValue: 1,
          duration: 1100,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [pulse, pulseAnim]);

  const glintX = glintProgress.interpolate({
    inputRange: [0, 1],
    outputRange: [-55, 115],
  });

  return (
    <Animated.View style={[style, { overflow: 'hidden', transform: [{ scale: pulseAnim }] }]}>
      {children}
      {active && (
        <Animated.View
          pointerEvents="none"
          style={[
            styles.badgeGlintStreak,
            tintColor ? { backgroundColor: tintColor } : null,
            { transform: [{ translateX: glintX }, { rotate: '20deg' }] },
          ]}
        />
      )}
    </Animated.View>
  );
};

// ---------------------------------------------------------------------------
// 8. WaveCascadeCard — 35ms Diagonal Stagger on Filter / Folder Transitions
// ---------------------------------------------------------------------------
export const WaveCascadeCard: React.FC<{
  index: number;
  transitionKey: string | number;
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}> = ({ index, transitionKey, style, children }) => {
  const opacity = useRef(new Animated.Value(0)).current;
  const scale = useRef(new Animated.Value(0.94)).current;
  const translateY = useRef(new Animated.Value(10)).current;

  useEffect(() => {
    opacity.setValue(0);
    scale.setValue(0.94);
    translateY.setValue(10);
    const delay = Math.min(index, 14) * 35;

    Animated.parallel([
      Animated.timing(opacity, {
        toValue: 1,
        duration: 240,
        delay,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.timing(scale, {
        toValue: 1,
        duration: 260,
        delay,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.timing(translateY, {
        toValue: 0,
        duration: 260,
        delay,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
    ]).start();
  }, [transitionKey, index, opacity, scale, translateY]);

  return (
    <Animated.View style={[style, { opacity, transform: [{ scale }, { translateY }] }]}>
      {children}
    </Animated.View>
  );
};

// ---------------------------------------------------------------------------
// 9. KenBurnsBackdrop — Ultra-Smooth 14s Looping Cinema Drift
// ---------------------------------------------------------------------------
export const KenBurnsBackdrop: React.FC<FadeInPosterProps> = ({
  style,
  durationMs = 280,
  onLoad,
  ...rest
}) => {
  const fadeOpacity = useRef(new Animated.Value(0)).current;
  const driftAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    driftAnim.setValue(0);
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(driftAnim, {
          toValue: 1,
          duration: 7000,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
        Animated.timing(driftAnim, {
          toValue: 0,
          duration: 7000,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [driftAnim]);

  const scale = driftAnim.interpolate({ inputRange: [0, 1], outputRange: [1.0, 1.07] });
  const translateX = driftAnim.interpolate({ inputRange: [0, 1], outputRange: [-6, 6] });

  const handleLoad: ImageProps['onLoad'] = (e) => {
    Animated.timing(fadeOpacity, {
      toValue: 1,
      duration: durationMs,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
    if (onLoad) onLoad(e);
  };

  return (
    <AnimatedImage
      {...rest}
      onLoad={handleLoad}
      style={[
        style,
        { opacity: fadeOpacity, transform: [{ scale }, { translateX }] },
      ]}
    />
  );
};

// ---------------------------------------------------------------------------
// 10. SeekBurstOverlay — Double-Tap ±10s Expanding Ripple & Chevron Burst
// ---------------------------------------------------------------------------
export const SeekBurstOverlay: React.FC<{
  visible: boolean;
  position: string;
  time: number;
  burstKey?: number;
}> = ({ visible, position, time, burstKey = 0 }) => {
  const rippleScale = useRef(new Animated.Value(0.4)).current;
  const rippleOpacity = useRef(new Animated.Value(0)).current;
  const badgeOpacity = useRef(new Animated.Value(0)).current;
  const chevronShift = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (!visible || time <= 0) {
      badgeOpacity.setValue(0);
      return;
    }
    const isRight = position === 'right';
    rippleScale.setValue(0.4);
    rippleOpacity.setValue(0.85);
    badgeOpacity.setValue(1);
    chevronShift.setValue(0);

    Animated.parallel([
      Animated.timing(rippleScale, {
        toValue: 1.35,
        duration: 540,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.timing(rippleOpacity, {
        toValue: 0,
        duration: 540,
        easing: Easing.out(Easing.quad),
        useNativeDriver: true,
      }),
      Animated.timing(chevronShift, {
        toValue: isRight ? 10 : -10,
        duration: 420,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.sequence([
        Animated.delay(240),
        Animated.timing(badgeOpacity, {
          toValue: 0,
          duration: 520,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }),
      ]),
    ]).start();
  }, [visible, position, time, burstKey, rippleScale, rippleOpacity, badgeOpacity, chevronShift]);

  if (!visible && time <= 0) return null;
  const isRight = position === 'right';

  return (
    <Animated.View
      pointerEvents="none"
      style={[
        styles.seekBurstContainer,
        { alignItems: isRight ? 'flex-end' : 'flex-start', opacity: badgeOpacity },
      ]}>
      <View style={styles.seekBurstAnchor}>
        <Animated.View
          style={[
            styles.seekBurstRipple,
            { opacity: rippleOpacity, transform: [{ scale: rippleScale }] },
          ]}
        />
        <Animated.View
          style={[
            styles.seekBurstPill,
            { transform: [{ translateX: chevronShift }] },
          ]}>
          <Text style={styles.seekBurstChevron}>
            {isRight ? `${time}s »` : `« ${time}s`}
          </Text>
        </Animated.View>
      </View>
    </Animated.View>
  );
};

// ---------------------------------------------------------------------------
// 11. DownloadPulseBadge & ProgressGlowTip
// ---------------------------------------------------------------------------
export const DownloadPulseBadge: React.FC<{
  active: boolean;
  children: React.ReactNode;
}> = ({ active, children }) => {
  const pulseAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (!active) {
      pulseAnim.setValue(0);
      return;
    }
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 1,
          duration: 750,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
        Animated.timing(pulseAnim, {
          toValue: 0,
          duration: 750,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [active, pulseAnim]);

  const scale = pulseAnim.interpolate({ inputRange: [0, 1], outputRange: [1.0, 1.14] });
  const opacity = pulseAnim.interpolate({ inputRange: [0, 1], outputRange: [0.85, 1.0] });

  return (
    <Animated.View style={{ transform: [{ scale }], opacity }}>
      {children}
    </Animated.View>
  );
};

export const ProgressGlowTip: React.FC = () => {
  const pulseAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, {
          toValue: 1,
          duration: 900,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
        Animated.timing(pulseAnim, {
          toValue: 0,
          duration: 900,
          easing: Easing.inOut(Easing.sin),
          useNativeDriver: true,
        }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [pulseAnim]);

  const scale = pulseAnim.interpolate({ inputRange: [0, 1], outputRange: [0.85, 1.3] });
  const opacity = pulseAnim.interpolate({ inputRange: [0, 1], outputRange: [0.55, 1] });

  return (
    <Animated.View
      pointerEvents="none"
      style={[styles.progressGlowTip, { opacity, transform: [{ scale }] }]}
    />
  );
};

// ---------------------------------------------------------------------------
// 12. Infuse Pro MediaInfo Telemetry Components
// ---------------------------------------------------------------------------
export const BitrateMeterBar: React.FC<{
  bitrateMbps?: number;
  maxMbps?: number;
  color?: string;
}> = ({ bitrateMbps = 0, maxMbps = 85, color = '#38BDF8' }) => {
  const fillAnim = useRef(new Animated.Value(0)).current;
  const [trackWidth, setTrackWidth] = React.useState(130);

  const ratio = Math.max(0.08, Math.min(1, bitrateMbps > 0 ? bitrateMbps / maxMbps : 0.22));

  useEffect(() => {
    fillAnim.setValue(0);
    Animated.timing(fillAnim, {
      toValue: 1,
      duration: 620,
      delay: 140,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [bitrateMbps, ratio, fillAnim]);

  const translateX = fillAnim.interpolate({
    inputRange: [0, 1],
    outputRange: [-trackWidth, -trackWidth * (1 - ratio)],
  });

  return (
    <View
      onLayout={e => {
        const w = e.nativeEvent.layout.width;
        if (w > 0 && Math.abs(w - trackWidth) > 2) setTrackWidth(w);
      }}
      style={styles.bitrateTrack}>
      <Animated.View
        style={[
          styles.bitrateFill,
          { width: trackWidth, backgroundColor: color, transform: [{ translateX }] },
        ]}
      />
    </View>
  );
};

export const MiniEqualizerPulse: React.FC<{
  active?: boolean;
  color?: string;
}> = ({ active = true, color = '#F5C518' }) => {
  const b1 = useRef(new Animated.Value(0.45)).current;
  const b2 = useRef(new Animated.Value(0.95)).current;
  const b3 = useRef(new Animated.Value(0.6)).current;

  useEffect(() => {
    if (!active) {
      b1.setValue(0.4);
      b2.setValue(0.6);
      b3.setValue(0.5);
      return;
    }
    const pulse = (val: Animated.Value, lo: number, hi: number, dur: number) =>
      Animated.loop(
        Animated.sequence([
          Animated.timing(val, { toValue: hi, duration: dur, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
          Animated.timing(val, { toValue: lo, duration: dur, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
        ]),
      );
    const l1 = pulse(b1, 0.35, 1.0, 340);
    const l2 = pulse(b2, 0.4, 1.0, 280);
    const l3 = pulse(b3, 0.3, 0.95, 390);
    l1.start();
    l2.start();
    l3.start();
    return () => {
      l1.stop();
      l2.stop();
      l3.stop();
    };
  }, [active, b1, b2, b3]);

  return (
    <View style={styles.miniEqRow}>
      {[b1, b2, b3].map((animVal, idx) => (
        <Animated.View
          key={idx}
          style={[styles.miniEqBar, { backgroundColor: color, transform: [{ scaleY: animVal }] }]}
        />
      ))}
    </View>
  );
};

export const CopySpecPill: React.FC<{
  label?: string;
  text: string;
  onCopy: (text: string) => void;
  style?: StyleProp<ViewStyle>;
  renderIcon?: (copied: boolean) => React.ReactNode;
}> = ({ label = 'SOURCE FILE', text, onCopy, style, renderIcon }) => {
  const [copied, setCopied] = React.useState(false);
  const morphScale = useRef(new Animated.Value(1)).current;
  const timerRef = useRef<any>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  const handleCopy = () => {
    onCopy(text);
    setCopied(true);
    morphScale.setValue(0.84);
    Animated.spring(morphScale, {
      toValue: 1,
      speed: 28,
      bounciness: 10,
      useNativeDriver: true,
    }).start();
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setCopied(false), 1800);
  };

  return (
    <View style={[styles.copySpecContainer, style]}>
      <View style={styles.copySpecHeader}>
        <Text style={styles.copySpecLabel}>{label}</Text>
        <TactileSpringPressable
          onPress={handleCopy}
          glowColor={copied ? '#46D369' : '#64748B'}
          glowBorderRadius={8}
          style={[styles.copyChipBtn, copied && styles.copyChipBtnDone]}>
          <Animated.View style={[styles.copyChipInner, { transform: [{ scale: morphScale }] }]}>
            {renderIcon ? renderIcon(copied) : null}
            <Text style={[styles.copyChipText, copied && styles.copyChipTextDone]}>
              {copied ? 'Copied ✓' : 'Copy'}
            </Text>
          </Animated.View>
        </TactileSpringPressable>
      </View>
      <Text style={styles.copySpecMono} selectable={true}>
        {text}
      </Text>
    </View>
  );
};

// ---------------------------------------------------------------------------
// 13. Details Action Buttons — 60fps Signature Micro-Animations
// ---------------------------------------------------------------------------
export const PlayHeroButton: React.FC<
  TactileSpringPressableProps & {
    inspecting?: boolean;
    pulseTrigger?: string | number;
    renderContent?: (nudgeX: Animated.Value) => React.ReactNode;
  }
> = ({ inspecting = false, pulseTrigger, renderContent, children, style, onPressIn, onPressOut, ...rest }) => {
  const auraAnim = useRef(new Animated.Value(0)).current;
  const sweepAnim = useRef(new Animated.Value(0)).current;
  const nudgeX = useRef(new Animated.Value(0)).current;
  const switchScale = useRef(new Animated.Value(1)).current;
  const prevTriggerRef = useRef(pulseTrigger);

  useEffect(() => {
    if (pulseTrigger !== undefined && prevTriggerRef.current !== pulseTrigger) {
      prevTriggerRef.current = pulseTrigger;
      Animated.sequence([
        Animated.spring(switchScale, { toValue: 1.035, speed: 34, bounciness: 6, useNativeDriver: true }),
        Animated.spring(switchScale, { toValue: 1, speed: 24, bounciness: 8, useNativeDriver: true }),
      ]).start();
    }
  }, [pulseTrigger, switchScale]);

  useEffect(() => {
    if (inspecting) {
      auraAnim.stopAnimation();
      sweepAnim.stopAnimation();
      return;
    }
    auraAnim.setValue(0);
    sweepAnim.setValue(0);
    const auraLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(auraAnim, { toValue: 1, duration: 1500, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
        Animated.timing(auraAnim, { toValue: 0, duration: 1500, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
      ]),
    );
    const sweepLoop = Animated.loop(
      Animated.sequence([
        Animated.delay(2850),
        Animated.timing(sweepAnim, { toValue: 1, duration: 750, easing: Easing.inOut(Easing.cubic), useNativeDriver: true }),
        Animated.timing(sweepAnim, { toValue: 0, duration: 0, useNativeDriver: true }),
      ]),
    );
    auraLoop.start();
    sweepLoop.start();
    return () => {
      auraLoop.stop();
      sweepLoop.stop();
    };
  }, [inspecting, auraAnim, sweepAnim]);

  const auraOpacity = auraAnim.interpolate({ inputRange: [0, 1], outputRange: [0.25, 0.65] });
  const sweepX = sweepAnim.interpolate({ inputRange: [0, 1], outputRange: [-130, 420] });

  const handleIn: PressableProps['onPressIn'] = (e) => {
    Animated.spring(nudgeX, { toValue: 3, speed: 34, bounciness: 6, useNativeDriver: true }).start();
    if (onPressIn) onPressIn(e);
  };
  const handleOut: PressableProps['onPressOut'] = (e) => {
    Animated.spring(nudgeX, { toValue: 0, speed: 26, bounciness: 8, useNativeDriver: true }).start();
    if (onPressOut) onPressOut(e);
  };

  const resolvedStyle = typeof style === 'function' ? style({ pressed: false }) : style;

  return (
    <Animated.View style={[styles.playHeroAuraWrap, { transform: [{ scale: switchScale }] }]}>
      {!inspecting && (
        <Animated.View
          pointerEvents="none"
          style={[styles.playHeroOuterAura, { opacity: auraOpacity }]}
        />
      )}
      <TactileSpringPressable
        {...rest}
        onPressIn={handleIn}
        onPressOut={handleOut}
        glowBorderRadius={10}
        style={[resolvedStyle, { overflow: 'hidden' }]}>
        {!inspecting && (
          <Animated.View
            pointerEvents="none"
            style={[
              styles.heroSweepStreak,
              { transform: [{ translateX: sweepX }, { rotate: '20deg' }] },
            ]}
          />
        )}
        {renderContent ? renderContent(nudgeX) : children}
      </TactileSpringPressable>
    </Animated.View>
  );
};

export const ExternalLaunchButton: React.FC<
  TactileSpringPressableProps & {
    renderContent: (nudgeX: Animated.Value, nudgeY: Animated.Value) => React.ReactNode;
  }
> = ({ renderContent, onPressIn, onPressOut, glowColor = '#38BDF8', ...rest }) => {
  const nudgeX = useRef(new Animated.Value(0)).current;
  const nudgeY = useRef(new Animated.Value(0)).current;

  const handleIn: PressableProps['onPressIn'] = (e) => {
    Animated.parallel([
      Animated.spring(nudgeX, { toValue: 3, speed: 34, bounciness: 6, useNativeDriver: true }),
      Animated.spring(nudgeY, { toValue: -3, speed: 34, bounciness: 6, useNativeDriver: true }),
    ]).start();
    if (onPressIn) onPressIn(e);
  };
  const handleOut: PressableProps['onPressOut'] = (e) => {
    Animated.parallel([
      Animated.spring(nudgeX, { toValue: 0, speed: 26, bounciness: 8, useNativeDriver: true }),
      Animated.spring(nudgeY, { toValue: 0, speed: 26, bounciness: 8, useNativeDriver: true }),
    ]).start();
    if (onPressOut) onPressOut(e);
  };

  return (
    <TactileSpringPressable
      {...rest}
      glowColor={glowColor}
      glowBorderRadius={10}
      onPressIn={handleIn}
      onPressOut={handleOut}>
      {renderContent(nudgeX, nudgeY)}
    </TactileSpringPressable>
  );
};

export const MyListHeartButton: React.FC<
  TactileSpringPressableProps & {
    saved: boolean;
    renderContent: (
      heartScale: Animated.Value,
      rippleScale: Animated.Value,
      rippleOpacity: Animated.Value,
    ) => React.ReactNode;
  }
> = ({ saved, renderContent, ...rest }) => {
  const heartScale = useRef(new Animated.Value(1)).current;
  const rippleScale = useRef(new Animated.Value(0.5)).current;
  const rippleOpacity = useRef(new Animated.Value(0)).current;
  const mountedRef = useRef(false);

  useEffect(() => {
    if (!mountedRef.current) {
      mountedRef.current = true;
      return;
    }
    rippleScale.setValue(0.5);
    rippleOpacity.setValue(0.8);
    Animated.parallel([
      Animated.sequence([
        Animated.spring(heartScale, { toValue: 1.38, speed: 40, bounciness: 10, useNativeDriver: true }),
        Animated.spring(heartScale, { toValue: 1, speed: 24, bounciness: 8, useNativeDriver: true }),
      ]),
      Animated.timing(rippleScale, { toValue: 1.6, duration: 380, easing: Easing.out(Easing.cubic), useNativeDriver: true }),
      Animated.timing(rippleOpacity, { toValue: 0, duration: 380, easing: Easing.out(Easing.quad), useNativeDriver: true }),
    ]).start();
  }, [saved, heartScale, rippleScale, rippleOpacity]);

  return (
    <TactileSpringPressable
      {...rest}
      glowColor={saved ? '#E50914' : 'rgba(229, 9, 20, 0.72)'}
      glowBorderRadius={10}>
      {renderContent(heartScale, rippleScale, rippleOpacity)}
    </TactileSpringPressable>
  );
};

export const DownloadActionButton: React.FC<
  TactileSpringPressableProps & {
    progress?: number | null;
    isActiveDownload?: boolean;
    pulseTrigger?: string | number;
    renderContent: (arrowNudgeY: Animated.Value) => React.ReactNode;
  }
> = ({ progress = null, isActiveDownload = false, pulseTrigger, renderContent, style, onPressIn, onPressOut, ...rest }) => {
  const arrowNudgeY = useRef(new Animated.Value(0)).current;
  const switchScale = useRef(new Animated.Value(1)).current;
  const prevTriggerRef = useRef(pulseTrigger);
  const [btnWidth, setBtnWidth] = React.useState(260);
  const clampedPct = progress !== null ? Math.max(0.02, Math.min(1, progress / 100)) : 0;

  useEffect(() => {
    if (pulseTrigger !== undefined && prevTriggerRef.current !== pulseTrigger) {
      prevTriggerRef.current = pulseTrigger;
      Animated.sequence([
        Animated.spring(switchScale, { toValue: 1.035, speed: 34, bounciness: 6, useNativeDriver: true }),
        Animated.spring(switchScale, { toValue: 1, speed: 24, bounciness: 8, useNativeDriver: true }),
      ]).start();
    }
  }, [pulseTrigger, switchScale]);

  const handleIn: PressableProps['onPressIn'] = (e) => {
    Animated.spring(arrowNudgeY, { toValue: 3, speed: 34, bounciness: 6, useNativeDriver: true }).start();
    if (onPressIn) onPressIn(e);
  };
  const handleOut: PressableProps['onPressOut'] = (e) => {
    Animated.spring(arrowNudgeY, { toValue: 0, speed: 26, bounciness: 8, useNativeDriver: true }).start();
    if (onPressOut) onPressOut(e);
  };

  return (
    <Animated.View
      style={{ transform: [{ scale: switchScale }] }}
      onLayout={e => {
        const w = e.nativeEvent.layout.width;
        if (w > 0 && Math.abs(w - btnWidth) > 2) setBtnWidth(w);
      }}>
      <TactileSpringPressable
        {...rest}
        onPressIn={handleIn}
        onPressOut={handleOut}
        glowColor="#46D369"
        glowBorderRadius={10}
        style={[typeof style === 'function' ? style({ pressed: false }) : style, { overflow: 'hidden' }]}>
        {isActiveDownload && (
          <View
            pointerEvents="none"
            style={[
              styles.inlineDlProgressTrack,
              { width: btnWidth, transform: [{ translateX: -btnWidth * (1 - clampedPct) }] },
            ]}
          />
        )}
        {renderContent(arrowNudgeY)}
      </TactileSpringPressable>
    </Animated.View>
  );
};

export const TrailerGlowButton: React.FC<
  TactileSpringPressableProps & {
    available: boolean;
  }
> = ({ available, children, style, ...rest }) => {
  const pulseAnim = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    if (!available) {
      pulseAnim.stopAnimation();
      return;
    }
    pulseAnim.setValue(0);
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulseAnim, { toValue: 1, duration: 1500, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
        Animated.timing(pulseAnim, { toValue: 0, duration: 1500, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [available, pulseAnim]);

  const violetOpacity = pulseAnim.interpolate({ inputRange: [0, 1], outputRange: [0.25, 0.8] });
  const pinkOpacity = pulseAnim.interpolate({ inputRange: [0, 1], outputRange: [0.65, 0.2] });

  return (
    <View style={styles.trailerGlowWrap}>
      {available && (
        <>
          <Animated.View pointerEvents="none" style={[styles.trailerVioletRim, { opacity: violetOpacity }]} />
          <Animated.View pointerEvents="none" style={[styles.trailerPinkRim, { opacity: pinkOpacity }]} />
        </>
      )}
      <TactileSpringPressable
        {...rest}
        glowColor={available ? '#EC4899' : '#3F3F46'}
        glowBorderRadius={10}
        style={style}>
        {children}
      </TactileSpringPressable>
    </View>
  );
};

// ---------------------------------------------------------------------------
// 14. SeriesCycleBadge — Synchronized Season / Rating Pill Flip (Never Blank)
// ---------------------------------------------------------------------------
export const SeriesCycleBadge: React.FC<{
  seasonText: string;
  rating?: number | null;
}> = ({ seasonText, rating }) => {
  const phase = useRef(new Animated.Value(0)).current;
  const hasValidRating = typeof rating === 'number' && Number.isFinite(rating) && rating > 0.1;
  const safeSeason = seasonText || 'S01';
  const ratingText = hasValidRating ? `★ ${rating!.toFixed(1)}` : '';

  useEffect(() => {
    if (!hasValidRating || !safeSeason) {
      phase.setValue(0);
      return;
    }
    phase.setValue(0);
    const loop = Animated.loop(
      Animated.sequence([
        Animated.delay(2400),
        Animated.timing(phase, {
          toValue: 1,
          duration: 380,
          easing: Easing.inOut(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.delay(2400),
        Animated.timing(phase, {
          toValue: 0,
          duration: 380,
          easing: Easing.inOut(Easing.cubic),
          useNativeDriver: true,
        }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [hasValidRating, safeSeason, phase]);

  if (!hasValidRating) {
    return (
      <View style={styles.seriesSeasonPill}>
        <Text style={styles.seriesSeasonPillText} numberOfLines={1} ellipsizeMode="clip">
          {safeSeason}
        </Text>
      </View>
    );
  }

  const seasonOpacity = phase.interpolate({ inputRange: [0, 0.55, 1], outputRange: [1, 0, 0] });
  const seasonY = phase.interpolate({ inputRange: [0, 1], outputRange: [0, -10] });
  const ratingOpacity = phase.interpolate({ inputRange: [0, 0.45, 1], outputRange: [0, 0, 1] });
  const ratingY = phase.interpolate({ inputRange: [0, 1], outputRange: [10, 0] });
  const sizingLabel = safeSeason.length >= ratingText.length ? safeSeason : ratingText;

  return (
    <GlintBadge
      active={hasValidRating && rating! >= 7.5}
      tintColor="rgba(253, 230, 138, 0.52)"
      style={styles.seriesCycleWrap}>
      <View style={[styles.seriesSeasonPill, { backgroundColor: 'transparent', minWidth: 52 }]}>
        <Text style={[styles.seriesSeasonPillText, { opacity: 0 }]} numberOfLines={1} ellipsizeMode="clip">
          {sizingLabel}
        </Text>
      </View>
      <Animated.View
        pointerEvents="none"
        style={[
          StyleSheet.absoluteFill,
          styles.seriesSeasonPill,
          { opacity: seasonOpacity, transform: [{ translateY: seasonY }] },
        ]}>
        <Text style={styles.seriesSeasonPillText} numberOfLines={1} ellipsizeMode="clip">
          {safeSeason}
        </Text>
      </Animated.View>
      <Animated.View
        pointerEvents="none"
        style={[
          StyleSheet.absoluteFill,
          styles.seriesRatingPill,
          { opacity: ratingOpacity, transform: [{ translateY: ratingY }] },
        ]}>
        <Text style={styles.seriesRatingPillText} numberOfLines={1} ellipsizeMode="clip">
          {ratingText}
        </Text>
      </Animated.View>
    </GlintBadge>
  );
};

// ---------------------------------------------------------------------------
// 15. VersionOptionCard — Tactile Version Switcher with Radio Pop & Sheen
// ---------------------------------------------------------------------------
export const VersionOptionCard: React.FC<
  TactileSpringPressableProps & {
    active: boolean;
    renderRadioIcon: (radioScale: Animated.Value) => React.ReactNode;
    children: React.ReactNode;
  }
> = ({ active, renderRadioIcon, children, style, ...rest }) => {
  const radioScale = useRef(new Animated.Value(1)).current;
  const activeBorderOpacity = useRef(new Animated.Value(active ? 1 : 0)).current;
  const sweepAnim = useRef(new Animated.Value(0)).current;
  const sweepOpacity = useRef(new Animated.Value(0)).current;
  const prevActiveRef = useRef(active);

  useEffect(() => {
    Animated.timing(activeBorderOpacity, {
      toValue: active ? 1 : 0,
      duration: 200,
      useNativeDriver: true,
    }).start();

    if (active && !prevActiveRef.current) {
      radioScale.setValue(0.5);
      sweepAnim.setValue(0);
      sweepOpacity.setValue(0.85);
      Animated.parallel([
        Animated.sequence([
          Animated.spring(radioScale, { toValue: 1.18, speed: 36, bounciness: 10, useNativeDriver: true }),
          Animated.spring(radioScale, { toValue: 1, speed: 24, bounciness: 8, useNativeDriver: true }),
        ]),
        Animated.timing(sweepAnim, {
          toValue: 1,
          duration: 420,
          easing: Easing.out(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.timing(sweepOpacity, {
          toValue: 0,
          duration: 420,
          easing: Easing.in(Easing.quad),
          useNativeDriver: true,
        }),
      ]).start();
    }
    prevActiveRef.current = active;
  }, [active, activeBorderOpacity, radioScale, sweepAnim, sweepOpacity]);

  const sweepX = sweepAnim.interpolate({ inputRange: [0, 1], outputRange: [-110, 380] });
  const resolvedStyle = typeof style === 'function' ? style({ pressed: false }) : style;

  return (
    <TactileSpringPressable
      {...rest}
      activeScale={0.97}
      glowColor="#E50914"
      glowBorderRadius={9}
      style={[resolvedStyle, { overflow: 'hidden', position: 'relative' }]}>
      <Animated.View
        pointerEvents="none"
        style={[
          StyleSheet.absoluteFill,
          styles.versionActiveBorderOverlay,
          { opacity: activeBorderOpacity },
        ]}
      />
      <Animated.View
        pointerEvents="none"
        style={[
          styles.versionSheenStreak,
          { opacity: sweepOpacity, transform: [{ translateX: sweepX }, { rotate: '18deg' }] },
        ]}
      />
      <View style={styles.versionOptionContentRow}>
        <Animated.View style={{ transform: [{ scale: radioScale }] }}>
          {renderRadioIcon(radioScale)}
        </Animated.View>
        <View style={{ flex: 1 }}>{children}</View>
      </View>
    </TactileSpringPressable>
  );
};

// ---------------------------------------------------------------------------
// 16. HeroPortraitPoster — Spring Entrance, Tier Rim, Periodic 4.5s Sheen
// ---------------------------------------------------------------------------
export const HeroPortraitPoster: React.FC<{
  source: ImageProps['source'];
  tier?: CardGlowTier;
  isDv?: boolean;
  style?: StyleProp<ViewStyle>;
  imageStyle?: StyleProp<ImageStyle>;
}> = ({ source, tier = 'none', isDv = false, style, imageStyle }) => {
  const enterOpacity = useRef(new Animated.Value(0)).current;
  const enterScale = useRef(new Animated.Value(0.9)).current;
  const enterY = useRef(new Animated.Value(12)).current;
  const periodicSweep = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    enterOpacity.setValue(0);
    enterScale.setValue(0.9);
    enterY.setValue(12);
    Animated.parallel([
      Animated.timing(enterOpacity, {
        toValue: 1,
        duration: 280,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.spring(enterScale, {
        toValue: 1,
        speed: 22,
        bounciness: 6,
        useNativeDriver: true,
      }),
      Animated.timing(enterY, {
        toValue: 0,
        duration: 260,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
    ]).start();
  }, [enterOpacity, enterScale, enterY]);

  useEffect(() => {
    periodicSweep.setValue(0);
    const loop = Animated.loop(
      Animated.sequence([
        Animated.delay(3800),
        Animated.timing(periodicSweep, {
          toValue: 1,
          duration: 700,
          easing: Easing.inOut(Easing.cubic),
          useNativeDriver: true,
        }),
        Animated.timing(periodicSweep, {
          toValue: 0,
          duration: 0,
          useNativeDriver: true,
        }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [periodicSweep]);

  const periodicX = periodicSweep.interpolate({ inputRange: [0, 1], outputRange: [-90, 175] });
  const resolvedGlow = tier === '4k' ? (isDv ? '#C084FC' : '#F5C518') : tier === '1080p' ? '#38BDF8' : tier === 'sd' ? '#F59E0B' : '#E50914';

  return (
    <Animated.View style={{ opacity: enterOpacity, transform: [{ scale: enterScale }, { translateY: enterY }] }}>
      <TierRimGlowCard tier={tier} isDv={isDv} borderRadius={10}>
        <TactileSpringPressable
          enableSheen={true}
          activeScale={0.96}
          glowBorderRadius={10}
          glowColor={resolvedGlow}
          style={[style, { overflow: 'hidden', position: 'relative' }]}>
          <FadeInPoster source={source} style={[styles.heroPosterInnerImg, imageStyle]} resizeMode="cover" />
          <Animated.View
            pointerEvents="none"
            style={[
              styles.portraitSheenStreak,
              { transform: [{ translateX: periodicX }, { rotate: '20deg' }] },
            ]}
          />
        </TactileSpringPressable>
      </TierRimGlowCard>
    </Animated.View>
  );
};

// ---------------------------------------------------------------------------
// 17. SeasonTriggerPill, SeasonBottomSheet & SeasonOptionRow
// ---------------------------------------------------------------------------
export const SeasonTriggerPill: React.FC<
  TactileSpringPressableProps & {
    label: string;
    currentIndex?: number;
    totalCount: number;
    isOpen: boolean;
    chevronIcon?: React.ReactNode;
  }
> = ({ label, currentIndex = 0, totalCount, isOpen, chevronIcon, style, ...rest }) => {
  const dotPulse = useRef(new Animated.Value(0)).current;
  const radarAnim = useRef(new Animated.Value(0)).current;
  const rimPulse = useRef(new Animated.Value(0)).current;
  const sheenAnim = useRef(new Animated.Value(0)).current;
  const chevronAnim = useRef(new Animated.Value(isOpen ? 1 : 0)).current;

  useEffect(() => {
    Animated.spring(chevronAnim, {
      toValue: isOpen ? 1 : 0,
      speed: 24,
      bounciness: 4,
      useNativeDriver: true,
    }).start();
  }, [isOpen, chevronAnim]);

  useEffect(() => {
    dotPulse.setValue(0);
    radarAnim.setValue(0);
    rimPulse.setValue(0);
    sheenAnim.setValue(0);

    const dotLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(dotPulse, { toValue: 1, duration: 950, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
        Animated.timing(dotPulse, { toValue: 0, duration: 950, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
      ]),
    );
    const radarLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(radarAnim, { toValue: 1, duration: 1400, easing: Easing.out(Easing.cubic), useNativeDriver: true }),
        Animated.timing(radarAnim, { toValue: 0, duration: 0, useNativeDriver: true }),
      ]),
    );
    const rimLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(rimPulse, { toValue: 1, duration: 1500, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
        Animated.timing(rimPulse, { toValue: 0, duration: 1500, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
      ]),
    );
    const sheenLoop = Animated.loop(
      Animated.sequence([
        Animated.delay(2450),
        Animated.timing(sheenAnim, { toValue: 1, duration: 750, easing: Easing.inOut(Easing.cubic), useNativeDriver: true }),
        Animated.timing(sheenAnim, { toValue: 0, duration: 0, useNativeDriver: true }),
      ]),
    );

    dotLoop.start();
    radarLoop.start();
    rimLoop.start();
    sheenLoop.start();
    return () => {
      dotLoop.stop();
      radarLoop.stop();
      rimLoop.stop();
      sheenLoop.stop();
    };
  }, [dotPulse, radarAnim, rimPulse, sheenAnim]);

  const dotScale = dotPulse.interpolate({ inputRange: [0, 1], outputRange: [0.92, 1.15] });
  const radarScale = radarAnim.interpolate({ inputRange: [0, 1], outputRange: [0.8, 2.3] });
  const radarOpacity = radarAnim.interpolate({ inputRange: [0, 1], outputRange: [0.75, 0] });
  const outerAuraOpacity = rimPulse.interpolate({ inputRange: [0, 1], outputRange: [0.28, 0.85] });
  const goldRimOpacity = rimPulse.interpolate({ inputRange: [0, 1], outputRange: [0.5, 0.2] });
  const sheenX = sheenAnim.interpolate({ inputRange: [0, 1], outputRange: [-90, 230] });
  const chevronRotate = chevronAnim.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '180deg'] });
  const resolvedStyle = typeof style === 'function' ? style({ pressed: false }) : style;

  return (
    <View style={styles.seasonTriggerOuterWrap}>
      <Animated.View
        pointerEvents="none"
        style={[styles.seasonTriggerCrimsonAura, { opacity: outerAuraOpacity }]}
      />
      <Animated.View
        pointerEvents="none"
        style={[styles.seasonTriggerGoldRim, { opacity: goldRimOpacity }]}
      />
      <TactileSpringPressable
        {...rest}
        activeScale={0.95}
        glowColor="#F5C518"
        glowBorderRadius={999}
        style={[styles.seasonTriggerPill, resolvedStyle]}>
        <Animated.View
          pointerEvents="none"
          style={[styles.seasonTriggerSheen, { transform: [{ translateX: sheenX }, { rotate: '20deg' }] }]}
        />
        <View style={styles.seasonTriggerDotAnchor}>
          <Animated.View
            pointerEvents="none"
            style={[
              styles.seasonTriggerDotRadar,
              { opacity: radarOpacity, transform: [{ scale: radarScale }] },
            ]}
          />
          <Animated.View style={[styles.seasonTriggerDot, { transform: [{ scale: dotScale }] }]} />
        </View>
        <Text style={styles.seasonTriggerLabel} numberOfLines={1}>
          {label}
        </Text>
        {totalCount > 1 && (
          <View style={styles.seasonTriggerCounter}>
            <Text style={styles.seasonTriggerCounterText}>
              {`${Math.max(1, currentIndex + 1)}/${totalCount}`}
            </Text>
          </View>
        )}
        <Animated.View style={{ transform: [{ rotate: chevronRotate }] }}>
          {chevronIcon}
        </Animated.View>
      </TactileSpringPressable>
    </View>
  );
};

export const SeasonBottomSheet: React.FC<{
  seriesTitle: string;
  seasonCount: number;
  onClose: () => void;
  closeIcon?: React.ReactNode;
  children: React.ReactNode;
}> = ({ seriesTitle, seasonCount, onClose, closeIcon, children }) => {
  const backdropOpacity = useRef(new Animated.Value(0)).current;
  const sheetY = useRef(new Animated.Value(48)).current;

  useEffect(() => {
    Animated.parallel([
      Animated.timing(backdropOpacity, {
        toValue: 1,
        duration: 220,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.spring(sheetY, {
        toValue: 0,
        speed: 20,
        bounciness: 4,
        useNativeDriver: true,
      }),
    ]).start();
  }, [backdropOpacity, sheetY]);

  return (
    <View style={styles.seasonSheetBackdropWrap}>
      <Animated.View style={[StyleSheet.absoluteFill, styles.seasonSheetDim, { opacity: backdropOpacity }]}>
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="Close season drawer" />
      </Animated.View>
      <Animated.View style={[styles.seasonSheetContainer, { transform: [{ translateY: sheetY }] }]}>
        <View pointerEvents="none" style={styles.seasonSheetTopRim} />
        <View style={styles.seasonSheetHandle} />
        <View style={styles.seasonSheetHeaderRow}>
          <View style={{ flex: 1 }}>
            <Text style={styles.seasonSheetOverline} numberOfLines={1}>
              {(seriesTitle || 'SERIES').toUpperCase()}
            </Text>
            <View style={styles.seasonSheetTitleRow}>
              <Text style={styles.seasonSheetTitle}>Select Season</Text>
              <View style={styles.seasonSheetCountPill}>
                <Text style={styles.seasonSheetCountText}>
                  {seasonCount} {seasonCount === 1 ? 'SEASON' : 'SEASONS'}
                </Text>
              </View>
            </View>
          </View>
          <TactileSpringPressable
            onPress={onClose}
            activeScale={0.92}
            glowColor="rgba(255, 255, 255, 0.35)"
            glowBorderRadius={17}
            style={styles.seasonSheetCloseBtn}
            accessibilityLabel="Close">
            {closeIcon}
          </TactileSpringPressable>
        </View>
        {children}
      </Animated.View>
    </View>
  );
};

export const SeasonOptionRow: React.FC<
  TactileSpringPressableProps & {
    index: number;
    seasonNumber: number;
    label: string;
    specs: string[];
    active: boolean;
    rightIcon: React.ReactNode;
  }
> = ({ index, seasonNumber, label, specs, active, rightIcon, style, onPress, ...rest }) => {
  const enterOpacity = useRef(new Animated.Value(0)).current;
  const enterY = useRef(new Animated.Value(12)).current;
  const checkScale = useRef(new Animated.Value(active ? 1 : 0.8)).current;
  const sweepAnim = useRef(new Animated.Value(0)).current;
  const sweepOpacity = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const delay = Math.min(index, 12) * 35;
    Animated.parallel([
      Animated.timing(enterOpacity, {
        toValue: 1,
        duration: 220,
        delay,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.timing(enterY, {
        toValue: 0,
        duration: 240,
        delay,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
    ]).start();
  }, [index, enterOpacity, enterY]);

  useEffect(() => {
    if (active) {
      checkScale.setValue(0.6);
      Animated.spring(checkScale, {
        toValue: 1,
        speed: 32,
        bounciness: 10,
        useNativeDriver: true,
      }).start();
    } else {
      checkScale.setValue(1);
    }
  }, [active, checkScale]);

  const handlePress: PressableProps['onPress'] = (e) => {
    sweepAnim.setValue(0);
    sweepOpacity.setValue(0.85);
    Animated.parallel([
      Animated.timing(sweepAnim, {
        toValue: 1,
        duration: 380,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.timing(sweepOpacity, {
        toValue: 0,
        duration: 380,
        easing: Easing.in(Easing.quad),
        useNativeDriver: true,
      }),
    ]).start();
    if (onPress) onPress(e);
  };

  const sweepX = sweepAnim.interpolate({ inputRange: [0, 1], outputRange: [-120, 420] });
  const resolvedStyle = typeof style === 'function' ? style({ pressed: false }) : style;
  const badgeCode = `S${seasonNumber < 10 ? `0${seasonNumber}` : seasonNumber}`;

  return (
    <Animated.View style={{ opacity: enterOpacity, transform: [{ translateY: enterY }] }}>
      <TactileSpringPressable
        {...rest}
        activeScale={0.97}
        glowColor={active ? '#F5C518' : 'rgba(229, 9, 20, 0.65)'}
        glowBorderRadius={14}
        highlighted={active}
        onPress={handlePress}
        style={[
          styles.seasonRowCard,
          active && styles.seasonRowCardActive,
          resolvedStyle,
        ]}>
        <Animated.View
          pointerEvents="none"
          style={[
            styles.seasonRowSheen,
            { opacity: sweepOpacity, transform: [{ translateX: sweepX }, { rotate: '18deg' }] },
          ]}
        />
        <View style={[styles.seasonBadgeBox, active && styles.seasonBadgeBoxActive]}>
          <Text style={[styles.seasonBadgeBoxText, active && styles.seasonBadgeBoxTextActive]}>
            {badgeCode}
          </Text>
        </View>
        <View style={{ flex: 1, gap: 4 }}>
          <View style={styles.seasonRowTitleLine}>
            <Text style={[styles.seasonRowTitle, active && { color: '#FFFFFF' }]} numberOfLines={1}>
              {label}
            </Text>
            {active && (
              <View style={styles.seasonNowPill}>
                <Text style={styles.seasonNowPillText}>Now Viewing</Text>
              </View>
            )}
          </View>
          {specs.length > 0 && (
            <View style={styles.seasonSpecChipsRow}>
              {specs.map((sp, spIdx) => (
                <View key={spIdx} style={[styles.seasonSpecChip, active && styles.seasonSpecChipActive]}>
                  <Text style={styles.seasonSpecChipText}>{sp}</Text>
                </View>
              ))}
            </View>
          )}
        </View>
        <Animated.View style={{ transform: [{ scale: checkScale }] }}>
          {rightIcon}
        </Animated.View>
      </TactileSpringPressable>
    </Animated.View>
  );
};

// ---------------------------------------------------------------------------
// 18. ZoomHudPill — Centered Obsidian-Glass Player Zoom Indicator
// ---------------------------------------------------------------------------
export const ZoomHudPill: React.FC<{
  visible: boolean;
  zoomPct: number;
}> = ({ visible, zoomPct }) => {
  const opacity = useRef(new Animated.Value(0)).current;
  const scale = useRef(new Animated.Value(0.92)).current;

  useEffect(() => {
    Animated.parallel([
      Animated.timing(opacity, {
        toValue: visible ? 1 : 0,
        duration: visible ? 140 : 220,
        useNativeDriver: true,
      }),
      Animated.spring(scale, {
        toValue: visible ? 1 : 0.92,
        speed: 28,
        bounciness: 5,
        useNativeDriver: true,
      }),
    ]).start();
  }, [visible, opacity, scale]);

  const formattedPct = zoomPct > 0 ? `+${zoomPct}%` : `${zoomPct}%`;

  return (
    <Animated.View
      pointerEvents="none"
      style={[styles.zoomHudWrap, { opacity, transform: [{ scale }] }]}>
      <View style={styles.zoomHudPill}>
        <Text style={styles.zoomHudLabel}>Zoom: </Text>
        <Text style={[styles.zoomHudValue, zoomPct !== 0 && { color: zoomPct > 0 ? '#38BDF8' : '#F5C518' }]}>
          {formattedPct}
        </Text>
      </View>
    </Animated.View>
  );
};

const styles = StyleSheet.create({
  shimmerGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    paddingHorizontal: 10,
    paddingTop: 4,
  },
  skeletonCard: {
    backgroundColor: '#14171F',
    borderRadius: 12,
    overflow: 'hidden',
    borderWidth: 2,
    borderColor: '#1F2430',
  },
  skeletonPoster: {
    width: '100%',
    aspectRatio: 2 / 3,
    backgroundColor: '#141418',
    overflow: 'hidden',
    position: 'relative',
  },
  skeletonTopRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 8,
  },
  skeletonPillLeft: {
    width: 38,
    height: 18,
    borderRadius: 5,
    backgroundColor: '#222430',
  },
  skeletonPillRight: {
    width: 42,
    height: 18,
    borderRadius: 5,
    backgroundColor: '#222430',
  },
  shimmerSweep: {
    position: 'absolute',
    top: -20,
    bottom: -20,
    width: 110,
    flexDirection: 'row',
  },
  shimmerEdge: {
    flex: 1,
    backgroundColor: '#1E1E28',
    opacity: 0.45,
  },
  shimmerCore: {
    flex: 1.4,
    backgroundColor: '#262632',
    opacity: 0.85,
  },
  shimmerSweepSmall: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: 70,
    backgroundColor: '#262632',
    opacity: 0.75,
  },
  skeletonFooter: {
    padding: 9,
    gap: 7,
  },
  skeletonTitleBar: {
    height: 13,
    width: '78%',
    borderRadius: 4,
    backgroundColor: '#1C1E28',
    overflow: 'hidden',
  },
  skeletonMetaRow: {
    flexDirection: 'row',
    gap: 6,
  },
  skeletonYearBar: {
    height: 10,
    width: '28%',
    borderRadius: 4,
    backgroundColor: '#181A24',
  },
  skeletonTagBar: {
    height: 10,
    width: '36%',
    borderRadius: 4,
    backgroundColor: '#181A24',
  },
  arcCenterWrap: {
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
  },
  arcCompactWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
  },
  arcStatusText: {
    color: '#D1D5DB',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 0.3,
  },
  topProgressTrack: {
    height: 2.5,
    width: '100%',
    backgroundColor: 'rgba(229, 9, 20, 0.14)',
    overflow: 'hidden',
    marginBottom: 6,
  },
  topProgressBar: {
    height: 2.5,
    width: 160,
    borderRadius: 2,
    backgroundColor: '#E50914',
    shadowColor: '#E50914',
    shadowOpacity: 0.85,
    shadowRadius: 6,
    elevation: 4,
  },
  discInspectRow: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    position: 'relative',
    overflow: 'hidden',
    paddingVertical: 2,
  },
  discLaserSweep: {
    position: 'absolute',
    top: -24,
    bottom: -24,
    width: 68,
  },
  discRing: {
    width: 26,
    height: 26,
    borderRadius: 13,
    borderWidth: 2.5,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'relative',
    backgroundColor: 'rgba(255,255,255,0.06)',
  },
  discNotch: {
    position: 'absolute',
    top: -2.5,
    width: 6,
    height: 4,
    borderRadius: 2,
  },
  discInnerHub: {
    width: 8,
    height: 8,
    borderRadius: 4,
    borderWidth: 1.5,
  },
  discTextCol: {
    flexShrink: 1,
  },
  discHeadlineText: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '800',
  },
  discTelemetryText: {
    fontSize: 11,
    fontWeight: '700',
    marginTop: 1,
    letterSpacing: 0.2,
  },
  touchSheenStreak: {
    position: 'absolute',
    top: -40,
    bottom: -40,
    width: 72,
    backgroundColor: 'rgba(255, 255, 255, 0.26)',
  },
  badgeGlintStreak: {
    position: 'absolute',
    top: -10,
    bottom: -10,
    width: 22,
    backgroundColor: 'rgba(255, 255, 255, 0.42)',
  },
  seekBurstContainer: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    justifyContent: 'center',
    paddingHorizontal: 52,
  },
  seekBurstAnchor: {
    width: 112,
    height: 112,
    alignItems: 'center',
    justifyContent: 'center',
  },
  seekBurstRipple: {
    position: 'absolute',
    width: 108,
    height: 108,
    borderRadius: 54,
    backgroundColor: 'rgba(255, 255, 255, 0.22)',
    borderWidth: 1.5,
    borderColor: 'rgba(229, 9, 20, 0.72)',
  },
  seekBurstPill: {
    backgroundColor: 'rgba(12, 14, 20, 0.72)',
    paddingHorizontal: 14,
    paddingVertical: 9,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.25)',
  },
  seekBurstChevron: {
    color: '#FFFFFF',
    fontSize: 16,
    fontWeight: '900',
    letterSpacing: 0.6,
  },
  progressGlowTip: {
    position: 'absolute',
    right: -3,
    top: -1.5,
    width: 7,
    height: 7,
    borderRadius: 3.5,
    backgroundColor: '#FFFFFF',
    borderWidth: 1.5,
    borderColor: '#E50914',
  },
  bitrateTrack: {
    width: '100%',
    height: 5,
    borderRadius: 3,
    backgroundColor: '#1E2534',
    overflow: 'hidden',
    marginTop: 7,
  },
  bitrateFill: {
    height: '100%',
    borderRadius: 3,
  },
  miniEqRow: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: 2,
    height: 11,
    marginRight: 5,
  },
  miniEqBar: {
    width: 2.5,
    height: 11,
    borderRadius: 1.5,
  },
  copySpecContainer: {
    marginTop: 14,
    padding: 11,
    borderRadius: 10,
    backgroundColor: '#0D1018',
    borderWidth: 1,
    borderColor: '#1F2636',
  },
  copySpecHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 6,
  },
  copySpecLabel: {
    color: '#6C7789',
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1,
  },
  copyChipBtn: {
    paddingHorizontal: 9,
    paddingVertical: 4,
    borderRadius: 7,
    backgroundColor: '#181E2B',
    borderWidth: 1,
    borderColor: '#2E384D',
  },
  copyChipBtnDone: {
    backgroundColor: 'rgba(70, 211, 105, 0.14)',
    borderColor: '#46D369',
  },
  copyChipInner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  copyChipText: {
    color: '#CBD5E1',
    fontSize: 11,
    fontWeight: '700',
  },
  copyChipTextDone: {
    color: '#46D369',
    fontWeight: '800',
  },
  copySpecMono: {
    color: '#A3B0C4',
    fontSize: 11.5,
    lineHeight: 16,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  playHeroAuraWrap: {
    position: 'relative',
  },
  playHeroOuterAura: {
    position: 'absolute',
    top: -3,
    bottom: -3,
    left: -3,
    right: -3,
    borderRadius: 13,
    borderWidth: 2,
    borderColor: '#FF2A36',
  },
  heroSweepStreak: {
    position: 'absolute',
    top: -28,
    bottom: -28,
    width: 60,
    backgroundColor: 'rgba(255, 255, 255, 0.24)',
  },
  inlineDlProgressTrack: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    backgroundColor: 'rgba(70, 211, 105, 0.16)',
    borderBottomWidth: 3,
    borderBottomColor: '#46D369',
  },
  trailerGlowWrap: {
    position: 'relative',
    marginBottom: 0,
  },
  trailerVioletRim: {
    position: 'absolute',
    top: -1.5,
    bottom: -1.5,
    left: -1.5,
    right: -1.5,
    borderRadius: 11.5,
    borderWidth: 1.5,
    borderColor: '#8B5CF6',
  },
  trailerPinkRim: {
    position: 'absolute',
    top: -1.5,
    bottom: -1.5,
    left: -1.5,
    right: -1.5,
    borderRadius: 11.5,
    borderWidth: 1.5,
    borderColor: '#EC4899',
  },
  seriesCycleWrap: {
    minWidth: 52,
    borderRadius: 5,
    overflow: 'hidden',
    position: 'relative',
  },
  seriesSeasonPill: {
    backgroundColor: 'rgba(245, 197, 24, 0.94)',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 5,
    minWidth: 52,
    alignItems: 'center',
    justifyContent: 'center',
  },
  seriesSeasonPillText: {
    color: '#000000',
    fontSize: 10,
    fontWeight: '900',
  },
  seriesRatingPill: {
    backgroundColor: 'rgba(11, 13, 18, 0.9)',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 5,
    minWidth: 52,
    borderWidth: 1,
    borderColor: 'rgba(245, 197, 24, 0.65)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  seriesRatingPillText: {
    color: '#F5C518',
    fontSize: 10,
    fontWeight: '800',
  },
  versionActiveBorderOverlay: {
    borderRadius: 9,
    borderWidth: 1.5,
    borderColor: '#F5C518',
    backgroundColor: 'rgba(229, 9, 20, 0.08)',
  },
  versionSheenStreak: {
    position: 'absolute',
    top: -20,
    bottom: -20,
    width: 66,
    backgroundColor: 'rgba(255, 255, 255, 0.22)',
  },
  versionOptionContentRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 9,
  },
  heroPosterInnerImg: {
    width: '100%',
    height: '100%',
  },
  portraitSheenStreak: {
    position: 'absolute',
    top: -24,
    bottom: -24,
    width: 38,
    backgroundColor: 'rgba(255, 255, 255, 0.24)',
  },
  seasonTriggerOuterWrap: {
    position: 'relative',
  },
  seasonTriggerCrimsonAura: {
    position: 'absolute',
    top: -2,
    bottom: -2,
    left: -2,
    right: -2,
    borderRadius: 999,
    borderWidth: 1.5,
    borderColor: '#E50914',
  },
  seasonTriggerGoldRim: {
    position: 'absolute',
    top: -1,
    bottom: -1,
    left: -1,
    right: -1,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: '#F5C518',
  },
  seasonTriggerPill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    backgroundColor: '#1B1118',
    paddingVertical: 7,
    paddingHorizontal: 13,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: 'rgba(229, 9, 20, 0.65)',
    overflow: 'hidden',
    position: 'relative',
  },
  seasonTriggerSheen: {
    position: 'absolute',
    top: -16,
    bottom: -16,
    width: 34,
    backgroundColor: 'rgba(255, 255, 255, 0.2)',
  },
  seasonTriggerDotAnchor: {
    width: 10,
    height: 10,
    alignItems: 'center',
    justifyContent: 'center',
    position: 'relative',
  },
  seasonTriggerDotRadar: {
    position: 'absolute',
    width: 10,
    height: 10,
    borderRadius: 5,
    borderWidth: 1.5,
    borderColor: '#FF4B55',
  },
  seasonTriggerDot: {
    width: 7,
    height: 7,
    borderRadius: 3.5,
    backgroundColor: '#E50914',
  },
  seasonTriggerLabel: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '800',
  },
  seasonTriggerCounter: {
    backgroundColor: 'rgba(245, 197, 24, 0.18)',
    borderWidth: 1,
    borderColor: 'rgba(245, 197, 24, 0.42)',
    paddingHorizontal: 7,
    paddingVertical: 1.5,
    borderRadius: 999,
  },
  seasonTriggerCounterText: {
    color: '#F5C518',
    fontSize: 10.5,
    fontWeight: '900',
  },
  seasonSheetBackdropWrap: {
    flex: 1,
    justifyContent: 'flex-end',
  },
  seasonSheetDim: {
    backgroundColor: 'rgba(0, 0, 0, 0.72)',
  },
  seasonSheetContainer: {
    backgroundColor: '#0F1118',
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    paddingHorizontal: 18,
    paddingTop: 10,
    paddingBottom: 26,
    borderWidth: 1,
    borderBottomWidth: 0,
    borderColor: 'rgba(255, 255, 255, 0.09)',
    maxHeight: '78%',
    position: 'relative',
    overflow: 'hidden',
  },
  seasonSheetTopRim: {
    position: 'absolute',
    top: 0,
    left: 24,
    right: 24,
    height: 1,
    backgroundColor: 'rgba(255, 255, 255, 0.14)',
  },
  seasonSheetHandle: {
    width: 36,
    height: 4,
    borderRadius: 2,
    backgroundColor: 'rgba(255, 255, 255, 0.22)',
    alignSelf: 'center',
    marginBottom: 14,
  },
  seasonSheetHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginBottom: 14,
  },
  seasonSheetOverline: {
    color: '#E50914',
    fontSize: 10.5,
    fontWeight: '900',
    letterSpacing: 1.2,
  },
  seasonSheetTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginTop: 3,
  },
  seasonSheetTitle: {
    color: '#FFFFFF',
    fontSize: 20,
    fontWeight: '800',
  },
  seasonSheetCountPill: {
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.12)',
    paddingHorizontal: 8,
    paddingVertical: 2.5,
    borderRadius: 999,
  },
  seasonSheetCountText: {
    color: '#CBD5E1',
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.6,
  },
  seasonSheetCloseBtn: {
    width: 34,
    height: 34,
    borderRadius: 17,
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.12)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  seasonRowCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    backgroundColor: '#161A25',
    paddingVertical: 12,
    paddingHorizontal: 12,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.08)',
    marginBottom: 9,
    overflow: 'hidden',
    position: 'relative',
  },
  seasonRowCardActive: {
    backgroundColor: 'rgba(229, 9, 20, 0.12)',
    borderColor: 'rgba(245, 197, 24, 0.55)',
  },
  seasonRowSheen: {
    position: 'absolute',
    top: -20,
    bottom: -20,
    width: 66,
    backgroundColor: 'rgba(255, 255, 255, 0.22)',
  },
  seasonBadgeBox: {
    paddingHorizontal: 9,
    paddingVertical: 8,
    minWidth: 44,
    borderRadius: 10,
    backgroundColor: '#1F2536',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.1)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  seasonBadgeBoxActive: {
    backgroundColor: 'rgba(245, 197, 24, 0.16)',
    borderColor: 'rgba(245, 197, 24, 0.65)',
  },
  seasonBadgeBoxText: {
    color: '#CBD5E1',
    fontSize: 13,
    fontWeight: '900',
  },
  seasonBadgeBoxTextActive: {
    color: '#F5C518',
  },
  seasonRowTitleLine: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  seasonRowTitle: {
    color: '#E2E8F0',
    fontSize: 15,
    fontWeight: '800',
    flexShrink: 1,
  },
  seasonNowPill: {
    backgroundColor: 'rgba(229, 9, 20, 0.22)',
    borderWidth: 1,
    borderColor: 'rgba(229, 9, 20, 0.6)',
    paddingHorizontal: 7,
    paddingVertical: 2,
    borderRadius: 999,
  },
  seasonNowPillText: {
    color: '#FCA5A5',
    fontSize: 9.5,
    fontWeight: '800',
  },
  seasonSpecChipsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 5,
  },
  seasonSpecChip: {
    backgroundColor: '#1E2433',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.08)',
    paddingHorizontal: 7,
    paddingVertical: 2,
    borderRadius: 5,
  },
  seasonSpecChipActive: {
    backgroundColor: 'rgba(255, 255, 255, 0.08)',
    borderColor: 'rgba(245, 197, 24, 0.35)',
  },
  seasonSpecChipText: {
    color: '#A5B4C8',
    fontSize: 10.5,
    fontWeight: '700',
  },
  zoomHudWrap: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
  zoomHudPill: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(11, 13, 19, 0.86)',
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.16)',
    paddingHorizontal: 18,
    paddingVertical: 10,
    borderRadius: 999,
  },
  zoomHudLabel: {
    color: '#CBD5E1',
    fontSize: 14,
    fontWeight: '700',
  },
  zoomHudValue: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '900',
  },
});
