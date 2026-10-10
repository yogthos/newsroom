// newsroom_tts.cpp: KittenTTS 2 in-process. See newsroom_tts.h.
//
// The speech language model is Qwen3 1.7B over a vocabulary of text and S3
// codec tokens. A chunk's prompt is the voice's reference transcript and its
// audio as codec tokens, then the text to speak, and the model writes codec
// tokens until <|speech_end|>; the voice's speaker embedding stands in for the
// first token's. Sampling follows kittenml.kittentts2.logits: a penalty on a
// codec token held too long, a windowed repetition penalty that spares the
// tokens that end a segment, then temperature, top-k, top-p and min-p. The S3
// decoder (s3gen.cpp) turns the codec tokens into 24 kHz audio.

#include "newsroom_tts.h"

#include "llama.h"
#include "gguf.h"
#include "ggml.h"
#include "lame/lame.h"
#include "nlohmann/json.hpp"
#include "s3gen.h"
#include "safetensors.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <functional>
#include <limits>
#include <map>
#include <memory>
#include <random>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#define NRTTS_VERSION "0.1.0"

using json = nlohmann::json;

namespace {

void set_err(char * err, int cap, const std::string & msg) {
    if (err && cap > 0) {
        std::snprintf(err, (size_t) cap, "%s", msg.c_str());
    }
}

// --- GGUF, read and written raw ------------------------------------------------
// gguf.h refuses KittenTTS's file, whose TQ2_1 tensors have a type stock ggml
// doesn't know, so the conversion reads and writes the format itself.

enum { GT_UINT8, GT_INT8, GT_UINT16, GT_INT16, GT_UINT32, GT_INT32, GT_FLOAT32, GT_BOOL,
       GT_STRING, GT_ARRAY, GT_UINT64, GT_INT64, GT_FLOAT64 };

constexpr uint32_t TYPE_TQ2_1 = 43;     // the fork's ternary type
constexpr uint32_t FTYPE_Q4_0 = 2;
constexpr uint32_t FTYPE_KEY_TQ2_1 = 42;

struct reader {
    FILE * f;
    explicit reader(FILE * f) : f(f) {}
    void bytes(void * p, size_t n) {
        if (std::fread(p, 1, n, f) != n) throw std::runtime_error("the GGUF file is truncated");
    }
    template <typename T> T get() { T v; bytes(&v, sizeof v); return v; }
    std::string str() {
        uint64_t n = get<uint64_t>();
        std::string s(n, '\0');
        bytes(s.data(), n);
        return s;
    }
};

size_t scalar_size(uint32_t t) {
    switch (t) {
    case GT_UINT8: case GT_INT8: case GT_BOOL: return 1;
    case GT_UINT16: case GT_INT16: return 2;
    case GT_UINT32: case GT_INT32: case GT_FLOAT32: return 4;
    case GT_UINT64: case GT_INT64: case GT_FLOAT64: return 8;
    default: throw std::runtime_error("unknown GGUF value type " + std::to_string(t));
    }
}

// a value's raw bytes, as the file has them
void copy_value(reader & r, uint32_t t, std::vector<uint8_t> & out) {
    auto put = [&](const void * p, size_t n) {
        const uint8_t * b = (const uint8_t *) p;
        out.insert(out.end(), b, b + n);
    };
    if (t == GT_STRING) {
        std::string s = r.str();
        uint64_t n = s.size();
        put(&n, 8);
        put(s.data(), s.size());
    } else if (t == GT_ARRAY) {
        uint32_t et = r.get<uint32_t>();
        uint64_t n = r.get<uint64_t>();
        put(&et, 4);
        put(&n, 8);
        for (uint64_t i = 0; i < n; ++i) copy_value(r, et, out);
    } else {
        uint8_t buf[8];
        size_t n = scalar_size(t);
        r.bytes(buf, n);
        put(buf, n);
    }
}

// One TQ2_1 block, 256 weights, as eight Q4_0 blocks: a weight is
// scale * (code - 1) with code in {0, 1, 2}, and Q4_0 holds d * (q - 8), so
// d = scale and q = code + 7 hold it exactly.
void tq2_1_to_q4_0(const uint8_t * src, uint8_t * dst) {
    const uint8_t * qs = src;
    uint16_t d[2];
    std::memcpy(d, src + 64, 4);
    for (int g = 0; g < 2; ++g) {
        int8_t codes[128];
        for (int l = 0; l < 4; ++l)
            for (int m = 0; m < 32; ++m) {
                int c = (qs[g * 32 + m] >> (2 * l)) & 3;
                if (c == 3) throw std::runtime_error("an invalid ternary code in the model");
                codes[l * 32 + m] = (int8_t) c;
            }
        for (int b = 0; b < 4; ++b) {
            uint8_t * blk = dst + (size_t) (g * 4 + b) * 18;
            std::memcpy(blk, &d[g], 2);
            for (int j = 0; j < 16; ++j) {
                int lo = codes[b * 32 + j] + 7, hi = codes[b * 32 + 16 + j] + 7;
                blk[2 + j] = (uint8_t) (lo | (hi << 4));
            }
        }
    }
}

void convert_lm(const std::string & src_path, const std::string & dst_path) {
    FILE * f = std::fopen(src_path.c_str(), "rb");
    if (!f) throw std::runtime_error("cannot open " + src_path);
    std::unique_ptr<FILE, int (*)(FILE *)> fin(f, std::fclose);
    reader r(f);
    char magic[4];
    r.bytes(magic, 4);
    if (std::memcmp(magic, "GGUF", 4) != 0) throw std::runtime_error(src_path + " is not a GGUF file");
    const uint32_t version = r.get<uint32_t>();
    if (version != 3) throw std::runtime_error("unsupported GGUF version " + std::to_string(version));
    const uint64_t n_tensors = r.get<uint64_t>(), n_kv = r.get<uint64_t>();

    std::vector<uint8_t> kv;
    uint32_t alignment = 32;
    for (uint64_t i = 0; i < n_kv; ++i) {
        std::string key = r.str();
        uint32_t t = r.get<uint32_t>();
        uint64_t klen = key.size();
        kv.insert(kv.end(), (uint8_t *) &klen, (uint8_t *) &klen + 8);
        kv.insert(kv.end(), key.begin(), key.end());
        kv.insert(kv.end(), (uint8_t *) &t, (uint8_t *) &t + 4);
        const size_t at = kv.size();
        copy_value(r, t, kv);
        if (key == "general.alignment" && t == GT_UINT32) std::memcpy(&alignment, kv.data() + at, 4);
        if (key == "general.file_type" && t == GT_UINT32) {
            uint32_t ft;
            std::memcpy(&ft, kv.data() + at, 4);
            if (ft == FTYPE_KEY_TQ2_1) std::memcpy(kv.data() + at, &FTYPE_Q4_0, 4);
        }
    }

    struct info { std::string name; std::vector<uint64_t> dims; uint32_t type; uint64_t offset, size, out_size; };
    std::vector<info> infos(n_tensors);
    for (auto & ti : infos) {
        ti.name = r.str();
        uint32_t nd = r.get<uint32_t>();
        ti.dims.resize(nd);
        for (auto & d : ti.dims) d = r.get<uint64_t>();
        ti.type = r.get<uint32_t>();
        ti.offset = r.get<uint64_t>();
        uint64_t n = 1;
        for (auto d : ti.dims) n *= d;
        if (ti.type == TYPE_TQ2_1) {
            if (n % 256) throw std::runtime_error(ti.name + " is not a whole number of ternary blocks");
            ti.size = n / 256 * 68;
            ti.out_size = n / 256 * 8 * 18;
        } else {
            const ggml_type gt = (ggml_type) ti.type;
            if (ti.type >= GGML_TYPE_COUNT) throw std::runtime_error(ti.name + " has an unknown type");
            ti.size = ggml_row_size(gt, (int64_t) ti.dims[0]) * (n / ti.dims[0]);
            ti.out_size = ti.size;
        }
    }
    auto align = [&](uint64_t x) { return (x + alignment - 1) / alignment * alignment; };
    const long here = std::ftell(f);
    const uint64_t data_start = align((uint64_t) here);

    // the output: the same header with the new types and offsets
    std::vector<uint8_t> head;
    auto put = [&](const void * p, size_t n) {
        head.insert(head.end(), (const uint8_t *) p, (const uint8_t *) p + n);
    };
    put("GGUF", 4);
    put(&version, 4);
    put(&n_tensors, 8);
    put(&n_kv, 8);
    head.insert(head.end(), kv.begin(), kv.end());
    uint64_t offset = 0;
    std::vector<uint64_t> out_offsets(n_tensors);
    for (size_t i = 0; i < infos.size(); ++i) {
        auto & ti = infos[i];
        uint64_t len = ti.name.size();
        put(&len, 8);
        put(ti.name.data(), ti.name.size());
        uint32_t nd = (uint32_t) ti.dims.size();
        put(&nd, 4);
        for (auto d : ti.dims) put(&d, 8);
        uint32_t type = ti.type == TYPE_TQ2_1 ? (uint32_t) GGML_TYPE_Q4_0 : ti.type;
        put(&type, 4);
        out_offsets[i] = offset;
        put(&offset, 8);
        offset = align(offset + ti.out_size);
    }
    head.resize(align(head.size()), 0);

    const std::string tmp = dst_path + ".part";
    FILE * o = std::fopen(tmp.c_str(), "wb");
    if (!o) throw std::runtime_error("cannot write " + tmp);
    std::unique_ptr<FILE, int (*)(FILE *)> fout(o, std::fclose);
    auto write = [&](const void * p, size_t n) {
        if (std::fwrite(p, 1, n, o) != n) throw std::runtime_error("cannot write " + tmp);
    };
    write(head.data(), head.size());
    uint64_t written = 0;
    std::vector<uint8_t> in, out;
    for (size_t i = 0; i < infos.size(); ++i) {
        auto & ti = infos[i];
        if (st::seek(f, (int64_t) (data_start + ti.offset)) != 0) throw std::runtime_error("cannot seek in " + src_path);
        in.resize(ti.size);
        r.bytes(in.data(), in.size());
        if (written < out_offsets[i]) {
            std::vector<uint8_t> zeros(out_offsets[i] - written, 0);
            write(zeros.data(), zeros.size());
            written = out_offsets[i];
        }
        if (ti.type == TYPE_TQ2_1) {
            out.resize(ti.out_size);
            for (size_t b = 0; b < ti.size / 68; ++b) tq2_1_to_q4_0(in.data() + b * 68, out.data() + b * 144);
            write(out.data(), out.size());
        } else {
            write(in.data(), in.size());
        }
        written += ti.out_size;
    }
    fout.reset();
    fin.reset();
    if (std::rename(tmp.c_str(), dst_path.c_str()) != 0) throw std::runtime_error("cannot move " + tmp + " to " + dst_path);
}

// --- the voices ------------------------------------------------------------------

struct token_map {
    int base = 0, count = 0, speech_start = 0, speech_end = 0, text_start = 0, start = 0, stop = 0;
    int final_seg = 0, ref_text_start = 0, ref_text_end = 0, ref_speech_start = 0, ref_speech_end = 0;
};

struct voice {
    std::string name, transcript;
    std::vector<float> speaker;          // the model's width, already projected
    std::vector<int32_t> reference_tokens;
    s3gen::voice decoder;
};

std::vector<float> flat_floats(const json & j) {
    std::vector<float> out;
    std::function<void(const json &)> walk = [&](const json & x) {
        if (x.is_array()) for (auto & y : x) walk(y);
        else out.push_back(x.get<float>());
    };
    walk(j);
    return out;
}

std::vector<int32_t> flat_ints(const json & j) {
    std::vector<int32_t> out;
    std::function<void(const json &)> walk = [&](const json & x) {
        if (x.is_array()) for (auto & y : x) walk(y);
        else out.push_back(x.get<int32_t>());
    };
    walk(j);
    return out;
}

void convert_voices(const std::string & voices_path, const std::string & config_path, const std::string & dst) {
    std::ifstream vf(voices_path), cf(config_path);
    if (!vf) throw std::runtime_error("cannot open " + voices_path);
    if (!cf) throw std::runtime_error("cannot open " + config_path);
    json voices = json::parse(vf), config = json::parse(cf);

    gguf_context * g = gguf_init_empty();
    gguf_set_val_str(g, "general.architecture", "kitten-voices");
    const json & tm = config.at("token_map");
    for (auto & [k, v] : tm.items()) gguf_set_val_i32(g, ("token_map." + k).c_str(), v.get<int32_t>());
    const json gen = config.value("generation", json::object());
    gguf_set_val_str(g, "generation.emotion_control", gen.value("emotion_control", "").c_str());
    gguf_set_val_i32(g, "generation.repetition_window", gen.value("repetition_window", 50));
    gguf_set_val_bool(g, "generation.use_reference_prompt", gen.value("use_reference_prompt", true));
    gguf_set_val_i32(g, "voices.count", (int32_t) voices.size());

    size_t bytes = 0, n_tensors = 0;
    for (auto & [name, v] : voices.items()) {
        bytes += (v.at("speaker").size() + v.at("reference_tokens").size()) * 4;
        bytes += flat_ints(v.at("prompt_token")).size() * 4 + flat_floats(v.at("prompt_feat")).size() * 4;
        bytes += flat_floats(v.at("embedding")).size() * 4 + 5 * 64;
        n_tensors += 5;
    }
    ggml_init_params p = { bytes + ggml_tensor_overhead() * n_tensors, nullptr, false };
    ggml_context * ctx = ggml_init(p);
    int i = 0;
    for (auto & [name, v] : voices.items()) {
        const std::string pfx = "voice." + std::to_string(i);
        gguf_set_val_str(g, (pfx + ".name").c_str(), name.c_str());
        gguf_set_val_str(g, (pfx + ".transcript").c_str(), v.at("transcript").get<std::string>().c_str());
        auto add_f = [&](const std::string & n, const std::vector<float> & x, int64_t rows) {
            ggml_tensor * t = rows > 1 ? ggml_new_tensor_2d(ctx, GGML_TYPE_F32, (int64_t) x.size() / rows, rows)
                                       : ggml_new_tensor_1d(ctx, GGML_TYPE_F32, (int64_t) x.size());
            ggml_set_name(t, n.c_str());
            std::memcpy(t->data, x.data(), x.size() * 4);
            gguf_add_tensor(g, t);
        };
        auto add_i = [&](const std::string & n, const std::vector<int32_t> & x) {
            ggml_tensor * t = ggml_new_tensor_1d(ctx, GGML_TYPE_I32, (int64_t) x.size());
            ggml_set_name(t, n.c_str());
            std::memcpy(t->data, x.data(), x.size() * 4);
            gguf_add_tensor(g, t);
        };
        auto feat = flat_floats(v.at("prompt_feat"));
        add_f(pfx + ".speaker", flat_floats(v.at("speaker")), 1);
        add_i(pfx + ".reference_tokens", flat_ints(v.at("reference_tokens")));
        add_i(pfx + ".prompt_token", flat_ints(v.at("prompt_token")));
        add_f(pfx + ".prompt_feat", feat, (int64_t) feat.size() / 80);
        add_f(pfx + ".embedding", flat_floats(v.at("embedding")), 1);
        ++i;
    }
    const bool ok = gguf_write_to_file(g, dst.c_str(), false);
    gguf_free(g);
    ggml_free(ctx);
    if (!ok) throw std::runtime_error("cannot write " + dst);
}

// --- sampling --------------------------------------------------------------------

struct sampling {
    float temperature = 0.8f, top_p = 0.8f, min_p = 0.0f;
    int top_k = 50;
    float repetition = 1.1f;
    int window = 50;
    float run_penalty = 1.3f;
    int grace = 10;
};

constexpr int SILENCE = 4299;

void process(std::vector<float> & scores, const std::vector<int> & history, size_t prompt_size,
             const token_map & t, const sampling & s) {
    if (!history.empty() && s.run_penalty != 0) {
        const int last = history.back();
        int run = 0;
        for (auto it = history.rbegin(); it != history.rend() && *it == last; ++it) ++run;
        if (last >= t.base && last < t.base + t.count && run >= s.grace)
            scores[last] -= (float) (run - s.grace + 1) * s.run_penalty;
    }
    if (s.repetition != 1.0f) {
        size_t begin = 0;
        if (s.window > 0)
            begin = std::max(prompt_size, history.size() > (size_t) s.window ? history.size() - s.window : 0);
        std::vector<char> seen(scores.size(), 0);
        for (size_t i = begin; i < history.size(); ++i) {
            const int id = history[i];
            if (id == t.speech_end || id == t.stop || id == t.base + SILENCE || seen[id]) continue;
            seen[id] = 1;
            scores[id] = scores[id] < 0 ? scores[id] * s.repetition : scores[id] / s.repetition;
        }
    }
    for (auto & x : scores) x /= s.temperature;
    const float neg = -std::numeric_limits<float>::infinity();
    if (s.top_k > 0 && (size_t) s.top_k < scores.size()) {
        std::vector<float> sorted(scores);
        std::nth_element(sorted.begin(), sorted.begin() + s.top_k - 1, sorted.end(), std::greater<float>());
        const float cutoff = sorted[s.top_k - 1];
        for (auto & x : scores) if (x < cutoff) x = neg;
    }
    if (s.top_p < 1.0f) {
        std::vector<size_t> order;
        for (size_t i = 0; i < scores.size(); ++i) if (std::isfinite(scores[i])) order.push_back(i);
        std::sort(order.begin(), order.end(), [&](size_t a, size_t b) { return scores[a] < scores[b]; });
        const float peak = scores[order.back()];
        double sum = 0;
        for (size_t id : order) sum += std::exp((double) (scores[id] - peak));
        double acc = 0;
        for (size_t i = 0; i + 1 < order.size(); ++i) {
            const size_t id = order[i];
            acc += std::exp((double) (scores[id] - peak)) / sum;
            if (acc <= 1.0 - s.top_p) scores[id] = neg;
        }
    }
    if (s.min_p > 0) {
        const float cutoff = *std::max_element(scores.begin(), scores.end()) + std::log(s.min_p);
        for (auto & x : scores) if (x < cutoff) x = neg;
    }
}

int sample(const std::vector<float> & scores, std::mt19937 & rng) {
    const float peak = *std::max_element(scores.begin(), scores.end());
    std::vector<double> w;
    std::vector<int> ids;
    for (size_t i = 0; i < scores.size(); ++i)
        if (std::isfinite(scores[i])) {
            ids.push_back((int) i);
            w.push_back(std::exp((double) (scores[i] - peak)));
        }
    if (ids.empty()) throw std::runtime_error("no token left to sample");
    return ids[std::discrete_distribution<size_t>(w.begin(), w.end())(rng)];
}

}  // namespace

// --- the engine ------------------------------------------------------------------

struct nrtts {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    std::unique_ptr<s3gen::decoder> decoder;
    std::vector<voice> voices;
    token_map tm;
    std::string emotion_control;
    int repetition_window = 50;
    bool use_reference = true;
    int n_ctx = 0, n_embd = 0, n_vocab = 0, threads = 1;
    std::string device = "CPU";
    std::vector<float> audio;
    std::string error;       // why it couldn't be opened
    std::string last_error;  // why the last call failed

    ~nrtts() {
        if (ctx) llama_free(ctx);
        if (model) llama_model_free(model);
    }

    std::vector<int> tokenize(const std::string & text) {
        int n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, false, false);
        if (n == 0) return {};
        std::vector<int> ids(n < 0 ? -n : n);
        n = llama_tokenize(vocab, text.data(), (int32_t) text.size(), ids.data(), (int32_t) ids.size(), false, false);
        if (n < 0) throw std::runtime_error("cannot tokenize the text");
        ids.resize(n);
        return ids;
    }

    const voice & find_voice(const std::string & name) const {
        for (auto & v : voices) if (v.name == name) return v;
        throw std::runtime_error("no voice named " + name);
    }
};

namespace {

void load_voices(nrtts * h, const std::string & path) {
    ggml_context * tmp = nullptr;
    gguf_init_params gp = { false, &tmp };
    gguf_context * g = gguf_init_from_file(path.c_str(), gp);
    if (!g) throw std::runtime_error("cannot read the voices from " + path);
    auto i32 = [&](const std::string & k, int fallback) {
        int64_t id = gguf_find_key(g, k.c_str());
        return id < 0 ? fallback : (int) gguf_get_val_i32(g, id);
    };
    auto str = [&](const std::string & k) {
        int64_t id = gguf_find_key(g, k.c_str());
        return id < 0 ? std::string() : std::string(gguf_get_val_str(g, id));
    };
    token_map & t = h->tm;
    t.base = i32("token_map.audio_id_base", 0);
    t.count = i32("token_map.num_audio_tokens", 0);
    t.speech_start = i32("token_map.speech_start_id", 0);
    t.speech_end = i32("token_map.speech_end_id", 0);
    t.text_start = i32("token_map.text_start_id", 0);
    t.start = i32("token_map.start_id", 0);
    t.stop = i32("token_map.stop_id", 0);
    t.final_seg = i32("token_map.final_seg_id", 0);
    t.ref_text_start = i32("token_map.reference_text_start_id", 0);
    t.ref_text_end = i32("token_map.reference_text_end_id", 0);
    t.ref_speech_start = i32("token_map.reference_speech_start_id", 0);
    t.ref_speech_end = i32("token_map.reference_speech_end_id", 0);
    h->emotion_control = str("generation.emotion_control");
    h->repetition_window = i32("generation.repetition_window", 50);
    {
        int64_t id = gguf_find_key(g, "generation.use_reference_prompt");
        h->use_reference = id < 0 || gguf_get_val_bool(g, id);
    }
    const int n = i32("voices.count", 0);
    auto tensor = [&](const std::string & name) {
        ggml_tensor * x = ggml_get_tensor(tmp, name.c_str());
        if (!x) throw std::runtime_error("the voices file has no " + name);
        return x;
    };
    for (int i = 0; i < n; ++i) {
        const std::string pfx = "voice." + std::to_string(i);
        voice v;
        v.name = str(pfx + ".name");
        v.transcript = str(pfx + ".transcript");
        auto floats = [&](const std::string & k) {
            ggml_tensor * x = tensor(pfx + k);
            const float * p = (const float *) ggml_get_data(x);
            return std::vector<float>(p, p + ggml_nelements(x));
        };
        auto ints = [&](const std::string & k) {
            ggml_tensor * x = tensor(pfx + k);
            const int32_t * p = (const int32_t *) ggml_get_data(x);
            return std::vector<int32_t>(p, p + ggml_nelements(x));
        };
        v.speaker = floats(".speaker");
        v.reference_tokens = ints(".reference_tokens");
        v.decoder.prompt_token = ints(".prompt_token");
        v.decoder.prompt_feat = floats(".prompt_feat");
        v.decoder.prompt_rows = (int) (v.decoder.prompt_feat.size() / 80);
        v.decoder.embedding = floats(".embedding");
        h->voices.push_back(std::move(v));
    }
    gguf_free(g);
    ggml_free(tmp);
}

// the prompt for one chunk, as kittenml.kittentts2.prompt builds it
std::vector<int> build_prompt(nrtts * h, const voice & v, const std::string & text, bool expression) {
    const token_map & t = h->tm;
    std::vector<int> p = {t.start};
    auto append = [&](const std::vector<int> & ids) { p.insert(p.end(), ids.begin(), ids.end()); };
    if (h->use_reference) {
        auto ref_text = h->tokenize(v.transcript);
        const bool roles = t.ref_text_start != 0;
        p.push_back(roles ? t.ref_text_start : t.text_start);
        append(ref_text);
        if (roles) p.push_back(t.ref_text_end);
        p.push_back(roles ? t.ref_speech_start : t.speech_start);
        for (int a : v.reference_tokens) p.push_back(t.base + a);
        p.push_back(roles ? t.ref_speech_end : t.speech_end);
    }
    if (expression && !h->emotion_control.empty()) append(h->tokenize(h->emotion_control));
    p.push_back(t.text_start);
    append(h->tokenize(text));
    if (t.final_seg) p.push_back(t.final_seg);
    p.push_back(t.speech_start);
    return p;
}

// the codec tokens the model writes for `prompt`; -2 in `status` when cancelled
// the speaker embedding and the prompt, decoded into a cleared context
void prefill(nrtts * h, const voice & v, const std::vector<int> & prompt) {
    llama_memory_clear(llama_get_memory(h->ctx), true);

    // the speaker embedding stands in for the first token
    {
        llama_batch b = llama_batch_init(1, h->n_embd, 1);
        b.n_tokens = 1;
        std::copy(v.speaker.begin(), v.speaker.end(), b.embd);
        b.pos[0] = 0;
        b.n_seq_id[0] = 1;
        b.seq_id[0][0] = 0;
        b.logits[0] = false;
        const int rc = llama_decode(h->ctx, b);
        llama_batch_free(b);
        if (rc) throw std::runtime_error("the speaker prefill failed");
    }
    const int n_batch = (int) llama_n_batch(h->ctx);
    {
        llama_batch b = llama_batch_init(n_batch, 0, 1);
        for (size_t off = 1; off < prompt.size();) {
            const int n = (int) std::min((size_t) n_batch, prompt.size() - off);
            b.n_tokens = n;
            for (int i = 0; i < n; ++i) {
                b.token[i] = prompt[off + i];
                b.pos[i] = (llama_pos) (off + i);
                b.n_seq_id[i] = 1;
                b.seq_id[i][0] = 0;
                b.logits[i] = off + i + 1 == prompt.size();
            }
            if (llama_decode(h->ctx, b)) {
                llama_batch_free(b);
                throw std::runtime_error("the prompt prefill failed");
            }
            off += n;
        }
        llama_batch_free(b);
    }
}

std::vector<int32_t> generate(nrtts * h, const voice & v, const std::vector<int> & prompt, const sampling & s,
                              uint32_t seed, int budget, const int32_t * cancel, int & status) {
    status = 0;
    if ((int) prompt.size() + budget > h->n_ctx) throw std::runtime_error("the text is too long for the model's context");
    prefill(h, v, prompt);

    std::mt19937 rng(seed);
    std::vector<int> history(prompt);
    std::vector<int32_t> audio;
    llama_batch one = llama_batch_init(1, 0, 1);
    std::vector<float> scores(h->n_vocab);
    for (int step = 0; step < budget; ++step) {
        if (cancel && *(const volatile int32_t *) cancel) {
            status = -2;
            break;
        }
        const float * raw = llama_get_logits_ith(h->ctx, -1);
        std::copy(raw, raw + h->n_vocab, scores.begin());
        process(scores, history, prompt.size(), h->tm, s);
        const int token = sample(scores, rng);
        history.push_back(token);
        if (token == h->tm.speech_end || token == h->tm.stop) break;
        if (token >= h->tm.base && token < h->tm.base + h->tm.count) audio.push_back(token - h->tm.base);
        if (step + 1 < budget) {
            one.n_tokens = 1;
            one.token[0] = token;
            one.pos[0] = (llama_pos) (history.size() - 1);
            one.n_seq_id[0] = 1;
            one.seq_id[0][0] = 0;
            one.logits[0] = true;
            if (llama_decode(h->ctx, one)) {
                llama_batch_free(one);
                throw std::runtime_error("generation failed");
            }
        }
    }
    llama_batch_free(one);
    return audio;
}

}  // namespace

extern "C" {

const char * nrtts_version(void) { return NRTTS_VERSION; }

int nrtts_convert_lm(const char * src, const char * dst, char * err, int cap) {
    try {
        convert_lm(src, dst);
        return 0;
    } catch (const std::exception & e) {
        set_err(err, cap, e.what());
        return -1;
    }
}

int nrtts_convert_decoder(const char * src, const char * dst, char * err, int cap) {
    try {
        s3gen::convert_safetensors(src, dst);
        return 0;
    } catch (const std::exception & e) {
        set_err(err, cap, e.what());
        return -1;
    }
}

int nrtts_convert_voices(const char * voices_json, const char * config_json, const char * dst, char * err, int cap) {
    try {
        convert_voices(voices_json, config_json, dst);
        return 0;
    } catch (const std::exception & e) {
        set_err(err, cap, e.what());
        return -1;
    }
}

static void quiet_log(enum ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) std::fputs(text, stderr);
}

// whether the build has a GPU backend with a device that comes up
static bool have_gpu() {
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto t = ggml_backend_dev_type(ggml_backend_dev_get(i));
        if (t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU) return true;
    }
    return false;
}

// the name of the GPU the speech model goes to, as ggml calls it
static std::string gpu_name() {
    for (auto type : {GGML_BACKEND_DEVICE_TYPE_GPU, GGML_BACKEND_DEVICE_TYPE_IGPU})
        if (ggml_backend_dev_t d = ggml_backend_dev_by_type(type)) return ggml_backend_dev_name(d);
    return "CPU";
}

// Whether the decoder on the GPU gets what a CPU copy of it gets for a few
// seconds of the voice's own codec tokens, with the same noise. Every op
// passes ggml's own checks on every backend, but a driver can still get a
// whole graph wrong (MoltenVK computes one tile of the upsampled encoder
// wrong at random), and the speech would come out as noise; the speech model
// is a well-worn path through llama.cpp and stays on the GPU either way.
static bool decoder_agrees(s3gen::decoder & gpu, const char * path, int threads, const voice & v) {
    s3gen::decoder cpu(path, threads, false);
    std::vector<int32_t> tokens(v.reference_tokens.begin(),
                                v.reference_tokens.begin() + std::min<size_t>(50, v.reference_tokens.size()));
    const size_t T_mu = 2 * (v.decoder.prompt_token.size() + tokens.size() + 3);
    std::vector<float> z0(80 * T_mu);
    std::mt19937 rng(7);
    std::normal_distribution<float> gauss(0.0f, 1.0f);
    for (auto & x : z0) x = gauss(rng);
    std::vector<float> source((T_mu - v.decoder.prompt_rows) * 480, 0.0f);
    s3gen::overrides o;
    o.z0 = &z0;
    o.source = &source;
    auto a = gpu.decode(tokens, v.decoder, 1, &o), b = cpu.decode(tokens, v.decoder, 1, &o);
    if (a.size() != b.size() || a.empty()) return false;
    double dot = 0, aa = 0, bb = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        dot += (double) a[i] * b[i];
        aa += (double) a[i] * a[i];
        bb += (double) b[i] * b[i];
    }
    // Metal comes out at 0.9999; a broken graph near nothing
    return dot / std::sqrt(std::max(aa * bb, 1e-30)) > 0.995;
}

// the speech model and its context, on the GPU or kept to the CPU
static bool load_lm(nrtts * h, const char * lm, bool gpu, int n_ctx = 4096, bool repack = true) {
    static ggml_backend_dev_t no_devices[1] = {nullptr};
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = gpu ? -1 : 0;
    if (!gpu) mp.devices = no_devices;
    mp.use_extra_bufts = repack;
    h->model = llama_model_load_from_file(lm, mp);
    if (!h->model) return false;
    auto cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = h->threads;
    cp.n_threads_batch = h->threads;
    if (!gpu) {
        cp.offload_kqv = false;
        cp.op_offload = false;
    }
    h->ctx = llama_init_from_model(h->model, cp);
    if (!h->ctx) {
        llama_model_free(h->model);
        h->model = nullptr;
        return false;
    }
    return true;
}

// Whether the speech model on the GPU ranks the first token of a prompt as a
// CPU copy of it does. A driver that runs the model wrong (Mesa calls its own
// Haswell support incomplete) would otherwise make noise of every line. The
// copy maps the same file and keeps to its own weights, so it costs a prompt
// of CPU time rather than another model's memory.
static bool lm_agrees(nrtts * h, const char * lm) {
    nrtts cpu;
    cpu.threads = h->threads;
    if (!load_lm(&cpu, lm, false, 1024, false)) return false;
    cpu.n_embd = h->n_embd;
    const voice & v = h->voices.at(0);
    auto prompt = build_prompt(h, v, "Good morning, and welcome to the briefing.", false);
    auto first = [&](nrtts * x) {
        prefill(x, v, prompt);
        const float * raw = llama_get_logits_ith(x->ctx, -1);
        return std::vector<float>(raw, raw + h->n_vocab);
    };
    auto a = first(h), b = first(&cpu);
    auto top = [](const std::vector<float> & l) {
        std::vector<int> ids(l.size());
        for (size_t i = 0; i < ids.size(); ++i) ids[i] = (int) i;
        std::partial_sort(ids.begin(), ids.begin() + 10, ids.end(), [&](int x, int y) { return l[x] > l[y]; });
        ids.resize(10);
        return ids;
    };
    auto ta = top(a), tb = top(b);
    int shared = 0;
    for (int x : ta) shared += std::count(tb.begin(), tb.end(), x) > 0;
    // Metal and Vulkan share all ten with the CPU and agree on the first
    return ta[0] == tb[0] && shared >= 7;
}

nrtts * nrtts_open(const char * lm, const char * dec, const char * voices, int threads, int device) {
    nrtts * h = new (std::nothrow) nrtts();
    if (!h) return nullptr;
    try {
        llama_log_set(quiet_log, nullptr);
        h->threads = threads > 0 ? threads : (int) std::max(1u, std::thread::hardware_concurrency());
        llama_backend_init();
        const bool want_gpu = device != NRTTS_CPU && have_gpu();
        bool gpu = want_gpu;
        if (!load_lm(h, lm, gpu)) {
            // a GPU that can't take the model: the CPU, then
            if (!gpu || !load_lm(h, lm, false))
                throw std::runtime_error(std::string("cannot load the speech model from ") + lm);
            gpu = false;
        }
        h->vocab = llama_model_get_vocab(h->model);
        h->n_vocab = llama_vocab_n_tokens(h->vocab);
        h->n_embd = llama_model_n_embd(h->model);
        h->n_ctx = (int) llama_n_ctx(h->ctx);
        load_voices(h, voices);
        if (h->tm.base + h->tm.count > h->n_vocab) throw std::runtime_error("the voices' token map doesn't fit the model");
        for (auto & v : h->voices)
            if ((int) v.speaker.size() != h->n_embd) throw std::runtime_error("voice " + v.name + " doesn't fit the model");
        if (gpu && !lm_agrees(h, lm)) {
            // a GPU that gets the speech model wrong: the CPU for it
            llama_free(h->ctx);
            llama_model_free(h->model);
            h->ctx = nullptr;
            h->model = nullptr;
            if (!load_lm(h, lm, false)) throw std::runtime_error(std::string("cannot load the speech model from ") + lm);
            h->n_ctx = (int) llama_n_ctx(h->ctx);
            gpu = false;
        }
        // the decoder is checked on its own, so a GPU that gets one of the two
        // wrong still runs the other
        h->decoder = std::make_unique<s3gen::decoder>(dec, h->threads, want_gpu);
        bool dec_gpu = h->decoder->device() != "CPU";
        if (dec_gpu && !decoder_agrees(*h->decoder, dec, h->threads, h->voices.at(0))) {
            h->decoder = std::make_unique<s3gen::decoder>(dec, h->threads, false);
            dec_gpu = false;
        }
        if (gpu && dec_gpu) h->device = gpu_name();
        else if (gpu) h->device = gpu_name() + " (decoder on the CPU)";
        else if (dec_gpu) h->device = gpu_name() + " (speech model on the CPU)";
    } catch (const std::exception & e) {
        h->error = e.what();
    }
    return h;
}

const char * nrtts_device(nrtts * h) { return h ? h->device.c_str() : "none"; }

int nrtts_ok(nrtts * h) { return h && h->error.empty() && h->decoder ? 1 : 0; }
const char * nrtts_error(nrtts * h) {
    if (!h) return "no engine";
    return h->error.empty() ? h->last_error.c_str() : h->error.c_str();
}
void nrtts_free(nrtts * h) { delete h; }

int nrtts_voice_count(nrtts * h) { return h ? (int) h->voices.size() : 0; }
const char * nrtts_voice_name(nrtts * h, int i) {
    return h && i >= 0 && i < (int) h->voices.size() ? h->voices[i].name.c_str() : nullptr;
}

int nrtts_speak(nrtts * h, const char * voice_name, const char * text, int expression,
                float temperature, int top_k, float top_p, float min_p, uint32_t seed,
                int max_tokens, const int32_t * cancel) {
    if (!nrtts_ok(h)) return -1;
    h->audio.clear();
    h->last_error.clear();
    try {
        const voice & v = h->find_voice(voice_name);
        sampling s;
        s.temperature = temperature;
        s.top_k = top_k;
        s.top_p = top_p;
        s.min_p = min_p;
        s.window = h->repetition_window;
        auto prompt = build_prompt(h, v, text, expression != 0);
        int status = 0;
        auto codes = generate(h, v, prompt, s, seed, std::max(1, max_tokens), cancel, status);
        if (status == -2) return -2;
        if (codes.empty()) return 0;
        h->audio = h->decoder->decode(codes, v.decoder, seed);
        return (int) h->audio.size();
    } catch (const std::exception & e) {
        // the engine stays usable after a failed call
        h->last_error = e.what();
        h->audio.clear();
        return -1;
    }
}

int nrtts_prompt(nrtts * h, const char * voice_name, const char * text, int expression, int32_t * out, int cap) {
    if (!nrtts_ok(h)) return -1;
    try {
        auto p = build_prompt(h, h->find_voice(voice_name), text, expression != 0);
        if ((int) p.size() > cap) return -1;
        std::copy(p.begin(), p.end(), out);
        return (int) p.size();
    } catch (const std::exception & e) {
        h->last_error = e.what();
        return -1;
    }
}

int nrtts_first_logits(nrtts * h, const char * voice_name, const char * text, int expression, float * out, int cap) {
    if (!nrtts_ok(h) || cap < h->n_vocab) return -1;
    try {
        const voice & v = h->find_voice(voice_name);
        auto p = build_prompt(h, v, text, expression != 0);
        prefill(h, v, p);
        const float * raw = llama_get_logits_ith(h->ctx, -1);
        std::copy(raw, raw + h->n_vocab, out);
        return h->n_vocab;
    } catch (const std::exception & e) {
        h->last_error = e.what();
        return -1;
    }
}

int nrtts_vocab_size(nrtts * h) { return h ? h->n_vocab : 0; }

const float * nrtts_audio(nrtts * h) { return h ? h->audio.data() : nullptr; }
int nrtts_audio_length(nrtts * h) { return h ? (int) h->audio.size() : 0; }

}  // extern "C"

// --- MP3 -------------------------------------------------------------------------

struct nrtts_mp3 {
    lame_global_flags * gf = nullptr;
    std::vector<uint8_t> bytes;
    int sample_rate = 24000;
    uint64_t samples = 0;
    bool finished = false;
};

extern "C" {

nrtts_mp3 * nrtts_mp3_open(int sample_rate, int kbps) {
    auto * e = new (std::nothrow) nrtts_mp3();
    if (!e) return nullptr;
    e->sample_rate = sample_rate;
    e->gf = lame_init();
    if (!e->gf) {
        delete e;
        return nullptr;
    }
    lame_set_in_samplerate(e->gf, sample_rate);
    lame_set_out_samplerate(e->gf, sample_rate);
    lame_set_num_channels(e->gf, 1);
    lame_set_mode(e->gf, MONO);
    lame_set_brate(e->gf, kbps);
    lame_set_quality(e->gf, 2);
    if (lame_init_params(e->gf) < 0) {
        lame_close(e->gf);
        delete e;
        return nullptr;
    }
    return e;
}

int nrtts_mp3_add(nrtts_mp3 * e, const float * pcm, int n) {
    if (!e || e->finished || n < 0) return -1;
    if (n == 0) return 0;
    std::vector<uint8_t> buf((size_t) (1.25 * n) + 7200);
    int w = lame_encode_buffer_ieee_float(e->gf, pcm, pcm, n, buf.data(), (int) buf.size());
    if (w < 0) return -1;
    e->bytes.insert(e->bytes.end(), buf.begin(), buf.begin() + w);
    e->samples += (uint64_t) n;
    return w;
}

// kittenml's join_chunks: a chunk's own lead-in and trail-out silence trimmed
// (below -45 dB, keeping 20 ms), and 8 ms fades at its edges, which rarely
// fall on a zero crossing and would click
static std::vector<float> trimmed(const std::vector<float> & wav, int sr) {
    std::vector<float> w(wav);
    const int hop = std::max(1, sr / 100);
    if ((int) w.size() >= sr / 20) {
        const double thresh = std::pow(10.0, -45.0 / 20.0);
        int first = -1, last = -1;
        const int frames = (int) w.size() / hop;
        for (int f = 0; f < frames; ++f) {
            double e = 0;
            for (int i = 0; i < hop; ++i) e += (double) w[f * hop + i] * w[f * hop + i];
            if (std::sqrt(e / hop + 1e-12) > thresh) {
                if (first < 0) first = f;
                last = f;
            }
        }
        if (first >= 0) {
            const int keep = sr / 50;
            const size_t from = (size_t) std::max(0, first * hop - keep);
            const size_t to = std::min(w.size(), (size_t) ((last + 1) * hop + keep));
            w = std::vector<float>(w.begin() + from, w.begin() + to);
        }
    }
    const int fade = std::max(1, (int) (sr * 0.008));
    if ((int) w.size() > 2 * fade) {
        for (int i = 0; i < fade; ++i) {
            const float g = fade > 1 ? (float) i / (fade - 1) : 1.0f;
            w[i] *= g;
            w[w.size() - 1 - i] *= g;
        }
    }
    return w;
}

int nrtts_mp3_add_speech(nrtts_mp3 * e, nrtts * h, int gap_ms) {
    if (!e || !h) return -1;
    if (h->audio.empty()) return 0;
    auto w = trimmed(h->audio, e->sample_rate);
    if (nrtts_mp3_add(e, w.data(), (int) w.size()) < 0) return -1;
    if (gap_ms > 0) {
        std::vector<float> silence((size_t) e->sample_rate * gap_ms / 1000, 0.0f);
        if (nrtts_mp3_add(e, silence.data(), (int) silence.size()) < 0) return -1;
    }
    return 0;
}

int nrtts_mp3_finish(nrtts_mp3 * e) {
    if (!e) return -1;
    if (e->finished) return (int) e->bytes.size();
    uint8_t buf[7200];
    int w = lame_encode_flush(e->gf, buf, (int) sizeof buf);
    if (w < 0) return -1;
    e->bytes.insert(e->bytes.end(), buf, buf + w);
    e->finished = true;
    return (int) e->bytes.size();
}

const uint8_t * nrtts_mp3_bytes(nrtts_mp3 * e) { return e ? e->bytes.data() : nullptr; }
int nrtts_mp3_length(nrtts_mp3 * e) { return e ? (int) e->bytes.size() : 0; }
double nrtts_mp3_seconds(nrtts_mp3 * e) { return e ? (double) e->samples / e->sample_rate : 0.0; }

void nrtts_mp3_close(nrtts_mp3 * e) {
    if (!e) return;
    if (e->gf) lame_close(e->gf);
    delete e;
}

}  // extern "C"
