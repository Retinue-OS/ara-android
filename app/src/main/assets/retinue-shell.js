// Installed by the Android shell at document start, on the configured origin
// only (see web/ShellBridge.kt). Two things live here:
//
//  1. window.RetinueShell — the shell <-> web contract (plan section 4):
//     {platform, version, setVoiceActive(bool), openSettings()} and Health
//     Connect sync: healthConnectAvailable(), healthSync(), healthSyncStatus(),
//     setHealthSyncInterval(minutes), setHealthAutoSync(bool). Shell -> web
//     events arrive as `retinue-shell` CustomEvents dispatched on window; the
//     shell checks whether a page listener ran for them (see below).
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
  };
  function takeHealth(s) {
    if (!s || typeof s !== 'object') return;
    for (var k in health) {
      if (Object.prototype.hasOwnProperty.call(s, k)) health[k] = s[k];
    }
  }
  try { takeHealth(JSON.parse("__RETINUE_HEALTH_STATUS__")); } catch (_) { /* the bare asset: defaults */ }

  var shell = {
    platform: 'android',
    version: '__RETINUE_SHELL_VERSION__',
    // The page has the microphone in use: the shell pauses wake detection and
    // keeps the screen on until it is reported inactive again.
    setVoiceActive: function (active) { post({ type: 'voiceActive', active: !!active }); },
    openSettings: function () { post({ type: 'openSettings' }); },
    nativeSpeech: false,
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
    if (msg && msg.type === 'health.status') takeHealth(msg.status);
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
