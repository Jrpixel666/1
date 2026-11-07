# Llama AI Studio

A native Android app (Kotlin + Jetpack Compose + NDK/C++) built for the Google Pixel 9 /
Tensor G4, combining fully local LLM inference (llama.cpp), a Hugging Face model browser,
a Web Studio that builds and serves websites from an idea, and a lightweight Android dev workspace.

## Workspaces

- **Chat** — offline chat backed by a vendored, pinned build of llama.cpp
  (`third_party/llama.cpp`, see `VENDORED_COMMIT.txt`). Streaming generation, adjustable
  temperature/top-k/top-p/min-p/repeat-penalty/context length/max tokens, live tok/s, and
  persistent sessions (Room). The ⋮ menu renames the session, edits its system prompt,
  shares it as a Markdown transcript, clears or deletes it. Stopping a reply keeps what was
  generated so far; any reply can be copied.
- **Sessions** (Chat's ☰ button) — every chat session in one place: search across titles
  *and* message text, pin favourites to the top, rename, per-session system prompt,
  duplicate (fork a conversation), share as a Markdown transcript, clear and delete. Each entry
  shows message count, last message, last activity and the model it last ran on. New
  sessions are auto-titled from their first message. Chat and Sessions share one "active
  session", so opening one from either side keeps the other in sync.
- **Web** (Web Studio) — describe a website and the loaded model builds it: HTML, CSS and
  JavaScript, several files per generation (`=== FILE: path ===` blocks, path-checked so
  output can't escape the project). Static-site and Node.js templates, a file list and code
  editor, and a **built-in web server**: one tap serves the site at `http://127.0.0.1:8080`
  with live reload — see **Web Studio's built-in server** below.
- **Model storage** — Settings → Model storage chooses where downloaded models go: app
  storage (default), phone storage (`/sdcard/LlamaAIStudio/models`, kept after uninstall,
  needs All files access) or a removable SD card. Existing models can be moved there, and
  models already in the folder are picked up.
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
  **Build APK / Run** compiles projects with Gradle *on the phone* inside the built-in Linux
  and installs the result (see **On-device builds** below). The Dev tab also opens the
  **Linux terminal**: Alpine Linux with one-tap installs of Node.js, Java + Gradle and git.

## Native engine

`app/src/main/cpp/llama_bridge.cpp` is a JNI bridge over llama.cpp's modern C API
(`llama_model_load_from_file`, sampler chains, `llama_chat_apply_template`, etc.). It is
built CPU-only for this ABI:

- ARM64 NEON-optimized ggml CPU kernels (ggml's own cross-platform SIMD dispatch).
- **KleidiAI** micro-kernels (`GGML_CPU_KLEIDIAI=ON` for `arm64-v8a`), matching the ggml
  upstream's own `examples/llama.android` reference project.
- OpenMP threading (also per the upstream Android reference).

**Vulkan GPU backend (GPU + CPU).** `ggml-vulkan` is compiled in for arm64-v8a
(`LLAMA_STUDIO_VULKAN`, on by default): it builds against the vendored Khronos headers in
`third_party/vulkan` (the NDK's are C-only and too old), links the NDK `libvulkan.so` stub —
the phone's own Vulkan driver at runtime (Mali-G715 on Tensor G4) — and compiles its shaders
with the NDK's `glslc`. ggml builds its `vulkan-shaders-gen` helper for the host
automatically while cross-compiling.

- **Performance → Inference backend** shows the detected GPU and lets you choose **CPU only**
  or **GPU + CPU**, with a slider for how many layers go to the GPU (the rest run on the
  CPU). *CPU only* really is CPU only (no GPU devices are given to llama.cpp, so even big
  prompt batches stay on the CPU). **Apply** reloads the model with the new placement.
- **Auto-tune** measures this model on this phone — CPU generation at several thread counts
  (changed live with `llama_set_n_threads`, no reload) and GPU + CPU with every layer
  offloaded — then saves and applies whichever generates fastest. Mobile GPUs aren't always
  faster than the CPU for LLMs, so this is measured rather than assumed.
- **Auto (fit to memory)** — the default GPU-layer setting — calls llama.cpp's
  `common_fit_params` before loading: as many layers as fit the GPU's free memory go to the
  GPU, the rest run on the CPU at the same time.
- **Models → Find models for CPU + GPU** lists the most-downloaded GGUF chat models (or those
  matching the search box) that fit this phone, choosing for each the best file
  (`data/hf/DeviceFit.kt`: k-quants first; multi-part, mmproj/draft files and implausibly small
  files skipped) and showing whether it runs entirely on the GPU or split GPU + CPU.
  Downloading one switches Chat to GPU + CPU (auto-fit).
- Threads are big.LITTLE-aware (`CpuTopology`): generation uses the fast cores only
  (4 on Tensor G4: X4 + 3× A720), prompt processing can use all 8.
- **Max performance** profile = fast cores for generation, all cores for prompts, GPU + CPU
  with all layers when a Vulkan GPU is present, 8K context.
- Safety net: a marker file is written around every GPU load/generation; if the app dies
  inside the GPU driver, the next launch switches back to CPU only instead of crashing again
  on the automatic model restore, and the Performance tab explains what happened.

## Dev Studio and ARM64

Google's own Android SDK `build-tools` (`aapt2`, `zipalign`, `aidl`) are published for
linux-x86_64, macOS and Windows only — there is no linux-aarch64 build. On-device builds
therefore swap in statically linked aarch64 builds of those tools (from AOSP sources, via
lzhiyong/android-sdk-tools) and point the Android Gradle Plugin at them with
`android.aapt2FromMavenOverride`; everything else (javac, kotlinc, d8, R8, apksigner) is Java
and runs unchanged on the in-app OpenJDK. See **On-device builds** below.

## Web Studio's built-in server

`webserver/LocalWebServer.kt` is a small HTTP/1.1 static-file server inside the app (plain
`java.net` sockets, no dependencies), so testing a site needs no Termux and no installs:

- **Start server** / **Preview** serve the project at `http://127.0.0.1:8080` (next free port
  if taken). A successful AI build starts it automatically.
- **Live reload**: HTML pages get a tiny script that polls `/__llama/version` — a fingerprint
  of the folder's file timestamps — and reloads when anything changes, so the in-app preview
  and any browser tab refresh themselves after each build or save.
- **Share on Wi-Fi** (off by default) binds all interfaces so a laptop or another phone on the
  same network can open `http://<phone-ip>:8080`.
- GET/HEAD only, `Cache-Control: no-store`, every path canonicalised and kept inside the
  project folder.
- `WebServerService` (a foreground service with a "Serving … · Stop" notification) keeps the
  server answering while you test in another browser — otherwise Android freezes the
  backgrounded app and the page hangs.

Cleartext HTTP from the app itself is allowed only to `127.0.0.1`/`localhost`
(`res/xml/network_security_config.xml`).

**Node projects:** the built-in server serves `public/`. **Start Node** runs the real
`server.js` (for `/api` routes) with Node inside the built-in Linux on port 3000, installing
Node.js on first use. The project folder is mounted at `/projects/web/<name>`, so there is no
copying; Node restarts automatically when server code changes. No Termux anywhere.

## Built-in Linux (Alpine + proot)

The app carries its own Linux userland, so it doesn't depend on Termux:

- **proot** (`third_party/proot`, termux/proot pinned in `VENDORED_COMMIT.txt`, GPL-2.0) and
  **talloc** (`third_party/talloc`, LGPL-3.0+) are cross-compiled by `scripts/build-proot.sh`
  (Gradle task `:app:buildProot`, runs automatically) and packaged as
  `lib/arm64-v8a/libproot.so` + `libproot-loader.so`.
- Native libraries are **extracted at install** (`useLegacyPackaging = true`): the app's
  `nativeLibraryDir` is the only place Android 10+ lets a modern app execute files from.
  The loader is unbundled (`PROOT_LOADER`) for the same reason — proot would otherwise
  extract it into app storage and exec it there.
- **Install** downloads `alpine-minirootfs-3.24.3-aarch64.tar.gz` (~4 MB) from
  dl-cdn.alpinelinux.org, checks it against the pinned SHA-256 from Alpine's `.sha256`, and
  unpacks it with `RootfsExtractor` (symlinks resolved chroot-style, permissions kept, nothing
  can be written outside the rootfs). DNS, hosts and the main/community repos are configured.
- Commands run as `proot --kill-on-exit --link2symlink -0 -r <rootfs> … /bin/sh -lc <cmd>` with
  a clean environment; Web/Dev Studio projects are mounted at `/projects/web` and
  `/projects/apps`.
- **Self-test** checks each step (run a Linux program, write files, DNS + HTTPS) and says
  plainly if this phone's Android blocks executing the guest's programs.

Not verified here: this build environment has no ARM64 device, so the Self-test on a real
phone is the first time the guest actually runs.

## On-device builds (Dev Studio)

**Install build tools** (one time, ~250 MB, every download SHA-256 pinned in
`linux/AndroidBuildToolchain.kt`) sets up, inside the built-in Linux:

- OpenJDK 17, bash, unzip (`apk`),
- `platforms;android-35` and `build-tools;34.0.0` from dl.google.com,
- statically linked **aarch64** `aapt2`, `aidl`, `zipalign` (+ `adb`) from
  [lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) 35.0.2, replacing
  Google's x86-64-only binaries. Projects get `android.aapt2FromMavenOverride` so AGP uses it.

**Build APK** runs the project's Gradle wrapper (`./gradlew assembleDebug`, Gradle 8.14.3,
added to every project) in `/projects/apps/<name>` and **Install APK** hands
`app-debug.apk` to the system installer. Console (Kotlin) projects use `gradle run`. The
first build downloads Gradle and the Android Gradle Plugin, so it needs a connection.

Verified off-device: both templates (`ProjectTemplates.kt`) build with the real Gradle + AGP
8.7.2 using the same `aapt2` override (APK with a launchable `MainActivity`; the console
program prints its greeting). Running Gradle and the ARM64 `aapt2` inside proot on a phone is
verified only by running it there.

A foreground service (`BackgroundWorkService`) keeps servers, builds and installs running
while you switch apps, with a **Stop all** action in its notification.

## LiteRT-LM (second on-device engine)

[LiteRT-LM](https://ai.google.dev/edge/litert-lm/android) (`com.google.ai.edge.litertlm:litertlm-android`)
runs Google's `.litertlm` models (Gemma, Qwen, Phi… from the LiteRT community on Hugging Face) on
the **GPU** (OpenCL), **CPU** or **NPU**. It sits next to llama.cpp: a model loads with the engine
its file needs (`.litertlm` → LiteRT-LM, `.gguf` → llama.cpp) and the other engine is unloaded.
Chat and Web/Dev Studio use it through `AiRouter`.

- **Models → LiteRT-LM models** searches `litert-community`; the file list hides builds compiled
  for one specific NPU (Tensor G5/G6, Qualcomm, MediaTek, Intel) or the web, lists `-gpu` builds
  first, and marks gated repos. There are no NPU builds for Tensor G4 (Pixel 9) — use GPU.
- **Settings (Models → ⚙)** now starts with *On-device engines*: LiteRT-LM backend (GPU / NPU /
  CPU), llama.cpp placement (CPU only / GPU + CPU auto-fit), what's loaded, and Apply-reload.
- Requires Kotlin ≥ 2.2: the project moved to Kotlin 2.4.21, AGP 8.13.2, KSP 2.3.13, Room 2.8.4.

## OpenCode (optional cloud models)

**Models → ⚙ Settings** takes an [OpenCode Zen](https://opencode.ai/docs/zen) API key. It is
encrypted with an Android Keystore AES-GCM key (`data/cloud/SecureKeyStore.kt`) and only sent
to opencode.ai. The catalogue comes from `https://opencode.ai/zen/v1/models`; the app shows
**only free models** (every listed price is 0) that offer an OpenAI-compatible
`chat-completions` endpoint, and streams replies from it.

- **Models → Discover** has two searches: on-device GGUF models (Hugging Face) and free
  OpenCode models. **Use for chat** routes Chat — and Web/Dev Studio's "build with AI" — to
  that model (`engine/AiRouter.kt`); **Use local** switches back.

## Database migrations

- v3 (`MIGRATION_2_3`): `chat_sessions.pinned` for Sessions.
- v4 (`MIGRATION_3_4`): the Notes workspace was removed — drops `notes`/`folders` and adds
  `web_projects`. Chats, models, images and Dev Studio projects are kept.

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
