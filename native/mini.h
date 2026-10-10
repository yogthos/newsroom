/* mini.h: KittenTTS mini 0.8, the small engine, through ONNX Runtime, with
 * espeak-ng for its phonemes. A plugin of its own, libnewsroom_tts_mini,
 * which newsroom.tts loads from the config directory's plugins/speech/;
 * see mini.cpp.
 *
 * Its audio goes to the MP3 through the main engine's nrtts_mp3_add_pcm. */
#ifndef NEWSROOM_TTS_MINI_H
#define NEWSROOM_TTS_MINI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct nrttsm nrttsm;

const char *nrttsm_version(void);

/* The model, its voices, its config.json (for the voices' names) and
 * espeak-ng's data directory; NULL only when out of memory, else check
 * nrttsm_ok. `threads` 0 picks the machine's count. */
nrttsm *nrttsm_open(const char *model_onnx, const char *voices_npz, const char *config_json,
                    const char *espeak_data, int threads);
int nrttsm_ok(nrttsm *h);
const char *nrttsm_error(nrttsm *h);
void nrttsm_free(nrttsm *h);

int nrttsm_voice_count(nrttsm *h);
const char *nrttsm_voice_name(nrttsm *h, int i);

/* Speaks one chunk of text, a sentence or a few, in `voice` (a name like
 * Hugo, or its expr-voice-4-m), at `speed`, and keeps the 24 kHz audio.
 * Answers the samples made, or -1. */
int nrttsm_speak(nrttsm *h, const char *voice, const char *text, float speed);
const float *nrttsm_audio(nrttsm *h);
int nrttsm_audio_length(nrttsm *h);

/* For the tests: the phonemes espeak gives `text`, as phonemizer leaves
 * them, into `out` (answers the bytes, or -1); the token ids the model
 * reads; and the durations of the last nrttsm_speak. */
int nrttsm_phonemes(nrttsm *h, const char *text, char *out, int cap);
int nrttsm_tokens(nrttsm *h, const char *text, int64_t *out, int cap);
int nrttsm_durations(nrttsm *h, int64_t *out, int cap);

#ifdef __cplusplus
}
#endif

#endif
