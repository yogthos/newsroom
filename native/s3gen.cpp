// s3gen.cpp: KittenTTS 2's S3 decoder on ggml's CPU backend.
//
// KittenTTS 2's language model writes S3 codec tokens, and chatterbox-turbo's
// S3Gen turns them into 24 kHz audio, as kittenml.kittentts2.vocoder does:
//
//   tokens  = prompt_token ++ speech tokens ++ 3 silence tokens
//   mu      = encoder(input_embedding(tokens))      conformer, upsampled 2x
//   spks    = affine(normalize(x-vector))
//   cond    = prompt_feat over the prompt's frames, zero after
//   z       = noise; two meanflow euler steps of the U-Net estimator
//   mel     = z after the prompt's frames
//   wav     = HiFT(mel, source(f0(mel)))           NSF source + iSTFT
//
// The graphs are adapted from chatterbox.cpp by Gianfranco Cordella, a ggml
// port of Chatterbox (https://github.com/gianni-cor/chatterbox.cpp, MIT
// License, Copyright (c) 2026 Gianfranco Cordella), cut down to the turbo
// decoder on the CPU, with the weights read from a GGUF written by
// convert_safetensors below.

#include "s3gen.h"

#include "ggml.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "gguf.h"
#include "safetensors.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <random>
#include <regex>
#include <stdexcept>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace s3gen {

namespace {

constexpr int D = 512;           // the encoder's width
constexpr int MEL = 80;
constexpr int SILENCE = 4299;    // the codec's silence token
constexpr int VOCAB = 6561;
constexpr int LOOKAHEAD = 3;     // silence tokens appended for the encoder
constexpr int SR = 24000;

}  // namespace

struct model {
    ggml_backend_t        backend = nullptr;
    ggml_context *        ctx_w = nullptr;
    ggml_backend_buffer_t buffer_w = nullptr;
    std::map<std::string, ggml_tensor *> tensors;
    int n_threads = 1;
    // what the vocoder needs on the CPU side
    std::vector<float> l_linear_w;
    float l_linear_b = 0.0f;

    ~model() {
        if (buffer_w) ggml_backend_buffer_free(buffer_w);
        if (ctx_w) ggml_free(ctx_w);
        if (backend) ggml_backend_free(backend);
    }

    ggml_tensor * get(const std::string & name) const {
        auto it = tensors.find(name);
        if (it == tensors.end()) throw std::runtime_error("s3gen: no tensor " + name);
        return it->second;
    }

    std::vector<float> read(const std::string & name) const {
        ggml_tensor * t = get(name);
        std::vector<float> v(ggml_nelements(t));
        ggml_backend_tensor_get(t, v.data(), 0, ggml_nbytes(t));
        return v;
    }

    void compute(ggml_cgraph * gf) const {
        ggml_backend_cpu_set_n_threads(backend, n_threads);
        ggml_backend_graph_compute(backend, gf);
    }
};

namespace {

// A graph's context and allocator, freed with it.
struct graph {
    std::vector<uint8_t> buf;
    ggml_context * ctx = nullptr;
    ggml_cgraph * gf = nullptr;
    ggml_gallocr_t allocr = nullptr;

    graph(size_t meta_bytes, size_t nodes) : buf(meta_bytes) {
        ggml_init_params p = { buf.size(), buf.data(), true };
        ctx = ggml_init(p);
        gf = ggml_new_graph_custom(ctx, nodes, false);
    }
    ~graph() {
        if (allocr) ggml_gallocr_free(allocr);
        if (ctx) ggml_free(ctx);
    }
    ggml_tensor * input(ggml_tensor * t, const char * name) {
        ggml_set_name(t, name);
        ggml_set_input(t);
        return t;
    }
    void build(const model & m, ggml_tensor * out) {
        ggml_set_output(out);
        ggml_build_forward_expand(gf, out);
        allocr = ggml_gallocr_new(ggml_backend_get_default_buffer_type(m.backend));
        if (!ggml_gallocr_alloc_graph(allocr, gf)) throw std::runtime_error("s3gen: cannot allocate a graph");
    }
    void set(const char * name, const void * data, size_t bytes) {
        ggml_backend_tensor_set(ggml_graph_get_tensor(gf, name), data, 0, bytes);
    }
    std::vector<float> get(ggml_tensor * t) {
        std::vector<float> v(ggml_nelements(t));
        ggml_backend_tensor_get(t, v.data(), 0, ggml_nbytes(t));
        return v;
    }
};

// conv1d over x ne=[L, IC], kernel ne=[K, IC, OC]: ne=[L_out, OC]
ggml_tensor * conv1d(ggml_context * ctx, ggml_tensor * kernel, ggml_tensor * x,
                     int stride, int padding, int dilation) {
    ggml_tensor * cols = ggml_im2col(ctx, kernel, x, stride, 0, padding, 0, dilation, 0, false, GGML_TYPE_F32);
    ggml_tensor * y = ggml_mul_mat(ctx,
        ggml_reshape_2d(ctx, cols, cols->ne[0], cols->ne[2] * cols->ne[1]),
        ggml_reshape_2d(ctx, kernel, kernel->ne[0] * kernel->ne[1], kernel->ne[2]));
    return ggml_reshape_3d(ctx, y, cols->ne[1], kernel->ne[2], cols->ne[2]);
}

ggml_tensor * conv1d_b(ggml_context * ctx, ggml_tensor * kernel, ggml_tensor * bias, ggml_tensor * x,
                       int stride, int padding, int dilation) {
    ggml_tensor * y = conv1d(ctx, kernel, x, stride, padding, dilation);
    return ggml_add(ctx, y, ggml_reshape_2d(ctx, bias, 1, kernel->ne[2]));
}

ggml_tensor * conv_transpose1d(ggml_context * ctx, ggml_tensor * kernel, ggml_tensor * x,
                               int stride, int padding) {
    ggml_tensor * out = ggml_conv_transpose_1d(ctx, kernel, x, stride, 0, 1);
    if (padding == 0) return out;
    ggml_tensor * v = ggml_view_3d(ctx, out, out->ne[0] - 2 * padding, out->ne[1], out->ne[2],
                                   out->nb[1], out->nb[2], (size_t) padding * out->nb[0]);
    return ggml_cont(ctx, v);
}

// zeros before and after dim 0
ggml_tensor * zero_pad(ggml_context * ctx, ggml_tensor * x, int front, int back) {
    return ggml_pad_ext(ctx, x, front, back, 0, 0, 0, 0, 0, 0);
}

ggml_tensor * mish(ggml_context * ctx, ggml_tensor * x) {
    return ggml_mul(ctx, x, ggml_tanh(ctx, ggml_softplus(ctx, x)));
}

ggml_tensor * layer_norm(ggml_context * ctx, ggml_tensor * x, ggml_tensor * w, ggml_tensor * b, float eps) {
    return ggml_add(ctx, ggml_mul(ctx, ggml_norm(ctx, x, eps), w), b);
}

// LayerNorm over the channels of x ne=[T, C]
ggml_tensor * layer_norm_channels(ggml_context * ctx, ggml_tensor * x, ggml_tensor * w, ggml_tensor * b) {
    ggml_tensor * xt = ggml_cont(ctx, ggml_transpose(ctx, x));
    xt = layer_norm(ctx, xt, w, b, 1e-5f);
    return ggml_cont(ctx, ggml_transpose(ctx, xt));
}

ggml_tensor * linear(ggml_context * ctx, ggml_tensor * w, ggml_tensor * b, ggml_tensor * x) {
    ggml_tensor * y = ggml_mul_mat(ctx, w, x);
    return b ? ggml_add(ctx, y, b) : y;
}

// --- the encoder ---------------------------------------------------------------

struct conformer_w {
    ggml_tensor *norm_mha_w, *norm_mha_b, *norm_ff_w, *norm_ff_b;
    ggml_tensor *q_w, *q_b, *k_w, *k_b, *v_w, *v_b, *o_w, *o_b;
    ggml_tensor *pos_w, *pos_bias_u, *pos_bias_v;
    ggml_tensor *ff1_w, *ff1_b, *ff2_w, *ff2_b;
};

conformer_w load_conformer(const model & m, const std::string & p) {
    return {m.get(p + "/norm_mha/w"), m.get(p + "/norm_mha/b"), m.get(p + "/norm_ff/w"), m.get(p + "/norm_ff/b"),
            m.get(p + "/attn/q/w"), m.get(p + "/attn/q/b"), m.get(p + "/attn/k/w"), m.get(p + "/attn/k/b"),
            m.get(p + "/attn/v/w"), m.get(p + "/attn/v/b"), m.get(p + "/attn/o/w"), m.get(p + "/attn/o/b"),
            m.get(p + "/attn/pos/w"), m.get(p + "/attn/pos_bias_u"), m.get(p + "/attn/pos_bias_v"),
            m.get(p + "/ff/w1/w"), m.get(p + "/ff/w1/b"), m.get(p + "/ff/w2/w"), m.get(p + "/ff/w2/b")};
}

// A conformer layer with relative-position self-attention and no convolution
// module, x ne=[D, T]: ESPnet's RelPositionMultiHeadedAttention, whose
// rel_shift is a view of the scores padded by one column.
ggml_tensor * conformer(ggml_context * ctx, const conformer_w & w, ggml_tensor * x, ggml_tensor * pos_emb,
                        int T, int H, int HD) {
    const float eps = 1e-12f;
    ggml_tensor * xn = layer_norm(ctx, x, w.norm_mha_w, w.norm_mha_b, eps);
    ggml_tensor * q = linear(ctx, w.q_w, w.q_b, xn);
    ggml_tensor * k = linear(ctx, w.k_w, w.k_b, xn);
    ggml_tensor * v = linear(ctx, w.v_w, w.v_b, xn);
    ggml_tensor * p = ggml_mul_mat(ctx, w.pos_w, pos_emb);

    auto heads = [&](ggml_tensor * t, int64_t n) {
        return ggml_cont(ctx, ggml_permute(ctx, ggml_reshape_3d(ctx, t, HD, H, n), 0, 2, 1, 3));
    };
    q = heads(q, T);
    k = heads(k, T);
    v = heads(v, T);
    p = heads(p, pos_emb->ne[1]);

    ggml_tensor * q_u = ggml_add(ctx, q, ggml_reshape_3d(ctx, w.pos_bias_u, HD, 1, H));
    ggml_tensor * q_v = ggml_add(ctx, q, ggml_reshape_3d(ctx, w.pos_bias_v, HD, 1, H));
    ggml_tensor * ac = ggml_mul_mat(ctx, k, q_u);                 // [T, T, H]
    ggml_tensor * bd = ggml_mul_mat(ctx, p, q_v);                 // [2T-1, T, H]
    bd = zero_pad(ctx, bd, 1, 0);                                 // [2T, T, H]
    bd = ggml_reshape_3d(ctx, bd, T, 2 * T, H);
    bd = ggml_view_3d(ctx, bd, T, 2 * T - 1, H, bd->nb[1], bd->nb[2], bd->nb[1]);
    bd = ggml_reshape_3d(ctx, ggml_cont(ctx, bd), 2 * T - 1, T, H);
    bd = ggml_cont(ctx, ggml_view_3d(ctx, bd, T, T, H, bd->nb[1], bd->nb[2], 0));

    ggml_tensor * scores = ggml_scale(ctx, ggml_add(ctx, ac, bd), 1.0f / std::sqrt((float) HD));
    ggml_tensor * attn = ggml_soft_max(ctx, scores);
    ggml_tensor * vt = ggml_cont(ctx, ggml_permute(ctx, v, 1, 0, 2, 3));
    ggml_tensor * out = ggml_mul_mat(ctx, vt, attn);              // [HD, T, H]
    out = ggml_reshape_2d(ctx, ggml_cont(ctx, ggml_permute(ctx, out, 0, 2, 1, 3)), HD * H, T);
    x = ggml_add(ctx, x, linear(ctx, w.o_w, w.o_b, out));

    xn = layer_norm(ctx, x, w.norm_ff_w, w.norm_ff_b, eps);
    ggml_tensor * ff = ggml_silu(ctx, linear(ctx, w.ff1_w, w.ff1_b, xn));
    return ggml_add(ctx, x, linear(ctx, w.ff2_w, w.ff2_b, ff));
}

// ESPnet's relative positional encoding for T positions, ne=[D, 2T-1]:
// positions T-1 down to 0, then -1 down to -(T-1).
std::vector<float> rel_pos_emb(int T) {
    std::vector<float> pe((size_t) (2 * T - 1) * D);
    std::vector<float> div(D / 2);
    for (int i = 0; i < D / 2; ++i) div[i] = std::exp(-((float) (2 * i) * std::log(10000.0f) / (float) D));
    for (int r = 0; r < 2 * T - 1; ++r) {
        const float pos = (float) (T - 1 - r);
        for (int k = 0; k < D / 2; ++k) {
            pe[(size_t) r * D + 2 * k] = std::sin(pos * div[k]);
            pe[(size_t) r * D + 2 * k + 1] = std::cos(pos * div[k]);
        }
    }
    return pe;
}

// tokens embedded, ne=[D, T] -> mu ne=[80, 2T]
std::vector<float> run_encoder(const model & m, const std::vector<float> & embedded, int T) {
    const int H = 8, HD = 64, T2 = 2 * T;
    graph g(64u << 20, 32768);
    ggml_context * ctx = g.ctx;
    ggml_tensor * x_in = g.input(ggml_new_tensor_2d(ctx, GGML_TYPE_F32, D, T), "x_in");
    ggml_tensor * pos1 = g.input(ggml_new_tensor_2d(ctx, GGML_TYPE_F32, D, 2 * T - 1), "pos1");
    ggml_tensor * pos2 = g.input(ggml_new_tensor_2d(ctx, GGML_TYPE_F32, D, 2 * T2 - 1), "pos2");

    auto embed = [&](ggml_tensor * x, const std::string & p) {
        x = linear(ctx, m.get(p + "/linear/w"), m.get(p + "/linear/b"), x);
        x = layer_norm(ctx, x, m.get(p + "/norm/w"), m.get(p + "/norm/b"), 1e-5f);
        return ggml_scale(ctx, x, std::sqrt((float) D));
    };
    ggml_tensor * x = embed(x_in, "flow/encoder/embed");

    // the pre-lookahead layer: a conv over the next three frames, then one
    // over the last two, and the residual
    ggml_tensor * xt = ggml_cont(ctx, ggml_transpose(ctx, x));
    xt = conv1d_b(ctx, m.get("flow/encoder/pre_lookahead/conv1/w"), m.get("flow/encoder/pre_lookahead/conv1/b"),
                  zero_pad(ctx, xt, 0, 3), 1, 0, 1);
    xt = ggml_leaky_relu(ctx, xt, 0.01f, false);
    xt = conv1d_b(ctx, m.get("flow/encoder/pre_lookahead/conv2/w"), m.get("flow/encoder/pre_lookahead/conv2/b"),
                  zero_pad(ctx, xt, 2, 0), 1, 0, 1);
    x = ggml_add(ctx, ggml_cont(ctx, ggml_transpose(ctx, xt)), x);

    for (int i = 0; i < 6; ++i)
        x = conformer(ctx, load_conformer(m, "flow/encoder/block" + std::to_string(i)), x, pos1, T, H, HD);

    // nearest-neighbour upsampling by 2, then a causal conv of 5
    ggml_tensor * xu = ggml_cont(ctx, ggml_transpose(ctx, x));                // [T, D]
    ggml_tensor * x3 = ggml_reshape_3d(ctx, xu, 1, T, D);
    xu = ggml_reshape_2d(ctx, ggml_cont(ctx, ggml_concat(ctx, x3, x3, 0)), T2, D);
    xu = conv1d_b(ctx, m.get("flow/encoder/up_layer/conv/w"), m.get("flow/encoder/up_layer/conv/b"),
                  zero_pad(ctx, xu, 4, 0), 1, 0, 1);
    x = embed(ggml_cont(ctx, ggml_transpose(ctx, xu)), "flow/encoder/up_embed");

    for (int i = 0; i < 4; ++i)
        x = conformer(ctx, load_conformer(m, "flow/encoder/up_block" + std::to_string(i)), x, pos2, T2, H, HD);

    x = layer_norm(ctx, x, m.get("flow/encoder/after_norm/w"), m.get("flow/encoder/after_norm/b"), 1e-5f);
    ggml_tensor * mu = linear(ctx, m.get("flow/encoder_proj/w"), m.get("flow/encoder_proj/b"), x);
    g.build(m, mu);

    g.set("x_in", embedded.data(), embedded.size() * sizeof(float));
    auto pe1 = rel_pos_emb(T), pe2 = rel_pos_emb(T2);
    g.set("pos1", pe1.data(), pe1.size() * sizeof(float));
    g.set("pos2", pe2.data(), pe2.size() * sizeof(float));
    m.compute(g.gf);
    return g.get(mu);
}

// --- the meanflow estimator ------------------------------------------------------

struct resnet_w {
    ggml_tensor *b1_conv_w, *b1_conv_b, *b1_ln_w, *b1_ln_b;
    ggml_tensor *b2_conv_w, *b2_conv_b, *b2_ln_w, *b2_ln_b;
    ggml_tensor *mlp_w, *mlp_b, *res_w, *res_b;
};

resnet_w load_resnet(const model & m, const std::string & p) {
    return {m.get(p + "/block1/block/0/weight"), m.get(p + "/block1/block/0/bias"),
            m.get(p + "/block1/block/2/weight"), m.get(p + "/block1/block/2/bias"),
            m.get(p + "/block2/block/0/weight"), m.get(p + "/block2/block/0/bias"),
            m.get(p + "/block2/block/2/weight"), m.get(p + "/block2/block/2/bias"),
            m.get(p + "/mlp/1/weight"), m.get(p + "/mlp/1/bias"),
            m.get(p + "/res_conv/weight"), m.get(p + "/res_conv/bias")};
}

struct tfm_w {
    ggml_tensor *norm1_w, *norm1_b, *to_q, *to_k, *to_v, *to_out_w, *to_out_b;
    ggml_tensor *norm3_w, *norm3_b, *ff0_w, *ff0_b, *ff2_w, *ff2_b;
};

tfm_w load_tfm(const model & m, const std::string & p) {
    return {m.get(p + "/norm1/weight"), m.get(p + "/norm1/bias"),
            m.get(p + "/attn1/to_q/weight"), m.get(p + "/attn1/to_k/weight"), m.get(p + "/attn1/to_v/weight"),
            m.get(p + "/attn1/to_out/0/weight"), m.get(p + "/attn1/to_out/0/bias"),
            m.get(p + "/norm3/weight"), m.get(p + "/norm3/bias"),
            m.get(p + "/ff/net/0/proj/weight"), m.get(p + "/ff/net/0/proj/bias"),
            m.get(p + "/ff/net/2/weight"), m.get(p + "/ff/net/2/bias")};
}

// a causal conv of 3, x ne=[T, C]
ggml_tensor * causal_k3(ggml_context * ctx, ggml_tensor * x, ggml_tensor * w, ggml_tensor * b) {
    return conv1d_b(ctx, w, b, zero_pad(ctx, x, 2, 0), 1, 0, 1);
}

ggml_tensor * causal_block(ggml_context * ctx, ggml_tensor * x, ggml_tensor * conv_w, ggml_tensor * conv_b,
                           ggml_tensor * ln_w, ggml_tensor * ln_b) {
    return mish(ctx, layer_norm_channels(ctx, causal_k3(ctx, x, conv_w, conv_b), ln_w, ln_b));
}

ggml_tensor * resnet(ggml_context * ctx, const resnet_w & w, ggml_tensor * x, ggml_tensor * t_emb) {
    ggml_tensor * h = causal_block(ctx, x, w.b1_conv_w, w.b1_conv_b, w.b1_ln_w, w.b1_ln_b);
    ggml_tensor * t = linear(ctx, w.mlp_w, w.mlp_b, mish(ctx, t_emb));
    h = ggml_add(ctx, h, ggml_reshape_2d(ctx, t, 1, t->ne[0]));
    h = causal_block(ctx, h, w.b2_conv_w, w.b2_conv_b, w.b2_ln_w, w.b2_ln_b);
    return ggml_add(ctx, h, conv1d_b(ctx, w.res_w, w.res_b, x, 1, 0, 1));
}

// diffusers' BasicTransformerBlock with self-attention only, x ne=[C, T]
ggml_tensor * transformer(ggml_context * ctx, const tfm_w & w, ggml_tensor * x, int T, int H = 8, int HD = 64) {
    const int INNER = H * HD;
    ggml_tensor * nx = layer_norm(ctx, x, w.norm1_w, w.norm1_b, 1e-5f);
    const size_t col = (size_t) INNER * sizeof(float), head = (size_t) HD * sizeof(float);
    ggml_tensor * q = ggml_view_3d(ctx, ggml_mul_mat(ctx, w.to_q, nx), HD, T, H, col, head, 0);
    ggml_tensor * k = ggml_view_3d(ctx, ggml_mul_mat(ctx, w.to_k, nx), HD, T, H, col, head, 0);
    ggml_tensor * v = ggml_view_3d(ctx, ggml_mul_mat(ctx, w.to_v, nx), HD, T, H, col, head, 0);
    ggml_tensor * a = ggml_flash_attn_ext(ctx, q, k, v, nullptr, 1.0f / std::sqrt((float) HD), 0.0f, 0.0f);
    x = ggml_add(ctx, x, linear(ctx, w.to_out_w, w.to_out_b, ggml_reshape_2d(ctx, a, INNER, T)));
    ggml_tensor * ff = ggml_gelu_erf(ctx, linear(ctx, w.ff0_w, w.ff0_b, layer_norm(ctx, x, w.norm3_w, w.norm3_b, 1e-5f)));
    return ggml_add(ctx, x, linear(ctx, w.ff2_w, w.ff2_b, ff));
}

// four transformer blocks over x ne=[T, C]
ggml_tensor * transformers(ggml_context * ctx, const model & m, const std::string & p, ggml_tensor * x, int T) {
    ggml_tensor * xt = ggml_cont(ctx, ggml_transpose(ctx, x));
    for (int i = 0; i < 4; ++i) xt = transformer(ctx, load_tfm(m, p + "/" + std::to_string(i)), xt, T);
    return ggml_cont(ctx, ggml_transpose(ctx, xt));
}

// The sinusoidal embedding of a time and the time MLP: (1024,)
std::vector<float> time_mlp(const model & m, float t) {
    const int TDIM = 320;
    std::vector<float> sinus(TDIM);
    const float f = std::log(10000.0f) / (float) (TDIM / 2 - 1);
    for (int i = 0; i < TDIM / 2; ++i) {
        float arg = 1000.0f * t * std::exp(-(float) i * f);
        sinus[i] = std::sin(arg);
        sinus[i + TDIM / 2] = std::cos(arg);
    }
    graph g(4u << 20, 1024);
    ggml_tensor * x = g.input(ggml_new_tensor_1d(g.ctx, GGML_TYPE_F32, TDIM), "x");
    ggml_tensor * y = linear(g.ctx, m.get("cfm/time_mlp/linear_1/weight"), m.get("cfm/time_mlp/linear_1/bias"), x);
    y = linear(g.ctx, m.get("cfm/time_mlp/linear_2/weight"), m.get("cfm/time_mlp/linear_2/bias"), ggml_silu(g.ctx, y));
    g.build(m, y);
    g.set("x", sinus.data(), sinus.size() * sizeof(float));
    m.compute(g.gf);
    return g.get(y);
}

// t's and r's embeddings mixed, the meanflow time conditioning: (1024,)
std::vector<float> time_mixed(const model & m, float t, float r) {
    auto te = time_mlp(m, t), re = time_mlp(m, r);
    graph g(4u << 20, 1024);
    ggml_tensor * a = g.input(ggml_new_tensor_1d(g.ctx, GGML_TYPE_F32, (int64_t) te.size()), "t");
    ggml_tensor * b = g.input(ggml_new_tensor_1d(g.ctx, GGML_TYPE_F32, (int64_t) re.size()), "r");
    ggml_tensor * y = ggml_mul_mat(g.ctx, m.get("cfm/time_embed_mixer/weight"), ggml_concat(g.ctx, a, b, 0));
    g.build(m, y);
    g.set("t", te.data(), te.size() * sizeof(float));
    g.set("r", re.data(), re.size() * sizeof(float));
    m.compute(g.gf);
    return g.get(y);
}

// The estimator's graph for T frames, built once and run for each step.
struct estimator {
    graph g;
    ggml_tensor * out = nullptr;

    estimator(const model & m, int T) : g(64u << 20, 65536) {
        ggml_context * ctx = g.ctx;
        const int TIME = 1024;
        ggml_tensor * x = g.input(ggml_new_tensor_2d(ctx, GGML_TYPE_F32, T, MEL), "x");
        ggml_tensor * mu = g.input(ggml_new_tensor_2d(ctx, GGML_TYPE_F32, T, MEL), "mu");
        ggml_tensor * spks = g.input(ggml_new_tensor_1d(ctx, GGML_TYPE_F32, MEL), "spks");
        ggml_tensor * cond = g.input(ggml_new_tensor_2d(ctx, GGML_TYPE_F32, T, MEL), "cond");
        ggml_tensor * t = g.input(ggml_new_tensor_1d(ctx, GGML_TYPE_F32, TIME), "t");

        ggml_tensor * z = ggml_concat(ctx, x, mu, 1);
        z = ggml_concat(ctx, z, ggml_repeat(ctx, ggml_reshape_2d(ctx, spks, 1, MEL), x), 1);
        z = ggml_concat(ctx, z, cond, 1);

        z = resnet(ctx, load_resnet(m, "cfm/down_blocks/0/0"), z, t);
        z = transformers(ctx, m, "cfm/down_blocks/0/1", z, T);
        ggml_tensor * skip = z;
        z = causal_k3(ctx, z, m.get("cfm/down_blocks/0/2/weight"), m.get("cfm/down_blocks/0/2/bias"));
        for (int i = 0; i < 12; ++i) {
            const std::string p = "cfm/mid_blocks/" + std::to_string(i);
            z = resnet(ctx, load_resnet(m, p + "/0"), z, t);
            z = transformers(ctx, m, p + "/1", z, T);
        }
        z = ggml_concat(ctx, z, skip, 1);
        z = resnet(ctx, load_resnet(m, "cfm/up_blocks/0/0"), z, t);
        z = transformers(ctx, m, "cfm/up_blocks/0/1", z, T);
        z = causal_k3(ctx, z, m.get("cfm/up_blocks/0/2/weight"), m.get("cfm/up_blocks/0/2/bias"));
        z = causal_block(ctx, z, m.get("cfm/final_block/block/0/weight"), m.get("cfm/final_block/block/0/bias"),
                         m.get("cfm/final_block/block/2/weight"), m.get("cfm/final_block/block/2/bias"));
        out = conv1d_b(ctx, m.get("cfm/final_proj/weight"), m.get("cfm/final_proj/bias"), z, 1, 0, 1);
        g.build(m, out);
    }

    // dx/dt, ne=[T, 80], each input in the same layout
    std::vector<float> run(const model & m, const std::vector<float> & x, const std::vector<float> & mu,
                           const std::vector<float> & spks, const std::vector<float> & cond,
                           const std::vector<float> & t_emb) {
        g.set("x", x.data(), x.size() * sizeof(float));
        g.set("mu", mu.data(), mu.size() * sizeof(float));
        g.set("spks", spks.data(), spks.size() * sizeof(float));
        g.set("cond", cond.data(), cond.size() * sizeof(float));
        g.set("t", t_emb.data(), t_emb.size() * sizeof(float));
        m.compute(g.gf);
        return g.get(out);
    }
};

// --- the HiFT vocoder ----------------------------------------------------------

constexpr int N_FFT = 16, HOP = 4, F = N_FFT / 2 + 1;

std::vector<float> hann(int n) {
    std::vector<float> w(n);
    for (int i = 0; i < n; ++i) w[i] = (float) (0.5 * (1.0 - std::cos(2.0 * M_PI * i / n)));
    return w;
}

// mel ne=[T, 80] -> f0 (T,)
std::vector<float> run_f0(const model & m, const std::vector<float> & mel, int T) {
    graph g(8u << 20, 1024);
    ggml_tensor * x = g.input(ggml_new_tensor_2d(g.ctx, GGML_TYPE_F32, T, MEL), "mel");
    for (int i = 0; i < 5; ++i) {
        const std::string p = "hift/f0_predictor/condnet/" + std::to_string(i * 2);
        x = ggml_elu(g.ctx, conv1d_b(g.ctx, m.get(p + "/weight"), m.get(p + "/bias"), x, 1, 1, 1));
    }
    ggml_tensor * y = linear(g.ctx, m.get("hift/f0_predictor/classifier/weight"),
                             m.get("hift/f0_predictor/classifier/bias"), ggml_cont(g.ctx, ggml_transpose(g.ctx, x)));
    y = ggml_reshape_1d(g.ctx, ggml_abs(g.ctx, y), T);
    g.build(m, y);
    g.set("mel", mel.data(), mel.size() * sizeof(float));
    m.compute(g.gf);
    return g.get(y);
}

// SourceModuleHnNSF: the sine of f0 and its eight harmonics, each with a
// random phase but the first, noise, and their tanh'd mix
std::vector<float> nsf_source(const model & m, const std::vector<float> & f0_up, uint32_t seed) {
    const int H = 9;
    const float sine_amp = 0.1f, noise_std = 0.003f, voiced_threshold = 10.0f;
    const size_t n = f0_up.size();
    std::mt19937 rng(seed);
    std::uniform_real_distribution<float> uniform(-(float) M_PI, (float) M_PI);
    std::normal_distribution<float> gauss(0.0f, 1.0f);
    float phase[H] = {0};
    for (int h = 1; h < H; ++h) phase[h] = uniform(rng);
    std::vector<double> cum(H, 0.0);
    std::vector<float> src(n);
    for (size_t t = 0; t < n; ++t) {
        const float f0 = f0_up[t];
        const bool voiced = f0 > voiced_threshold;
        float s = m.l_linear_b;
        for (int h = 0; h < H; ++h) {
            cum[h] += (double) f0 * (h + 1) / (double) SR;
            const double theta = 2.0 * M_PI * (cum[h] - std::floor(cum[h]));
            const float sine = sine_amp * std::sin((float) theta + phase[h]);
            const float amp = voiced ? noise_std : sine_amp / 3.0f;
            s += m.l_linear_w[h] * ((voiced ? sine : 0.0f) + amp * gauss(rng));
        }
        src[t] = std::tanh(s);
    }
    return src;
}

// torch.stft(center=True, reflect) of the source: ne=[T_stft, 18], the real
// parts of the 9 bins then the imaginary
std::vector<float> stft(const std::vector<float> & x) {
    const int n = (int) x.size(), pad = N_FFT / 2;
    const int frames = n / HOP + 1;
    auto w = hann(N_FFT);
    auto at = [&](int i) {
        if (i < 0) i = -i;
        if (i >= n) i = 2 * (n - 1) - i;
        return x[i];
    };
    std::vector<float> out((size_t) frames * 2 * F);
    for (int t = 0; t < frames; ++t) {
        for (int f = 0; f < F; ++f) {
            double re = 0, im = 0;
            for (int k = 0; k < N_FFT; ++k) {
                const double v = at(t * HOP + k - pad) * w[k];
                const double th = 2.0 * M_PI * f * k / N_FFT;
                re += v * std::cos(th);
                im -= v * std::sin(th);
            }
            out[(size_t) f * frames + t] = (float) re;
            out[(size_t) (F + f) * frames + t] = (float) im;
        }
    }
    return out;
}

// torch.istft(center=True) of magnitude and phase, each ne=[T_stft, 9]
std::vector<float> istft(const std::vector<float> & mag, const std::vector<float> & phase, int frames) {
    auto w = hann(N_FFT);
    const int len = (frames - 1) * HOP + N_FFT;
    std::vector<double> y(len, 0.0), env(len, 0.0);
    for (int t = 0; t < frames; ++t) {
        for (int k = 0; k < N_FFT; ++k) {
            double v = 0;
            for (int f = 0; f < F; ++f) {
                const double a = std::min(mag[(size_t) f * frames + t], 100.0f);
                const double p = phase[(size_t) f * frames + t];
                const double th = 2.0 * M_PI * f * k / N_FFT;
                const double c = (f == 0 || f == N_FFT / 2) ? 1.0 : 2.0;
                v += c * a * (std::cos(p) * std::cos(th) - std::sin(p) * std::sin(th));
            }
            y[t * HOP + k] += v / N_FFT * w[k];
            env[t * HOP + k] += (double) w[k] * w[k];
        }
    }
    const int pad = N_FFT / 2, n = (frames - 1) * HOP;
    std::vector<float> out(n);
    for (int i = 0; i < n; ++i) {
        const double e = env[i + pad];
        out[i] = (float) (e > 1e-11 ? y[i + pad] / e : 0.0);
    }
    return out;
}

ggml_tensor * snake(ggml_context * ctx, ggml_tensor * x, ggml_tensor * alpha, ggml_tensor * inv_alpha) {
    ggml_tensor * s = ggml_sin(ctx, ggml_mul(ctx, x, ggml_reshape_2d(ctx, alpha, 1, alpha->ne[0])));
    return ggml_add(ctx, x, ggml_mul(ctx, ggml_mul(ctx, s, s), ggml_reshape_2d(ctx, inv_alpha, 1, inv_alpha->ne[0])));
}

// mel ne=[T, 80] and the source's stft ne=[T_stft, 18] -> conv_post's
// output ne=[T_stft, 18], the log magnitudes then the phases' arguments
std::vector<float> run_hift(const model & m, const std::vector<float> & mel, int T,
                            const std::vector<float> & s_stft, int frames) {
    const int rates[3] = {8, 5, 3}, ksizes[3] = {16, 11, 7}, chans[3] = {256, 128, 64};
    const int rb_k[3] = {3, 7, 11}, src_rb_k[3] = {7, 7, 11};
    const int dils[3] = {1, 3, 5};
    graph g(64u << 20, 131072);
    ggml_context * ctx = g.ctx;
    ggml_tensor * mel_in = g.input(ggml_new_tensor_2d(ctx, GGML_TYPE_F32, T, MEL), "mel");
    ggml_tensor * s_in = g.input(ggml_new_tensor_2d(ctx, GGML_TYPE_F32, frames, 2 * F), "s");

    auto resblock = [&](const std::string & p, ggml_tensor * x, int ks) {
        for (int i = 0; i < 3; ++i) {
            const std::string n = std::to_string(i);
            ggml_tensor * xt = snake(ctx, x, m.get(p + "/activations1/" + n + "/alpha"),
                                     m.get(p + "/activations1/" + n + "/inv_alpha"));
            xt = conv1d_b(ctx, m.get(p + "/convs1/" + n + "/weight"), m.get(p + "/convs1/" + n + "/bias"), xt,
                          1, (ks * dils[i] - dils[i]) / 2, dils[i]);
            xt = snake(ctx, xt, m.get(p + "/activations2/" + n + "/alpha"),
                       m.get(p + "/activations2/" + n + "/inv_alpha"));
            xt = conv1d_b(ctx, m.get(p + "/convs2/" + n + "/weight"), m.get(p + "/convs2/" + n + "/bias"), xt,
                          1, (ks - 1) / 2, 1);
            x = ggml_add(ctx, x, xt);
        }
        return x;
    };

    ggml_tensor * x = conv1d_b(ctx, m.get("hift/conv_pre/weight"), m.get("hift/conv_pre/bias"), mel_in, 1, 3, 1);
    for (int i = 0; i < 3; ++i) {
        const std::string n = std::to_string(i);
        x = ggml_leaky_relu(ctx, x, 0.1f, false);
        x = conv_transpose1d(ctx, m.get("hift/ups/" + n + "/weight"), x, rates[i], (ksizes[i] - rates[i]) / 2);
        x = ggml_add(ctx, x, ggml_reshape_2d(ctx, m.get("hift/ups/" + n + "/bias"), 1, chans[i]));
        if (i == 2) {
            // ReflectionPad1d((1, 0))
            ggml_tensor * first = ggml_cont(ctx, ggml_view_2d(ctx, x, 1, x->ne[1], x->nb[1], x->nb[0]));
            x = ggml_concat(ctx, first, x, 0);
        }
        const int stride = i == 0 ? 15 : i == 1 ? 3 : 1, pad = i == 0 ? 7 : i == 1 ? 1 : 0;
        ggml_tensor * si = conv1d_b(ctx, m.get("hift/source_downs/" + n + "/weight"),
                                    m.get("hift/source_downs/" + n + "/bias"), s_in, stride, pad, 1);
        x = ggml_add(ctx, x, resblock("hift/source_resblocks/" + n, si, src_rb_k[i]));
        ggml_tensor * xs = nullptr;
        for (int j = 0; j < 3; ++j) {
            ggml_tensor * r = resblock("hift/resblocks/" + std::to_string(i * 3 + j), x, rb_k[j]);
            xs = xs ? ggml_add(ctx, xs, r) : r;
        }
        x = ggml_scale(ctx, xs, 1.0f / 3.0f);
    }
    x = ggml_leaky_relu(ctx, x, 0.01f, false);
    x = conv1d_b(ctx, m.get("hift/conv_post/weight"), m.get("hift/conv_post/bias"), x, 1, 3, 1);
    g.build(m, x);
    g.set("mel", mel.data(), mel.size() * sizeof(float));
    g.set("s", s_stft.data(), s_stft.size() * sizeof(float));
    m.compute(g.gf);
    return g.get(x);
}

}  // namespace

// --- loading ---------------------------------------------------------------------

decoder::decoder(const std::string & path, int n_threads) : m(std::make_unique<model>()) {
    ggml_context * tmp = nullptr;
    gguf_init_params gp = { false, &tmp };
    gguf_context * g = gguf_init_from_file(path.c_str(), gp);
    if (!g) throw std::runtime_error("cannot read the decoder's weights from " + path);
    m->n_threads = std::max(1, n_threads);
    m->backend = ggml_backend_cpu_init();
    const int64_t n = gguf_get_n_tensors(g);
    // the snake activations' reciprocals are added next to their alphas
    std::vector<std::string> alphas;
    for (int64_t i = 0; i < n; ++i) {
        std::string name = gguf_get_tensor_name(g, i);
        if (name.size() > 6 && name.compare(name.size() - 6, 6, "/alpha") == 0) alphas.push_back(name);
    }
    ggml_init_params p = { ggml_tensor_overhead() * (size_t) (n + alphas.size()), nullptr, true };
    m->ctx_w = ggml_init(p);
    for (int64_t i = 0; i < n; ++i) {
        const char * name = gguf_get_tensor_name(g, i);
        ggml_tensor * dst = ggml_dup_tensor(m->ctx_w, ggml_get_tensor(tmp, name));
        ggml_set_name(dst, name);
        m->tensors[name] = dst;
    }
    for (auto & a : alphas) {
        ggml_tensor * dst = ggml_dup_tensor(m->ctx_w, ggml_get_tensor(tmp, a.c_str()));
        std::string inv = a.substr(0, a.size() - 5) + "inv_alpha";
        ggml_set_name(dst, inv.c_str());
        m->tensors[inv] = dst;
    }
    m->buffer_w = ggml_backend_alloc_ctx_tensors(m->ctx_w, m->backend);
    for (int64_t i = 0; i < n; ++i) {
        const char * name = gguf_get_tensor_name(g, i);
        ggml_tensor * src = ggml_get_tensor(tmp, name);
        ggml_backend_tensor_set(m->tensors[name], ggml_get_data(src), 0, ggml_nbytes(src));
    }
    for (auto & a : alphas) {
        ggml_tensor * src = ggml_get_tensor(tmp, a.c_str());
        std::vector<float> inv(ggml_nelements(src));
        const float * v = (const float *) ggml_get_data(src);
        for (size_t i = 0; i < inv.size(); ++i) inv[i] = 1.0f / (v[i] + 1e-9f);
        ggml_backend_tensor_set(m->tensors[a.substr(0, a.size() - 5) + "inv_alpha"], inv.data(), 0, ggml_nbytes(src));
    }
    gguf_free(g);
    ggml_free(tmp);
    m->l_linear_w = m->read("hift/m_source/l_linear/weight");
    m->l_linear_b = m->read("hift/m_source/l_linear/bias")[0];
}

decoder::~decoder() = default;

// --- decoding --------------------------------------------------------------------

std::vector<float> decoder::decode(const std::vector<int32_t> & speech, const voice & v,
                                   uint32_t seed, const overrides * o) {
    std::vector<int32_t> tokens(v.prompt_token);
    size_t n_speech = 0;
    for (int32_t t : speech) {
        if (t >= 0 && t < VOCAB) {
            tokens.push_back(t);
            ++n_speech;
        }
    }
    if (n_speech == 0) return {};
    for (int i = 0; i < LOOKAHEAD; ++i) tokens.push_back(SILENCE);
    n_speech += LOOKAHEAD;
    const int T = (int) tokens.size(), T_mu = 2 * T;

    // the tokens' embeddings, ne=[D, T]
    {
        ggml_tensor * e = m->get("flow/input_embedding");
        if (e->ne[0] != D || e->ne[1] != VOCAB) throw std::runtime_error("s3gen: unexpected input embedding");
    }
    std::vector<float> embedded((size_t) T * D);
    {
        ggml_tensor * e = m->get("flow/input_embedding");
        for (int i = 0; i < T; ++i)
            ggml_backend_tensor_get(e, embedded.data() + (size_t) i * D, (size_t) tokens[i] * D * sizeof(float),
                                    D * sizeof(float));
    }
    std::vector<float> mu_tm = run_encoder(*m, embedded, T);     // ne=[80, T_mu]
    if (o && o->mu_out) *o->mu_out = mu_tm;
    std::vector<float> mu((size_t) MEL * T_mu);                  // ne=[T_mu, 80]
    for (int c = 0; c < MEL; ++c)
        for (int t = 0; t < T_mu; ++t) mu[(size_t) c * T_mu + t] = mu_tm[(size_t) t * MEL + c];

    // the speaker: its x-vector normalized and projected to 80
    std::vector<float> spks(MEL);
    {
        const size_t E = v.embedding.size();
        double norm = 0;
        for (float x : v.embedding) norm += (double) x * x;
        norm = std::max(std::sqrt(norm), 1e-12);
        auto w = m->read("flow/spk_embed_affine/w"), b = m->read("flow/spk_embed_affine/b");
        if (w.size() != E * MEL) throw std::runtime_error("s3gen: the speaker embedding should have 192 values");
        for (int c = 0; c < MEL; ++c) {
            double acc = b[c];
            for (size_t i = 0; i < E; ++i) acc += (double) w[c * E + i] * v.embedding[i] / norm;
            spks[c] = (float) acc;
        }
    }

    // the prompt's mel frames condition the frames they cover
    const int prompt_rows = v.prompt_rows;
    if (prompt_rows > T_mu) throw std::runtime_error("s3gen: the voice's prompt is longer than the tokens");
    std::vector<float> cond((size_t) MEL * T_mu, 0.0f);
    for (int c = 0; c < MEL; ++c)
        for (int t = 0; t < prompt_rows; ++t) cond[(size_t) c * T_mu + t] = v.prompt_feat[(size_t) t * MEL + c];

    // noise everywhere, the speech's frames drawn again as the meanflow
    // decoder's noised mels are
    std::vector<float> z((size_t) MEL * T_mu);
    if (o && o->z0) {
        if (o->z0->size() != z.size()) throw std::runtime_error("s3gen: the noise override has the wrong size");
        z = *o->z0;
    } else {
        std::mt19937 rng(seed);
        std::normal_distribution<float> gauss(0.0f, 1.0f);
        for (auto & x : z) x = gauss(rng);
    }

    estimator est(*m, T_mu);
    const float steps[3] = {0.0f, 0.5f, 1.0f};
    for (int s = 0; s < 2; ++s) {
        auto t_emb = time_mixed(*m, steps[s], steps[s + 1]);
        auto dxdt = est.run(*m, z, mu, spks, cond, t_emb);
        const float dt = steps[s + 1] - steps[s];
        for (size_t i = 0; i < z.size(); ++i) z[i] += dt * dxdt[i];
    }

    const int T_mel = T_mu - prompt_rows;
    std::vector<float> mel((size_t) MEL * T_mel);                // ne=[T_mel, 80]
    for (int c = 0; c < MEL; ++c)
        for (int t = 0; t < T_mel; ++t) mel[(size_t) c * T_mel + t] = z[(size_t) c * T_mu + prompt_rows + t];
    if (o && o->mel_out) *o->mel_out = mel;

    // the vocoder
    auto f0 = run_f0(*m, mel, T_mel);
    if (o && o->f0_out) *o->f0_out = f0;
    const int up = 8 * 5 * 3 * HOP;
    std::vector<float> source;
    if (o && o->source) {
        source = *o->source;
    } else {
        std::vector<float> f0_up((size_t) T_mel * up);
        for (int i = 0; i < T_mel; ++i) std::fill_n(f0_up.begin() + (size_t) i * up, up, f0[i]);
        source = nsf_source(*m, f0_up, seed + 1);
    }
    auto s_stft = stft(source);
    const int frames = (int) source.size() / HOP + 1;
    auto post = run_hift(*m, mel, T_mel, s_stft, frames);
    std::vector<float> mag((size_t) F * frames), phase((size_t) F * frames);
    for (size_t i = 0; i < mag.size(); ++i) {
        mag[i] = std::exp(post[i]);
        phase[i] = std::sin(post[mag.size() + i]);
    }
    auto wav = istft(mag, phase, frames);
    for (auto & x : wav) x = std::clamp(x, -0.99f, 0.99f);

    // the fade the Python decoder applies, against the reference's spillover
    const int n_trim = SR / 50;
    if ((int) wav.size() >= 2 * n_trim) {
        std::fill_n(wav.begin(), n_trim, 0.0f);
        for (int i = 0; i < n_trim; ++i) {
            // torch.linspace(pi, 0, n_trim)
            const double th = M_PI * (1.0 - (double) i / (n_trim - 1));
            wav[n_trim + i] *= (float) ((std::cos(th) + 1.0) / 2.0);
        }
    }
    return wav;
}

// --- converting the checkpoint ---------------------------------------------------

namespace {

// g * v / ||v||, the norm over every dimension but the first
std::vector<float> fold_weight_norm(const st::tensor & g, const st::tensor & v) {
    auto gv = g.f32(), vv = v.f32();
    const int64_t rows = v.shape[0], per = v.numel() / rows;
    for (int64_t r = 0; r < rows; ++r) {
        double n = 0;
        for (int64_t i = 0; i < per; ++i) n += (double) vv[r * per + i] * vv[r * per + i];
        const double s = gv[r] / std::sqrt(n);
        for (int64_t i = 0; i < per; ++i) vv[r * per + i] = (float) (vv[r * per + i] * s);
    }
    return vv;
}

std::string conformer_name(const std::string & rest) {
    static const std::map<std::string, std::string> names = {
        {"norm_mha.weight", "norm_mha/w"}, {"norm_mha.bias", "norm_mha/b"},
        {"norm_ff.weight", "norm_ff/w"}, {"norm_ff.bias", "norm_ff/b"},
        {"self_attn.linear_q.weight", "attn/q/w"}, {"self_attn.linear_q.bias", "attn/q/b"},
        {"self_attn.linear_k.weight", "attn/k/w"}, {"self_attn.linear_k.bias", "attn/k/b"},
        {"self_attn.linear_v.weight", "attn/v/w"}, {"self_attn.linear_v.bias", "attn/v/b"},
        {"self_attn.linear_out.weight", "attn/o/w"}, {"self_attn.linear_out.bias", "attn/o/b"},
        {"self_attn.linear_pos.weight", "attn/pos/w"},
        {"self_attn.pos_bias_u", "attn/pos_bias_u"}, {"self_attn.pos_bias_v", "attn/pos_bias_v"},
        {"feed_forward.w_1.weight", "ff/w1/w"}, {"feed_forward.w_1.bias", "ff/w1/b"},
        {"feed_forward.w_2.weight", "ff/w2/w"}, {"feed_forward.w_2.bias", "ff/w2/b"},
    };
    auto it = names.find(rest);
    return it == names.end() ? "" : it->second;
}

// The decoder's name for a checkpoint tensor, "" for one it doesn't use.
std::string gguf_name(const std::string & k) {
    static const std::map<std::string, std::string> fixed = {
        {"flow.input_embedding.weight", "flow/input_embedding"},
        {"flow.spk_embed_affine_layer.weight", "flow/spk_embed_affine/w"},
        {"flow.spk_embed_affine_layer.bias", "flow/spk_embed_affine/b"},
        {"flow.encoder_proj.weight", "flow/encoder_proj/w"},
        {"flow.encoder_proj.bias", "flow/encoder_proj/b"},
        {"flow.encoder.embed.out.0.weight", "flow/encoder/embed/linear/w"},
        {"flow.encoder.embed.out.0.bias", "flow/encoder/embed/linear/b"},
        {"flow.encoder.embed.out.1.weight", "flow/encoder/embed/norm/w"},
        {"flow.encoder.embed.out.1.bias", "flow/encoder/embed/norm/b"},
        {"flow.encoder.pre_lookahead_layer.conv1.weight", "flow/encoder/pre_lookahead/conv1/w"},
        {"flow.encoder.pre_lookahead_layer.conv1.bias", "flow/encoder/pre_lookahead/conv1/b"},
        {"flow.encoder.pre_lookahead_layer.conv2.weight", "flow/encoder/pre_lookahead/conv2/w"},
        {"flow.encoder.pre_lookahead_layer.conv2.bias", "flow/encoder/pre_lookahead/conv2/b"},
        {"flow.encoder.up_layer.conv.weight", "flow/encoder/up_layer/conv/w"},
        {"flow.encoder.up_layer.conv.bias", "flow/encoder/up_layer/conv/b"},
        {"flow.encoder.up_embed.out.0.weight", "flow/encoder/up_embed/linear/w"},
        {"flow.encoder.up_embed.out.0.bias", "flow/encoder/up_embed/linear/b"},
        {"flow.encoder.up_embed.out.1.weight", "flow/encoder/up_embed/norm/w"},
        {"flow.encoder.up_embed.out.1.bias", "flow/encoder/up_embed/norm/b"},
        {"flow.encoder.after_norm.weight", "flow/encoder/after_norm/w"},
        {"flow.encoder.after_norm.bias", "flow/encoder/after_norm/b"},
    };
    if (auto it = fixed.find(k); it != fixed.end()) return it->second;
    std::smatch mt;
    static const std::regex block(R"(flow\.encoder\.(encoders|up_encoders)\.(\d+)\.(.+))");
    if (std::regex_match(k, mt, block)) {
        std::string rest = conformer_name(mt[3]);
        if (rest.empty()) return "";
        return std::string("flow/encoder/") + (mt[1] == "encoders" ? "block" : "up_block") + mt[2].str() + "/" + rest;
    }
    auto slashed = [](std::string s) {
        std::replace(s.begin(), s.end(), '.', '/');
        return s;
    };
    const std::string est = "flow.decoder.estimator.", hift = "mel2wav.";
    if (k.compare(0, est.size(), est) == 0) return "cfm/" + slashed(k.substr(est.size()));
    if (k.compare(0, hift.size(), hift) == 0) return "hift/" + slashed(k.substr(hift.size()));
    return "";
}

}  // namespace

void convert_safetensors(const std::string & src, const std::string & dst) {
    auto all = st::read(src, [](const std::string & k) {
        return k.compare(0, 5, "flow.") == 0 || k.compare(0, 8, "mel2wav.") == 0;
    });
    // name -> (shape, values), weight norm folded
    std::map<std::string, std::pair<std::vector<int64_t>, std::vector<float>>> out;
    const std::string g_suffix = ".parametrizations.weight.original0", v_suffix = ".parametrizations.weight.original1";
    for (auto & [k, t] : all) {
        if (k.size() > v_suffix.size() && k.compare(k.size() - v_suffix.size(), v_suffix.size(), v_suffix) == 0) continue;
        if (k.size() > g_suffix.size() && k.compare(k.size() - g_suffix.size(), g_suffix.size(), g_suffix) == 0) {
            const std::string base = k.substr(0, k.size() - g_suffix.size());
            const std::string name = gguf_name(base + ".weight");
            if (name.empty()) continue;
            out[name] = {all.at(base + v_suffix).shape, fold_weight_norm(t, all.at(base + v_suffix))};
            continue;
        }
        const std::string name = gguf_name(k);
        if (name.empty() || t.dtype != "F32") continue;
        out[name] = {t.shape, t.f32()};
    }
    size_t bytes = 0;
    for (auto & [k, v] : out) bytes += v.second.size() * sizeof(float) + 64;
    ggml_init_params p = { bytes + ggml_tensor_overhead() * out.size(), nullptr, false };
    ggml_context * ctx = ggml_init(p);
    gguf_context * g = gguf_init_empty();
    gguf_set_val_str(g, "general.architecture", "s3gen");
    gguf_set_val_str(g, "general.name", "chatterbox-turbo s3gen_meanflow, for KittenTTS 2");
    gguf_set_val_bool(g, "s3gen.meanflow", true);
    gguf_set_val_u32(g, "s3gen.n_timesteps", 2);
    for (auto & [name, sv] : out) {
        auto & [shape, vals] = sv;
        int64_t ne[4] = {1, 1, 1, 1};
        const int nd = (int) shape.size();
        for (int i = 0; i < nd; ++i) ne[i] = shape[nd - 1 - i];
        ggml_tensor * t = ggml_new_tensor(ctx, GGML_TYPE_F32, std::max(1, nd), ne);
        ggml_set_name(t, name.c_str());
        std::memcpy(t->data, vals.data(), vals.size() * sizeof(float));
        gguf_add_tensor(g, t);
    }
    const bool ok = gguf_write_to_file(g, dst.c_str(), false);
    gguf_free(g);
    ggml_free(ctx);
    if (!ok) throw std::runtime_error("cannot write " + dst);
}

}  // namespace s3gen
