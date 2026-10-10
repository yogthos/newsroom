// test_mini.cpp: the mini engine against the upstream kittenml package's
// goldens (dev/mini_golden.py). The phonemes espeak-ng gives each sentence
// and the token ids made of them have to be the Python ones exactly; the
// durations the model predicts, which no noise touches, have to agree within
// a frame a phoneme, but for one a sentence, which may be five off: ONNX
// Runtime's own kernels differ by platform, and its Python package on x86
// Linux holds the last "t" of "Well... maybe not." half as long as on the
// Mac, where the goldens are made. Writes the first sentence's audio to OUT.raw (float32).
//
//   native/build/test-mini MODEL.onnx VOICES.npz CONFIG.json ESPEAK_DATA GOLDEN_DIR OUT.raw

#include "mini.h"
#include "nlohmann/json.hpp"

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

int main(int argc, char ** argv) {
    if (argc < 7) {
        std::fprintf(stderr, "usage: test-mini MODEL.onnx VOICES.npz CONFIG.json ESPEAK_DATA GOLDEN_DIR OUT.raw\n");
        return 2;
    }
    std::ifstream gf(std::string(argv[5]) + "/golden.json");
    auto golden = nlohmann::json::parse(gf);
    const std::string voice = golden.at("voice");
    nrttsm * h = nrttsm_open(argv[1], argv[2], argv[3], argv[4], 0);
    if (!nrttsm_ok(h)) {
        std::fprintf(stderr, "cannot open the mini engine: %s\n", nrttsm_error(h));
        return 1;
    }
    int failures = 0, n = 0;
    for (auto & c : golden.at("cases")) {
        const std::string text = c.at("text"), want = c.at("phonemes");
        std::vector<char> buf(4096);
        nrttsm_phonemes(h, text.c_str(), buf.data(), (int) buf.size());
        const std::string got(buf.data());
        std::vector<int64_t> ids(2048);
        ids.resize(std::max(0, nrttsm_tokens(h, text.c_str(), ids.data(), (int) ids.size())));
        const bool phon_ok = got == want, ids_ok = ids == c.at("tokens").get<std::vector<int64_t>>();
        const int samples = nrttsm_speak(h, voice.c_str(), text.c_str(), 1.0f);
        std::vector<int64_t> dur(2048);
        dur.resize(std::max(0, nrttsm_durations(h, dur.data(), (int) dur.size())));
        auto want_dur = c.at("duration").get<std::vector<int64_t>>();
        int off = 0, far = 0;
        bool dur_ok = dur.size() == want_dur.size();
        for (size_t i = 0; dur_ok && i < dur.size(); ++i) {
            const int d = (int) std::llabs(dur[i] - want_dur[i]);
            off = std::max(off, d);
            if (d > 1) ++far;
        }
        dur_ok = dur_ok && off <= 5 && far <= 1;
        const bool ok = phon_ok && ids_ok && dur_ok && samples > 0;
        if (!ok) ++failures;
        std::printf("%-6s %s\n", ok ? "ok" : "FAIL", text.c_str());
        if (!phon_ok) std::printf("       phonemes %s\n       expected %s\n", got.c_str(), want.c_str());
        if (!ids_ok) std::printf("       %zu tokens, expected %zu\n", ids.size(), c.at("tokens").size());
        if (samples < 0) std::printf("       speaking failed: %s\n", nrttsm_error(h));
        if (!dur_ok) {
            std::printf("       durations off by up to %d (%zu of %zu):", off, dur.size(), want_dur.size());
            for (size_t i = 0; i < std::min(dur.size(), want_dur.size()); ++i)
                if (dur[i] != want_dur[i])
                    std::printf(" [%zu] %lld for %lld", i, (long long) dur[i], (long long) want_dur[i]);
            std::printf("\n");
        }
        if (n++ == 0 && samples > 0) {
            if (FILE * f = std::fopen(argv[6], "wb")) {
                std::fwrite(nrttsm_audio(h), sizeof(float), (size_t) samples, f);
                std::fclose(f);
            }
        }
    }
    nrttsm_free(h);
    return failures ? 1 : 0;
}
