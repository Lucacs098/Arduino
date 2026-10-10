/*
 * Banco Fiches — interfaccia web per il banco e per i giocatori.
 * Nessuna libreria esterna (a parte qrcode.js per il QR): funziona offline sulla rete locale.
 */
(function () {
  'use strict';

  // ------------------------------------------------------------ utilità
  function lsGet(k) { try { return localStorage.getItem(k) || ''; } catch (e) { return ''; } }
  function lsSet(k, v) { try { if (v) localStorage.setItem(k, v); else localStorage.removeItem(k); } catch (e) { /* storage non disponibile */ } }
  function ssGet(k) { try { return sessionStorage.getItem(k) || ''; } catch (e) { return ''; } }
  function ssSet(k, v) { try { if (v) sessionStorage.setItem(k, v); else sessionStorage.removeItem(k); } catch (e) { /* storage non disponibile */ } }
  var $ = function (sel) { return document.querySelector(sel); };
  var ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return ESC[c]; }); }
  function fmt(n) { return Number(n || 0).toLocaleString('it-IT'); }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
  function short(v) {
    if (v >= 1e6 && v % 1e5 === 0) return (v / 1e6).toLocaleString('it-IT') + 'M';
    if (v >= 1000 && v % 100 === 0) return (v / 1000).toLocaleString('it-IT') + 'K';
    return String(v);
  }
  function initial(name) { var a = Array.from(String(name || '?').trim()); return a.length ? a[0] : '?'; }
  function color(id) { return 'hsl(' + ((id * 137) % 360) + ' 52% 40%)'; }
  function setHtml(el, html) {
    if (el && el._html !== html) { el.innerHTML = html; el._html = html; }
  }

  // ------------------------------------------------------------ stato
  var S = {
    s: null,            // ultimo stato ricevuto dal banco
    v: -1,
    token: lsGet('bf_token'),
    admin: ssGet('bf_admin'),
    tab: 'table',
    mode: '',
    online: true,
    busy: false,
    spectate: false,
    raiseOpen: false,
    raiseTo: 0,
    actFor: false,
    turnKey: '',
    wasMyTurn: false,
    winners: {},
    winnersKey: '',
    sheet: null,
    inviteUrl: '',
    dragging: false,
    sound: lsGet('bf_sound') !== '0'
  };

  // Link speciali: #a=TOKEN (banco, dall'app) e #t=TOKEN (rientro di un giocatore).
  (function readHash() {
    if (!location.hash) return;
    var h = new URLSearchParams(location.hash.slice(1));
    if (h.get('a')) { S.admin = h.get('a'); ssSet('bf_admin', S.admin); }
    if (h.get('t')) { S.token = h.get('t'); lsSet('bf_token', S.token); }
    try { history.replaceState(null, '', location.pathname + location.search); } catch (e) { /* ignora */ }
  })();

  function setToken(t) { S.token = t || ''; lsSet('bf_token', S.token); }

  function byId(id) {
    var ps = S.s ? S.s.players : [];
    for (var i = 0; i < ps.length; i++) if (ps[i].id === id) return ps[i];
    return null;
  }
  function nameOf(id) {
    var p = byId(id);
    if (p) return p.name;
    var d = S.s.departed.filter(function (x) { return x.id === id; })[0];
    return d ? d.name : '#' + id;
  }
  function me() { return S.s && S.s.me != null ? byId(S.s.me) : null; }
  function turnPlayer() { var h = S.s.hand; return h.stage === 'betting' ? byId(h.toAct) : null; }

  // ------------------------------------------------------------ rete
  function toast(msg, err) {
    var t = $('#toast');
    t.textContent = msg;
    t.className = 'show' + (err ? ' err' : '');
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { t.className = err ? 'err' : ''; }, err ? 3400 : 2200);
  }

  function api(path, params) {
    if (S.busy) return Promise.resolve(null);
    S.busy = true;
    var body = new URLSearchParams(params || {});
    if (S.token) body.set('t', S.token);
    if (S.admin) body.set('a', S.admin);
    return fetch('/api/' + path, { method: 'POST', body: body, cache: 'no-store' })
      .then(function (r) {
        return r.json().catch(function () { return { error: 'Risposta non valida (' + r.status + ')' }; });
      })
      .then(function (j) {
        S.busy = false;
        if (j.error) { toast(j.error, true); return null; }
        if (path === 'join' && j.token) setToken(j.token);
        if (j.state) applyState(j.state, S.token);
        return j;
      })
      .catch(function () {
        S.busy = false;
        toast('Banco non raggiungibile. Sei sulla stessa rete Wi-Fi?', true);
        return null;
      });
  }

  var pollCtl = null;
  var forceFresh = false;

  function setOnline(v) {
    if (S.online !== v) { S.online = v; render(); }
  }

  function pollLoop() {
    var reqToken = S.token;
    var since = forceFresh ? -1 : S.v;
    forceFresh = false;
    var ctl = window.AbortController ? new AbortController() : null;
    pollCtl = ctl;
    var timer = setTimeout(function () { if (ctl) ctl.abort(); }, 40000);
    var q = new URLSearchParams({ since: String(since) });
    if (S.token) q.set('t', S.token);
    if (S.admin) q.set('a', S.admin);
    fetch('/api/state?' + q.toString(), { cache: 'no-store', signal: ctl ? ctl.signal : undefined })
      .then(function (r) { if (!r.ok) throw new Error('http ' + r.status); return r.json(); })
      .then(function (s) {
        clearTimeout(timer);
        setOnline(true);
        applyState(s, reqToken);
        pollLoop();
      })
      .catch(function () {
        clearTimeout(timer);
        if (ctl && ctl._manual) { pollLoop(); return; }
        setOnline(false);
        setTimeout(pollLoop, 1500);
      });
  }

  // Al ritorno in primo piano il browser può aver congelato la connessione: ripartiamo subito.
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && pollCtl) { pollCtl._manual = true; forceFresh = true; pollCtl.abort(); }
  });

  function applyState(s, reqToken) {
    if (!s || s.v == null) return;
    if (reqToken !== S.token) return; // il token è cambiato durante la richiesta
    if (S.s && s.sid === S.s.sid && s.v < S.v) return; // risposta vecchia
    if (S.token && s.me == null && !S.busy) {
      setToken('');
      if (S.s && S.s.me != null) toast('Non sei più seduto al tavolo');
    }
    S.s = s;
    S.v = s.v;
    var h = s.hand;

    var key = h.n + ':' + h.stage + ':' + h.street + ':' + h.toAct + ':' + h.currentBet;
    if (key !== S.turnKey) { S.turnKey = key; S.raiseOpen = false; S.actFor = false; S.dragging = false; }

    if (h.stage === 'showdown') {
      var wk = h.n + ':' + JSON.stringify(h.pots);
      if (wk !== S.winnersKey) {
        S.winnersKey = wk;
        S.winners = {};
        h.pots.forEach(function (p, i) { if (p.eligible.length === 1) S.winners[i] = [p.eligible[0]]; });
      }
    }

    var m = me();
    var myTurn = !!(m && h.stage === 'betting' && h.toAct === m.id);
    if (myTurn && !S.wasMyTurn) notifyTurn();
    S.wasMyTurn = myTurn;
    document.title = myTurn ? '🔔 Tocca a te! · Banco Fiches' : s.cfg.tableName + ' · Banco Fiches';

    if (S.sheet && S.sheet.kind === 'player' && !byId(S.sheet.id)) S.sheet = null;
    render();
  }

  // ------------------------------------------------------------ avvisi turno
  var audioCtx = null;
  function unlockAudio() {
    try {
      audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
    } catch (e) { audioCtx = null; }
  }
  document.addEventListener('pointerdown', unlockAudio, { once: true });

  function beep() {
    if (!audioCtx) return;
    try {
      var t = audioCtx.currentTime;
      [[0, 784], [0.17, 1047]].forEach(function (n) {
        var o = audioCtx.createOscillator(), g = audioCtx.createGain();
        o.type = 'sine';
        o.frequency.value = n[1];
        g.gain.setValueAtTime(0.0001, t + n[0]);
        g.gain.exponentialRampToValueAtTime(0.3, t + n[0] + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, t + n[0] + 0.16);
        o.connect(g);
        g.connect(audioCtx.destination);
        o.start(t + n[0]);
        o.stop(t + n[0] + 0.18);
      });
    } catch (e) { /* audio non disponibile */ }
  }

  function notifyTurn() {
    try { if (navigator.vibrate) navigator.vibrate([200, 100, 200]); } catch (e) { /* ignora */ }
    if (S.sound) beep();
  }

  // ------------------------------------------------------------ rendering
  function tabList() {
    var s = S.s;
    if (s.admin) return [['table', 'Tavolo'], ['players', 'Giocatori'], ['invite', 'Invita'], ['ledger', 'Bilancio'], ['settings', 'Opzioni']];
    if (me()) return [['table', 'Tavolo'], ['invite', 'Invita'], ['ledger', 'Bilancio'], ['me', 'Profilo']];
    return [['table', 'Tavolo'], ['invite', 'Invita'], ['ledger', 'Bilancio']];
  }

  function render() {
    var s = S.s;
    if (!s) return;
    var app = $('#app');
    var mode = (!me() && !s.admin && !S.spectate) ? 'join' : 'main';
    if (mode !== S.mode) {
      S.mode = mode;
      app.innerHTML = mode === 'join' ? joinSkeleton() : '<header id="hdr"></header><main id="view"></main><div id="bar"></div>';
    }
    if (mode === 'join') {
      setHtml($('#joinTable'), esc(s.cfg.tableName));
      setHtml($('#joinDyn'), joinDynHtml());
    } else {
      var tabs = tabList();
      if (!tabs.some(function (t) { return t[0] === S.tab; })) S.tab = 'table';
      setHtml($('#hdr'), hdrHtml(tabs));
      setHtml($('#view'), viewHtml());
      if (!S.dragging) setHtml($('#bar'), barHtml());
    }
    renderSheet();
  }

  // --- ingresso ---
  function joinSkeleton() {
    return '<div class="join"><div class="join-card">' +
      '<div class="big-chip"></div><h1>Banco Fiches</h1><div class="tname" id="joinTable"></div>' +
      '<form data-form="join" autocomplete="off">' +
      '<input class="field" name="name" maxlength="20" placeholder="Il tuo nome" autocomplete="nickname" enterkeyhint="go" required>' +
      '<button class="btn primary big">Siediti al tavolo</button></form>' +
      '<div id="joinDyn"></div>' +
      '<button class="linkbtn" data-act="spectate">Guarda soltanto il tavolo</button>' +
      '</div></div>';
  }

  function joinDynHtml() {
    var s = S.s, html = '';
    if (s.cfg.locked) html += '<p class="warn" style="margin-top:14px">Il tavolo è chiuso a nuovi giocatori: chiedi al banco di riaprirlo.</p>';
    if (s.players.length) {
      html += '<div class="who muted">Al tavolo: ' + s.players.map(function (p) { return '<span>' + esc(p.name) + '</span>'; }).join('') + '</div>';
    } else {
      html += '<div class="who muted">Sei il primo ad arrivare!</div>';
    }
    return html;
  }

  // --- intestazione ---
  function hdrHtml(tabs) {
    var s = S.s, h = s.hand, sub;
    if (h.stage === 'idle') sub = h.n ? 'Mano #' + h.n + ' conclusa' : 'In attesa della prima mano';
    else sub = 'Mano #' + h.n + ' · ' + (h.stage === 'showdown' ? 'Showdown' : h.streetName);
    if (s.cfg.autoBlinds && s.cfg.bigBlind) sub += ' · Bui ' + fmt(s.cfg.smallBlind) + '/' + fmt(s.cfg.bigBlind);
    return (S.online ? '' : '<div class="offline-banner">Connessione al banco persa… riprovo</div>') +
      '<div class="hdr-row"><div class="logo"></div><div class="hdr-title"><div class="t">' + esc(s.cfg.tableName) + '</div>' +
      '<div class="s">' + esc(sub) + '</div></div>' +
      (s.admin && s.undo ? '<button class="undo-btn" data-act="undo">↶ Annulla</button>' : '') +
      '<span class="conn' + (S.online ? '' : ' off') + '" title="' + (S.online ? 'Connesso' : 'Non connesso') + '"></span></div>' +
      '<nav class="tabs">' + tabs.map(function (t) {
        return '<button class="tab' + (S.tab === t[0] ? ' on' : '') + '" data-act="tab" data-tab="' + t[0] + '">' + t[1] + '</button>';
      }).join('') + '</nav>';
  }

  function viewHtml() {
    switch (S.tab) {
      case 'players': return viewPlayers();
      case 'invite': return viewInvite();
      case 'ledger': return viewLedger();
      case 'settings': return viewSettings();
      case 'me': return viewMe();
      default: return viewTable();
    }
  }

  // --- tavolo ---
  function nextDealer() {
    var ps = S.s.players, h = S.s.hand;
    var el = function (p) { return !p.sittingOut && p.stack > 0; };
    var cur = -1;
    ps.forEach(function (p, i) { if (p.id === h.dealer) cur = i; });
    if (h.dealerFixed && cur >= 0 && el(ps[cur])) return ps[cur];
    for (var k = 1; k <= ps.length; k++) {
      var p = ps[((cur + k) % ps.length + ps.length) % ps.length];
      if (el(p)) return p;
    }
    return null;
  }

  function potHtml() {
    var s = S.s, h = s.hand;
    if (h.stage === 'idle') {
      return (h.lastResult ? '<div class="result">' + esc(h.lastResult) + '</div>' : '') +
        '<div class="pot idle"><div class="lbl">' + (h.n ? 'Mano #' + h.n + ' conclusa' : 'Benvenuti al tavolo') + '</div>' +
        '<div class="amt">' + (h.n ? 'In attesa della prossima mano' : 'Pronti per la prima mano') + '</div>' +
        (s.admin ? '' : '<div class="sub">Il banco darà il via alla mano</div>') + '</div>';
    }
    var sub = h.stage === 'showdown' ? 'Mostrate le carte!' :
      (h.currentBet > 0 ? 'Puntata da pareggiare: <b class="num">' + fmt(h.currentBet) + '</b>' : 'Nessuna puntata in questo giro');
    var side = '';
    if (h.stage === 'showdown' && h.pots.length > 1) {
      side = '<div class="side">' + h.pots.map(function (p, i) {
        return '<div>' + (i ? 'Laterale ' + i : 'Principale') + ': <b class="num">' + fmt(p.amount) + '</b> · ' +
          p.eligible.map(function (id) { return esc(nameOf(id)); }).join(', ') + '</div>';
      }).join('') + '</div>';
    }
    return '<div class="pot"><div class="lbl">Piatto · mano #' + h.n + '</div>' +
      '<div class="amt num"><span class="stack-ico"></span>' + fmt(h.potTotal) + '</div>' +
      '<div class="sub">' + sub + '</div>' +
      '<div class="street">' + (h.stage === 'showdown' ? 'Showdown' : esc(h.streetName)) + '</div>' + side + '</div>';
  }

  function hostPanelHtml() {
    var s = S.s, h = s.hand, html = '';
    if (h.stage === 'idle') {
      var eligible = s.players.filter(function (p) { return !p.sittingOut && p.stack > 0; }).length;
      var nd = nextDealer();
      var blinds = s.cfg.autoBlinds && s.cfg.bigBlind > 0
        ? 'bui ' + fmt(s.cfg.smallBlind) + '/' + fmt(s.cfg.bigBlind) + (s.cfg.ante ? ' · ante ' + fmt(s.cfg.ante) : '')
        : 'bui manuali';
      html = '<div class="card host-panel"><button class="btn primary big" data-act="start"' + (eligible < 2 ? ' disabled' : '') + '>▶ Nuova mano</button>' +
        '<div class="info">' + (eligible < 2
          ? (s.players.length < 2 ? 'Invita almeno 2 giocatori dalla scheda “Invita”' : 'Servono almeno 2 giocatori con fiches e non in pausa')
          : 'Mazziere: <b>' + esc(nd ? nd.name : '-') + '</b> · ' + blinds) + '</div></div>';
    } else if (h.stage === 'betting') {
      var t = byId(h.toAct), isMe = t && t.id === s.me;
      html = '<div class="card host-panel"><div class="turn-line"><div class="grow">Turno di <b>' + esc(t ? t.name : '?') + '</b></div>' +
        (t && !isMe ? '<button class="btn small" data-act="actFor">Agisci per ' + esc(t.name) + '</button>' : '') +
        '<button class="btn small ghost" data-act="cancelHand">Annulla mano</button></div></div>';
    } else {
      html = '<div class="card host-panel"><h3>Chi ha vinto?</h3>';
      h.pots.forEach(function (pot, i) {
        var sel = S.winners[i] || [];
        html += '<div class="pot-pick"><div class="pp-title">' + (i === 0 ? 'Piatto principale' : 'Piatto laterale ' + i) +
          ' · <span class="num">' + fmt(pot.amount) + '</span></div><div class="picks">' +
          pot.eligible.map(function (id) {
            return '<button class="pick' + (sel.indexOf(id) >= 0 ? ' on' : '') + '" data-act="pick" data-pot="' + i + '" data-id="' + id + '">' + esc(nameOf(id)) + '</button>';
          }).join('') + '</div></div>';
      });
      html += '<div class="hint">Tocca chi ha la mano migliore. Scegline più di uno per dividere il piatto.</div>' +
        '<div class="row"><button class="btn ghost" data-act="cancelHand">Annulla mano</button>' +
        '<button class="btn primary" data-act="award">Assegna vincita</button></div></div>';
    }
    if (!me() && h.stage === 'idle') {
      html += '<div class="card turn-line"><div class="grow">Giochi anche tu da questo telefono?</div>' +
        '<button class="btn small primary" data-act="sitHost">Siediti al tavolo</button></div>';
    }
    return html;
  }

  function seatHtml(p) {
    var s = S.s, h = s.hand, meId = s.me;
    var inPlay = h.stage !== 'idle';
    var turn = h.stage === 'betting' && h.toAct === p.id;
    var cls = 'seat';
    if (p.id === meId) cls += ' me';
    if (turn) cls += ' turn';
    if (inPlay && p.inHand && p.folded) cls += ' folded';
    if ((inPlay && !p.inHand) || (!inPlay && (p.sittingOut || p.stack === 0))) cls += ' out';
    var clickable = s.admin || p.id === meId;
    if (clickable) cls += ' click';

    var badge = '';
    if (p.id === h.dealer) badge = '<span class="badge d">D</span>';
    else if (inPlay && p.id === h.sb) badge = '<span class="badge sb">SB</span>';
    else if (inPlay && p.id === h.bb) badge = '<span class="badge bb">BB</span>';

    var sub = '', subCls = '';
    if (inPlay && p.inHand) {
      if (p.folded) sub = 'Fold';
      else if (p.allIn) { sub = /^All-in/.test(p.lastAction) ? p.lastAction : 'All-in'; subCls = 'allin'; }
      else if (turn) { sub = 'Sta decidendo…'; subCls = 'hot'; }
      else sub = p.lastAction;
    } else if (p.sittingOut) sub = 'In pausa';
    else if (p.stack === 0) sub = 'Senza fiches';
    else if (inPlay) sub = 'Entra alla prossima mano';
    if (p.pending > 0) sub += (sub ? ' · ' : '') + '+' + fmt(p.pending) + ' alla prossima mano';

    return '<div class="' + cls + '"' + (clickable ? ' role="button" tabindex="0" data-act="seat" data-id="' + p.id + '"' : '') + '>' +
      '<div class="avatar" style="background:' + color(p.id) + '">' + esc(initial(p.name)) + badge + '</div>' +
      '<div style="min-width:0"><div class="seat-name"><span class="dot' + (p.online ? ' on' : '') + '"></span>' +
      '<span class="n">' + esc(p.name) + '</span>' + (p.id === meId ? '<span class="tag">Tu</span>' : '') + '</div>' +
      '<div class="seat-sub ' + subCls + '">' + (sub ? esc(sub) : '&nbsp;') + '</div></div>' +
      '<div class="seat-right"><div class="seat-stack num">' + fmt(p.stack) + '</div>' +
      (p.bet > 0 ? '<div class="betchip num">' + fmt(p.bet) + '</div>' : '') + '</div></div>';
  }

  function logHtml(limit) {
    var log = S.s.log.slice(-limit).reverse();
    if (!log.length) return '';
    return '<div class="card"><div class="log-head"><h4>Ultime azioni</h4>' +
      '<button class="btn small ghost" data-act="openLog">Cronologia</button></div><ul class="log">' +
      log.map(function (e) { return '<li' + (/^Mano #\d+ —/.test(e.t) ? ' class="h"' : '') + '>' + esc(e.t) + '</li>'; }).join('') +
      '</ul></div>';
  }

  function viewTable() {
    var s = S.s, html = potHtml();
    if (s.admin) html += hostPanelHtml();
    else if (!me()) {
      html += '<div class="card turn-line"><div class="grow">Stai guardando il tavolo.</div>' +
        '<button class="btn small primary" data-act="joinNow">Siediti</button></div>';
    }
    html += s.players.length
      ? '<div class="seats">' + s.players.map(seatHtml).join('') + '</div>'
      : '<div class="card muted">Nessuno al tavolo. Fai scansionare il QR dalla scheda “Invita”.</div>';
    return html + logHtml(6);
  }

  // --- giocatori (banco) ---
  function viewPlayers() {
    var s = S.s;
    var rows = s.players.map(function (p) {
      return '<div class="prow" role="button" tabindex="0" data-act="seat" data-id="' + p.id + '">' +
        '<div class="avatar" style="background:' + color(p.id) + '">' + esc(initial(p.name)) + '</div>' +
        '<div style="min-width:0"><div class="seat-name"><span class="dot' + (p.online ? ' on' : '') + '"></span><span class="n">' + esc(p.name) + '</span>' +
        (p.sittingOut ? '<span class="tag">Pausa</span>' : '') + (p.id === s.hand.dealer ? '<span class="tag gold">D</span>' : '') + '</div>' +
        '<div class="meta">Fiches <b class="num">' + fmt(p.stack) + '</b> · buy-in ' + fmt(p.buyIn) + '</div></div>' +
        '<span class="chev">›</span></div>';
    }).join('');
    return '<div class="card"><h3>Giocatori al tavolo (' + s.players.length + ')</h3>' +
      (rows ? '<div class="plist">' + rows + '</div>' : '<p class="muted">Ancora nessuno.</p>') +
      '<div style="margin-top:12px"><button class="btn wide" data-act="addPlayer">＋ Aggiungi giocatore senza telefono</button></div></div>' +
      '<div class="card"><h4>Come si usa</h4><p class="muted" style="margin:0">Tocca un giocatore per ricaricare o ritirare fiches, correggere lo stack, ' +
      'spostarlo di posto (l\'ordine è quello del giro), farlo mazziere, metterlo in pausa o mostrargli il QR per rientrare. ' +
      'Per chi gioca senza telefono usa “Agisci per…” quando tocca a lui.</p></div>';
  }

  // --- invito ---
  function baseUrls() {
    var list = [];
    var local = /^(127\.|localhost$|\[::1\]$)/.test(location.hostname);
    if (!local) list.push(location.origin + '/');
    (S.s.urls || []).forEach(function (u) { if (list.indexOf(u) < 0) list.push(u); });
    return list;
  }

  function qrSvg(text) {
    try {
      var qr = qrcode(0, 'M');
      qr.addData(text);
      qr.make();
      return qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
    } catch (e) {
      return '<p style="color:#000">QR non disponibile</p>';
    }
  }

  function viewInvite() {
    var urls = baseUrls();
    if (!urls.length) {
      return '<div class="card"><h3>Invita al tavolo</h3><p class="warn">Nessuna rete trovata. Collega questo telefono a un Wi-Fi ' +
        'oppure attiva l\'hotspot, poi torna qui.</p></div>' + tipsHtml();
    }
    var sel = urls.indexOf(S.inviteUrl) >= 0 ? S.inviteUrl : urls[0];
    return '<div class="card" style="text-align:center"><h3>Invita al tavolo</h3>' +
      '<p class="muted" style="margin:0 0 6px">Inquadra il QR con la fotocamera: il tavolo si apre nel browser, senza installare nulla.</p>' +
      '<div class="qr">' + qrSvg(sel) + '</div><div class="url">' + esc(sel.replace(/\/$/, '')) + '</div>' +
      '<div class="row"><button class="btn" data-act="copy" data-u="' + esc(sel) + '">Copia link</button>' +
      '<button class="btn primary" data-act="share" data-u="' + esc(sel) + '">Condividi</button></div>' +
      (urls.length > 1 ? '<div class="pills">' + urls.map(function (u) {
        return '<button class="' + (u === sel ? 'on' : '') + '" data-act="inviteUrl" data-u="' + esc(u) + '">' + esc(u.replace(/^http:\/\/|\/$/g, '')) + '</button>';
      }).join('') + '</div><p class="muted" style="font-size:13px;margin:8px 0 0">Più reti trovate: se un indirizzo non va, prova l\'altro.</p>' : '') +
      '</div>' + tipsHtml();
  }

  function tipsHtml() {
    return '<div class="card"><h4>Come collegarsi</h4><ol class="steps">' +
      '<li>Tutti sulla <b>stessa rete Wi-Fi</b> del banco, oppure attiva l\'<b>hotspot</b> sul telefono del banco e fai collegare gli altri.</li>' +
      '<li>Scansiona il QR o apri il link nel browser (Chrome, Safari…).</li>' +
      '<li>Scrivi il tuo nome e siediti: il banco ti dà le fiches iniziali.</li></ol>' +
      '<p class="muted" style="font-size:13px;margin:10px 0 0">Il link inizia con http:// (non https). Se non si apre, disattiva i dati mobili ' +
      'sul telefono dell\'ospite o controlla che il Wi-Fi non sia una rete “ospiti” isolata.</p></div>';
  }

  // --- bilancio ---
  function money(n) {
    var c = S.s.cfg;
    return (n * c.chipValue).toLocaleString('it-IT', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + (c.currency ? ' ' + c.currency : '');
  }

  function signed(n, m) {
    var txt = m ? money(n) : fmt(n);
    return '<span class="' + (n > 0 ? 'pos' : n < 0 ? 'neg' : '') + '">' + (n > 0 ? '+' : '') + txt + '</span>';
  }

  function ledgerRows() {
    var s = S.s;
    var rows = s.players.map(function (p) {
      return { name: p.name, buyIn: p.buyIn, chips: p.stack + p.pending + p.contributed + p.cashOut, left: false };
    }).concat(s.departed.map(function (p) {
      return { name: p.name, buyIn: p.buyIn, chips: p.cashOut, left: true };
    }));
    rows.forEach(function (r) { r.net = r.chips - r.buyIn; });
    return rows;
  }

  function settle(rows) {
    var cred = rows.filter(function (r) { return r.net > 0; }).map(function (r) { return { n: r.name, v: r.net }; });
    var debt = rows.filter(function (r) { return r.net < 0; }).map(function (r) { return { n: r.name, v: -r.net }; });
    cred.sort(function (a, b) { return b.v - a.v; });
    debt.sort(function (a, b) { return b.v - a.v; });
    var out = [], i = 0, j = 0;
    while (i < debt.length && j < cred.length) {
      var x = Math.min(debt[i].v, cred[j].v);
      out.push({ from: debt[i].n, to: cred[j].n, v: x });
      debt[i].v -= x; cred[j].v -= x;
      if (!debt[i].v) i++;
      if (!cred[j].v) j++;
    }
    return out;
  }

  function viewLedger() {
    var s = S.s, useMoney = s.cfg.chipValue > 0;
    var rows = ledgerRows();
    if (!rows.length) return '<div class="card"><h3>Bilancio</h3><p class="muted">Nessun giocatore ancora.</p></div>';
    var tb = 0, tc = 0, tn = 0;
    var body = rows.map(function (r) {
      tb += r.buyIn; tc += r.chips; tn += r.net;
      return '<tr><td>' + esc(r.name) + (r.left ? ' <span class="muted">(uscito)</span>' : '') + '</td>' +
        '<td>' + fmt(r.buyIn) + '</td><td>' + fmt(r.chips) + '</td><td>' + signed(r.net) + '</td>' +
        (useMoney ? '<td>' + signed(r.net, true) + '</td>' : '') + '</tr>';
    }).join('');
    var html = '<div class="card"><h3>Bilancio</h3><table class="ledger"><thead><tr><th>Giocatore</th><th>Buy-in</th><th>Fiches</th><th>Netto</th>' +
      (useMoney ? '<th>' + esc(s.cfg.currency || 'Valore') + '</th>' : '') + '</tr></thead><tbody>' + body +
      '<tr class="tot"><td>Totale</td><td>' + fmt(tb) + '</td><td>' + fmt(tc) + '</td><td>' + signed(tn) + '</td>' +
      (useMoney ? '<td>' + signed(tn, true) + '</td>' : '') + '</tr></tbody></table>' +
      '<p class="muted" style="font-size:13px;margin:10px 0 0">Buy-in = fiches ricevute dal banco. Fiches = fiches attuali (piatto in corso compreso) più quelle già riconsegnate.</p>' +
      (tn !== 0 ? '<p class="warn" style="margin-top:10px">Il totale non torna di ' + fmt(Math.abs(tn)) + ' fiches: c\'è stata una correzione manuale degli stack.</p>' : '') +
      '</div>';
    var tr = settle(rows);
    if (tr.length) {
      html += '<div class="card"><h4>Chi paga chi</h4><ul class="settle">' + tr.map(function (t) {
        return '<li><span>' + esc(t.from) + ' → <b>' + esc(t.to) + '</b></span><b class="num">' + (useMoney ? money(t.v) : fmt(t.v) + ' fiches') + '</b></li>';
      }).join('') + '</ul>' + (useMoney ? '' : '<p class="muted" style="font-size:13px;margin:10px 0 0">Imposta il valore di una fiche nelle opzioni del banco per vedere gli importi in denaro.</p>') + '</div>';
    }
    return html;
  }

  // --- opzioni (banco) ---
  function viewSettings() {
    var c = S.s.cfg;
    var chk = function (b) { return b ? ' checked' : ''; };
    var num = function (name, label, v) {
      return '<label>' + label + '<input class="field" type="number" inputmode="numeric" min="0" step="1" name="' + name + '" value="' + v + '"></label>';
    };
    return '<div class="card"><h3>Tavolo</h3><form class="form" data-form="settings">' +
      '<label>Nome del tavolo<input class="field" name="tableName" maxlength="40" value="' + esc(c.tableName) + '"></label>' +
      '<div class="grid2">' + num('startingStack', 'Stack iniziale', c.startingStack) + num('ante', 'Ante', c.ante) + '</div>' +
      '<div class="grid2">' + num('smallBlind', 'Small blind', c.smallBlind) + num('bigBlind', 'Big blind', c.bigBlind) + '</div>' +
      '<label class="switch"><input type="checkbox" name="autoBlinds"' + chk(c.autoBlinds) + '> Bui automatici a ogni mano (il mazziere gira da solo)</label>' +
      '<label>Tagli delle fiches (pulsanti rapidi)<input class="field" name="chips" value="' + esc(c.chips.join(', ')) + '"></label>' +
      '<div class="grid2"><label>Valore di 1 fiche<input class="field" name="chipValue" inputmode="decimal" value="' + (c.chipValue || '') + '" placeholder="es. 0,01"></label>' +
      '<label>Valuta<input class="field" name="currency" maxlength="5" value="' + esc(c.currency) + '"></label></div>' +
      '<label class="switch"><input type="checkbox" name="locked"' + chk(c.locked) + '> Tavolo chiuso a nuovi giocatori</label>' +
      '<button class="btn primary big">Salva</button></form></div>' +
      '<div class="card"><h4>Livelli</h4><div class="row"><button class="btn" data-act="doubleBlinds">Raddoppia i bui (' +
      fmt(c.smallBlind * 2) + '/' + fmt(c.bigBlind * 2) + ')</button></div></div>' +
      '<div class="card"><h4>Questo telefono</h4><div class="row">' +
      '<button class="btn" data-act="sound">' + (S.sound ? '🔔 Suoni attivi' : '🔕 Suoni spenti') + '</button>' +
      (me() ? '' : '<button class="btn primary" data-act="sitHost">Siediti al tavolo</button>') + '</div></div>' +
      '<div class="card"><h4>Partita</h4><div class="stack-gap">' +
      '<button class="btn wide" data-act="resetKeep">Nuova partita con gli stessi giocatori</button>' +
      '<button class="btn wide danger" data-act="resetAll">Svuota il tavolo</button>' +
      (window.BancoFiches ? '<button class="btn wide danger" data-act="stopServer">Spegni il banco</button>' : '') +
      '</div></div>';
  }

  // --- profilo (giocatore) ---
  function viewMe() {
    var m = me();
    if (!m) return '';
    return '<div class="card"><div class="sheet-head" style="margin:0 0 12px"><div class="avatar" style="background:' + color(m.id) + '">' + esc(initial(m.name)) + '</div>' +
      '<div><h3 style="margin:0">' + esc(m.name) + '</h3><div class="muted">Fiches ' + fmt(m.stack) + ' · buy-in ' + fmt(m.buyIn) + '</div></div></div>' +
      '<div class="grid-actions">' +
      '<button class="btn" data-act="selfRename">✎ Cambia nome</button>' +
      '<button class="btn" data-act="selfPause">' + (m.sittingOut ? 'Torna in gioco' : 'Salta le prossime mani') + '</button>' +
      '<button class="btn" data-act="sound">' + (S.sound ? '🔔 Suoni attivi' : '🔕 Suoni spenti') + '</button>' +
      '<button class="btn danger" data-act="selfLeave">Lascia il tavolo</button></div></div>' +
      '<div class="card"><h4>Come funziona</h4><ol class="steps">' +
      '<li>Le carte sono vere: qui ci sono solo le fiches.</li>' +
      '<li>Quando tocca a te il telefono vibra e compaiono i pulsanti Fold, Check/Chiama e Punta/Rilancia.</li>' +
      '<li>Per rilanciare tocca le fiches per aggiungere l\'importo, poi conferma.</li>' +
      '<li>Alla fine della mano il banco assegna il piatto a chi vince.</li></ol></div>';
  }

  // --- barra azioni ---
  var CHIP_COLORS = [['#f2f2f2', '#2f6fd6', 1], ['#d63a3a'], ['#2e9d5b'], ['#202020'], ['#2f6fd6'], ['#8e44ad'], ['#e67e22'], ['#e0b52d', '#7a5a00', 1]];

  function chipsHtml() {
    return S.s.cfg.chips.map(function (v, i) {
      var c = CHIP_COLORS[i % CHIP_COLORS.length];
      return '<button class="chip' + (c[2] ? ' light' : '') + '" style="--c:' + c[0] + (c[1] ? ';--s:' + c[1] : '') + '" data-act="addChip" data-v="' + v + '">+' + short(v) + '</button>';
    }).join('');
  }

  function addLabel(p, to, o) {
    return to >= o.maxTo ? 'all-in' : '(metti ' + fmt(to - p.bet) + ')';
  }

  function barHtml() {
    var s = S.s, h = s.hand, m = me(), t = turnPlayer();
    var myTurn = m && t && t.id === m.id;
    var forOther = s.admin && S.actFor && t && !myTurn;
    if ((myTurn || forOther) && h.options) return actionPanel(t, !myTurn);
    if (!m || S.tab !== 'table') return '';
    var status;
    if (h.stage === 'betting') status = !m.inHand ? 'Fuori da questa mano' : m.folded ? 'Hai passato' : (t ? 'Tocca a <b>' + esc(t.name) + '</b>' : '');
    else if (h.stage === 'showdown') status = 'Showdown';
    else status = m.sittingOut ? 'In pausa' : (m.stack > 0 ? 'In attesa della mano' : 'Chiedi una ricarica al banco');
    return '<div class="bar-inner"><div class="bar-info" style="padding-bottom:2px"><span>Le tue fiches <b class="num">' + fmt(m.stack) + '</b></span>' +
      (m.bet ? '<span>· puntata <b class="num">' + fmt(m.bet) + '</b></span>' : '') + '<span class="grow"></span><span>' + status + '</span></div></div>';
  }

  function actionPanel(p, forOther) {
    var o = S.s.hand.options;
    var head = '<span class="pulse"></span><span class="who">' + (forOther ? 'Per conto di ' + esc(p.name) : 'Tocca a te!') + '</span>' +
      '<span class="grow"></span><span>Fiches <b class="num">' + fmt(p.stack) + '</b></span>' +
      (forOther ? '<button class="bar-close" data-act="actForClose" aria-label="Chiudi">×</button>' : '');
    if (S.raiseOpen && o.canRaise) {
      var to = clamp(S.raiseTo, o.minTo, o.maxTo);
      var verb = o.isBet ? 'Punta' : 'Rilancia a';
      return '<div class="bar-inner turn"><div class="bar-info">' + head + '</div><div class="raise">' +
        '<div class="raise-head"><span>' + verb + '</span><b id="rv" class="num">' + fmt(to) + '</b><small id="ra">' + addLabel(p, to, o) + '</small></div>' +
        '<input id="rr" type="range" min="' + o.minTo + '" max="' + o.maxTo + '" step="1" value="' + to + '" data-input="raise" aria-label="Importo">' +
        '<div class="chips-row">' + chipsHtml() + '<button class="chip reset" data-act="raiseReset" aria-label="Riparti dal minimo">↺</button></div>' +
        '<div class="presets"><button data-act="preset" data-p="min">Minimo</button><button data-act="preset" data-p="half">½ piatto</button>' +
        '<button data-act="preset" data-p="pot">Piatto</button><button data-act="preset" data-p="max">All-in</button></div></div>' +
        '<div class="act-row"><button class="act cancel" data-act="raiseClose">Indietro</button>' +
        '<button class="act confirm" id="rc" data-act="doRaise">' + verb + ' ' + fmt(to) + '</button></div></div>';
    }
    var callLbl = o.canCheck ? 'Check' : (o.toCall >= p.stack ? 'All-in' : 'Chiama');
    return '<div class="bar-inner turn"><div class="bar-info">' + head + '</div><div class="act-row">' +
      '<button class="act fold" data-act="do" data-a="fold">Fold</button>' +
      '<button class="act call" data-act="do" data-a="' + (o.canCheck ? 'check' : 'call') + '">' + callLbl +
      (o.canCheck ? '' : '<small class="num">' + fmt(o.toCall) + '</small>') + '</button>' +
      (o.canRaise ? '<button class="act raise" data-act="raiseOpen">' + (o.isBet ? 'Punta' : 'Rilancia') + '<small class="num">min ' + fmt(o.minTo) + '</small></button>' : '') +
      '</div></div>';
  }

  function updateRaiseLabels() {
    var o = S.s.hand.options, p = turnPlayer();
    if (!o || !p) return;
    var to = clamp(S.raiseTo, o.minTo, o.maxTo);
    var verb = o.isBet ? 'Punta' : 'Rilancia a';
    if ($('#rv')) $('#rv').textContent = fmt(to);
    if ($('#ra')) $('#ra').textContent = addLabel(p, to, o);
    if ($('#rc')) $('#rc').textContent = verb + ' ' + fmt(to);
  }

  // --- fogli modali ---
  function renderSheet() {
    var root = $('#sheet');
    var html = S.sheet ? sheetHtml(S.sheet) : '';
    var wasOpen = root.classList.contains('open');
    setHtml(root, html ? '<div class="sheet-bg" data-act="closeSheet"></div><div class="sheet" role="dialog" aria-modal="true">' + html + '</div>' : '');
    root.classList.toggle('open', !!html);
    if (html && !wasOpen) {
      var inp = root.querySelector('input.field');
      if (inp) setTimeout(function () { inp.focus(); if (inp.select) inp.select(); }, 60);
    }
  }

  function sheetHead(title, avatarP) {
    return '<div class="sheet-head">' + (avatarP ? '<div class="avatar" style="background:' + color(avatarP.id) + '">' + esc(initial(avatarP.name)) + '</div>' : '') +
      '<div style="min-width:0"><h3>' + esc(title) + '</h3>' + (avatarP ? '<div class="muted" style="font-size:14px">Fiches ' + fmt(avatarP.stack) +
      ' · buy-in ' + fmt(avatarP.buyIn) + (avatarP.cashOut ? ' · ritirate ' + fmt(avatarP.cashOut) : '') + '</div>' : '') + '</div>' +
      '<button class="x" data-act="closeSheet" aria-label="Chiudi">×</button></div>';
  }

  function sheetHtml(sh) {
    var s = S.s;
    if (sh.kind === 'player') {
      var p = byId(sh.id);
      if (!p) return '';
      if (!s.admin) {
        return sheetHead(p.name, p) + '<div class="grid-actions">' +
          '<button class="btn" data-act="selfRename">✎ Cambia nome</button>' +
          '<button class="btn" data-act="selfPause">' + (p.sittingOut ? 'Torna in gioco' : 'Salta le mani') + '</button>' +
          '<button class="btn danger" data-act="selfLeave">Lascia il tavolo</button></div>';
      }
      var b = function (k, label, cls) { return '<button class="btn' + (cls ? ' ' + cls : '') + '" data-act="pAct" data-k="' + k + '">' + label + '</button>'; };
      return sheetHead(p.name, p) + '<div class="grid-actions">' +
        b('rebuy', '＋ Ricarica fiches') + b('cashout', '－ Ritira fiches') +
        b('fix', '✎ Correggi stack') + b('dealer', 'Ⓓ Fai mazziere') +
        b('up', '↑ Sposta su') + b('down', '↓ Sposta giù') +
        b('pause', p.sittingOut ? 'Rientra in gioco' : 'Metti in pausa') + b('link', '🔗 QR per rientrare') +
        b('rename', '✎ Rinomina') + b('remove', p.id === s.me ? '✕ Lascia il tavolo' : '✕ Togli dal tavolo', 'danger') +
        '</div>';
    }
    if (sh.kind === 'link') {
      var lp = byId(sh.id), base = baseUrls()[0];
      if (!lp) return '';
      if (!base) return sheetHead('QR di ' + lp.name) + '<p class="warn">Nessuna rete trovata.</p>';
      var url = base + '#t=' + sh.token;
      return sheetHead('QR di ' + lp.name) +
        '<p class="muted" style="margin-top:0">Fai inquadrare questo QR a <b>' + esc(lp.name) + '</b>: rientra al suo posto con le sue fiches ' +
        '(utile se ha chiuso il browser o cambiato telefono). È personale: non mostrarlo agli altri.</p>' +
        '<div class="qr">' + qrSvg(url) + '</div><button class="btn wide" data-act="backToPlayer">Indietro</button>';
    }
    if (sh.kind === 'log') {
      return sheetHead('Cronologia') + '<ul class="log">' + s.log.slice().reverse().map(function (e) {
        return '<li' + (/^Mano #\d+ —/.test(e.t) ? ' class="h"' : '') + '>' + esc(e.t) + '</li>';
      }).join('') + '</ul>';
    }
    if (sh.kind === 'confirm') {
      return sheetHead(sh.title) + '<p style="margin-top:0">' + esc(sh.text) + '</p><div class="row">' +
        '<button class="btn ghost" data-act="closeSheet">Annulla</button>' +
        '<button class="btn ' + (sh.danger ? 'danger' : 'primary') + '" data-act="confirmOk">' + esc(sh.ok) + '</button></div>';
    }
    if (sh.kind === 'amount' || sh.kind === 'text') {
      var input = sh.kind === 'amount'
        ? '<input class="field" name="v" type="number" inputmode="numeric" min="0" step="1" value="' + esc(sh.value) + '">'
        : '<input class="field" name="v" maxlength="20" value="' + esc(sh.value || '') + '" placeholder="Nome">';
      return sheetHead(sh.title) + '<form class="form" data-form="sheet">' +
        '<label>' + esc(sh.label || '') + input + '</label>' +
        (sh.quick && sh.quick.length ? '<div class="quick">' + sh.quick.map(function (q) {
          return '<button type="button" data-act="quickSet" data-v="' + q + '">' + fmt(q) + '</button>';
        }).join('') + '</div>' : '') +
        (sh.hint ? '<div class="hint">' + esc(sh.hint) + '</div>' : '') +
        '<button class="btn primary big">' + esc(sh.ok) + '</button></form>';
    }
    return '';
  }

  function openSheet(sh) { S.sheet = sh; render(); }
  function closeSheet() { S.sheet = null; render(); }
  function confirmBox(o) { o.kind = 'confirm'; openSheet(o); }
  function amountBox(o) { o.kind = 'amount'; openSheet(o); }
  function textBox(o) { o.kind = 'text'; openSheet(o); }

  // ------------------------------------------------------------ azioni
  function doAct(action, amount) {
    var h = S.s.hand, m = me();
    var params = { action: action };
    if (amount != null) params.amount = amount;
    if (S.s.admin && (!m || h.toAct !== m.id)) params.pid = h.toAct;
    S.raiseOpen = false;
    S.actFor = false;
    return api('act', params);
  }

  function copyText(text) {
    var legacy = function () {
      var ta = document.createElement('textarea');
      ta.value = text;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
      document.body.removeChild(ta);
      toast(ok ? 'Link copiato' : 'Copia non riuscita: ' + text, !ok);
    };
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(function () { toast('Link copiato'); }, legacy);
    } else {
      legacy();
    }
  }

  function shareUrl(url) {
    if (window.BancoFiches && window.BancoFiches.share) {
      window.BancoFiches.share(url, S.s.cfg.tableName);
    } else if (navigator.share) {
      navigator.share({ title: 'Banco Fiches', text: 'Siediti al tavolo “' + S.s.cfg.tableName + '”', url: url }).catch(function () { /* annullato */ });
    } else {
      copyText(url);
    }
  }

  function presetTo(kind) {
    var h = S.s.hand, o = h.options, p = turnPlayer();
    var toCall = Math.max(0, h.currentBet - p.bet);
    var potAfterCall = h.potTotal + toCall;
    var to;
    if (kind === 'min') to = o.minTo;
    else if (kind === 'max') to = o.maxTo;
    else if (kind === 'half') to = h.currentBet + potAfterCall / 2;
    else to = h.currentBet + potAfterCall;
    if (kind === 'half' || kind === 'pot') {
      var step = S.s.cfg.chips[0] || 1;
      to = Math.round(to / step) * step;
    }
    return clamp(to, o.minTo, o.maxTo);
  }

  var ACTIONS = {
    tab: function (el) { S.tab = el.getAttribute('data-tab'); render(); window.scrollTo(0, 0); },
    spectate: function () { S.spectate = true; render(); },
    joinNow: function () { S.spectate = false; render(); },
    do: function (el) {
      var a = el.getAttribute('data-a'), o = S.s.hand.options;
      if (a === 'fold' && o && o.canCheck) {
        confirmBox({ title: 'Passare la mano?', text: 'Puoi fare check senza mettere fiches. Vuoi davvero passare?', ok: 'Sì, passo', danger: true, onOk: function () { doAct('fold'); } });
        return;
      }
      doAct(a);
    },
    raiseOpen: function () { S.raiseOpen = true; S.raiseTo = S.s.hand.options.minTo; render(); },
    raiseClose: function () { S.raiseOpen = false; render(); },
    raiseReset: function () { S.raiseTo = S.s.hand.options.minTo; render(); },
    addChip: function (el) {
      var o = S.s.hand.options;
      S.raiseTo = clamp(clamp(S.raiseTo, o.minTo, o.maxTo) + Number(el.getAttribute('data-v')), o.minTo, o.maxTo);
      render();
    },
    preset: function (el) { S.raiseTo = presetTo(el.getAttribute('data-p')); render(); },
    doRaise: function () { var o = S.s.hand.options; doAct('raise', clamp(S.raiseTo, o.minTo, o.maxTo)); },
    actFor: function () { S.actFor = true; render(); },
    actForClose: function () { S.actFor = false; S.raiseOpen = false; render(); },
    start: function () { api('start'); },
    cancelHand: function () {
      confirmBox({ title: 'Annullare la mano?', text: 'Tutte le fiches puntate in questa mano tornano ai giocatori.', ok: 'Annulla la mano', danger: true, onOk: function () { api('cancel'); } });
    },
    undo: function () {
      confirmBox({ title: 'Annullare l\'ultima operazione?', text: 'Verrà annullato: ' + S.s.undo, ok: '↶ Annulla', onOk: function () {
        api('undo').then(function (j) { if (j) toast('Operazione annullata'); });
      } });
    },
    pick: function (el) {
      var i = Number(el.getAttribute('data-pot')), id = Number(el.getAttribute('data-id'));
      var pots = S.s.hand.pots, cur = (S.winners[i] || []).slice();
      var wasEmpty = !cur.length, k = cur.indexOf(id);
      if (k >= 0) cur.splice(k, 1); else cur.push(id);
      S.winners[i] = cur;
      // Chi vince il piatto principale di solito vince anche i laterali a cui partecipa.
      if (wasEmpty && k < 0) {
        for (var j = i + 1; j < pots.length; j++) {
          if ((!S.winners[j] || !S.winners[j].length) && pots[j].eligible.indexOf(id) >= 0) S.winners[j] = [id];
        }
      }
      render();
    },
    award: function () {
      var pots = S.s.hand.pots;
      for (var i = 0; i < pots.length; i++) {
        if (!S.winners[i] || !S.winners[i].length) { toast('Scegli chi vince ' + (i ? 'il piatto laterale ' + i : 'il piatto principale'), true); return; }
      }
      api('award', { w: pots.map(function (p, i) { return S.winners[i].join(','); }).join(';') });
    },
    seat: function (el) { openSheet({ kind: 'player', id: Number(el.getAttribute('data-id')) }); },
    closeSheet: closeSheet,
    backToPlayer: function () { openSheet({ kind: 'player', id: S.sheet.id }); },
    confirmOk: function () { var f = S.sheet.onOk; closeSheet(); if (f) f(); },
    quickSet: function (el) { var inp = $('#sheet input[name=v]'); if (inp) { inp.value = el.getAttribute('data-v'); inp.focus(); } },
    openLog: function () { openSheet({ kind: 'log' }); },
    inviteUrl: function (el) { S.inviteUrl = el.getAttribute('data-u'); render(); },
    copy: function (el) { copyText(el.getAttribute('data-u')); },
    share: function (el) { shareUrl(el.getAttribute('data-u')); },
    sound: function () { S.sound = !S.sound; lsSet('bf_sound', S.sound ? '1' : '0'); unlockAudio(); if (S.sound) beep(); render(); },
    sitHost: function () {
      textBox({ title: 'Siediti al tavolo', label: 'Il tuo nome', ok: 'Siediti', onSubmit: function (v) { return api('join', { name: v }); } });
    },
    addPlayer: function () {
      textBox({ title: 'Giocatore senza telefono', label: 'Nome', ok: 'Aggiungi', hint: 'Quando tocca a lui usa “Agisci per…” nella scheda Tavolo.',
        onSubmit: function (v) { return api('add', { name: v }); } });
    },
    doubleBlinds: function () {
      var c = S.s.cfg;
      confirmBox({ title: 'Raddoppiare i bui?', text: 'Dalla prossima mano: ' + fmt(c.smallBlind * 2) + '/' + fmt(c.bigBlind * 2) + (c.ante ? ', ante ' + fmt(c.ante * 2) : '') + '.',
        ok: 'Raddoppia', onOk: function () {
          api('settings', { smallBlind: c.smallBlind * 2, bigBlind: c.bigBlind * 2, ante: c.ante * 2 }).then(function (j) { if (j) toast('Bui raddoppiati'); });
        } });
    },
    resetKeep: function () {
      confirmBox({ title: 'Nuova partita?', text: 'Tutti ripartono con ' + fmt(S.s.cfg.startingStack) + ' fiches. Bilancio e cronologia vengono azzerati.', ok: 'Ricomincia', danger: true,
        onOk: function () { api('reset', { keep: 1 }); } });
    },
    resetAll: function () {
      confirmBox({ title: 'Svuotare il tavolo?', text: 'Tutti i giocatori vengono tolti e la partita azzerata. Dovranno sedersi di nuovo.', ok: 'Svuota', danger: true,
        onOk: function () { api('reset', { keep: 0 }); } });
    },
    stopServer: function () {
      confirmBox({ title: 'Spegnere il banco?', text: 'Gli altri giocatori verranno disconnessi. La partita resta salvata e riprende alla prossima apertura.', ok: 'Spegni', danger: true,
        onOk: function () { if (window.BancoFiches) window.BancoFiches.stopServer(); } });
    },
    selfRename: function () {
      var m = me();
      textBox({ title: 'Cambia nome', label: 'Nome', value: m ? m.name : '', ok: 'Salva', onSubmit: function (v) { return api('rename', { name: v }); } });
    },
    selfPause: function () { var m = me(); if (m) api('sitout', { value: m.sittingOut ? 0 : 1 }); },
    selfLeave: function () {
      confirmBox({ title: 'Lasciare il tavolo?', text: 'Le tue fiches tornano al banco. Potrai sederti di nuovo come nuovo giocatore.', ok: 'Lascia', danger: true,
        onOk: function () { api('leave'); } });
    },
    pAct: function (el) {
      var k = el.getAttribute('data-k'), p = byId(S.sheet.id);
      if (!p) return;
      var back = { kind: 'player', id: p.id };
      var st = S.s.cfg.startingStack || 1000;
      switch (k) {
        case 'rebuy':
          amountBox({ title: 'Ricarica ' + p.name, label: 'Fiches da dare a ' + p.name, value: st, quick: [Math.round(st / 2), st, st * 2], ok: 'Ricarica', back: back,
            onSubmit: function (v) { return api('rebuy', { pid: p.id, amount: v }); } });
          break;
        case 'cashout':
          amountBox({ title: 'Ritira fiches', label: 'Fiches che ' + p.name + ' riconsegna', value: p.stack, quick: p.stack ? [p.stack] : [], ok: 'Ritira', back: back,
            hint: 'Ha ' + fmt(p.stack) + ' fiches. Il ritiro resta nel bilancio.', onSubmit: function (v) { return api('cashout', { pid: p.id, amount: v }); } });
          break;
        case 'fix':
          amountBox({ title: 'Correggi stack', label: 'Nuovo stack di ' + p.name, value: p.stack, ok: 'Salva', back: back, allowZero: true,
            hint: 'Solo per sistemare errori: il buy-in non cambia (per dare fiches usa “Ricarica”).', onSubmit: function (v) { return api('setstack', { pid: p.id, amount: v }); } });
          break;
        case 'dealer': api('dealer', { pid: p.id }); break;
        case 'up': api('move', { pid: p.id, dir: -1 }); break;
        case 'down': api('move', { pid: p.id, dir: 1 }); break;
        case 'pause': api('sitout', { pid: p.id, value: p.sittingOut ? 0 : 1 }); break;
        case 'link':
          api('link', { pid: p.id }).then(function (j) { if (j) openSheet({ kind: 'link', id: p.id, token: j.token }); });
          break;
        case 'rename':
          textBox({ title: 'Rinomina', label: 'Nome', value: p.name, ok: 'Salva', back: back, onSubmit: function (v) { return api('rename', { pid: p.id, name: v }); } });
          break;
        case 'remove':
          confirmBox({ title: p.id === S.s.me ? 'Lasciare il tavolo?' : 'Togliere ' + p.name + '?',
            text: 'Le sue ' + fmt(p.stack) + ' fiches tornano al banco e restano nel bilancio.', ok: 'Conferma', danger: true,
            onOk: function () { api('leave', { pid: p.id }); } });
          break;
      }
    }
  };

  document.addEventListener('click', function (e) {
    var el = e.target.closest('[data-act]');
    if (!el || el.disabled) return;
    var fn = ACTIONS[el.getAttribute('data-act')];
    if (fn) { e.preventDefault(); fn(el, e); }
  });
  document.addEventListener('keydown', function (e) {
    if ((e.key === 'Enter' || e.key === ' ') && e.target.getAttribute && e.target.getAttribute('role') === 'button') {
      e.preventDefault();
      e.target.click();
    }
    if (e.key === 'Escape' && S.sheet) closeSheet();
  });

  document.addEventListener('input', function (e) {
    if (e.target.getAttribute('data-input') !== 'raise') return;
    var o = S.s.hand.options, step = S.s.cfg.chips[0] || 1;
    var v = Number(e.target.value);
    var to = v >= o.maxTo ? o.maxTo : Math.round(v / step) * step;
    S.raiseTo = clamp(to, o.minTo, o.maxTo);
    updateRaiseLabels();
  });
  document.addEventListener('pointerdown', function (e) { if (e.target.getAttribute && e.target.getAttribute('data-input') === 'raise') S.dragging = true; });
  var endDrag = function () { if (S.dragging) { S.dragging = false; render(); } };
  document.addEventListener('pointerup', endDrag);
  document.addEventListener('pointercancel', endDrag);
  document.addEventListener('change', function (e) { if (e.target.getAttribute('data-input') === 'raise') endDrag(); });

  document.addEventListener('submit', function (e) {
    var f = e.target, kind = f.getAttribute('data-form');
    if (!kind) return;
    e.preventDefault();
    if (kind === 'join') {
      var name = f.elements.name.value.trim();
      if (!name) return;
      api('join', { name: name }).then(function (j) { if (j) { S.tab = 'table'; render(); } });
    } else if (kind === 'settings') {
      var el = f.elements;
      api('settings', {
        tableName: el.tableName.value, startingStack: el.startingStack.value, ante: el.ante.value,
        smallBlind: el.smallBlind.value, bigBlind: el.bigBlind.value, autoBlinds: el.autoBlinds.checked ? '1' : '0',
        chips: el.chips.value, chipValue: el.chipValue.value || '0', currency: el.currency.value, locked: el.locked.checked ? '1' : '0'
      }).then(function (j) { if (j) toast('Impostazioni salvate'); });
    } else if (kind === 'sheet') {
      var sh = S.sheet, raw = f.elements.v.value;
      if (!sh) return;
      var val;
      if (sh.kind === 'amount') {
        val = parseInt(raw, 10);
        if (isNaN(val) || val < (sh.allowZero ? 0 : 1)) { toast('Scrivi un numero valido', true); return; }
      } else {
        val = raw.trim();
        if (!val) { toast('Scrivi un nome', true); return; }
      }
      Promise.resolve(sh.onSubmit(val)).then(function (j) {
        if (j && S.sheet === sh) { S.sheet = sh.back || null; render(); }
      });
    }
  });

  pollLoop();
})();
