# 容错实验：把不确定性变得可控

> 这一篇是整个项目里最该亲手做一遍的部分。做完你会亲身体会到
> "大模型应用"和"普通增删改查"到底差在哪 —— 以及为什么这个差距是**工程问题**，不是算法问题。

---

## 你要体验的三件事

1. **调用失败**：超时、服务端错误、鉴权失败
2. **调用"成功"但结果不可用**：返回乱码、JSON 被截断、空响应
3. **不加保护的调用会把整个服务拖死** —— 而且**不抛异常、不记日志、健康检查还是 UP**

第 3 点最反直觉，也最值钱。它是这组实验存在的理由。

---

## 实验台：九种故障模式

| 模型名 | 含义 | 可重试 |
|---|---|---|
| `mock-ok` | 正常返回 | — |
| `mock-slow` | 比超时阈值还慢（5s vs 1.5s） | 是 |
| `mock-hang` | 永远不返回 | 是 |
| `mock-error` | 服务端 5xx | 是 |
| `mock-auth` | 鉴权失败 401 | **否** |
| `mock-garbage` | 返回非 JSON 文本 | 是 |
| `mock-empty` | 返回空字符串 | 是 |
| `mock-truncated` | 返回被截断的 JSON | 是 |
| `mock-random` | 随机挑一个 | — |

**为什么要枚举故障**：真实 LLM 的失败是随机发生的，你今天遇到超时、明天遇到乱码，
永远凑不齐完整样本，也就没法系统性验证容错代码。
把故障变成可复现的，是工程化的第一步 —— 这叫**故障注入**（fault injection）。

看看当前有哪些模式：

```powershell
curl.exe http://127.0.0.1:8081/api/lab/modes
```

---

## 实验协议

启动应用后（`.\run.ps1`），另开一个终端按顺序做。

### 实验 1：正常路径

```powershell
curl.exe -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-ok"
```

观察 `trace`：只有一条"第 1 次尝试：成功"，`attempts=1`。

### 实验 2：超时 → 重试 → 降级

```powershell
curl.exe -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-slow"
```

观察 `trace`：三次尝试、每次之间有退避、最后降级。注意 `attempts=3`、`degraded=true`。

**关键**：业务方拿到的是 200 + 一段明确的降级文案，不是 500。
这就是"降级"和"直接失败"的区别。

### 实验 3：不可重试的错误

```powershell
curl.exe -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-auth"
```

**只尝试 1 次就放弃。** 对比实验 2 的 3 次。

> 想清楚为什么：API key 错了，重试一万次结果都一样，只会白白放大对上游的压力。
> 线上的"重试风暴"事故，根源多半就是没做这个区分。

### 实验 4：调用成功但结果不可用

```powershell
curl.exe -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-garbage"
curl.exe -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-truncated"
curl.exe -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-empty"
```

三种都"返回了内容"，但没一种是能用的。
这就是为什么 **"拿到了回复" ≠ "拿到了可用结果"**。

`mock-truncated` 在真实世界极其常见：模型输出达到 `max_tokens` 上限，JSON 就断在半截。

### 实验 5：熔断

先重置，然后打两次 `mock-slow`：

```powershell
curl.exe -X POST http://127.0.0.1:8081/api/lab/breakers/reset
curl.exe -s -o NUL -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-slow"
curl.exe -s -o NUL -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-slow"
curl.exe http://127.0.0.1:8081/api/lab/stats
```

> **为什么是两次而不是五次**：阈值是 `breaker-failure-threshold: 5`，
> 但**一次调用内部会重试 3 次** —— 也就是一次调用贡献 3 次失败。
> 两次调用 = 6 次失败 ≥ 5，熔断触发。
>
> 这个细节很值得注意：**阈值的单位是"失败次数"，不是"失败请求数"**。
> 配重试的时候如果不把这两个概念分开，很容易把阈值理解错。
> 实测轨迹：第 1 次 `attempts=3, CLOSED`；第 2 次 `attempts=3, OPEN`；
> 第 3 次开始 `attempts=0, OPEN`。

熔断器变成 `OPEN`。此时再调用**同一个模型**：

```powershell
curl.exe -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-slow"
```

**`attempts=0`、`elapsedMs=0`** —— 一次都没打出去，直接快速失败。

熔断打开后 `breaker-open-ms: 10000`（10 秒）内即使上游恢复了也不放行。
想看这一点，在 10 秒内重复调用 `mock-slow`，始终是 0 次。

### 实验 5b：熔断的粒度 —— **不要跳过这一节**

现在试一个**不同的模型**，但走同一个供应商：

```powershell
curl.exe -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-ok"
```

**它是成功的：`attempts=1`、`success=true`。**

因为熔断粒度是 `(供应商, 模型)` —— `mock/mock-slow` 和 `mock/mock-ok`
是两个**独立的熔断器**。一个模型坏了，不该拖垮这家供应商的其他模型。

对照 `/api/lab/stats` 就能看到：

```json
{
  "providers": {
    "breakers": {
      "mock/mock-slow": { "state": "OPEN", "consecutiveFailures": 6, "failureThreshold": 5 }
    },
    "pools": {
      "mock": { "activeThreads": 0, "poolSize": 4, "queueSize": 0, "queueCapacity": 8 }
    }
  }
}
```

**注意这个对比本身就是设计的全部意义**：

| | 粒度 | 键 |
|---|---|---|
| 熔断器 | **(供应商, 模型)** | `mock/mock-slow` |
| 线程池 | **供应商** | `mock` |

熔断关心"**这个具体能力能不能用**"，所以粒度细；
线程池关心"**别把上游打死**"，所以粒度粗 —— 按模型建池的话，
10 个模型 × 4 线程 = 40 并发打到同一家，隔离就失去意义了。

> ⚠️ 这一节我最初写错了：当时声称"熔断后 `mock-ok` 也会被拦"，
> 同时又声称"熔断按模型隔离" —— 这两条不可能同时成立。
> 实测才发现是后者对。**写文档时前后矛盾的地方，一定要真跑一遍确认。**

### 实验 6：线程池隔离（本组实验的重点）

**先跑正确做法：**

```powershell
curl.exe -X POST http://127.0.0.1:8081/api/lab/breakers/reset
curl.exe -X POST "http://127.0.0.1:8081/api/lab/load?model=mock-hang&count=20&cancelOnTimeout=true"
curl.exe http://127.0.0.1:8081/api/lab/stats
```

**再跑错误做法：**

```powershell
curl.exe -X POST http://127.0.0.1:8081/api/lab/breakers/reset
curl.exe -X POST "http://127.0.0.1:8081/api/lab/load?model=mock-hang&count=20&cancelOnTimeout=false"
curl.exe http://127.0.0.1:8081/api/lab/stats
```

**然后重置熔断器，再请求一次完全正常的调用：**

```powershell
curl.exe -X POST http://127.0.0.1:8081/api/lab/breakers/reset
curl.exe -X POST "http://127.0.0.1:8081/api/lab/ask?model=mock-ok"
```

---

## 实测结果

（这台开发机上跑出来的真实数据，你复现应该得到相近结果）

线程池参数：`pool-size: 4`、`queue-capacity: 8`、`timeout-ms: 1500`

| | `cancelOnTimeout=true` | `cancelOnTimeout=false` |
|---|---|---|
| 20 并发 HANG | 降级 20，被拒绝 5 | 降级 20，**被拒绝 20** |
| 结束后线程池 | 活跃 **0**，队列 **0/8** | 活跃 **4**，队列 **8/8** |
| 之后一次正常的 OK 调用 | **成功** | **失败**（3 次全被拒绝） |

`cancelOnTimeout=false` 时那次 OK 调用的轨迹长这样：

```
第 1 次尝试：线程池与队列均已满，请求被拒绝（隔离生效，主线程未被拖住）
退避 231ms 后重试
第 2 次尝试：线程池与队列均已满，请求被拒绝
退避 456ms 后重试
第 3 次尝试：线程池与队列均已满，请求被拒绝
尝试耗尽，返回降级内容
```

**注意此刻的处境：**

- 没有异常被抛出
- 日志里没有 ERROR
- `/actuator/health` 依然返回 `{"status":"UP"}`
- 但所有 LLM 调用都失败了

**服务已经死了，而监控完全看不出来。** 这是生产环境最难排查的一类故障。

---

## 三个结论

### 1.「调用成功」≠「拿到可用结果」

LLM 返回 200 只代表 HTTP 通了。内容可能是乱码、被截断的半截 JSON、或者空字符串。
**输出校验必须当成调用链的一部分**，不能假设它一定对。

### 2. 超时之后必须取消，否则是静默死亡

`future.get(timeout)` 只是"不再等待"，**工作线程还在跑**。挂起几次之后池子被永久占满。

- `future.cancel(false)` = 上面那个 4 活跃 / 8 排队的死局
- `future.cancel(true)` = 中断线程，池子能恢复

生产代码必须用 `true`。而且注意：**要确保你的下游客户端真的响应中断**
（比如 `Thread.sleep`、部分 HTTP 客户端会，但阻塞式 JDBC 调用通常不会）。
如果下游不响应中断，`cancel(true)` 也救不了你 —— 这时唯一可靠的防线是
**把这类调用隔离到独立线程池**，让它死也只死自己那一块。

### 3. 重试必须区分可重试性，并且必须加抖动

- 4xx（密钥错、参数错）重试没有意义，只会放大负载 → 用 `retryable` 标记区分
- 所有客户端按同样的节奏退避，会在同一时刻集体重试，把刚恢复的下游再打挂
  → **指数退避必须加随机抖动**

---

## 练习

前四个练习的解答已经在项目里实现了，可以直接对照代码看；
后两个是留给你自己的。

### ✅ 练习 1（已实现）：降级要分层

现在的降级是返回一句固定文案。更好的降级是**分层次**的：

1. 先尝试返回缓存里的上一次结果
2. 再尝试换一个更便宜、更快的模型
3. 最后才是固定文案

想清楚：降级返回的数据，要不要标记出来让前端知道？为什么？

> 对照：`GatewayService` 的降级路径、`ResponseCache` 的 TTL 语义。

### ✅ 练习 2（已实现）：把线程池状态接进监控

手动 `curl /api/lab/stats` 只能看当下。
项目把它接进了 `/actuator/prometheus` 和控制台 Dashboard。

想清楚：**应该对哪个指标设告警？**

> 提示：`activeThreads` 长时间等于 `poolSize` 意味着什么？
> 这个告警比"错误率上升"更早还是更晚？
> 答案在 `docs/07-loadtest.md` 的"静默死亡的识别方法"里。

### ✅ 练习 3（已实现）：给重试加"总时间预算"

重试次数固定时，**总耗时 = 次数 × (超时 + 退避)**。
`mock-slow` 下一次调用要 5.2 秒 —— 对用户来说太久了。

项目里的做法是把超时预算做成可配置（`timeout-ms`），
并且优先级是「调用方指定 > 供应商默认 > 全局默认」。

想清楚：这个预算应该由谁决定？调用方还是被调方？

### ✅ 练习 4（已实现）：输出校验

`mock-garbage` / `mock-truncated` / `mock-empty` 三种"成功但不可用"的情况，
项目里是怎么识别的？

> 对照：`OpenAiCompatibleProvider` 里对响应体的解析与校验。
> 关键点：**解析失败要归类成可重试错误**，而不是当成成功。

### ⬜ 练习 5（留给你）：把熔断阈值调成 1

改 `application.yml` 里的 `breaker-failure-threshold: 1`，重启，再做实验 5。

观察：是不是一次失败就熔断了？这样做有什么问题？

（提示：偶发的一次网络抖动就该熔断吗？思考"连续失败"和"失败率"两种判定方式的差别。）

### ⬜ 练习 6（留给你）：给熔断加"最小请求数"

现在的熔断只看连续失败次数。低流量时这会误判 ——
一共就 2 个请求、失败了 2 次，可能只是巧合。

改造它：加一个"窗口内最少请求数"门槛（比如至少 20 个请求才允许熔断），
再改成按**失败率**判定（比如 50%）。

想清楚：失败率和连续失败次数，各自适合什么场景？

---

## 这一段的产出，直接对应你简历上的什么

做完这一段，你能在面试里讲的东西包括：

- 为什么要做线程池隔离，不做会怎样（**有实测数据**）
- 超时、取消、重试、退避、抖动、熔断、降级各自的适用边界
- 什么叫"静默死亡"，怎么提前发现它
- 为什么重试要区分可重试性（能举出重试风暴的反例）

**这些都不是背书背来的，是按按钮看到过的。** 面试官追问三层也答得住。
