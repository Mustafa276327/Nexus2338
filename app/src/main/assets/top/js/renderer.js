'use strict';

const SEARCH_URL = 'https://www.google.com/search?q=';

let tabs = [];        // {id, title, url, pinned, private, canGoBack, canGoForward}
let activeTabId = null;

const el = {
  tabs: document.getElementById('tabs'),
  newTabBtn: document.getElementById('newTabBtn'),
  newPrivateTabBtn: document.getElementById('newPrivateTabBtn'),
  addressBar: document.getElementById('addressBar'),
  backBtn: document.getElementById('backBtn'),
  forwardBtn: document.getElementById('forwardBtn'),
  reloadBtn: document.getElementById('reloadBtn'),
  starBtn: document.getElementById('starBtn'),
  lockIcon: document.getElementById('lockIcon'),
  menuBtn: document.getElementById('menuBtn'),
  suggestBox: document.getElementById('suggestBox')
};

// ---------- Kişiselleştirme: kayıtlı accent rengini uygula ----------
(function applyAccent() {
  try {
    const s = JSON.parse(window.AndroidHost.getSettingsJson());
    if (s.accentColor) document.documentElement.style.setProperty('--accent', s.accentColor);
  } catch (e) {}
})();

function getTab(id) { return tabs.find(t => t.id === id); }
function getActiveTab() { return getTab(activeTabId); }

function isLikelyUrl(input) {
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(input)) return true;
  if (/^localhost(:\d+)?$/i.test(input)) return true;
  if (/^\d{1,3}(\.\d{1,3}){3}(:\d+)?$/.test(input)) return true;
  if (/^[^\s]+\.[a-z]{2,}([\/?#].*)?$/i.test(input) && !input.includes(' ')) return true;
  return false;
}
function toUrl(input) {
  if (isLikelyUrl(input)) return /^[a-z][a-z0-9+.-]*:\/\//i.test(input) ? input : 'https://' + input;
  return SEARCH_URL + encodeURIComponent(input);
}

function createTab(isPrivate) {
  const id = window.AndroidHost.createTab(!!isPrivate);
  tabs.push({ id, title: isPrivate ? 'Gizli sekme' : 'Yeni sekme', url: '', pinned: false, private: !!isPrivate, canGoBack: false, canGoForward: false });
  activeTabId = id;
  renderTabs();
  updateToolbarForActiveTab();
}

function switchTab(id) {
  activeTabId = id;
  window.AndroidHost.switchTab(id);
  renderTabs();
  updateToolbarForActiveTab();
}

function closeTab(id) {
  window.AndroidHost.closeTab(id);
  tabs = tabs.filter(t => t.id !== id);
  if (activeTabId === id) {
    activeTabId = tabs.length ? tabs[tabs.length - 1].id : null;
  }
  renderTabs();
  updateToolbarForActiveTab();
}

function goToInput(raw) {
  const tab = getActiveTab();
  if (!tab) return;
  window.AndroidHost.navigate(tab.id, toUrl(raw));
}

function renderTabs() {
  el.tabs.innerHTML = '';
  tabs.forEach(tab => {
    const div = document.createElement('div');
    div.className = 'tab' + (tab.id === activeTabId ? ' active' : '') + (tab.private ? ' private' : '');
    const letter = (tab.title || 'N').trim().charAt(0).toUpperCase() || 'N';
    div.innerHTML = `<div class="favicon">${letter}</div><div class="title">${escapeHtml(tab.title || 'Yeni sekme')}</div><div class="close">✕</div>`;
    div.querySelector('.title').addEventListener('click', () => switchTab(tab.id));
    div.querySelector('.favicon').addEventListener('click', () => switchTab(tab.id));
    div.querySelector('.close').addEventListener('click', (e) => { e.stopPropagation(); closeTab(tab.id); });
    el.tabs.appendChild(div);
  });
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function updateToolbarForActiveTab() {
  const tab = getActiveTab();
  el.addressBar.value = tab ? (tab.url || '') : '';
  const isHttps = tab && tab.url && tab.url.startsWith('https://');
  el.lockIcon.style.stroke = (tab && tab.url) ? (isHttps ? '#23a55a' : '#c9752f') : '#7a7d94';
  el.backBtn.disabled = !(tab && tab.canGoBack);
  el.forwardBtn.disabled = !(tab && tab.canGoForward);
  const bookmarked = tab && tab.url ? window.AndroidHost.isBookmarked(tab.url) : false;
  el.starBtn.classList.toggle('active-toggle', !!bookmarked);
}

// ---------- Kotlin'den gelen olaylar ----------
window.NexusTop = {
  onNavigated(tabId, url, title, canGoBack, canGoForward) {
    const tab = getTab(tabId);
    if (!tab) return;
    tab.url = url;
    tab.title = title || url || 'Yeni sekme';
    tab.canGoBack = !!canGoBack;
    tab.canGoForward = !!canGoForward;
    renderTabs();
    if (tabId === activeTabId) updateToolbarForActiveTab();
  },
  onPrivacyUpdate(_count) { /* gizlilik panosu açıkken overlay kendi okuyor, burada iş yok */ }
};

// ---------- Olaylar ----------
el.newTabBtn.addEventListener('click', () => createTab(false));
el.newPrivateTabBtn.addEventListener('click', () => createTab(true));

el.addressBar.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && el.addressBar.value.trim()) {
    goToInput(el.addressBar.value.trim());
    el.addressBar.blur();
    hideSuggestions();
  }
});
el.addressBar.addEventListener('focus', () => el.addressBar.select());
el.addressBar.addEventListener('input', () => {
  const q = el.addressBar.value.trim();
  if (!q) { hideSuggestions(); return; }
  try {
    const list = JSON.parse(window.AndroidHost.getSmartSuggestionsJson(q));
    renderSuggestions(list);
  } catch (e) { hideSuggestions(); }
});
el.addressBar.addEventListener('blur', () => setTimeout(hideSuggestions, 150));

function renderSuggestions(list) {
  if (!list.length) { hideSuggestions(); return; }
  el.suggestBox.innerHTML = list.map(item => `
    <div class="suggest-item" data-url="${escapeHtml(item.url)}">
      <div class="s-title">${escapeHtml(item.title || item.url)}</div>
      <div class="s-url">${escapeHtml(item.url)}</div>
    </div>
  `).join('');
  el.suggestBox.querySelectorAll('.suggest-item').forEach(node => {
    node.addEventListener('mousedown', (e) => {
      e.preventDefault();
      goToInput(node.getAttribute('data-url'));
      hideSuggestions();
      el.addressBar.blur();
    });
  });
  el.suggestBox.classList.add('open');
}
function hideSuggestions() { el.suggestBox.classList.remove('open'); el.suggestBox.innerHTML = ''; }

el.backBtn.addEventListener('click', () => { const t = getActiveTab(); if (t) window.AndroidHost.goBack(t.id); });
el.forwardBtn.addEventListener('click', () => { const t = getActiveTab(); if (t) window.AndroidHost.goForward(t.id); });
el.reloadBtn.addEventListener('click', () => { const t = getActiveTab(); if (t) window.AndroidHost.reload(t.id); });

el.starBtn.addEventListener('click', () => {
  const t = getActiveTab();
  if (!t || !t.url) return;
  const nowBookmarked = window.AndroidHost.toggleBookmark(t.title || t.url, t.url);
  el.starBtn.classList.toggle('active-toggle', !!nowBookmarked);
});

el.menuBtn.addEventListener('click', () => window.AndroidHost.showPanel('settings'));

// ---------- Başlangıç ----------
createTab(false);
