// Installed by the Android shell at document start, on the configured origin
// only (see web/ShellBridge.kt). Two things live here:
//
//  1. window.RetinueShell — the shell <-> web contract (plan section 4):
//     {platform, version, setVoiceActive(bool), openSettings()}. Shell -> web
//     events arrive as `retinue-shell` CustomEvents dispatched on window; the
//     shell checks whether the page listens for them (see below).
//
//  2. A speechSynthesis / SpeechSynthesisUtterance stand-in, backed by
//     Android's TextToSpeech, installed only when the WebView has no working
//     Web Speech synthesis of its own. It implements the surface the web app's
//     Reader (webapp/components/speech.js) relies on: speak, cancel, pause,
//     resume, getVoices, speaking/pending/paused, voiceschanged, and per
//     utterance start/end/error/boundary/pause/resume events.
//
// The native side is reached through window.RetinueShellNative.postMessage(
// string), injected by addWebMessageListener (or addJavascriptInterface on old
// WebViews); it answers through window.__retinueShellReceive(message).
(function () {
  'use strict';
  if (window.RetinueShell && window.RetinueShell.platform === 'android') return;
  var nativeBridge = window.RetinueShellNative;
  if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') return;

  function post(msg) {
    try { nativeBridge.postMessage(JSON.stringify(msg)); } catch (_) { /* shell gone */ }
  }

  var shell = {
    platform: 'android',
    version: '__RETINUE_SHELL_VERSION__',
    // The page has the microphone in use: the shell pauses wake detection and
    // keeps the screen on until it is reported inactive again.
    setVoiceActive: function (active) { post({ type: 'voiceActive', active: !!active }); },
    openSettings: function () { post({ type: 'openSettings' }); },
    nativeSpeech: false,
  };
  try {
    Object.defineProperty(window, 'RetinueShell', { value: shell, configurable: true, enumerable: true, writable: false });
  } catch (_) {
    window.RetinueShell = shell;
  }

  // ---- Shell events: does the page listen? -------------------------------
  //
  // The shell dispatches `retinue-shell` events (wake, home). A page of the
  // origin without the web app's listener (a gateway page such as /sends) would
  // swallow them, so the shell asks whether anyone listens and otherwise loads
  // the web app itself. Listeners added on window are counted here, before any
  // page script runs; a page may also call preventDefault() on the event.
  var shellListeners = [];
  function sameListener(entry, fn, capture) { return entry.fn === fn && entry.capture === capture; }
  function captureOf(options) { return typeof options === 'boolean' ? options : !!(options && options.capture); }
  if (typeof window.addEventListener === 'function' && typeof window.removeEventListener === 'function') {
    var addListener = window.addEventListener;
    var removeListener = window.removeEventListener;
    window.addEventListener = function (type, fn, options) {
      if (type === 'retinue-shell' && fn) {
        var capture = captureOf(options);
        var known = false;
        for (var i = 0; i < shellListeners.length; i++) {
          if (sameListener(shellListeners[i], fn, capture)) known = true;
        }
        if (!known) shellListeners.push({ fn: fn, capture: capture });
      }
      return addListener.apply(this, arguments);
    };
    window.removeEventListener = function (type, fn, options) {
      if (type === 'retinue-shell') {
        var capture = captureOf(options);
        shellListeners = shellListeners.filter(function (e) { return !sameListener(e, fn, capture); });
      }
      return removeListener.apply(this, arguments);
    };
  }
  Object.defineProperty(window, '__retinueShellListening', {
    value: function () { return shellListeners.length > 0; },
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
