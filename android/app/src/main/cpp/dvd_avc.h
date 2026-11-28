#pragma once

namespace dvd {
// The transport stream has a single presentation/decode clock. Older codecs
// without request-sync and codecs that reorder on the first attempt need all IDRs.
template<class SetInt>
void configureAvcTiming(bool canRequestSync, bool intraOnly, SetInt setInt) {
    setInt("profile", 1); // AVCProfileBaseline
    setInt("max-bframes", 0); // MediaFormat.KEY_MAX_B_FRAMES
    setInt("latency", 0);
    setInt("i-frame-interval", canRequestSync && !intraOnly ? 1 : 0);
}
}
