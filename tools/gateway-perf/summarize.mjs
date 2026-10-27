import fs from 'node:fs';
import path from 'node:path';

const [input, output] = process.argv.slice(2);
if (!input || !output) throw new Error('Usage: node summarize.mjs <results.json> <report.md>');
const result = JSON.parse(fs.readFileSync(input, 'utf8').replace(/^\uFEFF/, ''));
if (!result.completedAt || result.failure || result.supersededReason) throw new Error('Refuse to publish an incomplete or superseded run');
const expected = (result.options.cases?.length || 9) * result.options.levels.length * result.options.repetitions;
if (result.runs.length !== expected) throw new Error(`Expected ${expected} runs, found ${result.runs.length}`);
const names = { 'gateway-ordinary': '普通转发', 'gateway-jwt': 'JWT（热验证缓存）', 'gateway-api-key': 'API Key',
  'gateway-basic': 'Basic BCrypt cost=10', 'gateway-body': '1MiB 正文透传', 'gateway-sse': 'AI_PROXY + SSE',
  'direct-ordinary': '直连普通上游', 'direct-body': '直连 1MiB 上游', 'direct-sse': '直连 SSE 上游' };
const fmt = n => n == null || !Number.isFinite(n) ? '—' : n.toFixed(2);
const range = values => `${fmt(Math.min(...values))}–${fmt(Math.max(...values))}`;
const errors = result.runs.reduce((sum, r) => sum + r.failed, 0);
const attempts = result.runs.reduce((sum, r) => sum + r.attempted, 0);
const localTime = value => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value));
const lines = ['# 网关本机性能基线', '', `测量开始：${localTime(result.createdAt)}；结束：${localTime(result.completedAt)}（Asia/Shanghai）。`,
  `生产代码提交：\`${result.sourceCommit}\`。本报告是可重复的本机基线，不是生产容量承诺。`, '',
  '## 环境与范围', '',
  `- 主机：${result.host.cpuModel.trim()}，${result.host.logicalProcessors} 个逻辑处理器，${fmt(result.host.totalMemoryBytes / 1073741824)} GiB 内存，${result.host.platform} ${result.host.release}。`,
  `- Java ${result.gateway.java}，Node ${result.host.node}；网关独立 JVM，堆 512 MiB、直接内存上限 256 MiB，ActiveProcessorCount=4。此设置不是操作系统 CPU 配额。`,
  '- 网关、模拟上游、压测端为三个独立进程，同机 loopback、HTTP/1.1 长连接，无 TLS。没有修改线上部署。',
  '- 真实 SCG 过滤器链与过滤器执行跟踪保持启用；禁用外部 Redis/Nacos/管理端依赖、后台定时任务、访问日志和追踪上报、WAF、响应/语义缓存。',
  '- SSE 场景启用实际 AI_PROXY 模型转发、正文处理和 Provider 凭证注入；凭证在本地提供，模拟热缓存，不访问付费模型。',
  '- 模拟 SSE：50ms 后首个内容帧，16 个内容帧，计划帧间隔 10ms，最后必须收到 DONE。Windows 实际定时粒度可能更粗，以直连对照为准。',
  `- 各场景预热 ${result.options.warmupMs / 1000}s、测量 ${result.options.holdMs / 1000}s，${result.options.repetitions} 次重复，并发 ${result.options.levels.join('/')}；此前另对所有网关路径做 JVM/JIT 预热。`,
  `- 空闲事件循环 P99：压测端 ${fmt(result.calibration.clientIdleEventLoopP99Ms)}ms、上游 ${fmt(result.calibration.upstreamIdleEventLoopP99Ms)}ms；环境延迟标记阈值 ${fmt(result.calibration.lagLimitMs)}ms。`,
  `- 共 ${result.runs.length} 轮；测量窗口内开始的请求 ${attempts} 个，失败 ${errors} 个。`, '',
  '## 网关测量结果', '',
  '吞吐列给出重复轮次的最小–最大值；P99 是重复轮次中较大的值，不是合并样本计算。CPU 单位为占用的核数，内存为各轮采样峰值。', '',
  '| 场景 | 并发 | 成功 req/s 范围 | 较大 P99 ms | 失败/开始请求 | 网关 CPU 核数范围 | 堆峰值 MiB | 直接内存峰值 MiB | 说明 |',
  '|---|---:|---:|---:|---:|---:|---:|---:|---|'];
for (const [label, name] of Object.entries(names).filter(([key]) => key.startsWith('gateway'))) {
  for (const c of result.options.levels) {
    const rows = result.runs.filter(r => r.case === label && r.concurrency === c);
    if (!rows.length) continue;
    const variable = Math.max(...rows.map(r => r.successRps)) > Math.min(...rows.map(r => r.successRps)) * 1.25;
    const notes = [rows.some(r => r.p99LowSampleWarning) ? 'P99 样本少' : '', rows.some(r => r.environmentLimited) ? '存在环境受限轮次' : '', variable ? '轮间吞吐波动>25%' : ''].filter(Boolean).join('；');
    lines.push(`| ${name} | ${c} | ${range(rows.map(r => r.successRps))} | ${fmt(Math.max(...rows.map(r => r.latencyMs?.p99 || 0)))} | ${rows.reduce((s, r) => s + r.failed, 0)}/${rows.reduce((s, r) => s + r.attempted, 0)} | ${range(rows.map(r => r.metrics.gateway.meanCpuCores || 0))} | ${fmt(Math.max(...rows.map(r => r.metrics.gateway.peakHeapUsedBytes || 0)) / 1048576)} | ${fmt(Math.max(...rows.map(r => r.metrics.gateway.peakDirectBytes || 0)) / 1048576)} | ${notes} |`);
  }
}
lines.push('', '## 直连与流式对照', '', '| 路径 | 并发 | 成功 req/s 范围 | 较大 P99 ms | 较大 TTFT P99 ms | 较大 TPOT P99 ms |',
  '|---|---:|---:|---:|---:|---:|');
for (const label of ['direct-ordinary', 'direct-body', 'direct-sse', 'gateway-sse']) {
  for (const c of result.options.levels) {
    const rows = result.runs.filter(r => r.case === label && r.concurrency === c);
    if (!rows.length) continue;
    lines.push(`| ${names[label]} | ${c} | ${range(rows.map(r => r.successRps))} | ${fmt(Math.max(...rows.map(r => r.latencyMs?.p99 || 0)))} | ${rows[0].ttftMs ? fmt(Math.max(...rows.map(r => r.ttftMs.p99))) : '—'} | ${rows[0].tpotMs ? fmt(Math.max(...rows.map(r => r.tpotMs.p99))) : '—'} |`);
  }
}
const limited = result.runs.filter(r => r.environmentLimited);
lines.push('', '## 解读与限制', '',
  `- ${limited.length}/${result.runs.length} 轮被标记为压测端/上游 CPU 或事件循环受限。标记轮次仍展示观测值，但不能用于宣称网关最大容量。`,
  '- 这是闭环并发模型，不代表固定到达率下的排队与过载表现；未定义业务 SLO，不能据此给出“安全最大 QPS”。',
  '- Basic BCrypt 不缓存校验结果，每个请求都进行 cost=10 校验；JWT 使用重复 Token，反映热验证缓存而非大量唯一 Token 的冷验证成本。',
  '- 1MiB 场景只测正文透传，不包含 WAF、Schema 校验或正文转换的组合开销。',
  '- P99 少于 1,000 个成功延迟样本时明确标记；低并发 SSE/Basic 的尾延迟只能作为初步观测。',
  '- 网关堆峰值包含等待 GC 的历史分配，不能当作每请求常驻内存；未采集网关操作系统 RSS。CPU/GC 为每秒采样窗口的近似差分。',
  '- 不对独立运行的 P99 做相减；比较成组分布、重复波动和直连上游对照。',
  '- 两次重复的吞吐变化超过 25% 时标记为波动较大，不能只取较快一次作为稳定能力；此轮基线不对波动原因作未经剖析的归因。',
  '- 线上仍需在相同 Java/JVM 配置下测 TLS、实际依赖和观测功能组合，再确定容量。', '',
  '## 复现', '', '```powershell', './tools/gateway-perf/run.ps1 -Levels "1,16,64" -Repetitions 2 -WarmupMs 3000 -HoldMs 8000',
  'node tools/gateway-perf/summarize.mjs <results.json> <report.md>', '```', '',
  '工具说明见 [tools/gateway-perf/README.md](../../tools/gateway-perf/README.md)。各轮 P50/P95/P99、HTTP 状态、错误分类、资源采样及环境标记保存在对应 results.json；没有保存每个请求的原始延迟。', '');
fs.mkdirSync(path.dirname(path.resolve(output)), { recursive: true });
fs.writeFileSync(output, lines.join('\n'));
const compact = { ...result, runs: result.runs.map(({ resources, ...run }) => run) };
fs.writeFileSync(output.replace(/\.md$/i, '') + '.json', JSON.stringify(compact, null, 2));
console.log(path.resolve(output));
