// The settings page's decisions, without a page: what an endpoint draft may
// be saved as and what closing the editor does with it, the values the page
// shows for the dashboard's per-device preferences (with the same bounds and
// defaults the dashboard applies), the health line, and the wake phrase's
// notes. Kept free of DOM work, so app/src/test/js/settings-page.test.mjs runs
// it under Node. The page itself is page.mjs; the app's side is
// os.retinue.ara.settings (SettingsBridge, SettingsMessages, SettingsState).

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
  };
}

// A draft of a new endpoint.
export function newDraft(id) {
  const d = {
    id, isNew: true, baseUrl: '', certificateAlias: null, username: '', password: '',
    hasStoredPassword: false, forgetPassword: false, healthUpload: false, name: null,
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
    || now.username !== o.username || now.healthUpload !== o.healthUpload;
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
  };
}

// One line on what is set up for an endpoint.
export function endpointSummary(row) {
  const cert = row.certificateAlias || 'none';
  const user = row.username || 'none';
  return `Certificate: ${cert} · Sign-in: ${user} · Health data: ${row.healthUpload ? 'on' : 'off'}`;
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

// ── Wake phrase ─────────────────────────────────────────────────────────────
// What stands between the chosen mode and a working wake phrase, each with the
// action that fixes it where the app has one.
export function wakeNotes(w) {
  if (!w) return [];
  const notes = [];
  const on = w.mode && w.mode !== 'off';
  if (on && !w.micGranted) notes.push({ text: 'Microphone permission is needed. Ara asks for it when you choose a mode.' });
  if (on && !w.modelsReady) notes.push({ text: 'Download the feature models and a phrase model first, under Advanced.' });
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

export function wakeModelsLine(w) {
  if (!w) return '';
  const yes = (b) => (b ? 'present' : 'missing');
  return `Melspectrogram: ${yes(w.melspectrogram)} · Embedding: ${yes(w.embedding)} · Phrase: ${yes(w.phrase)}`;
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
