/* ===========================================================================
   STRIDE — the Session screen, shared by every interface.
   ---------------------------------------------------------------------------
   A list of saved structured sessions (intervals or a progression) and the
   editor that makes them. See docs/superpowers/specs/2026-11-21-session-mode-
   design.md for the contract; this file is only the screen.

   **Why one shared screen and not five.** It is used for a minute before a
   run, never during one, and it is a form: five copies of a form are five
   chances for one of them to stop matching what Kotlin accepts. So it follows
   stride-settings.js exactly — injected into whichever UI is up, inside a
   shadow root, every class prefixed `ss-`, colours taken from the host UI
   through PALETTES.

   **Why nothing is clamped here.** The steppers stop at the board's reported
   range so the screen does not offer what the belt cannot do, but that is a
   courtesy. Kotlin validates on save and on start and clamps every step when
   the session is resolved, and a session saved on a day the board reported a
   smaller range must not be rewritten by this page either. A value that is
   already outside the range is shown as stored and only ever moved back
   towards it.

   **Why START does not save.** "Six reps today instead of eight" is a tweak
   for one run, not a new session to keep. START hands the edited JSON to
   chooseSession as it stands; SAVE is a separate decision.
   =========================================================================== */
(function (global) {
  'use strict';

  var root = null;          // .ss-root inside the shadow root
  var shadowHost = null;
  var view = null;          // the swappable list / editor container
  var modal = null;
  var opts = {};
  var sessions = [];        // last list from the bridge
  var selectedId = '';
  var L = null;             // board limits, display only

  var LIMITS_FALLBACK = { minGrade: -3, maxGrade: 12, minKph: 1.6, maxKph: 20 };

  function bridge() { return global.Stride; }
  function has(fn) { try { return typeof bridge()[fn] === 'function'; } catch (e) { return false; } }

  /* The physical keys and the first touch after the screen wakes are
     swallowed by stride-core.js so the press that wakes the console does not
     also land on whatever was under the finger. Honour it here too. */
  function woke() {
    try { return !!global.STRIDE.justWoke(); } catch (e) { return false; }
  }

  function loadLimits() {
    var l = null;
    try { if (has('boardLimits')) l = JSON.parse(bridge().boardLimits()); } catch (e) { l = null; }
    // Same test as stride-core.js: until the poll loop has read the board
    // Kotlin can answer with zeros, and a 0..0 km/h stepper is a dead stepper.
    L = (l && l.maxKph > 0 && l.maxGrade > 0) ? l : LIMITS_FALLBACK;
  }

  /* A session from the bridge is trusted only as far as its shape. Kotlin
     rejects unknown kinds on load, but this page also runs on a desktop stub
     and, one day, against a newer Kotlin with kinds this file has never heard
     of — those are skipped rather than drawn wrong or crashed on. */
  function usable(s) {
    if (!s || typeof s !== 'object' || !s.warmup || !s.cooldown) return false;
    if (s.kind === 'interval') return !!(s.interval && s.interval.on && s.interval.off);
    if (s.kind === 'progression') return !!s.progression;
    return false;
  }

  function loadSessions() {
    var list = [];
    try { if (has('sessionsJson')) list = JSON.parse(bridge().sessionsJson()) || []; } catch (e) { list = []; }
    if (!(list instanceof Array)) list = [];
    sessions = list.filter(function (s) {
      var ok = usable(s);
      if (!ok) { try { console.log('StrideSession: skipped unusable session', JSON.stringify(s)); } catch (e) {} }
      return ok;
    });
    return sessions;
  }

  function clone(o) { return JSON.parse(JSON.stringify(o)); }

  /* Builder's call, from the spec: a warm-up long enough to mean it, six
     honest reps, and a cool-down. Speeds are the owner's usual ones. */
  function newInterval() {
    return {
      name: '', kind: 'interval',
      warmup:   { sec: 600, grade: 1.0, kph: 6.0 },
      cooldown: { sec: 300, grade: 0.0, kph: 5.0 },
      interval: {
        on:  { sec: 60, grade: 1.0, kph: 12.0 },
        off: { sec: 60, grade: 1.0, kph: 6.0 },
        reps: 6
      }
    };
  }

  function newProgression() {
    return {
      name: '', kind: 'progression',
      warmup:   { sec: 600, grade: 1.0, kph: 6.0 },
      cooldown: { sec: 300, grade: 0.0, kph: 5.0 },
      progression: {
        sec: 1200, startGrade: 0.0, endGrade: 4.0,
        startKph: 8.0, endKph: 12.0, shape: 'up'
      }
    };
  }

  /* ---- arithmetic -------------------------------------------------------- */

  /* The off period after the last rep is dropped because the cool-down
     follows straight away (Sessions.resolve does the same). The total here
     has to agree with what the belt will actually do, or the number on the
     editor is a lie by one off period. */
  function totalSec(s) {
    var t = (s.warmup.sec || 0) + (s.cooldown.sec || 0);
    if (s.kind === 'interval') {
      var i = s.interval, n = Math.max(1, i.reps || 1);
      t += n * (i.on.sec || 0) + (n - 1) * (i.off.sec || 0);
    } else {
      t += s.progression.sec || 0;
    }
    return t;
  }

  function clock(sec) {
    sec = Math.round(sec);
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), x = sec % 60;
    var ss = ('0' + x).slice(-2);
    return h ? h + ':' + ('0' + m).slice(-2) + ':' + ss : m + ':' + ss;
  }

  /* The session as [t, kph, grade] corners. A flat step is two corners (so
     it draws as a level line with an edge, the way the belt will feel it); a
     progression is a straight line between its ends; up-and-down peaks at
     half time, as resolve splits it. */
  function profile(s) {
    var pts = [], t = 0;
    function flat(p) { pts.push([t, p.kph, p.grade]); t += p.sec; pts.push([t, p.kph, p.grade]); }
    flat(s.warmup);
    if (s.kind === 'interval') {
      var i = s.interval, n = Math.max(1, i.reps || 1);
      for (var k = 0; k < n; k++) {
        flat(i.on);
        if (k < n - 1) flat(i.off);
      }
    } else {
      var p = s.progression;
      pts.push([t, p.startKph, p.startGrade]);
      if (p.shape === 'updown') {
        pts.push([t + p.sec / 2, p.endKph, p.endGrade]);
        pts.push([t + p.sec, p.startKph, p.startGrade]);
      } else {
        pts.push([t + p.sec, p.endKph, p.endGrade]);
      }
      t += p.sec;
    }
    flat(s.cooldown);
    return pts;
  }

  /* One polyline, scaled to the session itself. A minimum span keeps a
     nearly flat session looking nearly flat instead of magnifying 0.5 %
     into a mountain — the same reason stride-settings.js gives its route
     sparkline a floor. */
  function line(pts, idx, w, h, minSpan) {
    var tMax = pts[pts.length - 1][0] || 1;
    var lo = Infinity, hi = -Infinity;
    for (var i = 0; i < pts.length; i++) {
      lo = Math.min(lo, pts[i][idx]); hi = Math.max(hi, pts[i][idx]);
    }
    var span = Math.max(hi - lo, minSpan);
    var base = (lo + hi) / 2 - span / 2;
    var pad = 3;
    return pts.map(function (p) {
      return (p[0] / tMax * w).toFixed(1) + ',' +
             (pad + (1 - (p[idx] - base) / span) * (h - 2 * pad)).toFixed(1);
    }).join(' ');
  }

  function sparkSvg(s, w, h, withGrade) {
    var pts = profile(s);
    var out = '<svg width="' + w + '" height="' + h + '" viewBox="0 0 ' + w + ' ' + h + '">';
    if (withGrade) {
      out += '<polyline points="' + line(pts, 2, w, h, 4) + '" fill="none" ' +
             'stroke="rgb(var(--ss-warm))" stroke-width="2" stroke-opacity=".75" ' +
             'stroke-linejoin="round"/>';
    }
    out += '<polyline points="' + line(pts, 1, w, h, 4) + '" fill="none" ' +
           'stroke="' + (withGrade ? 'rgb(var(--ss-acc))' : 'currentColor') + '" ' +
           'stroke-width="' + (withGrade ? 2.5 : 2) + '" stroke-linejoin="round"/>';
    return out + '</svg>';
  }

  // Lowest and highest of one column of profile(): 1 = km/h, 2 = grade.
  function range(s, idx) {
    var pts = profile(s);
    var lo = Infinity, hi = -Infinity;
    for (var i = 0; i < pts.length; i++) { lo = Math.min(lo, pts[i][idx]); hi = Math.max(hi, pts[i][idx]); }
    return [lo, hi];
  }

  /* ---- styles ------------------------------------------------------------ */
  var CSS = [
    ':host{all:initial}',
    '.ss-root{position:absolute;top:0;right:0;bottom:0;left:0;',
    '  background:rgb(var(--ss-bg));color:var(--ss-ink);',
    '  font-family:"Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased;',
    '  user-select:none;-webkit-tap-highlight-color:rgba(0,0,0,0)}',
    '*{margin:0;padding:0;box-sizing:border-box}',
    'button{font-family:inherit;outline:none;border:none;background:none;color:inherit;',
    '  -webkit-tap-highlight-color:rgba(0,0,0,0)}',
    '.ss-root::before{content:"";position:absolute;top:0;right:0;bottom:0;left:0;pointer-events:none;',
    '  background:radial-gradient(ellipse 900px 520px at 50% 44%,',
    '  rgba(var(--ss-glow1),.55) 0%,rgba(var(--ss-glow2),.25) 45%,transparent 72%)}',

    '.ss-view{position:relative;z-index:2;height:100%;display:flex;flex-direction:column;padding:24px 40px 0}',
    '.ss-top{flex:none;display:flex;align-items:center;height:76px}',
    '.ss-title{flex:1;font-size:38px;font-weight:200;letter-spacing:.02em}',

    '.ss-pill{height:72px;padding:0 30px;border-radius:18px;background:rgba(var(--ss-surf),.5);',
    '  border:2px solid rgb(var(--ss-line));color:var(--ss-ink);font-size:18px;letter-spacing:.12em;',
    '  flex:none;white-space:nowrap}',
    '.ss-pill.ss-down{transform:scale(.98);background:rgba(var(--ss-acc),.2)}',
    '.ss-pill.ss-go{border-color:rgb(var(--ss-acc));background:rgba(var(--ss-acc),.16)}',
    '.ss-pill.ss-warn{border-color:rgb(var(--ss-alert));color:var(--ss-alert-ink)}',
    /* The console has a bottom bezel and the hand is coming up from a
       moving belt: the buttons that matter are the biggest on the screen. */
    '.ss-pill.ss-big{height:84px;min-width:230px;font-size:21px}',
    '.ss-pill.ss-off{opacity:.3}',
    '.ss-back{min-width:170px}',

    /* padding-bottom, not a spacer: it scrolls with the list, so the last row
       can be brought clear of the footer. */
    '.ss-scroll{flex:1;margin-top:16px;overflow-y:auto;padding-right:12px;padding-bottom:20px;',
    '  -webkit-overflow-scrolling:touch}',
    '.ss-scroll::-webkit-scrollbar{width:8px}',
    '.ss-scroll::-webkit-scrollbar-thumb{background:rgb(var(--ss-line));border-radius:4px}',

    '.ss-item{display:flex;align-items:center;height:100px;padding:0 16px 0 28px;margin-bottom:12px;',
    '  border-radius:20px;background:rgba(var(--ss-surf),.36);border:2px solid rgb(var(--ss-line))}',
    '.ss-item.ss-on{border-color:rgb(var(--ss-acc));background:rgba(var(--ss-acc),.12)}',
    '.ss-iname{flex:1;min-width:0;margin-right:20px;font-size:26px;font-weight:300;',
    '  white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.ss-ikind{width:160px;flex:none;font-size:17px;color:var(--ss-dim);letter-spacing:.04em}',
    '.ss-imin{width:110px;flex:none;margin-right:28px;text-align:right;font-size:24px;font-weight:300}',
    '.ss-imin small{font-size:14px;color:var(--ss-dim);margin-left:4px}',
    '.ss-ispark{width:170px;height:44px;flex:none;margin-right:24px;color:rgb(var(--ss-acc))}',
    '.ss-ispark svg{display:block}',
    '.ss-item .ss-pill{height:68px;padding:0 28px}',
    '.ss-empty{margin-top:10px;padding:40px;border-radius:20px;text-align:center;',
    '  border:2px dashed rgb(var(--ss-line));color:var(--ss-faint);font-size:19px;line-height:1.5}',

    '.ss-foot{flex:none;display:flex;align-items:center;padding:16px 0 30px;',
    '  border-top:1px solid rgba(var(--ss-line),.6)}',
    '.ss-foot .ss-pill{margin-right:14px}',
    '.ss-foot .ss-pill:last-child{margin-right:0}',
    '.ss-gap{flex:1}',
    '.ss-msg{margin:0 20px;max-width:380px;text-align:right;font-size:16px;color:var(--ss-dim);line-height:1.4}',
    '.ss-msg.ss-bad{color:var(--ss-alert-ink)}',

    '.ss-name{flex:1;min-width:0;height:68px;margin-right:24px;padding:0 20px;border-radius:16px;',
    '  background:rgba(var(--ss-bg),.7);border:2px solid rgb(var(--ss-line));color:var(--ss-ink);',
    '  font-size:28px;font-weight:300;font-family:inherit;outline:none}',
    '.ss-name::placeholder{color:var(--ss-faint)}',
    '.ss-name.ss-ask{border-color:rgb(var(--ss-acc))}',
    '.ss-total{flex:none;margin-right:26px;text-align:right}',
    '.ss-total small{display:block;font-size:12px;letter-spacing:.2em;text-transform:uppercase;color:var(--ss-faint)}',
    '.ss-total b{font-size:40px;font-weight:200}',

    '.ss-chart{flex:none;margin-top:14px;height:100px;padding:9px 16px 6px;border-radius:18px;',
    '  background:rgba(var(--ss-surf),.3);border:1px solid rgb(var(--ss-line))}',
    '.ss-leg{height:17px;font-size:13px;color:var(--ss-dim);letter-spacing:.04em;white-space:nowrap}',
    '.ss-leg span{margin-right:32px}',
    '.ss-leg i{display:inline-block;width:18px;height:3px;border-radius:2px;vertical-align:middle;margin-right:8px}',
    '.ss-chart svg{display:block;margin-top:2px}',

    '.ss-rows{flex:1;overflow:hidden}',
    '.ss-ch{display:flex;align-items:center;height:26px;margin-top:10px;font-size:12px;',
    '  letter-spacing:.2em;text-transform:uppercase;color:var(--ss-faint)}',
    '.ss-er{display:flex;align-items:center;height:80px;border-bottom:1px solid rgba(var(--ss-line),.6)}',
    '.ss-er:last-child{border-bottom:none}',
    '.ss-lab{width:200px;flex:none}',
    '.ss-lab b{display:block;font-size:19px;font-weight:300;letter-spacing:.12em}',
    '.ss-lab small{display:block;margin-top:3px;font-size:13px;color:var(--ss-faint)}',
    '.ss-cell{width:333px;flex:none;display:flex;justify-content:center}',
    '.ss-cell.ss-wide{width:666px}',
    '.ss-hint{width:666px;flex:none;padding:0 28px;font-size:15px;color:var(--ss-faint);line-height:1.45}',

    '.ss-step{display:flex;align-items:center}',
    '.ss-step button{width:72px;height:62px;border-radius:14px;background:rgba(var(--ss-surf),.5);',
    '  border:2px solid rgb(var(--ss-line));color:var(--ss-ink);font-size:30px;font-weight:200}',
    '.ss-step button.ss-down{background:rgba(var(--ss-acc),.24)}',
    '.ss-v{width:150px;text-align:center;font-size:27px;font-weight:300}',
    '.ss-v small{font-size:15px;color:var(--ss-dim);margin-left:5px}',

    '.ss-seg{display:flex;border:2px solid rgb(var(--ss-line));border-radius:16px;overflow:hidden}',
    '.ss-seg button{height:62px;width:196px;color:var(--ss-dim);font-size:17px;letter-spacing:.08em;',
    '  border-right:1px solid rgb(var(--ss-line))}',
    '.ss-seg button:last-child{border-right:none}',
    '.ss-seg button.ss-on{background:rgba(var(--ss-acc),.16);color:var(--ss-ink)}',

    '.ss-modal{position:absolute;top:0;right:0;bottom:0;left:0;z-index:30;display:none;',
    '  align-items:center;justify-content:center;background:rgba(0,0,0,.55)}',
    '.ss-modal.ss-on{display:flex}',
    '.ss-card{width:660px;padding:36px 40px 32px;border-radius:24px;background:var(--ss-panel);',
    '  border:2px solid rgb(var(--ss-line))}',
    '.ss-card p{font-size:25px;font-weight:300;line-height:1.35}',
    '.ss-card .ss-csub{margin-top:10px;font-size:16px;color:var(--ss-dim);line-height:1.45}',
    '.ss-btns{display:flex;justify-content:flex-end;margin-top:30px}',
    '.ss-btns .ss-pill{margin-left:14px;min-width:170px}',

    '.ss-kb{position:absolute;left:0;right:0;bottom:0;z-index:20;display:none;',
    '  background:var(--ss-panel);border-top:2px solid rgb(var(--ss-line));padding:16px 20px 20px}',
    '.ss-kb.ss-on{display:block}',
    '.ss-kb-peek{display:flex;align-items:center;margin:0 6px 14px;height:58px;',
    '  border-radius:14px;background:rgba(var(--ss-bg),.8);border:2px solid rgb(var(--ss-acc));',
    '  padding:0 18px;overflow:hidden}',
    '.ss-kb-peek .ss-kb-lbl{font-size:14px;color:var(--ss-faint);flex:none;margin-right:14px;',
    '  text-transform:uppercase;letter-spacing:.14em}',
    '.ss-kb-peek .ss-kb-val{font-size:22px;font-weight:300;color:var(--ss-ink);',
    '  white-space:nowrap;overflow:hidden}',
    '.ss-kb-peek .ss-kb-car{color:rgb(var(--ss-acc));font-weight:200}',
    '.ss-kb-row{display:flex;justify-content:center;margin-bottom:8px}',
    '.ss-kb-row button{height:62px;min-width:62px;margin:0 4px;border-radius:12px;',
    '  background:rgba(var(--ss-surf),.75);border:1px solid rgb(var(--ss-line));color:var(--ss-ink);',
    '  font-size:22px;font-weight:300}',
    '.ss-kb-row button.ss-down{background:rgba(var(--ss-acc),.28)}',
    '.ss-kb-row button.ss-wide{min-width:150px;font-size:16px;letter-spacing:.1em}',
    '.ss-kb-row button.ss-space{min-width:320px}',
    '.ss-kb-row button.ss-go{border-color:rgb(var(--ss-acc));background:rgba(var(--ss-acc),.18)}'
  ].join('');

  /* ---- palettes ----------------------------------------------------------
     The same role map as stride-settings.js, copied rather than shared
     because that file keeps its PALETTES private. A screen that opens in a
     different colour from the UI under it reads as a different app (that was
     the lesson Settings learnt on the console). Keep the two in step when a
     UI's colours change. Bare "r,g,b" triplets where the sheet uses alpha. */
  var PALETTES = {
    original: { bg: '5,11,31', panel: '#081127', line: '30,48,104', surf: '22,37,90',
                glow1: '28,58,140', glow2: '10,21,51',
                ink: '#eaf0ff', dim: '#7f92c4', faint: '#44548a',
                acc: '57,224,255', warm: '255,157,60', good: '93,255,155',
                alert: '255,92,122', alertInk: '#ffc4cf' },
    ember:    { bg: '11,10,9', panel: '#131110', line: '34,31,28', surf: '28,25,23',
                glow1: '48,36,24', glow2: '19,17,16',
                ink: '#f4efe6', dim: '#7c756b', faint: '#4a423a',
                acc: '224,164,88', warm: '224,164,88', good: '95,192,138',
                alert: '224,85,62', alertInk: '#f2b9ad' },
    cluster:  { bg: '13,15,16', panel: '#141719', line: '35,40,43', surf: '20,23,25',
                glow1: '30,38,42', glow2: '18,21,23',
                ink: '#eef3f5', dim: '#8b969b', faint: '#454d51',
                acc: '111,208,224', warm: '229,181,103', good: '95,192,138',
                alert: '217,45,32', alertInk: '#f2aaa4' },
    daylight: { bg: '234,230,220', panel: '#f6f3ea', line: '201,195,180', surf: '220,215,201',
                glow1: '246,243,234', glow2: '234,230,220',
                ink: '#1b1a17', dim: '#6f6a5e', faint: '#8a8374',
                acc: '61,107,74', warm: '181,101,29', good: '46,139,87',
                alert: '192,43,29', alertInk: '#c02b1d' },
    pacer:    { bg: '22,18,31', panel: '#211b2e', line: '47,39,67', surf: '43,35,64',
                glow1: '60,45,90', glow2: '28,23,40',
                ink: '#f7f4ff', dim: '#8f82a8', faint: '#6d6088',
                acc: '198,242,78', warm: '255,179,71', good: '198,242,78',
                alert: '255,77,61', alertInk: '#ffc0b8' }
  };

  /* Ember's accent and warm are the same amber, so speed and grade would
     draw in one colour on its chart. Grade takes Ember's "good" green there
     instead; elsewhere warm is distinct from the accent. */
  var GRADE_OVERRIDE = { ember: '95,192,138' };

  /* Which UI is showing. The page's own file name first: on the console it is
     the UI Kotlin loaded, and on a desktop the stub's currentUi() always says
     "original", which would paint Daylight's editor navy. */
  function uiName() {
    try {
      var m = /([a-z]+)\.html$/.exec(global.location.pathname || '');
      if (m && PALETTES[m[1]]) return m[1];
    } catch (e) {}
    var ui = 'original';
    try { ui = global.STRIDE.currentUi() || ui; } catch (e) {}
    return ui;
  }

  function paletteCss() {
    var ui = uiName();
    var c = PALETTES[ui] || PALETTES.original;
    return ':host,.ss-root{' +
      '--ss-bg:' + c.bg + ';--ss-panel:' + c.panel + ';--ss-line:' + c.line + ';' +
      '--ss-surf:' + c.surf + ';--ss-glow1:' + c.glow1 + ';--ss-glow2:' + c.glow2 + ';' +
      '--ss-ink:' + c.ink + ';--ss-dim:' + c.dim + ';--ss-faint:' + c.faint + ';' +
      '--ss-acc:' + c.acc + ';--ss-warm:' + (GRADE_OVERRIDE[ui] || c.warm) + ';--ss-good:' + c.good + ';' +
      '--ss-alert:' + c.alert + ';--ss-alert-ink:' + c.alertInk + '}';
  }

  /* ---- tiny DOM helpers -------------------------------------------------- */
  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  function esc(t) {
    return String(t == null ? '' : t).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  /* Presses fire on touchstart, as everywhere on the console: `click` waits
     for touch-end plus gesture disambiguation, which reads as an ignored
     press. Mouse too, so the screen works in a desktop browser. */
  function press(e, fn) {
    if (!e) return e;
    var done = false;
    e.addEventListener('touchstart', function (ev) {
      done = true; e.classList.add('ss-down');
      if (!woke()) fn(ev);
      ev.preventDefault();
    }, { passive: false });
    var up = function () { e.classList.remove('ss-down'); };
    e.addEventListener('touchend', up);
    e.addEventListener('touchcancel', up);
    e.addEventListener('click', function (ev) { if (!done) fn(ev); });
    return e;
  }

  /* Fine on a tap, coarse on a hold, accelerating — copied from stride-core's
     stepper() so a held + here feels like a held + on the live screen. A flat
     fast repeat overshot; a flat slow one made 6 → 14 km/h a chore. */
  var HOLD_MS = 550, REPEAT_START_MS = 420, REPEAT_MIN_MS = 110, REPEAT_DECAY = 0.82;

  function holdRepeat(e, fn) {
    var holdTimer = null, repeatTimer = null, handled = false;
    function begin() {
      if (woke()) return;
      fn(false);
      var gap = REPEAT_START_MS;
      function again() {
        fn(true);
        gap = Math.max(REPEAT_MIN_MS, gap * REPEAT_DECAY);
        repeatTimer = setTimeout(again, gap);
      }
      holdTimer = setTimeout(again, HOLD_MS);
    }
    function end() {
      clearTimeout(holdTimer); clearTimeout(repeatTimer);
      holdTimer = repeatTimer = null;
      e.classList.remove('ss-down');
    }
    e.addEventListener('touchstart', function (ev) {
      handled = true; e.classList.add('ss-down'); begin(); ev.preventDefault();
    }, { passive: false });
    e.addEventListener('touchend', end);
    e.addEventListener('touchcancel', end);
    e.addEventListener('mousedown',  function () { if (!handled) begin(); });
    e.addEventListener('mouseup',    function () { if (!handled) end(); });
    e.addEventListener('mouseleave', function () { if (!handled) end(); });
    return e;
  }

  /* Clamp only in the direction of travel. A stored value already past the
     board's range (saved on a day it reported a wider one) stays as it is
     until somebody moves it, and then only moves back inside. */
  /** km/h as a pace, m:ss per kilometre. */
  function paceOf(kph) {
    if (!(kph > 0)) return '–:––';
    var sec = Math.round(3600 / kph);
    return Math.floor(sec / 60) + ':' + ('0' + (sec % 60)).slice(-2);
  }

  function bounded(v, nv, d, min, max) {
    if (d > 0) return Math.min(nv, Math.max(v, max));
    return Math.max(nv, Math.min(v, min));
  }

  var KIND = {
    /* ±15 s under five minutes, where a rep length lives; ±1 min above,
       where a warm-up lives. At exactly 5:00 the step that applies is the
       one on the side you are heading to, so 5:00 goes down to 4:45. */
    sec: {
      fmt: function (v) { return clock(v); },
      bump: function (v, d) {
        var step = d > 0 ? (v >= 300 ? 60 : 15) : (v > 300 ? 60 : 15);
        return bounded(v, v + d * step, d, 10, 3 * 3600);
      }
    },
    grade: {
      fmt: function (v) { return v.toFixed(1) + '<small>%</small>'; },
      bump: function (v, d) {
        return Math.round(bounded(v, v + d * 0.5, d, L.minGrade, L.maxGrade) * 100) / 100;
      }
    },
    /* Speed is set as pace, min/km, because that is how the owner thinks
       about a rep (2026-11-21). Stored as km/h all the same: Kotlin, the
       board and saved sessions all speak km/h, and a pace is only a way of
       writing one. + and - move the number on screen, so + is a slower pace.
       Five seconds a tap, fifteen on a hold. The belt resolves 0.1 km/h,
       which at walking paces is coarser than five seconds a kilometre, so a
       slow pace may land a few seconds either side of the one shown. */
    kph: {
      fmt: function (v) { return paceOf(v) + '<small>/km</small>'; },
      bump: function (v, d, coarse) {
        var fast = Math.ceil(3600 / L.maxKph / 5) * 5;
        var slow = Math.floor(3600 / L.minKph / 5) * 5;
        var pace = Math.round(3600 / (v > 0 ? v : L.minKph) / 5) * 5;
        var next = bounded(pace, pace + d * (coarse ? 15 : 5), d, fast, slow);
        return Math.round(3600 / next * 1000) / 1000;
      }
    },
    reps: {
      fmt: function (v) { return v + '<small>×</small>'; },
      bump: function (v, d) { return bounded(v, v + d, d, 1, 50); }
    }
  };

  /* A stepper bound to obj[key]. It writes straight into the draft and tells
     the editor, which redraws the total and the chart — no Apply step. */
  function stepper(obj, key, kind, after) {
    var k = KIND[kind];
    var s = el('div', 'ss-step');
    var v = el('div', 'ss-v');
    var draw = function () { v.innerHTML = k.fmt(Number(obj[key]) || 0); };
    var bump = function (d, coarse) {
      obj[key] = k.bump(Number(obj[key]) || 0, d, coarse);
      draw();
      if (after) after();
    };
    s.appendChild(holdRepeat(el('button', '', '&minus;'), function (c) { bump(-1, c); }));
    s.appendChild(v);
    s.appendChild(holdRepeat(el('button', '', '+'), function (c) { bump(1, c); }));
    draw();
    return s;
  }

  function msgEl() { return el('div', 'ss-msg', ''); }
  function say(m, text, bad) {
    if (!m) return;
    m.textContent = text || '';
    m.classList.toggle('ss-bad', !!bad);
  }

  /* ---- on-screen keyboard ------------------------------------------------
     Copied from stride-settings.js, which keeps its keyboard private. The
     console never shows the system one: the activity runs immersive-sticky
     and the IME is never given room (`mInputShown=false`), and `prompt()`
     does nothing in a WebView without a WebChromeClient. A session name is
     the only thing typed here, so this is the name-shaped subset of the
     Settings keyboard: no password dotting, no maxlength surprises.
     -------------------------------------------------------------------- */
  var kb = null, kbTarget = null, kbShift = false;

  var KB_ROWS = [
    '1234567890'.split(''),
    'qwertyuiop'.split(''),
    'asdfghjkl'.split(''),
    'zxcvbnm'.split('')
  ];
  // What session names realistically use: "8 x 1 min", "Tempo 10/12", "Hills #2".
  var KB_SYMS = ['.', '-', '/', '+', '×', '#', ':', '!'];

  function buildKeyboard() {
    if (kb) return kb;
    kb = el('div', 'ss-kb');
    var peek = el('div', 'ss-kb-peek');
    kb.appendChild(peek);

    var draw = function () {
      var v = kbTarget ? kbTarget.value : '';
      var label = (kbTarget && kbTarget.ssLabel) || '';
      peek.innerHTML =
        (label ? '<span class="ss-kb-lbl">' + label + '</span>' : '') +
        '<span class="ss-kb-val">' + esc(v) +
        '<span class="ss-kb-car">|</span></span>';
    };
    kb.ssDraw = draw;

    var type = function (ch) {
      if (!kbTarget) return;
      var max = parseInt(kbTarget.getAttribute('maxlength') || '0', 10);
      if (max && kbTarget.value.length >= max) return;
      kbTarget.value += ch;
      // Shift is a one-shot, as on every phone keyboard.
      if (kbShift) { kbShift = false; paintCase(); }
      draw();
    };

    // A name wants a capital first.
    var autoShift = function () {
      kbShift = !!(kbTarget && !kbTarget.value.length);
      paintCase();
    };

    KB_ROWS.forEach(function (chars, i) {
      var r = el('div', 'ss-kb-row');
      chars.forEach(function (c) {
        var b = press(el('button', '', c), function () {
          type(kbShift && i > 0 ? c.toUpperCase() : c);
        });
        b.ssChar = c;
        r.appendChild(b);
      });
      if (i === 3) {
        r.insertBefore(press(el('button', 'ss-wide', 'SHIFT'), function () {
          kbShift = !kbShift;
          paintCase();
        }), r.firstChild);
        r.appendChild(press(el('button', 'ss-wide', 'DELETE'), function () {
          if (!kbTarget) return;
          kbTarget.value = kbTarget.value.slice(0, -1);
          draw();
        }));
      }
      kb.appendChild(r);
    });

    kb.ssAutoShift = autoShift;

    var last = el('div', 'ss-kb-row');
    KB_SYMS.forEach(function (c) {
      last.appendChild(press(el('button', '', c), function () { type(c); }));
    });
    last.appendChild(press(el('button', 'ss-space', 'space'), function () { type(' '); }));
    last.appendChild(press(el('button', 'ss-wide ss-go', 'DONE'), closeKeyboard));
    kb.appendChild(last);

    root.appendChild(kb);
    return kb;
  }

  function paintCase() {
    if (!kb) return;
    [].forEach.call(kb.querySelectorAll('.ss-kb-row button'), function (b) {
      if (b.ssChar && /[a-z]/i.test(b.ssChar)) {
        b.textContent = kbShift ? b.ssChar.toUpperCase() : b.ssChar;
      }
    });
  }

  function openKeyboard(input) {
    buildKeyboard();
    kbTarget = input;
    kb.ssAutoShift();
    kb.ssDraw();
    kb.classList.add('ss-on');
  }

  function closeKeyboard() {
    if (!kb) return;
    var t = kbTarget;
    kb.classList.remove('ss-on');
    kbTarget = null;
    // Commit on close, the moment a blur would have. After clearing the
    // target, because a commit may go on to save and leave the editor.
    if (t && t.ssCommit) t.ssCommit();
  }

  /* ---- confirm ------------------------------------------------------------
     An in-screen card, not `confirm()`: like prompt(), a WebView without a
     WebChromeClient answers it without ever showing it. */
  function ask(text, sub, yesLabel, onYes) {
    modal.innerHTML = '';
    var card = el('div', 'ss-card');
    card.appendChild(el('p', '', esc(text)));
    if (sub) card.appendChild(el('div', 'ss-csub', esc(sub)));
    var btns = el('div', 'ss-btns');
    btns.appendChild(press(el('button', 'ss-pill', 'CANCEL'), function () {
      modal.classList.remove('ss-on');
    }));
    btns.appendChild(press(el('button', 'ss-pill ss-warn', yesLabel), function () {
      modal.classList.remove('ss-on');
      onYes();
    }));
    card.appendChild(btns);
    modal.appendChild(card);
    modal.classList.add('ss-on');
  }

  /* ---- start ------------------------------------------------------------- */

  /* Hands the session to Kotlin and gets out of the way without onClose: the
     session is running and Kotlin switches the screen, so going "back" to the
     setup flow would be going back to a screen that is no longer true.

     If the host passed an onStart it is used instead of calling the bridge
     here — the spec's setup-flow section has flow() do the chooseSession
     call, its Interfaces section has this file do it, and doing both would
     start the session twice. */
  function start(s, m) {
    var json = JSON.stringify(s);
    try {
      if (typeof opts.onStart === 'function') opts.onStart(json);
      else if (has('chooseSession')) bridge().chooseSession(json);
      else { say(m, 'This console cannot start sessions yet.', true); return; }
    } catch (e) {
      say(m, 'The console would not start this session.', true);
      return;
    }
    hide();
  }

  /* ---- list -------------------------------------------------------------- */
  function findSession(id) {
    for (var i = 0; i < sessions.length; i++) if (sessions[i].id === id) return sessions[i];
    return null;
  }

  function showList() {
    closeKeyboard();
    loadSessions();
    if (!findSession(selectedId)) selectedId = '';

    view.innerHTML = '';
    var top = el('div', 'ss-top');
    top.appendChild(el('div', 'ss-title', 'Sessions'));
    top.appendChild(press(el('button', 'ss-pill ss-back', 'BACK'), function () {
      hide();
      if (typeof opts.onClose === 'function') opts.onClose();
    }));
    view.appendChild(top);

    var sc = el('div', 'ss-scroll');
    view.appendChild(sc);

    var startBtn, m = msgEl();
    var paintStart = function () { startBtn.classList.toggle('ss-off', !selectedId); };

    if (!sessions.length) {
      sc.appendChild(el('div', 'ss-empty',
        'No saved sessions yet.<br>Make one with + NEW INTERVALS or + NEW PROGRESSION below.'));
    }
    sessions.forEach(function (s) {
      var r = el('div', 'ss-item' + (s.id === selectedId ? ' ss-on' : ''));
      r.appendChild(el('div', 'ss-iname', esc(s.name || 'Untitled')));
      r.appendChild(el('div', 'ss-ikind', s.kind === 'interval' ? 'Intervals' : 'Progression'));
      r.appendChild(el('div', 'ss-imin', Math.round(totalSec(s) / 60) + '<small>min</small>'));
      r.appendChild(el('div', 'ss-ispark', sparkSvg(s, 170, 44, false)));
      var edit = el('button', 'ss-pill', 'EDIT');
      // The Edit press must not also count as a press on the row under it.
      edit.addEventListener('touchstart', function (ev) { ev.stopPropagation(); }, { passive: true });
      edit.addEventListener('click', function (ev) { ev.stopPropagation(); });
      press(edit, function () { selectedId = s.id; showEditor(clone(s)); });
      r.appendChild(edit);
      press(r, function () {
        selectedId = s.id;
        [].forEach.call(sc.querySelectorAll('.ss-item'), function (x) { x.classList.remove('ss-on'); });
        r.classList.add('ss-on');
        say(m, '');
        paintStart();
      });
      sc.appendChild(r);
    });

    var foot = el('div', 'ss-foot');
    foot.appendChild(press(el('button', 'ss-pill ss-big', '+ NEW INTERVALS'), function () {
      showEditor(newInterval());
    }));
    foot.appendChild(press(el('button', 'ss-pill ss-big', '+ NEW PROGRESSION'), function () {
      showEditor(newProgression());
    }));
    foot.appendChild(el('div', 'ss-gap'));
    foot.appendChild(m);
    startBtn = press(el('button', 'ss-pill ss-big ss-go', 'START'), function () {
      var s = findSession(selectedId);
      if (!s) { say(m, 'Choose a session first.'); return; }
      start(s, m);
    });
    foot.appendChild(startBtn);
    paintStart();
    view.appendChild(foot);
  }

  /* ---- editor ------------------------------------------------------------ */
  function showEditor(draft) {
    closeKeyboard();
    var initial = JSON.stringify(draft);
    var saveAfterName = false;
    var isInterval = draft.kind === 'interval';

    view.innerHTML = '';
    var m = msgEl();

    var top = el('div', 'ss-top');
    var name = el('input', 'ss-name');
    name.value = draft.name || '';
    name.setAttribute('placeholder', 'Tap to name this session');
    name.setAttribute('maxlength', '24');
    name.setAttribute('autocomplete', 'off');
    // readonly: the on-screen keyboard owns the value, and the system one
    // never appears on this console anyway.
    name.setAttribute('readonly', 'readonly');
    name.ssLabel = 'Name';
    name.ssCommit = function () {
      name.classList.remove('ss-ask');
      draft.name = name.value.replace(/^\s+|\s+$/g, '');
      name.value = draft.name;
      if (saveAfterName) {
        saveAfterName = false;
        if (draft.name) doSave();
        else say(m, 'A saved session needs a name.', true);
      }
    };
    press(name, function () { openKeyboard(name); });
    top.appendChild(name);

    var total = el('div', 'ss-total');
    top.appendChild(total);
    top.appendChild(press(el('button', 'ss-pill ss-back', 'BACK'), function () {
      closeKeyboard();
      if (JSON.stringify(draft) === initial) { showList(); return; }
      ask('Leave without saving?', 'The changes to this session will be lost.',
          'DISCARD', showList);
    }));
    view.appendChild(top);

    var chart = el('div', 'ss-chart');
    var leg = el('div', 'ss-leg');
    var plot = el('div');
    chart.appendChild(leg);
    chart.appendChild(plot);
    view.appendChild(chart);

    var live = function () {
      total.innerHTML = '<small>Total</small><b>' + clock(totalSec(draft)) + '</b>';
      var sp = range(draft, 1), gr = range(draft, 2);
      leg.innerHTML =
        '<span><i style="background:rgb(var(--ss-acc))"></i>Pace ' +
          paceOf(sp[1]) + '–' + paceOf(sp[0]) + ' /km</span>' +
        '<span><i style="background:rgb(var(--ss-warm))"></i>Grade ' +
          gr[0].toFixed(1) + '–' + gr[1].toFixed(1) + ' %</span>';
      plot.innerHTML = sparkSvg(draft, 1166, 66, true);
    };

    var rows = el('div', 'ss-rows');
    var ch = el('div', 'ss-ch');
    ch.innerHTML = '<div class="ss-lab"></div><div class="ss-cell">Time</div>' +
                   '<div class="ss-cell">Grade</div><div class="ss-cell">Pace</div>';
    rows.appendChild(ch);

    var row = function (label, sub, cells) {
      var r = el('div', 'ss-er');
      r.appendChild(el('div', 'ss-lab', '<b>' + label + '</b>' + (sub ? '<small>' + sub + '</small>' : '')));
      cells.forEach(function (c) { r.appendChild(c); });
      rows.appendChild(r);
      return r;
    };
    var cell = function (child, wide) {
      var c = el('div', 'ss-cell' + (wide ? ' ss-wide' : ''));
      if (child) c.appendChild(child);
      return c;
    };
    var period = function (label, sub, p) {
      row(label, sub, [cell(stepper(p, 'sec', 'sec', live)),
                       cell(stepper(p, 'grade', 'grade', live)),
                       cell(stepper(p, 'kph', 'kph', live))]);
    };

    period('WARM-UP', '', draft.warmup);
    if (isInterval) {
      var iv = draft.interval;
      period('ON', 'the hard part', iv.on);
      period('OFF', 'recovery', iv.off);
      row('REPS', '', [cell(stepper(iv, 'reps', 'reps', live)),
        el('div', 'ss-hint', 'The last rep goes straight into the cool-down, ' +
                             'so there is one off period fewer than reps.')]);
    } else {
      var pg = draft.progression;
      var startRow, endRow;
      var drawEnds = function () {
        // Rebuilt rather than poked, so the swapped values show at once.
        var sr = row('START', 'where it begins', [cell(null),
          cell(stepper(pg, 'startGrade', 'grade', live)),
          cell(stepper(pg, 'startKph', 'kph', live))]);
        var er = row('END', pg.shape === 'updown' ? 'at half time, then back' : 'where it finishes', [cell(null),
          cell(stepper(pg, 'endGrade', 'grade', live)),
          cell(stepper(pg, 'endKph', 'kph', live))]);
        if (startRow) { rows.replaceChild(sr, startRow); rows.replaceChild(er, endRow); }
        startRow = sr; endRow = er;
      };

      /* Start and end are literal: the belt always goes start → end. The
         toggle is a statement of intent, so choosing ONLY DOWN with 8 → 12 on
         the screen swaps them to 12 → 8 rather than leaving numbers that
         contradict the word. UP & DOWN climbs first, so it wants them the
         way ONLY UP does. Grade and speed are swapped independently: a
         session that speeds up on a falling grade is a real thing. */
      var seg = el('div', 'ss-seg');
      [['up', 'ONLY UP'], ['updown', 'UP &amp; DOWN'], ['down', 'ONLY DOWN']].forEach(function (o) {
        var b = el('button', o[0] === pg.shape ? 'ss-on' : '', o[1]);
        press(b, function () {
          [].forEach.call(seg.children, function (c) { c.classList.remove('ss-on'); });
          b.classList.add('ss-on');
          pg.shape = o[0];
          var down = pg.shape === 'down', t;
          if (down ? pg.startGrade < pg.endGrade : pg.startGrade > pg.endGrade) {
            t = pg.startGrade; pg.startGrade = pg.endGrade; pg.endGrade = t;
          }
          if (down ? pg.startKph < pg.endKph : pg.startKph > pg.endKph) {
            t = pg.startKph; pg.startKph = pg.endKph; pg.endKph = t;
          }
          drawEnds();
          live();
        });
        seg.appendChild(b);
      });
      row('MAIN', 'the progression', [cell(stepper(pg, 'sec', 'sec', live)), cell(seg, true)]);
      drawEnds();
    }
    period('COOL-DOWN', '', draft.cooldown);
    view.appendChild(rows);

    function doSave() {
      if (!has('saveSession')) { say(m, 'This console cannot save sessions yet.', true); return; }
      var id = '';
      try { id = bridge().saveSession(JSON.stringify(draft)); } catch (e) { id = ''; }
      if (typeof id !== 'string' || !id) {
        say(m, 'The console would not save this session. Check every time is between 10 s and 3 h.', true);
        return;
      }
      draft.id = id;
      selectedId = id;
      // Back to the list with it chosen: one more tap starts it, and the list
      // is where it is now proved to exist.
      showList();
    }

    var foot = el('div', 'ss-foot');
    if (draft.id) {
      foot.appendChild(press(el('button', 'ss-pill ss-big ss-warn', 'DELETE'), function () {
        closeKeyboard();
        ask('Delete “' + (draft.name || 'Untitled') + '”?',
            'It goes from this console for everyone. This cannot be undone.',
            'DELETE', function () {
              try { if (has('deleteSession')) bridge().deleteSession(draft.id); } catch (e) {}
              if (selectedId === draft.id) selectedId = '';
              showList();
            });
      }));
    }
    foot.appendChild(el('div', 'ss-gap'));
    foot.appendChild(m);
    foot.appendChild(press(el('button', 'ss-pill ss-big', 'SAVE'), function () {
      closeKeyboard();
      if (!draft.name) {
        // Straight into the keyboard; DONE carries on with the save.
        saveAfterName = true;
        name.classList.add('ss-ask');
        say(m, 'Name it, then DONE to save.');
        openKeyboard(name);
        return;
      }
      doSave();
    }));
    foot.appendChild(press(el('button', 'ss-pill ss-big ss-go', 'START'), function () {
      saveAfterName = false;
      closeKeyboard();
      start(draft, m);
    }));
    view.appendChild(foot);

    live();
  }

  /* ---- build ------------------------------------------------------------- */

  /* Inside a shadow root for the reason stride-settings.js gives at length:
     original.html styles bare `button` elements, and element selectors reach
     anything injected into the document. A shadow root is the boundary that
     stops it; `:host{all:initial}` stops inheritance. Without attachShadow it
     falls back to the light DOM — wrong-looking, but still a screen. */
  function build() {
    if (root) return;

    var host = el('div');
    host.id = 'stride-session';
    // Positioning on the host, inline, where no page rule can reach it.
    host.style.cssText = 'position:fixed;top:0;right:0;bottom:0;left:0;' +
                         'z-index:9100;display:none';
    document.body.appendChild(host);

    var mount = host;
    if (host.attachShadow) mount = host.attachShadow({ mode: 'open' });

    var style = document.createElement('style');
    style.textContent = paletteCss() + CSS;
    mount.appendChild(style);

    shadowHost = host;
    root = el('div', 'ss-root');
    view = el('div', 'ss-view');
    root.appendChild(view);
    modal = el('div', 'ss-modal');
    root.appendChild(modal);
    mount.appendChild(root);
  }

  function open(o) {
    opts = o || {};
    build();
    loadLimits();
    if (modal) modal.classList.remove('ss-on');
    showList();
    shadowHost.style.display = 'block';
  }

  /* Hides without onClose. Used by START, and by a host that is leaving the
     setup flow for its own reasons and does not want to be called back. */
  function hide() {
    if (!shadowHost) return;
    if (kb) { kb.classList.remove('ss-on'); kbTarget = null; }
    if (modal) modal.classList.remove('ss-on');
    shadowHost.style.display = 'none';
  }

  function isOpen() { return !!shadowHost && shadowHost.style.display !== 'none'; }

  global.StrideSession = { open: open, close: hide, isOpen: isOpen };
})(this);
