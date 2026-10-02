/**
 * 本地假上游 —— 模拟一个 OpenAI 兼容的 /chat/completions 接口。
 *
 * 为什么需要它：
 *   1. 没有 API key 也能验证「HTTP 请求构造 / 响应解析 / 错误码映射」这一整层
 *   2. 能精确制造 401、429、500、坏 JSON、慢响应 —— 真实上游做不到"这次给我 429"
 *   3. 不花钱，可以无限次跑
 *
 * 用法：
 *   node tools/fake-upstream.mjs
 *   然后用 tools/localtest.yml 作为附加配置启动 model-gate
 *
 * 按请求体里的 model 字段决定行为。
 */
import http from 'node:http';

const PORT = Number(process.env.PORT || 9999);
const HOST = '127.0.0.1';

function okBody(model, text) {
  return {
    id: 'chatcmpl-fake-' + Date.now(),
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [
      { index: 0, message: { role: 'assistant', content: text }, finish_reason: 'stop' },
    ],
    usage: { prompt_tokens: 11, completion_tokens: 7, total_tokens: 18 },
  };
}

/** 稳定的字符串哈希，用作"这道题算对还是算错"的确定性种子。 */
function hash(s) {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return Math.abs(h);
}

/** 从评测 prompt 里解出正确答案。假上游真的会算，不是硬编码返回。 */
function solveFixture(text) {
  // 算术题
  const m = text.match(/计算\s*(\d+)\s*([+\-*/])\s*(\d+)/);
  if (m) {
    const a = Number(m[1]);
    const b = Number(m[3]);
    switch (m[2]) {
      case '+': return String(a + b);
      case '-': return String(a - b);
      case '*': return String(a * b);
      case '/': return String(Math.floor(a / b));
    }
  }
  // 情感分类：简单关键词规则（这正是"弱模型"的实现方式）
  const positive = ['棒', '很好', '周到', '快', '很高', '没问题', '满意'];
  const negative = ['差', '难吃', '失望', '麻烦', '坏', '两小时'];
  let p = 0;
  let n = 0;
  for (const w of positive) if (text.includes(w)) p++;
  for (const w of negative) if (text.includes(w)) n++;
  if (p === 0 && n === 0) return '未知';
  return p >= n ? '正面' : '负面';
}

/** 答错时给一个"像样"的错误答案，而不是一眼假的占位符。 */
function wrongAnswer(text) {
  const correct = solveFixture(text);
  if (/^\d+$/.test(correct)) {
    // 算错一位 —— 真实模型最常见的错误形态
    return String(Number(correct) + 1);
  }
  if (correct === '正面') return '负面';
  if (correct === '负面') return '正面';
  return '正面';
}

const server = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', async () => {
    const raw = Buffer.concat(chunks).toString('utf8');
    const auth = String(req.headers['authorization'] || '');

    if (req.method !== 'POST' || !req.url.startsWith('/chat/completions')) {
      res.writeHead(404, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'not found' } }));
      return;
    }

    let model = 'unknown';
    try {
      model = JSON.parse(raw).model;
    } catch {
      /* 请求体解析失败就按 unknown 处理 */
    }

    // 打印收到的关键信息 —— 用来验证网关确实带上了 Authorization 和正确的 body
    console.log(
      `[fake] model=${model} auth=${auth ? auth.slice(0, 22) + '…' : '(缺失)'} bodyBytes=${raw.length}`
    );

    const send = (status, payload) => {
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(typeof payload === 'string' ? payload : JSON.stringify(payload));
    };

    // ------------------------------------------------------------------
    // 密钥相关行为：用来验证密钥池的轮询与冷却。
    //
    //   fake-key-bad  → 永远 401（模拟被吊销的密钥）
    //   fake-key-429  → 永远 429（模拟被限流的密钥）
    //
    // 网关应该：把坏的冷却掉，接下来自动换用别的密钥，
    // 而且【不要】重试那把已经判定失效的密钥。
    // ------------------------------------------------------------------
    const key = auth.startsWith('Bearer ') ? auth.slice(7) : '';
    if (key === 'fake-key-bad') {
      return send(401, { error: { message: 'invalid api key (fake-key-bad)' } });
    }
    if (key === 'fake-key-429') {
      return send(429, { error: { message: 'rate limit exceeded (fake-key-429)' } });
    }

    // ------------------------------------------------------------------
    // 评测用模型：模拟"能力不同、价格不同"的三个模型。
    //
    // 假上游真的会去算题，只是按模型名控制正确率：
    //   eval-strong  永远算对（贵）
    //   eval-weak    约 60% 算对（中）
    //   eval-cheap   约 35% 算对（便宜）
    //
    // 正确率用 prompt 的哈希做种子，所以**同一道题的结果是稳定的** ——
    // 评测必须可重复，不能每次跑分数都不一样。
    // ------------------------------------------------------------------
    if (model.startsWith('eval-')) {
      let userText = '';
      try {
        const parsed = JSON.parse(raw);
        userText = (parsed.messages || [])
          .filter((m) => m.role === 'user')
          .map((m) => m.content)
          .join('\n');
      } catch { /* ignore */ }

      const answer = solveFixture(userText);
      const accuracy = model === 'eval-strong' ? 1.0
        : model === 'eval-weak' ? 0.6
        : 0.35;
      // 用 prompt 哈希决定这道题算对还是算错 —— 确定性，可重复
      const roll = (hash(userText) % 1000) / 1000;
      const correct = roll < accuracy;
      const output = correct ? answer : wrongAnswer(userText);

      await new Promise((r) => setTimeout(r, model === 'eval-cheap' ? 20 : 60));
      return send(200, okBody(model, output));
    }

    switch (model) {
      case 'fake-500':
        return send(500, { error: { message: 'internal server error' } });
      case 'fake-401':
        return send(401, { error: { message: 'invalid api key' } });
      case 'fake-429':
        return send(429, { error: { message: 'rate limit exceeded' } });
      case 'fake-badjson':
        // 声明是 JSON 但内容不是 —— 验证网关的解析失败处理
        return send(200, 'this is definitely not json');
      case 'fake-slow':
        await new Promise((r) => setTimeout(r, 2000));
        return send(200, okBody(model, '慢但成功：2000ms'));
      case 'fake-verySlow':
        await new Promise((r) => setTimeout(r, 12000));
        return send(200, okBody(model, '超慢：12000ms'));
      default:
        return send(200, okBody(model, `来自假上游的回复（model=${model}）`));
    }
  });
});

server.listen(PORT, HOST, () => {
  console.log(`[fake] 假上游已启动: http://${HOST}:${PORT}`);
  console.log('[fake] 可用模型: fake-ok fake-500 fake-401 fake-429 fake-badjson fake-slow fake-verySlow');
});
