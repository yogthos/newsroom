// test_s3gen.cpp: the decoder against the upstream Python package's goldens
// (dev/tts_golden.py). It decodes the goldens' codec tokens with the voice
// conditioning, the flow's noise and the vocoder's source the Python run
// used, and compares each stage it records: the encoder's mu, the mel the
// meanflow steps arrive at, the predicted f0 and the waveform.
//
//   native/build/test-s3gen DECODER.gguf GOLDEN_DIR

#include "s3gen.h"
#include "safetensors.h"

#include <cmath>
#include <cstdio>
#include <fstream>

namespace {

struct diff {
    double max_abs = 0, rel_rms = 0, cosine = 0;
};

diff compare(const std::vector<float> & got, const std::vector<float> & want) {
    diff d;
    if (got.size() != want.size()) {
        std::fprintf(stderr, "  size %zu, expected %zu\n", got.size(), want.size());
        d.max_abs = INFINITY;
        d.rel_rms = INFINITY;
        return d;
    }
    double se = 0, sw = 0, dot = 0, sg = 0;
    for (size_t i = 0; i < got.size(); ++i) {
        const double e = (double) got[i] - want[i];
        d.max_abs = std::max(d.max_abs, std::fabs(e));
        se += e * e;
        sw += (double) want[i] * want[i];
        sg += (double) got[i] * got[i];
        dot += (double) got[i] * want[i];
    }
    d.rel_rms = std::sqrt(se / std::max(sw, 1e-30));
    d.cosine = dot / std::sqrt(std::max(sw * sg, 1e-30));
    return d;
}

int failures = 0;

void check(const char * what, const std::vector<float> & got, const std::vector<float> & want, double max_rel) {
    diff d = compare(got, want);
    const bool ok = d.rel_rms <= max_rel;
    std::printf("%-6s %s  max_abs=%.3e rel_rms=%.3e cos=%.6f (limit rel %.0e)\n",
                ok ? "ok" : "FAIL", what, d.max_abs, d.rel_rms, d.cosine, max_rel);
    if (!ok) ++failures;
}

}  // namespace

int main(int argc, char ** argv) try {
    if (argc < 3) {
        std::fprintf(stderr, "usage: test-s3gen DECODER.gguf GOLDEN_DIR\n");
        return 2;
    }
    const std::string dir = argv[2];
    auto g = st::read(dir + "/golden.safetensors");
    std::ifstream jf(dir + "/golden.json");
    auto meta = nlohmann::json::parse(jf);

    s3gen::voice v;
    v.prompt_token = g.at("prompt_token").i32();
    v.prompt_feat = g.at("prompt_feat").f32();
    v.prompt_rows = (int) g.at("prompt_feat").shape[1];
    v.embedding = g.at("embedding").f32();
    std::vector<int32_t> tokens = meta.at("audio_ids").get<std::vector<int32_t>>();

    auto z0 = g.at("step0.x").f32();
    auto source = g.at("source.merge").f32();
    std::vector<float> mu, mel, f0;
    s3gen::overrides o;
    o.z0 = &z0;
    o.source = &source;
    o.mu_out = &mu;
    o.mel_out = &mel;
    o.f0_out = &f0;

    s3gen::decoder dec(argv[1], 8);
    auto wav = dec.decode(tokens, v, 1234, &o);

    // the mel Python's second step arrives at, after the prompt's frames
    auto x1 = g.at("step1.x").f32(), out1 = g.at("step1.out").f32();
    const int T_mu = (int) g.at("step1.x").shape[2];
    const int rows = v.prompt_rows, T_mel = T_mu - rows;
    std::vector<float> want_mel((size_t) 80 * T_mel);
    for (int c = 0; c < 80; ++c)
        for (int t = 0; t < T_mel; ++t) {
            const size_t i = (size_t) c * T_mu + rows + t;
            want_mel[(size_t) c * T_mel + t] = x1[i] + 0.5f * out1[i];
        }

    check("mu", mu, g.at("mu").f32(), 1e-4);
    check("mel", mel, want_mel, 1e-3);
    check("f0", f0, g.at("f0").f32(), 2e-3);
    check("wav", wav, g.at("wav").f32(), 1e-2);
    return failures ? 1 : 0;
} catch (const std::exception & e) {
    std::fprintf(stderr, "test-s3gen: %s\n", e.what());
    return 1;
}
