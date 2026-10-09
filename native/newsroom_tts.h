/* newsroom_tts.h: KittenTTS 2 in-process, the flat C face newsroom.tts reads
 * through jolt.ffi.
 *
 * The speech language model runs through llama.cpp, the S3 decoder through
 * ggml (s3gen.cpp), and the MP3 through LAME. The model's files are converted
 * once, after they are downloaded: its GGUF to standard Q4_0, the decoder's
 * safetensors to a GGUF, and the voices to a GGUF with the token layout from
 * its config.json.
 *
 * A call that can fail answers a negative number or NULL, and says why
 * through nrtts_error (on a handle) or the `err` buffer it was given. The
 * long-running calls take no C strings from the caller's heap that the
 * collector could move: pass arena-owned pointers. */
#ifndef NEWSROOM_TTS_H
#define NEWSROOM_TTS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct nrtts nrtts;
typedef struct nrtts_mp3 nrtts_mp3;

const char *nrtts_version(void);

/* --- converting the downloaded files; 0 on success --- */

/* KittenTTS's TQ2_1 GGUF (its llama.cpp fork's ternary format) to Q4_0, which
 * holds the same weights exactly and which stock llama.cpp runs on every CPU */
int nrtts_convert_lm(const char *src, const char *dst, char *err, int err_cap);

/* chatterbox-turbo's s3gen_meanflow.safetensors to the decoder's GGUF */
int nrtts_convert_decoder(const char *src, const char *dst, char *err, int err_cap);

/* the C++ runtime's voices.json and the model's config.json to one GGUF */
int nrtts_convert_voices(const char *voices_json, const char *config_json, const char *dst,
                         char *err, int err_cap);

/* --- the engine --- */

/* Loads the three converted files; NULL only when out of memory, else check
 * nrtts_ok. `threads` 0 picks the machine's count. */
nrtts *nrtts_open(const char *lm_gguf, const char *decoder_gguf, const char *voices_gguf, int threads);
int nrtts_ok(nrtts *h);
const char *nrtts_error(nrtts *h);
void nrtts_free(nrtts *h);

int nrtts_voice_count(nrtts *h);
const char *nrtts_voice_name(nrtts *h, int i);

/* Speaks one chunk of text, at most a few sentences, in `voice`, and keeps the
 * 24 kHz audio (nrtts_audio). `expression` turns on the model's emotion
 * conditioning, for text with [emotion], <event> or (((emphasis))) markup.
 * `max_tokens` caps the codec tokens (25 a second). `cancel`, when not NULL,
 * is read between tokens and stops the call when it holds non-zero. Answers
 * the samples made, -1 on an error, -2 when cancelled. */
int nrtts_speak(nrtts *h, const char *voice, const char *text, int expression,
                float temperature, int top_k, float top_p, float min_p, uint32_t seed,
                int max_tokens, const int32_t *cancel);

/* For the tests: the prompt nrtts_speak would build, its token ids written to
 * `out` (answers the count, or -1), and the model's logits for the first
 * token it would write, into `out` of nrtts_vocab_size floats. */
int nrtts_prompt(nrtts *h, const char *voice, const char *text, int expression, int32_t *out, int cap);
int nrtts_first_logits(nrtts *h, const char *voice, const char *text, int expression, float *out, int cap);
int nrtts_vocab_size(nrtts *h);

/* the audio of the last nrtts_speak, valid until the next call */
const float *nrtts_audio(nrtts *h);
int nrtts_audio_length(nrtts *h);

/* --- MP3 --- */

nrtts_mp3 *nrtts_mp3_open(int sample_rate, int kbps);
/* appends the last nrtts_speak's audio, its edge silence trimmed and its
 * edges faded so chunks join without a seam, then `gap_ms` of silence */
int nrtts_mp3_add_speech(nrtts_mp3 *e, nrtts *h, int gap_ms);
int nrtts_mp3_add(nrtts_mp3 *e, const float *pcm, int n);
/* finishes the stream; its bytes stay valid until nrtts_mp3_close */
int nrtts_mp3_finish(nrtts_mp3 *e);
const uint8_t *nrtts_mp3_bytes(nrtts_mp3 *e);
int nrtts_mp3_length(nrtts_mp3 *e);
/* the seconds of audio added */
double nrtts_mp3_seconds(nrtts_mp3 *e);
void nrtts_mp3_close(nrtts_mp3 *e);

#ifdef __cplusplus
}
#endif

#endif
