# Ara — Android voice client for Retinue

Ara is a minimal native Android app that lets you talk to an existing
[Retinue](https://github.com/Retinue-OS/retinue) server-side assistant by voice.
You speak a prompt, Android transcribes it, Ara submits it to Retinue, polls the
returned job until the answer is ready, shows the answer, and (optionally) reads
it aloud — then, in continuous mode, starts listening again.

It is a companion to Retinue's **Signal Gateway** and speaks exactly the same
protocol (see [Relationship to the Signal Gateway](#relationship-to-the-signal-gateway)).

- **Language:** Kotlin, native Android APIs, Jetpack Compose UI.
- **Primary target device:** Samsung Galaxy S25 (One UI 7 / Android 15).
- **No** Flutter, React Native, or PWA.

---

## Building from the command line

Android Studio is optional — the project builds entirely from the command line
via the bundled Gradle Wrapper.

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the unit tests:

```bash
./gradlew testDebugUnitTest
```

### Requirements

- **JDK 17** (AGP 8.7 requires it).
- **Android SDK** with:
  - Android SDK Platform **35** (compile/target SDK),
  - Android SDK Build-Tools compatible with AGP 8.7,
  - minimum supported device level is **API 31** (Android 12).
- **Gradle** is provided by the wrapper (`./gradlew`); no system Gradle needed.

Point the build at your SDK either by exporting `ANDROID_HOME` (or
`ANDROID_SDK_ROOT`) or by creating `local.properties`:

```properties
sdk.dir=/path/to/Android/sdk
```

Install the debug build on a connected device:

```bash
./gradlew installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Versions

- Android Gradle Plugin 8.7.2, Gradle 8.11.1 (wrapper), Kotlin 2.0.21.
- Jetpack Compose (BOM 2024.10.01), Material 3.
- OkHttp 4.12, Kotlin Coroutines 1.9, DataStore Preferences 1.1, kotlinx-serialization 1.7.

---

## Configuring the Retinue endpoint

The Retinue endpoint is the one value Ara cannot derive from the Signal Gateway
(the gateway talks to Retinue over the internal container network at
`http://retinue:8080/message`, which is not reachable from a phone). It is
exposed through a single runtime configuration surface: **Settings**.

Open **Settings** (gear icon) and set:

| Setting | Meaning | Default |
|---|---|---|
| **Retinue base URL** | Public HTTPS origin, e.g. `https://agents.example.com` | *(empty — must be set)* |
| **Message path** | Prompt submission path | `/message` |
| **Initial poll interval** | First delay between job polls | 3 s |
| **Network request timeout** | Per-request (submit/poll) timeout | 30 s |
| **Overall job timeout** | Max time to keep polling one job | 3600 s |
| **Consecutive poll retries** | Transient poll failures tolerated before giving up | 10 |
| **Client certificate** | The installed client cert Ara presents (mTLS) | *(none — must be selected)* |

These defaults mirror the Signal Gateway's environment defaults
(`RETINUE_POLL_INTERVAL`, `RETINUE_POST_TIMEOUT`, `RETINUE_GATEWAY_TIMEOUT`, …).
No endpoint, credential, or key is baked into the APK.

---

## Relationship to the Signal Gateway

Ara reuses the Signal Gateway's Retinue contract verbatim; all of it lives in one
place, [`RetinueProtocol`](app/src/main/java/os/retinue/ara/protocol/RetinueProtocol.kt),
and the transport in [`RetinueClient`](app/src/main/java/os/retinue/ara/net/RetinueClient.kt).
Protocol parsing is never duplicated in the UI, ViewModel, or service.

| Concern | Behaviour (matches `scripts/signal-gateway.py`) |
|---|---|
| **Prompt formatting** | `POST <base>/message` with JSON `{"message": <prompt>, "async": true}`. The prompt appends `Please answer in the same language as the question (ISO language code: <tag>)`, using the device locale. |
| **Job URI extraction** | Response `job_url` (e.g. `/jobs/<id>`) resolved against the message URL. |
| **Polling** | `GET <job_url>`; start at the initial interval, back off ×2 up to a ceiling, until done/error/expired or the overall timeout. |
| **Job-status parsing** | `status` ∈ `pending` / `done` / `error`; answer from `response`, permalink from `entry_url`; HTTP 404 = expired. |
| **Auth** | Mutual TLS at the Traefik edge (`retinue-mtls`). Ara presents a user-selected client certificate. |
| **Timeouts / retries** | Per-request 30 s, initial poll 3 s, ×2 backoff to 300 s, overall 3600 s (all configurable). |
| **Blocked requester** | HTTP 403 → the `response` message is shown as an error. |

Ara implements **only** this one contract — no alternative endpoint shapes or
generic response formats.

---

## Permissions & privacy

### Microphone

`RECORD_AUDIO` is requested on first launch. If it is **denied**, Ara stays
usable: you can still read existing answers and open Settings — only listening is
disabled, with a clear explanation. Grant it to speak to Ara.

### Speech recognition & possible cloud processing

Ara uses Android's speech-recognition APIs and **prefers an on-device
recognizer** when the device provides one; otherwise it falls back to the normal
Android recognition service, which may transmit audio to the configured provider
(e.g. Google). Before the first recognition session that may use the cloud, Ara
shows a **disclosure** and asks you to acknowledge it; the acknowledgement is
stored locally.

The current recognition posture is always shown:

- *On-device recognition* — an on-device recognizer is confirmed in use,
- *Recognition may use a cloud service*,
- *Recognition mode unknown*.

Ara never claims recognition is local unless the Android API confirms an
on-device recognizer.

### Notifications

On Android 13+ Ara requests `POST_NOTIFICATIONS`. If **denied**, foreground use
is unaffected, but Ara cannot alert you when a background answer is ready and
does not pretend otherwise. Notifications are privacy-safe: neither the prompt
nor the answer appears in them, and lock-screen visibility is private.

---

## Client certificate (mutual TLS)

Retinue sits behind Traefik and requires a client certificate.

1. Install your client certificate on the device
   (**Settings → Security → Encryption & credentials → Install a certificate →
   VPN & app user certificate**, or via an MDM).
2. In Ara's **Settings**, tap **Select certificate** and choose the installed
   alias.

Ara stores only the **alias**. The private key and certificate chain are
retrieved through the Android **KeyChain** at connection time and are never
bundled in the APK or persisted by the app. The HTTPS client presents the
selected certificate while performing normal server-certificate validation and
hostname verification (no trust-all manager).

### mTLS troubleshooting

- **"No client certificate is selected"** — choose one in Settings.
- **"The selected client certificate could not be used"** — it was removed or
  expired, or the key is inaccessible; select another.
- **"Secure connection to Retinue failed"** — a TLS handshake/validation problem:
  check the base URL, that the server trusts your client CA, and that the server
  certificate is valid for the host.
- Certificates are user-installed and may sit behind hardware-backed keys; some
  keys cannot be exported and behave differently across Android versions. If the
  chooser shows no certificates, confirm the cert was installed as a *user*
  credential with its private key.

---

## Foreground / background behaviour

- Speech recognition and text-to-speech run **only while Ara is in the
  foreground**. Moving Ara to the background stops both immediately.
- **Job polling continues in the background** via a dedicated **foreground
  service** (type `dataSync`) that owns the active job, shows the ongoing
  notification, and survives the Activity going away.
- If a job **completes while backgrounded**, Ara persists the answer, replaces
  the ongoing notification with a privacy-safe **"Ara has an answer"** alert, and
  presents (and, if enabled, speaks) the answer when you return.
- Toggling the mic or speaker while waiting or speaking never cancels the request
  or stops playback — it only decides whether Ara listens again afterwards.
- The screen is kept awake while Ara is listening, recognizing, submitting,
  waiting, or speaking.

Long-running jobs may stay pending for several minutes. The maximum supported job
duration is the configurable **overall job timeout**; note that Android limits
`dataSync` foreground services (roughly a few hours of cumulative runtime per day
on Android 14+), so extremely long jobs may be constrained by the platform.

### Swiping Ara away from Recents

Removing Ara from the Recents screen is treated as an explicit stop: recognition
and TTS stop, local polling is cancelled, the foreground service stops, active
notifications are dismissed, unsent captured input is discarded, and resources
are released. Ara does **not** restart itself.

Your persisted preferences are **kept**: continuous-conversation, automatic
playback, the cloud-recognition acknowledgement, and the selected certificate
alias.

> Note: exceptional OS/manufacturer behaviour (aggressive task killing, forced
> stop) may not deliver identical callbacks in every scenario.

### Process-death recovery

System process reclamation is **not** the same as a Recents swipe. Ara persists
enough job state (job URI, submission time, timeout deadline, last status,
completed answer, whether it was presented/played, and an explicit cancellation
flag) to recover: on relaunch it resumes polling a still-valid pending job or
presents a completed answer, and never mistakes process death for user
cancellation. An explicitly cancelled job is never resumed.

---

## Launching Ara with Bixby

The app label is **Ara**, so Bixby can launch it by name:

> "Hi Bixby, start Ara."

On launch Ara restores your continuous-conversation and automatic-playback
preferences, initializes speech recognition and text-to-speech, handles any
pending answer or active job, and — if continuous conversation is enabled and
nothing blocks it — begins listening (after a short chime).

No custom Bixby capsule or extension is required.

---

## The two toggles

- **Microphone toggle** — *continuous conversation mode*. Enabled (default on
  first launch): Ara keeps the conversational loop going, listening again after
  each answer. Disabled: Ara does not start new listening sessions. If you
  disable it while speech has already been captured, Ara asks **Use captured
  input? — Send / Discard**. The toggle is persisted.
- **Speaker toggle** — *automatic playback*. Enabled: new answers are read aloud
  while Ara is in the foreground. Disabled (default on first launch): answers are
  shown as text only. While Ara is speaking, the speaker toggle is also the
  **stop control** — tapping it stops the current utterance immediately, disables
  playback, and (in continuous mode) starts listening again so you can restate
  your prompt. The toggle is persisted.

---

## Architecture

```
protocol/   RetinueProtocol, PollingPolicy      — pure Retinue contract + cadence (unit-tested)
state/      AraState, AraDecisions              — explicit state model + pure decision logic (unit-tested)
data/       AraSettings, AraStore               — runtime config + DataStore persistence
net/        AraHttp, RetinueClient              — mTLS OkHttp client + the only Retinue transport
speech/     SpeechController, TtsController      — SpeechRecognizer + TextToSpeech wrappers
audio/      Chime                               — start-listening chime
service/    PollingService, AraNotifications,   — foreground polling service, notifications,
            JobBus                                process-global job state
ui/         MainActivity, AraViewModel,         — Compose UI + orchestration
            AraScreen, SettingsScreen
```

The branching rules (toggle behaviour, the listening gate, send/discard,
duplicate-submission and stale-callback guards, notification and recovery
decisions) live in `AraDecisions` — framework-free and covered by unit tests, so
they can be verified without an emulator. The chime sound is
`app/src/main/res/raw/chime.wav`, easy to replace.

### Tests

`app/src/test/` covers Retinue job-URI and job-status parsing, polling
retry/timeout decisions, the toggle and send/discard behaviour, duplicate-submit
and stale-callback guards, foreground/background transitions, notification
decisions, and process-recovery / task-removal decisions.

---

## Known Android & Samsung limitations

- **Foreground-service limits:** Android 14+ caps `dataSync` foreground-service
  runtime; very long Retinue jobs may hit platform limits before the configured
  overall timeout.
- **Aggressive battery management:** Samsung's "Deep sleeping apps" / battery
  optimization can suspend background polling. For reliable multi-minute jobs,
  exclude Ara from battery optimization.
- **Recognition availability varies:** whether an on-device recognizer exists —
  and for which languages — depends on the device and installed speech services;
  Ara adapts and shows the actual posture.
- **KeyChain / hardware-backed keys:** certificate availability and behaviour
  differ across Android versions and secure-hardware implementations; some keys
  are non-exportable by design.
- **Task-removal callbacks:** forced stops or unusual OEM behaviour may not
  always deliver `onTaskRemoved`.

---

## Scope (version one)

One active Retinue request at a time; job-URI polling only — no WebSockets, no
server push, no concurrent conversations, no background microphone capture.
