(() => {
  'use strict';

  const params = new URLSearchParams(window.location.search);
  const sceneNames = { mv: 'MV 播放', audio: '音频 / 歌词', landscape: '横屏舞台' };
  const duration = 248;
  const artwork = '../../singverse-tv/app/src/debug/assets/home-hero.png';
  const song = { title: '把晚风唱给你', artist: '林间回声', album: '晚风来信' };
  // 原创演示歌词，不来自实际歌曲或 NAS 曲库。
  const lyrics = [
    { time: 0, text: '当城市慢慢安静' },
    { time: 24, text: '让晚风吹散倦意' },
    { time: 48, text: '沿着海岸的灯火' },
    { time: 72, text: '把今天轻轻放下' },
    { time: 88, text: '唱一首给自己的歌' },
    { time: 112, text: '等星光落在肩头' },
    { time: 140, text: '把心事唱成自由' },
    { time: 168, text: '那些没说完的话' },
    { time: 192, text: '都交给夜色回答' },
    { time: 220, text: '让歌声陪你回家' },
  ];
  const designs = [
    { theme: 'dark', letter: 'A', name: '沉浸深色', icon: 'moon', label: '夜色舞台', description: '<b>把注意力交给音乐。</b> 深色舞台与大字歌词，声音切换独立成组。' },
    { theme: 'light', letter: 'B', name: '清爽浅色', icon: 'sun', label: '清晨留白', description: '<b>让每一步都轻一点。</b> 留白、清晰的操作分层，以及独立的录音入口。' },
  ];
  const gallery = document.getElementById('gallery');
  let scene = Object.hasOwn(sceneNames, params.get('scene')) ? params.get('scene') : 'mv';
  let mobileTheme = params.get('theme') === 'light' ? 'light' : 'dark';
  let lastPortraitScene = scene === 'audio' ? 'audio' : 'mv';
  let activeModal = null;
  const players = new Map();
  const icon = (name, className = '') => `<svg class="icon ${className}" aria-hidden="true"><use href="#i-${name}"></use></svg>`;
  const timeLabel = (value) => `${Math.floor(Math.max(0, value) / 60)}:${String(Math.floor(Math.max(0, value) % 60)).padStart(2, '0')}`;

  function tool(action, name, label) {
    return `<button type="button" class="tool-button" data-action="${action}" data-lock>${icon(name)}<span>${label}</span></button>`;
  }

  function recordButton(wide = false) {
    return `<button type="button" class="record-button${wide ? ' wide-record' : ''}" data-action="record" aria-label="开始录音演示"><span class="record-dot" aria-hidden="true"></span><span class="record-label">${wide ? '开始录音' : '录音'}</span></button>`;
  }

  function roles() {
    return `<div class="role-switch" role="group" aria-label="原唱或伴奏">
      <button type="button" data-action="role-original" data-lock aria-pressed="false">原唱</button>
      <button type="button" data-action="role-backing" data-lock aria-pressed="true">伴奏</button>
    </div>`;
  }

  function createDesign(design) {
    const dark = design.theme === 'dark';
    const favorite = `<button type="button" class="icon-button favorite" data-action="favorite" aria-label="收藏歌曲" aria-pressed="false">${icon('heart')}</button>`;
    const timeline = `<div class="timeline">
      <label class="sr-only" for="progress-${design.theme}">播放进度（演示）</label>
      <input id="progress-${design.theme}" class="range progress-input" type="range" min="0" max="${duration}" step="1" value="88" data-lock>
      <div class="time-labels"><span class="elapsed">1:28</span><span class="total">4:08</span></div>
    </div>`;
    const play = `<button type="button" class="play-button" data-action="play" data-lock aria-label="播放音乐">${icon('play')}</button>`;
    return `<article class="variant${design.theme === mobileTheme ? ' device-selected' : ''}" data-theme="${design.theme}" aria-labelledby="title-${design.theme}">
      <div class="variant-heading"><h2 id="title-${design.theme}">${icon(design.icon)}${design.letter} · ${design.name}</h2><span class="variant-label">${design.label}</span></div>
      <div class="phone" data-theme="${design.theme}" data-scene="${scene}" aria-label="${design.name}播放器">
        <div class="status-bar" aria-hidden="true"><span>9:41</span><span class="status-icons">${icon('wifi')}${icon('battery')}</span></div>
        <div class="screen-body">
          <header class="player-header">
            <button type="button" class="icon-button collapse-button" data-action="collapse" aria-label="收起演唱">${icon('down')}</button>
            <div class="header-copy"><h3>${dark ? song.title : '正在练唱'}</h3><p>${song.artist} · ${song.album}</p></div>
            <button type="button" class="icon-button landscape-toggle" data-action="hide-controls" aria-label="收起播放控制">${icon('down')}</button>
            ${dark ? favorite : ''}
            <button type="button" class="icon-button" data-action="info" aria-label="歌曲信息">${icon('info')}</button>
          </header>
          <section class="stage" aria-label="媒体与歌词">
            <div class="video-box">
              <img class="video-art" src="${artwork}" alt="海岸公路与落日，示意 MV 画面" width="1672" height="941">
              <span class="video-note">MV · 演示画面</span>
              <button type="button" class="icon-button expand-button" data-action="fullscreen" aria-label="横屏全屏预览">${icon('expand')}</button>
            </div>
            <div class="audio-art"><img src="${artwork}" alt="晚风来信，示意歌曲封面" width="148" height="148"><span class="art-caption">LET THE<br>EVENING SING.</span></div>
            <div class="song-details"><div><h3>${song.title}</h3><p>${song.artist} <span aria-hidden="true"> / </span> ${song.album}</p></div>${dark ? '' : favorite}</div>
            <div class="lyrics-viewport" aria-label="原创示例歌词，点击可跳转"><div class="lyric-list"></div></div>
          </section>
          <section class="controls" aria-label="练唱控制">
            ${dark ? `<div class="mode-row"><span class="mode-caption">${icon('headphones')}演唱声音</span>${roles()}</div>` : ''}
            ${timeline}
            <div class="transport">${tool('restart', 'restart', '重唱')}${play}${dark ? recordButton() : tool('tune', 'tune', '调音')}</div>
            ${dark ? `<div class="tools">${tool('tune', 'tune', '调音')}${tool('range', 'range', '选段')}${tool('more', 'more', '更多')}</div>` : `<div class="light-utilities">${roles()}${tool('range', 'range', '选段')}${tool('more', 'more', '更多')}</div>${recordButton(true)}<div class="landscape-light-tools">${tool('range', 'range', '选段')}${tool('more', 'more', '更多')}${recordButton()}</div>`}
            <p class="session-note">${icon('mic-off')}<span>麦克风未开启 · 放心练唱</span></p>
          </section>
          <div class="recording-chip"><span class="record-dot" aria-hidden="true"></span><span class="recording-clock">录音演示 0:00</span><button type="button" class="record-chip-action" data-action="record">结束</button></div>
          <button type="button" class="show-controls" data-action="show-controls">${icon('tune')}显示播放控制</button>
        </div>
        <div class="home-area" aria-hidden="true"></div>
        <div class="toast" role="status" hidden></div>
      </div>
      <p class="variant-description">${design.description}</p>
    </article>`;
  }

  gallery.innerHTML = designs.map(createDesign).join('');
  for (const phone of gallery.querySelectorAll('.phone')) {
    players.set(phone.dataset.theme, {
      phone, theme: phone.dataset.theme, playing: false, position: 88, role: 'backing', favorite: false,
      pitch: 0, speed: 1, volume: .8, loop: false, subtitles: true, range: null,
      recording: 'idle', recorded: 0, countdown: 0, countdownTimer: null, clock: performance.now(), lyricKey: '', toastTimer: null,
    });
  }

  function setScene(next) {
    if (!Object.hasOwn(sceneNames, next)) return;
    if (next !== 'landscape') lastPortraitScene = next;
    scene = next;
    gallery.dataset.scene = scene;
    for (const button of document.querySelectorAll('button[data-scene]')) button.setAttribute('aria-pressed', String(button.dataset.scene === scene));
    for (const player of players.values()) {
      player.phone.dataset.scene = scene;
      player.phone.classList.remove('controls-hidden');
      player.phone.querySelector('.header-copy h3').textContent = player.theme === 'dark' || scene === 'landscape' ? song.title : '正在练唱';
      const collapse = player.phone.querySelector('.collapse-button');
      collapse.setAttribute('aria-label', scene === 'landscape' ? '退出横屏预览' : '收起演唱');
      collapse.innerHTML = icon(scene === 'landscape' ? 'shrink' : 'down');
      player.lyricKey = '';
      renderLyrics(player);
    }
  }

  function setMobileTheme(theme) {
    mobileTheme = theme;
    gallery.dataset.mobileTheme = theme;
    for (const variant of gallery.querySelectorAll('.variant')) variant.classList.toggle('device-selected', variant.dataset.theme === theme);
    for (const button of document.querySelectorAll('button[data-theme]')) button.setAttribute('aria-pressed', String(button.dataset.theme === theme));
  }

  function applyPreviewOptions() {
    for (const player of players.values()) {
      player.phone.classList.toggle('large-text', document.getElementById('large-text').checked);
      player.phone.classList.toggle('senior', document.getElementById('senior-targets').checked);
    }
  }

  function renderLyrics(player) {
    let active = lyrics.findLastIndex((line) => line.time <= player.position);
    if (active < 0) active = 0;
    const key = `${active}-${scene}-${player.recording}`;
    if (player.lyricKey === key) return;
    player.lyricKey = key;
    const count = 3;
    const start = Math.max(0, Math.min(lyrics.length - count, active - Math.floor(count / 2)));
    const list = player.phone.querySelector('.lyric-list');
    const focusedTime = list.contains(document.activeElement) ? document.activeElement.dataset.time : null;
    list.innerHTML = lyrics.slice(start, start + count).map((line, index) => {
      const current = index + start === active;
      const next = index + start === active + 1;
      return `<button type="button" class="lyric-line${current ? ' is-current' : next ? ' is-next' : ''}" data-action="seek-lyric" data-time="${line.time}"${current ? ' aria-current="true"' : ''}${player.recording !== 'idle' ? ' disabled' : ''} aria-label="跳到 ${timeLabel(line.time)}，${line.text}">${line.text}</button>`;
    }).join('');
    if (focusedTime !== null && player.recording === 'idle') {
      const target = list.querySelector(`[data-time="${focusedTime}"]`) ?? list.querySelector('.is-current');
      target?.focus({ preventScroll: true });
    }
  }

  function renderProgress(player) {
    const progress = player.phone.querySelector('.progress-input');
    progress.value = String(Math.floor(player.position));
    fillRange(progress);
    progress.setAttribute('aria-valuetext', `${timeLabel(player.position)}，共 ${timeLabel(duration)}`);
    player.phone.querySelector('.elapsed').textContent = timeLabel(player.position);
    renderLyrics(player);
    player.phone.querySelector('.recording-clock').textContent = `录音演示 ${timeLabel(player.recorded)}`;
    if (player.recording === 'recording') player.phone.querySelector('.session-note span').textContent = `录音演示中 · ${timeLabel(player.recorded)}`;
  }

  function renderActions(player) {
    const taking = player.recording !== 'idle';
    player.phone.classList.toggle('is-recording', player.recording === 'recording');
    for (const control of player.phone.querySelectorAll('[data-lock]')) control.disabled = taking;
    const play = player.phone.querySelector('[data-action="play"]');
    play.setAttribute('aria-label', player.playing ? '暂停音乐' : '播放音乐');
    play.innerHTML = icon(player.playing ? 'pause' : 'play');
    for (const role of ['original', 'backing']) {
      for (const button of player.phone.querySelectorAll(`[data-action="role-${role}"]`)) button.setAttribute('aria-pressed', String(player.role === role));
    }
    for (const button of player.phone.querySelectorAll('.favorite')) {
      button.setAttribute('aria-pressed', String(player.favorite));
      button.setAttribute('aria-label', player.favorite ? '取消收藏' : '收藏歌曲');
    }
    for (const button of player.phone.querySelectorAll('.record-button')) {
      const active = player.recording === 'recording';
      button.setAttribute('aria-label', active ? '结束录音演示' : '开始录音演示');
      button.innerHTML = `${active ? icon('stop') : '<span class="record-dot" aria-hidden="true"></span>'}<span class="record-label">${active ? '结束录音' : button.classList.contains('wide-record') ? '开始录音' : '录音'}</span>`;
    }
    const note = player.phone.querySelector('.session-note');
    note.innerHTML = `${icon(player.recording === 'recording' ? 'mic' : 'mic-off')}<span>${player.recording === 'recording' ? `录音演示中 · ${timeLabel(player.recorded)}` : player.range ? `选段练唱 ${timeLabel(player.range[0])} — ${timeLabel(player.range[1])}` : '麦克风未开启 · 放心练唱'}</span>`;
    renderProgress(player);
  }

  function fillRange(input) {
    const min = Number(input.min) || 0;
    const max = Number(input.max) || 100;
    input.style.setProperty('--fill', `${((Number(input.value) - min) / (max - min)) * 100}%`);
  }

  function toast(player, message) {
    const element = player.phone.querySelector('.toast');
    clearTimeout(player.toastTimer);
    element.textContent = message;
    element.hidden = false;
    player.toastTimer = setTimeout(() => { element.hidden = true; }, 2600);
  }

  function closeModal(restoreFocus = true) {
    if (!activeModal) return;
    const modal = activeModal;
    activeModal = null;
    modal.layer.remove();
    for (const [element, previous] of modal.inertNodes) element.inert = previous;
    if (restoreFocus) {
      const visible = (element) => element?.isConnected && !element.disabled && element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden';
      const target = visible(modal.trigger) ? modal.trigger : [...modal.player.phone.querySelectorAll('.record-button')].find(visible);
      target?.focus({ preventScroll: true });
    }
  }

  function openModal(player, kind, title, content, trigger = document.activeElement) {
    const opener = activeModal?.player === player ? activeModal.trigger : trigger;
    closeModal(false);
    const layer = document.createElement('div');
    layer.className = `modal-layer${kind === 'countdown' ? ' countdown-layer' : ''}`;
    const heading = `sheet-title-${player.theme}`;
    layer.innerHTML = kind === 'countdown'
      ? `<div class="modal-scrim"></div><section class="countdown-content" role="alertdialog" aria-modal="true" aria-labelledby="${heading}"><strong class="count-number" aria-live="assertive">3</strong><p id="${heading}">倒数结束，开始录音演示</p><button type="button" class="sheet-action" data-action="cancel-countdown">取消倒数</button></section>`
      : `<div class="modal-scrim" data-action="close-sheet" aria-hidden="true"></div><section class="sheet" role="dialog" aria-modal="true" aria-labelledby="${heading}"><header class="sheet-header"><h4 id="${heading}">${title}</h4><button type="button" class="icon-button" data-action="close-sheet" aria-label="关闭${title}">${icon('close')}</button></header>${content}</section>`;
    const inertNodes = [...document.querySelectorAll('.screen-body, .review-header, .review-intro, .review-toolbar, .review-footer, .variant-heading, .variant-description')].map((element) => [element, element.inert]);
    for (const [element] of inertNodes) element.inert = true;
    player.phone.append(layer);
    activeModal = { player, kind, layer, trigger: opener, inertNodes };
    for (const range of layer.querySelectorAll('input[type="range"]')) fillRange(range);
    layer.querySelector('button:not(:disabled), input:not(:disabled), select:not(:disabled)')?.focus({ preventScroll: true });
  }

  function showTuning(player) {
    const value = (setting) => setting === 'pitch' ? `${player.pitch > 0 ? '+' : ''}${player.pitch} 半音` : `${Math.round(player[setting] * 100)}%`;
    const setting = (name, label, min, max, step) => `<div class="setting-group"><div class="setting-label"><label for="${name}-${player.theme}">${label}</label><output for="${name}-${player.theme}" data-output="${name}">${value(name)}</output></div><input class="range" id="${name}-${player.theme}" data-setting="${name}" type="range" min="${min}" max="${max}" step="${step}" value="${player[name]}"></div>`;
    openModal(player, 'tune', '调整声音', `<p class="sheet-intro">找到最舒服的音调，按自己的节奏唱。</p>
      ${setting('pitch', '音调', -6, 6, 1)}${setting('speed', '速度', .6, 1.2, .05)}${setting('volume', '播放音量', 0, 1, .05)}
      <p class="sheet-note">参数仅用于界面演示。作品的人声与伴奏比例在回听时调整。</p>
      <button type="button" class="sheet-action" data-action="close-sheet">${icon('check')}调好了，继续唱</button>
      <button type="button" class="sheet-action sheet-secondary" data-action="reset-tuning">恢复默认声音</button>`);
  }

  function showInfo(player) {
    openModal(player, 'info', '关于这首歌', `<dl class="info-grid">
      <div><dt>歌曲</dt><dd>${song.title}</dd></div><div><dt>歌手</dt><dd>${song.artist}（示例）</dd></div><div><dt>专辑</dt><dd>${song.album}（示例）</dd></div>
      <div><dt>时长</dt><dd>4:08</dd></div><div><dt>歌词</dt><dd>原创演示歌词</dd></div><div><dt>画面</dt><dd>仓库内已有的海岸公路插图</dd></div>
      </dl><button type="button" class="sheet-action" data-action="close-sheet">回到演唱</button>`);
  }

  function showRange(player) {
    const start = player.range?.[0] ?? 88;
    const end = player.range?.[1] ?? 140;
    const options = (selected, ending = false) => lyrics.filter((line) => !ending || line.time > 0).map((line) => `<option value="${line.time}"${line.time === selected ? ' selected' : ''}>${timeLabel(line.time)} · ${line.text}</option>`).join('');
    openModal(player, 'range', '这一段，多唱几遍', `<p class="sheet-intro">选一句开始，选一句结束，循环练到喜欢。</p>
      <label class="setting-label" for="range-start-${player.theme}">开始句</label><select id="range-start-${player.theme}" data-range-boundary="start">${options(start)}</select>
      <label class="setting-label" for="range-end-${player.theme}">结束位置</label><select id="range-end-${player.theme}" data-range-boundary="end">${options(end, true)}<option value="${duration}">4:08 · 歌曲结束</option></select>
      <p class="sheet-note range-error" role="alert" hidden>结束位置需要晚于开始句。</p>
      <button type="button" class="sheet-action" data-action="apply-range">${icon('range')}循环练唱这一段</button>
      <button type="button" class="sheet-action sheet-secondary" data-action="clear-range">恢复整首练唱</button>`);
  }

  function showMore(player) {
    openModal(player, 'more', '更多练唱方式', `<button type="button" class="sheet-row" data-action="range">${icon('range')}<span>选段练唱<small>把喜欢的一句，唱得更好</small></span>${icon('arrow')}</button>
      <button type="button" class="sheet-row" data-action="toggle-loop" aria-pressed="${player.loop}">${icon('restart')}<span>单曲循环<small>${player.loop ? '已开启 · 结束后从头播放' : '已关闭 · 唱完这一首就休息'}</small></span>${icon(player.loop ? 'check' : 'arrow')}</button>
      <div class="sheet-divider"></div><button type="button" class="sheet-row" data-action="audio-settings">${icon('headphones')}<span>音轨与播放设置<small>选择原唱、伴奏和字幕</small></span>${icon('arrow')}</button>
      <p class="sheet-note">这是单曲播放器，演示不会加入电视聚会队列。</p>`);
  }

  function showAudioSettings(player) {
    openModal(player, 'audio-settings', '音轨与播放设置', `<p class="sheet-intro">当前提供两条已标记的示例音轨。</p>
      <label class="setting-label" for="audio-${player.theme}">演唱声音</label><select id="audio-${player.theme}" data-setting="role"><option value="original"${player.role === 'original' ? ' selected' : ''}>原唱音轨（示例）</option><option value="backing"${player.role === 'backing' ? ' selected' : ''}>伴奏音轨（示例）</option></select>
      <label class="setting-label" for="subtitles-${player.theme}">视频字幕</label><select id="subtitles-${player.theme}" data-setting="subtitles"><option value="on"${player.subtitles ? ' selected' : ''}>中文歌词（示例）</option><option value="off"${!player.subtitles ? ' selected' : ''}>关闭视频字幕</option></select>
      <p class="sheet-note">本页展示界面状态，不进行实际音轨切换或媒体解码。</p><button type="button" class="sheet-action" data-action="close-sheet">完成</button>`);
  }

  function startRecording(player) {
    player.previousPlayback = { playing: player.playing, position: player.position };
    player.recording = 'countdown';
    player.playing = false;
    player.countdown = 3;
    renderActions(player);
    openModal(player, 'countdown', '', '');
    const advance = () => {
      player.countdown -= 1;
      if (player.countdown > 0) {
        if (activeModal?.player === player && activeModal.kind === 'countdown') activeModal.layer.querySelector('.count-number').textContent = String(player.countdown);
        player.countdownTimer = setTimeout(advance, 1000);
        return;
      }
      player.countdownTimer = null;
      closeModal(false);
      player.recording = 'recording';
      player.recorded = 0;
      player.position = 0;
      player.playing = true;
      player.clock = performance.now();
      renderActions(player);
      [...player.phone.querySelectorAll('.record-button')].find((button) => button.getClientRects().length > 0)?.focus({ preventScroll: true });
    };
    player.countdownTimer = setTimeout(advance, 1000);
  }

  function cancelCountdown(player) {
    clearTimeout(player.countdownTimer);
    player.countdownTimer = null;
    player.recording = 'idle';
    player.countdown = 0;
    player.playing = player.previousPlayback.playing;
    player.position = player.previousPlayback.position;
    renderActions(player);
    closeModal();
  }

  function endRecording(player) {
    player.recording = 'idle';
    player.playing = false;
    player.phone.classList.remove('controls-hidden');
    renderActions(player);
    openModal(player, 'finished', '完成这一段', `<div class="take-summary">${icon('wave')}<h5>这段歌声，留给自己。</h5><p>录音演示时长 ${timeLabel(player.recorded)}<br>本次未采集声音，也未生成录音文件。</p></div>
      <button type="button" class="sheet-action" data-action="close-sheet">${icon('music')}回到练唱</button><button type="button" class="sheet-action sheet-secondary" data-action="record-again">再唱一遍</button>`);
  }

  const actions = {
    play(player) {
      if (player.position >= duration) player.position = player.range?.[0] ?? 0;
      player.playing = !player.playing;
      player.clock = performance.now();
      renderActions(player);
    },
    'role-original'(player) { player.role = 'original'; renderActions(player); toast(player, '已切换原唱 · 声音仅作演示'); },
    'role-backing'(player) { player.role = 'backing'; renderActions(player); toast(player, '已切换伴奏 · 声音仅作演示'); },
    favorite(player) { player.favorite = !player.favorite; renderActions(player); toast(player, player.favorite ? '已加入演示收藏' : '已取消演示收藏'); },
    restart(player) { player.position = player.range?.[0] ?? 0; player.playing = true; player.clock = performance.now(); renderActions(player); },
    'seek-lyric'(player, button) { player.position = Number(button.dataset.time); renderProgress(player); },
    tune: showTuning, info: showInfo, range: showRange, more: showMore, 'audio-settings': showAudioSettings,
    fullscreen() { setScene('landscape'); },
    collapse(player) {
      if (scene === 'landscape') { setScene(lastPortraitScene); return; }
      openModal(player, 'collapse', '暂时收起演唱？', `<p class="sheet-intro">正式播放器会返回曲库，并通过底部播放器继续控制。这份设计稿只展示演唱页面。</p><button type="button" class="sheet-action" data-action="close-sheet">继续留在舞台</button>`);
    },
    'hide-controls'(player) { player.phone.classList.add('controls-hidden'); player.phone.querySelector('.show-controls').focus({ preventScroll: true }); },
    'show-controls'(player) { player.phone.classList.remove('controls-hidden'); player.phone.querySelector('.landscape-toggle').focus({ preventScroll: true }); },
    'close-sheet'() { closeModal(); },
    'reset-tuning'(player) { player.pitch = 0; player.speed = 1; player.volume = .8; showTuning(player); },
    'toggle-loop'(player) { player.loop = !player.loop; showMore(player); },
    'apply-range'(player) {
      const start = Number(activeModal.layer.querySelector('[data-range-boundary="start"]').value);
      const end = Number(activeModal.layer.querySelector('[data-range-boundary="end"]').value);
      if (end <= start) { activeModal.layer.querySelector('.range-error').hidden = false; return; }
      player.range = [start, end]; player.position = start; renderActions(player); closeModal(); toast(player, '已选段 · 再唱一遍喜欢的旋律');
    },
    'clear-range'(player) { player.range = null; renderActions(player); closeModal(); toast(player, '已恢复整首练唱'); },
    record(player) {
      if (player.recording === 'recording') endRecording(player);
      else if (player.recording === 'countdown') cancelCountdown(player);
      else startRecording(player);
    },
    'cancel-countdown': cancelCountdown,
    'record-again'(player) { closeModal(); startRecording(player); },
  };

  gallery.addEventListener('click', (event) => {
    const button = event.target.closest('[data-action]');
    if (!button || button.disabled) return;
    const phone = button.closest('.phone');
    if (!phone) return;
    const player = players.get(phone.dataset.theme);
    const action = button.dataset.action;
    if (player.recording !== 'idle' && button.hasAttribute('data-lock')) return;
    if (actions[action]) actions[action](player, button);
  });

  gallery.addEventListener('input', (event) => {
    const input = event.target;
    const player = players.get(input.closest('.phone')?.dataset.theme);
    if (!player) return;
    if (input.classList.contains('progress-input')) { player.position = Number(input.value); renderProgress(player); }
    if (input.dataset.setting && input.type === 'range') {
      const setting = input.dataset.setting;
      player[setting] = Number(input.value);
      const output = activeModal.layer.querySelector(`[data-output="${setting}"]`);
      output.textContent = setting === 'pitch' ? `${player.pitch > 0 ? '+' : ''}${player.pitch} 半音` : `${Math.round(player[setting] * 100)}%`;
    }
    if (input.type === 'range') fillRange(input);
  });

  gallery.addEventListener('change', (event) => {
    const input = event.target;
    const player = players.get(input.closest('.phone')?.dataset.theme);
    if (!player || input.tagName !== 'SELECT') return;
    if (input.dataset.setting === 'role') { player.role = input.value; renderActions(player); }
    if (input.dataset.setting === 'subtitles') { player.subtitles = input.value === 'on'; player.phone.classList.toggle('subtitles-off', !player.subtitles); }
    if (input.dataset.rangeBoundary) activeModal.layer.querySelector('.range-error').hidden = true;
  });

  document.addEventListener('keydown', (event) => {
    if (!activeModal) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      if (activeModal.kind === 'countdown') cancelCountdown(activeModal.player);
      else closeModal();
      return;
    }
    if (event.key !== 'Tab') return;
    const controls = [...activeModal.layer.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), a[href]')].filter((element) => element.getClientRects().length > 0);
    const first = controls[0];
    const last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  });

  for (const button of document.querySelectorAll('button[data-scene]')) button.addEventListener('click', () => setScene(button.dataset.scene));
  for (const button of document.querySelectorAll('button[data-theme]')) button.addEventListener('click', () => setMobileTheme(button.dataset.theme));
  document.getElementById('large-text').checked = params.get('font') === '1.8';
  document.getElementById('senior-targets').checked = params.get('senior') === '1';
  document.getElementById('large-text').addEventListener('change', applyPreviewOptions);
  document.getElementById('senior-targets').addEventListener('change', applyPreviewOptions);
  if (params.get('device') === '1') document.body.classList.add('device-view');
  setMobileTheme(mobileTheme);
  setScene(scene);
  applyPreviewOptions();
  for (const player of players.values()) renderActions(player);

  setInterval(() => {
    const now = performance.now();
    for (const player of players.values()) {
      const delta = Math.min((now - player.clock) / 1000, 1);
      player.clock = now;
      if (document.hidden || !player.playing || player.recording === 'countdown') continue;
      const before = Math.floor(player.position);
      const recordedBefore = Math.floor(player.recorded);
      player.position += delta * player.speed;
      if (player.recording === 'recording') player.recorded += delta;
      const end = player.range && player.recording === 'idle' ? player.range[1] : duration;
      if (player.position >= end) {
        if (player.recording === 'recording') { player.position = duration; endRecording(player); }
        else if (player.range || player.loop) player.position = player.range?.[0] ?? 0;
        else { player.position = duration; player.playing = false; renderActions(player); }
      }
      if (before !== Math.floor(player.position) || recordedBefore !== Math.floor(player.recorded)) renderProgress(player);
    }
  }, 250);
})();
