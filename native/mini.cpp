// mini.cpp: KittenTTS mini 0.8 through ONNX Runtime. See mini.h.
//
// The model is a StyleTTS 2 style network in one ONNX graph, which reads
// phoneme ids, a voice's 256-d style and a speed, and writes 24 kHz audio. Its
// text front end is kittenml's: espeak-ng's IPA for the text, with stress and
// with the punctuation kept as the phonemizer package keeps it, split into
// words and marks and joined with spaces, each character looked up in the
// model's symbol table. This file reproduces that path step by step, since
// the model was trained on exactly what it produces.

#include "mini.h"

#include "espeak-ng/speak_lib.h"
#include "nlohmann/json.hpp"
#include "onnxruntime_cxx_api.h"
#include "ucd/ucd.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#define NRTTSM_VERSION "0.1.0"

namespace {

// --- UTF-8 ---------------------------------------------------------------------

std::u32string decode(const std::string & s) {
    std::u32string out;
    for (size_t i = 0; i < s.size();) {
        unsigned char c = (unsigned char) s[i];
        char32_t cp;
        int n;
        if (c < 0x80) { cp = c; n = 1; }
        else if ((c >> 5) == 6) { cp = c & 0x1f; n = 2; }
        else if ((c >> 4) == 14) { cp = c & 0x0f; n = 3; }
        else { cp = c & 0x07; n = 4; }
        for (int k = 1; k < n && i + k < s.size(); ++k) cp = (cp << 6) | ((unsigned char) s[i + k] & 0x3f);
        out.push_back(cp);
        i += n;
    }
    return out;
}

std::string encode(const std::u32string & s) {
    std::string out;
    for (char32_t c : s) {
        if (c < 0x80) out += (char) c;
        else if (c < 0x800) { out += (char) (0xc0 | (c >> 6)); out += (char) (0x80 | (c & 0x3f)); }
        else if (c < 0x10000) {
            out += (char) (0xe0 | (c >> 12)); out += (char) (0x80 | ((c >> 6) & 0x3f)); out += (char) (0x80 | (c & 0x3f));
        } else {
            out += (char) (0xf0 | (c >> 18)); out += (char) (0x80 | ((c >> 12) & 0x3f));
            out += (char) (0x80 | ((c >> 6) & 0x3f)); out += (char) (0x80 | (c & 0x3f));
        }
    }
    return out;
}

// Python's \s and \w on str
bool is_space(char32_t c) {
    return c == ' ' || (c >= 0x09 && c <= 0x0d) || (c >= 0x1c && c <= 0x1f) || c == 0x85 || c == 0xa0 ||
           c == 0x1680 || (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f ||
           c == 0x205f || c == 0x3000;
}

bool is_word(char32_t c) {
    if (c == '_') return true;
    auto g = ucd_lookup_category_group((codepoint_t) c);
    return g == UCD_CATEGORY_GROUP_L || g == UCD_CATEGORY_GROUP_N;
}

// --- phonemizer's punctuation --------------------------------------------------
// Its default marks; a run of them with the whitespace around it is one
// mark, except a , or . between two digits, which is part of a number.

const std::u32string MARKS = U";:,.!?¡¿—…\"«»“”(){}[]";

bool is_mark_at(const std::u32string & s, size_t i) {
    char32_t c = s[i];
    if (c == ',' || c == '.') {
        const bool before = i > 0 && s[i - 1] >= '0' && s[i - 1] <= '9';
        const bool after = i + 1 < s.size() && s[i + 1] >= '0' && s[i + 1] <= '9';
        return !(before && after);
    }
    return MARKS.find(c) != std::u32string::npos;
}

struct mark { std::u32string text; char position; };

// Punctuation._preserve_line: the line's chunks between its marks, and the marks
void preserve(const std::u32string & line, std::vector<std::u32string> & chunks, std::vector<mark> & marks) {
    // the matches of (\s*(?:mark)+\s*)+, left to right
    std::vector<std::pair<size_t, size_t>> spans;
    for (size_t i = 0; i < line.size();) {
        size_t j = i;
        while (j < line.size() && is_space(line[j])) ++j;
        if (j < line.size() && is_mark_at(line, j)) {
            size_t k = j;
            while (k < line.size() && (is_space(line[k]) || is_mark_at(line, k))) ++k;
            spans.push_back({i, k});
            i = k;
        } else {
            i = (j > i) ? j : i + 1;
        }
    }
    if (spans.empty()) {
        chunks.push_back(line);
        return;
    }
    if (spans.size() == 1 && spans[0].first == 0 && spans[0].second == line.size()) {
        marks.push_back({line, 'A'});
        return;
    }
    std::vector<mark> ms;
    for (size_t n = 0; n < spans.size(); ++n) {
        std::u32string text = line.substr(spans[n].first, spans[n].second - spans[n].first);
        char position = 'I';
        if (n == 0 && line.compare(0, text.size(), text) == 0) position = 'B';
        else if (n + 1 == spans.size() && line.size() >= text.size() &&
                 line.compare(line.size() - text.size(), text.size(), text) == 0) position = 'E';
        ms.push_back({text, position});
    }
    std::vector<std::u32string> pieces;
    std::u32string rest = line;
    for (auto & m : ms) {
        size_t at = rest.find(m.text);
        pieces.push_back(rest.substr(0, at));
        rest = rest.substr(at + m.text.size());
    }
    pieces.push_back(rest);
    for (auto & p : pieces) if (!p.empty()) chunks.push_back(p);
    marks = ms;
}

// Punctuation.restore with the default separator (word ' ', phone '') and no strip
std::u32string restore(std::vector<std::u32string> text, std::vector<mark> marks) {
    const std::u32string sep = U" ";
    auto ends_with_sep = [&](const std::u32string & s) { return !s.empty() && s.back() == ' '; };
    std::vector<std::u32string> out;
    while (!text.empty() || !marks.empty()) {
        if (marks.empty()) {
            for (auto line : text) {
                if (!ends_with_sep(line)) line += sep;
                out.push_back(line);
            }
            text.clear();
        } else if (text.empty()) {
            std::u32string all;
            for (auto & m : marks) all += m.text;
            out.push_back(all);
            marks.clear();
        } else {
            mark m = marks.front();
            marks.erase(marks.begin());
            if (ends_with_sep(text[0])) text[0].pop_back();
            switch (m.position) {
            case 'B':
                text[0] = m.text + text[0];
                break;
            case 'E':
                out.push_back(text[0] + m.text + (ends_with_sep(m.text) ? U"" : sep));
                text.erase(text.begin());
                break;
            case 'A':
                out.push_back(m.text + (ends_with_sep(m.text) ? U"" : sep));
                break;
            default:
                if (text.size() == 1) {
                    text[0] += m.text;
                } else {
                    std::u32string first = text[0];
                    text.erase(text.begin());
                    text[0] = first + m.text + text[0];
                }
            }
        }
    }
    std::u32string joined;
    for (auto & l : out) joined += l;
    return joined;
}

// --- espeak-ng -------------------------------------------------------------------

std::mutex espeak_mu;   // espeak-ng is one global state
bool espeak_ready = false;

void init_espeak(const std::string & data) {
    std::lock_guard<std::mutex> lk(espeak_mu);
    if (espeak_ready) return;
    if (espeak_Initialize(AUDIO_OUTPUT_RETRIEVAL, 0, data.c_str(), 0) < 0)
        throw std::runtime_error("espeak-ng couldn't start with its data at " + data);
    if (espeak_SetVoiceByName("en-us") != EE_OK) throw std::runtime_error("espeak-ng has no en-us voice");
    espeak_ready = true;
}

// EspeakWrapper.text_to_phonemes and EspeakBackend._postprocess_line, with
// stress kept and no phone separator
std::u32string espeak_phonemes(const std::u32string & chunk) {
    std::string utf8 = encode(chunk);
    std::string joined;
    {
        std::lock_guard<std::mutex> lk(espeak_mu);
        const void * p = utf8.c_str();
        const int mode = ('_' << 8) | 0x02;
        bool first = true;
        while (p) {
            const char * ph = espeak_TextToPhonemes(&p, espeakCHARS_UTF8, mode);
            if (ph && *ph) {
                if (!first) joined += ' ';
                joined += ph;
                first = false;
            }
        }
    }
    // strip, newlines to spaces, one double space to one
    auto trim = [](std::string s) {
        size_t a = s.find_first_not_of(" \t\n\r\f\v"), b = s.find_last_not_of(" \t\n\r\f\v");
        return a == std::string::npos ? std::string() : s.substr(a, b - a + 1);
    };
    std::string line = trim(joined);
    std::replace(line.begin(), line.end(), '\n', ' ');
    for (size_t at = 0; (at = line.find("  ", at)) != std::string::npos;) line.replace(at, 2, " "), at += 1;
    // _+ to _, then "_ " to " " (an espeak-ng quirk)
    std::string squeezed;
    for (char c : line) if (!(c == '_' && !squeezed.empty() && squeezed.back() == '_')) squeezed += c;
    for (size_t at = 0; (at = squeezed.find("_ ", at)) != std::string::npos;) squeezed.replace(at, 2, " ");
    if (squeezed.empty()) return U"";
    // each word stripped, its phone separators dropped, a space after it
    std::string out;
    size_t start = 0;
    while (true) {
        size_t sp = squeezed.find(' ', start);
        std::string word = trim(squeezed.substr(start, sp == std::string::npos ? std::string::npos : sp - start));
        word.erase(std::remove(word.begin(), word.end(), '_'), word.end());
        out += word + " ";
        if (sp == std::string::npos) break;
        start = sp + 1;
    }
    return decode(out);
}

// --- the model's symbols ---------------------------------------------------------

std::map<char32_t, int64_t> symbol_table() {
    // kittenml's TextCleaner, a later duplicate taking the index
    const std::string pad = "$";
    const std::string punctuation = ";:,.!?¡¿—…\"«»\"\" ";
    const std::string letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    const std::string ipa = "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ";
    std::map<char32_t, int64_t> t;
    int64_t i = 0;
    for (const auto & part : {pad, punctuation, letters, ipa})
        for (char32_t c : decode(part)) t[c] = i++;
    return t;
}

// --- voices.npz ------------------------------------------------------------------

// an uncompressed .npz's float32 arrays, by name without .npy. numpy writes
// it as a stream, so the sizes are in the central directory, not the local
// headers
std::map<std::string, std::pair<std::vector<int64_t>, std::vector<float>>> read_npz(const std::string & path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("cannot open " + path);
    std::vector<char> b((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    auto u16 = [&](size_t at) { return (uint64_t) (uint8_t) b[at] | ((uint64_t) (uint8_t) b[at + 1] << 8); };
    auto u32 = [&](size_t at) { return u16(at) | (u16(at + 2) << 16); };
    auto u64 = [&](size_t at) { return u32(at) | (u32(at + 4) << 32); };
    size_t eocd = b.size() >= 22 ? b.size() - 22 : 0;
    while (eocd > 0 && u32(eocd) != 0x06054b50) --eocd;
    if (u32(eocd) != 0x06054b50) throw std::runtime_error(path + " is not a zip file");
    const size_t entries = u16(eocd + 10);
    size_t cd = u32(eocd + 16);
    std::map<std::string, std::pair<std::vector<int64_t>, std::vector<float>>> out;
    for (size_t e = 0; e < entries; ++e) {
        if (u32(cd) != 0x02014b50) throw std::runtime_error(path + " has a broken central directory");
        const uint64_t method = u16(cd + 10), name_len = u16(cd + 28), extra_len = u16(cd + 30), comment = u16(cd + 32);
        uint64_t size = u32(cd + 20), offset = u32(cd + 42);
        std::string name(b.data() + cd + 46, name_len);
        // zip64: the sizes and offset that didn't fit, in that order
        for (size_t x = cd + 46 + name_len; x + 4 <= cd + 46 + name_len + extra_len;) {
            const uint64_t id = u16(x), len = u16(x + 2);
            if (id == 1) {
                size_t p = x + 4;
                if (u32(cd + 24) == 0xffffffff) p += 8;          // uncompressed
                if (u32(cd + 20) == 0xffffffff) { size = u64(p); p += 8; }
                if (u32(cd + 42) == 0xffffffff) { offset = u64(p); }
            }
            x += 4 + len;
        }
        cd += 46 + name_len + extra_len + comment;
        if (method != 0) throw std::runtime_error(path + " is compressed; only stored entries are read");
        const size_t data = offset + 30 + u16(offset + 26) + u16(offset + 28);
        // .npy: magic, version, header length, a dict naming dtype and shape
        if (std::memcmp(b.data() + data, "\x93NUMPY", 6) != 0) throw std::runtime_error(name + " is not a .npy");
        const int major = (uint8_t) b[data + 6];
        const size_t hlen = major == 1 ? u16(data + 8) : u32(data + 8);
        const size_t hstart = data + (major == 1 ? 10 : 12);
        std::string header(b.data() + hstart, hlen);
        if (header.find("'<f4'") == std::string::npos) throw std::runtime_error(name + " is not float32");
        std::vector<int64_t> shape;
        size_t lp = header.find('(', header.find("shape")), rp = header.find(')', lp);
        std::string dims = header.substr(lp + 1, rp - lp - 1);
        for (size_t s = 0; s < dims.size();) {
            size_t c = dims.find(',', s);
            std::string d = dims.substr(s, c == std::string::npos ? std::string::npos : c - s);
            if (d.find_first_of("0123456789") != std::string::npos) shape.push_back(std::stoll(d));
            if (c == std::string::npos) break;
            s = c + 1;
        }
        int64_t n = 1;
        for (auto d : shape) n *= d;
        if (hstart + hlen + (size_t) n * sizeof(float) > b.size()) throw std::runtime_error(name + " is truncated");
        std::vector<float> v(n);
        std::memcpy(v.data(), b.data() + hstart + hlen, n * sizeof(float));
        if (name.size() > 4 && name.compare(name.size() - 4, 4, ".npy") == 0) name.resize(name.size() - 4);
        out[name] = {shape, v};
        (void) size;
    }
    return out;
}

}  // namespace

struct nrttsm {
    std::unique_ptr<Ort::Env> env;
    std::unique_ptr<Ort::Session> session;
    std::map<std::string, std::vector<float>> voices;   // by name, rows of 256
    std::map<std::string, int> rows;
    std::map<std::string, std::string> aliases;          // Hugo -> expr-voice-4-m
    std::vector<std::string> names;
    std::map<char32_t, int64_t> symbols;
    std::vector<float> audio;
    std::vector<int64_t> durations;
    std::string error, last_error;
};

namespace {

std::u32string phonemize(const std::string & text) {
    std::vector<std::u32string> chunks;
    std::vector<mark> marks;
    preserve(decode(text), chunks, marks);
    std::vector<std::u32string> phonemized;
    for (auto & c : chunks) phonemized.push_back(espeak_phonemes(c));
    return restore(phonemized, marks);
}

// re.findall(r"\w+|[^\w\s]") joined with spaces, then the symbols, with
// the model's start and end tokens
std::vector<int64_t> tokens(nrttsm * h, const std::u32string & phonemes) {
    std::u32string spaced;
    for (size_t i = 0; i < phonemes.size();) {
        if (is_space(phonemes[i])) { ++i; continue; }
        std::u32string tok;
        if (is_word(phonemes[i])) {
            while (i < phonemes.size() && is_word(phonemes[i])) tok += phonemes[i++];
        } else {
            tok += phonemes[i++];
        }
        if (!spaced.empty()) spaced += U' ';
        spaced += tok;
    }
    std::vector<int64_t> ids = {0};
    for (char32_t c : spaced) {
        auto it = h->symbols.find(c);
        if (it != h->symbols.end()) ids.push_back(it->second);
    }
    ids.push_back(10);
    ids.push_back(0);
    return ids;
}

std::string voice_key(nrttsm * h, const std::string & name) {
    if (h->voices.count(name)) return name;
    auto it = h->aliases.find(name);
    if (it != h->aliases.end() && h->voices.count(it->second)) return it->second;
    throw std::runtime_error("no voice named " + name);
}

}  // namespace

extern "C" {

const char * nrttsm_version(void) { return NRTTSM_VERSION; }

nrttsm * nrttsm_open(const char * model, const char * voices, const char * config, const char * espeak_data,
                     int threads) {
    nrttsm * h = new (std::nothrow) nrttsm();
    if (!h) return nullptr;
    try {
        init_espeak(espeak_data);
        h->symbols = symbol_table();
        for (auto & [name, sv] : read_npz(voices)) {
            h->rows[name] = (int) sv.first.at(0);
            h->voices[name] = sv.second;
        }
        std::ifstream cf(config);
        if (!cf) throw std::runtime_error(std::string("cannot open ") + config);
        auto cj = nlohmann::json::parse(cf);
        const auto aliases = cj.value("voice_aliases", nlohmann::json::object());
        for (auto & [k, v] : aliases.items()) {
            h->aliases[k] = v.get<std::string>();
            h->names.push_back(k);
        }
        h->env = std::make_unique<Ort::Env>(ORT_LOGGING_LEVEL_ERROR, "newsroom");
        Ort::SessionOptions so;
        // past four threads the small graph spends more on handing work
        // round than it saves, most of all on a CPU with efficiency cores
        const int n = threads > 0 ? threads : (int) std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
        so.SetIntraOpNumThreads(n);
        so.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        h->session = std::make_unique<Ort::Session>(*h->env, model, so);
    } catch (const std::exception & e) {
        h->error = e.what();
    }
    return h;
}

int nrttsm_ok(nrttsm * h) { return h && h->error.empty() && h->session ? 1 : 0; }

const char * nrttsm_error(nrttsm * h) {
    if (!h) return "no engine";
    return h->error.empty() ? h->last_error.c_str() : h->error.c_str();
}

void nrttsm_free(nrttsm * h) { delete h; }

int nrttsm_voice_count(nrttsm * h) { return h ? (int) h->names.size() : 0; }
const char * nrttsm_voice_name(nrttsm * h, int i) {
    return h && i >= 0 && i < (int) h->names.size() ? h->names[i].c_str() : nullptr;
}

int nrttsm_phonemes(nrttsm * h, const char * text, char * out, int cap) {
    if (!nrttsm_ok(h)) return -1;
    std::string p = encode(phonemize(text));
    if ((int) p.size() + 1 > cap) return -1;
    std::memcpy(out, p.c_str(), p.size() + 1);
    return (int) p.size();
}

int nrttsm_tokens(nrttsm * h, const char * text, int64_t * out, int cap) {
    if (!nrttsm_ok(h)) return -1;
    auto ids = tokens(h, phonemize(text));
    if ((int) ids.size() > cap) return -1;
    std::copy(ids.begin(), ids.end(), out);
    return (int) ids.size();
}

int nrttsm_speak(nrttsm * h, const char * voice, const char * text, float speed) {
    if (!nrttsm_ok(h)) return -1;
    h->audio.clear();
    h->durations.clear();
    h->last_error.clear();
    try {
        const std::string key = voice_key(h, voice);
        auto ids = tokens(h, phonemize(text));
        // the voice's style row is picked by the text's length
        const int row = std::min((int) decode(text).size(), h->rows[key] - 1);
        std::vector<float> style(h->voices[key].begin() + (size_t) row * 256,
                                 h->voices[key].begin() + (size_t) (row + 1) * 256);
        auto mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        int64_t ids_shape[2] = {1, (int64_t) ids.size()}, style_shape[2] = {1, 256}, speed_shape[1] = {1};
        std::vector<Ort::Value> inputs;
        inputs.push_back(Ort::Value::CreateTensor<int64_t>(mem, ids.data(), ids.size(), ids_shape, 2));
        inputs.push_back(Ort::Value::CreateTensor<float>(mem, style.data(), style.size(), style_shape, 2));
        inputs.push_back(Ort::Value::CreateTensor<float>(mem, &speed, 1, speed_shape, 1));
        const char * in_names[] = {"input_ids", "style", "speed"};
        const char * out_names[] = {"waveform", "duration"};
        auto outs = h->session->Run(Ort::RunOptions{nullptr}, in_names, inputs.data(), 3, out_names, 2);
        const float * w = outs[0].GetTensorData<float>();
        const size_t n = outs[0].GetTensorTypeAndShapeInfo().GetElementCount();
        // kittenml trims the last 5000 samples, the model's tail
        const size_t keep = n > 5000 ? n - 5000 : 0;
        h->audio.assign(w, w + keep);
        auto dinfo = outs[1].GetTensorTypeAndShapeInfo();
        const size_t dn = dinfo.GetElementCount();
        if (dinfo.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) {
            const int64_t * d = outs[1].GetTensorData<int64_t>();
            h->durations.assign(d, d + dn);
        } else {
            const float * d = outs[1].GetTensorData<float>();
            for (size_t i = 0; i < dn; ++i) h->durations.push_back((int64_t) d[i]);
        }
        return (int) h->audio.size();
    } catch (const std::exception & e) {
        h->last_error = e.what();
        return -1;
    }
}

const float * nrttsm_audio(nrttsm * h) { return h ? h->audio.data() : nullptr; }
int nrttsm_audio_length(nrttsm * h) { return h ? (int) h->audio.size() : 0; }

int nrttsm_durations(nrttsm * h, int64_t * out, int cap) {
    if (!h || (int) h->durations.size() > cap) return -1;
    std::copy(h->durations.begin(), h->durations.end(), out);
    return (int) h->durations.size();
}

}  // extern "C"
