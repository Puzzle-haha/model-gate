/*
 * ModelGate 控制台前端。
 *
 * 刻意不引入前端框架：这个页面的复杂度不需要框架，而省掉 npm + 打包链
 * 意味着「clone 下来直接能跑」—— 面试官打开浏览器就能看到东西，
 * 而不是先 npm install 五分钟。
 */

const WINDOWS = [
  { label: '5 分钟', minutes: 5 },
  { label: '15 分钟', minutes: 15 },
  { label: '60 分钟', minutes: 60 },
  { label: '4 小时', minutes: 240 },
];

let currentWindow = 15;
let timer = null;
const charts = {};

const banner = document.getElementById('banner');

/* ------------------------------------------------------------------ 工具 */

async function request(url) {
  let res;
  try {
    res = await fetch(url);
  } catch (e) {
    throw new Error(`请求没能送达：${e.message}`);
  }
  const text = await res.text();
  let body = null;
  if (text) {
    try { body = JSON.parse(text); } catch { body = text; }
  }
  if (!res.ok) {
    const detail = body && typeof body === 'object' && body.message
      ? body.message : String(body);
    throw new Error(`HTTP ${res.status} — ${detail}`);
  }
  return body;
}

function showBanner(msg) {
  banner.textContent = msg;
  banner.className = 'banner';
}
function clearBanner() {
  banner.className = 'banner hidden';
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function fmt(n) {
  if (n === null || n === undefined) return '—';
  return Number(n).toLocaleString('zh-CN');
}

/** 成本展示：不足 0.01 元时用更多小数位，否则一律显示 0.00 看不出差别。 */
function fmtCost(costStr) {
  const v = Number(costStr);
  if (!isFinite(v)) return '—';
  if (v === 0) return '0';
  if (v < 0.01) return v.toFixed(6);
  return v.toFixed(4);
}

function el(tag, className, html) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (html !== undefined) e.innerHTML = html;
  return e;
}

/* ------------------------------------------------------------------ KPI */

function renderKpis(t) {
  const box = document.getElementById('kpis');
  box.innerHTML = '';

  const degradedRate = t.degradedRate ?? 0;
  const successClass = degradedRate === 0 ? 'good' : (degradedRate < 5 ? 'warn' : 'bad');

  const cards = [
    { label: '调用总量', value: fmt(t.calls), extra: `${t.degraded ?? 0} 次降级` },
    { label: '成功率', value: t.successRate ?? 0, suffix: '%', cls: successClass,
      extra: `降级率 ${degradedRate}%` },
    { label: 'P95 延迟', value: fmt(t.p95Ms), suffix: 'ms', cls: (t.p95Ms > 5000 ? 'warn' : ''),
      extra: `P50 ${fmt(t.p50Ms)}ms · P99 ${fmt(t.p99Ms)}ms` },
    { label: '平均延迟', value: fmt(t.avgMs), suffix: 'ms', extra: `最大 ${fmt(t.maxMs)}ms` },
    { label: '缓存命中率', value: t.cacheHitRate ?? 0, suffix: '%',
      cls: (t.cacheHitRate > 0 ? 'good' : ''), extra: `${fmt(t.cacheHits)} 次命中` },
    { label: '累计成本', value: fmtCost(t.cost), suffix: '元', extra: `${fmt(t.tokens)} tokens` },
  ];

  for (const c of cards) {
    const card = el('div', `kpi ${c.cls || ''}`);
    card.appendChild(el('div', 'label', escapeHtml(c.label)));
    const valueHtml = `${escapeHtml(String(c.value))}${c.suffix ? `<small>${c.suffix}</small>` : ''}`;
    card.appendChild(el('div', 'value', valueHtml));
    card.appendChild(el('div', 'extra', escapeHtml(c.extra || '')));
    box.appendChild(card);
  }
}

/* ------------------------------------------------------------------ 图表 */

const AXIS_STYLE = {
  axisLine: { lineStyle: { color: '#2b3342' } },
  axisLabel: { color: '#7c8798', fontSize: 11 },
  splitLine: { lineStyle: { color: '#1e2532' } },
};

/** 初始化一次，之后只 setOption —— 反复 init 会泄漏 canvas。 */
function initCharts() {
  charts.volume = echarts.init(document.getElementById('chart-volume'), null, { renderer: 'canvas' });
  charts.latency = echarts.init(document.getElementById('chart-latency'), null, { renderer: 'canvas' });
  charts.cost = echarts.init(document.getElementById('chart-cost'), null, { renderer: 'canvas' });
}

/**
 * 把后端返回的时间桶（UTC 字符串 'YYYY-MM-DD HH:mm'）转成本地时间显示。
 *
 * 后端统一用 UTC 存储时间（见 CallLog 的说明），所以日期格式化出来也是 UTC。
 * 直接显示会差一个时区偏移 —— 中国用户会看到比实际早 8 小时的时间。
 *
 * 正确做法是**服务端返回 UTC、客户端负责转换成用户本地时间**，
 * 而不是在 SQL 里硬编码 +8 小时。这样部署到任何时区都不用改代码。
 */
function bucketLabel(bucket) {
  if (!bucket) return '';
  // 补上 T 和 Z，让 JS 按 UTC 解析；否则会被当成本地时间解析
  const d = new Date(bucket.replace(' ', 'T') + ':00Z');
  if (isNaN(d.getTime())) return bucket.slice(11);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function renderVolume(series) {
  const buckets = series.map(s => bucketLabel(s.bucket));
  charts.volume.setOption({
    grid: { left: 42, right: 16, top: 28, bottom: 26 },
    tooltip: { trigger: 'axis' },
    legend: { data: ['调用量', '降级'], textStyle: { color: '#98a3b6', fontSize: 11 }, top: 0 },
    xAxis: { type: 'category', data: buckets, ...AXIS_STYLE },
    yAxis: { type: 'value', ...AXIS_STYLE, minInterval: 1 },
    series: [
      { name: '调用量', type: 'line', smooth: true, showSymbol: false,
        data: series.map(s => s.calls),
        lineStyle: { color: '#4a9eff', width: 2 },
        areaStyle: { color: 'rgba(74,158,255,0.15)' } },
      { name: '降级', type: 'bar',
        data: series.map(s => s.degraded),
        itemStyle: { color: '#e0b154' }, barMaxWidth: 14 },
    ],
  }, true);
}

function renderLatency(series) {
  const buckets = series.map(s => bucketLabel(s.bucket));
  charts.latency.setOption({
    grid: { left: 52, right: 16, top: 28, bottom: 26 },
    tooltip: { trigger: 'axis', valueFormatter: v => `${v} ms` },
    xAxis: { type: 'category', data: buckets, ...AXIS_STYLE },
    yAxis: { type: 'value', ...AXIS_STYLE, name: 'ms',
             nameTextStyle: { color: '#7c8798', fontSize: 11 } },
    series: [{
      name: '平均延迟', type: 'line', smooth: true, showSymbol: false,
      data: series.map(s => s.avgMs),
      lineStyle: { color: '#4ec9a0', width: 2 },
      areaStyle: { color: 'rgba(78,201,160,0.13)' },
    }],
  }, true);
}

function renderCost(byModel) {
  const items = byModel.filter(m => Number(m.cost) > 0 || m.calls > 0).slice(0, 8).reverse();
  charts.cost.setOption({
    grid: { left: 110, right: 40, top: 16, bottom: 26 },
    tooltip: {
      trigger: 'axis',
      formatter: p => {
        const m = items[p[0].dataIndex];
        return `${m.model}<br/>成本 ${m.cost} 元<br/>调用 ${m.calls} 次（缓存命中 ${m.cacheHits}）<br/>tokens ${m.tokens}`;
      },
    },
    xAxis: { type: 'value', ...AXIS_STYLE },
    yAxis: {
      type: 'category',
      data: items.map(m => m.model),
      ...AXIS_STYLE,
      axisLabel: { color: '#98a3b6', fontSize: 11 },
    },
    series: [{
      type: 'bar',
      data: items.map(m => Number(m.cost)),
      itemStyle: { color: '#8b7dd8' },
      barMaxWidth: 16,
    }],
  }, true);
}

/* ------------------------------------------------------------------ 表格 */

function table(containerId, headers, rows, emptyText) {
  const t = document.getElementById(containerId);
  t.innerHTML = '';
  if (!rows || rows.length === 0) {
    t.innerHTML = `<tbody><tr><td class="empty">${escapeHtml(emptyText)}</td></tr></tbody>`;
    return;
  }
  const thead = el('thead');
  const tr = el('tr');
  headers.forEach(h => tr.appendChild(el('th', h.cls || '', escapeHtml(h.label))));
  thead.appendChild(tr);
  t.appendChild(thead);

  const tbody = el('tbody');
  rows.forEach(cells => {
    const r = el('tr');
    cells.forEach(c => r.appendChild(el('td', c.cls || '', c.html !== undefined ? c.html : escapeHtml(String(c.text ?? '')))));
    tbody.appendChild(r);
  });
  t.appendChild(tbody);
}

function renderProviders(list) {
  table('table-providers',
    [{ label: '供应商' }, { label: '调用量', cls: 'num' }, { label: '平均耗时', cls: 'num' },
     { label: '降级', cls: 'num' }],
    list.map(p => [
      { text: p.provider },
      { text: fmt(p.calls), cls: 'num' },
      { text: `${fmt(p.avgMs)} ms`, cls: 'num' },
      { text: fmt(p.degraded), cls: 'num ' + (p.degraded > 0 ? 'warn' : '') },
    ]),
    '窗口内没有调用记录');
}

function renderBreakers(runtime) {
  const breakers = runtime.breakersAndPools?.breakers || {};
  const rows = Object.entries(breakers).map(([key, v]) => {
    const state = v.state || 'CLOSED';
    const cls = state === 'CLOSED' ? 'ok' : (state === 'HALF_OPEN' ? 'warn' : 'bad');
    return [
      { text: key },
      { text: state, cls },
      { text: fmt(v.consecutiveFailures), cls: 'num' },
      { text: fmt(v.failureThreshold), cls: 'num' },
    ];
  });
  table('table-breakers',
    [{ label: '供应商 / 模型' }, { label: '状态' }, { label: '连续失败', cls: 'num' },
     { label: '阈值', cls: 'num' }],
    rows, '还没有触发过熔断（说明一切正常）');
}

function renderPools(runtime) {
  const pools = runtime.breakersAndPools?.pools || {};
  const rows = Object.entries(pools).map(([name, v]) => {
    const saturated = v.activeThreads >= v.poolSize && v.queueSize >= v.queueCapacity;
    return [
      { text: name },
      { text: `${v.activeThreads}/${v.poolSize}`, cls: 'num ' + (saturated ? 'bad' : '') },
      { text: `${v.queueSize}/${v.queueCapacity}`, cls: 'num ' + (saturated ? 'bad' : '') },
      { text: fmt(v.completedTasks), cls: 'num' },
    ];
  });
  table('table-pools',
    [{ label: '供应商' }, { label: '活跃线程', cls: 'num' }, { label: '队列', cls: 'num' },
     { label: '已完成', cls: 'num' }],
    rows, '没有上游调用');
}

function renderRuntime(runtime) {
  const c = runtime.cache || {};
  const r = runtime.rateLimit || {};
  const rows = [
    [{ text: '缓存命中' }, { text: fmt(c.hits), cls: 'num' }],
    [{ text: '缓存未命中' }, { text: fmt(c.misses), cls: 'num' }],
    [{ text: '被单飞合并' }, { text: fmt(c.coalesced), cls: 'num' },
     { text: '' }],
    [{ text: '因非确定性跳过' }, { text: fmt(c.skippedNotCacheable), cls: 'num' }],
    [{ text: '令牌桶' }, { text: `容量 ${r.capacity} · 补充 ${r.refillPerSecond}/s ` }],
    [{ text: '限流故障策略' }, { text: r.failOpen ? 'fail-open（保可用性）' : 'fail-closed（保上游）' }],
  ];
  table('table-runtime',
    [{ label: '项' }, { label: '值' }],
    rows.map(row => row.slice(0, 2)), '—');
}

/* ------------------------------------------------------------------ 主流程 */

async function refresh() {
  try {
    const data = await request(`/api/dashboard/summary?minutes=${currentWindow}`);
    clearBanner();

    renderKpis(data.totals || {});
    renderVolume(data.series || []);
    renderLatency(data.series || []);
    renderCost(data.byModel || []);
    renderProviders(data.byProvider || []);
    renderBreakers(data.runtime || {});
    renderPools(data.runtime || {});
    renderRuntime(data.runtime || {});

    document.getElementById('updated').textContent =
      `更新于 ${new Date().toLocaleTimeString('zh-CN')}`;
  } catch (e) {
    showBanner(`加载失败 — ${e.message}`);
  }
}

function renderWindowButtons() {
  const box = document.getElementById('windows');
  box.innerHTML = '';
  WINDOWS.forEach(w => {
    const b = el('button', w.minutes === currentWindow ? 'active' : '', escapeHtml(w.label));
    b.addEventListener('click', () => {
      currentWindow = w.minutes;
      renderWindowButtons();
      refresh();
    });
    box.appendChild(b);
  });
}

function setupAutoRefresh() {
  const box = document.getElementById('auto-refresh');
  const apply = () => {
    if (timer) { clearInterval(timer); timer = null; }
    if (box.checked) {
      timer = setInterval(refresh, 5000);
    }
  };
  box.addEventListener('change', apply);
  apply();
}

window.addEventListener('resize', () => {
  Object.values(charts).forEach(c => c.resize());
});

renderWindowButtons();
initCharts();
setupAutoRefresh();
refresh();
