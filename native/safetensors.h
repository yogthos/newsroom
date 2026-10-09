// safetensors.h: reading a .safetensors file, its JSON header and the raw
// bytes of each tensor, for the converters and the parity tests.
#pragma once

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

#include "nlohmann/json.hpp"

namespace st {

inline int seek(FILE * f, int64_t at) {
#ifdef _WIN32
    return _fseeki64(f, at, SEEK_SET);
#else
    return fseeko(f, (off_t) at, SEEK_SET);
#endif
}

struct tensor {
    std::string          dtype;   // F32, F16, BF16, I32, I64, ...
    std::vector<int64_t> shape;   // as torch has it, outermost first
    std::vector<uint8_t> data;

    int64_t numel() const {
        int64_t n = 1;
        for (auto d : shape) n *= d;
        return n;
    }
    // the values as floats, from F32 (anything else throws)
    std::vector<float> f32() const {
        if (dtype != "F32") throw std::runtime_error("expected F32, got " + dtype);
        std::vector<float> v(numel());
        std::memcpy(v.data(), data.data(), v.size() * sizeof(float));
        return v;
    }
    std::vector<int32_t> i32() const {
        std::vector<int32_t> v(numel());
        if (dtype == "I32") {
            std::memcpy(v.data(), data.data(), v.size() * sizeof(int32_t));
        } else if (dtype == "I64") {
            const int64_t * p = reinterpret_cast<const int64_t *>(data.data());
            for (size_t i = 0; i < v.size(); ++i) v[i] = (int32_t) p[i];
        } else {
            throw std::runtime_error("expected I32 or I64, got " + dtype);
        }
        return v;
    }
};

// Every tensor in the file whose name `keep` accepts, by name.
template <typename Keep>
std::map<std::string, tensor> read(const std::string & path, Keep keep) {
    FILE * f = std::fopen(path.c_str(), "rb");
    if (!f) throw std::runtime_error("cannot open " + path);
    uint64_t n = 0;
    if (std::fread(&n, 8, 1, f) != 1 || n > (1u << 30)) {
        std::fclose(f);
        throw std::runtime_error("not a safetensors file: " + path);
    }
    std::string header(n, '\0');
    if (std::fread(header.data(), 1, n, f) != n) {
        std::fclose(f);
        throw std::runtime_error("truncated header: " + path);
    }
    const int64_t base = 8 + (int64_t) n;
    auto j = nlohmann::json::parse(header);
    std::map<std::string, tensor> out;
    for (auto & [name, info] : j.items()) {
        if (name == "__metadata__" || !keep(name)) continue;
        tensor t;
        t.dtype = info.at("dtype").get<std::string>();
        t.shape = info.at("shape").get<std::vector<int64_t>>();
        auto off = info.at("data_offsets").get<std::vector<uint64_t>>();
        t.data.resize(off[1] - off[0]);
        if (seek(f, base + (int64_t) off[0]) != 0 ||
            std::fread(t.data.data(), 1, t.data.size(), f) != t.data.size()) {
            std::fclose(f);
            throw std::runtime_error("cannot read " + name + " from " + path);
        }
        out.emplace(name, std::move(t));
    }
    std::fclose(f);
    return out;
}

inline std::map<std::string, tensor> read(const std::string & path) {
    return read(path, [](const std::string &) { return true; });
}

}  // namespace st
