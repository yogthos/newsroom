/* plugin_names.h: the GPU plugin's names for the C face. Compiled in with
 * -include, it renames every nrtts_ function nrttsg_, so the plugin and the
 * CPU engine linked into the binary can be loaded side by side; see
 * build_tts.sh and newsroom.tts. */
#pragma once
#define nrtts_audio nrttsg_audio
#define nrtts_audio_length nrttsg_audio_length
#define nrtts_convert_decoder nrttsg_convert_decoder
#define nrtts_convert_lm nrttsg_convert_lm
#define nrtts_convert_voices nrttsg_convert_voices
#define nrtts_device nrttsg_device
#define nrtts_error nrttsg_error
#define nrtts_first_logits nrttsg_first_logits
#define nrtts_free nrttsg_free
#define nrtts_mp3_add nrttsg_mp3_add
#define nrtts_mp3_add_speech nrttsg_mp3_add_speech
#define nrtts_mp3_add_pcm nrttsg_mp3_add_pcm
#define nrtts_mp3_bytes nrttsg_mp3_bytes
#define nrtts_mp3_close nrttsg_mp3_close
#define nrtts_mp3_finish nrttsg_mp3_finish
#define nrtts_mp3_length nrttsg_mp3_length
#define nrtts_mp3_open nrttsg_mp3_open
#define nrtts_mp3_seconds nrttsg_mp3_seconds
#define nrtts_ok nrttsg_ok
#define nrtts_open nrttsg_open
#define nrtts_prompt nrttsg_prompt
#define nrtts_speak nrttsg_speak
#define nrtts_version nrttsg_version
#define nrtts_vocab_size nrttsg_vocab_size
#define nrtts_voice_count nrttsg_voice_count
#define nrtts_voice_name nrttsg_voice_name
