'use strict';

const WEAK_PASSWORDS = ['123456', 'password', '12345678', 'qwerty', '111111', 'abc123', '123456789', 'sifre', '1234'];
const ACCENT_SWATCHES = ['#5b5bf0', '#ef4759', '#23a55a', '#e08a2c', '#7a4fe0', '#0ea5b7'];

const el = {
  backBtn: document.getElementById('backBtn'),
  closeBtn: document.getElementById('closeBtn'),
  headTitle: document.getElementById('headTitle'),
  body: document.getElementById('body')
};

(function applyAccent() {
  try {
    const s = JSON.parse(window.AndroidHost.getSettingsJson());
    if (s.accentColor) document.documentElement.style.setProperty('--accent', s.accentColor);
  } catch (e) {}
})();

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function isWeakPassword(pw) {
  if (!pw) return true;
  if (pw.length < 8) return true;
  if (WEAK_PASSWORDS.includes(pw.toLowerCase())) return true;
  return false;
}
function setSetting(key, value) { window.AndroidHost.setSetting(key, JSON.stringify(value)); }

function setHeader(title, showBack) {
  el.headTitle.textContent = title;
  el.backBtn.style.visibility = showBack ? 'visible' : 'hidden';
}

window.NexusOverlay = {
  open(name) { render(name || 'settings'); },
  onSummaryResult(text) {
    const box = document.getElementById('summaryResult');
    if (box) box.textContent = text;
  },
  onBreachResults(json) {
    const list = JSON.parse(json);
    const box = document.getElementById('breachResult');
    if (!box) return;
    if (!list.length) { box.innerHTML = '<div class="empty">Kayıtlı şifre yok</div>'; return; }
    box.innerHTML = list.map(r => {
      let status;
      if (r.breached === true) status = '<span class="pw-badge-weak">SIZINTIDA BULUNDU</span>';
      else if (r.breached === false) status = '<span style="color:#23a55a;font-size:11px;font-weight:700;">Temiz</span>';
      else status = '<span style="color:var(--text-dim);font-size:11px;">Kontrol edilemedi</span>';
      return `<div class="list-item"><div class="l-title">${escapeHtml(r.site)}</div><div class="l-sub">${status}</div></div>`;
    }).join('');
  },
  onGroupingResult(text) {
    const box = document.getElementById('groupResult');
    if (!box) return;
    if (text === 'NOKEY') { box.innerHTML = '<div class="empty">Önce Menü → AI Ayarları\'ndan Groq API anahtarı ekleyin</div>'; return; }
    try {
      const clean = text.trim().replace(/^```json/i, '').replace(/```$/, '');
      const data = JSON.parse(clean);
      box.innerHTML = (data.gruplar || []).map(g => `
        <div class="list-item">
          <div class="l-title">🧩 ${escapeHtml(g.isim)}</div>
          <div class="l-sub">${(g.sekme_id || []).length} sekme</div>
        </div>
      `).join('') || '<div class="empty">Grup bulunamadı</div>';
    } catch (e) {
      box.innerHTML = `<div class="empty">${escapeHtml(text)}</div>`;
    }
  }
};

function render(view) {
  if (view === 'settings') renderSettings();
  else if (view === 'history') renderHistory();
  else if (view === 'bookmarks') renderBookmarks();
  else if (view === 'passwords') renderPasswords();
  else if (view === 'privacy') renderPrivacy();
  else if (view === 'performance') renderPerformance();
  else if (view === 'ai') renderAiSettings();
  else if (view === 'personalize') renderPersonalize();
  else if (view === 'summary') renderSummary();
  else if (view === 'group') renderGroup();
  else if (view === 'split') renderSplit();
  else renderSettings();
}

// ---------- Ana menü ----------
function renderSettings() {
  setHeader('Menü', false);
  const s = JSON.parse(window.AndroidHost.getSettingsJson());
  el.body.innerHTML = `
    <div class="section-title">Gizlilik &amp; Güvenlik</div>
    <div class="menu-item switch-row"><div class="m-label">Reklam engelleme</div><input type="checkbox" id="tAdBlock" ${s.adBlock ? 'checked' : ''} /></div>
    <div class="menu-item switch-row"><div class="m-label">İzleyici engelleme</div><input type="checkbox" id="tTrackerBlock" ${s.trackerBlock ? 'checked' : ''} /></div>
    <div class="menu-item switch-row"><div class="m-label">HTTPS zorunlu modu</div><input type="checkbox" id="tHttpsOnly" ${s.httpsOnly ? 'checked' : ''} /></div>

    <div class="section-title">Tarayıcı</div>
    <div class="menu-item" id="goHistory"><div class="m-label">📜 Geçmiş</div><div class="chev">›</div></div>
    <div class="menu-item" id="goBookmarks"><div class="m-label">⭐ Yer İmleri</div><div class="chev">›</div></div>
    <div class="menu-item" id="goPasswords"><div class="m-label">🔑 Şifre Yöneticisi</div><div class="chev">›</div></div>
    <div class="menu-item" id="goPrivacy"><div class="m-label">🛡️ Gizlilik Panosu</div><div class="chev">›</div></div>
    <div class="menu-item" id="goPerformance"><div class="m-label">⚡ Performans &amp; Veri</div><div class="chev">›</div></div>
    <div class="menu-item" id="goPersonalize"><div class="m-label">🎨 Kişiselleştirme</div><div class="chev">›</div></div>

    <div class="section-title">Yapay Zeka</div>
    <div class="menu-item" id="goAi"><div class="m-label">🤖 AI Ayarları (Groq)</div><div class="chev">›</div></div>
    <div class="menu-item" id="goSummary"><div class="m-label">✨ Sayfayı Özetle</div><div class="chev">›</div></div>
    <div class="menu-item" id="goGroup"><div class="m-label">🧩 Sekmeleri Grupla</div><div class="chev">›</div></div>

    <div class="section-title">Araçlar</div>
    <div class="menu-item" id="doScreenshot"><div class="m-label">📷 Ekran Görüntüsü Al</div><div class="chev">›</div></div>
    <div class="menu-item" id="doPdf"><div class="m-label">🖨️ Sayfayı PDF Olarak Kaydet</div><div class="chev">›</div></div>
    <div class="menu-item" id="goSplit"><div class="m-label">🪟 Bölünmüş Ekran</div><div class="chev">›</div></div>
    <div class="menu-item" id="doUpdate"><div class="m-label">🔄 Güncellemeleri Kontrol Et</div><div class="chev">›</div></div>
  `;
  document.getElementById('tAdBlock').addEventListener('change', (e) => setSetting('adBlock', e.target.checked));
  document.getElementById('tTrackerBlock').addEventListener('change', (e) => setSetting('trackerBlock', e.target.checked));
  document.getElementById('tHttpsOnly').addEventListener('change', (e) => setSetting('httpsOnly', e.target.checked));
  document.getElementById('goHistory').addEventListener('click', () => { setHeader('Geçmiş', true); render('history'); });
  document.getElementById('goBookmarks').addEventListener('click', () => { setHeader('Yer İmleri', true); render('bookmarks'); });
  document.getElementById('goPasswords').addEventListener('click', () => { setHeader('Şifre Yöneticisi', true); render('passwords'); });
  document.getElementById('goPrivacy').addEventListener('click', () => { setHeader('Gizlilik Panosu', true); render('privacy'); });
  document.getElementById('goPerformance').addEventListener('click', () => { setHeader('Performans & Veri', true); render('performance'); });
  document.getElementById('goPersonalize').addEventListener('click', () => { setHeader('Kişiselleştirme', true); render('personalize'); });
  document.getElementById('goAi').addEventListener('click', () => { setHeader('AI Ayarları', true); render('ai'); });
  document.getElementById('goSummary').addEventListener('click', () => { setHeader('Sayfayı Özetle', true); render('summary'); });
  document.getElementById('goGroup').addEventListener('click', () => { setHeader('Sekmeleri Grupla', true); render('group'); });
  document.getElementById('goSplit').addEventListener('click', () => { setHeader('Bölünmüş Ekran', true); render('split'); });
  document.getElementById('doScreenshot').addEventListener('click', () => {
    const id = window.AndroidHost.getActiveTabId();
    if (id) window.AndroidHost.takeScreenshot(id);
    window.AndroidHost.closePanel();
  });
  document.getElementById('doPdf').addEventListener('click', () => {
    const id = window.AndroidHost.getActiveTabId();
    if (id) window.AndroidHost.printPageAsPdf(id);
    window.AndroidHost.closePanel();
  });
  document.getElementById('doUpdate').addEventListener('click', () => window.AndroidHost.checkForUpdate());
}

// ---------- Geçmiş ----------
function renderHistory() {
  const list = JSON.parse(window.AndroidHost.getHistoryJson());
  el.body.innerHTML = !list.length ? '<div class="empty">Henüz geçmiş yok</div>' : list.map(h => `
    <div class="list-item"><div class="l-title">${escapeHtml(h.title || h.url)}</div><div class="l-sub">${escapeHtml(h.url)}</div></div>
  `).join('');
}

// ---------- Yer imleri ----------
function renderBookmarks() {
  const list = JSON.parse(window.AndroidHost.getBookmarksJson());
  el.body.innerHTML = !list.length ? '<div class="empty">Henüz yer imi yok</div>' : list.map(b => `
    <div class="list-item"><div class="l-title">${escapeHtml(b.title || b.url)}</div><div class="l-sub">${escapeHtml(b.url)}</div></div>
  `).join('');
}

// ---------- Şifre yöneticisi ----------
const shownPasswords = new Set();
function renderPasswords() {
  el.body.innerHTML = `
    <div class="pw-form">
      <input id="pwSite" placeholder="Site (örn. example.com)" />
      <input id="pwUser" placeholder="Kullanıcı adı / e-posta" />
      <input id="pwPass" placeholder="Şifre" type="password" />
      <div class="pw-strength" id="pwStrength"></div>
      <button class="primary-btn" id="pwSaveBtn">Kaydet</button>
      <button class="primary-btn" id="breachBtn" style="background:var(--surface);color:var(--text);border:1px solid var(--border);">🔍 Tüm Şifrelerde Sızıntı Kontrolü</button>
    </div>
    <div id="breachResult"></div>
    <div id="pwList"></div>
  `;
  const pwPass = document.getElementById('pwPass');
  const pwStrength = document.getElementById('pwStrength');
  pwPass.addEventListener('input', () => {
    const pw = pwPass.value;
    if (!pw) { pwStrength.textContent = ''; pwStrength.className = 'pw-strength'; return; }
    if (isWeakPassword(pw)) { pwStrength.textContent = 'Zayıf şifre — en az 8 karakter ve tahmin edilmesi zor bir şifre kullanın'; pwStrength.className = 'pw-strength weak'; }
    else { pwStrength.textContent = 'Şifre gücü uygun görünüyor'; pwStrength.className = 'pw-strength ok'; }
  });
  document.getElementById('pwSaveBtn').addEventListener('click', () => {
    const site = document.getElementById('pwSite').value.trim();
    const user = document.getElementById('pwUser').value.trim();
    const pass = pwPass.value;
    if (!site || !pass) return;
    window.AndroidHost.savePassword(site, user, pass);
    document.getElementById('pwSite').value = ''; document.getElementById('pwUser').value = ''; pwPass.value = '';
    pwStrength.textContent = ''; pwStrength.className = 'pw-strength';
    renderPasswordsList();
  });
  document.getElementById('breachBtn').addEventListener('click', () => {
    document.getElementById('breachResult').innerHTML = '<div class="empty">Kontrol ediliyor…</div>';
    window.AndroidHost.checkBreachesAsync();
  });
  renderPasswordsList();
}

function renderPasswordsList() {
  const listEl = document.getElementById('pwList');
  if (!listEl) return;
  const list = JSON.parse(window.AndroidHost.getPasswordsJson());
  if (!list.length) { listEl.innerHTML = '<div class="empty">Henüz kayıtlı şifre yok</div>'; return; }
  listEl.innerHTML = list.map(p => {
    const weak = isWeakPassword(p.password);
    const masked = shownPasswords.has(p.id) ? escapeHtml(p.password) : '•'.repeat(Math.max(6, p.password.length));
    return `
      <div class="pw-item">
        <div class="pw-top">
          <div><div class="pw-site">${escapeHtml(p.site)}${weak ? '<span class="pw-badge-weak">ZAYIF</span>' : ''}</div><div class="pw-user">${escapeHtml(p.username)}</div></div>
          <button class="pw-del" data-id="${p.id}">Sil</button>
        </div>
        <div class="pw-secret" data-show="${p.id}">${masked}</div>
      </div>`;
  }).join('');
  listEl.querySelectorAll('[data-show]').forEach(node => node.addEventListener('click', () => {
    const id = node.getAttribute('data-show');
    if (shownPasswords.has(id)) shownPasswords.delete(id); else shownPasswords.add(id);
    renderPasswordsList();
  }));
  listEl.querySelectorAll('.pw-del').forEach(btn => btn.addEventListener('click', () => {
    window.AndroidHost.deletePassword(btn.getAttribute('data-id'));
    renderPasswordsList();
  }));
}

// ---------- Gizlilik panosu ----------
function renderPrivacy() {
  const count = window.AndroidHost.getPrivacyCount();
  el.body.innerHTML = `<div class="privacy-stat"><div class="privacy-number">${count}</div><div class="privacy-label">bu oturumda engellenen reklam / izleyici isteği</div></div>`;
}

// ---------- Performans & Veri ----------
function renderPerformance() {
  const s = JSON.parse(window.AndroidHost.getSettingsJson());
  const modes = [['performans', '🚀 Performans'], ['dengeli', '⚖️ Dengeli'], ['tasarruf', '🔋 Tasarruf'], ['veri', '📶 Veri Tasarrufu']];
  el.body.innerHTML = `
    <div class="section-title">Mod</div>
    ${modes.map(([val, label]) => `
      <div class="menu-item" data-mode="${val}" style="${s.perfMode === val ? 'border-color:var(--accent);' : ''}">
        <div class="m-label">${label}</div>
        <div class="chev">${s.perfMode === val ? '✓' : ''}</div>
      </div>
    `).join('')}

    <div class="section-title">Detaylı Ayarlar</div>
    <div class="menu-item switch-row"><div class="m-label">Sekme uyutma (arka plandaki sekmeleri duraklat)</div><input type="checkbox" id="tSleep" ${s.tabSleep ? 'checked' : ''} /></div>

    <div class="menu-item" style="flex-direction:column; align-items:stretch;">
      <div class="m-label" style="margin-bottom:8px;">Resim yükleme sınırı (MB, 0 = sınırsız)</div>
      <input type="number" id="imgLimit" value="${s.imageLimitMB || 0}" min="0" style="padding:10px;border:1px solid var(--border);border-radius:8px;" />
    </div>
    <div class="menu-item" style="flex-direction:column; align-items:stretch;">
      <div class="m-label" style="margin-bottom:8px;">Önbellek sınırı (MB, 0 = sınırsız)</div>
      <input type="number" id="cacheLimit" value="${s.cacheLimitMB || 0}" min="0" style="padding:10px;border:1px solid var(--border);border-radius:8px;" />
    </div>
    <button class="primary-btn" id="savePerfBtn">Kaydet</button>
  `;
  el.body.querySelectorAll('[data-mode]').forEach(node => node.addEventListener('click', () => {
    setSetting('perfMode', node.getAttribute('data-mode'));
    render('performance');
  }));
  document.getElementById('tSleep').addEventListener('change', (e) => setSetting('tabSleep', e.target.checked));
  document.getElementById('savePerfBtn').addEventListener('click', () => {
    setSetting('imageLimitMB', parseInt(document.getElementById('imgLimit').value || '0', 10));
    setSetting('cacheLimitMB', parseInt(document.getElementById('cacheLimit').value || '0', 10));
    window.AndroidHost.closePanel();
  });
}

// ---------- AI Ayarları ----------
function renderAiSettings() {
  const s = JSON.parse(window.AndroidHost.getSettingsJson());
  el.body.innerHTML = `
    <div class="section-title">Groq API</div>
    <div class="pw-form">
      <input id="groqKey" placeholder="Groq API anahtarı" type="password" value="${escapeHtml(s.groqApiKey || '')}" />
      <input id="groqModel" placeholder="Model adı" value="${escapeHtml(s.groqModel || 'llama-3.3-70b-versatile')}" />
      <button class="primary-btn" id="saveAiBtn">Kaydet</button>
    </div>
    <div class="empty" style="margin-top:0;">Sayfa özetleme ve sekme gruplama bu anahtarı kullanır. Anahtar sadece bu cihazda saklanır.</div>
  `;
  document.getElementById('saveAiBtn').addEventListener('click', () => {
    setSetting('groqApiKey', document.getElementById('groqKey').value.trim());
    setSetting('groqModel', document.getElementById('groqModel').value.trim() || 'llama-3.3-70b-versatile');
    window.AndroidHost.closePanel();
  });
}

// ---------- Kişiselleştirme ----------
function renderPersonalize() {
  const s = JSON.parse(window.AndroidHost.getSettingsJson());
  el.body.innerHTML = `
    <div class="section-title">Vurgu Rengi</div>
    <div style="display:flex; gap:10px; flex-wrap:wrap; padding:4px;">
      ${ACCENT_SWATCHES.map(c => `
        <div data-color="${c}" style="width:44px;height:44px;border-radius:50%;background:${c};border:3px solid ${s.accentColor === c ? '#000' : 'transparent'};"></div>
      `).join('')}
    </div>
  `;
  el.body.querySelectorAll('[data-color]').forEach(node => node.addEventListener('click', () => {
    setSetting('accentColor', node.getAttribute('data-color'));
    render('personalize');
  }));
}

// ---------- Sayfayı Özetle ----------
function renderSummary() {
  el.body.innerHTML = `<div class="empty" id="summaryResult">Analiz ediliyor… (birkaç saniye sürebilir)</div>`;
  const id = window.AndroidHost.getActiveTabId();
  if (id) window.AndroidHost.aiSummarizePage(id);
  else document.getElementById('summaryResult').textContent = 'Aktif sekme bulunamadı';
}

// ---------- Sekmeleri Grupla ----------
function renderGroup() {
  el.body.innerHTML = `<div class="empty" id="groupResult">Gruplanıyor…</div>`;
  try {
    const tabs = JSON.parse(window.AndroidHost.getOpenTabsJson());
    window.AndroidHost.groupTabsAI(JSON.stringify(tabs));
  } catch (e) {
    document.getElementById('groupResult').textContent = 'Hata: ' + e.message;
  }
}

// ---------- Bölünmüş Ekran ----------
function renderSplit() {
  const tabs = JSON.parse(window.AndroidHost.getOpenTabsJson());
  const activeId = window.AndroidHost.getActiveTabId();
  const others = tabs.filter(t => t.id !== activeId);
  el.body.innerHTML = `
    <button class="primary-btn" id="exitSplitBtn" style="margin-bottom:14px;background:var(--surface);color:var(--text);border:1px solid var(--border);">Bölünmüş Ekranı Kapat</button>
    <div class="section-title">İkinci sekmeyi seçin</div>
    ${!others.length ? '<div class="empty">Bölmek için başka açık sekme yok</div>' : others.map(t => `
      <div class="menu-item" data-tab="${t.id}"><div class="m-label">${escapeHtml(t.title)}</div><div class="chev">›</div></div>
    `).join('')}
  `;
  document.getElementById('exitSplitBtn').addEventListener('click', () => { window.AndroidHost.exitSplitView(); window.AndroidHost.closePanel(); });
  el.body.querySelectorAll('[data-tab]').forEach(node => node.addEventListener('click', () => {
    window.AndroidHost.enterSplitView(node.getAttribute('data-tab'));
    window.AndroidHost.closePanel();
  }));
}

// ---------- Header ----------
el.backBtn.addEventListener('click', () => render('settings'));
el.closeBtn.addEventListener('click', () => window.AndroidHost.closePanel());

render('settings');
