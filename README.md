# Omarchy Launcher

A minimal Android home launcher styled after [Omarchy](https://github.com/omacom/omarchy) -
DHH's Arch Linux + Hyprland setup - with a small set of **local, on-device "agentic" features**,
plus a guided path to installing **Arch Linux ARM in proot** (via Termux) from inside the
launcher itself.

## Look & feel

The UI is built from Omarchy's "Tokyo Night" theme (`themes/tokyo-night/colors.toml` in the
Omarchy repo - the palette most commonly seen in Omarchy screenshots), reduced to what a phone
actually needs:

- Dark-only color scheme (`#1a1b26` background, `#7aa2f7` accent) - no light mode, same as a
  single Omarchy theme has no light variant.
- Monospace type everywhere (Android's system monospace family - no font files to bundle).
- A minimal, text-only top status bar (clock + date), in place of Waybar.
- Small, consistent corner radii (4-8dp) instead of Material's usual large/pill shapes, mirroring
  Hyprland's window decoration.
- A rofi/wofi-style app launcher: a single search field that live-filters the app list and also
  doubles as a command bar.

See `app/src/main/java/com/omarchy/launcher/ui/theme/` for the full palette/typography/shape
definitions.

## "Agentic" features (all on-device, no network, no API key)

Per the project's own scope: every agent-like behavior here runs locally on the phone. The app
requests no `INTERNET` permission and makes no network calls anywhere in its source.

1. **Usage-based app ranking** (`agent/AppRanker.kt`) - the favorites row and the drawer's sort
   order are driven by a score blending overall frequency, recency, and "same hour of day"
   frequency, so e.g. an app you always open at 7am surfaces itself at 7am.
2. **Local automation engine** (`agent/AutomationEngine.kt`, `agent/AutomationAction.kt`) - a small
   if-this-then-that system: "when app X opens, do Y" where Y is one of a fixed, auditable set of
   actions (open another app, show the Wi-Fi/volume quick panel, start a timer, show a reminder
   toast). Manage rules from Settings; every firing is written to...
3. **Agent activity log** (`ui/agent/AgentLogScreen.kt`) - a transparency log of every automation
   the agent has fired, so nothing happens silently.
4. **Local command parser** (`agent/CommandParser.kt`) - the search bar also understands a small
   set of keyword patterns entirely via regex, no LLM/network involved: `open <app>`,
   `call <number>`, `timer 10`, `alarm 7:30am`, `search <query>`. Anything else just live-filters
   the app list (fuzzy subsequence match, `util/FuzzySearch.kt`).

None of this calls out to Claude, OpenAI, or any other cloud model - it's closer to "agent" in the
classic sense (perceives app-usage state, decides, acts) than to an LLM-backed assistant. That was
a deliberate scope choice (see the project history) to keep the launcher private-by-default with
zero API keys to manage.

## Arch Linux ARM in proot

The Terminal tab (bottom-right icon on the home screen, or Settings -> terminal) has two buttons:

- **Install Arch Linux ARM** - dispatches `assets/termux/install_archlinux_arm.sh` into Termux.
  It installs `proot-distro` and runs `proot-distro install archlinux`, which resolves to the
  Arch Linux ARM rootfs (from archlinuxarm.org) automatically on aarch64/armv7 devices.
- **Apply Omarchy theme** - dispatches `assets/termux/omarchy_theme.sh`, which logs into that
  Arch container and sets up zsh + a Tokyo-Night-colored prompt + fastfetch, so the shell you land
  in matches the launcher.

### Why this goes through Termux

Since Android 10, apps cannot execute native binaries they download into their own writable
storage (W^X enforcement) - that's precisely the problem Termux already solves, by shipping
`proot`/`busybox`/etc. as packaged native libraries that Android's installer extracts into an
exec-allowed directory. Reimplementing that from scratch (a second copy of proot + a second
package manager) would be redundant and far more fragile than just driving Termux, which is the
standard, well-tested way this is done on Android (also how AnLinux/UserLAnd work). So:

- This app ships **no** proot/busybox binaries of its own.
- It talks to Termux via its documented [`RUN_COMMAND` Intent API](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
  (`termux/TermuxController.kt`), passing scripts as a `bash -c "<script>"` argument - so the
  script text lives in this app's own assets and no file has to be shared between the two apps.
- Output streams back via a `PendingIntent` broadcast (`termux/TermuxResultReceiver.kt`) into the
  Terminal tab's log view.

### One-time setup this requires

1. Install **Termux from F-Droid** (the Play Store build is unmaintained and can't run RUN_COMMAND
   from other apps reliably) - https://f-droid.org/packages/com.termux/
2. Open Termux once, edit `~/.termux/termux.properties`, and add:
   ```
   allow-external-apps=true
   ```
   then `termux-reload-settings` (or just restart Termux).
3. In Omarchy Launcher's Terminal tab, tap "Grant Termux RUN_COMMAND permission" when prompted.
4. Tap **Install Arch Linux ARM** (downloads a rootfs - can take a while on slower connections),
   then **Apply Omarchy theme**.
5. From then on: `proot-distro login archlinux` in Termux drops you straight into it.

A full Hyprland/Wayland session cannot run this way (there's no display server on stock Android),
so what you get is the Arch Linux ARM userland + an Omarchy-styled shell, not a graphical desktop.
Running the actual Hyprland compositor would need either a rooted device with its own DRM/KMS
access or a separate VNC/X11-forwarding setup, which is out of scope here.

You can also run `install_archlinux_arm.sh` / `omarchy_theme.sh` directly in Termux by hand (they
don't depend on the launcher app at all) - they're plain files under
`app/src/main/assets/termux/`.

## Project layout

```
app/src/main/java/com/omarchy/launcher/
  LauncherActivity.kt          - the HOME activity, nav host, back-press handling
  MainViewModel.kt             - shared state: app list, ranking, rules, search
  AppContainer.kt              - tiny manual DI container
  agent/                       - ranking, automation engine/actions, command parser
  data/                        - PackageManager wrapper, DataStore-backed prefs
  termux/                      - RUN_COMMAND dispatch + result receiver
  ui/
    home/, drawer/             - launcher + app drawer screens
    terminal/                  - embedded terminal + Arch/proot controls
    settings/, agent/          - automation rules UI, activity log
    theme/                     - Omarchy palette/typography/shapes
  assets/termux/                - install_archlinux_arm.sh, omarchy_theme.sh
```

## Building

Requires JDK 17+, the Android SDK (platform 34, build-tools 34.0.0), and network access to
`dl.google.com` / Maven Central / Google's Maven for the first dependency sync.

```
./gradlew :app:assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app-debug.apk`, then set it as the default launcher from Android Settings > Apps
> Default apps > Home app.

minSdk 26 (Android 8.0), targetSdk/compileSdk 34.

## Known limitations

- No app widgets on the home screen yet (would need `AppWidgetHost` integration).
- No icon loading in the drawer list yet (labels only) - `AppRepository.loadIcon` exists but isn't
  wired into the list UI.
- The automation rule builder only supports one trigger type ("app X opened"); time-of-day
  triggers, etc. would be a natural next step.
- Requires Termux + a one-time `allow-external-apps=true` opt-in for the Arch/proot features -
  this is an inherent Android platform constraint (see above), not a shortcut taken here.
