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
2. **Local automation engine** (`agent/AutomationEngine.kt`, `agent/AutomationAction.kt`,
   `agent/TimeOfDayScheduler.kt`) - a small if-this-then-that system with two trigger types,
   "app X opens" (handled reactively on launch) and "at HH:MM" (a daily, inexact `AlarmManager`
   alarm that survives reboots), each firing one of a fixed, auditable set of actions (open
   another app, show the Wi-Fi/volume quick panel, start a timer, show a reminder toast). Manage
   rules from Settings; every firing is written to...
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

## Home screen widgets

Inspired by [omalauncher](https://github.com/SimonSchubert/omalauncher)'s **OWL** - its README
calls OWL "a small declarative language you can edit on the phone." OWL itself is a large, mature
system (shell/HTTP/sensor data sources, reactive state, Canvas/Shader drawing, a widget
marketplace with CI checking) - cloning it faithfully from its docs alone wasn't realistic to do
correctly in one pass, so what's here is a **scoped subset in the same spirit**, not a port:

- Widgets are plain-text `.owl` files, stored in app-private storage (`data/WidgetRepository.kt`)
  so they're genuinely editable on-device - Settings -> widgets opens a list, tap one to edit it
  in a built-in text editor (`ui/widgets/WidgetEditorScreen.kt`) with inline parse errors as you
  type, Save writes straight back to the file.
- Format (`widgets/OwlNode.kt`, `widgets/OwlParser.kt` - a hand-written tokenizer/parser with unit
  tests in `app/src/test/.../OwlParserTest.kt`): a `widget { size: "6x2" }` header followed by
  `Name { prop: value; children }` nodes. Layout: `Column`/`Row`/`Box`. Display: `Text`/`Button`/
  `Spacer`. Props: `padding`, `spacing`, `background`/`color` (theme tokens like `accent`,
  `surface`, `subtle`, or `red`/`green`/`yellow`/`magenta`/`cyan`), `radius`, `fill`, `weight`,
  `size` (text size or grid rows/cols). `${clock}`, `${date}`, `${battery}` interpolate into any
  `text:` string. `Button { onTap: open("pkg.name") }` or `onTap: url("https://...")` for taps.
- Three bundled examples ship as the defaults (`app/src/main/assets/widgets/`): a clock, a
  battery readout, and a quick-actions row - copied into app storage on first run so they're
  editable too, not read-only assets.
- Active widgets render stacked above the favorites list on the home screen
  (`ui/widgets/ActiveWidgetsSection.kt`); which ones are active and in what order is managed from
  the widgets screen and persisted via `data/WidgetPreferences.kt`.

**Deliberately not included** (OWL has all of this; this subset doesn't): `cmd()`/`http()`/stream
data sources, `state`/`persist`/`let`, `Grid`/`Flow`/`Image`/`Progress`/`Ring`/`Sparkline`/
`Canvas`/`Shader`/`Terminal` nodes, `onDrag`, a widget marketplace, and the multi-page/6-column
drag-and-drop grid (widgets here are a simple ordered vertical stack instead).

## Native Android widgets

Alongside the OWL-inspired `.owl` widgets above, Settings -> native widgets hosts **real**
installed Android widgets (a clock, weather, calendar, anything any app offers) via the actual
platform framework, `AppWidgetHost`/`AppWidgetManager` - no custom language, no parsing, the
genuine system mechanism every launcher uses:

- `nativewidgets/NativeWidgetHostManager.kt` wraps one `AppWidgetHost` for the app (id allocation,
  `createView`, delete, `startListening()`/`stopListening()` tied to `LauncherActivity`'s
  `onResume`/`onPause`), and lists installed home-screen-capable providers.
- Adding one is the standard third-party-launcher flow, since normal apps don't hold the
  restricted `BIND_APPWIDGET` permission: allocate an id, try a dialog-free
  `bindAppWidgetIdIfAllowed` first, and if that's refused (the common case), launch the system's
  own `ACTION_APPWIDGET_BIND` confirmation dialog, then `ACTION_APPWIDGET_CONFIGURE` if the widget
  has a config screen - all coordinated in `LauncherActivity` via `registerForActivityResult`
  (`ui/nativewidgets/NativeWidgetPickerScreen.kt` is just the chooser; the actual bind/configure
  round-trip needs an Activity, not a ViewModel). No extra manifest permission is needed - that's
  the point of the confirmation-dialog mechanism.
- The resulting `AppWidgetHostView` is a classic Android `View`, rendered inside Compose via
  `AndroidView` (`ui/nativewidgets/NativeWidgetHostComposable.kt`).
- Active widget IDs and their order persist via `data/NativeWidgetPreferences.kt` and survive
  restarts (the IDs stay valid at the system level); management (reorder/remove) is
  `ui/nativewidgets/NativeWidgetsScreen.kt`.

Native widgets render below the OWL ones, in the same scrollable column as the favorites list -
both widget sections and favorites now share one `LazyColumn` on the home screen, so a long
widget stack scrolls instead of squeezing favorites off-screen.

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
  agent/                       - ranking, automation engine/actions/triggers, alarm scheduling,
                                  boot receiver, command parser
  data/                        - PackageManager wrapper, DataStore-backed prefs, widget storage
  termux/                      - RUN_COMMAND dispatch + result receiver
  widgets/                     - OwlNode/OwlParser (the scoped widget format), OwlRenderer,
                                  LiveValues (${clock}/${date}/${battery}), WidgetAction
  nativewidgets/               - NativeWidgetHostManager (AppWidgetHost/AppWidgetManager wrapper)
  ui/
    home/, drawer/             - launcher + app drawer screens (icons via ui/common/AppIcon.kt)
    terminal/                  - embedded terminal + Arch/proot controls
    settings/, agent/          - automation rules UI, activity log
    widgets/                   - .owl widget list/editor screens, home-screen widget section
    nativewidgets/             - native widget picker/management screens, AndroidView host
    theme/                     - Omarchy palette/typography/shapes
  assets/
    termux/                    - install_archlinux_arm.sh, omarchy_theme.sh
    widgets/                   - bundled default .owl widgets
```

## Building

Requires JDK 17+, the Android SDK (platform 34, build-tools 34.0.0), and network access to
`dl.google.com` / Maven Central / Google's Maven for the first dependency sync.

```
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # OwlParser unit tests, run on the JVM, no device needed
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app-debug.apk`, then set it as the default launcher from Android Settings > Apps
> Default apps > Home app.

minSdk 26 (Android 8.0), targetSdk/compileSdk 34.

## Known limitations

- The `.owl` widgets are an OWL-*inspired* scoped subset, not a port - see "Deliberately not
  included" above. Native `AppWidgetHost` widgets (previously tracked as a follow-up) are now
  built, as a separate section - see "Native Android widgets" above.
- Automation rules support two trigger types - "app X opens" (reactive) and "time of day"
  (a daily `AlarmManager` alarm per rule, rescheduled on boot via `BootCompletedReceiver`,
  inexact on purpose so it needs no exact-alarm permission). Location/connectivity-change
  triggers would be natural next additions.
- Requires Termux + a one-time `allow-external-apps=true` opt-in for the Arch/proot features -
  this is an inherent Android platform constraint (see above), not a shortcut taken here.
- Native widgets haven't been exercised against a widget with an unusual `minResizeWidth`/
  resize mode, or a keyguard-only provider filtered out by `WIDGET_CATEGORY_HOME_SCREEN` - common
  widgets (clock, weather, calendar) are the tested target.
