/**
 * 极简压测工具 —— 用 Node 内置能力，不引入任何依赖。
 *
 * 为什么需要它：
 *   1. 验证限流的原子性：并发打 N 个请求，看实际放行数是否【严格等于】桶容量。
 *      如果放行数超过容量，说明「读-判断-扣减」不是原子的。
 *      这个用 curl 循环是测不出来的（那是串行）。
 *   2. 后续 M7 的压测报告也用它拿 P50/P95/P99。
 *
 * 用法：
 *   node tools/loadtest.mjs --requests 50 --concurrency 50 --api-key tenant-a --model fake-fast
 *   node tools/loadtest.mjs --requests 200 --concurrency 20 --url http://127.0.0.1:8081/v1/chat/completions
 */
import { parseArgs } from 'node:util';

const { values } = parseArgs({
  options: {
    url: { type: 'string', default: 'http://127.0.0.1:8081/v1/chat/completions' },
    requests: { type: 'string', default: '50' },
    concurrency: { type: 'string', default: '50' },
    'api-key': { type: 'string', default: 'loadtest-key' },
    model: { type: 'string', default: 'mock-ok' },
    prompt: { type: 'string', default: '压测请求' },
    temperature: { type: 'string', default: '' },
    identical: { type: 'boolean', default: false },
    /**
     * 预热请求数（结果丢弃）。
     *
     * ⚠️ 这个参数不是可选的锦上添花，而是**压测正确性的前提**。
     * JVM 在最初几百次请求里处于解释执行 + JIT 编译阶段，延迟可能是
     * 稳态的十几倍。把它算进结果，你测的是"冷启动性能"，
     * 却会当成"服务性能"来解读 —— 而且第一个跑的测试总是最惨的，
     * 于是得出"缓存路径比不走缓存还慢"这种荒谬结论。
     */
    warmup: { type: 'string', default: '0' },
    timeout: { type: 'string', default: '120000' },
  },
  allowPositionals: true,
});

const url = values.url;
const total = Number(values.requests);
const concurrency = Number(values.concurrency);
const apiKey = values['api-key'];
const model = values.model;
const timeoutMs = Number(values.timeout);
// 空字符串表示"不传 temperature"。传 0 才能命中缓存 ——
// 见 ResponseCache 的说明：非确定性请求不该被缓存。
const temperature = values.temperature === '' ? undefined : Number(values.temperature);

const statusCounts = new Map();
const latencies = [];
const retryAfterSamples = [];
let networkErrors = 0;
let allowed = 0;
let rejected = 0;

function record(status, ms, retryAfter) {
  statusCounts.set(status, (statusCounts.get(status) || 0) + 1);
  latencies.push(ms);
  if (status >= 200 && status < 300) {
    allowed++;
  } else if (status === 429) {
    rejected++;
    if (retryAfter && retryAfterSamples.length < 3) {
      retryAfterSamples.push(retryAfter);
    }
  }
}

async function one(index) {
  const started = Date.now();
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        model,
        ...(temperature === undefined ? {} : { temperature }),
        // --identical 时所有请求用同一段 prompt，于是共享同一个缓存键，
        // 用来验证单飞（缓存击穿保护）是否生效。
        // 默认带序号，保证每个请求都是独立的缓存键。
        messages: [{
          role: 'user',
          content: values.identical ? values.prompt : `${values.prompt} #${index}`,
        }],
      }),
      signal: controller.signal,
    });
    // 必须把 body 读完，否则连接不会释放，高并发下会耗尽连接池
    await res.text();
    clearTimeout(timer);
    record(res.status, Date.now() - started, res.headers.get('retry-after'));
  } catch (e) {
    networkErrors++;
    latencies.push(Date.now() - started);
  }
}

function percentile(sorted, p) {
  if (sorted.length === 0) return 0;
  const idx = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

const wallStart = Date.now();

// 用固定大小的 worker 池推进任务队列：既能拉满并发，又不会一次性创建
// 几千个 Promise 把客户端自己压垮（压测工具本身不能成为瓶颈）。
async function runBatch(count) {
  let cursor = 0;
  async function worker() {
    while (true) {
      const i = cursor++;
      if (i >= count) return;
      await one(i);
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, count) }, worker));
}

// ---- 预热：跑完把统计清掉，不计入结果 ----
const warmupCount = Number(values.warmup);
if (warmupCount > 0) {
  await runBatch(warmupCount);
  statusCounts.clear();
  latencies.length = 0;
  networkErrors = 0;
  allowed = 0;
  rejected = 0;
  retryAfterSamples.length = 0;
  console.log(`[预热] 已完成 ${warmupCount} 次请求（结果已丢弃）`);
  console.log('');
}

const measuredStart = Date.now();
await runBatch(total);
const wallMs = Date.now() - measuredStart;
const sorted = [...latencies].sort((a, b) => a - b);

console.log('=== 压测结果 ===');
console.log(`目标        : ${url}`);
console.log(`模型        : ${model}   调用方: ${apiKey}`);
console.log(`请求数      : ${total}   并发: ${concurrency}`);
console.log(`总耗时      : ${wallMs} ms`);
console.log(`吞吐        : ${Math.round(total / (wallMs / 1000))} req/s`);
console.log('');
console.log('状态码分布:');
for (const [status, count] of [...statusCounts.entries()].sort((a, b) => a[0] - b[0])) {
  console.log(`  ${status}  ${String(count).padStart(5)} 次`);
}
if (networkErrors > 0) {
  console.log(`  网络错误  ${String(networkErrors).padStart(5)} 次`);
}
console.log('');
console.log(`放行(2xx)   : ${allowed}`);
console.log(`拒绝(429)   : ${rejected}`);
console.log('');
console.log('延迟:');
console.log(`  P50  ${percentile(sorted, 50)} ms`);
console.log(`  P95  ${percentile(sorted, 95)} ms`);
console.log(`  P99  ${percentile(sorted, 99)} ms`);
console.log(`  max  ${sorted[sorted.length - 1] ?? 0} ms`);
if (retryAfterSamples.length > 0) {
  console.log('');
  console.log(`Retry-After 头样例: ${retryAfterSamples.map((s) => s + 's').join(', ')}`);
}
