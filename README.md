# Ara — the Android shell for Retinue

Ara is a small native Android app that turns a phone into a **Retinue device**:
a specialized browser and home launcher around the
[Retinue](https://github.com/Retinue-OS/retinue) web app.

- A **full-height WebView** shows the web app (threads, the attention list,
  voice conversation, contacts, sends — everything that *is* Retinue lives
  there and works the same in Chrome or on a desktop).
- A **launcher bar** at the bottom holds the device's own shortcuts (Phone,
  Messages, Mail, Maps by default) and an **All apps** drawer.
- Ara can be the **default home app**, so the Home button lands on the web
  app's home.
- It adds the few things a browser tab cannot do: an on-device **wake phrase**,
  **native text-to-speech** where the WebView has none, and the **client
  certificate** (mutual TLS) for the Retinue edge.

The old native voice loop (SpeechRecognizer, `/message` job polling, the answer
screen) is gone: continuous conversation is now a per-thread mode of the web
app.

- **Language:** Kotlin, Jetpack Compose, Android WebView (+ androidx.webkit).
- **Primary target device:** Samsung Galaxy S25 (One UI 7 / Android 15);
  minimum Android 12 (API 31).

---

## Building

The project builds from the command line with the bundled Gradle Wrapper.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests of the pure logic
node app/src/test/js/retinue-shell.test.mjs   # the page-side shell script
./gradlew installDebug           # or: adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: **JDK 17**, an **Android SDK** with platform 35 (point
`ANDROID_HOME` at it or write `sdk.dir=…` into `local.properties`). Versions:
AGP 8.7.2, Gradle 8.11.1, Kotlin 2.0.21, Compose BOM 2024.10.01, androidx.webkit
1.12.1, ONNX Runtime for Android 1.20.0. The APK carries native ONNX Runtime
libraries for `arm64-v8a` and `x86_64` only (phones and the emulator).

CI (`.github/workflows/ci.yml`) assembles the debug APK and runs the unit tests
on every pull request and push to `main`; the nightly workflow publishes a
signed release APK (see `keystore/README.md`). Debug and release builds are
both signed with the checked-in test certificate, so any CI APK installs over
the previous one without uninstalling.

---

## First run and settings

On first start there is no base URL yet, so Ara opens its **shell settings**.
They are also reachable from the long-press menu of any launcher-bar entry, and
from the web app's Settings page (it calls `RetinueShell.openSettings()`).

| Setting | Meaning |
|---|---|
| **Retinue base URL** | The web app's origin, e.g. `https://agents.example.com` (`https://` is assumed if omitted). Pages on this origin stay in the shell; nothing else gets the bridge, the microphone or the certificate. |
| **Client certificate** | The installed certificate Ara presents to that origin (mTLS). |
| **Home screen** | Shows whether Ara is the default home app; the button asks Android to make it so. |
| **Launcher bar** | Add links, web-app pages and folders, reorder, edit, remove, reset to defaults. |
| **Wake phrase** | Mode, sensitivity, and the models (see below). |

Nothing environment-specific is baked into the APK.

---

## The web view

- JavaScript, DOM storage and service workers are on; media may play without a
  tap (a wake arrives without one, and the web app's chime and voice-activity
  detector need audio to start).
- The user agent ends in ` RetinueShell/1 (Android)`.
- **Same-origin** links stay in the view; links to **other web origins** open in
  the default browser; **other schemes** (`tel:`, `sms:`, `mailto:`, `geo:`, …)
  go to Android (to browsable handlers only, as from a browser). `intent:` URLs
  are parsed with `Intent.parseUri` and sanitized as Chrome does (browsable
  only, no explicit component or selector, no URI grants or clip data, no
  `file:` or `content:` data); when no app takes one, its `browser_fallback_url`
  is honoured. A malformed one is ignored.
  Top-level `javascript:`, `data:`, `file:` and `content:` navigations are
  refused.
- **File chooser** for attachments (the system picker, multiple files allowed).
- **Microphone** for the page: granted only to the configured origin, after
  Android's `RECORD_AUDIO` permission (asked for on first use).
- **HTTP authentication** prompts for user name and password (a fallback; the
  edge normally authenticates by certificate).
- **Back** goes back in the page's history. As home app, Back at the start page
  does nothing; otherwise it sends Ara to the background (the page is kept).
- A crashed or reclaimed WebView renderer is replaced and the start page
  reloaded.
- **The keyboard stays up under its own popups.** Chromium 155 to 157 hide the
  soft keyboard whenever the WebView's window loses focus (crbug.com/560321831,
  fixed in 158), and the keyboard's language list or the input-method picker
  takes that focus, so the keyboard and the popup with it vanished as soon as
  it opened. While the keyboard is up, the WebView is not told its window lost
  focus.
- **Load failures are shown, not left blank**: a thin progress bar runs while
  the page loads; a main-frame network error, an HTTP error status (400 and up),
  a refused TLS certificate of the configured origin or a cancelled sign-in
  replace the page with a card giving the reason and URL, with Retry, Choose
  certificate (when none is selected) and Shell settings. The settings' **Page
  status** section shows the current status, the last URL, the selected
  certificate and the page's last console errors, for a screenshot.

## Shell ↔ web contract

The web app detects the shell and talks to it through:

- `window.RetinueShell` — installed at document start on the configured origin
  only: `{platform: 'android', version, setVoiceActive(bool), openSettings()}`.
  `setVoiceActive(true)` (the page has the mic open, or holds a continuous
  conversation) keeps the screen on and pauses wake detection until
  `setVoiceActive(false)`, however long that takes. As a safety net, a
  microphone grant to the page pauses wake detection by itself until the page
  reports, or for 10 s if it never does.
- Shell → web events:
  `window.dispatchEvent(new CustomEvent('retinue-shell', {detail: {type}}))` with
  `type` `wake` (the phrase was heard) or `home` (Home pressed while Ara was
  already in front). A wake while the page is not loaded loads `<base>/` and
  dispatches `wake` once it has finished loading, so no wake flag lands in the
  WebView history.
  The event is cancelable, and the shell checks whether the page handled it:
  a `retinue-shell` listener on `window` ran for it (the page script wraps
  them, so listeners removed, run `once` or aborted no longer count), or the
  page called `preventDefault()`. A listener receives every type, so it counts
  for every type: a page that handles only `home` still takes `wake` (the web
  app handles both). On a page of the origin without a listener (a
  gateway page such as `/sends`), a wake loads `<base>/?wake=1` (the web app
  strips the flag with `history.replaceState` on load) and Home loads `<base>/`
  (Home on the start page leaves it alone).
- A `speechSynthesis` / `SpeechSynthesisUtterance` stand-in backed by Android
  TextToSpeech, installed when the WebView has no Web Speech synthesis (or one
  without any voices). It covers what the web Reader uses: `speak`, `cancel`,
  `pause`/`resume` (an utterance resumes from its start), `getVoices` with
  `voiceschanged`, `speaking`/`pending`/`paused`, and per-utterance
  `start`/`end`/`error`/`boundary` events; `cancel()` reports `interrupted` for
  the spoken utterance and `canceled` for queued ones, as Chrome does.

Implementation: `assets/retinue-shell.js` (page side) and `web/ShellBridge.kt`
(`addWebMessageListener` + `addDocumentStartJavaScript`, restricted to the
origin). A WebView without `addWebMessageListener` gets no bridge: the page
behaves as in a browser tab.

---

## Launcher bar

Entries are stored on the device (DataStore, as JSON) and are one of:

| Type | Example | Opens |
|---|---|---|
| **App** | pinned from All apps | the app's launcher activity |
| **Shortcut** (`shortcut`) | pinned by another app | that app's shortcut (needs Ara as home app) |
| **Link** (`uri`) | `tel:`, `sms:`, `mailto:`, `geo:0,0?q=`, `https://…`, `intent:…` | the dialer (tel), a composer (sms/mailto), otherwise Android's handler |
| **Web page** (`web`) | `/#contacts` | that page of the web app, in the shell |
| **Folder** (`folder`) | a *Games* folder | its apps, over the bar |

The defaults are Phone, Messages, Mail and Maps — the four OS actions the web
app's dock used to have. The bar scrolls sideways when it is full. The last
slot is always **All apps**: a searchable list of launchable apps (tap to open,
long-press to pin it to the bar or put it in a folder). It is what keeps other
apps reachable once Ara is the home screen. Long-press a bar entry to edit,
move, or remove it; the editor in the shell settings adds links, web pages and
folders and reorders everything.

As home app Ara accepts pinned shortcuts (a browser's "Install app" / "Add to
home screen", other apps' shortcuts) after a confirmation: they go on the bar
and are listed in All apps with the apps. Removing one from the bar keeps it in
All apps; long-press it there and choose "Remove from phone" to unpin it. Web
apps Chrome installs as WebAPKs are ordinary apps in All apps.

Uninstalled apps leave the bar by themselves, also from inside folders (a
folder stays, even when that empties it): Android reports a removal while Ara
runs (`LauncherApps.Callback`), and Ara checks the bar's apps each time it
starts. An app update, a disabled app, an app on storage that is not mounted
and (Android 15+) an archived app keep their place. A pinned shortcut leaves
with its app when that is uninstalled while Ara runs.

App discovery uses a `<queries>` declaration for launcher activities — no
`QUERY_ALL_PACKAGES`.

### Folders

A folder holds installed apps — only apps (web apps Chrome installs as WebAPKs
included). Pinned shortcuts, links, web pages and other folders cannot go in,
so folders never nest: the stored form has room for apps only, and anything
else found in a stored folder is dropped when it is read.

- **Create** one with *New folder* in the long-press menu of **All apps**, with
  *New folder with this app* in an app's long-press menu inside All apps, or
  with **Add folder** in the shell settings.
- **Type:** the editor offers standard folder types — Folder, Chat, Social,
  Media, Photos, Games, News, Travel, Work, Tools, Shopping, Finance, Health,
  Smart home. The type gives the folder its symbol and, until you name it
  yourself, its name, shown in the phone's current language. Types that match
  an Android app category (Social, Media, Photos, Games, News, Travel, Work)
  list the installed apps of that category first, as suggestions; apps declare
  their category in their manifest, so not every app has one.
- **Icon:** the type's symbol, or an image **from photos** (the system photo
  picker) or **from files** (any document provider: the device's storage,
  Drive, Nextcloud …). The image is cropped to its centre square, scaled to
  192 px and kept as a PNG in Ara's own storage, so the original may be moved
  or deleted. An icon that is replaced, or whose folder is removed, is deleted.
- **Apps:** the editor lists the folder's apps (reorder, remove) and the
  installed apps to add (searchable). Apps also go in from All apps
  (long-press → *Add to …*) and from the bar (long-press an app on the bar →
  *Move to …*). An app may be in several folders and on the bar as well.
- **Use:** tap the folder to open it over the bar; tap an app to start it,
  long-press one to move it or take it out; **+** adds apps and the pencil
  edits the folder. Long-press the folder on the bar to edit, move or remove
  it; removing a folder with apps in it asks first, and never uninstalls
  anything.
- **Cleanup:** a folder's icon file is deleted with the folder or when it is
  replaced; one left behind by an interrupted save is deleted on the next
  start.

## Default home app

Settings → **Set as default home app** asks Android for the home role
(`RoleManager.ROLE_HOME`), or opens the system's home-app settings when the role
is already held or cannot be requested. Ara declares `MAIN` + `HOME` + `DEFAULT`
next to its normal launcher entry, so it can be switched back from the system
settings at any time.

---

## Client certificate (mutual TLS)

1. Install the certificate as a *VPN & app user certificate* (Settings →
   Security → Encryption & credentials → Install a certificate), or via MDM.
2. In the shell settings, **Select certificate** and pick its alias.

Ara stores only the **alias**. When the configured origin asks for a client
certificate (`onReceivedClientCertRequest`), the key and chain are taken from
the Android KeyChain off the main thread and presented — to that origin only;
any other host is refused. Without a selected certificate the request proceeds
without one (and is asked again later). The same certificate is used for model
downloads from the configured origin; that client follows no redirects itself,
so a redirect to another host is fetched without the certificate.

Files the page downloads from the configured origin are fetched the same way
(with the WebView's cookies) into the phone's **Downloads** (MediaStore, up to
200 MB, the name from `Content-Disposition` made safe); a redirect to another
host, and any download from another origin, opens in the browser instead.

---

## Wake phrase

Say the phrase and the web app opens a thread in continuous voice mode. Ara
runs [openWakeWord](https://github.com/dscripka/openWakeWord) on the device
with ONNX Runtime: 16 kHz mono audio in 80 ms frames → `melspectrogram.onnx`
(scaled `x/10 + 2`) → 76-frame windows → `embedding_model.onnx` → the last 16
embeddings → the phrase model → a score. A score at or above the threshold
(default 0.5) is a wake; a 2 s refractory period suppresses repeats. The
pipeline was checked against openWakeWord's own Python implementation on the
v0.5.1 release models (scores equal to within 1e-6, same detections).

On a wake Ara plays a short chime, brings itself to the front (turning the
screen on and asking to dismiss the keyguard), and dispatches `wake` to the
page once the page can open the mic: Ara is resumed, its window has focus and
the phone is unlocked. A wake that cannot get there within a minute (or whose
unlock the user cancels) is dropped. The mic is then left to the page;
detection resumes once the page reports the mic inactive (or after 10 s if it
never takes it).

A wake heard while Ara is in the background (mode *Always*) cannot start the
activity directly (Android 10+ blocks that), so it posts a high-priority
**full-screen notification**: on a locked or sleeping phone it opens Ara at
once, otherwise it shows as a heads-up to tap. Android 14+ grants full-screen
notifications by default only to calling and alarm apps; the wake section of
the shell settings shows a button to allow it when needed
(`USE_FULL_SCREEN_INTENT`). To have Ara come forward at once even while the
phone is unlocked with another app in front, allow it to **display over other
apps** (`SYSTEM_ALERT_WINDOW`, also offered in the wake section): that
permission exempts the app from the background-start block, so the wake starts
the activity directly and the notification is cancelled as soon as Ara takes
the wake. With Ara's notifications off (permission denied,
turned off for the app, or the "Wake phrase heard" channel blocked) a background
wake cannot reach Ara: the wake section says so and links to the app's
notification settings, and such a wake plays no chime. The wake intent carries a one-time token valid for
a minute (as long as a wake may wait for the shell), so other apps cannot fake a wake, and a recreated activity never replays
one.

**Modes:** *Off* (default, the mic is never held) · *While the home screen is
visible* (runs only while Ara is in front) · *Always* (a foreground service of
type microphone with an ongoing notification; Android 14 lets it start only
while Ara is in front, so it starts when you open Ara; if it is refused, it is
retried later with a growing delay). Detection pauses while the web app
reports the mic in use. A microphone error stops detection for 30 s (doubling
up to 10 min on repeated errors), or until Ara is opened again.

**Models are not bundled** (licence and size). In the shell settings:

1. **Download feature models** — `melspectrogram.onnx` and
   `embedding_model.onnx` from openWakeWord's v0.5.1 release.
2. **Phrase model** — download from a URL (default: the release's
   `hey_jarvis_v0.1.onnx`, for testing) or **Choose file…** to import an
   `.onnx` from the device.

A downloaded or imported model replaces the old one only after it loads.

### Recommended phrase: "Hey Retinue"

"Hey Ara" and "Okay Ara" are short, mostly vowels, confusable with common names
and words, and "Ara" is said whenever the system is merely talked *about*.
**"Hey Retinue"** (/heɪ ˈrɛtɪnjuː/, four syllables) is rare in both German and
English, its /t/ and /nju/ give the detector clear evidence, and it is still
one easy breath. A false wake only costs a chime: nothing is sent unless speech
follows, and an unused wake closes the mic again.

To train it: open openWakeWord's
[automatic model training notebook](https://github.com/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb)
(Colab works), set the target phrase to `hey retinue`, run it (it synthesizes
thousands of variations with Piper TTS and trains the small classifier), and
export the `.onnx`. Put the file somewhere the phone can reach — e.g. on the
Retinue host, where the client certificate is used for the download — and enter
its URL, or copy it to the phone and use **Choose file…**. Raise the threshold
if it wakes too easily, lower it if it is hard to trigger.

---

## Known gaps

- **Web Push does not work inside Android WebView.** In the shell, push
  notifications still need the PWA installed in Chrome, or a follow-up
  (UnifiedPush, or a light poll of `/attention`).
- With the phone **locked**, a wake in mode *Always* turns the screen on, shows
  Ara over the lock screen and asks to unlock; the wake reaches the web app
  (which then opens the mic) once the phone is unlocked. Without the
  full-screen-notification permission (Android 14+) it is only a heads-up
  notification.
- On WebViews without `WEB_MESSAGE_LISTENER` (only very old ones) there is no
  bridge: no wake event, native speech or settings link for the page.

---

## Architecture

```
shell/     ShellUrls, UrlRouter, ShellDecisions,   — pure: origins, navigation routing, Back/Home/wake
           ShellMessages, FileNames                   decisions, the bridge wire format, download
                                                      file names (unit-tested)
launcher/  LauncherEntry, LauncherConfig,           — pure: entry types (folders hold apps only), JSON
           FolderKind                                 codec, edits, folder types (unit-tested)
           AppCatalog, FolderIcons                  — launchable apps and their icons, folder images
wake/      WakeLogic (WakeMode, WakeScorer,         — pure: scoring, the mic gate (unit-tested)
           WakeGate), WakeFeatures                  — pure: openWakeWord's streaming buffers (unit-tested)
           OnnxWakeModels                           — the three ONNX models (ORT Java API)
           WakeListener, WakeRuntime, WakeService,  — mic thread, process-wide owner, foreground service,
           WakeModelFiles                             model download/import
web/       ShellWebView, ShellWebClients,           — the WebView, its setup and callbacks,
           ShellBridge, NativeSpeech,                 the bridge, TextToSpeech,
           ShellIntents, ShellDownloads               intents to Android, page downloads into Downloads
data/      ShellSettings, AraStore                  — settings and launcher persistence (DataStore)
net/       AraHttp                                  — KeyChain client certificate, mTLS OkHttp client
ui/        MainActivity, ShellViewModel,            — the Compose shell, settings, bar, drawer,
           LauncherBar, AppDrawer, LauncherFolders,   folders (open folder, folder editor)
           SettingsScreen
assets/    retinue-shell.js                         — page side of the bridge and the speech stand-in
```

The branching rules live in framework-free classes covered by JVM unit tests;
the Android classes only gather inputs and carry out the answers. The chime is
`app/src/main/res/raw/chime.wav`.
