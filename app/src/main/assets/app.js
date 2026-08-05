/* ===== BBDown Android — 前端逻辑 v1.9.95 ===== */
/* 包含: 扫码登录、批量下载、多线程下载、任务管理、调试日志 */

/* ---------- 原生桥接 Promise 封装 ---------- */
let _bseq = 0;
const _bres = {};
window.__onBridge = function(reqId, result){
  const r = _bres[reqId];
  if(r){ delete _bres[reqId]; result.ok ? r.resolve(result.data) : r.reject(result.error); }
};
/* 生成登录信息签名，用于检测是否有实质变化（避免无意义的重渲染导致闪烁） */
function _loginInfoSig(info){
  if(!info) return 'none';
  const w = info.web || {}, t = info.tv || {};
  return [info.isLogin, info.uname||'', info.mid||'', info.face||'',
          w.isLogin||false, w.uname||'', w.mid||'', w.isVip||false,
          t.isLogin||false, t.uname||'', t.mid||'', t.isVip||false].join('|');
}
/* 接收后端推送的最新登录状态（checkLogin Phase 2 异步刷新） */
window.__onLoginUpdate = function(info){
  try{
    if(info.face) info.face = info.face.replace(/^http:\/\//, 'https://');
    if(info.isLogin && !info.uname){
      info.uname = (info.web && info.web.uname) || (info.tv && info.tv.uname) || '已登录';
    }
    // 仅在登录信息有实质变化时才重新渲染，避免页面闪烁
    const changed = _loginInfoSig(state._loginInfo) !== _loginInfoSig(info);
    state._loginInfo = info;
    jsLog('Login updated: ' + JSON.stringify(info) + (changed ? '' : ' (no change)'));
    updateStatusBar();
    if(changed && state.currentView === 'account' && typeof renderEditor === 'function'){
      renderEditor();
    }
  }catch(e){ jsLog('onLoginUpdate error: '+e); }
};
/* 接收后端推送的最新收藏夹列表（getFavFolders 异步刷新） */
window.__onFavFoldersUpdate = function(folders){
  try{
    state._favFolders = folders;
    const container = el('favFoldersContainer');
    if(container && folders){
      renderFavFolders(container, folders);
    }
  }catch(e){ jsLog('onFavFoldersUpdate error: '+e); }
};
function callBridge(method, ...args){
  return new Promise((resolve, reject)=>{
    const id = ++_bseq;
    _bres[id] = {resolve, reject};
    try{ AndroidBridge[method](id, ...args); }
    catch(e){ delete _bres[id]; reject(String(e)); }
  });
}
/* 带进度回调的桥接调用：用于批量解析等流式回传场景 */
const _progCbs = {};
window.__onBatchParseProgress = function(reqId, progress){
  const cb = _progCbs[reqId];
  if(cb) cb(progress);
};
function callBridgeProgress(method, ...args){
  // 最后一个参数是进度回调
  const onProgress = args.pop();
  // id 必须在 Promise 外部声明，否则 .finally() 回调无法访问（const 为块级作用域）
  const id = ++_bseq;
  return new Promise((resolve, reject)=>{
    _bres[id] = {resolve, reject};
    _progCbs[id] = onProgress;
    try{ AndroidBridge[method](id, ...args); }
    catch(e){ delete _bres[id]; delete _progCbs[id]; reject(String(e)); }
  }).finally(()=>{ delete _progCbs[id]; });
}
/* JS端日志 → 原生Logger */
function jsLog(msg){
  try{ AndroidBridge.jsLog(msg); }catch(e){}
}

/* ---------- 图片缓存（封面/头像） ----------
   通过原生桥接下载图片并缓存为 blob URL，避免 CORS 限制和重复网络请求。
   同一 URL 仅请求一次，命中缓存后直接复用 blob URL。
   LRU 淘汰策略：超过上限时释放最久未使用的 blob URL，防止内存泄漏。
   访问时将条目移到 Map 末尾（真正的 LRU），而非仅更新时间戳。 */
const _IMG_CACHE_MAX = 80; // 最大缓存条目数
const _IMG_ERR_RETRY_MS = 30000; // 错误条目 30 秒后可重试
const _IMG_ERR_MAX = 20; // 最多保留的错误条目数（防止错误条目占满缓存）
const _imgCache = new Map(); // url -> {blobUrl, loading:[cbs], err, errTime, ts}

/** 释放一条缓存中的 blob URL（如果存在），回收内存 */
function _revokeImgEntry(url){
  const entry = _imgCache.get(url);
  if(entry && entry.blobUrl){
    try { URL.revokeObjectURL(entry.blobUrl); } catch(_){}
    entry.blobUrl = '';
  }
}

/** 统计错误条目数量 */
function _countErrEntries(){
  let n = 0;
  for(const e of _imgCache.values()) if(e.err) n++;
  return n;
}

/** LRU 淘汰：当缓存超过上限时，释放最旧的可淘汰条目。
 *  淘汰优先级：过期错误条目 > 普通缓存条目 > 正在加载的条目。
 *  通过计数器防止所有条目都在加载时陷入死循环。 */
function _evictImgCache(){
  let guard = _imgCache.size + 10; // 安全阀，防止死循环
  while(_imgCache.size >= _IMG_CACHE_MAX && guard-- > 0){
    const oldest = _imgCache.keys().next().value;
    if(!oldest) break;
    const entry = _imgCache.get(oldest);
    if(!entry){ _imgCache.delete(oldest); continue; }
    // 优先淘汰已过期的错误条目
    if(entry.err && Date.now() - entry.errTime >= _IMG_ERR_RETRY_MS){
      _imgCache.delete(oldest);
      continue;
    }
    // 错误条目过多时强制淘汰（即使未过期）
    if(entry.err && _countErrEntries() > _IMG_ERR_MAX){
      _imgCache.delete(oldest);
      continue;
    }
    // 正在加载的条目：移到末尾，稍后再淘汰
    if(entry.loading && entry.loading.length > 0){
      _imgCache.delete(oldest);
      _imgCache.set(oldest, entry);
      continue;
    }
    // 普通缓存条目：释放 blob URL 并删除
    _revokeImgEntry(oldest);
    _imgCache.delete(oldest);
  }
}

/** LRU 触摸：将条目移到 Map 末尾，标记为最近使用 */
function _touchImgEntry(url){
  const entry = _imgCache.get(url);
  if(entry){
    _imgCache.delete(url);
    entry.ts = Date.now();
    _imgCache.set(url, entry);
  }
}

function cachedImgUrl(url){
  if(!url) return Promise.resolve('');
  const hit = _imgCache.get(url);
  if(hit){
    if(hit.blobUrl){
      _touchImgEntry(url); // LRU: 移到末尾
      return Promise.resolve(hit.blobUrl);
    }
    if(hit.err){
      // 错误条目超时后允许重试
      if(Date.now() - hit.errTime < _IMG_ERR_RETRY_MS) return Promise.resolve('');
      // 超时，清除错误状态，重新加载
      _imgCache.delete(url);
      // 继续往下走，重新加载
    } else {
      // 正在加载，加入等待队列
      return new Promise(res=>hit.loading.push(res));
    }
  }
  _evictImgCache(); // 插入前检查是否需要淘汰
  const entry = {blobUrl:'', loading:[], err:false, errTime:0, ts:Date.now()};
  _imgCache.set(url, entry);
  return new Promise(resolve=>{
    // 优先通过原生桥接下载（无 CORS 限制），失败时回退到 fetch
    callBridge('fetchImage', url)
      .then(res=>{
        try{
          const bytes = atob(res.data);
          const arr = new Uint8Array(bytes.length);
          for(let i=0;i<bytes.length;i++) arr[i]=bytes.charCodeAt(i);
          const blob = new Blob([arr], {type: res.type||'image/jpeg'});
          entry.blobUrl = URL.createObjectURL(blob);
          resolve(entry.blobUrl);
          entry.loading.forEach(cb=>cb(entry.blobUrl));
          entry.loading = [];
        }catch(e){
          // 解码失败，标记错误（可重试）
          entry.err = true; entry.errTime = Date.now(); resolve('');
          entry.loading.forEach(cb=>cb('')); entry.loading=[];
        }
      })
      .catch(()=>{
        // 桥接失败，尝试 fetch 作为回退（可能受 CORS 限制）
        fetch(url, {mode:'cors', credentials:'omit'})
          .then(r=> r.ok ? r.blob() : Promise.reject('status'+r.status))
          .then(b=>{
            try{
              entry.blobUrl = URL.createObjectURL(b);
              resolve(entry.blobUrl);
              entry.loading.forEach(cb=>cb(entry.blobUrl));
              entry.loading = [];
            }catch(e){ entry.err=true; entry.errTime=Date.now(); resolve(''); entry.loading.forEach(cb=>cb('')); entry.loading=[]; }
          })
          .catch(()=>{
            entry.err = true; entry.errTime = Date.now(); resolve('');
            entry.loading.forEach(cb=>cb('')); entry.loading=[];
          });
      });
  });
}
/** 同步获取已缓存的图片URL（未缓存时返回空字符串，触发异步缓存后通过回调更新DOM） */
function imgSrc(url){
  if(!url) return '';
  const hit = _imgCache.get(url);
  if(hit && hit.blobUrl){ _touchImgEntry(url); return hit.blobUrl; }
  if(hit && hit.err){
    // 错误超时可重试
    if(Date.now() - hit.errTime < _IMG_ERR_RETRY_MS) return '';
    _imgCache.delete(url);
  }
  // 未缓存：返回空，异步加载后由 applyCachedImages 更新DOM
  if(!_imgCache.has(url)) cachedImgUrl(url);
  return '';
}

/** 渲染后扫描所有带 data-img-url 的元素，异步加载图片并更新背景/源 */
function applyCachedImages(container){
  if(!container) container = document;
  container.querySelectorAll('[data-img-url]').forEach(el=>{
    const url = el.dataset.imgUrl;
    if(!url) return;
    const hit = _imgCache.get(url);
    if(hit && hit.blobUrl){
      _touchImgEntry(url); // LRU: 移到末尾
      if(el.tagName === 'IMG') el.src = hit.blobUrl;
      else el.style.backgroundImage = `url('${hit.blobUrl}')`;
      el.removeAttribute('data-img-url');
    } else if(hit && hit.err && Date.now() - hit.errTime < _IMG_ERR_RETRY_MS){
      el.removeAttribute('data-img-url');
    } else {
      cachedImgUrl(url).then(blobUrl=>{
        if(blobUrl){
          if(el.tagName === 'IMG') el.src = blobUrl;
          else el.style.backgroundImage = `url('${blobUrl}')`;
        }
        el.removeAttribute('data-img-url');
      });
    }
  });
}

/** 清除全部图片缓存（释放所有 blob URL），用于内存压力或页面切换 */
function clearImgCache(){
  for(const url of _imgCache.keys()){
    _revokeImgEntry(url);
  }
  _imgCache.clear();
}

/* ---------- 主题切换 ---------- */
function applyTheme(theme){
  if(theme === 'system'){
    // 使用 JS 检测系统主题，显式设置 data-theme 属性
    // 比 CSS 媒体查询更可靠，兼容所有 WebView 版本
    const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    const resolved = prefersDark ? 'dark' : 'light';
    document.documentElement.setAttribute('data-theme', resolved);
    jsLog('Theme applied: system → ' + resolved);
  } else {
    document.documentElement.setAttribute('data-theme', theme);
    jsLog('Theme applied: ' + theme);
  }
}
function setTheme(theme){
  state._theme = theme;
  applyTheme(theme);
  // 同步保存到 localStorage，供下次启动时在 <head> 中立即读取（避免闪烁）
  try{ localStorage.setItem('theme', theme); }catch(e){}
  try{ AndroidBridge.setSetting(++_bseq, 'theme', theme); _bres[_bseq]={resolve(){},reject(){}}; }catch(e){}
  // 同步更新原生 WebView 背景色，避免 HTML 重新渲染前出现背景色不一致
  try{ AndroidBridge.updateNativeTheme(theme); }catch(e){}
}
// 监听系统主题变化，跟随系统模式下实时切换
if(window.matchMedia){
  const mql = window.matchMedia('(prefers-color-scheme: dark)');
  const onSysThemeChange = (e)=>{
    if(state._theme === 'system'){
      applyTheme('system');
      // 系统主题变化时同步更新原生 WebView 背景色
      try{ AndroidBridge.updateNativeTheme('system'); }catch(e){}
      jsLog('System theme changed, re-applying system theme');
    }
  };
  if(mql.addEventListener) mql.addEventListener('change', onSysThemeChange);
  else if(mql.addListener) mql.addListener(onSysThemeChange);
}

/* ---------- 自定义下拉选择器 ---------- */
function csHTML(id, options, currentVal){
  const cur = options.find(o=>o[0]===currentVal);
  const curLabel = cur ? cur[1] : (options.length?options[0][1]:'');
  return `<div class="cs-wrap" data-cs="${id}">
    <button class="cs-btn" type="button" onclick="csToggle('${id}')">
      <span class="cs-label" id="csLabel_${id}">${esc(curLabel)}</span>
      <svg class="cs-arrow" viewBox="0 0 10 10"><path fill="currentColor" d="M5 7L1 3h8z"/></svg>
    </button>
  </div>`;
}
/** 视频流专用下拉：按钮显示简短标签，展开后双行布局 */
function csStreamShortLabel(s){
  return [s.dfn, s.codecs, s.fps?s.fps+'fps':''].filter(x=>x).join(' ');
}
function csStreamHTML(id, streams, currentVal){
  const cur = streams.find(s=>s.key===currentVal);
  const shortLabel = cur ? csStreamShortLabel(cur) : (streams.length?csStreamShortLabel(streams[0]):'');
  window._csStreamData = window._csStreamData||{};
  window._csStreamData[id] = streams;
  return `<div class="cs-wrap" data-cs="${id}" data-cs-stream="1">
    <button class="cs-btn" type="button" onclick="csToggle('${id}')">
      <span class="cs-label" id="csLabel_${id}">${esc(shortLabel)}</span>
      <svg class="cs-arrow" viewBox="0 0 10 10"><path fill="currentColor" d="M5 7L1 3h8z"/></svg>
    </button>
  </div>`;
}
function csToggle(id){
  const wrap = document.querySelector(`[data-cs="${id}"]`);
  if(!wrap) return;
  const btn = wrap.querySelector('.cs-btn');
  document.querySelectorAll('.cs-panel').forEach(p=>p.remove());
  document.querySelectorAll('.cs-btn.open').forEach(b=>b.classList.remove('open'));
  const isStream = wrap.dataset.csStream === '1';
  const panel = document.createElement('div');
  panel.className = 'cs-panel';
  if(isStream){
    const streams = (window._csStreamData||{})[id]||[];
    const curVal = window._csVal[id];
    panel.innerHTML = streams.map(s=>{
      const shortLabel = csStreamShortLabel(s);
      // 第二行：编码 · 分辨率 · 码率 · 大小，逐段拼接避免尾部残留分隔符
      const subParts = [];
      if(s.codecs) subParts.push(`<span class="cos-codec">${esc(s.codecs)}</span>`);
      if(s.res) subParts.push(esc(s.res));
      if(s.bandwidth) subParts.push(esc(s.bandwidth));
      if(s.size) subParts.push(esc(s.size));
      return `
      <div class="cs-option cs-option-stream ${s.key===curVal?'sel':''}" onclick="csSelectStream('${id}','${esc(s.key)}','${esc(shortLabel)}')">
        <div class="cos-body">
          <div class="cos-main"><span class="cos-dfn">${esc(s.dfn)}</span>${s.fps?`<span class="cos-fps">${esc(s.fps)}fps</span>`:''}</div>
          <div class="cos-sub">${subParts.join(' · ')}</div>
        </div>
        <svg class="cs-check" viewBox="0 0 14 14"><path fill="currentColor" d="M5.5 10L2 6.5l1-1L5.5 8l5-5 1 1z"/></svg>
      </div>`;
    }).join('');
  } else {
    const opts = window._csOpts[id];
    if(!opts) return;
    const curVal = window._csVal[id];
    panel.innerHTML = opts.map(([v,l])=>`
      <div class="cs-option ${v===curVal?'sel':''}" onclick="csSelect('${id}','${esc(v)}','${esc(l)}')">
        <span>${esc(l)}</span>
        <svg class="cs-check" viewBox="0 0 14 14"><path fill="currentColor" d="M5.5 10L2 6.5l1-1L5.5 8l5-5 1 1z"/></svg>
      </div>`).join('');
  }
  wrap.appendChild(panel);
  btn.classList.add('open');
  setTimeout(()=>{
    const handler = (e)=>{
      if(!wrap.contains(e.target)){ panel.remove(); btn.classList.remove('open'); document.removeEventListener('click',handler); }
    };
    document.addEventListener('click',handler);
  },0);
}
function csSelectStream(id, val, shortLabel){
  window._csVal[id] = val;
  const lbl = document.getElementById('csLabel_'+id);
  if(lbl) lbl.textContent = shortLabel;
  const cb = window._csCallback[id];
  if(cb) cb(val);
  document.querySelectorAll('.cs-panel').forEach(p=>p.remove());
  document.querySelectorAll('.cs-btn.open').forEach(b=>b.classList.remove('open'));
}
function csSelect(id, val, label){
  window._csVal[id] = val;
  const lbl = document.getElementById('csLabel_'+id);
  if(lbl) lbl.textContent = label;
  const cb = window._csCallback[id];
  if(cb) cb(val);
  document.querySelectorAll('.cs-panel').forEach(p=>p.remove());
  document.querySelectorAll('.cs-btn.open').forEach(b=>b.classList.remove('open'));
}

/* ---------- 全局状态 ---------- */
window._csOpts = {};
window._csVal = {};
window._csCallback = {};
const state = {
  currentView: 'explorer',
  selectedTaskId: null,
  _skipAutoSelect: false,
  parsed: null,
  videoInfo: null,
  playInfo: null,
  selectedPages: new Set(),
  selectedQn: null,
  selectedCodec: 'avc',
  selectedAudio: 'm4a',
  selectedVideoStream: null,  // 视频流选择器：格式 "id|codecs"，如 "116|hevc"
  selectedAudioStream: null,  // 音频流选择器：流的 id
  batchQn: 'auto',        // 批量下载默认清晰度（auto=每个视频自动取最高可用）
  downloadMode: 'all',
  downloadDanmaku: false,
  skipMux: false,
  skipSubtitle: false,
  skipCover: false,
  skipAi: true,
  videoAscending: false,
  audioAscending: false,
  filePattern: '{pageTitle}',
  filePatternMultiPage: '{pageTitle} P{pageNumber}',
  filePatternCollection: '{collectionIndex}. {pageTitle}',
  filePatternCollectionMultiPage: '{collectionIndex}. {videoTitle} P{pageNumber}',
  forceHttp: false,
  delayPerPage: 0,         // 合集/批量下载时分P间隔(秒)
  showAdvanced: false,
  // 批量下载
  batchResults: null,
  _batchCollectionTitle: '',  // 批量下载时若来自合集/系列，保存其名称用于创建子文件夹
  // 收藏夹
  _favFolders: null,
  _favList: null,
  _favFolderTitle: '',
  _favMediaId: '',
  _favPage: 1,
  _favTotal: 0,
  // 合集检测
  _collectionInfo: null,
  _collectionType: '',  // "season"=合集, "series"=系列
  _collectionLoading: false,
  _collectionVideos: null,
  // UP主搜索
  _searchKeyword: '',
  _searchResults: null,
  _searchType: 'upper',
  _videoSearchResults: null,
  _upperMid: '',
  _upperName: '',
  _upperFace: '',
  _upperOfficialType: -1,
  _upperVipType: 0,
  _upperVipStatus: 0,
  _upperVideos: null,
  _upperPage: 1,
  _upperTotal: 0,
  _upperReturnView: '',  // 进入UP投稿视频时的来源视图，退出时返回该视图
  _upperView: 'list', // 'list' or 'detail'
  // 关注列表
  _followings: null,
  _followTotal: 0,
  _followPage: 1,
  _followTags: null,       // 关注分组(分类)列表
  _followTagsLoaded: false, // 分组是否已尝试加载(区分空列表和未加载)
  _followTagId: 0,         // 当前选中的分组，0=全部
  _followOrderType: 'attention', // 排序：attention=最常访问(最近比较在意)，空=按关注时间
  // WEB/TV 双登录，不再使用单一登录类型切换
  settings: {},
  _lastUrl: '',
  _pollTimer: null,
  _tasksStatusSig: '', // 状态签名（任务状态分布，用于检测分类成员变化）
  _loginInfo: null,
  _taskFilter: 'all', // 'all' | 'running' | 'done' | 'failed'
  _taskManageMode: false, // 任务管理模式
  _taskSelected: new Set(), // 管理模式下选中的任务ID
  _taskSwipeAnchor: null, // 滑动选择锚点（上次滑动选中的任务ID，用于范围选择）
  _prevTaskStatus: null, // 上次详情页任务状态（用于检测状态变化）
  _taskDetailSig: '', // 详情页签名（检测进度/状态是否变化，避免无意义DOM更新导致闪烁）
  _firstPoll: true, // 首次轮询标志（启动时不自动选中任务，显示列表视图）
};

// 滑动事件时间戳：防止 touchend 后浏览器触发的 click 事件干扰新渲染的 DOM
var _lastSwipeTime = 0;

/* ---------- DOM 工具 ---------- */
const el = id => document.getElementById(id);

const editorBody = () => el('editorBody');
const sidebarBody = () => el('sidebarBody');

/* ---------- 二级页面顶部返回头 ---------- */
/* 在已有的 #tabs 栏中渲染返回按钮（不新增导航栏） */
let _subnavKey = '';
function subHeader(title, onclickExpr, rawTitle){
  const key = title + '|' + onclickExpr;
  if(key !== _subnavKey){
    _subnavKey = key;
    const bar = document.getElementById('tabs');
    if(bar){
      bar.innerHTML = `<button class="sn-back" onclick="${onclickExpr}">
        <svg viewBox="0 0 24 24" width="14" height="14"><path fill="currentColor" d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z"/></svg>
        <span>返回</span>
      </button>
      <span class="sn-title">${rawTitle ? title : esc(title)}</span>`;
    }
  }
  return '';
}

/** 清除 #tabs 栏中的返回按钮（主页面渲染时调用） */
function clearSubnav(){
  if(_subnavKey !== ''){
    _subnavKey = '';
    const bar = document.getElementById('tabs');
    if(bar){
      bar.innerHTML = '';
    }
  }
}

/* ---------- 布局检测 ---------- */
function detectLayout(){
  const narrow = window.innerWidth < 768;
  document.getElementById('app').classList.toggle('layout-narrow', narrow);
  if(!narrow) document.getElementById('app').classList.remove('show-sidebar');
}

/* ---------- 视图切换 ---------- */
function switchView(view){
  state.currentView = view;
  document.querySelectorAll('.ab-btn').forEach(b=>b.classList.toggle('active', b.dataset.view===view));
  const titles = {explorer:'任务列表', addtask:'新建下载', search:'搜索', account:'账号', settings:'设置', help:'帮助'};
  el('sidebarTitle').textContent = titles[view] || view;
  document.getElementById('app').classList.remove('show-sidebar');
  // 切换到不含图片的视图时，清除图片缓存释放内存（blob URL 会被回收）
  if(view === 'settings' || view === 'help'){
    clearImgCache();
  }
  renderSidebar();
  renderEditor();
  if(view==='account') initAccountView();
  // 切换到任务列表时立即轮询一次，确保显示最新任务状态
  if(view === 'explorer'){
    try { forcePollNow(); } catch(e) {}
  }
}

/* ---------- 侧边栏渲染 ---------- */
function renderSidebar(){
  const sb = sidebarBody();
  if(state.currentView === 'explorer'){
    renderTaskList(sb);
  } else if(state.currentView === 'addtask'){
    sb.innerHTML = `<div class="sb-empty">在右侧输入链接开始下载<br>支持空格分割批量下载</div>`;
  } else if(state.currentView === 'search'){
    sb.innerHTML = `<div class="sb-section-title">搜索</div><div class="sb-empty">输入关键词搜索UP主或视频<br>支持直接下载</div>`;
  } else if(state.currentView === 'account'){
    sb.innerHTML = `<div class="sb-empty">账号管理</div>
    <div style="padding:12px 16px"><button class="btn btn-sec" style="width:100%;font-size:12px" onclick="switchView('settings')">下载设置</button></div>`;
  } else if(state.currentView === 'settings'){
    sb.innerHTML = `<div class="sb-section-title">下载设置</div><div class="sb-empty">在右侧编辑设置</div>`;
  } else if(state.currentView === 'help'){
    sb.innerHTML = `<div class="sb-section-title">说明</div><div class="sb-empty">查看使用说明</div>`;
  }
}

function renderTaskList(sb){
  const tasks = state._tasks || [];
  const filter = state._taskFilter || 'all';
  const manageMode = state._taskManageMode;

  const filtered = sortTasksFiltered(tasks, filter);

  if(tasks.length === 0){
    sb.innerHTML = `<div class="sb-empty">暂无下载任务<br>点击左侧「新建下载」开始</div>`;
    return;
  }

  // 分类筛选栏与管理按钮由 renderTaskTabsBar() 统一渲染到 #tabs 栏
  sb.innerHTML = `<div class="sb-items">${renderTaskItemsHTML(filtered, manageMode)}</div>`;

  bindTaskItemEvents(sb, manageMode);
  applyCachedImages(sb);
}

/** 从 URL 中提取 BV 号 */
function extractBv(url){
  if(!url) return '';
  const m = String(url).match(/BV([0-9a-zA-Z]{10})/);
  return m ? ('BV' + m[1]) : '';
}

/** 仅生成任务项 HTML（不包含标签栏和管理栏），用于局部刷新 */
function renderTaskItemsHTML(filtered, manageMode){
  if(filtered.length === 0) return `<div class="sb-empty" style="padding:30px 16px">该分类下暂无任务</div>`;
  return filtered.map((t, idx)=>{
    const sel = state.selectedTaskId === t.taskId ? ' selected' : '';
    const mgrSel = state._taskSelected.has(t.taskId) ? ' mgr-selected' : '';
    const statusClass = taskStatusClass(t);
    const statusDot = taskStatusDot(t);
    const hasCover = t.pic && t.pic.length > 0;
    const cachedUrl = imgSrc(t.pic);
    const bgStyle = hasCover && cachedUrl ? `background-image:url('${esc(cachedUrl)}')` : '';
    const dataAttr = hasCover && !cachedUrl ? `data-img-url="${esc(t.pic)}"` : '';
    // 下载中任务显示进度条覆盖层
    const pct = Math.round((t.progress||0)*100);
    const progressBar = (t.status===1||t.status===2||t.status===3) ? `<div class="ti-progress" style="width:${pct}%"></div>` : '';
    // BV号标注
    const bv = extractBv(t.url);
    const bvLabel = bv ? `<div class="ti-bv">${esc(bv)}</div>` : '';

    return `<div class="task-item${sel} task-${statusClass}${hasCover?' has-cover':''}${mgrSel}" data-id="${t.taskId}">
      ${manageMode ? `<div class="ti-check${state._taskSelected.has(t.taskId)?' checked':''}"></div>` : ''}
      ${hasCover ? `<div class="ti-bg" ${dataAttr} style="${bgStyle}"></div>` : ''}
      <div class="ti-shade"></div>
      <div class="ti-index">#${idx+1}</div>
      ${bvLabel}
      ${progressBar}
      <div class="ti-body">
        <div class="ti-title">${esc(t.title)}</div>
        <div class="ti-sub">${statusDot}${t.pageCount>1?` · ${t.pageCount}P`:''}</div>
      </div>
    </div>`;
  }).join('');
}

/** 绑定任务项事件 */
function bindTaskItemEvents(sb, manageMode){
  sb.querySelectorAll('.task-item').forEach(n=>{
    // 滑动手势：管理模式下左右滑动进行范围选择，非管理模式下左滑或右滑都进入管理模式
    let startX = 0, currentX = 0, swiping = false, isSwipe = false;
    n.addEventListener('touchstart', e=>{
      startX = e.touches[0].clientX;
      currentX = startX;
      swiping = true;
      isSwipe = false;
    }, {passive:true});
    n.addEventListener('touchmove', e=>{
      if(!swiping) return;
      currentX = e.touches[0].clientX;
      const dx = currentX - startX;
      if(Math.abs(dx) > 10) isSwipe = true;
      // 管理模式和非管理模式都允许左右双向滑动
      if(Math.abs(dx) < 80){
        n.style.transform = `translateX(${dx * 0.5}px)`;
        n.style.transition = 'none';
      }
    }, {passive:true});
    n.addEventListener('touchend', e=>{
      if(!swiping) return;
      swiping = false;
      const dx = currentX - startX;
      n.style.transform = '';
      n.style.transition = '';
      if(!isSwipe) return; // 点击而非滑动，交给 onclick 处理
      // 标记刚发生滑动，防止 touchend 后浏览器触发的 click 事件干扰新渲染的 DOM
      _lastSwipeTime = Date.now();
      if(manageMode){
        // 管理模式：滑动执行范围选择
        if(dx < -50 || dx > 50){
          swipeSelectTask(n.dataset.id);
        }
      } else {
        // 非管理模式：左滑或右滑都进入管理模式
        if(dx < -50 || dx > 50){
          enterTaskManage(n.dataset.id);
        }
      }
    }, {passive:true});

    if(manageMode){
      n.onclick = (e)=>{
        // 滑动后 500ms 内忽略 click（防止 enterTaskManage 重新渲染 DOM 后 click 命中新元素）
        if(isSwipe || Date.now() - _lastSwipeTime < 500) return;
        toggleTaskSelect(n.dataset.id);
      };
    } else {
      n.onclick = (e)=>{
        if(isSwipe || Date.now() - _lastSwipeTime < 500) return;
        state.selectedTaskId = n.dataset.id;
        state._prevTaskStatus = null;
        state._taskDetailSig = '';
        // 仅更新选中状态，避免全量重建
        sb.querySelectorAll('.task-item.selected').forEach(el=>el.classList.remove('selected'));
        n.classList.add('selected');
        renderEditor();
      };
    }
  });
}

/**
 * 滑动选择任务（管理模式下的范围选择）
 * 逻辑：
 * - 如果有锚点（_taskSwipeAnchor），选择锚点与当前任务之间的所有任务
 * - 锚点始终更新为当前滑动的任务
 * - 如果没有锚点（比如点击进入管理模式后首次滑动），仅选中当前任务
 */
function swipeSelectTask(taskId){
  const tasks = state._tasks || [];
  const filter = state._taskFilter || 'all';
  const filtered = sortTasksFiltered(tasks, filter);
  const ids = filtered.map(t=>t.taskId);

  const anchorId = state._taskSwipeAnchor;
  if(anchorId && anchorId !== taskId){
    // 范围选择：选中锚点到当前任务之间的所有任务（含两端）
    const anchorIdx = ids.indexOf(anchorId);
    const currentIdx = ids.indexOf(taskId);
    if(anchorIdx >= 0 && currentIdx >= 0){
      const start = Math.min(anchorIdx, currentIdx);
      const end = Math.max(anchorIdx, currentIdx);
      for(let i = start; i <= end; i++){
        state._taskSelected.add(ids[i]);
      }
    } else {
      // 锚点不在当前筛选列表中，仅选中当前任务
      state._taskSelected.add(taskId);
    }
  } else {
    // 没有锚点或滑动的是锚点本身：仅选中当前任务
    state._taskSelected.add(taskId);
  }
  // 更新锚点为当前滑动的任务
  state._taskSwipeAnchor = taskId;

  // 原地更新 UI，避免闪烁
  const containers = [sidebarBody(), editorBody()];
  containers.forEach(sb=>{
    if(!sb) return;
    sb.querySelectorAll('.task-item').forEach(item=>{
      const id = item.dataset.id;
      const checked = state._taskSelected.has(id);
      const check = item.querySelector('.ti-check');
      if(check) check.classList.toggle('checked', checked);
      item.classList.toggle('mgr-selected', checked);
    });
    updateManageBarState(sb);
  });
}

/** 设置任务过滤选项卡（局部刷新，避免闪烁） */
function setTaskFilter(filter){
  state._taskFilter = filter;
  // 更新 tabs 栏筛选按钮激活状态
  const bar = document.getElementById('tabs');
  if(bar){
    bar.querySelectorAll('.tft-btn').forEach(btn=>{
      const onclick = btn.getAttribute('onclick') || '';
      btn.classList.toggle('active', onclick.includes(`'${filter}'`));
    });
  }
  const tasks = state._tasks || [];
  const filtered = sortTasksFiltered(tasks, filter);
  // 重建侧边栏任务项
  const sb = sidebarBody();
  const sbItems = sb.querySelector('.sb-items');
  if(sbItems){
    sbItems.innerHTML = renderTaskItemsHTML(filtered, state._taskManageMode);
    bindTaskItemEvents(sb, state._taskManageMode);
    applyCachedImages(sb);
  }
  // 重建主内容区任务项（移动端显示任务卡）
  const eb = editorBody();
  const ebItems = eb.querySelector('.sb-items');
  if(ebItems){
    ebItems.innerHTML = renderTaskItemsHTML(filtered, state._taskManageMode);
    bindTaskItemEvents(eb, state._taskManageMode);
    applyCachedImages(eb);
  }
}

/** 主内容区的任务过滤（与侧边栏联动） */
function setTaskFilterMain(filter){
  setTaskFilter(filter);
}

/** 排序+过滤（供局部刷新复用） */
function sortTasksFiltered(tasks, filter){
  function sortTasks(arr){
    // 使用 seq 排序确保批量/合集下载按添加顺序排列，不乱序
    const getSeq = t => t.seq || 0;
    const done = arr.filter(t=>t.status===4).sort((a,b)=>(b.finishTime||b.createTime)-(a.finishTime||a.createTime));
    const running = arr.filter(t=>[1,2,3].includes(t.status)).sort((a,b)=>getSeq(a)-getSeq(b));
    const waiting = arr.filter(t=>t.status===0).sort((a,b)=>getSeq(a)-getSeq(b));
    const paused = arr.filter(t=>t.status===7).sort((a,b)=>getSeq(b)-getSeq(a));
    const failed = arr.filter(t=>[5,6].includes(t.status)).sort((a,b)=>getSeq(b)-getSeq(a));
    return [...done, ...running, ...waiting, ...paused, ...failed];
  }
  return sortTasks(tasks.filter(t=>{
    if(filter === 'all') return true;
    if(filter === 'running') return t.isRunning || t.status === 0 || t.status === 7;
    if(filter === 'done') return t.status === 4;
    if(filter === 'failed') return t.status === 5 || t.status === 6;
    return true;
  }));
}

/** 进入任务管理模式 */
function enterTaskManage(taskId){
  state._taskManageMode = true;
  state._taskSelected.clear();
  if(taskId) state._taskSelected.add(taskId);
  state._taskSwipeAnchor = taskId || null; // 记录首个滑动选中的任务作为锚点
  // 同时更新侧边栏和主内容区（移动端主内容区显示任务卡）
  renderTaskList(sidebarBody());
  renderEditor();
}

/** 退出任务管理模式 */
function exitTaskManage(){
  state._taskManageMode = false;
  state._taskSelected.clear();
  state._taskSwipeAnchor = null;
  // 显式清除 tabs 栏中的管理按钮（clearSubnav 不会清除管理模式设置的内容）
  const bar = document.getElementById('tabs');
  if(bar) bar.innerHTML = '';
  _subnavKey = '';
  renderTaskList(sidebarBody());
  renderEditor();
  // 退出管理模式后立即刷新任务状态
  try { forcePollNow(); } catch(e) {}
}

/** 渲染任务列表顶部 tabs 栏：非管理模式显示分类筛选，管理模式显示管理按钮 */
function renderTaskTabsBar(){
  const bar = document.getElementById('tabs');
  if(!bar) return;
  const manageMode = state._taskManageMode;
  if(manageMode){
    const selCount = state._taskSelected.size;
    // 判断是否已全选（用于切换全选按钮图标与文案）
    const filteredTasks = sortTasksFiltered(state._tasks||[], state._taskFilter||'all');
    const isAllSelected = filteredTasks.length > 0 && state._taskSelected.size === filteredTasks.length;
    // 判断选中任务的状态分布，决定哪些按钮可用
    const selTasks = (state._tasks||[]).filter(t=>state._taskSelected.has(t.taskId));
    const canPause = selTasks.some(t=>t.isRunning || t.status===0);
    const canResumeOrRetry = selTasks.some(t=>t.status===7 || t.status===5 || t.status===6);
    bar.innerHTML = `<button class="sn-back" onclick="exitTaskManage()" title="退出管理">
      <svg viewBox="0 0 24 24" width="14" height="14"><path fill="currentColor" d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z"/></svg>
    </button>
    <span class="sn-title">已选 ${selCount} 项</span>
    <div class="sn-actions">
      <button class="sn-btn-icon ${selCount>0&&canPause?'':'disabled'}" onclick="pauseSelectedTasks()" title="暂停">
        <svg viewBox="0 0 24 24" width="16" height="16"><path fill="currentColor" d="M6 4h4v16H6zM14 4h4v16h-4z"/></svg>
      </button>
      <button class="sn-btn-icon sn-btn-icon-primary ${selCount>0&&canResumeOrRetry?'':'disabled'}" onclick="resumeOrRetrySelectedTasks()" title="继续/重试">
        <svg viewBox="0 0 24 24" width="16" height="16"><path fill="currentColor" d="M8 5v14l11-7z"/></svg>
      </button>
      <button class="sn-btn-icon" onclick="toggleSelectAllTasks()" title="${isAllSelected?'取消全选':'全选'}">
        <svg viewBox="0 0 24 24" width="16" height="16"><path fill="currentColor" d="${isAllSelected?'M19 5v14H5V5h14m0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z':'M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-9 14l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z'}"/></svg>
      </button>
      <button class="sn-btn-icon sn-btn-icon-danger ${selCount>0?'':'disabled'}" onclick="deleteSelectedTasks()" title="删除">
        <svg viewBox="0 0 24 24" width="16" height="16"><path fill="currentColor" d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>
      </button>
    </div>`;
    _subnavKey = '__manage__';
  } else {
    const tasks = state._tasks || [];
    const filter = state._taskFilter || 'all';
    const counts = {
      all: tasks.length,
      running: tasks.filter(t=>t.isRunning||t.status===0||t.status===7).length,
      done: tasks.filter(t=>t.status===4).length,
      failed: tasks.filter(t=>t.status===5||t.status===6).length,
    };
    const tabs = [['all','全部'],['running','进行中'],['done','已完成'],['failed','失败']];
    bar.innerHTML = `<div class="task-filter-tabs in-tabs">${tabs.map(([k,label])=>
      `<button class="tft-btn${filter===k?' active':''}" onclick="setTaskFilter('${k}')">${label}${counts[k]>0?`<span class="tft-count">${counts[k]}</span>`:''}</button>`
    ).join('')}</div>`;
    _subnavKey = '__taskfilter__';
  }
}

/** 切换单个任务选中状态（原地更新，避免闪烁） */
function toggleTaskSelect(taskId){
  if(state._taskSelected.has(taskId)) state._taskSelected.delete(taskId);
  else state._taskSelected.add(taskId);
  state._taskSwipeAnchor = taskId; // 点击也更新锚点
  // 已选0项时自动退出管理模式
  if(state._taskSelected.size === 0){
    exitTaskManage();
    return;
  }
  // 同时更新侧边栏和主内容区中的任务卡
  const containers = [sidebarBody(), editorBody()];
  containers.forEach(sb=>{
    if(!sb) return;
    const item = sb.querySelector(`.task-item[data-id="${taskId}"]`);
    if(item){
      const check = item.querySelector('.ti-check');
      if(check) check.classList.toggle('checked', state._taskSelected.has(taskId));
      item.classList.toggle('mgr-selected', state._taskSelected.has(taskId));
    }
    updateManageBarState(sb);
  });
}

/** 全选/取消全选（原地更新，避免闪烁） */
function toggleSelectAllTasks(){
  const tasks = state._tasks || [];
  const filter = state._taskFilter || 'all';
  const filtered = sortTasksFiltered(tasks, filter);
  if(state._taskSelected.size === filtered.length){
    state._taskSelected.clear();
  } else {
    state._taskSelected = new Set(filtered.map(t=>t.taskId));
  }
  state._taskSwipeAnchor = null; // 全选/取消全选后重置锚点
  // 已选0项时自动退出管理模式
  if(state._taskSelected.size === 0){
    exitTaskManage();
    return;
  }
  const containers = [sidebarBody(), editorBody()];
  containers.forEach(sb=>{
    if(!sb) return;
    sb.querySelectorAll('.task-item').forEach(item=>{
      const id = item.dataset.id;
      const checked = state._taskSelected.has(id);
      item.classList.toggle('mgr-selected', checked);
      const check = item.querySelector('.ti-check');
      if(check) check.classList.toggle('checked', checked);
    });
    updateManageBarState(sb);
  });
}

/** 更新管理栏的选中计数和删除按钮状态（tabs栏中的管理按钮） */
function updateManageBarState(sb){
  const title = document.querySelector('#tabs .sn-title');
  if(title) title.textContent = `已选 ${state._taskSelected.size} 项`;
  const delBtn = document.querySelector('#tabs .sn-btn-icon-danger');
  if(delBtn) delBtn.classList.toggle('disabled', state._taskSelected.size === 0);
}

/** 删除选中的任务 */
async function deleteSelectedTasks(){
  const ids = Array.from(state._taskSelected);
  if(ids.length === 0) return;
  showConfirm({
    title: '删除任务',
    message: `确认删除 ${ids.length} 个任务？此操作不可撤销。`,
    icon: '<svg viewBox="0 0 24 24" width="28" height="28"><path fill="var(--warn)" d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
    confirmText: '删除',
    cancelText: '取消',
    onConfirm: async () => {
      for(const id of ids){
        try{ await callBridge('removeTask', id); }catch(e){}
      }
      state._taskSelected.clear();
      state._taskManageMode = false;
      state.selectedTaskId = null;
      state._skipAutoSelect = true;
      toast(`已删除 ${ids.length} 个任务`, 'ok');
      const freshTasks = await callBridge('getTasks');
      state._tasks = freshTasks;
      state._tasksStructSig = '';
      state._tasksContentSig = '';
      state._tasksStatusSig = '';
      // 同时更新侧边栏和主内容区
      renderTaskList(sidebarBody());
      renderEditor();
    }
  });
}

/** 批量暂停选中的任务 */
async function pauseSelectedTasks(){
  const ids = Array.from(state._taskSelected);
  if(ids.length === 0) return;
  let count = 0;
  for(const id of ids){
    try{
      await callBridge('pauseTask', id);
      count++;
    }catch(e){}
  }
  toast(`已暂停 ${count} 个任务`, 'ok');
  const freshTasks = await callBridge('getTasks');
  state._tasks = freshTasks;
  state._tasksStructSig = '';
  state._tasksContentSig = '';
  renderTaskTabsBar();
  renderTaskList(sidebarBody());
  renderEditor();
}

/** 批量继续/重试选中的任务（暂停→继续，失败/取消→重试，自动判断） */
async function resumeOrRetrySelectedTasks(){
  const ids = Array.from(state._taskSelected);
  if(ids.length === 0) return;
  const tasks = state._tasks || [];
  let count = 0;
  for(const id of ids){
    try{
      const t = tasks.find(t=>t.taskId===id);
      if(!t) continue;
      // 暂停状态→继续；失败/取消状态→重试
      if(t.status === 7){
        await callBridge('resumeTask', id);
      } else if(t.status === 5 || t.status === 6){
        await callBridge('retryTask', id);
      } else {
        continue;
      }
      count++;
    }catch(e){}
  }
  toast(`已继续/重试 ${count} 个任务`, 'ok');
  state._taskSelected.clear();
  state._taskManageMode = false;
  const freshTasks = await callBridge('getTasks');
  state._tasks = freshTasks;
  state._tasksStructSig = '';
  state._tasksContentSig = '';
  const bar = document.getElementById('tabs');
  if(bar) bar.innerHTML = '';
  _subnavKey = '';
  renderTaskList(sidebarBody());
  renderEditor();
}

/** 任务状态CSS类名 */
function taskStatusClass(t){
  if(t.status===0) return 'pending';
  if(t.status===1) return 'parsing';
  if(t.status===2) return 'downloading';
  if(t.status===3) return 'muxing';
  if(t.status===4) return 'done';
  if(t.status===5) return 'failed';
  if(t.status===6) return 'canceled';
  if(t.status===7) return 'paused';
  return 'pending';
}

/** 任务状态圆点（染色指示） */
function taskStatusDot(t){
  if(t.status===0) return '<span class="ti-dot dot-pending" title="等待">等待</span>';
  if(t.status===1) return '<span class="ti-dot dot-parsing" title="解析中">解析</span>';
  if(t.status===2) return `<span class="ti-dot dot-downloading" title="下载中">${Math.round(t.progress*100)}%</span>`;
  if(t.status===3) return '<span class="ti-dot dot-muxing" title="合并中">合并</span>';
  if(t.status===4) return '<span class="ti-dot dot-done" title="完成">完成</span>';
  if(t.status===5) return '<span class="ti-dot dot-failed" title="失败">失败</span>';
  if(t.status===6) return '<span class="ti-dot dot-canceled" title="取消">取消</span>';
  if(t.status===7) return '<span class="ti-dot dot-paused" title="暂停">暂停</span>';
  return '';
}

function taskIcon(t){
  // 所有有封面的任务都显示封面缩略图（不限状态）
  if(t.pic){
    const cu = imgSrc(t.pic);
    const fallback = (t.status===5?errIcon():(t.status>=4?okIcon():spinIcon())).replace(/'/g, "\\'");
    if(cu){
      return '<img src="' + esc(cu) + '" class="ti-cover" onerror="this.parentElement.innerHTML=\'' + fallback + '\'">';
    } else {
      return '<img data-img-url="' + esc(t.pic) + '" class="ti-cover" onerror="this.parentElement.innerHTML=\'' + fallback + '\'">';
    }
  }
  if(t.status===5) return errIcon();
  if(t.status>=4) return okIcon();
  return spinIcon();
}

/* ---------- 编辑器渲染 ---------- */
function renderEditor(){
  const eb = editorBody();
  // 先清除二级导航栏，二级页面会在渲染时通过 subHeader() 重新设置
  clearSubnav();
  switch(state.currentView){
    case 'explorer': renderExplorer(eb); break;
    case 'addtask': renderAddTask(eb); break;
    case 'search': renderSearch(eb); break;
    case 'account': renderAccount(eb); break;
    case 'settings': renderSettings(eb); break;
    case 'help': renderHelp(eb); break;
  }
  // 异步加载编辑区中的图片
  applyCachedImages(eb);
}

/* ===== 资源管理器(任务列表) ===== */
function renderExplorer(eb){
  const tasks = state._tasks || [];
  // 管理模式下始终显示任务列表，不进入任务详情
  const sel = state._taskManageMode ? null : tasks.find(t=>t.taskId===state.selectedTaskId);
  if(!sel){
    if(tasks.length===0){
      eb.innerHTML = `<div class="view">
        <div class="welcome-hero">
          <div class="logo-big">
            <svg viewBox="0 0 24 24" width="56" height="56"><rect x="2" y="2" width="20" height="20" rx="5" fill="#FAD4E0"/><path fill="#FFFFFF" d="M11,7 L13,7 L13,12 L14.5,10.5 L16,12 L12,16 L8,12 L9.5,10.5 L11,12 Z"/><rect x="7.5" y="16.8" width="9" height="2" rx="0.5" fill="#FFFFFF"/></svg>
          </div>
          <h1>BBDown</h1>
          <div class="ver">B站视频下载器</div>
          <p class="lead" style="margin-top:14px">点击左侧「新建下载」开始</p>
        </div>
      </div>`;
      return;
    }
    // 使用与侧边栏相同的新任务卡设计（封面背景+状态滤镜+管理模式）
    const filter = state._taskFilter || 'all';
    const manageMode = state._taskManageMode;
    const filtered = sortTasksFiltered(tasks, filter);
    // tabs 栏：非管理模式显示分类筛选，管理模式显示管理按钮
    renderTaskTabsBar();
    eb.innerHTML = `<div class="sb-items main-task-list">${renderTaskItemsHTML(filtered, manageMode)}</div>`;
    bindTaskItemEvents(eb, manageMode);
    applyCachedImages(eb);
    return;
  }
  renderTaskDetail(eb, sel);
}

function renderTaskDetail(eb, t){
  const pct = Math.round(t.progress*100);
  const stateText = ['等待中','解析中','下载中','合并中','已完成','失败','已取消','已暂停'][t.status];
  const stateClass = ['','running','running','running','success','failed','','paused'][t.status] || '';
  eb.innerHTML = `<div class="view">
    ${subHeader('任务详情', "backToTaskList()")}
    <h1>${esc(t.title)}</h1>
    <p class="detail-meta">${t.pageCount>1?t.pageCount+' 个分P · ':''}创建于 ${fmtTime(t.createTime)}</p>
    ${t.pic ? (()=>{ const cu=imgSrc(t.pic); return cu ? `<img class="detail-cover" src="${esc(cu)}" onerror="this.style.display='none'">` : `<img class="detail-cover" data-img-url="${esc(t.pic)}" onerror="this.style.display='none'">`; })() : ''}
    <span class="state-pill state-${stateClass}">${t.status===2?spinDot():''} ${stateText}</span>
    <div class="progress-wrap">
      <div class="progress-bar"><div class="progress-fill ${t.status===4?'done':''}${t.status===5?'fail':''}" style="width:${pct}%"></div></div>
      <div class="progress-info">
        <span>${pct}% ${t.status===2?'· '+fmtSpeed(t.speed):''}</span>
        <span>${t.status===4 ? (t.totalBytes>0 ? fmtBytes(t.totalBytes) : '已完成') : (fmtBytes(t.downloadedBytes) + ' / ' + (t.totalBytes>0?fmtBytes(t.totalBytes):'?'))}</span>
      </div>
    </div>
    ${t.errorMsg ? `<div class="task-err" style="color:var(--error);font-size:12px;margin:10px 0;padding:10px;background:var(--error-bg);border-radius:4px">${esc(t.errorMsg)}</div>` : ''}
    <div class="stat-grid">
      <div class="stat-card"><div class="sc-label">状态</div><div class="sc-value">${stateText}</div></div>
      <div class="stat-card"><div class="sc-label">分P数</div><div class="sc-value">${t.pageCount}</div></div>
    </div>
    ${t.outputFiles && t.outputFiles.length ? `
      <h2>输出文件</h2>
      ${t.outputFiles.map(f=>{
        const parts = f.split('/');
        const fileName = parts.pop() || f;
        const dirPath = parts.join('/');
        return `
        <div class="file-item">
          <svg viewBox="0 0 24 24" width="16" height="16" style="flex:0 0 auto"><path fill="var(--fg-dim)" d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/></svg>
          <div class="fi-content">
            <span class="fi-name">${esc(fileName)}</span>
            ${dirPath ? `<span class="fi-path-full">${esc(dirPath)}</span>` : ''}
          </div>
          <button class="btn btn-sec" style="font-size:11px;padding:4px 10px;flex:0 0 auto" onclick="openFile('${esc(f)}')">打开</button>
        </div>`;
      }).join('')}
    ` : ''}
    <div class="detail-actions">
      ${(t.isRunning || t.status===0) ? `<button class="btn btn-primary da-toggle" onclick="pauseTask('${t.taskId}')"><svg viewBox="0 0 24 24" width="14" height="14"><path fill="currentColor" d="M6 5h4v14H6zm8 0h4v14h-4z"/></svg>暂停</button>` : ''}
      ${t.status===7 ? `<button class="btn btn-primary da-toggle" onclick="resumeTask('${t.taskId}')"><svg viewBox="0 0 24 24" width="14" height="14"><path fill="currentColor" d="M8 5v14l11-7z"/></svg>继续下载</button>` : ''}
      ${(t.status===5||t.status===6) ? `<button class="btn btn-primary da-toggle" onclick="retryTask('${t.taskId}')"><svg viewBox="0 0 24 24" width="14" height="14"><path fill="currentColor" d="M17.65 6.35A7.96 7.96 0 0 0 12 4a8 8 0 1 0 7.74 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4z"/></svg>重试</button>` : ''}
      ${t.isRunning ? `<button class="btn btn-danger da-sec" onclick="cancelTask('${t.taskId}')">取消下载</button>` : ''}
      ${(!t.isRunning && t.status!==4) ? `<button class="btn da-delete" onclick="removeTask('${t.taskId}')"><svg viewBox="0 0 24 24" width="14" height="14"><path fill="currentColor" d="M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>删除</button>` : ''}
    </div>
  </div>`;
  // 异步检查权限状态
  setTimeout(()=>checkStoragePermissionStatus(), 100);
}

/* ===== 新建下载(支持批量) ===== */
function renderAddTask(eb){
  // 如果有批量解析结果
  if(state.batchResults){
    renderBatchFlow(eb);
    return;
  }
  // 如果有单个解析结果
  if(state.parsed){
    renderEditor_ParseFlow(eb);
    return;
  }
  eb.innerHTML = `<div class="view">
    <h1>新建下载任务</h1>
    <p class="lead">输入B站视频链接，支持批量(空格分割)</p>
    <div class="form-section">
      <div class="parse-box">
        <input type="text" id="f_url" placeholder="BV链接 / EP链接 / 空格分割批量" value="${esc(state._lastUrl||'')}">
        <button class="btn btn-primary" onclick="doParse()">解析</button>
      </div>
      <div style="font-size:11px;color:var(--fg-dim);margin-top:6px">
        单个: <code>BV1aE5T65Ebt</code> · 批量: <code>BV1xxx BV2yyy BV3zzz</code>
      </div>
    </div>
  </div>`;
}

async function doParse(){
  const url = el('f_url').value.trim();
  if(!url){ toast('请输入链接','err'); return; }
  state._lastUrl = url;
  state.parsed = null; state.videoInfo = null; state.playInfo = null; state.batchResults = null;
  state._collectionInfo = null; state._collectionLoading = false; state._collectionVideos = null;
  editorBody().innerHTML = `<div class="view"><h1>新建下载任务</h1><div class="loading-pulse">${spinIcon()} 正在解析链接…</div></div>`;
  try{
    // 从已保存的设置加载默认下载参数（如 downloadMode），确保编辑器初始选中与设置一致
    applyDefaultSettings();
    // 检测是否为批量(包含空格)
    const urls = url.split(/\s+/).filter(s=>s.length>0);
    if(urls.length > 1){
      // 批量解析（流式增量回传，降低内存峰值，避免大列表闪退）
      state.batchResults = [];
      state._batchCollectionTitle = '';
      editorBody().innerHTML = `<div class="view"><h1>新建下载任务</h1><div class="loading-pulse">${spinIcon()} 正在批量解析 ${urls.length} 个链接… (<span id="batchProg">0</span>/${urls.length})</div></div>`;
      await callBridgeProgress('parseBatch', url, (prog)=>{
        if(prog.items && prog.items.length){
          state.batchResults = state.batchResults.concat(prog.items);
        }
        const progEl = document.getElementById('batchProg');
        if(progEl) progEl.textContent = prog.processed || state.batchResults.length;
        if(prog.done && state.currentView === 'addtask') renderEditor();
      });
      if(state.currentView === 'addtask' && (!state.batchResults || state.batchResults.length === 0)) renderEditor();
      return;
    }
    // 合并解析流程：parseUrl + getVideoInfo + getPlayInfo 一次性完成，仅渲染一次
    const parsed = await callBridge('parseUrl', url);
    state.parsed = parsed;
    const info = await callBridge('getVideoInfo', parsed.type, parsed.aid, parsed.epId, parsed.bvid||'');
    state.videoInfo = info;
    state.selectedPages = new Set(info.pages.map(p=>p.index));
    // 内联获取播放信息（避免调用 fetchPlayInfo 导致额外渲染）
    const first = info.pages[0];
    try{
      const play = await callBridge('getPlayInfo', first.aid, first.cid, first.epid||'', info.isBangumi||false, info.isCheese||false);
      state.playInfo = play;
      if(play.videos && play.videos.length){
        const best = play.videos.reduce((a,b)=> (parseInt(b.id)>parseInt(a.id)?b:a));
        state.selectedQn = best.id;
      }
    }catch(e){ toast('获取清晰度失败：'+e,'warn'); }
    // 一次性渲染最终结果
    if(state.currentView === 'addtask') renderEditor();
    // 合集检测（使用局部更新，不刷新整个页面）
    if(info.bvid){
      checkCollectionForVideo(info.bvid);
    }
  }catch(e){ toast('解析失败：'+e,'err'); state.parsed=null; if(state.currentView === 'addtask') renderEditor(); }
}

async function fetchPlayInfo(){
  if(!state.videoInfo) return;
  const first = state.videoInfo.pages[0];
  try{
    const play = await callBridge('getPlayInfo', first.aid, first.cid, first.epid||'', state.videoInfo.isBangumi||false, state.videoInfo.isCheese||false);
    state.playInfo = play;
    if(play.videos && play.videos.length){
      const best = play.videos.reduce((a,b)=> (parseInt(b.id)>parseInt(a.id)?b:a));
      state.selectedQn = best.id;
    }
    if(state.currentView === 'addtask') renderEditor();
  }catch(e){ toast('获取清晰度失败：'+e,'warn'); }
  // 合集检测（不影响主流程）
  if(state.videoInfo.bvid){
    checkCollectionForVideo(state.videoInfo.bvid);
  }
}

/* ===== 共用：视频流数据构建 =====
   sources: 播放信息中的视频流数组（play.videos）或标准清晰度映射
   用 id|codecs 去重，统一返回 {key, dfn, codecs, fps, res, bandwidth, size} 结构
*/
function buildVStreamData(sources){
  const seen = new Set();
  const result = [];
  for(const v of sources){
    const key = v.id + '|' + (v.codecs||'').toLowerCase();
    if(seen.has(key)) continue;
    seen.add(key);
    result.push({
      key: key,
      dfn: v.dfn || v.id,
      codecs: (v.codecs||'').toUpperCase(),
      fps: v.fps || '',
      res: v.res || '',
      bandwidth: v.bandwidth ? v.bandwidth+'kbps' : '',
      size: ''
    });
  }
  return result;
}

/* ===== 共用：音频流数据构建 =====
   返回 [audioOpts, audioVal] 二元组
   sources: 播放信息中的音频流数组（play.audios），按 codecs+bandwidth 去重
*/
function buildAudioOpts(sources){
  const seen = new Set();
  const list = [];
  for(const a of sources){
    const key = a.codecs + '_' + a.bandwidth;
    if(seen.has(key)) continue;
    seen.add(key);
    list.push(a);
  }
  const opts = list.map(a=>{
    const bw = a.bandwidth ? ` ${a.bandwidth}kbps` : '';
    const label = (a.codecs === 'E-AC-3' ? '杜比全景声' : (a.codecs||'')) + bw;
    return [a.id||a.codecs, label];
  });
  return opts;
}

/* ===== 下载选项（单页/批量共用）=====
   cfg: {
     modeKey, modeOpts, modeCb,
     vstreamKey, vstreamData, vstreamVal, vstreamCb,   // 视频流（可空）
     audioKey, audioOpts, audioVal, audioCb,             // 音频流下拉（可空；为空时回退药丸按钮）
     showStreams,                                       // false=隐藏视频流/音频流选择（批量下载页）
     btnLabel, btnAction
   }
*/
function registerDownloadOpts(cfg){
  // 内容模式
  window._csOpts[cfg.modeKey] = cfg.modeOpts;
  window._csVal[cfg.modeKey] = state.downloadMode;
  window._csCallback[cfg.modeKey] = cfg.modeCb;
  // 视频流
  if(cfg.vstreamData && cfg.vstreamData.length){
    window._csStreamData = window._csStreamData||{};
    window._csStreamData[cfg.vstreamKey] = cfg.vstreamData;
    window._csVal[cfg.vstreamKey] = cfg.vstreamVal;
    window._csCallback[cfg.vstreamKey] = cfg.vstreamCb;
  }
  // 音频流下拉
  if(cfg.audioOpts && cfg.audioOpts.length){
    window._csOpts[cfg.audioKey] = cfg.audioOpts;
    window._csVal[cfg.audioKey] = cfg.audioVal;
    window._csCallback[cfg.audioKey] = cfg.audioCb;
  }
}

function downloadOptsHTML(cfg){
  // showStreams:false 时隐藏视频流/音频流选择（批量下载页不使用）
  const showStreams = cfg.showStreams !== false;
  const hasVstream = cfg.vstreamData && cfg.vstreamData.length;
  const showVstream = showStreams && hasVstream && (state.downloadMode==='all' || state.downloadMode==='video_only');
  const showAudio = showStreams && (state.downloadMode==='all' || state.downloadMode==='video_only' || state.downloadMode==='audio_only');
  const useAudioDropdown = cfg.audioOpts && cfg.audioOpts.length;
  return `
    <div class="compact-opts">
      <div class="compact-field">
        <label>内容</label>
        ${csHTML(cfg.modeKey, cfg.modeOpts, state.downloadMode)}
      </div>
      ${showVstream ? `
        <div class="compact-field">
          <label>视频流</label>
          ${csStreamHTML(cfg.vstreamKey, cfg.vstreamData, cfg.vstreamVal)}
        </div>
      ` : ''}
      ${showAudio ? `
      <div class="compact-field">
        <label>音频流</label>
        ${useAudioDropdown
          ? csHTML(cfg.audioKey, cfg.audioOpts, cfg.audioVal)
          : `<div class="pill-group">
          <button class="${state.selectedAudio==='auto'?'active':''}" onclick="state.selectedAudio='auto';renderEditor()">自动</button>
          <button class="${state.selectedAudio==='m4a'?'active':''}" onclick="state.selectedAudio='m4a';renderEditor()">M4A</button>
          <button class="${state.selectedAudio==='flac'?'active':''}" onclick="state.selectedAudio='flac';renderEditor()">FLAC</button>
          <button class="${state.selectedAudio==='e-ac-3'?'active':''}" onclick="state.selectedAudio='e-ac-3';renderEditor()">E-AC-3</button>
        </div>`}
      </div>
      ` : ''}
    </div>
    ${(state.downloadMode==='all' || state.downloadMode==='video_only' || state.downloadMode==='audio_only' || state.downloadMode==='subtitle_only') ? `
      <div class="adv-toggle" onclick="state.showAdvanced=!state.showAdvanced;renderEditor()">
        <span>${state.showAdvanced?'▼':'▶'} 高级选项</span>
      </div>
      ${state.showAdvanced ? `
        <div class="opt-list compact">
          ${state.downloadMode==='all' ? `<label class="opt-item"><input type="checkbox" class="checkbox" ${state.downloadDanmaku?'checked':''} onchange="state.downloadDanmaku=this.checked"><span>附带弹幕</span></label>` : ''}
          ${state.downloadMode==='all' ? `<label class="opt-item"><input type="checkbox" class="checkbox" ${state.skipMux?'checked':''} onchange="state.skipMux=this.checked"><span>跳过混流</span></label>` : ''}
          ${state.downloadMode==='all' ? `<label class="opt-item"><input type="checkbox" class="checkbox" ${state.skipSubtitle?'checked':''} onchange="state.skipSubtitle=this.checked"><span>跳过字幕</span></label>` : ''}
          ${state.downloadMode==='all' ? `<label class="opt-item"><input type="checkbox" class="checkbox" ${state.skipCover?'checked':''} onchange="state.skipCover=this.checked"><span>跳过封面</span></label>` : ''}
          ${(state.downloadMode==='all' || state.downloadMode==='subtitle_only') ? `<label class="opt-item"><input type="checkbox" class="checkbox" ${state.skipAi?'checked':''} onchange="state.skipAi=this.checked"><span>跳过AI字幕</span></label>` : ''}
          ${(state.downloadMode==='all' || state.downloadMode==='video_only') ? `<label class="opt-item"><input type="checkbox" class="checkbox" ${state.videoAscending?'checked':''} onchange="state.videoAscending=this.checked"><span>视频升序(最小体积)</span></label>` : ''}
          ${(state.downloadMode==='all' || state.downloadMode==='audio_only') ? `<label class="opt-item"><input type="checkbox" class="checkbox" ${state.audioAscending?'checked':''} onchange="state.audioAscending=this.checked"><span>音频升序(最小体积)</span></label>` : ''}
          <label class="opt-item"><input type="checkbox" class="checkbox" ${state.forceHttp?'checked':''} onchange="state.forceHttp=this.checked"><span>强制HTTP</span></label>
          ${
            (() => {
              if(cfg.isCollection){
                const isSeries = state._collectionType === 'series';
                return `
                  <div class="opt-item" style="flex-direction:column;align-items:stretch;gap:4px">
                    <span style="font-size:11px">${isSeries ? '单P命名模板' : '合集命名模板'}</span>
                    <input type="text" class="text-input" style="font-size:11px;padding:4px 8px" value="${esc(isSeries ? state.filePattern : state.filePatternCollection)}" onchange="${isSeries ? "state.filePattern=this.value" : "state.filePatternCollection=this.value"}">
                  </div>
                  <div class="opt-item" style="flex-direction:column;align-items:stretch;gap:4px">
                    <span style="font-size:11px">${isSeries ? '多P命名模板' : '合集多P命名模板'}</span>
                    <input type="text" class="text-input" style="font-size:11px;padding:4px 8px" value="${esc(isSeries ? state.filePatternMultiPage : state.filePatternCollectionMultiPage)}" onchange="${isSeries ? "state.filePatternMultiPage=this.value" : "state.filePatternCollectionMultiPage=this.value"}">
                  </div>
                  ${filePatternVarsTable()}`;
              } else {
                const multiPage = state.videoInfo && state.videoInfo.pages && state.videoInfo.pages.length > 1;
                if(multiPage){
                  return `
                    <div class="opt-item" style="flex-direction:column;align-items:stretch;gap:4px">
                      <span style="font-size:11px">多P命名模板</span>
                      <input type="text" class="text-input" style="font-size:11px;padding:4px 8px" value="${esc(state.filePatternMultiPage)}" onchange="state.filePatternMultiPage=this.value">
                    </div>
                    ${filePatternVarsTable()}`;
                } else {
                  return `
                    <div class="opt-item" style="flex-direction:column;align-items:stretch;gap:4px">
                      <span style="font-size:11px">单P命名模板</span>
                      <input type="text" class="text-input" style="font-size:11px;padding:4px 8px" value="${esc(state.filePattern)}" onchange="state.filePattern=this.value">
                    </div>
                    ${filePatternVarsTable()}`;
                }
              }
            })()
          }
        </div>
      ` : ''}
    ` : ''}
    <div class="btn-row" style="margin-top:12px">
      <button class="btn btn-primary btn-block" onclick="${cfg.btnAction}">${cfg.btnLabel}</button>
    </div>
  `;
}

function renderEditor_ParseFlow(eb){
  if(!state.videoInfo){
    eb.innerHTML = `<div class="view"><h1>新建下载任务</h1><div class="loading-pulse">${spinIcon()} 正在获取视频信息…</div></div>`;
    return;
  }
  const info = state.videoInfo;
  const play = state.playInfo;
  // 视频流：复用共用构建函数
  let vStreamData = (play && play.videos) ? buildVStreamData(play.videos) : null;
  let vstreamVal = state.selectedVideoStream;
  if(vStreamData && vStreamData.length){
    if(!vstreamVal) vstreamVal = vStreamData[0].key;
    state.selectedVideoStream = vstreamVal;
  }
  // 音频流：复用共用构建函数
  let audioOpts = (play && play.audios) ? buildAudioOpts(play.audios) : null;
  let audioVal = state.selectedAudioStream;
  if(audioOpts && audioOpts.length){
    if(!audioVal) audioVal = audioOpts[0][0];
    state.selectedAudioStream = audioVal;
  }
  // 注册并渲染共用下载选项
  const modeOpts = [['all','完整下载(含字幕/封面/弹幕)'],['video_only','仅视频(有声不含附加)'],['audio_only','仅音频'],['subtitle_only','仅字幕'],['cover_only','仅封面'],['danmaku_only','仅弹幕']];
  const optsCfg = {
    modeKey: 'sel_mode', modeOpts: modeOpts,
    modeCb: (v)=>{ state.downloadMode=v; renderEditor(); },
    vstreamKey: 'sel_vstream', vstreamData: vStreamData, vstreamVal: vstreamVal,
    vstreamCb: (v)=>{ state.selectedVideoStream=v; },
    audioKey: 'sel_audio', audioOpts: audioOpts, audioVal: audioVal,
    audioCb: (v)=>{ state.selectedAudioStream=v; state.selectedAudio=v; },
    btnLabel: '开始下载', btnAction: 'doDownload()'
  };
  registerDownloadOpts(optsCfg);
  eb.innerHTML = `<div class="view">
    ${subHeader('新建下载任务', "state.parsed=null;state.videoInfo=null;state.playInfo=null;state._lastUrl='';state.batchResults=null;renderEditor()")}
    <h1>新建下载任务</h1>
    <div class="vc-list">
      ${videoCardHTML({title:info.title, pic:info.pic, bvid:info.bvid, duration:info.duration, pubdate:info.pubTime, ownerName:info.upperName, ownerFace:info.ownerFace, officialType:info.officialType, vipType:info.vipType, vipStatus:info.vipStatus, play:info.play, danmaku:info.danmaku}, {showUpper:true})}
    </div>
    <div id="collectionBannerContainer">${collectionBannerHTML()}</div>
    ${info.pages.length > 1 ? `
      <div class="compact-row">
        <label class="field-label" style="margin:0;white-space:nowrap">分P选择</label>
        <span style="font-size:11px;color:var(--fg-dim)">${state.selectedPages.size}/${info.pages.length} 已选</span>
        <button class="btn btn-sec" style="font-size:10px;padding:3px 8px;margin-left:auto" onclick="selectAllPages(true)">全选</button>
        <button class="btn btn-sec" style="font-size:10px;padding:3px 8px" onclick="selectAllPages(false)">取消</button>
      </div>
      <div class="page-list">
        ${info.pages.map(p=>`
          <div class="page-item" data-idx="${p.index}">
            <div class="pi-check"><input type="checkbox" class="checkbox" ${state.selectedPages.has(p.index)?'checked':''} data-idx="${p.index}"></div>
            <div class="pi-body"><div class="pi-title">P${p.index} ${esc(p.title)}</div></div>
          </div>`).join('')}
      </div>
    ` : ''}
    ${downloadOptsHTML(optsCfg)}
  </div>`;
  eb.querySelectorAll('.page-item').forEach(n=>{
    n.onclick = (e)=>{
      if(e.target.tagName !== 'INPUT'){
        const cb = n.querySelector('input');
        cb.checked = !cb.checked;
      }
      const idx = parseInt(n.dataset.idx);
      if(state.selectedPages.has(idx)) state.selectedPages.delete(idx);
      else state.selectedPages.add(idx);
    };
  });
}

/* ===== 批量下载流程 ===== */
function renderBatchFlow(eb){
  const results = state.batchResults;
  if(!results || !results.length){
    state.batchResults = null;
    renderAddTask(eb);
    return;
  }
  const okItems = results.filter(r=>r.ok);
  const failItems = results.filter(r=>!r.ok);
  // 注册并渲染共用下载选项（与下载页使用同一套 registerDownloadOpts/downloadOptsHTML）
  const modeOpts = [['all','完整下载(含字幕/封面/弹幕)'],['video_only','仅视频(有声不含附加)'],['audio_only','仅音频'],['subtitle_only','仅字幕'],['cover_only','仅封面'],['danmaku_only','仅弹幕']];
  const optsCfg = {
    modeKey: 'batch_mode', modeOpts: modeOpts,
    modeCb: (v)=>{ state.downloadMode=v; renderEditor(); },
    // 批量下载不提供音视频流选择，统一按设置默认值下载
    showStreams: false,
    btnLabel: '批量下载全部', btnAction: 'doBatchDownload()',
    isCollection: true
  };
  registerDownloadOpts(optsCfg);
  eb.innerHTML = `<div class="view">
    ${subHeader('批量下载', "state.batchResults=null;state._lastUrl='';state._batchCollectionTitle='';renderEditor()")}
    <h1>批量下载</h1>
    <p class="lead">共 ${results.length} 个视频，${okItems.length} 个解析成功${failItems.length>0?'，'+failItems.length+'个失败':''}</p>
    ${failItems.length>0 ? `
      <div style="margin-bottom:12px">
        ${failItems.map(r=>`<div class="batch-error-item" style="color:var(--error);font-size:12px;padding:4px 0">${esc(r.url)}: ${esc(r.error)}</div>`).join('')}
      </div>
    ` : ''}
    <div class="vc-list">
      ${okItems.map((r,i)=>videoCardHTML({
        title:r.title, pic:r.pic, bvid:r.bvid, url:r.url,
        duration:r.duration, pubdate:r.pubdate||r.pubTime||0,
        ownerName:r.ownerName||r.upperName||r.author||'',
        ownerFace:r.ownerFace||r.face||'',
        officialType:r.officialType!=null?r.officialType:-1,
        vipType:r.vipType||0, vipStatus:r.vipStatus||0,
        play:r.play||0, danmaku:r.danmaku||0,
        pages:r.pages
      }, {index:i+1, showUpper:true})).join('')}
    </div>
    ${downloadOptsHTML(optsCfg)}
  </div>`;
}

// 根据视频类型选择文件命名模板：系列用普通模板，合集用合集模板
function getFilePattern(isMultiPage, isCollection){
  if(isCollection && state._collectionType === 'series'){
    return isMultiPage ? state.filePatternMultiPage : state.filePattern;
  }
  if(isCollection){
    return isMultiPage ? state.filePatternCollectionMultiPage : state.filePatternCollection;
  }
  return isMultiPage ? state.filePatternMultiPage : state.filePattern;
}

async function doBatchDownload(){
  const okItems = state.batchResults.filter(r=>r.ok);
  if(!okItems.length){ toast('没有可下载的视频','err'); return; }
  jsLog('doBatchDownload: ' + okItems.length + ' items, mode=' + state.downloadMode + ' qn=' + state.batchQn);
  const tasks = okItems.map((r, idx)=>({
    url: r.url,
    title: r.title,
    pic: r.pic,
    pages: r.pages.map(p=>({index:p.index,aid:p.aid,cid:p.cid,epid:p.epid,title:p.title,duration:p.duration})),
    collectionIndex: r.collectionIndex || (idx + 1),
    videoId: state.batchQn || 'auto',
    preferCodec: state.selectedCodec,
    preferAudio: state.selectedAudioStream || state.selectedAudio,
    downloadMode: state.downloadMode,
    downloadDanmaku: state.downloadDanmaku,
    skipMux: state.skipMux,
    skipSubtitle: state.skipSubtitle,
    skipCover: state.skipCover,
    skipAi: state.skipAi,
    videoAscending: state.videoAscending,
    audioAscending: state.audioAscending,
    filePattern: getFilePattern(r.pages.length > 1, !!state._batchCollectionTitle),
    forceHttp: state.forceHttp,
    collectionTitle: state._batchCollectionTitle || '',
    delayPerPage: state.delayPerPage || 0,
    isCheese: r.isCheese || false,
    // 字段名回退（修复5）：合集视频卡片用 ownerName/pubdate，普通解析用 upperName/pubTime
    upperName: r.upperName || r.ownerName || '',
    desc: r.desc || '',
    pubTime: r.pubTime || r.pubdate || 0,
    bvid: r.bvid || '',
    ownerMid: r.ownerMid || '',
  }));
  try{
    await callBridge('addBatchTasks', JSON.stringify(tasks));
    toast(`已添加 ${tasks.length} 个下载任务`,'ok');
    state.batchResults = null;
    state._batchCollectionTitle = '';
    state.parsed = null;
    state.videoInfo = null;
    state.playInfo = null;
    state._lastUrl = '';
    // 先获取最新任务列表，再切换视图，避免闪烁
    const freshTasks = await callBridge('getTasks');
    state._tasks = freshTasks;
    state._tasksStructSig = '';
    state._tasksContentSig = '';
    state._tasksStatusSig = '';
    state.selectedTaskId = null;
    state._skipAutoSelect = true;
    switchView('explorer');
  }catch(e){ toast('批量下载失败：'+e,'err'); }
}

function selectAllPages(all){
  if(all) state.selectedPages = new Set(state.videoInfo.pages.map(p=>p.index));
  else state.selectedPages = new Set();
  renderEditor();
}

/** 从已保存的设置中加载默认下载参数到 state，供一键下载使用 */
function applyDefaultSettings(){
  const s = state.settings || {};
  state.selectedCodec = s.preferCodec || 'avc';
  state.selectedAudio = s.preferAudio || 'm4a';
  state.batchQn = s.batchQn || 'auto';
  state.downloadMode = s.downloadMode || 'all';
  state.downloadDanmaku = (s.downloadDanmaku||'false')==='true';
  state.skipMux = (s.skipMux||'false')==='true';
  state.skipSubtitle = (s.skipSubtitle||'false')==='true';
  state.skipCover = (s.skipCover||'false')==='true';
  state.skipAi = (s.skipAi||'true')==='true';
  state.videoAscending = (s.videoAscending||'false')==='true';
  state.audioAscending = (s.audioAscending||'false')==='true';
  state.forceHttp = (s.forceHttp||'false')==='true';
  state.delayPerPage = parseInt(s.delayPerPage)||0;
  state.filePattern = s.filePattern || '{pageTitle}';
  state.filePatternMultiPage = s.filePatternMultiPage || '{pageTitle} P{pageNumber}';
  state.filePatternCollection = s.filePatternCollection || '{collectionIndex}. {pageTitle}';
  state.filePatternCollectionMultiPage = s.filePatternCollectionMultiPage || '{collectionIndex}. {videoTitle} P{pageNumber}';
  state.showAdvanced = false;
  jsLog('applyDefaultSettings: mode=' + state.downloadMode + ' codec=' + state.selectedCodec +
    ' audio=' + state.selectedAudio + ' skipMux=' + state.skipMux + ' skipSub=' + state.skipSubtitle +
    ' skipCover=' + state.skipCover + ' skipAi=' + state.skipAi + ' danmaku=' + state.downloadDanmaku +
    ' forceHttp=' + state.forceHttp);
}

async function doDownload(){
  if(!state.videoInfo){ toast('请先解析','warn'); return; }
  const selected = state.videoInfo.pages.filter(p=>state.selectedPages.has(p.index));
  if(!selected.length){ toast('请选择至少一个分P','err'); return; }
  const commonOpts = {
    url: state._lastUrl||'',
    pic: state.videoInfo.pic,
    videoId: state.selectedVideoStream ? state.selectedVideoStream.split('|')[0] : (state.selectedQn||'80'),
    preferCodec: state.selectedVideoStream ? state.selectedVideoStream.split('|')[1] : state.selectedCodec,
    preferAudio: state.selectedAudioStream || state.selectedAudio,
    downloadMode: state.downloadMode,
    downloadDanmaku: state.downloadDanmaku,
    skipMux: state.skipMux,
    skipSubtitle: state.skipSubtitle,
    skipCover: state.skipCover,
    skipAi: state.skipAi,
    videoAscending: state.videoAscending,
    audioAscending: state.audioAscending,
    filePattern: state.filePattern,
    forceHttp: state.forceHttp,
    isCheese: state.videoInfo.isCheese || false,
    upperName: state.videoInfo.upperName || '',
    desc: state.videoInfo.desc || '',
    pubTime: state.videoInfo.pubTime || 0,
    bvid: state.videoInfo.bvid || '',
    ownerMid: state.videoInfo.ownerMid || '',
  };
  try{
    if(selected.length > 1){
      // 多P：每个分P作为独立任务批量添加，串行执行避免风控
      const tasks = selected.map(p=>({
        ...commonOpts,
        title: `${state.videoInfo.title} - P${p.index} ${p.title}`,
        pages: [{index:p.index,aid:p.aid,cid:p.cid,epid:p.epid,title:p.title,duration:p.duration}],
        filePattern: state.filePatternMultiPage,
      }));
      await callBridge('addBatchTasks', JSON.stringify(tasks));
      toast(`已添加 ${tasks.length} 个下载任务（串行执行）`,'ok');
    } else {
      // 单P：直接添加单个任务
      const p = selected[0];
      const task = {
        ...commonOpts,
        title: state.videoInfo.title,
        pages: [{index:p.index,aid:p.aid,cid:p.cid,epid:p.epid,title:p.title,duration:p.duration}],
      };
      await callBridge('addTask', JSON.stringify(task));
      toast('已开始下载','ok');
    }
    state.parsed=null; state.videoInfo=null; state.playInfo=null; state._lastUrl='';
    // 重置为持久化的默认设置，而非硬编码 all
    applyDefaultSettings();
    // 先获取最新任务列表，再切换视图，避免切换后多次重渲染导致闪烁
    const tasks = await callBridge('getTasks');
    state._tasks = tasks;
    state._tasksStructSig = '';
    state._tasksContentSig = '';
    state._tasksStatusSig = '';
    state.selectedTaskId = null;
    state._skipAutoSelect = true;
    switchView('explorer');
  }catch(e){ toast('添加失败：'+e,'err'); }
}

/* ===== 搜索UP主/视频与投稿视频 ===== */
function renderSearch(eb){
  // 如果在UP主投稿视频视图
  if(state._upperVideos){
    renderUpperVideoList(eb);
    return;
  }
  // UP主投稿视频加载中（从关注列表点击UP进入时）
  if(state._upperMid && !state._upperVideos){
    const upperNameHtml = renderUpperName(state._upperName, state._upperVipType, state._upperVipStatus, '');
    eb.innerHTML = `<div class="view">
      ${subHeader(upperNameHtml+'的投稿', "exitUpperSpace()", true)}
      <h1>${upperNameHtml}的投稿</h1>
      <div class="loading-pulse">${spinIcon()} 正在加载投稿视频…</div>
    </div>`;
    return;
  }
  // UP主搜索结果视图
  if(state._searchResults){
    renderSearchResultList(eb);
    return;
  }
  // 视频搜索结果视图
  if(state._videoSearchResults){
    renderVideoSearchResultList(eb);
    return;
  }
  // 未登录时提示
  if(!state._loginInfo || !state._loginInfo.isLogin){
    eb.innerHTML = `<div class="view">
      <h1>搜索</h1>
      <div class="detail-empty">
        <svg viewBox="0 0 24 24" width="48" height="48"><path fill="currentColor" d="M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0 2c-5 0-9 2.5-9 6v2h18v-2c0-3.5-4-6-9-6z"/></svg>
        <p style="margin-top:12px;font-size:14px;color:var(--fg)">搜索功能需要登录B站账号</p>
        <p style="font-size:12px;color:var(--fg-dim);margin-top:4px">登录后可搜索UP主和视频，并直接下载</p>
        <button class="btn btn-primary" style="margin-top:16px" onclick="switchView('account')">去登录</button>
      </div>
    </div>`;
    return;
  }
  // 初始搜索表单
  const searchType = state._searchType || 'upper';
  eb.innerHTML = `<div class="view">
    <h1>搜索</h1>
    <p class="lead">搜索B站UP主或视频，支持直接下载</p>
    <div class="search-type-tabs">
      <button class="${searchType==='upper'?'active':''}" onclick="state._searchType='upper';state._searchResults=null;state._videoSearchResults=null;renderEditor()">UP主</button>
      <button class="${searchType==='video'?'active':''}" onclick="state._searchType='video';state._searchResults=null;state._videoSearchResults=null;renderEditor()">视频</button>
    </div>
    <div class="form-section">
      <div class="parse-box">
        <input type="text" id="f_search" placeholder="${searchType==='upper'?'输入UP主名称…':'输入视频关键词…'}" value="${esc(state._searchKeyword||'')}" onkeydown="if(event.key==='Enter')doSearch()">
        <button class="btn btn-primary" onclick="doSearch()">搜索</button>
      </div>
      <div style="font-size:11px;color:var(--fg-dim);margin-top:6px">
        ${searchType==='upper'?'搜索UP主，查看投稿视频列表，支持直接下载':'搜索视频，点击直接下载或查看详情'}
      </div>
    </div>
  </div>`;
}

async function doSearch(){
  const kw = el('f_search') ? el('f_search').value.trim() : state._searchKeyword;
  if(!kw){ toast('请输入搜索关键词','err'); return; }
  state._searchKeyword = kw;
  state._searchResults = null;
  state._videoSearchResults = null;
  const searchType = state._searchType || 'upper';
  const typeLabel = searchType==='upper'?'UP主':'视频';
  editorBody().innerHTML = `<div class="view"><h1>搜索</h1><div class="loading-pulse">${spinIcon()} 正在搜索${typeLabel}"${esc(kw)}"…</div></div>`;
  try{
    if(searchType === 'video'){
      const results = await callBridge('searchVideo', kw);
      state._videoSearchResults = results;
    } else {
      const results = await callBridge('searchUpper', kw);
      state._searchResults = results;
    }
    renderEditor();
  }catch(e){ toast('搜索失败：'+e,'err'); }
}

function renderVideoSearchResultList(eb){
  const results = state._videoSearchResults || [];
  if(results.length === 0){
    eb.innerHTML = `<div class="view">
      ${subHeader('搜索结果', "state._videoSearchResults=null;renderEditor()")}
      <div class="empty-state">未找到相关视频</div>
    </div>`;
    return;
  }
  eb.innerHTML = `<div class="view">
    ${subHeader('搜索结果', "state._videoSearchResults=null;renderEditor()")}
    <p class="lead">找到 ${results.length} 个视频</p>
    <div class="vc-list">
      ${results.map(v=>videoCardHTML(v, {onclick:`downloadFromSearch('${esc(v.bvid)}')`, showUpper:true})).join('')}
    </div>
  </div>`;
}

async function downloadFromSearch(bvid){
  toast('正在解析视频信息…');
  try{
    const parsed = await callBridge('parseUrl', bvid);
    state.parsed = parsed;
    state.batchResults = null;
    state._lastUrl = bvid;
    state._searchResults = null;
    state._videoSearchResults = null;
    state._upperVideos = null;
    const info = await callBridge('getVideoInfo', parsed.type, parsed.aid, parsed.epId, parsed.bvid||'');
    state.videoInfo = info;
    state.selectedPages = new Set(info.pages.map(p=>p.index));
    await fetchPlayInfo();
    // 显示下载选项界面，让用户选择下载参数
    applyDefaultSettings();
    switchView('addtask');
    renderEditor();
  }catch(e){
    toast('解析失败：'+e,'err');
    switchView('addtask');
    renderEditor();
  }
}

function renderSearchResultList(eb){
  const results = state._searchResults || [];
  if(results.length === 0){
    eb.innerHTML = `<div class="view">
      ${subHeader('搜索结果', "state._searchResults=null;state._searchKeyword='';renderEditor()")}
      <div class="empty-state">未找到相关UP主</div>
    </div>`;
    return;
  }
  eb.innerHTML = `<div class="view">
    ${subHeader('搜索结果', "state._searchResults=null;state._searchKeyword='';renderEditor()")}
    <p class="lead">找到 ${results.length} 个UP主</p>
    <div class="upper-search-list">
      ${results.map(u=>`
        <div class="upper-card" onclick="openUpperSpace('${esc(u.mid)}','${esc(u.uname)}','${esc(u.face||'')}',${u.officialType!=null?u.officialType:-1},${u.vipType||0},${u.vipStatus||0})">
          ${renderAvatarWrap(u.face, u.uname, u.officialType, 'lg')}
          <div class="uc-body">
            <div class="uc-name">${renderUpperName(u.uname, u.vipType, u.vipStatus, '')}</div>
            <div class="uc-mid">${renderUpperUid(u.mid, 0, 0)}</div>
            <div class="uc-sign">${esc(u.sign || '这个UP主很懒，什么都没写')}</div>
            <div class="uc-stats">
              <span class="uc-stat">粉丝 ${fmtCount(u.fans)}</span>
              <span class="uc-stat">投稿 ${u.videoCount} 个</span>
            </div>
          </div>
          <svg class="uc-arrow" viewBox="0 0 24 24" width="18" height="18"><path fill="var(--fg-dim)" d="M9 6l6 6-6 6"/></svg>
        </div>
      `).join('')}
    </div>
  </div>`;
}

function openUpperSpace(mid, uname, face, officialType, vipType, vipStatus){
  // 记录来源视图，退出时返回该视图（而非固定回搜索页）
  state._upperReturnView = state.currentView;
  state._upperMid = mid;
  state._upperName = uname;
  state._upperFace = face || '';
  state._upperOfficialType = officialType != null ? officialType : -1;
  state._upperVipType = vipType || 0;
  state._upperVipStatus = vipStatus || 0;
  state._upperVideos = null;
  state._upperPage = 1;
  state._upperTotal = 0;
  // 切换到搜索视图（投稿视频列表在 renderSearch 中渲染）
  // 不能直接设 innerHTML，否则 loadUpperVideos→renderEditor 会因 currentView 仍是 account 而回退
  if(state.currentView !== 'search'){
    state.currentView = 'search';
    document.querySelectorAll('.ab-btn').forEach(b=>b.classList.toggle('active', b.dataset.view==='search'));
    el('sidebarTitle').textContent = '搜索';
    renderSidebar();
  }
  // 显示加载状态（renderSearch 检测 _upperMid 已设、_upperVideos 为空时显示加载态）
  clearSubnav();
  renderEditor();
  loadUpperVideos(1);
}

/** 退出UP投稿视频视图，返回来源页面 */
function exitUpperSpace(){
  state._upperVideos = null;
  state._upperMid = '';
  state._upperName = '';
  state._upperFace = '';
  state._upperOfficialType = -1;
  state._upperVipType = 0;
  state._upperVipStatus = 0;
  state._upperPage = 1;
  state._upperTotal = 0;
  const returnView = state._upperReturnView || 'search';
  state._upperReturnView = '';
  if(state.currentView !== returnView){
    switchView(returnView);
  } else {
    renderEditor();
  }
}

async function loadUpperVideos(page){
  state._upperPage = page;
  try{
    const result = await callBridge('getUpperVideos', state._upperMid, page);
    state._upperVideos = result.items || [];
    state._upperTotal = result.total || 0;
    renderEditor();
  }catch(e){
    // 加载失败：设置空数组触发 renderUpperVideoList 显示错误态，避免卡在加载中
    state._upperVideos = [];
    state._upperTotal = 0;
    renderEditor();
    toast('加载投稿视频失败：'+e,'err');
  }
}

function renderUpperVideoList(eb){
  const videos = state._upperVideos || [];
  const total = state._upperTotal || 0;
  const page = state._upperPage;
  const pageSize = 30;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  const upperNameHtml = renderUpperName(state._upperName, state._upperVipType, state._upperVipStatus, '');
  eb.innerHTML = `<div class="view">
    ${subHeader(upperNameHtml+'的投稿', "exitUpperSpace()", true)}
    <p class="lead">${total > 0 ? total + ' 个投稿' : videos.length + ' 个投稿'}</p>
    ${videos.length === 0 ? '<div class="empty-state">该UP主暂无投稿视频</div>' :
      `<div class="vc-list">
        ${videos.map(v=>{
          // 合并UP主信息（投稿视频列表中所有视频属于同一UP主）
          const v2 = Object.assign({}, v, {
            ownerName: state._upperName || v.ownerName || '',
            ownerFace: state._upperFace || v.ownerFace || '',
            officialType: state._upperOfficialType != null ? state._upperOfficialType : (v.officialType != null ? v.officialType : -1),
            vipType: state._upperVipType || v.vipType || 0,
            vipStatus: state._upperVipStatus || v.vipStatus || 0
          });
          return videoCardHTML(v2, {onclick:`downloadUpperVideo('${esc(v.bvid)}','${esc(v.title)}')`});
        }).join('')}
      </div>`
    }
    ${totalPages > 1 ? `
      <div class="fav-pagination">
        ${page > 1 ? `<button class="btn btn-sec" onclick="loadUpperVideos(${page-1})">上一页</button>` : ''}
        <span class="page-info">第 ${page} / ${totalPages} 页</span>
        ${page < totalPages ? `<button class="btn btn-sec" onclick="loadUpperVideos(${page+1})">下一页</button>` : ''}
      </div>
    ` : ''}
  </div>`;
}

async function downloadUpperVideo(bvid, title){
  toast('正在解析视频信息…');
  try{
    const url = bvid;
    const parsed = await callBridge('parseUrl', url);
    state.parsed = parsed;
    state.batchResults = null;
    state._lastUrl = url;
    const info = await callBridge('getVideoInfo', parsed.type, parsed.aid, parsed.epId, parsed.bvid||'');
    state.videoInfo = info;
    state.selectedPages = new Set(info.pages.map(p=>p.index));
    await fetchPlayInfo();
    // 显示下载选项界面，让用户选择下载参数
    applyDefaultSettings();
    switchView('addtask');
    renderEditor();
  }catch(e){
    toast('解析失败：'+e,'err');
    switchView('addtask');
    renderEditor();
  }
}

function fmtCount(n){
  if(!n || n === 0) return '0';
  if(n >= 100000000) return (n/100000000).toFixed(1) + '亿';
  if(n >= 10000) return (n/10000).toFixed(1) + '万';
  return String(n);
}

/**
 * 统一视频卡片HTML生成器，用于合集/批量/搜索/投稿/收藏等所有视频列表。
 * 自动兼容不同数据源的字段名差异。
 * @param {Object} v - 视频数据
 * @param {Object} opts - { onclick, checkboxData, index, showUpper }
 *   onclick: 点击事件的JS代码字符串（不含onclick=）
 *   checkboxData: 如果提供，在卡片右侧渲染checkbox，值为 {bvid, checked, onchange, extraClass, disabled}
 *     extraClass: 额外的class（如 'col-check'/'fav-check'），用于区分不同列表的选择器
 *   index: 序号（从1开始），可选
 *   showUpper: 是否显示UP主信息（默认true）
 *   failed: 是否为解析失败项
 *   errorMsg: 失败时的错误信息
 */
function videoCardHTML(v, opts){
  opts = opts || {};
  const showUpper = opts.showUpper !== false;

  // 字段标准化（兼容不同数据源）
  const title = v.title || v.url || '';
  const pic = v.pic || '';
  const bvid = v.bvid || v.url || '';
  // duration 可能是字符串("10:30")或数字(秒)
  let duration = v.duration;
  if(typeof duration === 'number' && duration > 0){
    const m = Math.floor(duration / 60);
    const s = duration % 60;
    duration = `${m}:${String(s).padStart(2,'0')}`;
  }
  // 发布时间：优先 pubdate > created > pubTime > favTime
  const pubdate = v.pubdate || v.created || v.pubTime || v.favTime || 0;
  // UP主信息
  const ownerName = v.ownerName || v.upperName || v.author || v.upper || '';
  const ownerFace = v.ownerFace || v.face || '';
  const officialType = v.officialType != null ? v.officialType : -1;
  const vipType = v.vipType || 0;
  const vipStatus = v.vipStatus || 0;
  const play = v.play || 0;
  const danmaku = v.danmaku || 0;

  const phSvg = '<svg viewBox="0 0 24 24" width="24" height="24"><path fill="currentColor" d="M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11l-4 4z"/></svg>';

  // 失败状态：使用独立简化布局
  if(opts.failed){
    const failCheckboxHtml = opts.checkboxData ? `<div class="vc-actions"><input type="checkbox" class="checkbox vc-check ${opts.checkboxData.extraClass||''}" data-bvid="${esc(opts.checkboxData.bvid)}" ${opts.checkboxData.checked?'checked':''} onchange="${opts.checkboxData.onchange||''};vcSyncCheckStyle(this)" disabled onclick="event.stopPropagation()"></div>` : '';
    return `<div class="vc-item vc-failed vc-item-static">
      <div class="vc-cover-wrap"><div class="vc-cover vc-cover-ph">${phSvg}</div></div>
      <div class="vc-body">
        <div class="vc-title vc-title-static">${esc(title||'解析失败')}</div>
        <div class="vc-meta"><span style="color:var(--error)">${esc(opts.errorMsg||'解析失败')}</span></div>
      </div>
      ${failCheckboxHtml}
    </div>`;
  }

  // 封面
  const coverHtml = pic ? (() => {
    const cu = imgSrc(pic);
    return cu ? `<img class="vc-cover" src="${esc(cu)}" onerror="this.style.display='none';this.nextElementSibling.style.display='flex'"><div class="vc-cover vc-cover-ph" style="display:none">${phSvg}</div>` : `<img class="vc-cover" data-img-url="${esc(pic)}" onerror="this.style.display='none';this.nextElementSibling.style.display='flex'"><div class="vc-cover vc-cover-ph" style="display:none">${phSvg}</div>`;
  })() : `<div class="vc-cover vc-cover-ph">${phSvg}</div>`;

  // 时长角标（与UP投稿页面 .uv-duration 样式一致）
  const durationHtml = duration ? `<span class="vc-duration">${esc(String(duration))}</span>` : '';

  // 封面容器
  const coverWrap = `<div class="vc-cover-wrap">${coverHtml}${durationHtml}</div>`;

  // 标题（序号已移至卡片左上角角标，不再作为文字前缀）
  const titleHtml = `<div class="vc-title${opts.onclick ? '' : ' vc-title-static'}">${esc(title)}</div>`;

  // 发布时间
  const dateHtml = pubdate ? `<div class="vc-date">${fmtTime(pubdate)}</div>` : '';

  // UP主信息行
  let upperHtml = '';
  if(showUpper && ownerName){
    const avatarHtml = renderAvatarWrap(ownerFace, ownerName, officialType, 'sm');
    const nameHtml = renderUpperName(ownerName, vipType, vipStatus, 'vc-upper-name');
    upperHtml = `<div class="vc-upper">${avatarHtml}${nameHtml}</div>`;
  }

  // 统计信息
  const metaParts = [];
  if(play) metaParts.push(`<span>播放 ${fmtCount(play)}</span>`);
  metaParts.push(`<span>弹幕 ${fmtCount(danmaku)}</span>`);
  const metaHtml = metaParts.length ? `<div class="vc-meta">${metaParts.join('')}</div>` : '';

  // checkbox — 点击卡片即可切换选中状态
  const checkboxHtml = opts.checkboxData ? `<div class="vc-actions"><input type="checkbox" class="checkbox vc-check ${opts.checkboxData.extraClass||''}" data-bvid="${esc(opts.checkboxData.bvid)}" ${opts.checkboxData.checked?'checked':''} onchange="${opts.checkboxData.onchange||''};vcSyncCheckStyle(this)" ${opts.checkboxData.disabled?'disabled':''} onclick="event.stopPropagation()"></div>` : '';

  // onclick：优先用 opts.onclick；若有 checkbox 且无 onclick，则点击卡片切换选中
  let clickAttr = '';
  let hasClick = false;
  if (opts.onclick) {
    clickAttr = ` onclick="${opts.onclick}"`;
    hasClick = true;
  } else if (opts.checkboxData && !opts.checkboxData.disabled) {
    clickAttr = ` onclick="vcToggleCheck(this)"`;
    hasClick = true;
  }

  const checkedCls = (opts.checkboxData && opts.checkboxData.checked && !opts.checkboxData.disabled) ? ' vc-checked' : '';

  return `<div class="vc-item${hasClick ? '' : ' vc-item-static'}${checkedCls}"${clickAttr}>
    ${coverWrap}
    <div class="vc-body">
      ${titleHtml}
      ${upperHtml}
      ${dateHtml}
      ${metaHtml}
    </div>
    ${checkboxHtml}
  </div>`;
}

/** 点击视频卡片切换勾选状态（点击 checkbox 本身不触发此函数，已 stopPropagation） */
function vcToggleCheck(card){
  const cb = card.querySelector('.vc-check');
  if(cb && !cb.disabled){
    cb.checked = !cb.checked;
    vcSyncCheckStyle(cb);
    cb.dispatchEvent(new Event('change', {bubbles:false}));
  }
}

/** 根据 checkbox 状态同步卡片选中样式 */
function vcSyncCheckStyle(cb){
  if(!cb) return;
  const card = cb.closest('.vc-item');
  if(!card) return;
  if(cb.checked){
    card.classList.add('vc-checked');
  }else{
    card.classList.remove('vc-checked');
  }
}

/* ===== 账号(扫码登录) - 简洁双状态 ===== */
// _loginView: 'main' = 主视图(二维码/用户卡片), 'auth' = 授权另一方式(二维码), 'manage' = 管理授权(取消按钮)
function renderAccount(eb){
  const web = (state._loginInfo && state._loginInfo.web) || {isLogin: false};
  const tv = (state._loginInfo && state._loginInfo.tv) || {isLogin: false};
  const anyLogin = web.isLogin || tv.isLogin;
  const view = state._loginView || 'main';

  // ===== 授权视图：点击隐蔽链接进入，只显示二维码（不重复头像ID） =====
  if(view === 'auth'){
    // 优先使用显式指定的目标方式，否则自动检测未授权方式
    const authMode = state._authTarget || (!web.isLogin ? 'web' : (!tv.isLogin ? 'tv' : null));
    if(!authMode || (authMode === 'web' && web.isLogin) || (authMode === 'tv' && tv.isLogin)){
      // 该方式已授权或无目标，返回主视图
      state._loginView = 'main';
      state._authTarget = null;
      renderEditor();
      return;
    }
    const desc = authMode === 'web'
      ? '使用 <b style="color:var(--fg)">哔哩哔哩手机APP</b> 扫码登录网页版'
      : '使用 <b style="color:var(--fg)">哔哩哔哩手机APP</b> 扫码授权TV版';
    const qrId = authMode === 'web' ? 'webQrWrapper' : 'tvQrWrapper';
    const statusId = authMode === 'web' ? 'webQrStatus' : 'tvQrStatus';

    eb.innerHTML = `<div class="view">
      ${subHeader(authMode==='web'?'网页版登录':'TV版授权', "state._loginView='main';state._authTarget=null;renderEditor()")}
      <h1>${authMode==='web'?'网页版登录':'TV版授权'}</h1>
      <div class="login-section">
        <div class="qr-login-box">
          <div class="qr-desc" style="margin-bottom:14px">${desc}</div>
          <div class="qr-wrapper" id="${qrId}">
            <div class="loading-pulse" style="padding:60px 0">${spinIcon()}<br><span style="font-size:11px;margin-top:8px;display:block">生成二维码中…</span></div>
          </div>
          <div class="qr-status" id="${statusId}">等待生成二维码…</div>
          <button class="btn btn-primary" style="margin-top:14px;font-size:13px;padding:9px 22px" onclick="openBiliApp('${authMode}')">
            <svg viewBox="0 0 24 24" width="15" height="15" style="vertical-align:middle;margin-right:4px"><path fill="currentColor" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>
            跳转B站确认
          </button>
        </div>
      </div>
    </div>`;

    if(authMode === 'web') startWebLogin();
    else startTvLogin();
    return;
  }

  // ===== 管理视图：点击头像进入，管理两种登录方式的授权（不重复显示头像ID） =====
  if(view === 'manage' && anyLogin){
    eb.innerHTML = `<div class="view">
      ${subHeader('授权管理', "state._loginView='main';state._authTarget=null;renderEditor()")}
      <h1>授权管理</h1>
      <div class="auth-manage-list">
        <div class="auth-manage-item ${web.isLogin?'auth-active':''}">
          <div class="am-icon">
            <svg viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93C7.05 19.44 4 16.08 4 12c0-.61.08-1.21.21-1.78L9 15v1c0 1.1.9 2 2 2v1.93z"/></svg>
          </div>
          <div class="am-body">
            <div class="am-title">网页版登录</div>
            <div class="am-status ${web.isLogin?'ok':''}">${web.isLogin?'已登录':'未登录'}</div>
          </div>
          ${web.isLogin ? '<button class="btn btn-danger am-btn" onclick="doLogoutWeb()">取消授权</button>' : '<button class="btn btn-sec am-btn" onclick="state._authTarget=\'web\';state._loginView=\'auth\';renderEditor()">去授权</button>'}
        </div>
        <div class="auth-manage-item ${tv.isLogin?'auth-active':''}">
          <div class="am-icon">
            <svg viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z"/></svg>
          </div>
          <div class="am-body">
            <div class="am-title">TV版授权</div>
            <div class="am-status ${tv.isLogin?'ok':''}">${tv.isLogin?'已授权':'未授权'}</div>
          </div>
          ${tv.isLogin ? '<button class="btn btn-danger am-btn" onclick="doLogoutTv()">取消授权</button>' : '<button class="btn btn-sec am-btn" onclick="state._authTarget=\'tv\';state._loginView=\'auth\';renderEditor()">去授权</button>'}
        </div>
      </div>
    </div>`;
    return;
  }

  // ===== 主视图 =====
  if(!anyLogin){
    // 未登录：只显示当前选中方式的二维码
    const mode = state._loginMode || 'web';
    const qrId = mode === 'web' ? 'webQrWrapper' : 'tvQrWrapper';
    const statusId = mode === 'web' ? 'webQrStatus' : 'tvQrStatus';
    const desc = mode === 'web'
      ? '使用 <b style="color:var(--fg)">哔哩哔哩手机APP</b> 扫码登录网页版'
      : '使用 <b style="color:var(--fg)">哔哩哔哩手机APP</b> 扫码授权TV版';
    const otherMode = mode === 'web' ? 'tv' : 'web';
    const otherLabel = mode === 'web' ? 'TV版授权' : '网页版登录';

    eb.innerHTML = `<div class="view">
      <h1>账号管理</h1>
      <div class="login-section">
        <div class="qr-login-box">
          <div class="qr-desc" style="margin-bottom:14px">${desc}</div>
          <div class="qr-wrapper" id="${qrId}">
            <div class="loading-pulse" style="padding:60px 0">${spinIcon()}<br><span style="font-size:11px;margin-top:8px;display:block">生成二维码中…</span></div>
          </div>
          <div class="qr-status" id="${statusId}">等待生成二维码…</div>
          <button class="btn btn-primary" style="margin-top:14px;font-size:13px;padding:9px 22px" onclick="openBiliApp('${mode}')">
            <svg viewBox="0 0 24 24" width="15" height="15" style="vertical-align:middle;margin-right:4px"><path fill="currentColor" d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>
            跳转B站确认
          </button>
        </div>
        <div class="login-switch-hint">
          <span class="login-switch-link" onclick="switchLoginMode('${otherMode}')">使用${otherLabel}登录 →</span>
        </div>
      </div>
    </div>`;

    if(mode === 'web') startWebLogin();
    else startTvLogin();
    return;
  }

  // 已登录：头像（仅一次，可点击进入管理）+ ID + 简洁授权状态 + 收藏夹
  const primary = web.isLogin ? web : tv;

  eb.innerHTML = `<div class="view">
    <h1>账号管理</h1>
    <div class="login-section">
      <div class="user-card compact-uc">
        <div class="uc-avatar-wrap" onclick="state._loginView='manage';renderEditor()">
          ${primary.face ? (()=>{ const cu=imgSrc(primary.face); return cu ? `<img class="uc-avatar" src="${esc(cu)}" onerror="this.style.display='none'">` : `<img class="uc-avatar" data-img-url="${esc(primary.face)}" onerror="this.style.display='none'">`; })() : ''}
        </div>
        <div class="uc-body">
          <div class="uc-name-row" style="display:flex;align-items:center;gap:6px">
            <span class="uc-name ${primary.isVip ? 'vip-name' : ''}">${esc(primary.uname||'已登录')}</span>
            ${primary.isVip ? '<span class="uc-vip">大会员</span>' : ''}
          </div>
          <div class="uc-mid">UID: ${esc(primary.mid||'')}</div>
          <div class="ls-pills">
            <span class="ls-pill ${web.isLogin?'on':''}">
              <span class="ls-pill-dot"></span>网页版
            </span>
            <span class="ls-pill ${tv.isLogin?'on':''}">
              <span class="ls-pill-dot"></span>TV版
            </span>
          </div>
        </div>
      </div>
    </div>
    ${anyLogin ? `
      <h2 class="login-section-title" style="margin-top:24px">我的关注<span id="followCountText" class="follow-count-small"></span></h2>
      <div id="followingsContainer" class="fav-folders-loading">${spinIcon()} 加载中…</div>
      <h2 class="login-section-title" style="margin-top:24px">我的收藏夹</h2>
      <div id="favFoldersContainer" class="fav-folders-loading">${spinIcon()} 加载中…</div>
    ` : ''}
  </div>`;

  if(anyLogin){
    loadFollowings(true);
    loadFavFolders();
  }
}

/** 切换登录方式（未登录时） */
function switchLoginMode(m){
  // 停止当前轮询
  if(typeof _webQrPollTimer !== 'undefined' && _webQrPollTimer){ clearInterval(_webQrPollTimer); _webQrPollTimer = null; }
  if(typeof _tvQrPollTimer !== 'undefined' && _tvQrPollTimer){ clearInterval(_tvQrPollTimer); _tvQrPollTimer = null; }
  state._loginMode = m;
  renderEditor();
}

/** 打开哔哩哔哩手机App，直接跳转到授权确认页面 */
async function openBiliApp(loginType){
  const authUrl = loginType === 'tv' ? _tvQrAuthUrl : _webQrAuthUrl;
  if(!authUrl){
    toast('请先等待二维码生成','warn');
    return;
  }
  try{
    const res = await callBridge('openBiliApp', authUrl);
    if(res.opened){
      if(res.fallback){
        toast('未检测到B站App，已跳转下载页','warn');
      } else if(res.auth){
        toast('已跳转到B站授权页面，请确认授权','ok');
      } else if(res.hint){
        toast(res.hint,'warn');
      } else {
        toast('已打开B站App','ok');
      }
    }
  }catch(e){ toast('打开失败：'+e,'err'); }
}

async function initAccountView(){
  jsLog('initAccountView called');
  // 注意：switchView 已调用 renderEditor() 渲染了账号页，此处不再重复渲染（避免闪烁）
  updateStatusBar();
  // 进入账号页时刷新登录状态，获取最新大会员信息
  // 仅在登录信息有实质变化时才重新渲染，避免页面闪烁
  try{
    const prevSig = _loginInfoSig(state._loginInfo);
    await refreshLoginStateSafe();
    if(prevSig !== _loginInfoSig(state._loginInfo) && state.currentView === 'account'){
      renderEditor();
    }
  }catch(e){}
}

// WEB/TV 各自独立的二维码轮询状态
let _webQrPollTimer = null;
let _webQrCurrentKey = null;
let _webQrAuthUrl = null;
let _tvQrPollTimer = null;
let _tvQrCurrentKey = null;
let _tvQrAuthUrl = null;

/** 刷新登录状态（重新调用 checkLogin） */
async function refreshLoginState(){
  try{
    const res = await callBridge('checkLogin');
    if(res.face) res.face = res.face.replace(/^http:\/\//, 'https://');
    if(res.web && res.web.face) res.web.face = res.web.face.replace(/^http:\/\//, 'https://');
    if(res.tv && res.tv.face) res.tv.face = res.tv.face.replace(/^http:\/\//, 'https://');
    state._loginInfo = res;
    jsLog('Login state refreshed: web=' + (res.web?.isLogin) + ', tv=' + (res.tv?.isLogin));
  }catch(e){
    jsLog('refreshLoginState error: ' + e);
  }
}

/** 安全刷新登录状态：合并后端结果，不覆盖已登录的本地态 */
async function refreshLoginStateSafe(){
  try{
    const res = await callBridge('checkLogin');
    if(res.face) res.face = res.face.replace(/^http:\/\//, 'https://');
    if(res.web && res.web.face) res.web.face = res.web.face.replace(/^http:\/\//, 'https://');
    if(res.tv && res.tv.face) res.tv.face = res.tv.face.replace(/^http:\/\//, 'https://');
    if(!state._loginInfo) state._loginInfo = {};
    // WEB: 仅在后端确认登录或本地未登录时更新；本地已登录则保留，只补充字段
    if(res.web){
      if(res.web.isLogin){
        state._loginInfo.web = res.web;
      } else if(!state._loginInfo.web || !state._loginInfo.web.isLogin){
        state._loginInfo.web = res.web;
      }
      // else: 本地已登录但后端未确认 → 保留本地态（可能是网络延迟）
    }
    // TV: 同理
    if(res.tv){
      if(res.tv.isLogin){
        state._loginInfo.tv = res.tv;
      } else if(!state._loginInfo.tv || !state._loginInfo.tv.isLogin){
        state._loginInfo.tv = res.tv;
      }
    }
    state._loginInfo.isLogin = (state._loginInfo.web && state._loginInfo.web.isLogin) || (state._loginInfo.tv && state._loginInfo.tv.isLogin) || false;
    // 更新顶层字段（侧栏显示用）
    if(state._loginInfo.web && state._loginInfo.web.isLogin){
      state._loginInfo.uname = state._loginInfo.web.uname;
      state._loginInfo.mid = state._loginInfo.web.mid;
      state._loginInfo.face = state._loginInfo.web.face;
    } else if(state._loginInfo.tv && state._loginInfo.tv.isLogin){
      state._loginInfo.uname = state._loginInfo.tv.uname;
      state._loginInfo.mid = state._loginInfo.tv.mid;
      state._loginInfo.face = state._loginInfo.tv.face;
    }
    updateStatusBar();
    jsLog('Login state safe-refreshed: web=' + (state._loginInfo.web?.isLogin) + ', tv=' + (state._loginInfo.tv?.isLogin));
  }catch(e){
    jsLog('refreshLoginStateSafe error: ' + e);
  }
}

// ===== WEB 登录 =====
async function startWebLogin(){
  jsLog('startWebLogin begin');
  _webQrCurrentKey = null;
  _webQrAuthUrl = null;
  if(_webQrPollTimer){ clearInterval(_webQrPollTimer); _webQrPollTimer = null; }
  try{
    const qr = await callBridge('getQrCode');
    jsLog('getQrCode returned: key=' + (qr.qrcodeKey||'EMPTY') + ', imageLen=' + (qr.image||'').length);
    _webQrCurrentKey = qr.qrcodeKey;
    _webQrAuthUrl = qr.url || null;
    const wrapper = el('webQrWrapper');
    if(wrapper){
      wrapper.innerHTML = `<img src="${qr.image}" alt="QR Code" style="width:180px;height:180px">`;
    }
    const status = el('webQrStatus');
    if(status){ status.textContent = '请使用手机APP扫码'; status.className = 'qr-status'; }
    startWebLoginPolling();
  }catch(e){
    jsLog('startWebLogin ERROR: ' + String(e));
    const status = el('webQrStatus');
    if(status){ status.textContent = '获取二维码失败：'+e; status.className = 'qr-status expired'; }
    const wrapper = el('webQrWrapper');
    if(wrapper){
      wrapper.innerHTML = `<div style="padding:20px;text-align:center;color:var(--error);font-size:12px">${esc(String(e))}<br><br><button class="btn btn-primary" style="font-size:12px;padding:6px 16px" onclick="startWebLogin()">重试</button></div>`;
    }
    toast('获取网页版二维码失败','err');
  }
}

function startWebLoginPolling(){
  if(_webQrPollTimer) clearInterval(_webQrPollTimer);
  _webQrPollTimer = setInterval(async ()=>{
    if(!_webQrCurrentKey) return;
    try{
      const res = await callBridge('pollQrLogin', _webQrCurrentKey);
      const status = el('webQrStatus');
      const wrapper = el('webQrWrapper');
      if(res.code === 0){
        clearInterval(_webQrPollTimer); _webQrPollTimer = null;
        if(status){ status.textContent = '登录成功！'; status.className = 'qr-status success'; }
        if(wrapper){
          wrapper.innerHTML = `<div class="qr-overlay"><div class="qr-ov-ok">${okIcon()} 登录成功</div></div>`;
        }
        toast('网页版登录成功','ok');
        // 直接用返回数据更新登录状态，避免 checkLogin 网络请求失败导致不显示
        if(!state._loginInfo) state._loginInfo = {};
        state._loginInfo.web = {
          isLogin: true,
          uname: res.uname || '',
          mid: res.mid || '',
          isVip: res.isVip || false,
          face: res.face ? res.face.replace(/^http:\/\//, 'https://') : ''
        };
        state._loginInfo.isLogin = true;
        updateStatusBar();
        setTimeout(()=>renderEditor(), 800);
        // 延迟刷新后端状态，避免覆盖刚设置的本地登录态
        setTimeout(()=>refreshLoginStateSafe(), 2000);
      } else if(res.code === 86090){
        if(status){ status.textContent = '已扫码，请在手机上确认'; status.className = 'qr-status scanning'; }
        if(wrapper){
          wrapper.innerHTML = `<div class="qr-overlay"><div class="qr-ov-text">${spinIcon()} 已扫码</div><div class="qr-ov-text">请在手机上确认登录</div></div>`;
        }
      } else if(res.code === 86039 || res.code === 86101){
        if(status){ status.textContent = '等待扫码…'; status.className = 'qr-status'; }
      } else if(res.code === 86038 || res.code === -1 || (res.message && (res.message.includes('过期') || res.message.includes('expire')))){
        clearInterval(_webQrPollTimer); _webQrPollTimer = null;
        if(status){ status.textContent = '二维码已过期，请刷新'; status.className = 'qr-status expired'; }
        if(wrapper){
          wrapper.innerHTML = `<div class="qr-overlay"><div class="qr-ov-text">二维码已过期</div>
            <button class="btn btn-primary qr-refresh" onclick="startWebLogin()" style="font-size:12px;padding:6px 16px">刷新二维码</button></div>`;
        }
      }
    }catch(e){
      // 轮询出错，继续
    }
  }, 2000);
}

// ===== TV 登录 =====
async function startTvLogin(){
  jsLog('startTvLogin begin');
  _tvQrCurrentKey = null;
  _tvQrAuthUrl = null;
  if(_tvQrPollTimer){ clearInterval(_tvQrPollTimer); _tvQrPollTimer = null; }
  try{
    const qr = await callBridge('getTvQrCode');
    jsLog('getTvQrCode returned: key=' + (qr.qrcodeKey||'EMPTY') + ', imageLen=' + (qr.image||'').length);
    _tvQrCurrentKey = qr.qrcodeKey;
    _tvQrAuthUrl = qr.url || null;
    const wrapper = el('tvQrWrapper');
    if(wrapper){
      wrapper.innerHTML = `<img src="${qr.image}" alt="QR Code" style="width:180px;height:180px">`;
    }
    const status = el('tvQrStatus');
    if(status){ status.textContent = '请使用手机APP扫码'; status.className = 'qr-status'; }
    startTvLoginPolling();
  }catch(e){
    jsLog('startTvLogin ERROR: ' + String(e));
    const status = el('tvQrStatus');
    if(status){ status.textContent = '获取二维码失败：'+e; status.className = 'qr-status expired'; }
    const wrapper = el('tvQrWrapper');
    if(wrapper){
      wrapper.innerHTML = `<div style="padding:20px;text-align:center;color:var(--error);font-size:12px">${esc(String(e))}<br><br><button class="btn btn-primary" style="font-size:12px;padding:6px 16px" onclick="startTvLogin()">重试</button></div>`;
    }
    toast('获取TV版二维码失败','err');
  }
}

function startTvLoginPolling(){
  if(_tvQrPollTimer) clearInterval(_tvQrPollTimer);
  _tvQrPollTimer = setInterval(async ()=>{
    if(!_tvQrCurrentKey) return;
    try{
      const res = await callBridge('pollTvLogin', _tvQrCurrentKey);
      const status = el('tvQrStatus');
      const wrapper = el('tvQrWrapper');
      if(res.code === 0){
        clearInterval(_tvQrPollTimer); _tvQrPollTimer = null;
        if(status){ status.textContent = '授权成功！'; status.className = 'qr-status success'; }
        if(wrapper){
          wrapper.innerHTML = `<div class="qr-overlay"><div class="qr-ov-ok">${okIcon()} 授权成功</div></div>`;
        }
        toast('TV版授权成功','ok');
        // 直接用返回数据更新登录状态
        if(!state._loginInfo) state._loginInfo = {};
        state._loginInfo.tv = {
          isLogin: true,
          hasToken: true,
          uname: res.uname || '',
          mid: res.mid || '',
          isVip: res.isVip || false,
          face: res.face ? res.face.replace(/^http:\/\//, 'https://') : ''
        };
        state._loginInfo.isLogin = true;
        updateStatusBar();
        setTimeout(()=>renderEditor(), 800);
        // 延迟刷新后端状态，避免覆盖刚设置的本地登录态
        setTimeout(()=>refreshLoginStateSafe(), 2000);
      } else if(res.code === 86090){
        if(status){ status.textContent = '已扫码，请在手机上确认'; status.className = 'qr-status scanning'; }
        if(wrapper){
          wrapper.innerHTML = `<div class="qr-overlay"><div class="qr-ov-text">${spinIcon()} 已扫码</div><div class="qr-ov-text">请在手机上确认授权</div></div>`;
        }
      } else if(res.code === 86039 || res.code === 86101){
        if(status){ status.textContent = '等待扫码…'; status.className = 'qr-status'; }
      } else if(res.code === 86038 || res.code === -1 || (res.message && (res.message.includes('过期') || res.message.includes('expire')))){
        clearInterval(_tvQrPollTimer); _tvQrPollTimer = null;
        if(status){ status.textContent = '二维码已过期，请刷新'; status.className = 'qr-status expired'; }
        if(wrapper){
          wrapper.innerHTML = `<div class="qr-overlay"><div class="qr-ov-text">二维码已过期</div>
            <button class="btn btn-primary qr-refresh" onclick="startTvLogin()" style="font-size:12px;padding:6px 16px">刷新二维码</button></div>`;
        }
      }
    }catch(e){
      // 轮询出错，继续
    }
  }, 2000);
}

// ===== 独立退出 =====
async function doLogoutWeb(){
  try{
    await callBridge('logoutWeb');
    // 先清除本地登录态，确保 UI 立即更新
    if(state._loginInfo && state._loginInfo.web){
      state._loginInfo.web = {isLogin: false};
    }
    state._loginInfo.isLogin = (state._loginInfo && state._loginInfo.tv && state._loginInfo.tv.isLogin) || false;
    state._loginView = 'main'; // 返回主视图
    toast('已退出网页版登录','ok');
    updateStatusBar();
    renderEditor();
    // 异步刷新后端状态
    refreshLoginStateSafe();
  }catch(e){ toast('退出失败','err'); }
}

async function doLogoutTv(){
  try{
    await callBridge('logoutTv');
    // 先清除本地登录态，确保 UI 立即更新
    if(state._loginInfo && state._loginInfo.tv){
      state._loginInfo.tv = {isLogin: false};
    }
    state._loginInfo.isLogin = (state._loginInfo && state._loginInfo.web && state._loginInfo.web.isLogin) || false;
    state._loginView = 'main'; // 返回主视图
    toast('已取消TV版授权','ok');
    updateStatusBar();
    renderEditor();
    // 异步刷新后端状态
    refreshLoginStateSafe();
  }catch(e){ toast('退出失败','err'); }
}

/* ===== 关注列表功能 ===== */

/** B站小闪电徽章HTML（放在头像右下角） type: -1=无, 0=个人认证(黄), 1=机构认证(蓝) */
function officialBadgeHtml(type){
  if(type !== 0 && type !== 1) return '';
  const cls = type === 0 ? 'official-personal' : 'official-org';
  return `<span class="official-badge ${cls}"><svg viewBox="0 0 24 24"><path d="M13 2L4.5 13.5h6L9 22l8.5-11.5h-6L13 2z"/></svg></span>`;
}

/** 渲染UP主头像（含右下角小闪电徽章） */
function renderAvatarWrap(face, uname, officialType, sizeCls){
  const wrapCls = sizeCls === 'sm' ? 'upper-avatar-wrap' : 'uc-avatar-wrap';
  const imgCls = sizeCls === 'sm' ? 'upper-avatar' : 'uc-avatar';
  const phCls = sizeCls === 'sm' ? 'upper-avatar-ph' : 'uc-avatar-ph';
  const badge = officialBadgeHtml(officialType);
  const imgHtml = face ? (()=>{ const cu=imgSrc(face); return cu ? `<img class="${imgCls}" src="${esc(cu)}" onerror="this.style.display='none'">` : `<img class="${imgCls}" data-img-url="${esc(face)}" onerror="this.style.display='none'">`; })() : `<div class="${imgCls} ${phCls}">${esc((uname||'?').charAt(0))}</div>`;
  return `<div class="${wrapCls}">${imgHtml}${badge}</div>`;
}

/** 渲染UP主名称（大会员粉色名字） baseCls: 父级字体类，如 'upper-name' 或 '' */
function renderUpperName(uname, vipType, vipStatus, baseCls){
  const isVip = (vipType > 0 && vipStatus === 1);
  const cls = baseCls ? (isVip ? `${baseCls} vip-name` : baseCls) : (isVip ? 'vip-name' : '');
  return `<span class="${cls}">${esc(uname)}</span>`;
}

/** 渲染UP主 UID（大会员粉色），与名字配色一致 */
function renderUpperUid(mid, vipType, vipStatus){
  const isVip = (vipType > 0 && vipStatus === 1);
  const cls = isVip ? 'uc-uid vip-name' : 'uc-uid';
  return `<span class="${cls}">UID: ${esc(mid||'')}</span>`;
}

/** 加载关注分组(分类) */
async function loadFollowTags(){
  try{
    const tags = await callBridge('getFollowTags');
    state._followTags = tags || [];
    state._followTagsLoaded = true;
  }catch(e){
    state._followTags = [];
    state._followTagsLoaded = true;
    jsLog('加载关注分组失败: '+e);
  }
}

/** 刷新关注分组(清除缓存后重新请求，用于风控后重试) */
async function retryLoadTags(){
  try{
    state._followTags = null;
    state._followTagsLoaded = false;
    const tags = await callBridge('refreshFollowTags');
    state._followTags = tags || [];
    state._followTagsLoaded = true;
    // 重新渲染以更新分组条
    const container = el('followingsContainer');
    if(container) renderFollowings(container);
  }catch(e){
    state._followTags = [];
    state._followTagsLoaded = true;
    jsLog('刷新关注分组失败: '+e);
  }
}

async function loadFollowings(reset){
  const container = el('followingsContainer');
  if(!container) return;
  try{
    const web = (state._loginInfo && state._loginInfo.web) || {};
    const tv = (state._loginInfo && state._loginInfo.tv) || {};
    const mid = web.mid || tv.mid || '';
    if(!mid){
      container.innerHTML = `<div style="color:var(--fg-dim);font-size:12px;padding:10px 0">无法获取用户ID</div>`;
      return;
    }
    if(reset){
      state._followPage = 1;
      state._followings = null;
      // 显示加载中
      container.className = 'followings-content';
      container.innerHTML = `<div style="padding:12px 0;color:var(--fg-dim);font-size:12px;display:flex;align-items:center;gap:6px">${spinIcon()} 加载中…</div>`;
      // 首次加载时获取关注分组
      if(!state._followTags) await loadFollowTags();
    }
    const page = state._followPage;
    const res = await callBridge('getFollowings', mid, page, state._followOrderType, state._followTagId);
    if(reset || !state._followings){
      state._followings = res.items || [];
    } else {
      // 加载更多：追加到列表
      state._followings = (state._followings || []).concat(res.items || []);
    }
    state._followTotal = res.total || 0;
    renderFollowings(container);
  }catch(e){
    container.innerHTML = `<div style="color:var(--error);font-size:12px;padding:10px 0">加载关注列表失败：${esc(String(e))}</div>`;
  }
}

/** 切换关注分组(分类) */
function switchFollowTag(tagId){
  state._followTagId = tagId;
  loadFollowings(true);
}

/** 切换排序方式 */
function switchFollowOrder(orderType){
  state._followOrderType = orderType;
  loadFollowings(true);
}

/** 加载更多关注 */
function loadMoreFollowings(){
  state._followPage++;
  loadFollowings(false);
}

function renderFollowings(container){
  // 修复布局：容器原带 fav-folders-loading 的 display:flex，会导致子元素横向排列
  container.className = 'followings-content';
  const list = state._followings || [];
  const total = state._followTotal || 0;
  const tags = state._followTags || [];
  const currentTagId = state._followTagId;

  // 更新标题旁的关注数(小字)
  const countEl = el('followCountText');
  if(countEl) countEl.textContent = total > 0 ? ` · 共关注 ${total} 个UP主` : '';

  if(list.length === 0 && tags.length === 0){
    const hint = state._followTagsLoaded
      ? `<div style="color:var(--fg-dim);font-size:12px;padding:10px 0">暂无关注 · 分组可能被风控限制<br><span class="follow-retry-link" onclick="retryLoadTags()">点击刷新分组</span></div>`
      : `<div style="color:var(--fg-dim);font-size:12px;padding:10px 0">暂无关注</div>`;
    container.innerHTML = hint;
    return;
  }

  // 关注分组(分类)筛选条
  const tagBarHtml = tags.length > 0 ? `
    <div class="follow-tag-bar">
      <div class="follow-tag-item ${currentTagId===0?'active':''}" onclick="switchFollowTag(0)">全部</div>
      ${tags.map(t=>`<div class="follow-tag-item ${currentTagId===t.tagid?'active':''}" onclick="switchFollowTag(${t.tagid})">${esc(t.name)}${t.count>0?`<span class="follow-tag-count">${t.count}</span>`:''}</div>`).join('')}
    </div>` : (state._followTagsLoaded && list.length > 0 ? `
    <div class="follow-tag-bar follow-tag-bar-hint">
      <span class="follow-tag-hint-text">分组加载受限</span>
      <span class="follow-retry-link" onclick="retryLoadTags()">刷新分组</span>
    </div>` : '');

  // 排序切换
  const orderBarHtml = `
    <div class="follow-order-bar">
      <span class="follow-order-label">排序：</span>
      <span class="follow-order-item ${state._followOrderType==='attention'?'active':''}" onclick="switchFollowOrder('attention')">最常访问</span>
      <span class="follow-order-item ${state._followOrderType===''?'active':''}" onclick="switchFollowOrder('')">最近关注</span>
    </div>`;

  // 选中分组但无结果时提示
  const emptyTagHint = (currentTagId !== 0 && list.length === 0)
    ? `<div style="color:var(--fg-dim);font-size:12px;padding:10px 0">该分组暂无UP主或加载受限<br><span class="follow-retry-link" onclick="switchFollowTag(0)">查看全部关注</span></div>`
    : '';

  // 是否还有更多
  const hasMore = list.length < total;

  container.innerHTML = `${tagBarHtml}${orderBarHtml}
  ${emptyTagHint}
  <div class="upper-list">${list.map(u=>`
    <div class="upper-item" onclick="viewFollowingVideos('${esc(u.mid)}','${esc(u.uname)}','${esc(u.face||'')}',${u.officialType!=null?u.officialType:-1},${u.vipType||0},${u.vipStatus||0})">
      ${renderAvatarWrap(u.face, u.uname, u.officialType, 'sm')}
      <div class="upper-info">
        <div class="upper-name-row">${renderUpperName(u.uname, u.vipType, u.vipStatus, 'upper-name')}${u.special===1?'<span class="special-star" title="特别关注">★</span>':''}</div>
        <div class="upper-meta-row">
          <div class="upper-sign">${esc(u.sign||'')}</div>
          ${u.fans>0?`<span class="upper-fans">${fmtCount(u.fans)}粉</span>`:''}
        </div>
      </div>
      <svg class="upper-arrow" viewBox="0 0 24 24" width="16" height="16"><path fill="currentColor" d="M9 5l7 7-7 7"/></svg>
    </div>
  `).join('')}</div>
  ${hasMore ? `<button class="follow-load-more" onclick="loadMoreFollowings()"><span>加载更多 (${list.length}/${total})</span></button>` : (total > 0 ? `<div class="follow-list-end">已全部加载 (${total}个)</div>` : '')}`;
  // 关注列表渲染在 sidebar 容器中，不走 renderEditor，需手动触发图片懒加载
  applyCachedImages(container);
}

/** 查看关注UP主的投稿视频（复用投稿视频渲染逻辑） */
async function viewFollowingVideos(mid, uname, face, officialType, vipType, vipStatus){
  openUpperSpace(mid, uname, face, officialType, vipType, vipStatus);
}

/* ===== 收藏夹功能 ===== */
async function loadFavFolders(){
  const container = el('favFoldersContainer');
  if(!container) return;
  try{
    const folders = await callBridge('getFavFolders');
    state._favFolders = folders;
    renderFavFolders(container, folders);
  }catch(e){
    container.innerHTML = `<div style="color:var(--error);font-size:12px;padding:10px 0">加载收藏夹失败：${esc(String(e))}</div>`;
  }
}

function renderFavFolders(container, folders){
  if(!folders || folders.length === 0){
    container.innerHTML = `<div style="color:var(--fg-dim);font-size:12px;padding:10px 0">暂无收藏夹</div>`;
    return;
  }
  container.innerHTML = `<div class="fav-folder-grid">${folders.map(f=>`
    <div class="fav-folder-card" onclick="openFavFolder('${esc(f.id)}','${esc(f.title)}',${f.mediaCount})">
      <div class="ff-icon">
        <svg viewBox="0 0 24 24" width="16" height="16"><path fill="currentColor" d="M10 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-8l-2-2z"/></svg>
      </div>
      <div class="ff-body">
        <div class="ff-title">${esc(f.title)}</div>
        <div class="ff-count">${f.mediaCount} 个视频</div>
      </div>
    </div>
  `).join('')}</div>`;
}

async function openFavFolder(mediaId, title, count){
  state._favMediaId = mediaId;
  state._favFolderTitle = title;
  state._favPage = 1;
  state._favTotal = count;
  state._favList = null;
  await loadFavList();
}

async function loadFavList(){
  const eb = editorBody();
  eb.innerHTML = `<div class="view">
    ${subHeader(state._favFolderTitle, "switchView('account')")}
    <p class="lead">${state._favTotal} 个视频 · 第 ${state._favPage} 页</p>
    <div class="loading-pulse">${spinIcon()} 加载中…</div>
  </div>`;
  try{
    const res = await callBridge('getFavList', state._favMediaId, state._favPage);
    state._favList = res.items || [];
    state._favTotal = res.total || state._favTotal;
    renderFavListView();
  }catch(e){
    eb.innerHTML = `<div class="view">${subHeader(state._favFolderTitle, "switchView('account')")}<div style="color:var(--error)">加载失败：${esc(String(e))}</div></div>`;
  }
}

function renderFavListView(){
  const eb = editorBody();
  const items = state._favList || [];
  if(items.length === 0){
    eb.innerHTML = `<div class="view">
      ${subHeader(state._favFolderTitle, "switchView('account')")}
      <div class="empty-state">收藏夹为空</div>
    </div>`;
    return;
  }
  const pageSize = 20;
  const totalPages = Math.ceil(state._favTotal / pageSize) || 1;
  eb.innerHTML = `<div class="view">
    ${subHeader(state._favFolderTitle, "switchView('account')")}
    <p class="lead">${state._favTotal} 个视频 · 第 ${state._favPage}/${totalPages} 页</p>
    <div class="compact-row" style="margin-bottom:12px">
      <button class="btn btn-sec" style="font-size:11px;padding:5px 10px" id="favToggleAllBtn" onclick="toggleFavAll()">全选</button>
      <button class="btn btn-primary" style="font-size:11px;padding:5px 10px" onclick="downloadAllFav()">下载选中</button>
      <span id="favSelCount" style="font-size:11px;color:var(--fg-dim);margin-left:auto">0/${items.length} 已选</span>
    </div>
    <div class="vc-list">
      ${items.map((v,i)=>videoCardHTML(v, {index:i+1, showUpper:true, checkboxData:{bvid:v.bvid, checked:false, onchange:'updateFavSelCount()', extraClass:'fv-check'}})).join('')}
    </div>
    <div class="btn-row" style="margin-top:14px">
      ${state._favPage > 1 ? `<button class="btn btn-sec" onclick="state._favPage--;loadFavList()">上一页</button>` : ''}
      ${state._favPage < totalPages ? `<button class="btn btn-sec" onclick="state._favPage++;loadFavList()">下一页</button>` : ''}
    </div>
  </div>`;
  applyCachedImages(eb);
}

function fmtTime2(sec){
  const m = Math.floor(sec/60);
  const s = sec%60;
  return `${m}:${String(s).padStart(2,'0')}`;
}

async function downloadAllFav(){
  const checks = document.querySelectorAll('.fv-check:checked');
  if(checks.length === 0){ toast('请至少选择一个视频','err'); return; }
  const bvids = Array.from(checks).map(c=>c.dataset.bvid);
  // 直接设置视图状态，不调用 switchView 避免触发 renderEditor 显示空的新建下载页
  state.currentView = 'addtask';
  state.batchResults = [];
  state._lastUrl = bvids.join(' ');
  state._batchCollectionTitle = '';
  // 手动更新侧边栏高亮和标题
  document.querySelectorAll('.ab-btn').forEach(b=>b.classList.toggle('active', b.dataset.view==='addtask'));
  el('sidebarTitle').textContent = '新建下载';
  document.getElementById('app').classList.remove('show-sidebar');
  renderSidebar();
  // 直接显示解析进度，不经过 renderEditor
  editorBody().innerHTML = `<div class="view">
    <h1>批量下载</h1>
    <p class="lead">正在解析收藏夹中的 ${bvids.length} 个视频… (<span id="batchProg">0</span>/${bvids.length})</p>
    <div class="loading-pulse" style="padding:30px 0">${spinIcon()}<br><span style="font-size:12px;margin-top:8px;display:block;color:var(--fg-dim)">正在获取视频信息，请稍候</span></div>
  </div>`;
  try{
    const urlStr = bvids.join(' ');
    await callBridgeProgress('parseBatch', urlStr, (prog)=>{
      if(prog.items && prog.items.length){
        state.batchResults = state.batchResults.concat(prog.items);
      }
      const progEl = document.getElementById('batchProg');
      if(progEl) progEl.textContent = prog.processed || state.batchResults.length;
      if(prog.done) renderEditor();
    });
    if(state.batchResults && state.batchResults.length === 0) renderEditor();
  }catch(e){
    toast('解析失败：'+e,'err');
    state.batchResults = null;
    renderEditor();
  }
}

function toggleAllFavChecks(checked){
  document.querySelectorAll('.fv-check').forEach(c=>{c.checked=checked;vcSyncCheckStyle(c);});
  updateFavSelCount();
}

/** 全选/取消全选切换：根据当前选中状态自动判断 */
function toggleFavAll(){
  const all = document.querySelectorAll('.fv-check');
  const checked = document.querySelectorAll('.fv-check:checked');
  const shouldCheck = checked.length < all.length;
  toggleAllFavChecks(shouldCheck);
}

function updateFavSelCount(){
  const total = document.querySelectorAll('.fv-check').length;
  const checked = document.querySelectorAll('.fv-check:checked').length;
  const el2 = el('favSelCount');
  if(el2) el2.textContent = `${checked}/${total} 已选`;
  // 同步全选按钮文字
  const btn = document.getElementById('favToggleAllBtn');
  if(btn) btn.textContent = (checked === total && total > 0) ? '取消全选' : '全选';
}

/* ===== 合集检测 ===== */
/** 生成合集横幅HTML（可被局部更新调用，无需刷新整个页面） */
function collectionBannerHTML(){
  if(state._collectionLoading){
    return `<div class="collection-banner collection-loading">${spinIcon()} 正在检测合集…</div>`;
  }
  if(state._collectionInfo && state._collectionInfo.found){
    return `
      <div class="collection-banner">
        <div class="cb-icon">
          <svg viewBox="0 0 24 24" width="20" height="20"><path fill="currentColor" d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z"/></svg>
        </div>
        <div class="cb-body">
          <div class="cb-title">合集：${esc(state._collectionInfo.title)}</div>
          <div class="cb-desc">共 ${state._collectionInfo.total} 个视频${(!state._loginInfo || !state._loginInfo.isLogin) ? ' · <span style="color:var(--warn)">需登录后下载</span>' : ''}</div>
        </div>
        <div style="display:flex;gap:6px;flex:0 0 auto">
          <button class="btn btn-sec" style="font-size:11px;padding:6px 12px" onclick="showCollectionSelection()">选择下载</button>
          <button class="btn btn-primary" style="font-size:11px;padding:6px 12px" onclick="downloadCollection()">全部下载</button>
        </div>
      </div>`;
  }
  return '';
}

/** 局部更新合集横幅，避免整页刷新闪烁 */
function updateCollectionBanner(){
  const container = document.getElementById('collectionBannerContainer');
  if(container){
    container.innerHTML = collectionBannerHTML();
  }
}

async function checkCollectionForVideo(bvid){
  if(!bvid) return;
  state._collectionLoading = true;
  state._collectionInfo = null;
  // 先显示 loading 状态（局部更新）
  updateCollectionBanner();
  try{
    jsLog('checkCollection: ' + bvid);
    const res = await callBridge('checkCollection', bvid);
    state._collectionInfo = res;
    state._collectionType = res.type || '';  // "season"=合集, "series"=系列
    jsLog('checkCollection result: ' + JSON.stringify(res));
  }catch(e){
    jsLog('checkCollection error: ' + e);
    state._collectionInfo = { found: false };
  }finally{
    state._collectionLoading = false;
    // 局部更新合集横幅，不再调用 renderEditor() 刷新整个页面
    updateCollectionBanner();
  }
}

async function downloadCollection(){
  const ci = state._collectionInfo;
  if(!ci || !ci.found || !ci.bvidList || ci.bvidList.length === 0){
    toast('合集信息不可用','err');
    return;
  }
  jsLog('downloadCollection: ' + ci.title + ', ' + ci.bvidList.length + ' videos, hasMetas=' + !!(ci.videoMetas && ci.videoMetas.length));
  // 未登录时提示先登录
  if(!state._loginInfo || !state._loginInfo.isLogin){
    toast('合集下载需要登录B站账号，请先登录','err');
    switchView('account');
    return;
  }
  const bvids = ci.bvidList;
  toast(`开始下载合集「${ci.title}」共 ${bvids.length} 个视频…`,'ok');
  try{
    // 直接使用已有元数据构建任务，仅在元数据不完整时才回退到 parseBatch
    const metas = ci.videoMetas || [];
    const hasFullMetas = metas.length === bvids.length && metas.every(m=>m.aid && m.cid);
    if(hasFullMetas){
      // 元数据完整，直接构建批量任务（0次额外API请求）
      const tasks = metas.map((m, idx)=>({
        url: m.bvid,
        title: m.title || m.bvid,
        pic: m.pic || '',
        pages: [{index:1, aid:m.aid, cid:m.cid, epid:'', title:m.title||'', duration:m.duration||0}],
        collectionIndex: idx + 1,
        // 元数据字段（修复5）：从 CollectionVideoMeta 映射到任务字段
        upperName: m.ownerName || '',
        desc: m.desc || '',
        pubTime: m.pubdate || 0,
        bvid: m.bvid || '',
        ownerMid: m.ownerMid || '',
      }));
      applyDefaultSettings();
      const tasksWithOpts = tasks.map(t=>({
        ...t,
        videoId: state.batchQn || state.selectedQn || 'auto',
        preferCodec: state.selectedCodec,
        preferAudio: state.selectedAudio,
        downloadMode: state.downloadMode,
        downloadDanmaku: state.downloadDanmaku,
        skipMux: state.skipMux,
        skipSubtitle: state.skipSubtitle,
        skipCover: state.skipCover,
        skipAi: state.skipAi,
        videoAscending: state.videoAscending,
        audioAscending: state.audioAscending,
        filePattern: getFilePattern(false, true),
        forceHttp: state.forceHttp,
        collectionTitle: ci.title || '',
        delayPerPage: state.delayPerPage || 0,
        isCheese: false,
        upperName: t.upperName || '',
        desc: t.desc || '',
        pubTime: t.pubTime || 0,
        bvid: t.bvid || '',
        ownerMid: t.ownerMid || '',
      }));
      await callBridge('addBatchTasks', JSON.stringify(tasksWithOpts));
      toast(`已添加 ${tasks.length} 个下载任务`,'ok');
      state._collectionInfo = null;
      const freshTasks = await callBridge('getTasks');
      state._tasks = freshTasks;
      state._tasksStructSig = '';
      state._tasksContentSig = '';
      state._tasksStatusSig = '';
      state.selectedTaskId = null;
      state._skipAutoSelect = true;
      switchView('explorer');
    } else {
      // 元数据不完整，回退到 parseBatch（流式）
      const urlStr = bvids.join(' ');
      state._batchCollectionTitle = ci.title || '';
      state.batchResults = [];
      state._lastUrl = urlStr;
      editorBody().innerHTML = `<div class="view"><h1>下载合集</h1><div class="loading-pulse">${spinIcon()} 正在解析合集「${esc(ci.title)}」${bvids.length} 个视频… (<span id="batchProg">0</span>/${bvids.length})</div></div>`;
      await callBridgeProgress('parseBatch', urlStr, (prog)=>{
        if(prog.items && prog.items.length){
          state.batchResults = state.batchResults.concat(prog.items);
        }
        const progEl = document.getElementById('batchProg');
        if(progEl) progEl.textContent = prog.processed || state.batchResults.length;
        if(prog.done && state.currentView === 'addtask') renderEditor();
      });
      if(state.currentView === 'addtask' && state.batchResults && state.batchResults.length === 0) renderEditor();
    }
  }catch(e){ toast('合集下载失败：'+e,'err'); if(state.currentView === 'addtask') renderEditor(); }
}

async function showCollectionSelection(){
  const ci = state._collectionInfo;
  if(!ci || !ci.found || !ci.bvidList || ci.bvidList.length === 0){
    toast('合集信息不可用','err');
    return;
  }
  // 未登录时提示先登录
  if(!state._loginInfo || !state._loginInfo.isLogin){
    toast('合集下载需要登录B站账号，请先登录','err');
    switchView('account');
    return;
  }
  const eb = editorBody();
  eb.innerHTML = `<div class="view">
    <h1>选择合集视频</h1>
    <p class="lead">合集：${esc(ci.title)} · 共 ${ci.bvidList.length} 个视频</p>
    <div class="loading-pulse">${spinIcon()} 正在加载视频列表…</div>
  </div>`;
  try{
    // 直接使用 checkCollection 返回的 videoMetas（0次额外API请求）
    // 不再调用 parseBatch（会触发大量串行API请求导致412限流）
    const metas = ci.videoMetas || [];
    // 合集API可能不返回每视频的owner信息，回退到单个视频解析的UP主信息
    const vi = state.videoInfo || {};
    const fallbackOwner = vi.upperName || '';
    const fallbackFace = vi.ownerFace || '';
    const fallbackMid = vi.ownerMid || '';
    const fallbackOfficial = vi.officialType != null ? vi.officialType : -1;
    const fallbackVipType = vi.vipType || 0;
    const fallbackVipStatus = vi.vipStatus || 0;
    let results;
    if(metas.length === ci.bvidList.length && metas.some(m=>m.title)){
      // 元数据完整，直接使用
      results = metas.map(m=>({
        url: m.bvid,
        bvid: m.bvid,
        title: m.title || m.bvid,
        pic: m.pic || '',
        ok: true,
        pages: m.aid ? [{index:1, aid:m.aid, cid:m.cid||'', title:m.title||'', duration:m.duration||0}] : [],
        // 补全全部字段（修复2）：使合集视频卡片与其他页面统一，使用同一个 videoCardHTML
        // 合集API可能不返回每视频的owner信息，回退到单个视频解析的UP主信息
        duration: m.duration || 0,
        pubdate: m.pubdate || 0,
        ownerName: m.ownerName || fallbackOwner,
        upperName: m.ownerName || fallbackOwner,
        pubTime: m.pubdate || 0,
        ownerFace: m.ownerFace || fallbackFace,
        ownerMid: m.ownerMid || fallbackMid,
        play: m.play || 0,
        danmaku: m.danmaku || 0,
        officialType: (m.officialType != null && m.officialType !== -1) ? m.officialType : fallbackOfficial,
        vipType: m.vipType || fallbackVipType,
        vipStatus: m.vipStatus || fallbackVipStatus,
        desc: m.desc || '',
      }));
    } else {
      // 元数据不完整（如 series 类型仅有 bvid），直接使用已有数据
      // aid/cid 将在下载时由下载引擎自动获取，避免串行请求导致412限流
      results = ci.bvidList.map(bv => {
        const meta = metas.find(m=>m.bvid===bv);
        if(meta && meta.title){
          return {
            url: bv, bvid: bv, title: meta.title, pic: meta.pic||'',
            ok: true,
            pages: meta.aid ? [{index:1, aid:meta.aid, cid:meta.cid||'', title:meta.title, duration:meta.duration||0}] : [],
            // 补全全部字段（修复2）：使合集视频卡片与其他页面统一
            // 合集API可能不返回每视频的owner信息，回退到单个视频解析的UP主信息
            duration: meta.duration || 0,
            pubdate: meta.pubdate || 0,
            ownerName: meta.ownerName || fallbackOwner,
            upperName: meta.ownerName || fallbackOwner,
            pubTime: meta.pubdate || 0,
            ownerFace: meta.ownerFace || fallbackFace,
            ownerMid: meta.ownerMid || fallbackMid,
            play: meta.play || 0,
            danmaku: meta.danmaku || 0,
            officialType: (meta.officialType != null && meta.officialType !== -1) ? meta.officialType : fallbackOfficial,
            vipType: meta.vipType || fallbackVipType,
            vipStatus: meta.vipStatus || fallbackVipStatus,
            desc: meta.desc || '',
          };
        }
        // 仅有 bvid，无标题信息，显示 bvid 作为标题
        return { url: bv, bvid: bv, title: bv, pic: '', ok: true, pages: [] };
      });
    }
    state._collectionVideos = results;
    // 按发布时间从早到晚排序，确保 P1=最早发布的视频（还原原版BBDown行为）
    state._collectionVideos.sort((a,b)=>(a.pubdate||a.pubTime||0)-(b.pubdate||b.pubTime||0));
    // 按排序后顺序重新分配页码（单视频时每个视频只有1页，序号即视频在合集中的位置）
    state._collectionVideos.forEach((v,i)=>{
      v.collectionIndex = i + 1;
      if(v.pages && v.pages.length === 1) v.pages[0].index = i + 1;
    });
    // 仅在用户仍在 addtask 视图时才渲染
    if(state.currentView === 'addtask'){
      renderCollectionSelection();
    }
  }catch(e){
    toast('加载视频信息失败：'+e,'err');
    if(state.currentView === 'addtask') renderEditor();
  }
}

function renderCollectionSelection(){
  const ci = state._collectionInfo;
  const results = state._collectionVideos || [];
  const eb = editorBody();
  eb.innerHTML = `<div class="view">
    ${subHeader('选择合集视频', "state._collectionVideos=null;renderEditor()")}
    <p class="lead">合集：${esc(ci.title)} · 共 ${results.length} 个视频</p>
    <div class="compact-row" style="margin-bottom:12px">
      <button class="btn btn-sec" style="font-size:11px;padding:5px 10px" onclick="toggleAllCollectionChecks(true)">全选</button>
      <button class="btn btn-sec" style="font-size:11px;padding:5px 10px" onclick="toggleAllCollectionChecks(false)">取消全选</button>
      <span id="colSelCount" style="font-size:11px;color:var(--fg-dim);margin-left:auto">${results.length}/${results.length} 已选</span>
    </div>
    <div class="vc-list">
      ${results.map((r,i)=>{
        if(!r.ok){
          return videoCardHTML({title:r.title||r.url, url:r.url, pic:r.pic, ok:false}, {index:i+1, failed:true, errorMsg:r.error||'未知错误', checkboxData:{bvid:r.url, checked:true, onchange:'updateColSelCount()', extraClass:'col-check', disabled:true}});
        }
        return videoCardHTML({
          title:r.title, pic:r.pic, bvid:r.bvid, url:r.url,
          duration:r.duration, pubdate:r.pubdate||r.pubTime||0,
          ownerName:r.ownerName||r.upperName||r.author||'',
          ownerFace:r.ownerFace||r.face||'',
          officialType:r.officialType!=null?r.officialType:-1,
          vipType:r.vipType||0, vipStatus:r.vipStatus||0,
          play:r.play||0, danmaku:r.danmaku||0,
          pages:r.pages
        }, {index:i+1, showUpper:true, checkboxData:{bvid:r.url, checked:true, onchange:'updateColSelCount()', extraClass:'col-check'}});
      }).join('')}
    </div>
    <div class="btn-row" style="margin-top:14px">
      <button class="btn btn-primary" onclick="downloadSelectedCollection()">下载选中视频</button>
    </div>
  </div>`;
  updateColSelCount();
  applyCachedImages(eb);
}

function toggleAllCollectionChecks(checked){
  document.querySelectorAll('.col-check').forEach(c=>{c.checked=checked;vcSyncCheckStyle(c);});
  updateColSelCount();
}

function updateColSelCount(){
  const total = document.querySelectorAll('.col-check').length;
  const checked = document.querySelectorAll('.col-check:checked').length;
  const el2 = el('colSelCount');
  if(el2) el2.textContent = `${checked}/${total} 已选`;
}

async function downloadSelectedCollection(){
  const checks = document.querySelectorAll('.col-check:checked:not([disabled])');
  if(checks.length === 0){ toast('请至少选择一个视频','err'); return; }
  const selectedUrls = new Set(Array.from(checks).map(c=>c.dataset.bvid));
  // 从已获取的视频信息中筛选选中的
  const allVideos = state._collectionVideos || [];
  const selected = allVideos.filter(r=>selectedUrls.has(r.url));
  if(selected.length === 0){ toast('未找到选中的视频','err'); return; }
  // 重新分配连续序号，确保选中视频的 P1,P2,P3... 是连续的
  selected.forEach((v,i)=>{
    v.collectionIndex = i + 1;
    if(v.pages && v.pages.length === 1) v.pages[0].index = i + 1;
  });
  state.batchResults = selected;
  state._lastUrl = selected.map(r=>r.url).join(' ');
  // 保留合集名称用于创建子文件夹
  if(state._collectionInfo && state._collectionInfo.title){
    state._batchCollectionTitle = state._collectionInfo.title;
  }
  state._collectionVideos = null;
  toast(`已选择 ${selected.length} 个视频`,'ok');
  renderEditor();
}

/* ===== 设置(含调试日志) ===== */
function renderSettings(eb){
  const s = state.settings || {};
  const theme = state._theme || 'dark';
  // 注册默认模式下拉
  const setModeOpts = [['all','完整下载(含字幕/封面/弹幕)'],['video_only','仅视频(有声不含附加)'],['audio_only','仅音频'],['subtitle_only','仅字幕'],['cover_only','仅封面'],['danmaku_only','仅弹幕']];
  window._csOpts['set_mode'] = setModeOpts;
  window._csVal['set_mode'] = s.downloadMode||'all';
  window._csCallback['set_mode'] = (v)=>{ saveSetting('downloadMode', v); state.downloadMode=v; };
  // 批量清晰度：软偏好（auto=每个视频取该编码最高可用，固定值时缺失的视频自动回退自身最高可用）
  const batchQnOpts = [['auto','自动（每视频取最高，含8K/杜比视界）'],['127','8K 超高清'],['126','杜比视界'],['125','HDR 真彩'],['120','4K 超清'],['116','1080P 高帧率'],['112','1080P 高码率'],['80','1080P 高清'],['74','720P 高帧率'],['64','720P 高清'],['32','480P 清晰'],['16','360P 流畅']];
  window._csOpts['set_batchQn'] = batchQnOpts;
  window._csVal['set_batchQn'] = s.batchQn||'auto';
  window._csCallback['set_batchQn'] = (v)=>{ saveSetting('batchQn', v); state.batchQn = v; };
  eb.innerHTML = `<div class="view">
    <h1>设置</h1>
    <div class="theme-toggle">
      <div>
        <div class="tt-label">主题模式</div>
        <div class="tt-desc">切换深色/浅色外观</div>
      </div>
      <div class="theme-switch">
        <button class="${theme==='dark'?'active':''}" onclick="setTheme('dark');renderEditor()">深色</button>
        <button class="${theme==='light'?'active':''}" onclick="setTheme('light');renderEditor()">浅色</button>
        <button class="${theme==='system'?'active':''}" onclick="setTheme('system');renderEditor()">跟随系统</button>
      </div>
    </div>
    <div class="form-row">
      <label class="field-label">下载目录</label>
      <input type="text" id="set_output_dir" value="${esc(s.output_dir||'')}" placeholder="应用私有目录">
      <div style="font-size:10.5px;color:var(--fg-dim);margin-top:5px;line-height:1.5">
        默认使用应用私有存储（无需权限，Android 11+ 可直接写入）<br>
        如需保存到公共目录(如 Movies/)，需授予「所有文件访问权限」
      </div>
    </div>
    <div class="form-row">
      <label class="field-label">文件访问权限</label>
      <div id="permStatus" style="padding:10px 12px;background:var(--card-bg);border:1px solid var(--card-border);border-radius:8px;font-size:12px;display:flex;align-items:center;justify-content:space-between;gap:8px">
        <span id="permStatusText" style="color:var(--fg-dim)">检查中…</span>
        <button class="btn btn-sec" style="font-size:11px;padding:5px 12px;flex:0 0 auto" onclick="requestStoragePermission()">授予权限</button>
      </div>
    </div>
    <div class="compact-opts">
      <div class="compact-field">
        <label>线程数</label>
        <div class="pill-group">
          ${[4,8,16].map(n=>`<button class="${(s.threads||'8')==String(n)?'active':''}" onclick="saveSetting('threads','${n}')">${n}</button>`).join('')}
        </div>
      </div>
      <div class="compact-field">
        <label>默认模式</label>
        ${csHTML('set_mode', setModeOpts, s.downloadMode||'all')}
      </div>
      <div class="compact-field">
        <label>批量清晰度</label>
        ${csHTML('set_batchQn', batchQnOpts, s.batchQn||'auto')}
        <div style="font-size:10px;color:var(--fg-dim);margin-top:4px">批量/合集下载的清晰度偏好：自动=每个视频独立取最高可用（含8K/杜比视界，不区分编码）；固定值=有则用，没有自动回退该视频最高可用</div>
      </div>
      <div class="compact-field">
        <label>编码优先级</label>
        <div class="pill-group">
          <button class="${(s.preferCodec||'avc')==='avc'?'active':''}" onclick="saveSetting('preferCodec','avc'); state.selectedCodec='avc'">AVC</button>
          <button class="${(s.preferCodec||'avc')==='hevc'?'active':''}" onclick="saveSetting('preferCodec','hevc'); state.selectedCodec='hevc'">HEVC</button>
          <button class="${(s.preferCodec||'avc')==='av1'?'active':''}" onclick="saveSetting('preferCodec','av1'); state.selectedCodec='av1'">AV1</button>
        </div>
        <div style="font-size:10px;color:var(--fg-dim);margin-top:4px">自动选择时同清晰度优先的编码：AVC兼容最广(原版默认)、HEVC体积小硬解普及、AV1体积最小但老设备不支持</div>
      </div>
    </div>
    <div class="compact-field">
        <label>API类型</label>
        <div class="pill-group">
          <button class="${(state._apiType||'web')==='web'?'active':''}" onclick="setApiTypeSetting('web')">WEB</button>
          <button class="${(state._apiType||'web')==='tv'?'active':''}" onclick="setApiTypeSetting('tv')">TV</button>
          <button class="${(state._apiType||'web')==='app'?'active':''}" onclick="setApiTypeSetting('app')">APP</button>
          <button class="${(state._apiType||'web')==='intl'?'active':''}" onclick="setApiTypeSetting('intl')">国际版</button>
        </div>
        <div style="font-size:10px;color:var(--fg-dim);margin-top:4px">选择解析/下载使用的API类型</div>
      </div>
    <div class="adv-toggle" onclick="toggleSettingsAdv()">
      <span id="setAdvToggle">${state._showSetAdv?'▼':'▶'} 高级默认值</span>
    </div>
    ${state._showSetAdv ? `
      <div class="opt-list compact">
        <label class="opt-item"><input type="checkbox" class="checkbox" ${(s.skipSubtitle||'false')==='true'?'checked':''} onchange="saveSetting('skipSubtitle',this.checked?'true':'false')"><span>跳过字幕</span></label>
        <label class="opt-item"><input type="checkbox" class="checkbox" ${(s.skipCover||'false')==='true'?'checked':''} onchange="saveSetting('skipCover',this.checked?'true':'false')"><span>跳过封面</span></label>
        <label class="opt-item"><input type="checkbox" class="checkbox" ${(s.skipAi||'true')==='true'?'checked':''} onchange="saveSetting('skipAi',this.checked?'true':'false')"><span>跳过AI字幕</span></label>
        <label class="opt-item"><input type="checkbox" class="checkbox" ${(s.skipMux||'false')==='true'?'checked':''} onchange="saveSetting('skipMux',this.checked?'true':'false')"><span>跳过混流</span></label>
        <label class="opt-item"><input type="checkbox" class="checkbox" ${(s.downloadDanmaku||'false')==='true'?'checked':''} onchange="saveSetting('downloadDanmaku',this.checked?'true':'false')"><span>附带弹幕</span></label>
        <label class="opt-item"><input type="checkbox" class="checkbox" ${(s.videoAscending||'false')==='true'?'checked':''} onchange="saveSetting('videoAscending',this.checked?'true':'false')"><span>视频升序</span></label>
        <label class="opt-item"><input type="checkbox" class="checkbox" ${(s.audioAscending||'false')==='true'?'checked':''} onchange="saveSetting('audioAscending',this.checked?'true':'false')"><span>音频升序</span></label>
        <label class="opt-item"><input type="checkbox" class="checkbox" ${(s.forceHttp||'false')==='true'?'checked':''} onchange="saveSetting('forceHttp',this.checked?'true':'false')"><span>强制HTTP</span></label>
        <div class="opt-item" style="flex-direction:row;align-items:center;gap:8px">
          <span style="font-size:11px;white-space:nowrap">任务间隔(秒)</span>
          <input type="number" class="text-input" style="font-size:11px;padding:4px 8px;width:60px" value="${s.delayPerPage||0}" min="0" max="60" onchange="saveSetting('delayPerPage',String(parseInt(this.value)||0))">
        </div>
        <div class="opt-item" style="flex-direction:column;align-items:stretch;gap:4px">
          <span style="font-size:11px">普通视频命名模板</span>
          <input type="text" class="text-input" style="font-size:11px;padding:4px 8px" value="${esc(s.filePattern||'{pageTitle}')}" onchange="saveSetting('filePattern',this.value)">
        </div>
        <div class="opt-item" style="flex-direction:column;align-items:stretch;gap:4px">
          <span style="font-size:11px">多P视频命名模板</span>
          <input type="text" class="text-input" style="font-size:11px;padding:4px 8px" value="${esc(s.filePatternMultiPage||'{pageTitle} P{pageNumber}')}" onchange="saveSetting('filePatternMultiPage',this.value)">
        </div>
        <div class="opt-item" style="flex-direction:column;align-items:stretch;gap:4px">
          <span style="font-size:11px">合集视频命名模板</span>
          <input type="text" class="text-input" style="font-size:11px;padding:4px 8px" value="${esc(s.filePatternCollection||'{collectionIndex}. {pageTitle}')}" onchange="saveSetting('filePatternCollection',this.value)">
        </div>
        <div class="opt-item" style="flex-direction:column;align-items:stretch;gap:4px">
          <span style="font-size:11px">合集多P命名模板</span>
          <input type="text" class="text-input" style="font-size:11px;padding:4px 8px" value="${esc(s.filePatternCollectionMultiPage||'{collectionIndex}. {videoTitle} P{pageNumber}')}" onchange="saveSetting('filePatternCollectionMultiPage',this.value)">
        </div>
        ${filePatternVarsTable()}
      </div>
    ` : ''}
    <div class="switch-row" style="margin-top:8px">
      <div class="sw-text">
        <div class="sw-label">退出时清理已完成任务</div>
        <div class="sw-desc">应用退出时自动移除已完成的任务记录</div>
      </div>
      <label class="switch">
        <input type="checkbox" ${(s.clearOnExit||'false')==='true'?'checked':''} onchange="saveSetting('clearOnExit',this.checked?'true':'false')">
        <span class="slider"></span>
      </label>
    </div>
    <div class="btn-row" style="margin-top:12px">
      <button class="btn btn-primary" onclick="saveOutputDir()">保存目录</button>
    </div>
    <h2>缓存管理</h2>
    <div class="btn-row">
      <button class="btn btn-sec" onclick="showCacheManage()">缓存管理</button>
    </div>
    <div id="cache-size-line" style="font-size:11px;color:var(--fg-dim);margin-top:6px">加载中…</div>
    <h2>调试</h2>
    <div class="switch-row">
      <div class="sw-text">
        <div class="sw-label">调试服务器</div>
        <div class="sw-desc">端口 19865，同一 WiFi 浏览器访问 http://手机IP:19865/ 实时查看调试日志与崩溃日志（默认关闭）</div>
      </div>
      <label class="switch">
        <input type="checkbox" ${(s.debug_server||'false')==='true'?'checked':''} onchange="saveSetting('debug_server',this.checked?'true':'false')">
        <span class="slider"></span>
      </label>
    </div>
    <div class="btn-row">
      <button class="btn btn-sec" onclick="showDebugLogs()">查看调试日志</button>
    </div>
    <div id="debug-log-count-line" style="font-size:11px;color:var(--fg-dim);margin-top:6px">加载中…</div>
    <h2>崩溃日志</h2>
    <div id="crash-log-count-line" style="font-size:11px;color:var(--fg-dim);margin-bottom:8px">加载中…</div>
    <div class="btn-row">
      <button class="btn btn-sec" onclick="showCrashLogs()">查看崩溃日志</button>
    </div>
    <h2>更新</h2>
    <div class="btn-row">
      <button class="btn btn-sec" onclick="checkUpdateNow(false)">检查更新</button>
    </div>
    <div class="switch-row" style="margin-top:8px">
      <div class="sw-text">
        <div class="sw-label">启动时检查更新</div>
        <div class="sw-desc">启动后静默检查 GitHub 新版本，有新版本才提示（仓库: xialiag/BBDownAndroid）</div>
      </div>
      <label class="switch">
        <input type="checkbox" ${(s.check_update||'true')==='true'?'checked':''} onchange="saveSetting('check_update',this.checked?'true':'false')">
        <span class="slider"></span>
      </label>
    </div>
  </div>`;
  // 进入设置页后主动检查权限状态，避免一直停留在"检查中…"
  setTimeout(()=>checkStoragePermissionStatus(), 60);
  // 异步加载日志统计
  setTimeout(()=>loadLogStats(), 100);
  // 异步加载缓存大小统计
  setTimeout(()=>loadCacheStats(), 100);
}

/** 加载并显示日志统计信息 */
async function loadLogStats(){
  try{
    const stats = await callBridge('getLogStats');
    const debugEl = document.getElementById('debug-log-count-line');
    if(debugEl){
      debugEl.textContent = `调试日志: ${stats.debugCount} 条`;
    }
    const crashEl = document.getElementById('crash-log-count-line');
    if(crashEl){
      crashEl.textContent = `崩溃日志: ${stats.crashCount} 个`;
    }
  }catch(e){
    const debugEl = document.getElementById('debug-log-count-line');
    if(debugEl) debugEl.textContent = '';
    const crashEl = document.getElementById('crash-log-count-line');
    if(crashEl) crashEl.textContent = '';
  }
}

function toggleSettingsAdv(){
  state._showSetAdv = !state._showSetAdv;
  renderEditor();
}

/* ===== 缓存管理 ===== */

/* ---------- 检查 GitHub 更新（固定仓库 xialiag/BBDownAndroid） ---------- */
async function checkUpdateNow(silent){
  try{
    const res = await callBridge('checkUpdate');
    if(!res || !res.hasUpdate){ if(!silent) toast('当前已是最新版本 ('+(res&&res.current||'')+')','ok'); return; }
    showConfirm({
      icon: okIcon(),
      title: '发现新版本 '+res.latest,
      message: '当前版本: '+res.current+'\n\n'+(res.note||'').slice(0,400),
      confirmText: '前往下载',
      onConfirm: async ()=>{ try{ await callBridge('openUrl', res.url); }catch(e){ toast('无法打开浏览器','err'); } }
    });
  }catch(e){ if(!silent) toast('检查更新失败: '+e,'err'); }
}
/* 启动时静默检查（MainActivity 6 秒后触发，有新版本才提示） */
window.__onCheckUpdate = function(){ checkUpdateNow(true); };

/** 格式化文件大小 */
function fmtCacheSize(bytes){
  if(bytes < 1024) return bytes + ' B';
  if(bytes < 1024*1024) return (bytes/1024).toFixed(1) + ' KB';
  if(bytes < 1024*1024*1024) return (bytes/(1024*1024)).toFixed(1) + ' MB';
  return (bytes/(1024*1024*1024)).toFixed(2) + ' GB';
}

/** 加载并在设置页显示缓存大小摘要 */
async function loadCacheStats(){
  try{
    const s = await callBridge('getCacheSizes');
    const el = document.getElementById('cache-size-line');
    if(el){
      el.textContent = `缓存总计: ${fmtCacheSize(s.total)}（临时文件 ${fmtCacheSize(s.tempFiles)} · 崩溃日志 ${fmtCacheSize(s.crashLogs||0)} · 调试日志 ${fmtCacheSize(s.debugLogs||0)} · WebView ${fmtCacheSize(s.webCache)}）`;
    }
  }catch(e){
    const el = document.getElementById('cache-size-line');
    if(el) el.textContent = '';
  }
}

/** 渲染缓存管理页面 */
async function showCacheManage(){
  const eb = editorBody();
  if(!eb){ toast('编辑区未就绪','err'); return; }
  eb.innerHTML = `<div class="view">
    ${subHeader('缓存管理', "renderEditor()")}
    <div class="loading-pulse">${spinIcon()} 正在扫描缓存…</div>
  </div>`;
  try{
    const s = await callBridge('getCacheSizes');
    eb.innerHTML = `<div class="view">
      ${subHeader('缓存管理', "renderEditor()")}
      <p class="lead">缓存总计 ${fmtCacheSize(s.total)}</p>
      <div class="cache-list">
        <div class="cache-item">
          <div class="ci-info">
            <div class="ci-name">下载临时文件</div>
            <div class="ci-desc">${s.tempCount} 个文件 · ${fmtCacheSize(s.tempFiles)}<br>.dl / .vpart / .apart / .meta 断点续传与混流临时文件</div>
          </div>
          <button class="btn btn-sec cache-clear-btn ${s.tempFiles>0?'':'disabled'}" onclick="clearCacheItem('temp')">清理</button>
        </div>
        <div class="cache-item">
          <div class="ci-info">
            <div class="ci-name">崩溃日志</div>
            <div class="ci-desc">${s.crashLogCount||0} 个文件 · ${fmtCacheSize(s.crashLogs||0)}<br>crash_*.txt 崩溃记录文件</div>
          </div>
          <button class="btn btn-sec cache-clear-btn ${(s.crashLogs||0)>0?'':'disabled'}" onclick="clearCacheItem('crashLogs')">清理</button>
        </div>
        <div class="cache-item">
          <div class="ci-info">
            <div class="ci-name">调试日志</div>
            <div class="ci-desc">${s.debugLogCount||0} 个文件 · ${fmtCacheSize(s.debugLogs||0)}<br>bbdown_log_*.txt 调试日志文件</div>
          </div>
          <button class="btn btn-sec cache-clear-btn ${(s.debugLogs||0)>0?'':'disabled'}" onclick="clearCacheItem('debugLogs')">清理</button>
        </div>
        <div class="cache-item">
          <div class="ci-info">
            <div class="ci-name">WebView 缓存</div>
            <div class="ci-desc">${fmtCacheSize(s.webCache)}<br>网页资源缓存，清理后首次加载稍慢</div>
          </div>
          <button class="btn btn-sec cache-clear-btn ${s.webCache>0?'':'disabled'}" onclick="clearCacheItem('webCache')">清理</button>
        </div>
      </div>
      <div class="btn-row" style="margin-top:16px">
        <button class="btn btn-primary" onclick="clearCacheItem('all')">全部清理</button>
        <button class="btn btn-sec" onclick="showCacheManage()">刷新</button>
      </div>
    </div>`;
  }catch(e){
    eb.innerHTML = `<div class="view">
      ${subHeader('缓存管理', "renderEditor()")}
      <p class="lead" style="color:var(--error)">获取缓存信息失败：${esc(String(e))}</p>
      <div class="btn-row"><button class="btn btn-sec" onclick="showCacheManage()">重试</button></div>
    </div>`;
  }
}

/** 清理指定类型的缓存 */
async function clearCacheItem(type){
  const names = {temp:'下载临时文件', crashLogs:'崩溃日志', debugLogs:'调试日志', logs:'日志文件', webCache:'WebView缓存', all:'全部缓存'};
  showConfirm({
    title: '清理缓存',
    message: `确定清理${names[type]||'缓存'}吗？`,
    confirmText: '清理',
    cancelText: '取消',
    onConfirm: async () => {
      try{
        const res = await callBridge('clearCache', type);
        toast(`已清理 ${fmtCacheSize(res.cleared)}`, 'ok');
        // 清理图片缓存（JS端 blob URL）
        if(type === 'all' || type === 'webCache'){
          clearImgCache();
        }
        showCacheManage();
        loadCacheStats();
      }catch(e){ toast('清理失败：'+e,'err'); }
    }
  });
}

/** 设置API类型 */
async function setApiTypeSetting(apiType){
  try{
    await callBridge('setApiType', apiType);
    state._apiType = apiType;
    toast('API类型已切换为：' + ({web:'WEB',tv:'TV',app:'APP',intl:'国际版'}[apiType]||apiType), 'ok');
    renderEditor();
  }catch(e){ toast('切换API类型失败','err'); }
}

/** 加载API类型 */
async function loadApiType(){
  try{
    const res = await callBridge('getApiType');
    state._apiType = res.apiType || 'web';
  }catch(e){ state._apiType = 'web'; }
}

/* 检查文件访问权限状态 */
async function checkStoragePermissionStatus(){
  const statusText = document.getElementById('permStatusText');
  if(!statusText) return;
  statusText.textContent = '检查中…';
  statusText.style.color = 'var(--fg-dim)';
  try{
    const res = await callBridge('checkStoragePermission');
    if(res.granted){
      statusText.textContent = '已授权（可写入公共目录）';
      statusText.style.color = 'var(--success)';
    } else {
      statusText.textContent = res.sdkInt >= 30 ? '未授权（仅可写入应用私有目录）' : '未授权';
      statusText.style.color = 'var(--fg-dim)';
    }
  }catch(e){
    statusText.textContent = '检查失败';
    statusText.style.color = 'var(--error)';
  }
  state._permCheckPending = false;
}

/* 请求文件访问权限 */
async function requestStoragePermission(){
  try{
    // 标记正在等待权限结果，返回app时自动重新检查
    state._permCheckPending = true;
    await callBridge('requestManageStorage');
  }catch(e){
    state._permCheckPending = false;
    toast('打开权限设置失败：'+e,'err');
  }
}

function colorizeLogs(raw){
  if(!raw) return '<span class="log-empty">(无日志)</span>';
  const lines = String(raw).split('\n');
  return lines.map(line=>{
    const safe = esc(line);
    // 匹配 [HH:mm:ss.SSS][LEVEL][TAG] 格式
    const m = line.match(/^\[([\d:.]+)\]\[([DIWE])\]\[([^\]]+)\]/);
    if(m){
      const lvl = m[1] ? m[2] : '';
      const time = m[1];
      const tag = m[3];
      const rest = safe.replace(/^\[[\d:.]+\]\[[DIWE]\]\[[^\]]+\]/, '');
      const lvlColor = lvl==='E'?'log-E':lvl==='W'?'log-W':lvl==='I'?'log-I':'log-D';
      return `<span class="log-line ${lvlColor}"><span class="log-time">[${time}]</span><span class="log-lvl log-lvl-${lvl}">[${lvl}]</span><span class="log-tag">[${esc(tag)}]</span>${rest}</span>`;
    }
    // 匹配 BBDown 控制台输出中的关键词
    if(/错误|error|failed|失败|异常|exception|崩溃|crash/i.test(line) && !/已修复|已恢复|成功/i.test(line)){
      return `<span class="log-line log-E">${safe}</span>`;
    }
    if(/警告|warning|warn|超时|timeout/i.test(line)){
      return `<span class="log-line log-W">${safe}</span>`;
    }
    if(/成功|完成|done|success|ok|已保存|已删除|已清除/i.test(line)){
      return `<span class="log-line log-I">${safe}</span>`;
    }
    return `<span class="log-line">${safe}</span>`;
  }).join('\n');
}

async function showDebugLogs(){
  try{
    const res = await callBridge('getDebugLogs');
    const logCount = res.logs ? res.logs.split('\n').filter(l=>l.trim()).length : 0;
    const eb = editorBody();
    eb.innerHTML = `<div class="view">
      ${subHeader('调试日志', "renderEditor()")}
      <p class="lead">共 ${logCount} 条日志</p>
      <div class="btn-row" style="margin-bottom:12px">
        <button class="btn btn-sec" onclick="showDebugLogs()">刷新</button>
        <button class="btn btn-sec" onclick="saveLogsToFile()">保存到文件</button>
        <button class="btn btn-sec" onclick="clearDebugLogs()">清除</button>
      </div>
      <pre class="debug-log-view">${colorizeLogs(res.logs)}</pre>
    </div>`;
    // 滚动到底部
    const pre = eb.querySelector('.debug-log-view');
    if(pre) pre.scrollTop = pre.scrollHeight;
  }catch(e){ toast('获取日志失败：'+e,'err'); }
}

async function saveLogsToFile(){
  try{
    const res = await callBridge('saveLogsToFile');
    if(res.path){
      toast('日志已保存','ok');
      // 提供分享选项（自定义弹窗，替代原生 confirm 旧安卓风格）
      showConfirm({
        title: '日志已保存',
        message: res.path,
        hint: '是否分享日志文件？',
        confirmText: '分享',
        cancelText: '关闭',
        onConfirm: async () => {
          try { await callBridge('shareLogFile', res.path); } catch(e2){ toast('分享失败：'+e2,'err'); }
        }
      });
    } else {
      toast('保存失败','err');
    }
  }catch(e){ toast('保存日志失败：'+e,'err'); }
}

async function clearDebugLogs(){
  try{
    await callBridge('clearDebugLogs');
    toast('日志已清除','ok');
    if(state.currentView==='settings') showDebugLogs();
    loadLogStats();
  }catch(e){ toast('清除失败','err'); }
}

/* ===== 崩溃日志查看 ===== */
async function showCrashLogs(){
  const eb = editorBody();
  if(!eb){ toast('编辑区未就绪','err'); return; }
  eb.innerHTML = `<div class="view">
    ${subHeader('崩溃日志', "switchView('settings')")}
    <div class="loading-pulse">${spinIcon()} 加载中…</div>
  </div>`;
  try{
    const logs = await callBridge('getCrashLogs');
    if(!logs || logs.length === 0){
      eb.innerHTML = `<div class="view">
        ${subHeader('崩溃日志', "switchView('settings')")}
        <div class="empty-state">暂无崩溃日志</div>
      </div>`;
      return;
    }
    const totalSize = logs.reduce((s,l)=>s+l.size,0);
    const totalKB = (totalSize/1024).toFixed(1);
    const logHtml = logs.map((log, i)=>{
      const time = new Date(log.time).toLocaleString();
      const sizeKB = (log.size / 1024).toFixed(1);
      return `<div class="crash-log-item">
        <div class="cli-header" onclick="this.parentElement.classList.toggle('expanded')">
          <span class="cli-title">${esc(log.filename)}</span>
          <span class="cli-meta">${time} · ${sizeKB}KB</span>
          <button class="cli-share" onclick="event.stopPropagation();shareCrashLog('${esc(log.filename)}')">分享</button>
          <button class="cli-delete" onclick="event.stopPropagation();deleteCrashLog('${esc(log.filename)}')">删除</button>
          <span class="cli-toggle">▼</span>
        </div>
        <pre class="cli-content">${esc(log.content)}</pre>
      </div>`;
    }).join('');
    eb.innerHTML = `<div class="view">
      ${subHeader('崩溃日志', "switchView('settings')")}
      <p class="lead">共 ${logs.length} 个崩溃日志 · 总计 ${totalKB}KB（点击展开/折叠）</p>
      <div class="btn-row" style="margin-bottom:12px">
        <button class="btn btn-sec" onclick="showCrashLogs()">刷新</button>
        <button class="btn btn-sec" onclick="clearCrashLogs()">清除全部</button>
      </div>
      ${logHtml}
    </div>`;
  }catch(e){
    eb.innerHTML = `<div class="view">
      ${subHeader('崩溃日志', "switchView('settings')")}
      <div style="color:var(--error)">加载失败：${esc(String(e))}</div>
    </div>`;
  }
}

async function shareCrashLog(filename){
  try{
    await callBridge('shareCrashLogFile', filename);
    toast('正在分享崩溃日志…','ok');
  }catch(e){ toast('分享失败：'+e,'err'); }
}

async function deleteCrashLog(filename){
  showConfirm({
    title: '删除崩溃日志',
    message: `确认删除 ${filename}？`,
    confirmText: '删除',
    cancelText: '取消',
    onConfirm: async () => {
      try{
        await callBridge('deleteCrashLog', filename);
        toast('已删除','ok');
        showCrashLogs();
      }catch(e){ toast('删除失败：'+e,'err'); }
    }
  });
}

async function clearCrashLogs(){
  showConfirm({
    title: '清除崩溃日志',
    message: '确认清除所有崩溃日志？此操作不可撤销。',
    icon: '<svg viewBox="0 0 24 24" width="28" height="28"><path fill="var(--warn)" d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
    confirmText: '清除',
    cancelText: '取消',
    onConfirm: async () => {
      try{
        await callBridge('clearCrashLogs');
        toast('崩溃日志已清除','ok');
        showCrashLogs();
        loadLogStats();
      }catch(e){ toast('清除失败','err'); }
    }
  });
}

async function saveSetting(key, value){
  try{
    await callBridge('setSetting', key, value);
    // 重新加载设置以获取可能被重定向的路径
    const fresh = await callBridge('getAllSettings');
    state.settings = fresh;
    toast('已保存','ok');
    renderEditor();
  }catch(e){ toast('保存失败：'+e,'err'); }
}

/** 调试服务器开关由设置页 switch 滑块直接 saveSetting('debug_server')，桥侧特判启停 */

async function saveOutputDir(){
  const v = el('set_output_dir').value.trim();
  if(!v){ toast('请输入目录','err'); return; }
  await saveSetting('output_dir', v);
  // 如果路径被重定向了，提示用户
  const saved = state.settings['output_dir'] || '';
  if(saved !== v){
    setTimeout(()=>toast('路径已自动重定向到应用私有存储（未授予文件访问权限，无法写入公共目录）','warn'), 500);
  } else {
    toast('下载目录已保存','ok');
  }
}

/* ===== 帮助 ===== */
function renderHelp(eb){
  eb.innerHTML = `<div class="view">
    <h1>使用说明</h1>
    <h2>支持的链接</h2>
    <p>· 完整链接: https://www.bilibili.com/video/BV1xxx</p>
    <p>· 短链: https://b23.tv/xxx</p>
    <p>· 番剧: https://www.bilibili.com/bangumi/play/epxxx</p>
    <p>· 课程: https://www.bilibili.com/cheese/play/epxxx</p>
    <p>· 纯ID: BV1xxx, av12345, ep12345, ss1234</p>
    <h2>下载模式</h2>
    <p>· 完整下载: 视频+音频合并+字幕+封面+弹幕</p>
    <p>· 仅视频: 视频+音频合并(不含字幕/弹幕等附加)</p>
    <p>· 仅音频: 只下载音频流(m4a)</p>
    <p>· 仅字幕: 下载字幕文件(srt)</p>
    <p>· 仅封面: 下载封面图片</p>
    <p>· 仅弹幕: 下载弹幕文件(xml)</p>
    <h2>批量下载</h2>
    <p>· 在输入框中用空格分割多个链接</p>
    <p>· 例: BV1xxx BV2yyy BV3zzz</p>
    <p>· 解析后会显示所有视频，一键批量下载</p>
    <h2>登录授权</h2>
    <p>· 点击左侧「账号」图标进入登录页</p>
    <p>· 网页版登录: 使用哔哩哔哩APP扫码，可下载大会员内容</p>
    <p>· TV版授权: 使用TV版二维码授权，获取TV端access_token</p>
    <p>· 两种登录可同时使用，互不影响</p>
    <h2>API类型</h2>
    <p>· 在设置中切换API类型: WEB / TV / APP / 国际版</p>
    <p>· 不同API类型可能获取不同的下载地址和清晰度</p>
    <p>· 遇到下载失败时可尝试切换API类型</p>
    <h2>搜索</h2>
    <p>· 点击左侧「搜索」图标</p>
    <p>· 需先登录B站账号</p>
    <p>· 切换「UP主」或「视频」搜索类型</p>
    <p>· 搜索UP主可查看投稿视频列表</p>
    <p>· 搜索视频可直接点击下载</p>
    <h2>收藏夹下载</h2>
    <p>· 登录后在账号页查看收藏夹列表</p>
    <p>· 点击收藏夹查看视频</p>
    <p>· 勾选视频后一键批量下载</p>
    <h2>关注列表</h2>
    <p>· 登录后在账号页查看已关注的UP主</p>
    <p>· 点击UP主查看其投稿视频</p>
    <p>· 可直接下载投稿视频</p>
    <h2>合集下载</h2>
    <p>· 解析视频时自动检测是否属于合集</p>
    <p>· 检测到合集后显示「下载整个合集」按钮</p>
    <p>· 合集内容会下载到以合集名命名的子文件夹</p>
    <h2>任务管理</h2>
    <p>· 左右滑动任务卡进入管理模式</p>
    <p>· 管理模式下再次滑动任务，自动选中两次滑动之间的所有任务</p>
    <p>· 点击任务可单独切换选中，全选按钮可一键选择</p>
    <p>· 支持批量暂停、继续/重试、删除</p>
    <p>· 支持按状态筛选: 全部 / 进行中 / 已完成 / 失败</p>
    <h2>日志管理</h2>
    <p>· 设置中点击「查看调试日志」查看运行日志</p>
    <p>· 设置中点击「查看崩溃日志」查看闪退记录</p>
    <p>· 崩溃日志支持单个分享和删除</p>
    <p>· 遇到问题时可分享日志文件便于排查</p>
    <h2>存储说明</h2>
    <p>· 默认下载到应用私有目录(Movies/BBDown)</p>
    <p>· 在设置中可自定义输出目录</p>
    <p>· 如需保存到公共目录，需授予「所有文件访问权限」</p>
    <p>· 支持自定义文件命名模板，四种类型独立设置</p>
    ${filePatternVarsTable()}
    <p style="font-size:10px;color:var(--fg-dim)">默认: 单P={pageTitle} | 多P={pageTitle} P{pageNumber} | 合集={collectionIndex}. {pageTitle} | 合集多P={collectionIndex}. {videoTitle} P{pageNumber}</p>
    <h2>其他设置</h2>
    <p>· 主题: 深色 / 浅色 / 跟随系统</p>
    <p>· 下载线程数: 默认8线程,可调4/16</p>
    <p>· 视频编码: 自动下载时同清晰度按「编码优先级」选择,默认 AVC(与原版 BBDown 一致,兼容性最广);可在设置中切换 HEVC(体积小、硬解普及)/ AV1(体积最小,老设备不支持)</p>
    <p>· 音频: 默认 M4A;Hi-Res FLAC 可在下载页「音频流」中选择——仅音频模式直接保存为 .flac,含视频模式自动转码 AAC 混流(FLAC 无法封装进 MP4)</p>
    <p>· 批量清晰度: 自动(每视频独立取最高,含8K/杜比视界)或固定清晰度(缺失自动回退)</p>
    <p>· 可跳过字幕、封面、AI字幕、混流等步骤</p>
    <p>· 强制HTTP: 将https地址转为http避免证书问题</p>
    <p>· 下载稳定性: 分片失败自动重试并切换备用CDN节点,整条流失败自动降级同清晰度其他编码</p>
  </div>`;
}

/* ===== 任务操作 ===== */
function backToTaskList(){
  state.selectedTaskId = null;
  state._skipAutoSelect = true;
  state._prevTaskStatus = null;
  state._taskDetailSig = '';
  renderTaskList(sidebarBody());
  renderEditor();
  // 返回列表时立即刷新任务状态
  try { forcePollNow(); } catch(e) {}
}

/** 安卓回退键处理：返回 true 表示已处理（导航回退），返回 false 表示已在根页面 */
function handleBackButton(){
  // 1. 有弹窗时关闭弹窗
  const overlay = document.querySelector('.modal-overlay');
  if(overlay){
    overlay.classList.remove('show');
    setTimeout(()=>overlay.remove(),220);
    return true;
  }
  // 2. 管理模式 → 退出管理模式
  if(state._taskManageMode){
    exitTaskManage();
    return true;
  }
  // 3. 任务详情 → 返回任务列表
  if(state.currentView === 'explorer' && state.selectedTaskId){
    backToTaskList();
    return true;
  }
  // 4. 有页面内返回按钮(.sn-back) → 触发其返回动作（等同于点击页面返回按钮，
  //    而非直接跳回任务列表。覆盖：搜索结果、UP投稿、收藏夹列表、合集/批量结果等子页面）
  const snBack = document.querySelector('#tabs .sn-back');
  if(snBack){
    snBack.click();
    return true;
  }
  // 5. 其它非根视图（无返回按钮的顶级视图，如账号/设置/帮助）→ 返回任务列表
  if(state.currentView !== 'explorer'){
    switchView('explorer');
    return true;
  }
  // 6. 已在根页面（任务列表）
  return false;
}

async function cancelTask(id){
  try{
    await callBridge('cancelTask', id);
    // 立即更新本地任务状态
    const t = (state._tasks||[]).find(x=>x.taskId===id);
    if(t){ t.status = 6; t.isRunning = false; t.speed = 0; }
    state._prevTaskStatus = null;
    state._taskDetailSig = '';
    toast('已取消','ok');
    renderEditor();
  }catch(e){ toast('取消失败','err'); }
}
async function pauseTask(id){
  try{
    await callBridge('pauseTask', id);
    // 立即更新本地任务状态，避免 renderEditor 使用旧数据
    const t = (state._tasks||[]).find(x=>x.taskId===id);
    if(t){ t.status = 7; t.isRunning = false; t.speed = 0; }
    state._prevTaskStatus = null;
    state._taskDetailSig = '';
    toast('已暂停','ok');
    renderEditor();
  }catch(e){ toast('暂停失败：'+e,'err'); }
}
async function resumeTask(id){
  try{
    await callBridge('resumeTask', id);
    // 立即更新本地任务状态为等待中
    const t = (state._tasks||[]).find(x=>x.taskId===id);
    if(t){ t.status = 0; t.isRunning = false; t.errorMsg = ''; }
    state._prevTaskStatus = null;
    state._taskDetailSig = '';
    toast('已继续下载','ok');
    renderEditor();
  }catch(e){ toast('继续失败：'+e,'err'); }
}
async function removeTask(id){
  try{
    await callBridge('removeTask', id);
    state.selectedTaskId=null;
    state._skipAutoSelect = true;
    state._prevTaskStatus = null;
    state._taskDetailSig = '';
    toast('已删除','ok');
    const freshTasks = await callBridge('getTasks');
    state._tasks = freshTasks;
    state._tasksStructSig = '';
    state._tasksContentSig = '';
    state._tasksStatusSig = '';
    renderTaskList(sidebarBody());
    renderEditor();
  }catch(e){ toast('删除失败','err'); }
}
async function retryTask(id){
  try{
    await callBridge('retryTask', id);
    // 立即更新本地任务状态为等待中
    const t = (state._tasks||[]).find(x=>x.taskId===id);
    if(t){ t.status = 0; t.isRunning = false; t.errorMsg = ''; t.progress = 0; }
    state._prevTaskStatus = null;
    state._taskDetailSig = '';
    toast('已开始续传','ok');
    renderEditor();
  }catch(e){ toast('续传失败：'+e,'err'); }
}
async function openFile(path){
  try{ await callBridge('openFile', path); }catch(e){ toast('打开失败：'+e,'err'); }
}

/* ===== 轮询任务状态 ===== */
let _pollTimeout = null;
function startPolling(){
  // 清除已有定时器，避免重复轮询
  if(_pollTimeout){ clearTimeout(_pollTimeout); _pollTimeout = null; }
  pollNow();
  schedulePoll();
}
/** 立即执行一次轮询（用于从后台恢复、视图切换等场景） */
async function forcePollNow(){
  // 如果有运行中的定时器，先取消，pollNow 完成后 schedulePoll 会重新排期
  if(_pollTimeout){ clearTimeout(_pollTimeout); _pollTimeout = null; }
  await pollNow();
  schedulePoll();
}
function schedulePoll(){
  // 动态轮询：有运行中任务时 500ms(实时感)，无任务时 3 秒，有任务但都已完成 2 秒
  const tasks = state._tasks || [];
  const hasRunning = tasks.some(t=>t.isRunning);
  const interval = hasRunning ? 500 : (tasks.length > 0 ? 2000 : 3000);
  _pollTimeout = setTimeout(async ()=>{
    await pollNow();
    schedulePoll();
  }, interval);
}
async function pollNow(){
  try{
    const tasks = await callBridge('getTasks');
    state._tasks = tasks;
    if(state.currentView === 'explorer'){
      // 结构签名：任务ID列表（检测任务增删）
      const structSig = tasks.map(t=>t.taskId).join('|');
      // 内容签名：状态+进度(0.1% 粒度，检测细微变化触发原地更新)
      const contentSig = tasks.map(t=>`${t.taskId}:${t.status}:${Math.round(t.progress*1000)}`).join('|');
      // 状态签名：仅状态分布（分类成员变化检测）
      const statusSig = tasks.map(t=>`${t.taskId}:${t.status}`).join('|');
      if(structSig !== state._tasksStructSig){
        // 任务列表结构变化 → 完整重渲染侧边栏 + 分类计数
        state._tasksStructSig = structSig;
        state._tasksContentSig = contentSig;
        state._tasksStatusSig = statusSig;
        // 自动选中最新的任务（跳过首次轮询和批量下载后的首次轮询，保持列表视图）
        if(state._firstPoll){
          state._firstPoll = false;
          state._skipAutoSelect = false;
        } else if(state._skipAutoSelect){
          state._skipAutoSelect = false;
        } else if(!state.selectedTaskId || !tasks.find(t=>t.taskId===state.selectedTaskId)){
          if(tasks.length>0) state.selectedTaskId = tasks[tasks.length-1].taskId;
        }
        renderTaskTabsBar();
        renderSidebar();
        renderEditor();
      } else if(contentSig !== state._tasksContentSig){
        state._tasksContentSig = contentSig;
        // 状态变化（完成/失败/取消/暂停）会改变分类筛选的成员与计数 → 重新渲染
        if(statusSig !== state._tasksStatusSig){
          state._tasksStatusSig = statusSig;
          renderTaskTabsBar();
          renderTaskList(sidebarBody());
        } else {
          // 纯进度变化 → 原地更新徽章，避免闪烁
          updateSidebarBadgesInPlace(tasks);
        }
      }
      // 选中任务时，原地更新进度信息而非完全重渲染
      const sel = tasks.find(t=>t.taskId===state.selectedTaskId);
      if(sel){
        updateTaskDetailInPlace(sel);
      }
    }
    updateStatusBar(tasks);
  }catch(e){
    console.error('[pollNow] 轮询失败:', e);
  }
}

/** 原地更新任务徽章（不重建DOM，避免闪烁）—— 同时更新侧边栏和主内容区 */
function updateSidebarBadgesInPlace(tasks){
  // 在侧边栏和主内容区中查找并更新任务卡片
  const containers = [sidebarBody(), editorBody()];
  containers.forEach(container=>{
    if(!container) return;
    tasks.forEach(t=>{
      const item = container.querySelector(`.task-item[data-id="${t.taskId}"]`);
      if(!item) return;
      // 更新状态类名（保留 task-item / selected / has-cover 类）
      const newClass = taskStatusClass(t);
      item.classList.remove('task-pending','task-parsing','task-downloading','task-muxing','task-done','task-failed','task-canceled');
      item.classList.add('task-' + newClass);
      const sub = item.querySelector('.ti-sub');
      if(sub){
        const dot = taskStatusDot(t);
        sub.innerHTML = `${dot}${t.pageCount>1?` · ${t.pageCount}P`:''}`;
      }
      // 更新进度条覆盖层（0.1% 粒度，慢速下载也平滑）
      const pct = Math.round((t.progress||0)*1000)/10;
      let progBar = item.querySelector('.ti-progress');
      if(t.status===1||t.status===2||t.status===3){
        if(!progBar){
          progBar = document.createElement('div');
          progBar.className = 'ti-progress';
          // 插入到 ti-body 之前
          const body = item.querySelector('.ti-body');
          if(body) item.insertBefore(progBar, body);
          else item.appendChild(progBar);
        }
        if(progBar.style.width !== pct + '%') progBar.style.width = pct + '%';
      } else if(progBar){
        progBar.remove();
      }
    });
  });
}

/** 原地更新任务详情的动态字段（避免完全重渲染导致闪烁） */
function updateTaskDetailInPlace(t){
  const pct = Math.round(t.progress*100);
  const stateText = ['等待中','解析中','下载中','合并中','已完成','失败','已取消','已暂停'][t.status];

  // 签名检测：若状态+进度+速度+字节均未变化，跳过所有DOM操作，避免闪烁
  const sig = `${t.status}:${pct}:${Math.round((t.speed||0)/1024)}:${t.downloadedBytes}:${t.totalBytes}`;
  if(sig === state._taskDetailSig) return;
  state._taskDetailSig = sig;

  // 状态变化时完整重渲染（更新操作按钮：暂停/继续/重试/删除等）
  // 覆盖所有状态转换：运行→暂停、暂停→运行、失败→重试、运行→完成/失败/取消等
  const prevStatus = state._prevTaskStatus;
  if(prevStatus != null && prevStatus !== t.status){
    state._prevTaskStatus = t.status;
    renderEditor();
    return;
  }
  state._prevTaskStatus = t.status;

  // 更新进度条（仅当值变化时操作DOM）
  const fill = document.querySelector('.progress-fill');
  if(fill){
    const newWidth = pct + '%';
    if(fill.style.width !== newWidth) fill.style.width = newWidth;
    const newClass = 'progress-fill' + (t.status===4?' done':'') + (t.status===5?' fail':'');
    if(fill.className !== newClass) fill.className = newClass;
  }
  // 更新进度文字
  const pInfo = document.querySelector('.progress-info');
  if(pInfo){
    const t0 = `${pct}% ${t.status===2?'· '+fmtSpeed(t.speed):''}`;
    const t1 = t.status===4 ? (t.totalBytes>0 ? fmtBytes(t.totalBytes) : '已完成') : (fmtBytes(t.downloadedBytes) + ' / ' + (t.totalBytes>0?fmtBytes(t.totalBytes):'?'));
    if(pInfo.children[0].textContent !== t0) pInfo.children[0].textContent = t0;
    if(pInfo.children[1].textContent !== t1) pInfo.children[1].textContent = t1;
  }
  // 更新状态标签（仅当内容变化时操作DOM，避免重置动画）
  const pill = document.querySelector('.state-pill');
  if(pill){
    const stateClass = ['','running','running','running','success','failed',''][t.status] || '';
    const newClass = 'state-pill state-' + stateClass;
    const newHtml = (t.status===2?spinDot():'') + ' ' + stateText;
    if(pill.className !== newClass) pill.className = newClass;
    if(pill.innerHTML !== newHtml) pill.innerHTML = newHtml;
  }
  // 更新状态卡片
  const scVals = document.querySelectorAll('.sc-value');
  if(scVals.length >= 1 && scVals[0].textContent !== stateText) scVals[0].textContent = stateText;
}

function updateStatusBar(tasks){
  tasks = tasks || state._tasks || [];
  const running = tasks.filter(t=>t.isRunning).length;
  const done = tasks.filter(t=>t.status===4).length;
  el('statusTasks').textContent = `${running} 运行中 · ${done} 已完成`;
  if(state._loginInfo && state._loginInfo.isLogin){
    el('sbDot').className = 'sb-dot ok';
    // 优先取顶层 uname，回退到 web/tv 子对象的 uname，最后回退到"已登录"
    const uname = state._loginInfo.uname
      || (state._loginInfo.web && state._loginInfo.web.uname)
      || (state._loginInfo.tv && state._loginInfo.tv.uname)
      || '已登录';
    // 状态栏背景是粉色(#FB7299)，统一用白色文字（不用 vip-name 粉色，会融入背景）
    el('sbConnText').innerHTML = `<span style="color:#fff">${esc(uname)}</span>`;
  } else {
    el('sbDot').className = 'sb-dot';
    el('sbConnText').textContent = '未登录';
  }
}

/* ---------- 工具函数 ---------- */
function esc(s){ return String(s==null?'':s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }

// 文件命名模板变量说明表格（BBDown 原版风格）
function filePatternVarsTable(){
  const vars = [
    ['{videoTitle}', '视频标题'],
    ['{pageNumber}', '分P序号'],
    ['{pageNumberWithZero}', '分P序号(补零)'],
    ['{pageTitle}', '分P标题'],
    ['{collectionIndex}', '合集/系列序号'],
    ['{bvid}', 'BV号'],
    ['{aid}', 'AV号'],
    ['{cid}', '分P CID'],
    ['{dfn}', '画质名称'],
    ['{res}', '分辨率'],
    ['{fps}', '帧率'],
    ['{videoCodecs}', '视频编码'],
    ['{videoBandwidth}', '视频码率'],
    ['{audioCodecs}', '音频编码'],
    ['{audioBandwidth}', '音频码率'],
    ['{ownerName}', 'UP主名称'],
    ['{ownerMid}', 'UP主MID'],
    ['{publishDate}', '发布时间'],
  ];
  return `<div class="fp-vars-table">
    <table><thead><tr><th>变量</th><th>说明</th></tr></thead><tbody>
    ${vars.map(([v, d])=>`<tr><td><code>${v}</code></td><td>${d}</td></tr>`).join('')}
    </tbody></table></div>`;
}
function fmtTime(t){ if(!t)return''; const d=new Date(t*1000); const p=n=>String(n).padStart(2,'0'); return `${d.getMonth()+1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`; }
function fmtBytes(b){ b=b||0; if(b<1024)return b+' B'; if(b<1048576)return(b/1024).toFixed(1)+' KB'; if(b<1073741824)return(b/1048576).toFixed(1)+' MB'; return(b/1073741824).toFixed(2)+' GB'; }
function fmtSpeed(s){ if(!s)return''; return fmtBytes(s)+'/s'; }
function toast(msg,type){ const t=el('toast'); t.className='toast hidden'; t.textContent=msg; requestAnimationFrame(()=>{t.className='toast show '+(type||'');}); clearTimeout(t._t); t._t=setTimeout(()=>{t.className='toast hidden';},2600); }
function spinIcon(){ return `<svg class="spin" viewBox="0 0 24 24" width="16" height="16"><path fill="#FB7299" d="M12 2a10 10 0 1 0 10 10h-3a7 7 0 1 1-7-7V2z"/></svg>`; }
function spinDot(){ return `<span style="display:inline-block;width:7px;height:7px;border-radius:50%;background:#FB7299;animation:pulse 1.2s infinite"></span>`; }
function okIcon(){ return `<svg viewBox="0 0 24 24" width="15" height="15"><path fill="var(--success)" d="M9 16.2l-3.5-3.5L4 14l5 5 11-11-1.5-1.4z"/></svg>`; }
function errIcon(){ return `<svg viewBox="0 0 24 24" width="15" height="15"><path fill="var(--error)" d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 13h-2v2h2zm0-8h-2v6h2z"/></svg>`; }
/* 自定义确认弹窗，替代原生 confirm（避免旧安卓风格系统对话框） */
function showConfirm(opts){
  const o=opts||{};
  const overlay=document.createElement('div');
  overlay.className='modal-overlay';
  overlay.innerHTML=`<div class="modal-card">
    <div class="modal-icon">${o.icon||okIcon()}</div>
    <div class="modal-title">${esc(o.title||'提示')}</div>
    ${o.message?`<div class="modal-msg">${esc(o.message)}</div>`:''}
    ${o.hint?`<div class="modal-hint">${esc(o.hint)}</div>`:''}
    <div class="modal-actions">
      <button class="modal-btn modal-cancel">${esc(o.cancelText||'取消')}</button>
      <button class="modal-btn modal-ok">${esc(o.confirmText||'确定')}</button>
    </div>`;
  document.body.appendChild(overlay);
  requestAnimationFrame(()=>overlay.classList.add('show'));
  const close=()=>{overlay.classList.remove('show');setTimeout(()=>overlay.remove(),220);};
  overlay.querySelector('.modal-cancel').onclick=close;
  overlay.querySelector('.modal-ok').onclick=async()=>{close();if(typeof o.onConfirm==='function'){try{await o.onConfirm();}catch(e){toast('操作失败：'+e,'err');}}};
  overlay.addEventListener('click',e=>{if(e.target===overlay)close();});
}

/* ---------- 启动 ---------- */
jsLog('App starting, AndroidBridge=' + (typeof AndroidBridge));
document.querySelectorAll('.ab-btn').forEach(b=>b.onclick=()=>switchView(b.dataset.view));

detectLayout();
jsLog('Loading settings...');
// 主题已在 <head> 中通过 getThemeSync() 同步应用，此处仅同步 state._theme，不再重复 applyTheme
callBridge('getAllSettings').then(s=>{ state.settings=s; jsLog('Settings loaded: '+JSON.stringify(s)); state._theme=s.theme||'dark'; try{ localStorage.setItem('theme', state._theme); }catch(e){} jsLog('Theme restored: '+state._theme); }).catch(e=>{ jsLog('Settings load error: '+e); state._theme='dark'; });
jsLog('Checking login...');
callBridge('checkLogin').then(res=>{
  // 将 face URL 转为 https:// 确保头像可加载
  if(res.face) res.face = res.face.replace(/^http:\/\//, 'https://');
  // 如果后端返回的 uname 为空但 isLogin 为 true，保留"已登录"作为兜底
  if(res.isLogin && !res.uname){
    res.uname = (res.web && res.web.uname) || (res.tv && res.tv.uname) || '已登录';
  }
  state._loginInfo = res;
  jsLog('Login check: ' + JSON.stringify(res));
  updateStatusBar();
}).catch(e=>{ state._loginInfo = {isLogin:false}; jsLog('Login check error: '+e); });
switchView('explorer');
startPolling();
// 加载API类型设置
loadApiType();
// 动态获取应用版本号，更新底栏显示
callBridge('getAppVersion').then(v=>{
  const sbVer = document.getElementById('sbVer');
  if(sbVer && v.versionName) sbVer.textContent = 'BBDown Android v' + v.versionName;
}).catch(e=>{ jsLog('获取版本号失败: '+e); });
window.addEventListener('orientationchange',()=>setTimeout(detectLayout,300));
// 页面可见性变化时刷新：从后台恢复时立即轮询任务状态，权限检查也一并处理
document.addEventListener('visibilitychange',()=>{
  if(document.visibilityState === 'visible'){
    // 从后台恢复时立即轮询一次，避免任务状态卡在旧数据
    try { forcePollNow(); } catch(e) {}
    if(state._permCheckPending || state.currentView === 'settings'){
      setTimeout(()=>checkStoragePermissionStatus(), 300);
    }
  }
});
