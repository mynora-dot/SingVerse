/* Offline visual prototype. No network calls, storage, real queue, auth or playback. */
'use strict';
const songs = [
  ['平凡之路', '朴树', 'photo'], ['晴天', '周杰伦', 'green'],
  ['后来', '刘若英', 'rose'], ['海阔天空', 'Beyond', 'blue'],
  ['慢慢喜欢你', '莫文蔚', 'gold'], ['夜空中最亮的星', '逃跑计划', 'stars'],
  ['未设置封面的歌曲', '歌手未知', 'missing']
];
const tv = document.querySelector('#tv');
const content = document.querySelector('#content');
const dialog = document.querySelector('dialog');
let returnFocus = null;
let demoCount = 3;
const art = ([title, , theme]) => `<div class="art ${theme}"><div class="landscape"></div>${theme === 'missing' ? '<svg><use href="#brand"/></svg>' : theme === 'photo' ? '' : `<span class="art-word">${title}<small>SINGVERSE COLLECTION</small></span>`}</div>`;
document.querySelector('#songs').innerHTML = songs.map((song, index) => `<button class="song" data-song="${index}" aria-label="${song[0]}，${song[1]}，查看详情">${art(song)}<span class="song-meta"><span><strong>${song[0]}</strong><small>${song[1]}</small></span><svg><use href="#plus"/></svg></span></button>`).join('');
function resize() { document.documentElement.style.setProperty('--scale', Math.min(innerWidth / 1920, innerHeight / 1080)); }
addEventListener('resize', resize);
resize();
function closeDialog() { dialog.close(); returnFocus?.focus({ preventScroll: true }); }
function openDialog(button) {
  returnFocus = button;
  const song = button.dataset.song !== undefined ? songs[Number(button.dataset.song)] : null;
  const title = song ? button.dataset.action === 'sing' ? '点唱确认' : '歌曲详情' : button.dataset.destination;
  if (title === '首页') { content.scrollTo({ top: 0, behavior: 'smooth' }); document.querySelector('#initial').focus({ preventScroll: true }); return; }
  document.querySelector('#dialog-title').textContent = title;
  document.querySelector('#dialog-song').innerHTML = song ? `${art(song)}<p>${song[0]} <span style="color:#9ea4b3">/ ${song[1]}</span></p>` : '';
  document.querySelector('#dialog-description').textContent = title === '手机加入'
    ? '正式功能保留微信扫码加入聚会。此处为视觉原型，未生成邀请二维码。'
    : title === '点唱确认' ? '确认后仅更新本页演示计数，不会发送点歌请求。'
    : title === '歌曲详情' ? '保留现有歌曲详情与点歌入口。封面为原创演示视觉，不是官方专辑封面。'
    : title === '已点' ? `本场已点 ${demoCount} 首 · 演示状态，不读取真实队列。`
    : `保留现有「${title}」入口。本阶段只评审首页视觉，不连接业务页面。`;
  document.querySelector('#demo-add').hidden = !song;
  dialog.showModal();
  document.querySelector('#close').focus();
}
tv.addEventListener('click', event => {
  const button = event.target.closest('button');
  if (!button || dialog.contains(button)) return;
  openDialog(button);
});
document.querySelector('#close').addEventListener('click', closeDialog);
document.querySelector('#demo-add').addEventListener('click', () => {
  document.querySelector('.count').textContent = String(++demoCount);
  closeDialog();
});
dialog.addEventListener('cancel', event => { event.preventDefault(); closeDialog(); });
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' || event.key === 'Backspace' || event.key === 'BrowserBack') {
    event.preventDefault();
    if (dialog.open) closeDialog();
    else { content.scrollTo({ top: 0, behavior: 'smooth' }); document.querySelector('#initial').focus({ preventScroll: true }); }
    return;
  }
  // Native buttons handle Enter/Space; remote arrows use geometric spatial navigation.
  const directions = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
  const direction = directions[event.key];
  if (!direction) return;
  event.preventDefault();
  const active = document.activeElement;
  const candidates = [...(dialog.open ? dialog : tv).querySelectorAll('button')].filter(el => !el.hidden && el.getClientRects().length);
  if (!candidates.includes(active)) { candidates[0]?.focus(); return; }
  const bounds = active.getBoundingClientRect();
  const x = bounds.left + bounds.width / 2;
  const y = bounds.top + bounds.height / 2;
  let best = null;
  let score = Infinity;
  for (const el of candidates) {
    if (el === active) continue;
    const box = el.getBoundingClientRect();
    const dx = box.left + box.width / 2 - x;
    const dy = box.top + box.height / 2 - y;
    const along = dx * direction[0] + dy * direction[1];
    const across = Math.abs(direction[0] ? dy : dx);
    if (along <= 8) continue;
    const sameLane = direction[0] ? Math.abs(dy) < Math.min(bounds.height, box.height) / 2 : Math.abs(dx) < (bounds.width + box.width) / 2;
    const value = along + across * 3 + (sameLane ? 0 : 300);
    if (value < score) { score = value; best = el; }
  }
  if (best) {
    best.focus({ preventScroll: true });
    best.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'nearest', inline: 'nearest' });
  }
});
document.querySelector('#initial').focus({ preventScroll: true });
