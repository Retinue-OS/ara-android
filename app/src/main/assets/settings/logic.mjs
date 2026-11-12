// The settings page's decisions, without a page: what an endpoint draft may
// be saved as and what closing the editor does with it, what a certificate
// import says, the values the page shows for the dashboard's per-device
// preferences (with the same bounds and defaults the dashboard applies), the
// health line, and the notes of the phone calls and the wake phrase. Kept free
// of DOM work, so app/src/test/js/settings-page.test.mjs runs it under Node.
// The page itself is page.mjs; the app's side is os.retinue.ara.settings (SettingsBridge,
// SettingsMessages, SettingsState).

// ── The dashboard's per-device preferences ──────────────────────────────────
// Held by the app for every endpoint (DeviceWebSettings), under the
// dashboard's own keys; mirrors webapp/components/continuous.js and
// shelf-store.js, which clamp what they read the same way.
export const VOICE_SILENCE = { key: 'retinue-voice-silence-ms', min: 500, max: 5000, def: 1500 };
export const VOICE_NO_SPEECH = { key: 'retinue-voice-nospeech-ms', min: 2000, max: 30000, def: 8000 };
export const SHELF_SIZE = { key: 'retinue.shelf.size', max: 50, def: 5 };

// Milliseconds as the dashboard reads them: a number, clamped; the default
// for anything else.
export function clampMs(value, spec) {
  const n = Number(value);
  if (value === null || value === undefined || value === '' || !Number.isFinite(n) || n <= 0) return spec.def;
  return Math.round(Math.max(spec.min, Math.min(spec.max, n)));
}

// The voice timeouts in seconds, for the page's fields.
export function voiceSeconds(device) {
  const d = device || {};
  return {
    silence: clampMs(d[VOICE_SILENCE.key], VOICE_SILENCE) / 1000,
    noSpeech: clampMs(d[VOICE_NO_SPEECH.key], VOICE_NO_SPEECH) / 1000,
  };
}

// What to store for `seconds` typed into the field of `spec`.
export function voiceValue(spec, seconds) {
  return String(clampMs(Number(seconds) * 1000, spec));
}

// The shelf size as the dashboard reads it.
export function clampShelf(value) {
  const v = Math.round(Number(value));
  if (value === null || value === undefined || value === '' || !Number.isFinite(v) || v < 1) return SHELF_SIZE.def;
  return Math.min(v, SHELF_SIZE.max);
}

export function shelfSize(device) {
  return clampShelf((device || {})[SHELF_SIZE.key]);
}

// ── Endpoint drafts ─────────────────────────────────────────────────────────
// The editor's draft mirrors the app's EndpointDraft: the app checks a draft
// again before it saves it, and its answer decides; these rules are for the
// page to say so before asking.

// What the user typed as a base URL, normalised as the app does
// (ShellUrls.normalizeBase): trimmed, https assumed, no query, fragment or
// trailing slash. Null for anything that is not an https URL with a host.
export function normalizeBase(raw) {
  let s = String(raw == null ? '' : raw).trim();
  if (!s) return null;
  if (!s.includes('://')) s = `https://${s}`;
  s = s.split('#')[0].split('?')[0].replace(/\/+$/, '');
  let url;
  try { url = new URL(s); } catch (_e) { return null; }
  if (url.protocol !== 'https:' || !url.hostname) return null;
  return url.origin + url.pathname.replace(/\/+$/, '');
}

// A plain-http address: the page says why it is refused.
export function isCleartext(raw) {
  return /^\s*http:\/\//i.test(String(raw == null ? '' : raw));
}

function fields(d) {
  return {
    baseUrl: String(d.baseUrl || '').trim(),
    certificateAlias: d.certificateAlias || null,
    username: String(d.username || '').trim(),
    healthUpload: !!d.healthUpload,
    background: !!d.background,
  };
}

// A draft of a new endpoint.
export function newDraft(id) {
  const d = {
    id, isNew: true, baseUrl: '', certificateAlias: null, username: '', password: '',
    hasStoredPassword: false, forgetPassword: false, healthUpload: false, background: false, name: null,
  };
  d.opened = fields(d);
  return d;
}

// A draft of an endpoint as the state lists it.
export function draftOf(row) {
  const d = {
    id: row.id,
    isNew: false,
    baseUrl: row.baseUrl || '',
    certificateAlias: row.certificateAlias || null,
    username: row.username || '',
    password: '',
    hasStoredPassword: !!row.hasPassword,
    forgetPassword: false,
    healthUpload: !!row.healthUpload,
    background: !!row.background,
    name: row.name || null,
  };
  d.opened = fields(d);
  return d;
}

// A password is in force after saving: typed now, or stored and kept.
export function hasPassword(d) {
  return !!d.password || (d.hasStoredPassword && !d.forgetPassword);
}

// Why the draft cannot be saved ('address', 'password', 'user'), or null.
export function draftProblem(d) {
  if (!normalizeBase(d.baseUrl)) return 'address';
  const user = String(d.username || '').trim();
  if (user && !hasPassword(d)) return 'password';
  if (!user && d.password) return 'user';
  return null;
}

// Anything differs from what the editor opened with.
export function draftChanged(d) {
  if (d.password || d.forgetPassword) return true;
  const now = fields(d);
  const o = d.opened || {};
  return now.baseUrl !== o.baseUrl || now.certificateAlias !== o.certificateAlias
    || now.username !== o.username || now.healthUpload !== o.healthUpload
    || now.background !== o.background;
}

// What closing the editor does: an untouched draft just closes; changes are
// saved on the way out, as Android editors do; changes that cannot be saved
// are asked about, never dropped silently.
export function onClose(d) {
  if (!draftChanged(d)) return { action: 'close' };
  const problem = draftProblem(d);
  return problem ? { action: 'ask', problem } : { action: 'save' };
}

export const PROBLEM_TEXT = {
  address: 'The address is missing or is not an https URL.',
  password: 'A user name needs a password.',
  user: 'A password needs a user name.',
};

// The draft as the app takes it for saving (no name, nothing it decides itself).
export function draftMessage(d) {
  return {
    id: d.id,
    isNew: !!d.isNew,
    baseUrl: String(d.baseUrl || '').trim(),
    certificateAlias: d.certificateAlias || null,
    username: String(d.username || '').trim(),
    password: d.password || '',
    forgetPassword: !!d.forgetPassword,
    healthUpload: !!d.healthUpload,
    background: !!d.background,
  };
}

// One line on what is set up for an endpoint.
export function endpointSummary(row) {
  const cert = row.certificateAlias || 'none';
  const user = row.username || 'none';
  return `Certificate: ${cert} · Sign-in: ${user} · Health data: ${row.healthUpload ? 'on' : 'off'}`;
}

// ── Instance states ─────────────────────────────────────────────────────────
// What an endpoint is to the phone (the app's InstanceState): the one shown
// (foreground), one that keeps syncing health data and listening for its wake
// phrase while another is shown (background), or one that rests (disabled).
export const STATE_TEXT = {
  foreground: 'Active',
  background: 'Background',
  disabled: 'Disabled',
};

// The state of a row, as the list shows it; the shown one is 'foreground'
// even from an app that does not send a state.
export function instanceState(row) {
  if (!row) return 'disabled';
  if (row.current) return 'foreground';
  if (row.state === 'background' || row.state === 'disabled') return row.state;
  return row.background ? 'background' : 'disabled';
}

// ── Importing a client certificate ──────────────────────────────────────────
// How an import ended, as the app reports it (CertificateImport.Outcome), and
// what the editor says about it. 'installed' is followed by Android's chooser,
// whose answer makes it 'selected' or 'unselected'; 'none' (no file picked)
// says nothing.
export const IMPORT_TEXT = {
  installed: 'Certificate installed. Select it in the list Android shows: only then may Ara use it.',
  selected: 'Certificate installed and selected.',
  unselected: 'Certificate installed, not selected yet. Android lets Ara use it once you select it: Select certificate.',
  cancelled: 'No certificate was installed. If Android’s installer did not open, this device does not let apps install certificates: install it in the device’s own settings or through its management.',
  'not-pkcs12': 'This is not a PKCS#12 file (.p12 or .pfx). Import one that holds the certificate together with its private key.',
  'too-large': 'This file is too large to be a certificate file.',
  unreadable: 'The file could not be read.',
  unavailable: 'This device has no certificate installer that Ara can open: install the certificate in the device’s own settings or through its management.',
  blocked: 'Installing certificates is turned off on this device, by its administrator or a device policy.',
  'no-picker': 'This device has no file picker to choose the certificate file with.',
};

const IMPORT_ERRORS = new Set(['not-pkcs12', 'too-large', 'unreadable', 'unavailable', 'blocked', 'no-picker']);

// The editor's note for an import outcome: {text, error}, or null for none.
export function importNote(outcome) {
  if (typeof outcome !== 'string' || !Object.prototype.hasOwnProperty.call(IMPORT_TEXT, outcome)) return null;
  return { text: IMPORT_TEXT[outcome], error: IMPORT_ERRORS.has(outcome) };
}

// The outcome once the chooser has answered (with the alias picked, or none).
export function importChosen(outcome, alias) {
  if (outcome !== 'installed') return outcome || null;
  return alias ? 'selected' : 'unselected';
}

// ── Health data ─────────────────────────────────────────────────────────────
// The status as the app reports it (HealthSyncStatus): mirrors the
// dashboard's components/shell.js, which shows the same lines.

export function describeHealthSync(st, locale) {
  if (!st) return '';
  if (st.permissions === 'none') return 'Health Connect access not granted yet. The app asks on the next update.';
  if (!st.last_sync_at) return 'No update yet.';
  const when = new Date(st.last_sync_at);
  const at = Number.isNaN(when.getTime()) ? st.last_sync_at : when.toLocaleString(locale || [], {
    weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  if (st.last_result === 'error') return `Last update ${at} failed${st.last_error ? `: ${st.last_error}` : '.'}`;
  if (st.last_result === 'skipped') return `Last update ${at} skipped.`;
  const n = Number.isFinite(st.last_count) ? `, ${st.last_count} change${st.last_count === 1 ? '' : 's'}` : '';
  const partial = st.permissions === 'partial' ? ' Access is granted for some data types only.' : '';
  return `Last update ${at}${n}.${partial}`;
}

export function describeHealthSyncEvent(d) {
  if (!d) return '';
  if (d.state === 'started') return 'Updating health data…';
  if (d.state === 'done') {
    const n = Number.isFinite(d.count) ? `, ${d.count} change${d.count === 1 ? '' : 's'}` : '';
    return `Health data updated${n}.`;
  }
  if (d.state === 'error') return `Health data update failed${d.error ? `: ${d.error}` : '.'}`;
  if (d.state === 'skipped') {
    const why = {
      interval: 'updated a moment ago',
      'no-permission': 'Health Connect access is not granted',
      unavailable: 'Health Connect is not available on this phone',
      'no-backend-support': 'this Retinue does not accept health data',
      disabled: 'health data is not uploaded to this endpoint',
    }[d.reason];
    return `Health data update skipped${why ? `: ${why}` : ''}.`;
  }
  return '';
}

// ── Phone calls ─────────────────────────────────────────────────────────────
// The card is the current endpoint's: `direct` is that endpoint's own switch
// (not part of an endpoint draft: saving one leaves it alone), the permission
// is the phone's. What stands between the switch and a call placed without a
// tap, with the action that fixes it: the app asks again, or opens Android's
// page for Ara once Android no longer asks.
export function callNotes(c) {
  if (!c || !c.direct || c.permissionGranted) return [];
  return [{
    text: 'Android has not allowed Ara to make calls, so calls open the dialer.',
    action: 'calls.permission', label: 'Allow phone calls',
  }];
}

// ── Wake phrase ─────────────────────────────────────────────────────────────
// What stands between the chosen mode and a working wake phrase, each with the
// action that fixes it where the app has one.
export function wakeNotes(w) {
  if (!w) return [];
  const notes = [];
  const on = w.mode && w.mode !== 'off';
  if (on && !w.micGranted) notes.push({ text: 'Microphone permission is needed. Ara asks for it when you choose a mode.' });
  if (on && !w.modelsReady) {
    // A model in use with a feature model missing (an upload while offline): the list would contradict the other note.
    const chosen = Array.isArray(w.models) && w.models.some((m) => m.active);
    notes.push({
      text: chosen
        ? 'The feature models are missing: download them under Advanced.'
        : 'No phrase model is in use yet: download one of the list and tap Use, or upload your own.',
    });
  }
  if (w.mode === 'always') {
    if (!w.notificationsAllowed) {
      notes.push({
        text: 'Notifications for Ara are off, so a wake heard in the background cannot open Ara. Turn on Ara’s notifications, including "Wake phrase heard".',
        action: 'wake.notifications', label: 'Open notification settings',
      });
    }
    if (!w.fullScreenAllowed) {
      notes.push({
        text: 'To open Ara when the phrase is heard in the background or on the lock screen, allow full-screen notifications for Ara.',
        action: 'wake.fullScreen', label: 'Allow full-screen notifications',
      });
    }
    if (!w.overlayAllowed) {
      notes.push({
        text: 'While the phone is unlocked with another app in front, a wake only shows a notification to tap. To bring Ara forward at once, allow it to display over other apps.',
        action: 'wake.overlay', label: 'Allow display over other apps',
      });
    }
  }
  return notes;
}

// ── Launcher bar ────────────────────────────────────────────────────────────
// The tray's order, with or without Android's usage stats, and the button to
// the system page that gives (or takes back) usage access.
export function usageAccessNote(launcher) {
  const on = !!(launcher && launcher.usageAccess);
  return on
    ? { text: 'Ara has usage access: the tray counts apps however they were opened.', label: 'Usage access settings' }
    : {
      text: 'The tray counts the apps you open from Ara. With usage access it also counts those opened from a notification, from Recents or from another app.',
      label: 'Allow usage access',
    };
}

// The other instances listened for now, which wake (and come to the front)
// with their own phrase.
export function alsoListeningLine(w) {
  const names = (w && Array.isArray(w.alsoListening)) ? w.alsoListening.filter((n) => typeof n === 'string' && n) : [];
  if (!names.length) return '';
  const them = names.length === 1 ? 'its' : 'each one’s';
  return `Also listening in the background for ${names.join(', ')}: ${them} own phrase brings it to the front.`;
}

export function wakeModelsLine(w) {
  if (!w) return '';
  const yes = (b) => (b ? 'present' : 'missing');
  return `Feature models: melspectrogram ${yes(w.melspectrogram)} · embedding ${yes(w.embedding)}. They are downloaded with the first phrase model.`;
}

// One row of the phrase model list: what it is, whether it is the one in use,
// and the buttons it offers. A model must be downloaded before it can be used;
// the user's own stay on the list after another is chosen, to go back to.
const SOURCE_TEXT = { builtin: 'openWakeWord', file: 'Your file', url: 'From a URL' };

export function wakeModelRow(m, w) {
  const busy = !!(w && w.busy);
  const working = busy && w.busyId === m.id;
  const parts = [SOURCE_TEXT[m.source] || ''];
  if (working) parts.push(m.downloaded ? 'working…' : 'downloading…');
  else if (!m.downloaded) parts.push('not downloaded');
  const actions = [];
  if (!m.downloaded) {
    if (m.url) actions.push({ label: 'Download', msg: { type: 'wake.model.download', id: m.id }, primary: true });
  } else if (m.active) {
    actions.push({ label: 'Stop using', msg: { type: 'wake.model.none' } });
  } else {
    actions.push({ label: 'Use this model', msg: { type: 'wake.model.use', id: m.id }, primary: true });
  }
  if (m.removable) actions.push({ label: 'Remove', msg: { type: 'wake.model.remove', id: m.id }, danger: true, confirm: true });
  else if (m.downloaded && !m.active) actions.push({ label: 'Delete download', msg: { type: 'wake.model.remove', id: m.id } });
  // Choosing a model is instant; downloads and removals wait for the job under way.
  for (const a of actions) a.disabled = busy && a.msg.type !== 'wake.model.use' && a.msg.type !== 'wake.model.none';
  return { name: m.name, detail: parts.filter(Boolean).join(' · '), active: !!m.active, actions };
}

// The line under the card's title: which model is listened for.
export function wakeActiveLine(w) {
  const models = (w && Array.isArray(w.models)) ? w.models : [];
  const active = models.find((m) => m.active);
  return active ? `In use: ${active.name}` : 'No phrase model in use.';
}

// ── The instance's own section ──────────────────────────────────────────────
// The message settings-instance.html posts from its frame: trusted only from
// the current endpoint's origin, and only its height is used.
export function instanceHeight(data) {
  if (!data || typeof data !== 'object' || data.type !== 'retinue-instance-settings') return null;
  const h = Number(data.height);
  if (!Number.isFinite(h) || h < 0) return null;
  return Math.min(Math.ceil(h), 20000);
}
