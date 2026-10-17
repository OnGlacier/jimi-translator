/**
 * dsh-rl-translator —— 屏幕英译中（强化学习 / 机器人方向）浮窗 · Host 半部
 *
 * 做什么：
 *   1. 定时（或手动）用 DSHA 桥截取平板当前屏幕；
 *   2. sharp 裁掉状态栏、缩到适合视觉模型的大小，并用一张极小灰度缩略图做「画面有没有变」的判定；
 *   3. 画面变了才把截图交给模型：一次调用同时做 OCR + 面向 RL/机器人的学术英译中；
 *   4. 译文按「句子」去重（归一化 key + 词集合 Jaccard），只把新句子追加进译文流；
 *   5. 新译文逐句推给 DSHA App 的手机悬浮条（/app/overlay），像字幕一样跟着英文往下走；
 *   6. 通过同源 HTTP 路由把译文流交给浏览器半部的浮窗面板。
 *
 * 刻意不做的事：
 *   - 不改动 agent 的会话日志：翻译是插件私有数据，落在 /root/.dsh/rl-translator/state.json；
 *   - 不因悬浮条/读屏失败而抛异常打崩插件树：所有 IO 都收敛成 status + 一句中文提示；
 *   - 不在画面没变时调模型：这是「不要重复翻译」的第一道闸，第二道闸是句子级去重。
 */
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { PAGE_HTML } from './page.js'

export const name = 'dsh-rl-translator'
export const inject = ['llm', 'attachments', 'webServer']

/** 桥只在回环上监听；容器与 App 共享网络命名空间。 */
const BRIDGE = 'http://127.0.0.1:3090'
const TOKEN_PATH = '/root/.dsh/.bridge_token'
const DATA_DIR = '/root/.dsh/rl-translator'
const DATA_FILE = DATA_DIR + '/state.json'
const ROUTE = '/dsh-rl-translator'
/** 独立翻译页：手机浏览器打开 http://127.0.0.1:3080/rl-translator ，可做系统浮窗。 */
const PAGE_ROUTE = '/rl-translator'

/** 手机悬浮条用它区分不同的输出源（状态条插件用会话短 id）。 */
const OVERLAY_SESSION = 'translate'

const MAX_ENTRIES = 300
const MAX_SEEN = 400
const MAX_QUEUE = 24

/** 系统提示：这是「RL 学术翻译 Agent」的人格与术语表。 */
const SYSTEM_PROMPT = `你是「RL 学术翻译」引擎：面向强化学习（reinforcement learning）与机器人学（robotics）方向的英文学术文献，把屏幕截图里的英文正文译成准确、书面、符合中文期刊/顶会论文语感的中文。

【输出格式】只输出一个 JSON 对象，不要 Markdown、不要解释：
{"segments":[{"en":"英文原文","zh":"中文译文"}]}
每个 segment 是一句（或一个短分句组）完整可读的话；按截图中从上到下的顺序排列。

【硬性规则】
1. 只输出"新的"内容：调用方会给出「已经翻译过的英文」，凡是与之重复（哪怕只差一个词、或只截到半句）的内容一律不要再输出。
2. 只读正文：忽略状态栏、时间、电量、页码、PDF 阅读器按钮/工具栏、选中高亮框、浮动翻译条、任何中文界面文字、页眉页脚、纯参考文献编号行。
3. 数学公式、变量名（如 π_θ、Q(s,a)、V^π）、算法与模型名（PPO、SAC、DDPG、TD3、MuJoCo、Isaac Gym）、数据集名、\cite/[12] 引用标记，保持原样，不翻译、不改写。
4. 处理 PDF 断行：跨行被拆开的单词要拼回（如 "reinforce-\\nment" → "reinforcement"），段内换行合并，句末缺标点按语义补全。
5. 忠实、简洁、不扩写、不解释、不添加原文没有的内容；不用口语，不用"我们"，不写"这段话的意思是"。
6. 如果截图中没有可翻译的新正文（例如还是同一页、或只有图表），输出 {"segments":[]}。

【术语对照（默认遵循，除非原文另有定义）】
reinforcement learning 强化学习；policy 策略；value function 价值函数；action-value/Q-function 动作价值函数；policy gradient 策略梯度；actor-critic 演员-评论家（首次出现可保留 actor-critic）；Markov decision process 马尔可夫决策过程；partially observable 部分可观测；reward 奖励；return 回报；discount factor 折扣因子；trajectory 轨迹；rollout 采样轨迹/rollout；episode 回合；horizon 时域/时长；state 状态；observation 观测；action 动作；transition 转移；on-policy 同策略；off-policy 异策略；exploration 探索；exploitation 利用；bootstrapping 自举；temporal difference 时序差分；sample efficiency 样本效率；baseline 基线；advantage 优势；entropy 熵；KL divergence KL 散度；trust region 信赖域；behavior policy 行为策略；target network 目标网络；replay buffer 经验回放池；imitation learning 模仿学习；inverse RL 逆强化学习；offline RL 离线强化学习；model-based 基于模型的；model-free 无模型的；meta-RL 元强化学习；hierarchical RL 分层强化学习；curriculum learning 课程学习；sim-to-real 仿真到现实（sim-to-real）；domain randomization 域随机化；hindsight experience replay 事后经验回放；intrinsic reward 内在奖励；manipulation 操作；grasping 抓取；dexterous 灵巧；locomotion 运动控制/行走；navigation 导航；end-effector 末端执行器；gripper 夹爪；kinematics 运动学；dynamics 动力学；degrees of freedom/DoF 自由度；Jacobian 雅可比矩阵；torque 力矩；joint 关节；contact 接触；pose 位姿；point cloud 点云；SLAM 同步定位与建图；embodied AI 具身智能；vision-language-action (VLA) 视觉-语言-动作（VLA）；teleoperation 遥操作；generalization 泛化；ablation 消融；benchmark 基准；ground truth 真值；proposed method 本文方法。`

function clamp(v, lo, hi) {
  const n = Number(v)
  if (!Number.isFinite(n)) return lo
  return Math.min(hi, Math.max(lo, n))
}

/** 归一化：小写、合并空白与连字符、去掉标点，用于判重。 */
function normKey(text) {
  return String(text || '')
    .toLowerCase()
    .replace(/[\u00ad]/g, '')
    .replace(/[-–—]+/g, ' ')
    .replace(/[^\p{L}\p{N}\s]+/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

const STOP = new Set(['the', 'and', 'for', 'with', 'that', 'this', 'from', 'are', 'was', 'were', 'our', 'which', 'these', 'those', 'than', 'such', 'into', 'have', 'has', 'not', '但是'])

function wordsOf(text) {
  const out = []
  for (const w of normKey(text).split(' ')) {
    if (w.length < 3 || STOP.has(w)) continue
    out.push(w)
  }
  return out
}

function jaccard(a, b) {
  if (!a.length || !b.length) return 0
  const sa = new Set(a)
  let inter = 0
  for (const w of new Set(b)) if (sa.has(w)) inter++
  const union = sa.size + new Set(b).size - inter
  return union === 0 ? 0 : inter / union
}

function cleanText(text, max) {
  return String(text || '').replace(/\s+/g, ' ').trim().slice(0, max || 800)
}

/** 从模型回复里取 JSON；容忍 ```json 围栏、前后废话、以及退化成纯数组。 */
function parseSegments(raw) {
  let s = String(raw || '').trim()
  if (!s) return []
  s = s.replace(/^```[a-zA-Z]*\s*/m, '').replace(/```\s*$/m, '').trim()
  const first = s.search(/[[{]/)
  if (first > 0) s = s.slice(first)
  const lastObj = s.lastIndexOf('}')
  const lastArr = s.lastIndexOf(']')
  const last = Math.max(lastObj, lastArr)
  if (last >= 0) s = s.slice(0, last + 1)
  let value
  try {
    value = JSON.parse(s)
  } catch {
    // 模型偶尔会给出 NDJSON / 多段 JSON：抓所有 {"en":..,"zh":..} 片段
    const found = []
    const re = /\{[^{}]*"en"\s*:\s*"((?:[^"\\]|\\.)*)"[^{}]*"zh"\s*:\s*"((?:[^"\\]|\\.)*)"[^{}]*\}/g
    let m
    while ((m = re.exec(String(raw))) !== null) {
      try {
        found.push({ en: JSON.parse('"' + m[1] + '"'), zh: JSON.parse('"' + m[2] + '"') })
      } catch { /* 跳过坏片段 */ }
    }
    return found
  }
  const list = Array.isArray(value) ? value : Array.isArray(value?.segments) ? value.segments : []
  return list
    .filter((x) => x && typeof x === 'object')
    .map((x) => ({ en: String(x.en ?? x.src ?? x.source ?? ''), zh: String(x.zh ?? x.zh_cn ?? x.translation ?? '') }))
    .filter((x) => x.en.trim() && x.zh.trim())
}

/** 逐句切分中文，喂给手机悬浮条（一行放不下太长，按标点切）。 */
function splitZh(text, max = 78) {
  const s = String(text || '').trim()
  if (!s) return []
  const out = []
  let buf = ''
  for (const ch of s) {
    buf += ch
    const isBreak = /[。！？；：，、,.!?;:]/.test(ch)
    if ((isBreak && buf.length >= max * 0.6) || buf.length >= max) {
      out.push(buf.trim())
      buf = ''
    }
  }
  if (buf.trim()) out.push(buf.trim())
  return out.filter(Boolean)
}

/** 解析 sharp：优先从 dsh 安装目录解析（与内置插件同源），再退回裸 import。 */
let sharpLoader
async function getSharp() {
  if (!sharpLoader) sharpLoader = (async () => {
    const bases = [
      process.env.DSH_INSTALL_DIR,
      '/usr/local/lib/node_modules/@deepseek-ai/dsh',
      '/usr/local/lib/node_modules/deepseek-harness',
    ].filter(Boolean)
    for (const base of bases) {
      try {
        const req = createRequire(base + '/package.json')
        const mod = req('sharp')
        return mod?.default ?? mod
      } catch { /* 下一个候选 */ }
    }
    try {
      const mod = await import('sharp')
      return mod?.default ?? mod
    } catch {
      return null
    }
  })()
  return sharpLoader
}

function humanError(error) {
  const m = String(error?.message || error || '')
  if (/SCREENSHOT_RUN_CHANGED/.test(m)) return '这一帧没截到（屏幕正在滚动/动画中），稍后重试即可'
  if (/SCREEN_TARGET_UNAVAILABLE/.test(m)) return '读屏暂时不可用：DSHA 的无障碍服务掉线了，重开 App 或到系统设置里把 DSHA 的无障碍开关关一下再开'
  if (/BRIDGE_UNREACHABLE/.test(m)) return '连不上 DSHA 的本地桥（3090），App 可能正在重启，几秒后再试'
  if (/NO_PERMISSION/.test(m)) return '缺少权限：请在 DSHA App 设置里授予读屏或悬浮窗权限'
  if (/DISABLED/.test(m)) return '该能力在 DSHA App 里被关闭了，请到 App 设置里重新打开'
  if (/BRIDGE_NOT_READY/.test(m)) return 'DSHA 桥未就绪：App 没在前台运行，或 /root/.dsh/.bridge_token 不可读'
  if (/BRIDGE_HTTP_(\d+)/.test(m)) return 'DSHA 桥返回 HTTP ' + m.match(/BRIDGE_HTTP_(\d+)/)[1]
  if (/SCREENSHOT_RESPONSE_INVALID/.test(m)) return '截屏响应格式异常：DSHA App 版本可能过旧，需要升级'
  if (/SHARP_UNAVAILABLE/.test(m)) return '图像处理库不可用（sharp），已退回原图直传'
  if (/timeout|aborted|AbortError/i.test(m)) return '请求超时：截图或模型响应太慢，已跳过这一轮'
  return m.slice(0, 300) || '未知错误'
}

export function apply(ctx) {
  /** 运行状态 + 用户设置，一起持久化。 */
  const rt = {
    // 设置
    running: false,
    intervalMs: 8000,
    overlayOn: true,
    cropTopRatio: 0.035,
    captureWidth: 1500,
    jpegQuality: 80,
    // 运行态
    status: 'idle',
    busy: false,
    lastError: '',
    lastErrorAt: 0,
    lastCaptureAt: 0,
    lastNewAt: 0,
    lastChangeAt: 0,
    lastEmptyAt: 0,
    entries: [],
    seen: [],
    seq: 0,
    stats: { captures: 0, calls: 0, segments: 0, duplicates: 0, failures: 0 },
    overlay: { queue: [], coolingUntil: 0, hint: '', lastPushed: '' },
    lastThumb: null,
    timer: null,
    saveTimer: null,
    /** 面板是否真的挂载了：浏览器半部每次轮询 +1，诊断用。 */
    hits: { state: 0, control: 0, lastStateAt: 0 },
  }

  // ---------------------------------------------------------------- 持久化
  function loadState() {
    try {
      if (!existsSync(DATA_FILE)) return
      const raw = JSON.parse(readFileSync(DATA_FILE, 'utf8'))
      if (!raw || typeof raw !== 'object') return
      rt.intervalMs = clamp(raw.intervalMs ?? rt.intervalMs, 3000, 120000)
      rt.overlayOn = raw.overlayOn !== false
      rt.cropTopRatio = clamp(raw.cropTopRatio ?? rt.cropTopRatio, 0, 0.3)
      rt.captureWidth = clamp(raw.captureWidth ?? rt.captureWidth, 600, 3000)
      rt.jpegQuality = clamp(raw.jpegQuality ?? rt.jpegQuality, 50, 95)
      rt.entries = Array.isArray(raw.entries) ? raw.entries.filter((e) => e && e.zh).slice(-MAX_ENTRIES) : []
      rt.seen = Array.isArray(raw.seen) ? raw.seen.filter((s) => s && s.k).slice(-MAX_SEEN) : []
      rt.seq = Number.isFinite(raw.seq) ? raw.seq : rt.entries.length
      if (raw.stats && typeof raw.stats === 'object') rt.stats = { ...rt.stats, ...raw.stats }
    } catch { /* 坏文件当作首次运行 */ }
  }

  function saveState() {
    try {
      mkdirSync(DATA_DIR, { recursive: true })
      const payload = JSON.stringify({
        version: 1,
        intervalMs: rt.intervalMs,
        overlayOn: rt.overlayOn,
        cropTopRatio: rt.cropTopRatio,
        captureWidth: rt.captureWidth,
        jpegQuality: rt.jpegQuality,
        entries: rt.entries.slice(-MAX_ENTRIES),
        seen: rt.seen.slice(-MAX_SEEN),
        seq: rt.seq,
        stats: rt.stats,
      })
      const tmp = DATA_FILE + '.' + process.pid + '.tmp'
      writeFileSync(tmp, payload, { mode: 0o600 })
      renameSync(tmp, DATA_FILE)
    } catch { /* 落盘失败不影响翻译 */ }
  }

  function saveSoon(delay = 700) {
    if (rt.saveTimer) return
    rt.saveTimer = setTimeout(() => {
      rt.saveTimer = null
      saveState()
    }, delay)
    rt.saveTimer.unref?.()
  }

  // ---------------------------------------------------------------- 桥
  let tokenCache = { value: '', at: 0 }
  function bridgeToken() {
    if (tokenCache.value && Date.now() - tokenCache.at < 5000) return tokenCache.value
    tokenCache.at = Date.now()
    try {
      tokenCache.value = readFileSync(TOKEN_PATH, 'utf8').trim()
    } catch {
      tokenCache.value = ''
    }
    return tokenCache.value
  }

  /** 调桥并取出 result 字符串；[ERR]/POLICY_BLOCKED 之类转成异常。 */
  async function bridge(pathname, timeoutMs = 15000) {
    const token = bridgeToken()
    if (!token) throw new Error('BRIDGE_NOT_READY')
    let response
    try {
      response = await fetch(BRIDGE + pathname, {
        headers: { 'X-Token': token },
        signal: AbortSignal.timeout(timeoutMs),
      })
    } catch (error) {
      throw new Error('BRIDGE_UNREACHABLE: ' + (error?.message || error))
    }
    const text = await response.text()
    if (!response.ok) throw new Error('BRIDGE_HTTP_' + response.status)
    let value = text
    try {
      const parsed = JSON.parse(text)
      if (parsed && typeof parsed.result === 'string') value = parsed.result
    } catch { /* 纯文本响应 */ }
    const trimmed = String(value || '').trim()
    if (/^\[ERR\]|^\s*(?:POLICY_BLOCKED|EXECUTION_UNKNOWN|NO_PERMISSION|DISABLED|UNAUTHORIZED)\b/.test(trimmed)) {
      throw new Error(trimmed.slice(0, 200))
    }
    return trimmed
  }

  /** 截屏一次；SCREENSHOT_RUN_CHANGED（画面在变）/ 桥抖一下都值得重试，权限类错误不重试。 */
  async function capturePng(attempt = 0) {
    let raw
    try {
      raw = await bridge('/app/ui/screenshot?format=mcp', 25000)
    } catch (error) {
      const message = String(error?.message || error)
      if (attempt < 3 && /SCREENSHOT_RUN_CHANGED|BRIDGE_UNREACHABLE|SCREEN_TARGET_UNAVAILABLE/.test(message)) {
        await new Promise((resolve) => setTimeout(resolve, 350 + attempt * 350))
        return capturePng(attempt + 1)
      }
      throw error
    }
    let shot
    try {
      shot = JSON.parse(raw)
    } catch {
      throw new Error('SCREENSHOT_RESPONSE_INVALID')
    }
    if (!shot || shot.kind !== 'dsha-screenshot-v1' || typeof shot.data !== 'string') throw new Error('SCREENSHOT_RESPONSE_INVALID')
    const png = Buffer.from(shot.data, 'base64')
    if (png.length < 128) throw new Error('SCREENSHOT_RESPONSE_INVALID')
    return png
  }

  // ---------------------------------------------------------------- 图像
  function thumbDiff(a, b) {
    if (!a || !b || a.length !== b.length) return Infinity
    let sum = 0
    for (let i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i])
    return sum / a.length
  }

  /** 裁状态栏 + 缩宽 + JPEG，另给一张 72×54 灰度图做变化检测。 */
  async function prepare(png) {
    const sharp = await getSharp()
    if (!sharp) {
      rt.lastThumb = createHash('sha1').update(png).digest('hex')
      return { jpeg: png, thumb: rt.lastThumb }
    }
    const meta = await sharp(png).metadata()
    const width0 = Number(meta.width) || 0
    const height0 = Number(meta.height) || 0
    if (!width0 || !height0) throw new Error('SCREENSHOT_RESPONSE_INVALID')
    const top = Math.round(height0 * rt.cropTopRatio)
    const height = Math.max(240, height0 - top)
    const width = Math.min(rt.captureWidth, width0)
    const base = sharp(png)
      .extract({ left: 0, top, width: width0, height })
      .resize({ width, withoutEnlargement: true })
    const jpeg = await base.clone().jpeg({ quality: rt.jpegQuality, mozjpeg: true }).toBuffer()
    const thumb = await base.clone().greyscale().resize(72, 54, { fit: 'fill' }).raw().toBuffer()
    return { jpeg, thumb }
  }

  /** 画面变化判定：缩略图平均绝对差。第一张永远算「变了」。 */
  function screenChanged(thumb) {
    const prev = rt.lastThumb
    rt.lastThumb = thumb
    if (typeof thumb === 'string' || typeof prev === 'string') return thumb !== prev
    if (!prev) return true
    return thumbDiff(prev, thumb) > 0.45
  }

  // ---------------------------------------------------------------- 模型
  function route() {
    try {
      const svc = ctx.get('agentDefaultModel')
      const selection = svc?.currentSelection?.()
      return {
        provider: selection?.provider || 'deepseek-official',
        model: selection?.model || 'deepseek-flash',
      }
    } catch {
      return { provider: 'deepseek-official', model: 'deepseek-flash' }
    }
  }

  async function collect(stream) {
    let text = ''
    let failure = null
    for await (const chunk of stream) {
      if (!chunk || typeof chunk !== 'object') continue
      if (chunk.type === 'text-delta') text += chunk.text || ''
      else if (chunk.type === 'finish') {
        const reason = chunk.reason
        if (reason && (reason.kind === 'error' || reason.kind === 'aborted')) failure = reason.failure
      }
    }
    if (failure) throw new Error(failure.message || failure.code || 'LLM_FAILED')
    return text
  }

  function buildUserText() {
    const known = rt.seen
      .slice(-26)
      .map((s) => cleanText(s.src, 220))
      .filter(Boolean)
    const knownBlock = known.length
      ? '【已经翻译过的英文（绝对不要再输出，哪怕只差一个词或只截到半句）】\n' + known.map((k, i) => `${i + 1}. ${k}`).join('\n')
      : '【已经翻译过的英文】〈暂无〉'
    return `${knownBlock}

【任务】
这是一张平板屏幕截图，通常是 PDF 阅读器（Zotero 等）里正在读的英文论文页面，可能停在段落中间。
按从上到下的顺序，找出截图中「完整可读、且不在上面列表里」的正文，切成句子/短分句组，逐句翻译成中文。
- 顶部可能残留上一屏的半句：若与列表重复就跳过，只输出真正的新内容。
- 若没有任何新内容，输出 {"segments":[]}。
- 不要输出页眉、页脚、页码、参考文献编号行、任何中文界面文字。

只输出 JSON：{"segments":[{"en":"原文","zh":"译文"}]}`
  }

  async function translateImage(jpeg, mediaType) {
    const ref = await ctx.attachments.saveImage({ data: jpeg, mediaType: mediaType || 'image/jpeg', name: 'screen.jpg' })
    const { provider, model } = route()
    const stream = ctx.llm.stream({
      provider,
      model,
      system: SYSTEM_PROMPT,
      temperature: 0.15,
      maxTokens: 1800,
      messages: [{
        role: 'user',
        content: [
          { type: 'image', attachment: ref },
          { type: 'text', text: buildUserText() },
        ],
      }],
      signal: AbortSignal.timeout(120000),
    })
    return parseSegments(await collect(stream))
  }

  // ---------------------------------------------------------------- 去重
  function isDuplicate(en) {
    const k = normKey(en)
    if (!k || k.length < 8) return true
    const w = wordsOf(en)
    for (let i = rt.seen.length - 1; i >= 0; i--) {
      const s = rt.seen[i]
      if (s.k === k) return true
      if (w.length >= 6 && Array.isArray(s.w) && s.w.length >= 6 && jaccard(s.w, w) >= 0.72) return true
    }
    return false
  }

  function remember(en) {
    rt.seen.push({ k: normKey(en), w: wordsOf(en).slice(0, 60), src: cleanText(en, 260), at: Date.now() })
    if (rt.seen.length > MAX_SEEN * 1.5) rt.seen.splice(0, rt.seen.length - MAX_SEEN)
  }

  // ---------------------------------------------------------------- 悬浮条
  function enqueueOverlay(texts) {
    for (const zh of texts) for (const chunk of splitZh(zh)) rt.overlay.queue.push(chunk)
    if (rt.overlay.queue.length > MAX_QUEUE) rt.overlay.queue.splice(0, rt.overlay.queue.length - MAX_QUEUE)
  }

  async function pumpOverlay() {
    if (!rt.overlayOn || rt.overlay.queue.length === 0) return
    if (Date.now() < rt.overlay.coolingUntil) return
    const text = rt.overlay.queue.shift()
    try {
      await bridge('/app/overlay?kind=text&session=' + OVERLAY_SESSION + '&text=' + encodeURIComponent(text), 6000)
      rt.overlay.hint = ''
      rt.overlay.lastPushed = text
    } catch (error) {
      const message = String(error?.message || error)
      if (/DISABLED|NO_PERMISSION/.test(message)) {
        rt.overlay.coolingUntil = Date.now() + 5 * 60_000
        rt.overlay.hint = /DISABLED/.test(message)
          ? '手机悬浮条没开：DSHA App → 设置 → 悬浮窗 / 状态悬浮条，打开它并授予悬浮窗权限'
          : '悬浮窗权限被拒：请到系统设置里给 DSHA App「显示在其他应用上层」'
        rt.overlay.queue.length = 0
      } else {
        rt.overlay.coolingUntil = Date.now() + 20_000
      }
    }
  }

  // ---------------------------------------------------------------- 主流程
  function setStatus(next) {
    rt.status = next
  }

  async function tick(options = {}) {
    if (rt.busy) return { skipped: 'busy' }
    rt.busy = true
    const startedAt = Date.now()
    try {
      setStatus('capturing')
      const png = await capturePng()
      rt.lastCaptureAt = Date.now()
      rt.stats.captures++
      const prepared = await prepare(png)
      if (!options.force && !screenChanged(prepared.thumb)) {
        setStatus(rt.running ? 'running' : 'idle')
        rt.lastError = ''
        saveSoon(3000)
        return { skipped: 'unchanged' }
      }
      rt.lastChangeAt = Date.now()
      setStatus('thinking')
      const segments = await translateImage(prepared.jpeg)
      rt.stats.calls++
      const fresh = []
      let duplicates = 0
      for (const segment of segments) {
        const en = cleanText(segment.en, 900)
        const zh = cleanText(segment.zh, 900)
        if (!en || !zh) continue
        if (isDuplicate(en)) { duplicates++; continue }
        remember(en)
        fresh.push({ en, zh })
        if (fresh.length >= 14) break
      }
      rt.stats.duplicates += duplicates
      if (fresh.length > 0) {
        rt.lastNewAt = Date.now()
        for (const item of fresh) {
          rt.entries.push({ id: ++rt.seq, en: item.en, zh: item.zh, at: Date.now() })
          rt.stats.segments++
        }
        if (rt.entries.length > MAX_ENTRIES) rt.entries.splice(0, rt.entries.length - MAX_ENTRIES)
        enqueueOverlay(fresh.map((item) => item.zh))
        saveSoon(300)
      } else {
        saveSoon(2000)
      }
      setStatus(rt.running ? 'running' : 'idle')
      rt.lastError = ''
      rt.lastEmptyAt = fresh.length === 0 ? Date.now() : 0
      return { added: fresh.length, duplicates, ms: Date.now() - startedAt }
    } catch (error) {
      rt.stats.failures++
      setStatus('error')
      rt.lastError = humanError(error)
      rt.lastErrorAt = Date.now()
      const message = String(error?.message || '')
      if (/NO_PERMISSION|DISABLED|BRIDGE_NOT_READY/.test(message)) {
        rt.running = false
        stopLoop()
      } else if (!rt.running) {
        // 手动触发失败：保持停止态即可
      }
      saveSoon(300)
      return { error: rt.lastError }
    } finally {
      rt.busy = false
      saveSoon(1500)
    }
  }

  function stopLoop() {
    if (rt.timer) clearTimeout(rt.timer)
    rt.timer = null
  }

  function startLoop() {
    stopLoop()
    const run = async () => {
      rt.timer = null
      if (!rt.running) return
      await tick()
      if (!rt.running) return
      rt.timer = setTimeout(run, rt.intervalMs)
      rt.timer.unref?.()
    }
    rt.timer = setTimeout(run, 250)
    rt.timer.unref?.()
  }

  function publicState() {
    return {
      ok: true,
      config: {
        running: rt.running,
        intervalMs: rt.intervalMs,
        overlayOn: rt.overlayOn,
        cropTopRatio: rt.cropTopRatio,
        captureWidth: rt.captureWidth,
      },
      status: rt.status,
      busy: rt.busy,
      lastError: rt.lastError,
      lastErrorAt: rt.lastErrorAt,
      lastCaptureAt: rt.lastCaptureAt,
      lastNewAt: rt.lastNewAt,
      lastEmptyAt: rt.lastEmptyAt,
      stats: rt.stats,
      overlay: {
        pending: rt.overlay.queue.length,
        hint: rt.overlay.hint,
        lastPushed: rt.overlay.lastPushed.slice(0, 80),
      },
      model: route(),
      seq: rt.seq,
      entries: rt.entries.slice(-120),
      hits: rt.hits.state,
    }
  }

  // ---------------------------------------------------------------- 控制
  function applySettings(patch) {
    if (patch && typeof patch === 'object') {
      if (patch.intervalMs !== undefined) rt.intervalMs = clamp(patch.intervalMs, 3000, 120000)
      if (patch.overlayOn !== undefined) rt.overlayOn = Boolean(patch.overlayOn)
      if (patch.cropTopRatio !== undefined) rt.cropTopRatio = clamp(patch.cropTopRatio, 0, 0.3)
      if (patch.captureWidth !== undefined) rt.captureWidth = clamp(patch.captureWidth, 600, 3000)
    }
    saveSoon(200)
  }

  async function launchZotero() {
    const candidates = ['org.zotero.android', 'com.zotero.Zotero', 'org.zotero.Zotero', 'com.zotero.android']
    const errors = []
    for (const pkg of candidates) {
      try {
        await bridge('/app/launch?pkg=' + encodeURIComponent(pkg), 8000)
        return { ok: true, pkg }
      } catch (error) {
        errors.push(pkg + ': ' + String(error?.message || error).slice(0, 60))
      }
    }
    return { ok: false, error: '没找到已安装的 Zotero（' + errors.join('；') + '）。可以手动切到 Zotero，翻译照样读屏。' }
  }

  async function control(body) {
    const action = String(body?.action || '')
    switch (action) {
      case 'start': {
        rt.running = true
        rt.lastError = ''
        setStatus('running')
        startLoop()
        saveSoon(100)
        return { ok: true }
      }
      case 'stop': {
        rt.running = false
        stopLoop()
        setStatus(rt.lastError ? 'error' : 'idle')
        saveSoon(100)
        return { ok: true }
      }
      case 'now': {
        const result = await tick({ force: true })
        return { ok: true, result }
      }
      case 'set': {
        applySettings(body.settings || body)
        if (rt.running) startLoop()
        return { ok: true }
      }
      case 'clear': {
        rt.entries = []
        rt.seen = []
        rt.seq = 0
        rt.stats.segments = 0
        rt.stats.duplicates = 0
        rt.lastNewAt = 0
        rt.overlay.queue.length = 0
        saveSoon(100)
        return { ok: true }
      }
      case 'reset-seen': {
        rt.seen = []
        saveSoon(100)
        return { ok: true }
      }
      case 'overlay-test': {
        rt.overlayOn = true
        rt.overlay.coolingUntil = 0
        rt.overlay.queue = splitZh(body?.text || 'RL 学术翻译浮窗已连接：滚动英文论文，这里会跟着出新译文。')
        await pumpOverlay()
        return { ok: true, hint: rt.overlay.hint }
      }
      case 'launch-zotero': {
        return await launchZotero()
      }
      default:
        return { ok: false, error: '未知操作：' + action }
    }
  }

  // ---------------------------------------------------------------- 自检
  /** 用文字渲染一张「论文截图」，走完整条链路；用于验证模型/术语表，不写入译文流。 */
  async function selfTest() {
    const sharp = await getSharp()
    if (!sharp) return { ok: false, error: 'sharp 不可用，无法生成测试图' }
    const lines = [
      'Policy Gradient Methods for Reinforcement Learning',
      '',
      'We consider a Markov decision process with state space S, action space A,',
      'transition dynamics P(s\'|s,a), and discount factor gamma. The objective is to',
      'maximize the expected discounted return J(theta) = E[sum_t gamma^t r_t].',
      'The policy gradient theorem states that the gradient of the objective can be',
      'written as the expectation of the score function multiplied by the advantage',
      'function. This allows us to estimate the gradient from trajectories collected',
      'by the current policy, without differentiating through the environment dynamics.',
      'We evaluate the proposed method on a set of continuous control benchmarks in',
      'MuJoCo, including HalfCheetah and Ant, and compare it against PPO and SAC.',
      'Our approach improves sample efficiency and achieves a higher average return.',
    ]
    const esc = lines.map((line, i) => line
      ? `<text x="70" y="${120 + i * 46}" font-family="KaTeX_Main, DejaVu Serif, serif" font-size="27" fill="#1a1a1a">${line.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/'/g, '&#39;')}</text>`
      : '').join('\n')
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="900" height="${160 + lines.length * 46}">
      <rect width="100%" height="100%" fill="#fbfbf8"/>${esc}</svg>`
    const jpeg = await sharp(Buffer.from(svg)).jpeg({ quality: 88 }).toBuffer()
    const startedAt = Date.now()
    const segments = await translateImage(jpeg, 'image/jpeg')
    return {
      ok: true,
      ms: Date.now() - startedAt,
      count: segments.length,
      segments: segments.slice(0, 6),
      route: route(),
    }
  }

  // ---------------------------------------------------------------- HTTP
  function readJson(req) {
    return new Promise((resolve) => {
      let size = 0
      const parts = []
      req.on('data', (chunk) => {
        size += chunk.length
        if (size > 256 * 1024) {
          req.destroy()
          resolve({})
          return
        }
        parts.push(chunk)
      })
      req.on('end', () => {
        try {
          resolve(parts.length ? JSON.parse(Buffer.concat(parts).toString('utf8')) : {})
        } catch {
          resolve({})
        }
      })
      req.on('error', () => resolve({}))
    })
  }

  function sendJson(res, code, value) {
    const body = JSON.stringify(value)
    res.writeHead(code, {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
      'content-length': Buffer.byteLength(body),
    })
    res.end(body)
  }

  async function dispatchGet(path) {
    if (path === '/' || path === '/state') {
      rt.hits.state++
      rt.hits.lastStateAt = Date.now()
      return { status: 200, value: publicState() }
    }
    return { status: 404, value: { ok: false, error: 'not found' } }
  }

  async function dispatchPost(path, body) {
    if (path === '/control') {
      rt.hits.control++
      const result = await control(body)
      return { status: 200, value: { ...result, state: publicState() } }
    }
    if (path === '/selftest') {
      return { status: 200, value: await selfTest() }
    }
    return { status: 404, value: { ok: false, error: 'not found' } }
  }

  async function handleRoute(req, res) {
    try {
      const url = new URL(req.url || '/', 'http://127.0.0.1')
      const path = url.pathname.slice(ROUTE.length) || '/'
      if (req.method === 'GET') {
        const result = await dispatchGet(path)
        return sendJson(res, result.status, result.value)
      }
      if (req.method === 'POST') {
        const result = await dispatchPost(path, await readJson(req))
        return sendJson(res, result.status, result.value)
      }
      return sendJson(res, 405, { ok: false, error: 'method not allowed' })
    } catch (error) {
      return sendJson(res, 500, { ok: false, error: String(error?.message || error) })
    }
  }

  // ---------------------------------------------------------------- 装配
  loadState()

  /** 独立页：只回一张 HTML，接口仍走 ROUTE（同源、无需 DSH 的 Cookie）。 */
  async function handlePage(req, res) {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { 'content-type': 'text/plain; charset=utf-8' })
      res.end('method not allowed')
      return
    }
    res.writeHead(200, {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-cache',
      'referrer-policy': 'no-referrer',
    })
    res.end(PAGE_HTML)
  }

  ctx.effect(() => {
    const disposePage = ctx.webServer.register({ kind: 'prefix', path: PAGE_ROUTE, handler: handlePage })
    return () => disposePage()
  }, 'rl-translator: standalone page')

  ctx.effect(() => {
    const dispose = ctx.webServer.register({ kind: 'prefix', path: ROUTE, handler: handleRoute })
    return () => {
      dispose()
      stopLoop()
      if (rt.saveTimer) clearTimeout(rt.saveTimer)
      saveState()
    }
  }, 'rl-translator: http route')

  // 同一条数据也挂在 /api 下：DSHA App 的 WebView 只把 /api/* 转发给 Host（其它路径会被
  // 当成静态资源吃掉），而且 /api 由 Connection 统一做 Host/Origin 与浏览器 Cookie 校验。
  // 两条通道都开着，浏览器半部按顺序探测。
  ctx.inject(['connection'], (connectionCtx) => {
    connectionCtx.effect(() => {
      const disposers = []
      const add = (suffix, methods) => {
        try {
          disposers.push(connectionCtx.connection.fetch.register({
            path: '/api/rl-translator' + suffix,
            methods,
            requestBody: 'buffered',
            fetch: async (request) => {
              try {
                if (request.method === 'GET') {
                  const result = await dispatchGet(suffix)
                  return Response.json(result.value, { status: result.status, headers: { 'cache-control': 'no-store' } })
                }
                let body = {}
                try { body = await request.json() } catch { /* 无 body */ }
                const result = await dispatchPost(suffix, body)
                return Response.json(result.value, { status: result.status, headers: { 'cache-control': 'no-store' } })
              } catch (error) {
                return Response.json({ ok: false, error: String(error?.message || error) }, { status: 500 })
              }
            },
          }))
        } catch (error) {
          // 重复注册（插件热重载）不该把入口整崩：保留已有那条即可
          try { ctx.logger?.warn?.('rl-translator: /api route ' + suffix + ' skipped: ' + String(error?.message || error)) } catch { /* ignore */ }
        }
      }
      add('/state', ['GET'])
      add('/control', ['POST'])
      add('/selftest', ['POST'])
      return () => { for (const dispose of disposers) { try { dispose() } catch { /* ignore */ } } }
    }, 'rl-translator: /api fetch routes')
  })

  // 悬浮条推送节拍：一句一推，像字幕。
  ctx.effect(() => {
    const timer = setInterval(() => { void pumpOverlay() }, 2600)
    timer.unref?.()
    return () => clearInterval(timer)
  }, 'rl-translator: overlay pump')

  if (rt.running) {
    // 上次退出时还开着自动跟读 → 继续（用户明确开过）
    rt.status = 'running'
    startLoop()
  }

  // 给 agent 的控制工具：和浮窗按钮走同一套 control()。
  ctx.inject(['tools'], (toolsCtx) => {
    toolsCtx.effect(() => {
      let dispose
      try {
        const { defineTool } = createRequire(import.meta.url)('@deepseek-ai/dsh-tools')
        dispose = toolsCtx.tools.register(defineTool({
          name: 'screen_translate',
          description: '控制「屏幕英译中（RL/机器人）」浮窗：start 开始自动跟读当前屏幕并持续翻译新内容；stop 停止；now 立即翻译当前屏幕一屏；status 读取状态与最近译文；clear 清空译文流与去重记录。翻译结果进入 DSH 浮窗与手机悬浮条，不占用会话上下文。',
          parameters: {
            action: {
              type: 'string',
              required: true,
              enum: ['start', 'stop', 'now', 'status', 'clear'],
              description: 'start / stop / now / status / clear',
            },
            intervalMs: {
              type: 'number',
              description: '仅 start 时可选：自动跟读间隔毫秒（3000–120000，默认 8000）。',
            },
            overlayOn: {
              type: 'boolean',
              description: '可选：是否把新译文推送到手机悬浮条。',
            },
          },
          output: {
            schema: { type: 'string' },
            render: (_args, value) => [{ type: 'text', text: String(value) }],
          },
          async execute(args) {
            const action = String(args?.action || 'status')
            if (action === 'status') {
              const state = publicState()
              return JSON.stringify({
                running: state.config.running,
                status: state.status,
                lastError: state.lastError || null,
                entries: state.entries.slice(-3).map((e) => ({ en: e.en, zh: e.zh })),
                stats: state.stats,
                overlay: state.overlay,
              }, null, 2)
            }
            const body = { action }
            if (action === 'start') {
              if (args.intervalMs !== undefined) body.settings = { ...(body.settings || {}), intervalMs: args.intervalMs }
              if (args.overlayOn !== undefined) body.settings = { ...(body.settings || {}), overlayOn: args.overlayOn }
            }
            const result = await control(body)
            if (body.settings) applySettings(body.settings)
            return JSON.stringify({ ok: result.ok !== false, ...result, status: rt.status, running: rt.running })
          },
          presentCall: (args) => ({ card: 'generic', title: '屏幕学术翻译', kind: 'other', rawInput: args.action }),
        }))
      } catch (error) {
        ctx.logger?.warn?.('rl-translator: tool registration skipped: ' + String(error?.message || error))
      }
      return () => { try { dispose?.() } catch { /* ignore */ } }
    }, 'rl-translator: tool')
  })

  // 让对话里的 agent 也知道这个能力（短，不喧宾夺主）。
  ctx.inject(['systemPrompt'], (promptCtx) => {
    promptCtx.effect(() => promptCtx.systemPrompt.section({
      name: 'rl-translator',
      order: 90,
      text: '屏幕学术翻译（RL/机器人）浮窗由插件 dsh-rl-translator 提供：工具 screen_translate 可 start/stop/now/status/clear。它直接读平板屏幕上的英文论文并只翻译新出现的内容，译文进入 DSH 浮窗面板与手机悬浮条。用户说「继续翻」「看这页」时优先用 now/start，不要凭记忆复述旧译文。',
    }), 'rl-translator: prompt section')
  })
}
