// Installed by the Android shell at document start, on the configured origin
// only (see web/ShellBridge.kt). Two things live here:
//
//  1. window.RetinueShell — the shell <-> web contract (plan section 4):
//     {platform, version, setVoiceActive(bool), openSettings(section?)},
//     Health Connect sync: healthConnectAvailable(), healthSync(),
//     healthSyncStatus(), setHealthSyncInterval(minutes),
//     setHealthAutoSync(bool), the endpoints (RetinueShell/3):
//     endpoints(), selectEndpoint(id), and (RetinueShell/4) the app's own
//     settings page (settingsPage) and the dashboard's per-device
//     preferences the app holds for every endpoint: deviceSettings(),
//     setDeviceSetting(key, value), and (RetinueShell/5) the keyboard for
//     other devices: keyboardStatus(), keyboardType(text, opts),
//     keyboardKey(name, opts), keyboardSecret(opts), keyboardOpen(),
//     keyboardCancel(). Shell -> web
//     events arrive as
//     `retinue-shell` CustomEvents dispatched on window; the shell checks
//     whether a page listener ran for them (see below).
//
//  2. A speechSynthesis / SpeechSynthesisUtterance stand-in, backed by
//     Android's TextToSpeech, installed only when the WebView has no working
//     Web Speech synthesis of its own. It implements the surface the web app's
//     Reader (webapp/components/speech.js) relies on: speak, cancel, pause,
//     resume, getVoices, speaking/pending/paused, voiceschanged, and per
//     utterance start/end/error/boundary/pause/resume events.
//
// The native side is reached through window.RetinueShellNative.postMessage(
// string), injected by addWebMessageListener for the configured origin only
// (WebViews without it get no bridge); it answers through
// window.__retinueShellReceive(message).
(function () {
  'use strict';
  if (window.RetinueShell && window.RetinueShell.platform === 'android') return;
  var nativeBridge = window.RetinueShellNative;
  if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') return;

  function post(msg) {
    try { nativeBridge.postMessage(JSON.stringify(msg)); } catch (_) { /* shell gone */ }
  }

  // ---- Health Connect sync status ------------------------------------------
  //
  // The bridge is asynchronous, but healthConnectAvailable() and
  // healthSyncStatus() answer at once: from the status the shell put into this
  // script when it installed it, kept current by the `health.status` messages
  // it sends on every change (before any `health-sync` event, so a listener
  // reads the new status).
  var health = {
    available: false, permissions: 'none', last_sync_at: null, last_result: null,
    last_error: null, last_count: null, min_interval_minutes: 5, auto_on_resume: true,
    enabled: false,
  };
  function takeHealth(s) {
    if (!s || typeof s !== 'object') return;
    for (var k in health) {
      if (Object.prototype.hasOwnProperty.call(s, k)) health[k] = s[k];
    }
  }
  try { takeHealth(JSON.parse("__RETINUE_HEALTH_STATUS__")); } catch (_) { /* the bare asset: defaults */ }

  // ---- Endpoints -----------------------------------------------------------
  //
  // The Retinue servers this phone knows, as the shell shows them: id, label
  // (the instance's own name) and which one is shown. A snapshot the shell
  // baked in when it installed this script, replaced by its `endpoints.status`
  // messages (an `endpoints` event follows each). Switching and editing are the
  // shell's: the page only asks, and never learns the other servers' URLs.
  var endpoints = [];
  function takeEndpoints(list) {
    if (!Array.isArray(list)) return;
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var e = list[i];
      if (e && typeof e === 'object' && typeof e.id === 'string' && e.id) {
        out.push({ id: e.id, label: typeof e.label === 'string' && e.label ? e.label : e.id, current: !!e.current });
      }
    }
    endpoints = out;
  }
  try { takeEndpoints(JSON.parse("__RETINUE_ENDPOINTS__")); } catch (_) { /* the bare asset: none */ }

  // ---- Per-device preferences (RetinueShell/4) -----------------------------
  //
  // The dashboard's voice timeouts, shelf size and layout, which the app holds
  // for every endpoint (the page's components/device-store.js; the app's
  // DeviceWebSettings). deviceSettings() answers at once from a copy: baked in
  // when the shell installed this script, replaced by its `device.status`
  // messages, after which a `device-settings` event names the keys that
  // changed. The keys and limits mirror the app's, so a write the app would
  // refuse is not kept here either.
  var DEVICE_KEYS = ['retinue-voice-silence-ms', 'retinue-voice-nospeech-ms', 'retinue.shelf.size',
    'retinue.layout.v1', 'retinue.chatpage.v1'];
  var DEVICE_PREFIXES = ['retinue.view.'];
  var DEVICE_MAX_KEY = 80;
  var DEVICE_MAX_VALUE = 4096;
  var DEVICE_MAX_ENTRIES = 64;
  var DEVICE_KEY_CHARS = /^[A-Za-z0-9._:-]+$/;
  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function isDeviceKey(k) {
    if (typeof k !== 'string' || !k || k.length > DEVICE_MAX_KEY || !DEVICE_KEY_CHARS.test(k)) return false;
    if (DEVICE_KEYS.indexOf(k) >= 0) return true;
    for (var i = 0; i < DEVICE_PREFIXES.length; i++) {
      var p = DEVICE_PREFIXES[i];
      if (k.indexOf(p) === 0 && k.length > p.length) return true;
    }
    return false;
  }
  var device = {};
  // Replace the copy; returns the keys whose values changed.
  function takeDevice(values) {
    if (!values || typeof values !== 'object' || Array.isArray(values)) return [];
    var next = {};
    var n = 0;
    for (var k in values) {
      if (!has(values, k) || n >= DEVICE_MAX_ENTRIES) continue;
      var v = values[k];
      if (isDeviceKey(k) && typeof v === 'string' && v.length <= DEVICE_MAX_VALUE) { next[k] = v; n++; }
    }
    var changed = [];
    for (k in device) { if (has(device, k) && device[k] !== next[k]) changed.push(k); }
    for (k in next) { if (has(next, k) && !has(device, k)) changed.push(k); }
    device = next;
    return changed;
  }
  try { takeDevice(JSON.parse("__RETINUE_DEVICE_SETTINGS__")); } catch (_) { /* the bare asset: none */ }

  // ---- Keyboard for other devices (RetinueShell/5) -------------------------
  //
  // The phone as a Bluetooth keyboard for a device that cannot run Retinue
  // itself. keyboardStatus() answers at once from a copy the shell baked in
  // and replaces with its `keyboard.status` messages (sent before each
  // `keyboard` event). Typing is the shell's: the page hands over text, a key
  // name, or asks for the native secret dialog — and hears the outcome as
  // `keyboard` events. A secret never passes through the page.
  var keyboard = { available: false, enabled: false, connected: false, host: null, layout: 'us' };
  function takeKeyboard(s) {
    if (!s || typeof s !== 'object') return;
    keyboard = {
      available: !!s.available,
      enabled: !!s.enabled,
      connected: !!s.connected,
      // The device's name only: its Bluetooth address stays in the app.
      host: s.host && typeof s.host === 'object' && typeof s.host.name === 'string' && s.host.name
        ? { name: s.host.name }
        : null,
      layout: typeof s.layout === 'string' ? s.layout : 'us',
    };
  }
  try { takeKeyboard(JSON.parse("__RETINUE_KEYBOARD_STATUS__")); } catch (_) { /* the bare asset: off */ }
  var KEYBOARD_KEYS = ['enter', 'tab', 'backspace', 'escape', 'up', 'down', 'left', 'right', 'home', 'end', 'delete', 'space'];
  function keyboardOpts(opts) {
    if (typeof opts === 'string') { try { opts = JSON.parse(opts); } catch (_) { opts = null; } }
    return opts && typeof opts === 'object' ? opts : {};
  }
  function keyboardId(o) { return typeof o.id === 'string' && o.id ? o.id : undefined; }

  var shell = {
    platform: 'android',
    version: '__RETINUE_SHELL_VERSION__',
    // The page has the microphone in use: the shell pauses wake detection and
    // keeps the screen on until it is reported inactive again.
    setVoiceActive: function (active) { post({ type: 'voiceActive', active: !!active }); },
    // The native settings; `section` names a part of them ('endpoints': the
    // endpoint list, or the editor of the one endpoint there is).
    openSettings: function (section) {
      var msg = { type: 'openSettings' };
      if (typeof section === 'string' && section) msg.section = section;
      post(msg);
    },
    nativeSpeech: false,
    // The configured endpoints as a JSON string: [{id, label, current}].
    endpoints: function () { return JSON.stringify(endpoints); },
    // Show the endpoint `id` (one of endpoints()); the shell switches and
    // loads that server's dashboard, so this page is left.
    selectEndpoint: function (id) {
      if (typeof id !== 'string' || !id) return;
      post({ type: 'endpoint.select', id: id });
    },
    // The app has a settings page of its own: openSettings() shows it, with
    // this app's settings, the endpoint's and the instance's.
    settingsPage: true,
    // The per-device preferences as a JSON string: {key: value}.
    deviceSettings: function () { return JSON.stringify(device); },
    // Keep `value` (a string; null removes it) under `key`, for every endpoint.
    setDeviceSetting: function (key, value) {
      if (!isDeviceKey(key)) return;
      var v = value == null ? null : String(value);
      if (v !== null && v.length > DEVICE_MAX_VALUE) return;
      if (v === null) {
        delete device[key];
      } else {
        if (!has(device, key) && Object.keys(device).length >= DEVICE_MAX_ENTRIES) return;
        device[key] = v;
      }
      post({ type: 'device.set', key: key, value: v });
    },
    healthConnectAvailable: function () { return health.available ? 'true' : 'false'; },
    // A sync now (asks for Health Connect permissions first if needed);
    // returns at once, the outcome comes as `health-sync` events.
    healthSync: function () { post({ type: 'health.sync' }); },
    healthSyncStatus: function () { return JSON.stringify(health); },
    setHealthSyncInterval: function (minutes) {
      var n = Math.floor(Number(minutes));
      if (!(n >= 1)) return;
      health.min_interval_minutes = n;
      post({ type: 'health.interval', minutes: n });
    },
    setHealthAutoSync: function (on) {
      health.auto_on_resume = !!on;
      post({ type: 'health.auto', on: !!on });
    },
    // {available, enabled, connected, host: {name}|null, layout} as a JSON string
    // (the host by name only: its Bluetooth address stays in the app).
    keyboardStatus: function () { return JSON.stringify(keyboard); },
    // Type `text` into the connected device; opts {id, enter} (an object or
    // its JSON). Returns at once; a `keyboard` event says `typed` or `error`.
    // A line break is typed as Shift+Enter (a new line in chat apps and text
    // areas, but a submit in a terminal or a one-line field), a tab as Tab.
    // Invalid arguments throw (the page's call fails at once) rather than
    // vanishing without the event the page waits for.
    keyboardType: function (text, opts) {
      if (typeof text !== 'string') throw new TypeError('keyboardType: text must be a string');
      var o = keyboardOpts(opts);
      post({ type: 'keyboard.type', text: text, enter: !!o.enter, id: keyboardId(o) });
    },
    // Press one key: enter, tab, backspace, escape, up, down, left, right, home, end, delete, space;
    // opts {id} (an object or its JSON). A `keyboard` event says `typed` or `error`.
    keyboardKey: function (name, opts) {
      if (KEYBOARD_KEYS.indexOf(name) < 0) throw new TypeError('keyboardKey: unknown key ' + String(name));
      post({ type: 'keyboard.key', name: name, id: keyboardId(keyboardOpts(opts)) });
    },
    // The native secret dialog; opts {id, label, enter}. The page hears only
    // `secret-typed` or `secret-cancelled` (or an `error`), never the secret.
    keyboardSecret: function (opts) {
      var o = keyboardOpts(opts);
      post({ type: 'keyboard.secret', label: typeof o.label === 'string' ? o.label : undefined, enter: !!o.enter, id: keyboardId(o) });
    },
    // The native keyboard screen: switch on, pair, layout, live typing.
    keyboardOpen: function () { post({ type: 'keyboard.open' }); },
    // Stop what is being typed; its request ends with an `error` `cancelled`.
    keyboardCancel: function () { post({ type: 'keyboard.cancel' }); },
  };
  try {
    Object.defineProperty(window, 'RetinueShell', { value: shell, configurable: true, enumerable: true, writable: false });
  } catch (_) {
    window.RetinueShell = shell;
  }

  // ---- Shell events: did the page take it? --------------------------------
  //
  // The shell dispatches `retinue-shell` events (wake, home). A page of the
  // origin without the web app's listener (a gateway page such as /sends) would
  // swallow them, so the shell asks whether a listener ran and otherwise loads
  // the web app itself. Listeners added on window from here on (before any page
  // script runs) are wrapped, so a call marks the event as taken; listeners
  // removed again, run once ({once: true}) or aborted through a signal are left
  // to the browser and simply do not run. A page may also call preventDefault()
  // on the event.
  var taken = typeof WeakSet === 'function' ? new WeakSet() : null;
  var wrapped = [];  // {fn, capture, wrapper}
  function captureOf(options) { return typeof options === 'boolean' ? options : !!(options && options.capture); }
  function wrapperOf(fn, capture) {
    for (var i = 0; i < wrapped.length; i++) {
      if (wrapped[i].fn === fn && wrapped[i].capture === capture) return wrapped[i].wrapper;
    }
    return null;
  }
  if (taken && typeof window.addEventListener === 'function' && typeof window.removeEventListener === 'function') {
    var addListener = window.addEventListener;
    var removeListener = window.removeEventListener;
    window.addEventListener = function (type, fn, options) {
      if (type !== 'retinue-shell' || !fn || (typeof fn !== 'function' && typeof fn !== 'object')) {
        return addListener.apply(this, arguments);
      }
      var capture = captureOf(options);
      var wrapper = wrapperOf(fn, capture);
      if (!wrapper) {
        wrapper = function (e) {
          if (e && typeof e === 'object') taken.add(e);
          if (typeof fn === 'function') return fn.call(this, e);
          if (typeof fn.handleEvent === 'function') return fn.handleEvent(e);
          return undefined;
        };
        wrapped.push({ fn: fn, capture: capture, wrapper: wrapper });
      }
      // The browser keeps the semantics of once, signal, passive and duplicates.
      return addListener.call(this, type, wrapper, options);
    };
    window.removeEventListener = function (type, fn, options) {
      if (type === 'retinue-shell' && fn) {
        var capture = captureOf(options);
        var wrapper = wrapperOf(fn, capture);
        if (wrapper) {
          wrapped = wrapped.filter(function (e) { return e.wrapper !== wrapper; });
          return removeListener.call(this, type, wrapper, options);
        }
      }
      return removeListener.apply(this, arguments);
    };
  }
  // Whether a wrapped listener ran for this event (ShellMessages.eventScript).
  Object.defineProperty(window, '__retinueShellTaken', {
    value: function (e) { return !!(taken && e && typeof e === 'object' && taken.has(e)); },
    configurable: false,
    enumerable: false,
    writable: false,
  });

  var receivers = [];
  Object.defineProperty(window, '__retinueShellReceive', {
    value: function (msg) {
      for (var i = 0; i < receivers.length; i++) {
        try { receivers[i](msg); } catch (_) { /* a receiver's bug is not the shell's */ }
      }
    },
    configurable: false,
    enumerable: false,
    writable: false,
  });

  receivers.push(function (msg) {
    if (!msg) return;
    if (msg.type === 'health.status') takeHealth(msg.status);
    else if (msg.type === 'endpoints.status') takeEndpoints(msg.endpoints);
    else if (msg.type === 'keyboard.status') takeKeyboard(msg.status);
    else if (msg.type === 'device.status') {
      var changed = takeDevice(msg.settings);
      if (changed.length) {
        try {
          window.dispatchEvent(new CustomEvent('retinue-shell', { detail: { type: 'device-settings', keys: changed } }));
        } catch (_) { /* no events here: the copy is current all the same */ }
      }
    }
  });

  // ---- speechSynthesis stand-in -------------------------------------------

  function makeEvent(type, fields) {
    var ev = new Event(type);
    for (var k in fields) {
      if (Object.prototype.hasOwnProperty.call(fields, k)) {
        try { Object.defineProperty(ev, k, { value: fields[k], enumerable: true }); } catch (_) { /* ignore */ }
      }
    }
    return ev;
  }

  // Fire on the target's listeners and its on<type> handler, the way a native
  // EventTarget with IDL event-handler attributes does.
  function fire(target, type, fields) {
    var ev = makeEvent(type, fields || {});
    try { target.dispatchEvent(ev); } catch (_) { /* ignore */ }
    var handler = target['on' + type];
    if (typeof handler === 'function') {
      try { handler.call(target, ev); } catch (e) { setTimeout(function () { throw e; }, 0); }
    }
  }

  function installSpeech() {
    var voices = [];
    var queue = [];       // utterances waiting their turn
    var current = null;   // {utter, id, started, restart, resumed}
    var paused = false;
    var nextId = 1;

    class SpeechSynthesisUtterance extends EventTarget {
      constructor(text) {
        super();
        this.text = text === undefined ? '' : String(text);
        this.lang = '';
        this.voice = null;
        this.volume = 1;
        this.rate = 1;
        this.pitch = 1;
        this.onstart = null;
        this.onend = null;
        this.onerror = null;
        this.onpause = null;
        this.onresume = null;
        this.onboundary = null;
        this.onmark = null;
      }
    }

    function utterEvent(c, type, extra) {
      var fields = { utterance: c.utter, charIndex: 0, charLength: 0, elapsedTime: 0, name: '' };
      if (extra) for (var k in extra) fields[k] = extra[k];
      fire(c.utter, type, fields);
    }

    function hand(c) {
      var u = c.utter;
      c.id = nextId++;
      c.started = false;
      post({
        type: 'tts.speak',
        id: c.id,
        text: String(u.text || ''),
        lang: String(u.lang || ''),
        voice: u.voice && u.voice.name ? String(u.voice.name) : '',
        rate: Number(u.rate) || 1,
        pitch: Number(u.pitch) || 1,
        volume: u.volume === undefined || u.volume === null ? 1 : Number(u.volume),
      });
    }

    function next() {
      if (current || paused) return;
      var u = queue.shift();
      if (!u) return;
      current = { utter: u, id: 0, started: false, restart: false, resumed: false };
      hand(current);
    }

    class SpeechSynthesis extends EventTarget {
      constructor() { super(); this.onvoiceschanged = null; }
      get speaking() { return !!current; }
      get pending() { return queue.length > 0; }
      get paused() { return paused; }
      speak(u) {
        if (!(u instanceof SpeechSynthesisUtterance)) {
          throw new TypeError("Failed to execute 'speak' on 'SpeechSynthesis': parameter 1 is not of type 'SpeechSynthesisUtterance'.");
        }
        queue.push(u);
        next();
      }
      cancel() {
        var c = current;
        var dropped = queue;
        current = null;
        queue = [];
        if (c) post({ type: 'tts.cancel' });
        // Like Chrome: asynchronously, the spoken utterance reports
        // 'interrupted' (or 'canceled' if it never started) and every queued
        // one 'canceled'. The paused flag is left as it was, as in Chrome.
        if (c || dropped.length) {
          setTimeout(function () {
            if (c) utterEvent(c, 'error', { error: c.started ? 'interrupted' : 'canceled' });
            for (var i = 0; i < dropped.length; i++) {
              utterEvent({ utter: dropped[i] }, 'error', { error: 'canceled' });
            }
          }, 0);
        }
      }
      // Android's engine cannot pause mid-sentence: pause stops the utterance
      // and resume speaks it again from its start.
      pause() {
        if (paused) return;
        paused = true;
        if (current) {
          post({ type: 'tts.pause' });
          current.restart = true;
          // Late events of the stopped hand-over must not end the utterance.
          current.id = -1;
          var c = current;
          setTimeout(function () { utterEvent(c, 'pause'); }, 0);
        }
      }
      resume() {
        if (!paused) return;
        paused = false;
        if (current && current.restart) {
          var c = current;
          c.restart = false;
          c.resumed = c.started;
          hand(c);
          setTimeout(function () { utterEvent(c, 'resume'); }, 0);
        } else {
          next();
        }
      }
      getVoices() { return voices.slice(); }
    }

    var synth = new SpeechSynthesis();

    receivers.push(function (msg) {
      if (!msg || typeof msg.type !== 'string') return;
      if (msg.type === 'tts.voices') {
        voices = (msg.voices || []).map(function (v) {
          return Object.freeze({
            voiceURI: String(v.name), name: String(v.name), lang: String(v.lang || ''),
            localService: !!v.localService, default: !!v.default,
          });
        });
        fire(synth, 'voiceschanged', {});
        return;
      }
      var c = current;
      if (!c || msg.id !== c.id) return;  // a superseded utterance
      if (msg.type === 'tts.start') {
        var first = !c.started;
        c.started = true;
        // A resumed utterance announced 'resume' already; no second 'start'.
        if (first && !c.resumed) utterEvent(c, 'start');
      } else if (msg.type === 'tts.boundary') {
        utterEvent(c, 'boundary', { charIndex: Number(msg.charIndex) || 0, name: 'word' });
      } else if (msg.type === 'tts.end') {
        current = null;
        utterEvent(c, 'end', { charIndex: String(c.utter.text || '').length });
        next();
      } else if (msg.type === 'tts.error') {
        current = null;
        utterEvent(c, 'error', { error: String(msg.error || 'synthesis-failed') });
        next();
      }
    });

    var defined = false;
    try {
      Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true, enumerable: true, writable: true });
      Object.defineProperty(window, 'SpeechSynthesisUtterance', { value: SpeechSynthesisUtterance, configurable: true, enumerable: false, writable: true });
      defined = window.speechSynthesis === synth;
    } catch (_) { defined = false; }
    if (!defined) return false;
    shell.nativeSpeech = true;
    post({ type: 'tts.voices' });
    return true;
  }

  var hasOwn = ('speechSynthesis' in window) && ('SpeechSynthesisUtterance' in window) && !!window.speechSynthesis;
  if (!hasOwn) {
    installSpeech();
  } else {
    // Some WebViews expose the API without an engine behind it: no voices,
    // ever, and speak() is silent. Give the voice list time to arrive (it is
    // asynchronous in Chromium) and take over only if it stays empty. The web
    // Reader looks speechSynthesis up on every use, so a late take-over works.
    var own = window.speechSynthesis;
    var settled = false;
    var check = function () {
      if (settled) return;
      var n = 0;
      try { n = (own.getVoices() || []).length; } catch (_) { n = 0; }
      if (n > 0) { settled = true; return; }
    };
    try { own.addEventListener('voiceschanged', check); } catch (_) { /* ignore */ }
    check();
    setTimeout(function () {
      check();
      if (!settled) { settled = true; installSpeech(); }
    }, 1500);
  }
})();
