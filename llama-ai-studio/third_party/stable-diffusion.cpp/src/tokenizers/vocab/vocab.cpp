#include "vocab.h"
#include "clip_merges.hpp"
#include <stdexcept>

// This is a mobile build of stable-diffusion.cpp vendored for Llama AI Studio: only CLIP
// tokenization is kept (SD1.x/2.x/SDXL/SDXL-Turbo and similar — the model families that
// actually fit a phone's RAM budget). The T5/UMT5/Gemma/Mistral/Qwen vocab tables this
// project embeds for SD3/Flux/Qwen-Image/Gemma-encoder models are ~137MB of compiled-in
// data for model families that are multi-billion-parameter, multi-file giants no Android
// device can realistically run locally; they are intentionally not vendored. Loading a
// model that needs one of these text encoders fails with a clear error instead of linking
// in that dead weight.

std::string load_clip_merges() {
    std::string merges_utf8_str(reinterpret_cast<const char*>(clip_merges_utf8_c_str), sizeof(clip_merges_utf8_c_str));
    return merges_utf8_str;
}

[[noreturn]] static void unsupported(const char* encoder) {
    throw std::runtime_error(
        std::string("This mobile build of Llama AI Studio only supports CLIP-based diffusion models "
                    "(SD1.x/2.x/SDXL/SDXL-Turbo and similar). The loaded model requires the ") +
        encoder + " text encoder, which needs a desktop/server build.");
}

std::string load_qwen2_merges() { unsupported("Qwen2"); }
std::string load_mistral_merges() { unsupported("Mistral"); }
std::string load_mistral_vocab_json() { unsupported("Mistral"); }
std::string load_t5_tokenizer_json() { unsupported("T5"); }
std::string load_umt5_tokenizer_json() { unsupported("UMT5"); }
std::string load_gemma_merges() { unsupported("Gemma"); }
std::string load_gemma_vocab_json() { unsupported("Gemma"); }
