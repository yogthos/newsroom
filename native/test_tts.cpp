// test_tts.cpp: the engine against the upstream Python package's goldens
// (dev/tts_golden.py), through the C face newsroom reads. The prompt it
// builds has to be the Python one token for token; the speech model's first
// logits, from the Q4_0 weights, have to rank the same tokens first as the
// bf16 Python model's do; and the golden text has to come out as speech,
// written to OUT.mp3 to listen to.
//
//   native/build/test-tts LM.gguf DECODER.gguf VOICES.gguf GOLDEN_DIR OUT.mp3

#include "newsroom_tts.h"
#include "safetensors.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <numeric>

int main(int argc, char ** argv) try {
    if (argc < 6) {
        std::fprintf(stderr, "usage: test-tts LM.gguf DECODER.gguf VOICES.gguf GOLDEN_DIR OUT.mp3\n");
        return 2;
    }
    const std::string dir = argv[4];
    auto g = st::read(dir + "/golden.safetensors");
    std::ifstream jf(dir + "/golden.json");
    auto meta = nlohmann::json::parse(jf);
    const std::string voice = meta.at("voice"), spoken = meta.at("spoken");
    int failures = 0;

    nrtts * h = nrtts_open(argv[1], argv[2], argv[3], 0);
    if (!nrtts_ok(h)) {
        std::fprintf(stderr, "cannot open the engine: %s\n", nrtts_error(h));
        return 1;
    }
    std::printf("%d voices\n", nrtts_voice_count(h));

    // the prompt
    std::vector<int32_t> prompt(8192);
    const int n = nrtts_prompt(h, voice.c_str(), spoken.c_str(), 0, prompt.data(), (int) prompt.size());
    prompt.resize(std::max(n, 0));
    auto want = meta.at("prompt_ids").get<std::vector<int32_t>>();
    const bool same = prompt == want;
    if (!same) {
        ++failures;
        size_t i = 0;
        while (i < std::min(prompt.size(), want.size()) && prompt[i] == want[i]) ++i;
        std::printf("FAIL   prompt: %zu tokens, expected %zu, first difference at %zu\n", prompt.size(), want.size(), i);
    } else {
        std::printf("ok     prompt: %zu tokens\n", prompt.size());
    }

    // the first logits
    std::vector<float> logits(nrtts_vocab_size(h));
    if (nrtts_first_logits(h, voice.c_str(), spoken.c_str(), 0, logits.data(), (int) logits.size()) < 0) {
        std::fprintf(stderr, "logits failed: %s\n", nrtts_error(h));
        return 1;
    }
    auto top_ids = g.at("logits.top_ids").i32();
    auto top_vals = g.at("logits.top_values").f32();
    std::vector<int> order(logits.size());
    std::iota(order.begin(), order.end(), 0);
    std::partial_sort(order.begin(), order.begin() + 10, order.end(), [&](int a, int b) { return logits[a] > logits[b]; });
    int overlap = 0;
    for (int i = 0; i < 10; ++i)
        for (int j = 0; j < 10; ++j) overlap += order[i] == top_ids[j];
    double err = 0;
    for (int j = 0; j < 10; ++j) err = std::max(err, (double) std::fabs(logits[top_ids[j]] - top_vals[j]));
    const bool logits_ok = order[0] == top_ids[0] && overlap >= 7;
    if (!logits_ok) ++failures;
    std::printf("%-6s logits: top token %d (expected %d), %d of the top 10 shared, max |diff| over them %.3f\n",
                logits_ok ? "ok" : "FAIL", order[0], top_ids[0], overlap, err);

    // speech
    const int samples = nrtts_speak(h, voice.c_str(), spoken.c_str(), 0, 0.8f, 50, 0.8f, 0.0f, 1234, 300, nullptr);
    const double seconds = samples / 24000.0;
    const bool speech_ok = samples > 0 && seconds > 1.0 && seconds < 8.0;
    if (!speech_ok) ++failures;
    std::printf("%-6s speech: %.2f s%s%s\n", speech_ok ? "ok" : "FAIL", seconds, samples < 0 ? ": " : "",
                samples < 0 ? nrtts_error(h) : "");
    if (samples > 0) {
        nrtts_mp3 * e = nrtts_mp3_open(24000, 64);
        nrtts_mp3_add_speech(e, h, 300);
        const int bytes = nrtts_mp3_finish(e);
        FILE * f = std::fopen(argv[5], "wb");
        if (f) {
            std::fwrite(nrtts_mp3_bytes(e), 1, (size_t) bytes, f);
            std::fclose(f);
        }
        std::printf("       wrote %s, %d bytes, %.2f s\n", argv[5], bytes, nrtts_mp3_seconds(e));
        nrtts_mp3_close(e);
    }
    nrtts_free(h);
    return failures ? 1 : 0;
} catch (const std::exception & e) {
    std::fprintf(stderr, "test-tts: %s\n", e.what());
    return 1;
}
