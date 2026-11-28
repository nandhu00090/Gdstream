import React, { useEffect, useRef } from 'react';
import {
  Animated,
  Easing,
  Image,
  ImageProps,
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
  style?: StyleProp<ViewStyle>;
}

export const CinemaArcLoader: React.FC<CinemaArcLoaderProps> = ({
  size = 'medium',
  statusText,
  messages,
  compact = false,
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

  return (
    <View style={[compact ? styles.arcCompactWrap : styles.arcCenterWrap, style]}>
      <View style={{ width: dim, height: dim, alignItems: 'center', justifyContent: 'center' }}>
        <Animated.View
          style={{
            position: 'absolute',
            width: dim,
            height: dim,
            borderRadius: dim / 2,
            borderWidth: stroke,
            borderColor: 'rgba(229,9,20,0.18)',
            borderTopColor: '#E50914',
            borderRightColor: '#E50914',
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
            borderColor: 'rgba(245,197,24,0.16)',
            borderBottomColor: '#F5C518',
            borderLeftColor: '#F5C518',
            transform: [{ rotate: innerRotate }],
          }}
        />
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: size === 'small' ? 1.5 : 2.5 }}>
          <Animated.View
            style={{
              width: barWidth,
              height: barHeight,
              borderRadius: barWidth,
              backgroundColor: '#E50914',
              transform: [{ scaleY: bar1 }],
            }}
          />
          <Animated.View
            style={{
              width: barWidth,
              height: barHeight,
              borderRadius: barWidth,
              backgroundColor: '#F5C518',
              transform: [{ scaleY: bar2 }],
            }}
          />
          <Animated.View
            style={{
              width: barWidth,
              height: barHeight,
              borderRadius: barWidth,
              backgroundColor: '#E50914',
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
// 4. TactileSpringPressable — Spring Press (0.96 -> 1.0) + GPU Border Glow
// ---------------------------------------------------------------------------
export interface TactileSpringPressableProps extends Omit<PressableProps, 'style'> {
  style?: StyleProp<ViewStyle> | ((state: { pressed: boolean }) => StyleProp<ViewStyle>);
  innerRef?: React.Ref<any>;
  activeScale?: number;
  glowColor?: string;
  glowBorderRadius?: number;
  highlighted?: boolean;
  children?: React.ReactNode;
}

export const TactileSpringPressable: React.FC<TactileSpringPressableProps> = ({
  style,
  innerRef,
  activeScale = 0.96,
  glowColor = 'rgba(229, 9, 20, 0.72)',
  glowBorderRadius = 12,
  highlighted = false,
  onPressIn,
  onPressOut,
  children,
  ...rest
}) => {
  const scale = useRef(new Animated.Value(1)).current;
  const glowOpacity = useRef(new Animated.Value(highlighted ? 1 : 0)).current;

  useEffect(() => {
    Animated.timing(glowOpacity, {
      toValue: highlighted ? 1 : 0,
      duration: 140,
      useNativeDriver: true,
    }).start();
  }, [highlighted, glowOpacity]);

  const handlePressIn: PressableProps['onPressIn'] = (e) => {
    Animated.parallel([
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
    ]).start();
    if (onPressIn) onPressIn(e);
  };

  const handlePressOut: PressableProps['onPressOut'] = (e) => {
    Animated.parallel([
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
    ]).start();
    if (onPressOut) onPressOut(e);
  };

  const resolvedStyle = typeof style === 'function' ? style({ pressed: false }) : style;

  return (
    <AnimatedPressable
      ref={innerRef}
      onPressIn={handlePressIn}
      onPressOut={handlePressOut}
      style={[resolvedStyle, { transform: [{ scale }] }]}
      {...rest}>
      {children}
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
});
