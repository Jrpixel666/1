# Llama AI Studio

A native Android app (Kotlin + Jetpack Compose + NDK/C++) built for the Google Pixel 9 /
Tensor G4, combining fully local LLM inference (llama.cpp), a Hugging Face model browser,
a notes workspace, and a lightweight Android dev workspace.

## Workspaces

- **Chat** — offline chat backed by a vendored, pinned build of llama.cpp
  (`third_party/llama.cpp`, see `VENDORED_COMMIT.txt`). Streaming generation, adjustable
  temperature/top-k/top-p/min-p/repeat-penalty/context length/max tokens, live tok/s, and
  persistent sessions (Room). The ⋮ menu renames the session, edits its system prompt,
  exports it to Notes, clears or deletes it. Stopping a reply keeps what was generated so far.
- **Sessions** — every chat session in one place: search across titles *and* message text,
  pin favourites to the top, rename, per-session system prompt, duplicate (fork a
  conversation), export as a Markdown transcript into Notes, clear and delete. Each entry
  shows message count, last message, last activity and the model it last ran on. New
  sessions are auto-titled from their first message. Chat and Sessions share one "active
  session", so opening one from either side keeps the other in sync.
- **Notes** — Markdown notes with folders, search, autosave, AI summaries (runs on the
  loaded local model), and two-way handoff with Chat (send a note into chat context, save a
  chat reply as a note). Side-by-side layout on wide screens (`BoxWithConstraints`).
- **Image** — fully local text-to-image generation on a vendored, pinned build of
  stable-diffusion.cpp (`third_party/stable-diffusion.cpp`, own module: `:imagegen`).
  Prompt/negative-prompt/steps/CFG/size controls, live per-step progress and cancel, a
  persisted gallery (Room), and its own Hugging Face GGUF browser for diffusion
  checkpoints. CLIP-based models only (SD1.x/2.x/SDXL/SDXL-Turbo and similar) — see
  **Image generation scope** below for why.
- **Models** — Hugging Face search (`huggingface.co/api/models`), GGUF file listing with
  quantisation/param-count/size parsed from the API's own `gguf` metadata and filenames,
  resumable downloads (HTTP Range) with SHA-256 verification against HF's published LFS
  hash, and a local model library.
- **Performance** — live memory and thermal-headroom monitoring, a performance-profile
  picker (battery saver / balanced / max performance, backed by DataStore), manual
  thread/context tuning, and a benchmark that measures real prefill/generation tok/s on the
  loaded model (not an estimate).
- **Dev Studio** — project scaffolding, a file explorer, and a plain-text/monospace code
  editor for Kotlin/Java/XML, all stored in app-private storage. See **Dev Studio and ARM64**
  below for why on-device APK building is scoped as experimental.

## Native engine

`app/src/main/cpp/llama_bridge.cpp` is a JNI bridge over llama.cpp's modern C API
(`llama_model_load_from_file`, sampler chains, `llama_chat_apply_template`, etc.). It is
built CPU-only for this ABI:

- ARM64 NEON-optimized ggml CPU kernels (ggml's own cross-platform SIMD dispatch).
- **KleidiAI** micro-kernels (`GGML_CPU_KLEIDIAI=ON` for `arm64-v8a`), matching the ggml
  upstream's own `examples/llama.android` reference project.
- OpenMP threading (also per the upstream Android reference).

**Vulkan is not compiled into this build.** `ggml-vulkan`'s shader compiler
(`vulkan-shaders-gen`) is built as a target-ABI binary by llama.cpp's own CMake, which
cannot run on the x86-64 host doing the cross-compile — making it work requires patching
the build to compile that one tool for the host separately. That's a real, scoped follow-up,
not a hand-wave: `LlamaNative.supportsVulkan()` and the whole backend-selection UI are wired
up and will report `true` the day a build defines `GGML_USE_VULKAN`; until then the app is
explicit in the Performance tab that it's running CPU/NEON/KleidiAI only, rather than
claiming GPU acceleration it doesn't have.

## Image generation scope

`imagegen/src/main/cpp/sd_bridge.cpp` bridges stable-diffusion.cpp as a **separate Gradle
module with its own, fully static-linked native build** — not folded into the llama.cpp
module's CMake tree. Two reasons:

1. stable-diffusion.cpp vendors its own pinned fork of ggml (`leejet/ggml`, a different
   commit from llama.cpp's own ggml copy). Building both inside one CMake configure would
   define the target name `ggml` twice — a hard error.
2. Even as two separate `add_subdirectory` calls, both forks default to building ggml as
   *shared* libraries, which would each produce `libggml.so`/`libggml-base.so`/
   `libggml-cpu.so` — identical filenames, silently colliding when AGP merges both
   modules' native libs into one APK's `lib/arm64-v8a/`. `:imagegen`'s CMakeLists forces
   `BUILD_SHARED_LIBS=OFF`, so stable-diffusion.cpp and its ggml statically link into one
   shared object (`libsd_bridge.so`) with hidden symbol visibility — zero shared
   intermediate libraries, zero collision surface. Verified: the shipped APK's
   `lib/arm64-v8a/` carries both module's outputs side by side with no duplicate filenames.

**Only CLIP-based diffusion models are supported** (SD1.x/2.x/SDXL/SDXL-Turbo, SDXS, and
similar distilled/turbo checkpoints — the families that actually fit a phone). Upstream
stable-diffusion.cpp also supports SD3/Flux/Qwen-Image-class models, whose text encoders
(T5, UMT5, Gemma, Mistral, Qwen) embed ~137MB of vocab/merge tables as compiled-in C++
headers (`src/tokenizers/vocab/*.hpp`) — removed entirely here (see the patch comment in
`third_party/stable-diffusion.cpp/src/tokenizers/vocab/vocab.cpp`), both because those model
families are multi-billion-parameter, multi-file giants no Android device can realistically
load, and because keeping them compiled in regardless of which model you actually load would
have bloated every build by that ~137MB for a capability this app's target hardware can't
use. Loading a model that needs one of those encoders fails with a clear error rather than
a crash.

## Dev Studio and ARM64

Google's own Android SDK `build-tools` (`aapt2`, `d8`, `zipalign`) are published for
linux-x86_64, macOS and Windows hosts only — there is no linux-aarch64 build. That means a
*normal* Android build (the one that produced this APK) always runs on an x86-64 machine,
and there is no official, supported way to run those exact binaries on the ARM64 phone
itself. Dev Studio's toolchain panel is honest about this (see
`app/src/main/java/com/llamaaistudio/app/devstudio/TermuxBridge.kt`): it documents which
components are genuinely ARM64-native (Termux's own aarch64 OpenJDK, Gradle, Git, Kotlin
compiler) and which are not, and routes on-device building through Termux rather than
silently assuming Google's x86-64 binaries would run on the device. Plain JVM
(Kotlin/Java) compilation that way is solid; full Android APK assembly via Termux is
labelled experimental in the UI because AGP's own `aapt2` dependency still expects the x86
artifact unless substituted.

## Database migrations

Room schema v3 adds `chat_sessions.pinned` through a real migration (`MIGRATION_2_3` in
`AppDatabase`), so upgrading from a v2 build keeps all chats and notes.

## Building this APK yourself

```
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
    "ndk;27.2.12479018" "cmake;3.22.1"
echo "sdk.dir=/path/to/sdk" > local.properties
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (arm64-v8a only, debug-signed).

For a release build, add a `keystore.properties` at the repo root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) — release signing is otherwise
skipped and `assembleRelease` produces an unsigned APK.

## What's verified vs. what isn't

Verified in this build: it compiles clean, the arm64-v8a native libraries
(`libggml*.so`, `libllama*.so`, `libllama_bridge.so`) are present and correctly targeted,
the APK is validly signed and zip-aligned (`apksigner verify`, `zipalign -c`). What is
**not** verified here: there is no Pixel 9 (or any ARM64 device/emulator — this build
environment has no `/dev/kvm`) available to actually run inference and measure real
tokens/sec on hardware. The Performance tab's benchmark is real code that will report real
numbers the first time someone runs it on a device; no numbers are fabricated in this
repo or its docs.
