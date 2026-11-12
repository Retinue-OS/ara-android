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
  **native text-to-speech** where the WebView has none, the **client
  certificate** (mutual TLS) and stored **sign-in** for the Retinue edge, the
  list of **endpoints** (several Retinue servers, one shown at a time),
  **Health Connect sync** (the phone's health data, as files, to the Retinue
  server — per endpoint, where switched on), and the **keyboard for other
  devices** (the phone as a Bluetooth keyboard, so Retinue can type into a
  headset, a TV or another computer, passwords from the password manager
  included).

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
signed release APK (see `keystore/README.md`) as a GitHub pre-release tagged
`nightly-YYYY-MM-DD` (`-Xn` for a manual run). Debug and release builds are
both signed with the checked-in test certificate, so any CI APK installs over
the previous one without uninstalling. Both workflows pass the commit as
`ARA_BUILD_SHA`, and the nightly its tag as `ARA_BUILD_TAG`; they end up in
`BuildConfig` and in the version shown in the settings, and the updater (see
[Updates](#updates)) compares them with the channel.

---

## First run and settings

**Settings is one page, the app's own** (`assets/settings/`): Ara shows it in a
WebView of its own at an address nothing on the internet answers
(`https://appassets.androidplatform.net/settings/`, the one Google reserves for
this; the app serves the page from the APK and lets no request for that address
leave the phone). So it opens with or without a server: on first run, when the
dashboard cannot load, from the web app's gear, from the long-press menu of any
launcher-bar entry and All apps, and from the card over a page that did not
load. It has three parts, from the phone outwards:

1. **This app** — what holds on this phone whichever endpoint is shown: the
   endpoints themselves, voice timing and the shelf, home screen, split screen,
   the launcher bar and updates.
2. **This app with *endpoint*** — what this phone does with the endpoint shown
   now: its health data, its wake phrase, whether a call asked for by
   voice there is placed directly, and whether its page may type on other
   devices (and, once the app receives them, its notifications).
3. **This instance** — the endpoint's own settings (its name and colour, agent
   models, the system pages), embedded from the server's
   `/settings-instance.html`, which the server lets only itself and this page
   frame. It is what a browser shows on the dashboard's own Settings page; when
   it cannot be shown here, a button opens the dashboard there. Its links open
   in the dashboard, and the page closes.

On first start there is no endpoint yet, so the page opens at a new one.

| Setting | Meaning |
|---|---|
| **Retinue endpoints** | The Retinue servers this phone can show, one at a time. Each has a **base URL** (the web app's origin, e.g. `https://agents.example.com`; `https://` is assumed if omitted — pages on this origin stay in the shell, and nothing else gets the bridge, the microphone, the certificate or the password), an optional **client certificate** (the installed certificate Ara presents to that origin, mTLS; the editor also imports one from a `.p12` or `.pfx` file, see [Client certificate](#client-certificate-mutual-tls-and-sign-in)), an optional **sign-in** (user name and password for an edge that asks for one instead of a certificate; stored encrypted, see [Sign-in](#client-certificate-mutual-tls-and-sign-in)), the **health data** switch (whether Health Connect data goes to this endpoint's letterbox; off for a new endpoint) and **Keep working in the background** (see [Instance states](#instance-states); off for a new endpoint). The endpoint shown is marked *Active*, the others *Background* or *Disabled*; *Use* shows another and *Edit* edits one. Back from the editor saves what was changed; changes that cannot be saved as they are (no https address, a user name without a password or a password without a user name) are asked about, *Discard* or *Keep editing*, never dropped silently. The list shows each endpoint under the name its server gives itself: the `short_name` of its web manifest, which the dashboard's Settings › Instance sets; until that is fetched, the host stands in, and when two servers share a name the host is appended. There is no label of the shell's own. |
| **Voice** | When speech recognition ends in a continuous conversation: the pause that ends a take, and how long the mic waits for speech at all. For every endpoint (see [Per-device preferences](#per-device-preferences)). |
| **Shelf** | How many minimized threads, chats and projects the dashboard's shelf keeps. For every endpoint, as above. |
| **Home screen** | Shows whether Ara is the default home app; the button asks Android to make it so. |
| **Split screen** | Open apps beside Ara, and keep Ara in recent apps (both off by default; see [Split screen](#split-screen)). |
| **Launcher bar** | Opens the bar's editor, native as the bar is: add links, web-app pages and folders, reorder, edit, remove, reset to defaults. The card also offers usage access for the tray's order (see [The tray](#the-tray)). |
| **Updates** | Install updates automatically (off by default) and the channel: nightly builds, or releases (none yet; see [Updates](#updates)). |
| **Health data** (with the endpoint) | Whether Health Connect data goes to the endpoint shown (off for a new endpoint) and, while it does, the last update, *Update health data now*, updating when Ara comes to the front, and how often (see [Health Connect sync](#health-connect-sync)). |
| **Wake phrase** (with the endpoint) | The endpoint's own wake phrase on this phone: mode, with what each needs from Android, the sensitivity, and its phrase model, chosen from the phone's list of models (one list, kept for every endpoint), plus the other endpoints listened for in the background; under *Advanced*, a model from a URL and the feature models (see [Wake phrase](#wake-phrase)). |
| **Phone calls** (with the endpoint) | **Call directly by voice**: whether a call you ask for in a voice conversation with the endpoint shown is placed without a tap, instead of opening the dialer. Each endpoint has its own switch, off for a new endpoint (see [Phone calls](#phone-calls)). |
| **Typing on other devices** (with the endpoint) | **This server may type on other devices**: whether the endpoint's page may make the phone's keyboard type at all. Each endpoint has its own switch, off for a new endpoint; the phone's own keyboard switch must be on as well (see [Keyboard for other devices](#keyboard-for-other-devices)). |

Nothing environment-specific is baked into the APK. The one base URL and
certificate alias of earlier builds become the first endpoint on update, with
health data on, as it was for that server; the current endpoint's address and
alias are mirrored back into those two keys, so an older build still finds its
server.

---

## The web view

- JavaScript, DOM storage and service workers are on; media may play without a
  tap (a wake arrives without one, and the web app's chime and voice-activity
  detector need audio to start).
- The user agent ends in ` RetinueShell/6 (Android)` (6: `placeCall`; 5: the
  keyboard for other devices; 4: the app has a settings page of its own and
  holds the dashboard's per-device preferences; 3: the endpoint members of the
  bridge exist; 2: the Health Connect members).
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
- **HTTP authentication**: the endpoint's stored user name and password answer
  the first challenge of a page load by themselves; otherwise (none stored, or
  it was refused) a dialog asks, with *Remember for this endpoint* to store
  what is typed. The edge accepts either a client certificate or the password.
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
  certificate (when none is selected), Switch endpoint (when there is another)
  and Settings. The settings page then starts with the same reason and URL,
  the page's last console errors under a fold, and Retry.

## Shell ↔ web contract

The web app detects the shell and talks to it through:

- `window.RetinueShell` — installed at document start on the configured origin
  only: `{platform: 'android', version, setVoiceActive(bool), openSettings(section?)}`
  plus the Health Connect members (see [Health Connect sync](#health-connect-sync)):
  `healthConnectAvailable()` → `"true"`/`"false"`, `healthSync()`,
  `healthSyncStatus()` → a JSON string, `setHealthSyncInterval(minutes)`,
  `setHealthAutoSync(bool)`; and the endpoint members (RetinueShell/3, see
  [Endpoints](#endpoints)): `endpoints()` → a JSON string
  `[{id, label, current}]`, `selectEndpoint(id)`. `openSettings('endpoints')`
  opens the settings page at the endpoint list, or at the editor straight away
  with one endpoint. RetinueShell/4 adds `settingsPage: true` (the gear opens
  the app's page) and the per-device preferences: `deviceSettings()` → a JSON
  string of them, `setDeviceSetting(key, value)` (`null` removes one); see
  [Per-device preferences](#per-device-preferences).
  RetinueShell/5 adds the [keyboard for other devices](#keyboard-for-other-devices):
  `keyboardStatus()` → a JSON string `{available, enabled, connected,
  host: {name}|null, layout}` (the device's name only; its Bluetooth address
  stays in the app); `keyboardType(text, opts)` with `opts`
  `{id, enter}` (an object or its JSON; at most 4000 characters, returns at
  once; a line break is typed as Shift+Enter, a tab as Tab);
  `keyboardKey(name, opts)` with `opts` `{id}` — `enter`, `tab`, `backspace`,
  `escape`, `up`, `down`, `left`, `right`, `home`, `end`, `delete`, `space`;
  `keyboardSecret(opts)` with `{id, label, enter}` (opens the native secret
  dialog); `keyboardOpen()` (the native keyboard screen); `keyboardCancel()`
  (stops a text being typed and closes an open secret dialog or
  confirmation, which then ends as `cancelled`; never a secret being
  typed). Typing, keys and the secret dialog are taken only from a
  page whose endpoint may type on other devices (otherwise `not-enabled`,
  and `keyboardStatus()` says the keyboard is not enabled and nothing of the
  device), only while the user is looking at the dashboard — resumed,
  focused, unlocked, not under the app's own settings page or editors
  (otherwise `not-in-front` at once) — and not while the phone's
  confirmation or the secret dialog is open (`busy`). What acts on the
  other device is confirmed on the phone first (see
  [From the dashboard](#keyboard-for-other-devices)); declined or not
  answered in time, it ends as `cancelled`.
  An `id` is echoed when it is 1–64 of `A–Z a–z 0–9 . _ : -`, and dropped
  otherwise; invalid arguments throw.
  RetinueShell/6 adds `placeCall(number)` (see [Phone calls](#phone-calls)).
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
  Health sync sends `type` `health-sync` with a `state`: `started` (with
  `trigger`), `done` (`count`), `error` (`error`) or `skipped` (`reason`:
  `interval`, `no-permission`, `unavailable`, `no-backend-support`,
  `disabled`); a changed endpoint list sends `type` `endpoints`, changed
  per-device preferences `type` `device-settings` with the `keys` that
  changed. The keyboard sends `type` `keyboard` with a `state`: `typed`
  (`count`, the characters typed — a CRLF line break as one; a key press
  too), `error` (`error`:
  `not-enabled`, `not-connected`, `not-in-front`, `too-long`,
  `unsupported-char`, `busy`, `cancelled`, `failed`),
  `secret-typed`, `secret-cancelled` or `status` (re-read `keyboardStatus()`;
  it is current before any `keyboard` event arrives; while the page's
  endpoint may not type, only when that switch changes), each with the request's
  `id` where it had one. Nobody has to take these.
  A `placeCall` is answered with `type` `call`: `state` `placed` (with
  `number`), `dialer` (`reason`: `emergency`, `service-code`, `disabled`,
  `no-permission`; with `number`) or `refused` (`reason`: `invalid-number`,
  `not-in-voice`, `no-telephony`, or `failed` with `number`).
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

### Phone calls

`RetinueShell.placeCall(number)` is how a call the user asked for by voice
gets made without a hand on the phone (the web app sends it for Ara's
`[[call: …]]` in a spoken conversation). It returns at once; the outcome comes
as the `call` event above. A call with no number at all (`placeCall()`,
`placeCall(null)`) sends nothing and gets no event; an empty string is
answered like any other that is no number, with `invalid-number`. The shell
decides (`shell/CallLogic.kt`), in this order:

1. The number is normalized — spaces, dashes, dots, parentheses, slashes and a
   leading `tel:` dropped, and the `(0)` of `+44 (0)20 …` — and must then be
   an optional `+` followed by digits, `*` and `#` with 3 to 20 digits and at
   most 32 characters (no `,` pauses or `;` extensions). Otherwise: refused,
   `invalid-number`. A phone without telephony: refused, `no-telephony`.
2. **Only inside a spoken conversation**: the page must hold
   `setVoiceActive(true)`, as the web app does for a whole continuous
   conversation (a microphone grant to the page counts for its 10 s too).
   Otherwise nothing starts, not even the dialer: refused, `not-in-voice`.
   The page reports this itself, so it keeps a number in a typed chat or on
   a page just loading from dialling; it is no barrier against the page,
   which the bridge trusts as the configured origin's main frame. A new
   document starts outside a conversation.
3. **Emergency numbers always open the dialer** (`reason` `emergency`;
   `TelephonyManager.isEmergencyNumber`, a failed check counts as one).
   Android lets no third-party app call them directly. Hands-free, use
   "Hey Google" or the phone's Emergency SOS for those.
4. **Service codes always open the dialer** (`service-code`): anything with
   `*` or `#`, such as `*21*…#` (forward every call) or a USSD request like
   `*123#`. Placed directly the phone would carry them out unseen; in the
   dialer the user sees the code and starts it.
5. **Call directly by voice** off for the endpoint whose page asks (each
   endpoint has its own switch, see below): the dialer opens with the number
   (`disabled`). On without Android's `CALL_PHONE` permission, which turning
   it on asks for: the dialer (`no-permission`); the settings say so and
   offer to ask again, or open Android's page for Ara once it no longer asks.
6. Otherwise `ACTION_CALL`: the call starts, **on speakerphone**
   (`TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE`) — the user asked by
   voice and may not be holding the phone. An intent that fails to start is
   reported as refused, `failed`.

**The switch is each endpoint's own.** *Call directly by voice* is on the
settings page under **This app with *endpoint*** → *Phone calls*, for the
endpoint shown, and is stored with it (`Endpoint.directCalls`): off for a new
endpoint, gone with the endpoint, and left as it is when the endpoint is
edited (the editor has no such switch; like the certificate and the password,
it stays the endpoint's at a new address too). On for one server, it gives
the others nothing: the bridge takes messages from the current endpoint's
origin only, and the shell answers with the switch of the endpoint the page
was loaded for, as long as that is still the endpoint shown
(`CallLogic.directCalls`). With no endpoint, no page of one loaded, or one
removed or left meanwhile, it counts as off. Two endpoints at the same origin
are one server to the bridge: a page there is answered with the switch of
whichever of them is shown. Only the app's own settings page sets the switch,
so no dashboard page can give itself the right. Android's `CALL_PHONE`
permission, and whether the phone can make calls at all, are the phone's: the
same for every endpoint.

The web app gives the user 5 s before it sends `placeCall`, with the mic open:
speech cancels the call (unless Ara marked a second attempt `no-voice-cancel`,
for a loud room). It closes its mic before it sends, so the call has it.
Once the call is dialling the shell cannot hear the user any more, nor tell
when the other side answers (only the default phone app can), so there is no
voice cancel after that.

### Per-device preferences

A few of the dashboard's preferences belong to the device, not to the server:
the voice timeouts, the shelf size, the layout of its split views. In a
browser the dashboard keeps them in local storage, per origin; inside Ara the
app holds them, so they are the same for every endpoint and survive switching.
The app takes only the dashboard's own keys (`data/DeviceWebSettings.kt`: an
allowlist, bounded in size), so one server cannot hand data to another
through them. The dashboard reads them with `deviceSettings()` and writes with
`setDeviceSetting()`; a value the app does not have yet is taken over from
the page's local storage once. The settings page edits the voice timeouts and
the shelf size directly.

### The settings page and the app

The page talks to the app through `window.AraSettingsNative`, injected by
`addWebMessageListener` into the page's own origin, main frame only
(`settings/SettingsBridge.kt`); the instance section, another origin, gets
nothing. The page sends `hello` once loaded and one message per action
(`settings/SettingsMessages.kt` lists them); the app answers with the whole
`state` whenever anything in it changes (`settings/SettingsState.kt`), plus
the answers to an endpoint save, to the certificate chooser and to a
certificate import, and health sync events. Unlike a dashboard page, it may change the endpoints, their
certificates and passwords, the health switch and the call switch; the app still checks every
endpoint it saves, and what is stored already (a password) never goes to the
page. Its Content-Security-Policy (`settings/AppPage.kt`) allows its own files,
a frame of the current endpoint's origin (the page loads afresh when that
changes) and nothing else; nobody may frame it, and the dashboard's WebView
never loads anything from its address. A policy cannot name an IPv6 address,
so an endpoint at one gets the way into its dashboard instead of the frame.
The frame signs in as the dashboard does (certificate, stored password) and
loads afresh when the endpoint, its certificate or its sign-in changes. The page's decisions live in
`assets/settings/logic.mjs`, tested with `node app/src/test/js/settings-page.test.mjs`,
which also checks that the page has every element its script (`page.mjs`) works on.

---

## Links into the app

A page can send the phone from a browser into Ara with an intent link, e.g. the
web app's `/sync-health` page:

```
intent://open?url=<percent-encoded full URL>#Intent;scheme=retinue-shell;package=os.retinue.ara;S.browser_fallback_url=<percent-encoded URL>;end
```

Chrome hands Ara `retinue-shell://open?url=…` (the opaque
`retinue-shell:open?url=…` works too; the activity declares the scheme with
`BROWSABLE`). Ara decodes the URL and compares its origin — scheme, host,
port, default ports filled in — **exactly** with the current endpoint's (http is
never upgraded to https). A match loads the URL, fragment included, in the page
(instead of the start page when the link started Ara) — in the canonical form
the check saw, not the link's own spelling: the WebView would canonicalise a
host such as `straße.example` (IDNA 2008) differently from the check (IDNA
2003), so the spelling could pass as one host and load another. A URL the
parser does not take as a whole is refused. A link for **another configured
endpoint** asks first ("Switch to <name>?"); on Switch that endpoint becomes
the current one and the link loads there. Anything else loads nothing and sets
nothing up: Ara says "This link is for <origin>, which is not set up in this
app."; a malformed link is ignored with a short message. A link is never
replayed by a recreated activity or from Recents. There are no Android App
Links: the origin is configured at runtime. Implementation:
`shell/ShellLinks.kt`.

---

## Health Connect sync

Ara reads the phone's Android **Health Connect** data and writes it, unchanged,
as files into the Retinue server's letterbox, where the health chamber files
it. It is a thin pipe: it sends what Health Connect's Changes API returns
(upserts as full records, deletions as ids) and nothing else; no merging,
correcting or dropping. No Google Play services are involved: the client
library binds to Health Connect itself (built into Android 14+, the Health
Connect app below that). Without Health Connect, `healthConnectAvailable()` is
`"false"` and nothing happens.

**Types** (read only): `HeartRateRecord`, `RestingHeartRateRecord`,
`HeartRateVariabilityRmssdRecord`, `SleepSessionRecord`, `BloodPressureRecord`,
`BloodGlucoseRecord`, `OxygenSaturationRecord`, `StepsRecord`; plus
`READ_HEALTH_DATA_IN_BACKGROUND` where Health Connect offers it. Whatever
subset the user grants is synced.

**Where it may go.** Each endpoint has its own **health data switch** (on the
settings page, with the endpoint shown, and in the endpoint's editor; off for
a new endpoint, on for the one an update carried over). Off, nothing is read
from Health Connect for that endpoint and nothing is sent, not even on
request: a deliberate sync reports `skipped`/`disabled`, and the status's
`enabled` is false. Only the app's own page sets the switch: it decides which
server receives the data, so no dashboard page may.

**Endpoints in the background.** An endpoint that keeps working in the
background (see [Instance states](#instance-states)) and has its switch on is
synced too, by the automatic syncs only: after the shown endpoint's sync, each
such server gets its own, with its own interval and back-off (kept per origin),
silently — the status, the events and the settings page's line stay the shown
endpoint's. A deliberate sync is for the endpoint shown only. Tokens and
rounds work for them exactly as for the shown one.

**When.** When Ara comes to the front, at most every 5 minutes (adjustable,
`setHealthSyncInterval`; counted from the last sync that ran; off with
`setHealthAutoSync(false)`; both kept on the device). Such a sync on a phone
without Health Connect, or where nothing was ever granted, does nothing at all;
one that is not due yet reports `skipped`/`interval`. A **deliberate** sync
(*Update health data now* on the settings page, `healthSync()` from the web
app, or the `/sync-health` page) always runs; asking again within 5 s, or while one runs, is
the same request. It is the only one that asks for permissions, through Health
Connect's own dialog: whenever nothing is granted, and once when only some
types are. Its trigger is `link` on `<base>/sync-health`, `manual` elsewhere.
One sync runs at a time; Health Connect and the network are used off the main
thread.

**What.** With a stored Changes token for the server (each server has its own,
kept per set of granted types): every change since (`kind: changes`),
deletions included. Without one (`initial`), or when it expired after about 30
days (`resync`): every record of the last 30 days as upserts, with a fresh
token taken before reading, so nothing falls between the two.

**Where.** Before Health Connect is read, Ara probes the letterbox share
`<origin>/inbox/`: a `PROPFIND` of depth 0, and when that is a 404 an
`OPTIONS` on the same path (a backend with only `PUT /inbox/<name>` routed
knows nothing of the collection). A 404 to both means the server has no
letterbox, `skipped`/`no-backend-support`, and nothing is read; a server that
does not speak `PROPFIND` (405, 501), or answers the `OPTIONS` with anything
but a 404, may still take the `PUT`, so the sync goes on and the `PUT`'s own
404/405 decides; 401/403, a redirect or a server error is an `error` at
either step. Each part
(at most 5000 changes) is then one file, written with a plain `PUT` (with
`Content-Length`) as
`<origin>/inbox/hc-<sent_at as 20261101T171500Z>-<batch_id>-p<part>of<parts>.health-connect.json`,
authenticated as the WebView is: the endpoint's client certificate and stored
password (one credential for the dashboard and the letterbox alike), and the
WebView's cookies for the origin. One round has one `batch_id` and one
`sent_at`, the same in every part. Any 2xx stored the part; anything else
(apart from the 404/405 above) is an `error`.

A round's parts are written to the device first (one at a time, in Ara's own
files) and uploaded one by one, each file deleted once the server has it. The
token advances only when every part of the round was stored. Tokens and rounds
are kept **per server** (origin): each server's token marks where in Health
Connect's change log its own deliveries stand, so a server that was not shown
for a while gets exactly what changed since its last delivery when it is shown
again — deletions included, nothing twice — as long as Health Connect still
has that token (about 30 days; after that it gets the 30-day window again). A
server without a token starts with the whole window. A round that failed
halfway stays on the device, in its server's own folder, and the next sync to
that server resumes it first — before Health Connect is even asked, so a
permission revoked meanwhile cannot strand data already read — from the first
unsent part, under the same `batch_id` and `sent_at`: the server sees one
batch and no orphans. Showing another endpoint leaves a round where it is: it
waits for its server. It is discarded only when its endpoint is removed, its
address changed or its health switch turned off; a server's token is
forgotten only when no endpoint points at it any more. A token Health
Connect no longer accepts (after a reset or reinstall) is treated like an
expired one: the window is read again.

After a sync that stored nothing — no letterbox, or an upload error —
automatic syncs back off: the interval, doubled with each failure in a row,
capped at six hours (5 → 10 → 20 min …); a resume in that time reports
`skipped` with the reason of the last failure (`no-backend-support`, or
`interval`). A success on that server resets it; switching to another
endpoint leaves it behind: the back-off is the failing server's, kept per
origin. A deliberate sync never
waits and always probes again.

**The file** (`schema` `retinue-health-connect/1`):

```json
{"schema": "retinue-health-connect/1", "batch_id": "<uuid4 per round>",
 "sync": {"kind": "changes|initial|resync", "trigger": "resume|manual|link",
          "requested_types": ["HeartRateRecord", "…"],
          "window_start": "<ISO, initial/resync only>", "part": 1, "parts": 3},
 "client": {"app": "os.retinue.ara", "version": "<versionName>", "android_sdk": 35},
 "sent_at": "<ISO>",
 "changes": [{"op": "upsert", "type": "HeartRateRecord", "record": {…}},
             {"op": "delete", "id": "<Health Connect id>"}]}
```

A record has `metadata` (`id`, `data_origin` (package), `last_modified_time`,
`client_record_id` when set, `client_record_version`, `recording_method` +
`recording_method_name`, and `device` {`manufacturer`, `model`, `type` +
`type_name`} when present), instants as ISO-8601 UTC (`…Z`), zone offsets as
`*_zone_offset_seconds` when known, `start_time`/`end_time` for interval
records and `time` for instant ones. Units are in the key names: heart rate
`samples: [{time, bpm}]`; resting heart rate `bpm`; HRV
`heart_rate_variability_millis`; sleep `title`, `notes` (when set) and
`stages: [{start_time, end_time, stage, stage_name}]`; blood pressure
`systolic_mmhg`, `diastolic_mmhg`, `body_position`, `measurement_location`;
glucose `level_mmol_per_l`, `specimen_source`, `meal_type`, `relation_to_meal`;
SpO2 `percentage`; steps `count`. Every enumeration is Health Connect's int
with its name beside it in `<key>_name` (`"unknown"` for a value Ara does not
know yet).

**Status** (`healthSyncStatus()`): `{"available", "permissions":
"granted"|"partial"|"none", "last_sync_at": ISO|null (last success),
"last_result": "ok"|"error"|"skipped"|null, "last_error", "last_count",
"min_interval_minutes", "auto_on_resume", "enabled"}` (`enabled`: the current
endpoint's health data switch). An `interval` skip leaves the last
result alone; for other skips `last_error` holds the reason. The bridge is
asynchronous, so the page script answers from a copy: the status the shell put
into the script when it was installed, updated by the shell on every change
(before the event that goes with it). A setter's value shows at once.

**Why.** Health Connect shows Ara's explanation (`HealthRationaleActivity`,
reached through `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` and, on
Android 14+, `VIEW_PERMISSION_USAGE` / `HEALTH_PERMISSIONS`): the data goes only
to the configured Retinue server.

Implementation: `health/HealthSyncLogic.kt` (when, what, where; framework-free
and unit-tested), `health/HealthRecords.kt` (records → JSON, unit-tested),
`health/HealthSyncer.kt` (the order of one sync, against interfaces for Health
Connect and the letterbox; unit-tested with fakes), `health/HealthSyncRuntime.kt`
(the real Health Connect and OkHttp behind them, state) and
`health/HealthRoundStore.kt` (the rounds on the device, one per server).

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
slot is always **All apps**: a searchable list of launchable apps and installed
web apps (tap to open, long-press to pin it to the bar or put it in a folder).
It is what keeps other apps reachable once Ara is the home screen. Long-press a
bar entry to edit, move, or remove it, or to open it the other way: beside Ara
in split screen, or full screen (see [Split screen](#split-screen)); the editor
(Settings → Launcher bar) adds links, web pages and folders and reorders everything.

As home app Ara accepts pinned shortcuts (a browser's "Install app" / "Add to
home screen" — an installed web app from Edge or Chrome — and other apps'
shortcuts) after a confirmation. Like a newly installed app, an accepted one
is listed in All apps; the confirmation can also put it on the bar or into a
folder right away. From then on it behaves like an app: pin it to the bar, add
it to folders, move it from the bar into a folder. Taking it off the bar or
out of a folder keeps it in All apps; long-press it there and choose "Remove
from phone" to unpin it, which also takes it off the bar and out of every
folder. Web apps Chrome installs as WebAPKs are ordinary apps in All apps.

Uninstalled apps leave the bar by themselves, also from inside folders (a
folder stays, even when that empties it), and an uninstalled browser takes the
web apps it installed along. Android tells Ara when an app is uninstalled with
its data (`ACTION_PACKAGE_FULLY_REMOVED`, declared in the manifest, so it
reaches Ara also when it is not running). An app update, a disabled app, an
app on storage that is not mounted and (Android 15+) an archived app keep their
place; so does an app uninstalled with its data kept, whose tile then only says
that no app can open it.

App discovery uses a `<queries>` declaration for launcher activities — no
`QUERY_ALL_PACKAGES`.

### The tray

**Pull the bar up** for more lines of apps: every installed app and web app
that is not on the bar itself (apps inside folders too, but no folders), the
**most recently used first**, then the never-used ones by name. The lines look
like the bar and are as tall; let go and the tray settles on a whole line, a
quick flick takes it all the way up or down. It goes up as far as the web
app's top menu (its navigation row `retinue-nav`, or a page's own `header`),
which stays in view: the shell asks the page for the menu's bottom edge each
time the tray comes up, and leaves 48 dp when the page has none. With more
apps than fit, the lines scroll once the tray is all the way up; dragging them
down scrolls back to the top first, then takes the tray down.

The tray **goes down again** on a tap anywhere above it (on the dimmed page or
its menu; the tap does nothing else), when anything is opened from the bar,
the tray or elsewhere, on Back, on Home, when the keyboard comes up and when
Ara leaves the screen. Tap an app in the tray to open it; **long-press** it for
the same menu as in All apps: open it the other way (beside Ara in split
screen, or full screen), pin it to the bar, add it to a folder or a new one,
and for a web app remove it from the phone. A screen reader gets an action on
the bar's handle to bring the tray up or down.

**Recent use** comes from two places: every app and web app Ara opens (from
the bar, a folder, All apps or the tray) is recorded with the time, on the
device (the 300 most recent); and, once the user grants Ara **usage access**
(Settings → Launcher bar → *Allow usage access*, which opens the system page;
`PACKAGE_USAGE_STATS`), Android's own record of when each app was last on
screen, so apps opened from a notification, from Recents or from another app
count too. An item's last use is the later of the two; a web app counts only
its own launches (its package is the browser that installed it). Without
usage access nothing is asked for and only Ara's launches count.

### Folders

A folder holds apps: installed apps and installed web apps (pinned shortcuts,
see above; web apps Chrome installs as WebAPKs are apps anyway). Links, web
pages and other folders cannot go in, so folders never nest: the stored form
has room for apps and web apps only, and anything else found in a stored
folder is dropped when it is read.

- **Create** one with *New folder* in the long-press menu of **All apps**, with
  *New folder with this app* in an app's long-press menu inside All apps, or
  with **Add folder** in the launcher bar's editor (Settings → Launcher bar).
- **Type:** the editor offers standard folder types — Folder, Chat, Social,
  Media, Photos, Games, News, Travel, Work, Tools, Shopping, Finance, Health,
  Smart home. The type gives the folder its symbol and, until you name it
  yourself, its name, shown in the phone's current language. Types that match
  an Android app category (Social, Media, Photos, Games, News, Travel, Work)
  list the installed apps of that category first, as suggestions; apps declare
  their category in their manifest, so not every app has one (web apps have
  none).
- **Icon:** the type's symbol, or an image **from photos** (the system photo
  picker) or **from files** (any document provider: the device's storage,
  Drive, Nextcloud …). The image is cropped to its centre square, scaled to
  192 px and kept as a PNG in Ara's own storage, so the original may be moved
  or deleted. An icon that is replaced, or whose folder is removed, is deleted.
- **Apps:** the editor lists the folder's apps (reorder, remove) and the
  installed apps and web apps to add (searchable). Both also go in from All
  apps and the tray (long-press → *Add to …*) and from the bar (long-press one
  on the bar → *Move to …*). An app may be in several folders and on the bar
  as well.
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

## Split screen

Android keeps the home activity full screen, behind everything: the home screen
can never share the screen with an app. So Ara has a second window,
`SplitShellActivity`, an ordinary activity in a task of its own that Android may
put in one half of the screen. It is the same shell — the same page and the same
bar — and both windows show **one page in one state**: there is a single
WebView (with its bridge and speech, see `ShellPage`), which moves to the window
that comes to the front. You are always at the same place in the web app, a
voice conversation keeps going, and there is one connection. A window that does
not hold the page shows a placeholder (seen only when both are visible at once,
in a desktop or freeform mode; tap it to bring the page there).

What the split-screen window starts — bar entries, apps from folders and All
apps, links from the page — opens **beside it**, in the other half
(`FLAG_ACTIVITY_LAUNCH_ADJACENT`; a pinned shortcut is sent through its
`PendingIntent` with that flag). From a full-screen split window this enters
split screen on Android 12L and later; on Android 12 the app opens full screen.

Settings → **Split screen**:

- **Open apps beside Ara** (off by default): a tap on the home screen opens the
  app in split screen. The home screen brings up the split-screen window, which
  starts the app beside itself once it is in front. Long-press an app, web app
  or link on the bar, in a folder or in All apps for the other way (*Open full
  screen*, or *Open beside Ara* while the setting is off). An app that does not
  get there (one that refuses split screen, or Android 12) covers Ara full
  screen, and Ara's window goes to the back, so Back from the app leads home.
- **Keep Ara in recent apps** (off by default): whenever the home screen comes
  up and the split-screen window is not among the recent apps (never opened, or
  swiped away), the home screen starts it straight to the back (not during a
  wake or a conversation, nor over the lock screen), so its tile is there to
  pair with any app from Recents. Android shows a new task's splash
  screen even then, so this is a brief flash of the background, and the tile
  becomes the most recent one (the quick switch to the last app then lands on
  Ara once).

**Which half.** Android puts the app it starts beside Ara in its "side stage",
by default the bottom (portrait) or right (landscape) half, so Ara lands on top.
There is no API for an app to choose. Swapping the two once with the divider
(double-tap it on stock Android, or the swap button in its menu on One UI) is
remembered by the system, so later apps open on the other side.

---

## Client certificate (mutual TLS) and sign-in

The certificate lives in the Android KeyChain, as a *VPN & app user
certificate*. There are two ways to put it there:

- **Import it in Ara.** In the endpoint's editor (Settings → the endpoint's
  *Edit*, or *Add endpoint*), **Import certificate…** and pick a PKCS#12 file
  (`.p12` or `.pfx`) that holds the certificate and its private key, copied
  to the device in any way (Syncthing, a download, a USB transfer). Android's
  own certificate installer asks for the file's password and a name for the
  certificate (where it asks what the certificate is for: *VPN and apps*).
  Android's chooser then opens on it; picking it there selects it for the
  endpoint, saved with the endpoint as always. This needs no developer mode,
  adb or system settings page, so it also works on devices whose settings
  offer no *Install a certificate*, such as a Meta Quest.
- **Install it in Android's settings** (Settings → Security → Encryption &
  credentials → Install a certificate), or via MDM; then, in the endpoint's
  editor, **Select certificate** and pick its alias.

Ara keeps no key store of its own. An imported file is read into memory only,
checked to look like a PKCS#12 store of at most 256 KB
(`net/CertificateImport.kt`; the installer, which has the password, reads the
whole store), handed to the installer in its intent
(`KeyChain.createInstallIntent()` with `EXTRA_PKCS12`, and the file's name as
`EXTRA_NAME`) and wiped (`net/CertificateInstaller.kt`); the key is Android's
from then on.
Installing does not give Ara the key: Android grants it only through its
chooser, which is why the import ends there. The editor says how an import
ended: not a PKCS#12 file, unreadable or too large; installing certificates
turned off on the device (a device policy, `DISALLOW_CONFIG_CREDENTIALS`); no
certificate installer Ara can open; or nothing installed, when the installer
was left, or closed at once on a device that withholds it from apps. Whether
Meta Quest OS lets apps open the installer has not been verified yet.

Ara stores only the **alias**, per endpoint. When the configured origin asks
for a client certificate (`onReceivedClientCertRequest`), the key and chain are
taken from the Android KeyChain off the main thread and presented — to that
origin only; any other host is refused. Without a selected certificate the
request proceeds without one (and is asked again later). The same certificate
is used for the health upload and for model and manifest downloads from the
configured origin; that client follows no redirects itself, so a redirect to
another host is fetched without the certificate.

An edge that asks for a **password** instead (the Retinue edge accepts either)
gets the endpoint's stored **sign-in**: a user name and a password, entered in
the editor or remembered from the sign-in dialog. The password is sealed with
a key that never leaves the Android Keystore (AES-256-GCM, `data/CredentialVault.kt`)
and stored in that form; a backup restored to another phone cannot open it, and
the dialog asks again. It is sent only to that endpoint's host, with the
WebView's first challenge of a page load (a second challenge means it was
refused: the dialog takes over) and, through OkHttp's authenticator, once per
request of the health upload and the downloads. With both a certificate and a
password set, the certificate decides the TLS handshake and the password is
never asked for.

## Endpoints

The shell keeps a list of Retinue servers (`data/Endpoint.kt`, stored as JSON in
the preferences) and shows one, the **current** endpoint; everything that is
"the configured origin" above — the bridge, the microphone, the certificate,
the password, the letterbox, the switch for direct calls — is the current
endpoint's. Switching (*Use* on the settings page, a link for another endpoint,
or `RetinueShell.selectEndpoint(id)`) reinstalls the bridge for the new origin
and loads its start page; the settings page stays open and shows the new
endpoint's parts. Health Connect sync follows the current endpoint
(and, for automatic syncs, the endpoints working in the background, see
[Instance states](#instance-states)): a sync still running for the old one stops, and its round, if any,
waits on the device for that server (see "Health Connect" above); each
server's Changes token is its own, so a server shown again gets what changed
since its last delivery.

The web app sees the list through `RetinueShell.endpoints()`: `[{id, label,
current}]` — the label the shell shows, never the other servers' URLs — and
may ask to switch or to open the editor (`openSettings('endpoints')`). It
cannot add, change or remove an endpoint: whatever origin the shell is pointed
at receives the bridge, the microphone, the certificate and the password, so a
page must not be able to point it anywhere. A change to the list while a page
is open is pushed to it (`endpoints.status`) and announced with an `endpoints`
event.

### Instance states

Every endpoint is in one of three states on the phone (`InstanceState`):

- **Foreground** — the one shown (*Active*). Everything above is its.
- **Background** — not shown, but still working for the phone: its health
  data is synced (where its switch is on, see
  [Health Connect sync](#health-connect-sync)) and its wake phrase is still
  listened for; hearing it brings that endpoint to the front.
- **Disabled** — not shown, and nothing runs for it until it is shown again.

Which of the last two an endpoint is while another is shown is its editor's
**Keep working in the background** switch (`Endpoint.background`, off for a new
endpoint and for the endpoints of earlier builds). Showing another endpoint
moves the one shown so far into its own state: background or disabled.

**Names.** An endpoint is shown under the name its server gives itself: the
`short_name` of its web manifest (`<base>/manifest.webmanifest`), which the
dashboard's Settings › Instance sets. The shell fetches it with the endpoint's
own certificate or password (`net/EndpointNames.kt`) when an endpoint is saved
or switched to, when the list is shown and after a page of it has loaded, at
most once an hour, and keeps the last answer. Until the first answer the host
stands in; when two endpoints carry the same name (two unnamed instances both
say "Retinue") the host is appended to tell them apart.

Files the WebView cannot show itself (a PDF, any download) are fetched from the
configured origin the same way, with the WebView's cookies, up to 200 MB; a
redirect to another host, and any file from another origin, opens in the
browser instead. The response's `Content-Disposition` decides what happens next,
as in a browser:

- **`inline`** — the page asked to *see* the file (the web app's view link):
  it opens in the app Android has for its type, from a private copy in the
  cache (handed over by a `FileProvider`; a copy a day old is removed when the
  next one is made), so reading the same invoice twice leaves no
  `invoice(1).pdf` in Downloads. When no installed app opens the type, the file
  is saved to Downloads instead, and a toast says so.
- **anything else** — saved to the phone's **Downloads** (MediaStore).

The file's name comes from that header, read by the shell itself
(`shell/ContentDisposition`, RFC 6266: `filename*` first, then `filename`) and
made safe (`shell/FileNames`). Android's own `URLUtil.guessFileName` names it
only when the header carries none: it takes a name only from a bare
`attachment; filename="…"`, and otherwise falls back to the URL's last segment.

---

## Updates

Ara is not in an app store, so it updates itself from the GitHub releases of
`Retinue-OS/ara-android`. The **Updates** card of the settings page has:

- **Install updates automatically** (off by default). Turning it on checks at
  once; after that Ara checks the channel whenever it comes to the front, at
  most every six hours (the nightly is built once a day; a check that failed,
  offline or rate-limited, is retried after half an hour at the earliest), and
  installs a new build the next time it comes to the front on an unmetered
  network — Wi-Fi, typically; a metered hotspot does not count (a nightly is
  about 85 MB; *Install* in the settings does not wait for that).
  Installing restarts Ara (Android kills the app it replaces and, as it is the
  home app, starts it again; the page is reloaded), so an automatic install
  waits for the end of a voice conversation (half an hour at most) and
  announces itself with a toast. Off, nothing is fetched until *Check for
  updates* is tapped, and a build it finds waits for *Install*.
- **Channel**: which releases count. *Nightly builds* are the pre-releases the
  nightly workflow publishes (tags `nightly-…`), read from the list of recent
  releases. *Releases* are the proper, non-pre-release ones — GitHub's "latest
  release". None has been published so far, so that channel reports "no build
  yet" after a check and installs nothing until the first one appears; nothing
  in the app needs to change for it.
- The installed build (its release tag and commit, from `BuildConfig`), the
  result of the last check, and the buttons.

What counts as an update is the channel's newest published release that ships
an `.apk` asset (drafts and unpublished entries never count), when it is not the installed build: not
the same tag, nor the same commit. A local build knows neither and so is
offered the latest build; a branch build from CI knows its commit but not
where it stands, so it is offered the latest nightly too — turn auto-update
off on a phone that runs branch builds. Changing the channel starts over.

Installing goes through Android's package installer as a self-update, so it
only ever succeeds for an APK **signed like the installed one** (the nightly
test certificate; see `keystore/README.md`) with a version code that is not
lower; anything else is refused by Android, whatever the download was. The
APK is fetched over HTTPS from GitHub (a redirect to plain HTTP is not
followed), checked against the size the release states, and kept in the cache
only until it is installed or the attempt is given up. Android asks the
user to allow **Install unknown apps** for Ara once (the settings offer the
system page); after that, since Ara holds
`UPDATE_PACKAGES_WITHOUT_USER_ACTION` and updates itself, Android 12+ installs
without a dialog and restarts Ara with the new build. Where Android does ask,
the dialog is shown while Ara is in front; *Confirm installation* in the
settings shows it again, and *Cancel* gives the attempt up. A committed
installer session survives a restart of Ara (its dialog may still be up); the
next attempt replaces it, and a session a crash left uncommitted is dropped at
start. A check made by one build does not count for the next, so a freshly
installed build checks again on its first resume (with auto-update on) and
reports itself up to date.

The check is a single unauthenticated request to the GitHub API (60 per hour
per network address); when the limit is hit, the settings say so. Nothing is
sent but the request itself (`User-Agent: Ara/<version> (Android)`).

Implementation: `update/UpdateLogic.kt` (which release, framework-free and
unit-tested), `update/UpdateRuntime.kt` (checking, downloading, state for the
settings), `update/UpdateInstaller.kt` and `update/UpdateReceiver.kt` (the
`PackageInstaller` session and its status broadcast).

---

## Wake phrase

Say the phrase and the web app opens a thread in continuous voice mode.

**Per instance.** The wake phrase is set per endpoint on this phone
(`Endpoint.wake`: mode, threshold, phrase model), on the settings page under
*This app with* that endpoint, so a phrase always wakes the instance it
belongs to. The endpoint shown and those working in the background
([Instance states](#instance-states)) are listened for at once, each with its
own phrase model and threshold, in one listener: the audio, the melspectrogram
and the embedding models run once, and every distinct phrase model scores the
same embeddings. A phrase of a background endpoint brings that endpoint to the
front (the wake waits until its page has loaded); when two listening endpoints
share a phrase model, the one shown takes it, else the first in the list,
whichever of their thresholds the score reaches (so the lowest of them is the
one that counts). The card's *Also listening* line names the background
endpoints a phrase heard now would bring to the front, so not one that shares
its model with the endpoint shown or with one before it. The service
of mode *Always* runs while any listening endpoint is in that mode. The one
device-wide wake setting of earlier builds becomes every endpoint's own on
update, with the phrase model that was in use, so the endpoint shown keeps
waking as before. An endpoint added later starts with its wake phrase off and
without a model, and editing an endpoint leaves its wake phrase as it is: the
editor does not hold it.

Ara runs [openWakeWord](https://github.com/dscripka/openWakeWord) on the device
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
the settings page shows a button to allow it when needed
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
reports the mic in use. A listener error (the microphone, or a model that
cannot be run) stops detection for 30 s (doubling up to 10 min on repeated
errors), or until Ara is opened again or the endpoints listen with other
phrase models than before (another one chosen for an endpoint, or an
endpoint no longer listened for).

**Models are not bundled** (licence and size). The phrase models are the
phone's: one list, the same in every endpoint's wake phrase card. Which of
them wakes an endpoint is that endpoint's own choice (`Endpoint.wake.modelId`),
and the card marks the model of the endpoint shown as *Active*:

- **Downloadable models** — Hey Jarvis (listed first, for testing), Hey
  Mycroft, Hey Rhasspy and Alexa from openWakeWord's v0.5.1 release. Tap
  **Download** first, then **Use this model**, which is the choice of the
  endpoint shown; a downloaded model can be deleted again while no endpoint
  uses it.
- **Your own** — **Upload a model file…** imports an `.onnx` from the phone;
  it is listed under its file name and is in use at once, for the endpoint
  that was shown when you started (should another come to the front
  meanwhile). Under *Advanced*, **Add from URL** does the same for a model on a
  server (a model on the shown endpoint's origin is fetched with that
  endpoint's client certificate). Your models stay on the list when you choose
  another, so you can switch back; **Remove** deletes one. **Stop using**
  leaves the endpoint shown without a model.

A model that another endpoint uses says so on its row (*Also used by …*,
whatever that endpoint's mode or state) and cannot be deleted or removed from
the card of the endpoint shown; the app refuses it as well, so that no
endpoint is left with a model that is gone. Stop using it on that endpoint's
own card first.

The list and the job that changes it (a download, an upload, a removal) are
the phone's as well, the same in the home screen's and the split-screen
window's card: one job runs at a time, whichever window started it, and no
card offers another meanwhile. One that is asked for all the same (a file
picked in a picker that was open already, a removal confirmed in its dialog)
waits its turn. A removal asks who uses the model as its last step before the
file goes, and a model is chosen only while its file is on the phone and no
job works on it.

An endpoint without a model is not listened for, whatever its mode, and its
card says so; the same holds for one whose model is no longer on the list or
not downloaded.

The feature models (`melspectrogram.onnx`, `embedding_model.onnx`) come with
the first phrase model; *Advanced* can download them again. A downloaded or
uploaded model is only added after it loads and gives a score for a window of
silence, so a file that is no phrase model (one of the feature models, picked
by mistake) is refused instead of stopping the listener. The single phrase model of
earlier builds is moved into the list on update (as Hey Jarvis when it is that
file, otherwise as "Earlier model") and stays in use, for every endpoint, as
that one file was; so does the one model chosen for the whole phone by a build
that had the list before the endpoints had their own choice. Where a phone has
both, the file counts: it was written after that choice was made.

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
its URL under **Add from URL**, or copy it to the phone and use **Upload a model file…**. Raise the threshold
if it wakes too easily, lower it if it is hard to trigger.

## Keyboard for other devices

The phone as a **Bluetooth keyboard** for a device that takes one but cannot
run Retinue itself — a VR headset, a TV, a streaming box, someone else's
computer. Retinue can then type there: text the user asks for in a thread
("type the text above"), single keys, and passwords from the user's password
manager. Nothing about it is specific to one kind of device; it is a standard
boot keyboard (`keyboard/HidKeys.kt`) registered through Android's HID device
profile (`BluetoothHidDevice`, Android 9+), the same mechanism keyboard-emulator
apps use.

**Switching it on and pairing.** Settings → *Keyboard for other devices* →
*Open keyboard* opens the native keyboard screen. Switching *Act as a Bluetooth
keyboard* on asks for Android's *connect* Bluetooth permission, registers Ara
as a keyboard and starts a foreground service (type `connectedDevice`, with
an ongoing notification) so the registration survives Ara going to the
background — only while Bluetooth is on (when the app was started in the
background, the service starts the next time Ara comes to the front). *Make
this phone discoverable* asks for the *advertise* permission then, and
Android for two minutes of visibility; the other device then pairs with the
phone from its own Bluetooth settings. The screen lists the paired devices
with *Connect*/*Disconnect*, and says when Bluetooth is off, when another app
holds the keyboard role, and — after ten seconds without an answer from it —
when the phone has no HID device profile at all. Off, Ara unregisters and the
service leaves.

**Which device it types into.** Only the one the user chose: the one they
tapped *Connect* for — while that connection is pending (up to 30 s), no
other — or one paired with the phone in the last three minutes (from the
other device, or in Android's own Bluetooth settings: pairing takes the user
on both sides), and from then on the one it last typed into. Another paired
device that connects to the phone by itself is disconnected again, so a text
never lands on a device nobody picked. Ara reconnects to the chosen device
when it starts, when the keyboard is switched on, when Bluetooth comes on,
and when that device connects to the phone for something else or is bonded
anew (its own Bluetooth broadcast; Ara does not page it). *Disconnect* holds:
Ara connects to no device — not even the chosen one connecting by itself —
but one paired just now, until the user taps *Connect* (or switches the
keyboard on afresh).

**Layouts.** A keyboard sends key positions, and the other device turns them
into characters with the layout *it* is set to; so Ara types for that layout,
chosen once per device (by Bluetooth address) on the keyboard screen: English
(US), German (Switzerland), French (Switzerland), German (Germany). A new
device starts with a guess from the phone's language, kept for that device
when it first connects. The tables follow the layouts as Windows, Linux,
ChromeOS and Android hosts (the Quest included) define them; on a Mac set to
a German or Swiss layout the AltGr characters (`@`, `#`, `[`, `{`, `\`, `|`,
`~`, `€`) sit elsewhere and come out wrong — English (US) types correctly on
a Mac. Characters a layout makes with a dead key (`É`, `ô`, capital umlauts on
the Swiss layouts) are typed as the dead key and the letter, for the letters
every host composes (á é í ó ú ý, à è ì ò ù, â ê î ô û, ä ë ï ö ü ÿ, ã ñ õ and
their capitals); others (`ń`, `ś`, …) count as missing, and so do `´` and `¨`
alone (dead key and space make `'` and `"` on Linux hosts). A text is mapped
whole before anything is sent: a character the layout cannot type is
reported (`unsupported-char`) and nothing is typed, so no half text is left
on the other device. When the host's Caps Lock is on (its LED report says
so), Ara presses it off before the text and on again after, so letters keep
their case; a newly connected host counts as off until it says otherwise (a
host that remaps Caps Lock — to Escape, say — gets those two presses as
that). A line break is typed as Shift+Enter
(a new line, not "send", in chat apps and text areas — but in a terminal, a
URL bar or a one-line field Shift+Enter submits like Enter, which is why the
dashboard never types a line break without a tap, and the phone asks before
one); Enter after the text is an explicit option.

**Pacing.** Every key is pressed and released on its own (so `ll` is two
presses), modifiers are pressed a report ahead of their key, and there is a
pause of 12 ms after every report: hosts drop keys that arrive faster (the
Quest among them). Typing goes one request at a time; a request while one is
running is refused as `busy`. The phone is kept awake while it types (a
press whose release came only after it slept would repeat). If the device
goes away mid-text, Ara releases all keys and reports `failed`; *Stop typing*
on the keyboard screen stops anything, a password too, before the next
character (never between a dead key and its letter) and reports `cancelled`.
The page's `keyboardCancel()` stops a text and closes an open password
dialog or confirmation (which ends as `cancelled`), but not a password being typed — a page cutting that short would
learn its length, and leave part of it on the device.

**From the dashboard.** The page asks with `keyboardType(text, {id, enter})`
and `keyboardKey(name, {id})` and hears the outcome — `typed` or `error`, with
the request's `id` — as `keyboard` events (see the
[contract](#shell--web-contract)). The app types only what a page of the
configured origin hands over, through the same origin-restricted bridge as
everything else, and every such request goes through one gate
(`KeyboardLogic.decide`), in this order: the endpoint's own switch,
*This server may type on other devices* (Settings → *This app with
endpoint* → *Typing on other devices*; off for a new endpoint, and off, the
page gets `not-enabled` and a `keyboardStatus()` that says the keyboard is
off, without the device's name, its layout or whether one is connected);
the keyboard on and a device connected; the user looking at the dashboard —
Ara resumed, its window focused, the phone unlocked, the page not under the
app's own settings page, launcher editor or folder editor (a script in a
page nobody is looking at gets `not-in-front`); no confirmation or password
dialog open (`busy`; one such window at a time); then the rule below.
Typing that started then runs to its end (or Stop). A call with invalid
arguments throws; every other request with an id gets exactly one final
event.

The phone itself asks before anything that can make the other device *do*
something, and lets plain one-line text type by itself, so that typing by
voice stays hands-free. A request **only writes** when it is a text without
Enter, not empty, without a blank at either end and without a carriage
return, line feed or tab; everything else **acts**: Enter, a line break, a
tab, a blank at an end (a space presses a focused button), and every key.
This is the web app's own rule for what it runs by itself (`runs_by_itself`
in the retinue repository's `scripts/shell_actions.py`, with Python's blanks;
`KeyboardRuleTest` pins its cases), so nothing the dashboard types by itself
meets a confirmation. What the phone asks is set for each device, next to
its layout on the keyboard screen: *Ask every time*; *One-line text by
itself* (where every device starts: it asks when a request acts); or *Never
ask*, which the screen asks about once more, since a page can open that
screen (for the same reason its keys, Connect and Disconnect and this choice
take no tap for 0.6 s after it appears and after any other touch on it).
After *Stop* on the strip, the next request asks, whatever the mode, until
the user approves one (a confirmation or the password dialog's *Type*). The confirmation is a small window of the app's own
(`ConfirmTypeActivity`, `FLAG_SECURE`): it names the device and the host of
the server that asked, as the app's settings have it (never the name the
server gives itself); shows the text exactly, in a box that scrolls, with
line breaks, tabs and blanks at the ends made visible, its length, and
whether Enter follows and line breaks go as Shift+Enter — or *Press* and the
key; and how many characters the dashboard typed on that device without
asking since the last approval (a count only; an approval and a disconnect
reset it). *Type* takes no tap for 0.6 s after the window appears and after
any touch on it that is not on the button, and for a text longer than its
box not before the box was scrolled to its end. An approval covers exactly
that request, once, on that device: if another device is connected by then,
nothing is typed (`not-connected`). Declined, left, or not answered within
25 s (12 s for a key), or closed by the page's own `keyboardCancel()`, it
ends as `cancelled`; after three in a row that were not approved (the page's
cancel counts as one), requests that would ask are `cancelled` without a
window for a minute. While a page types, and for three seconds after its last key, a
strip of the app's own above the launcher bar says *Typing on* the device,
with *Stop*.

What a script that got into the page, or a server taken over, can still do:
type plain one-line text without Enter on a device set to *One-line text by
itself* or *Never ask* — characters land wherever the device's focus is, and
a letter can be an app's shortcut there; do everything on a device set to
*Never ask*; raise the confirmation at any time with any text, for the user
to decline, and close it again with `keyboardCancel()`; and raise the
password dialog. Nothing of it while the
endpoint's switch is off.

**Passwords — the secret path.** `keyboardSecret({id, label, enter})` opens a
**native dialog**, outside the WebView, titled with the page's label ("Meta
account") and naming the device it types into (by the name the device gave
itself, cleaned like the label). Its field is a password field
with the autofill hint *password*, so the user's password manager (Bitwarden,
KeePassDX, …) offers to fill it — a locked one may show its unlock screen
first, and the dialog waits under it; the user can also type. *Type* sends it
to that device; if another one is connected by then, nothing is typed. What
holds:

- the secret is never logged, never stored by Ara, never handed to the page,
  the server or any agent;
- the page hears only `secret-typed` or `secret-cancelled` (or an `error` that
  says nothing about the secret: a character the layout lacks is `failed` to
  the page, and the phone itself says why). The typing is held — busy to
  every other request — to a multiple of three seconds, stopped or not, so
  how long it took, which a page could watch, says little of the length; the
  page cannot cut it short;
- Ara's own copies are overwritten: the char array the field's characters are
  copied into (as soon as they are mapped to key presses), the lists of those
  presses, and every key report once sent. The autofill session is cancelled
  (no "save password?" prompt) and the field is cleared. What Ara cannot
  wipe: the IME and the autofill service hand the text over as a string in
  Android's own code, the field's buffer keeps old characters until reused,
  and an autofill service sees what the user types into a field it serves;
- the dialog's window is `FLAG_SECURE` (no screenshots, blank in Recents), the
  keyboard is told not to learn from it, and the field cannot be long-pressed
  to copy;
- no dialog opens while no device is connected (the page hears
  `not-connected` instead), so nobody is asked for a password that could not
  be typed; a dialog nobody answers closes itself after ten minutes
  (`secret-cancelled`);
- it names the host of the server that asked, as the confirmation does, and
  when the dashboard typed on that device without asking since the last
  approval, says how many characters and starts with *Press Enter after*
  unticked, whatever the page asked for (the page may have moved the focus
  to where Enter does something else).

**Live mode.** The keyboard screen's *Type live* field sends every change at
once: new characters typed, deletions as Backspace (an edit in the middle of
the field is sent as deleting back to it and retyping the rest). Changes are
queued and sent in order — none is dropped while an earlier one is still
being typed, the buttons below (Enter, Tab, Backspace, Esc, the arrows) in
the same queue — and the field takes only characters the device's layout can
type, up to 4000 (a pasted `\r\n` becomes one line break); a line break goes
as Shift+Enter. If an edit cannot be sent (no device, stopped), or waiting
edits are dropped by a Stop, the field is cleared, with a note: what reached
the device stays there. The field takes no suggestions and no
personalised learning, and takes part in no autofill.

**Limits.** The other device treats the input as it would from any keyboard:
a password typed into a field that does not mask it is visible there, and a
text typed into the wrong field lands in the wrong field. Mouse and touchpad
are not offered (the descriptor is a keyboard only).

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
- **Split screen** behaviour differs between Android versions and makers (which
  half the app gets, whether an app already running is moved into the split or
  opened anew). The WebView's autofill stays tied to the window it was created
  in (normally the home screen), so autofill may not show in the split-screen
  window.

---

## Architecture

```
shell/     ShellUrls, UrlRouter, ShellDecisions,   — pure: origins, navigation routing, Back/Home/wake
           ShellMessages, FileNames,                  decisions, the bridge wire format, download
           ContentDisposition, ShellLinks             file names, view or save, retinue-shell: links (unit-tested)
keyboard/  HidKeys, KeyLayouts, KeyPacer,          — pure: the HID descriptor and reports, characters →
           KeyboardLogic, KeyboardConsent             presses per layout, pacing, prechecks, the page's
                                                      gate, the rule, modes, pause, count (unit-tested)
           KeyboardRuntime, KeyboardService,        — HID registration, connections, typing; the
           KeyboardPrefs                              foreground service; on/off, last device, layouts
                                                      and modes
health/    HealthSyncLogic, HealthRecords,          — pure: sync gating and back-off, file envelope and name,
           HealthSyncer                               rounds, records → JSON, the order of one sync (unit-tested)
           HealthSyncRuntime, HealthRoundStore      — Health Connect reads, letterbox probe and upload,
                                                      sync state, the rounds kept on the device (one per server)
launcher/  LauncherEntry, LauncherConfig,           — pure: entry types (folders hold apps only), JSON
           FolderKind, AppRecency (TrayLogic)         codec, edits, folder types, the tray's order and
                                                      height (unit-tested)
           AppCatalog, AppUsage, FolderIcons        — launchable apps and their icons, Android's usage
                                                      stats, folder images
wake/      WakeLogic (WakeMode, WakeScorer,         — pure: scoring, the mic gate (unit-tested)
           WakeGate), WakeFeatures                  — pure: openWakeWord's streaming buffers (unit-tested)
           OnnxWakeModels                           — the three ONNX models (ORT Java API)
           WakeListener, WakeRuntime, WakeService,  — mic thread, process-wide owner, foreground service,
           WakeModelFiles                             model download/import
web/       ShellWebView, ShellWebClients,           — the WebView, its setup and callbacks,
           ShellBridge, NativeSpeech,                 the bridge, TextToSpeech,
           ShellIntents, ShellDownloads               intents to Android, files opened in a viewer or saved
data/      ShellSettings, AraStore                  — settings, launcher and launch-time persistence (DataStore)
net/       AraHttp                                  — KeyChain client certificate, mTLS OkHttp client
           CertificateImport                        — pure: which file goes to the installer, its name (unit-tested)
           CertificateInstaller                     — reading the picked file, Android's certificate installer
ui/        MainActivity, SplitShellActivity,        — the Compose shell (home screen and split-screen
           ShellPage, ShellViewModel,                 window), the page they share, settings, bar,
           LauncherBar, AppTray, AppDrawer,           tray, drawer, folders (open folder, folder editor),
           LauncherFolders,
           LauncherEditorScreen, SettingsPageModel,   the launcher bar's editor, the settings page's state
           HealthRationaleActivity,
           KeyboardActivity, SecretTypeActivity,    the keyboard screen, the secret dialog,
           ConfirmTypeActivity                        the confirmation before typing
settings/  AppPage, SettingsMessages,               — pure: the settings page's address and policy, its
           SettingsState                              messages both ways, its state (unit-tested)
           AppPageServer, SettingsBridge,           — the page served from the APK, its line to the app,
           SettingsWebView                            its WebView
assets/    retinue-shell.js                         — page side of the bridge and the speech stand-in
           settings/                                — the settings page (logic.mjs: its decisions, node-tested)
```

The branching rules live in framework-free classes covered by JVM unit tests;
the Android classes only gather inputs and carry out the answers. The chime is
`app/src/main/res/raw/chime.wav`.
