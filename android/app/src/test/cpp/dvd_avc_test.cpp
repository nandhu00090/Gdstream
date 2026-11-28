#include "../../main/cpp/dvd_avc.h"
#include <cassert>
#include <map>
#include <string>

int main() {
    for (bool sync : {false, true}) {
        for (bool retry : {false, true}) {
            std::map<std::string, int> format;
            dvd::configureAvcTiming(sync, retry, [&](const char *key, int value) { format[key] = value; });
            assert(format.at("max-bframes") == 0);
            assert(format.at("profile") == 1);
            assert(format.at("latency") == 0);
            assert(format.at("i-frame-interval") == (sync && !retry ? 1 : 0));
        }
    }
}
