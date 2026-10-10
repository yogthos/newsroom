// s3gen.h: KittenTTS 2's S3 decoder (chatterbox-turbo's S3Gen: a conformer
// encoder, a two-step meanflow decoder and the HiFT vocoder) on ggml's CPU
// backend. See s3gen.cpp.
#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace s3gen {

// A voice's conditioning for the decoder: the reference clip's codec tokens
// and its mel frames (rows of 80), and its 192-d speaker x-vector.
struct voice {
    std::vector<int32_t> prompt_token;
    std::vector<float>   prompt_feat;
    int                  prompt_rows = 0;
    std::vector<float>   embedding;
};

// What a decode can be handed instead of drawing it, for the parity tests:
// the flow's initial noise, (80, T_mu) row-major, and the vocoder's source
// excitation after its tanh, one value a sample.
struct overrides {
    const std::vector<float> * z0     = nullptr;
    const std::vector<float> * source = nullptr;
    // filled, when set, with the stages' outputs
    std::vector<float> * mu_out  = nullptr;   // (T_mu, 80)
    std::vector<float> * mel_out = nullptr;   // (80, T_mel)
    std::vector<float> * f0_out  = nullptr;   // (T_mel,)
};

struct model;

class decoder {
public:
    // Loads the GGUF that convert_safetensors writes, onto the first GPU that
    // comes up when `use_gpu`, else, or when none does, the CPU. Ops the GPU
    // can't run go to the CPU.
    decoder(const std::string & gguf_path, int n_threads, bool use_gpu = true);
    ~decoder();
    decoder(const decoder &) = delete;
    decoder & operator=(const decoder &) = delete;

    // the backend the weights are on: the GPU's name, or CPU
    std::string device() const;

    // 24 kHz audio for `tokens`, codec ids in [0, 6561); three silence tokens
    // are appended as the encoder's lookahead, as the Python decoder does.
    std::vector<float> decode(const std::vector<int32_t> & tokens, const voice & v,
                              uint32_t seed, const overrides * o = nullptr);

private:
    std::unique_ptr<model> m;
};

// chatterbox-turbo's s3gen_meanflow.safetensors to the GGUF the decoder
// loads: the flow and vocoder weights only, weight norm folded in. Throws.
void convert_safetensors(const std::string & src, const std::string & dst);

}  // namespace s3gen
