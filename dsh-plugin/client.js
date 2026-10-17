/**
 * dsh-rl-translator —— 屏幕英译中（RL / 机器人）浮窗 · 浏览器半部
 *
 * 注册到 shell.overlay（frame 级浮层，位置自己说了算），轮询 Host 的同源路由
 * /dsh-rl-translator/state 拿译文流；所有会花钱的动作（截屏、调模型）都在 Host。
 *
 * 交互刻意保持简单：
 *   - 拖标题栏挪位置，位置记在 localStorage；
 *   - 折叠成右下角一颗「译」药丸，不挡论文；
 *   - 自动跟读 / 译一屏 / 悬浮条 三个开关，剩下的交给去重；
 *   - 每条译文点「EN」才显示英文原文，默认只给中文，方便顺着读。
 */
window.__ModuleLoader__.load({
  id: 'dsh-rl-translator',
  factory(require) {
    const React = require('react')
    const { useCallback, useEffect, useMemo, useRef, useState } = React
    const h = React.createElement

    // DSHA 的 WebView 只把 /api/* 转发给 Host，其它路径可能被当静态资源吃掉；
    // 所以两条都试，先 /api（官方通道，带 Cookie 校验），再退回自定义前缀。
    const BASES = ['/api/rl-translator', '/dsh-rl-translator']
    let activeBase = null
    let lastDiag = ''
    const POS_KEY = 'rltr.pos.v1'
    const OPEN_KEY = 'rltr.open.v1'
    const READ_KEY = 'rltr.read.v1'

    const STATUS_TEXT = {
      idle: '待机',
      running: '自动跟读中',
      capturing: '读取屏幕…',
      thinking: '翻译中…',
      error: '需要处理',
    }

    async function api(path, body) {
      const init = body === undefined
        ? { method: 'GET' }
        : { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }
      const order = activeBase ? [activeBase, ...BASES.filter((base) => base !== activeBase)] : BASES
      const errors = []
      for (const base of order) {
        try {
          const response = await fetch(base + path, init)
          if (!response.ok) {
            errors.push(base + ' → HTTP ' + response.status)
            continue
          }
          const value = await response.json()
          activeBase = base
          lastDiag = ''
          return value
        } catch (error) {
          errors.push(base + ' → ' + String((error && error.message) || error))
        }
      }
      activeBase = null
      lastDiag = errors.join('；')
      throw new Error(lastDiag)
    }

    function two(n) {
      return String(n).padStart(2, '0')
    }

    function clock(ts) {
      if (!ts) return '—'
      const d = new Date(ts)
      return two(d.getHours()) + ':' + two(d.getMinutes()) + ':' + two(d.getSeconds())
    }

    function readPos() {
      try {
        const raw = JSON.parse(localStorage.getItem(POS_KEY) || 'null')
        if (raw && Number.isFinite(raw.x) && Number.isFinite(raw.y)) return raw
      } catch { /* ignore */ }
      return null
    }

    function defaultPos() {
      const w = typeof window === 'undefined' ? 1024 : window.innerWidth
      const hgt = typeof window === 'undefined' ? 768 : window.innerHeight
      const width = Math.min(404, w - 24)
      // 卡片最高 62vh（上限 560）；默认贴在右下角、输入框上方，别压住发送键。
      const height = Math.min(560, Math.round(hgt * 0.62))
      return {
        x: Math.max(8, w - width - 14),
        y: Math.max(56, hgt - height - 96),
      }
    }

    /** 旋转屏幕 / 换设备后把记下的位置拉回视口内。 */
    function clampPos(pos) {
      if (typeof window === 'undefined') return pos
      const w = window.innerWidth
      const h = window.innerHeight
      const width = Math.min(404, w - 20)
      return {
        x: Math.min(Math.max(4, pos.x), Math.max(4, w - width - 4)),
        y: Math.min(Math.max(4, pos.y), Math.max(4, h - 96)),
      }
    }

    const CSS = `
.rltr-card {
  position: fixed; z-index: 2140; display: flex; flex-direction: column;
  width: 404px; max-width: calc(100vw - 20px); max-height: min(62vh, 560px);
  background: var(--dsw-alias-bg-overlay, var(--dsw-alias-bg-layer-1));
  color: var(--dsw-alias-label-primary);
  border: 1px solid var(--dsw-alias-border-l1);
  border-radius: 16px;
  box-shadow: 0 18px 48px rgba(0,0,0,.26), 0 2px 6px rgba(0,0,0,.10);
  pointer-events: auto; overflow: hidden;
  font-family: -apple-system, "PingFang SC", "Noto Sans SC", "HarmonyOS Sans SC", system-ui, sans-serif;
  font-size: 14px; line-height: 1.5;
}
.rltr-head {
  display: flex; align-items: center; gap: 8px; padding: 9px 8px 8px 12px;
  border-bottom: 1px solid var(--dsw-alias-border-l1);
  background: var(--dsw-alias-bg-layer-1);
  cursor: grab; touch-action: none; user-select: none;
}
.rltr-head:active { cursor: grabbing; }
.rltr-title { font-size: 13px; font-weight: 600; letter-spacing: .02em; white-space: nowrap; }
.rltr-spacer { flex: 1; }
.rltr-status { display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; color: var(--dsw-alias-label-secondary); white-space: nowrap; }
.rltr-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--dsw-alias-state-idle-primary); flex: none; }
.rltr-dot[data-s="running"], .rltr-dot[data-s="capturing"], .rltr-dot[data-s="thinking"] { background: var(--dsw-alias-state-success-primary); animation: rltr-pulse 1.6s ease-in-out infinite; }
.rltr-dot[data-s="error"] { background: var(--dsw-alias-state-error-primary); }
@keyframes rltr-pulse { 0%,100% { opacity: 1 } 50% { opacity: .35 } }
.rltr-icon {
  appearance: none; border: none; background: transparent; color: var(--dsw-alias-label-secondary);
  width: 26px; height: 26px; border-radius: 8px; cursor: pointer; font-size: 15px; line-height: 1;
  display: inline-flex; align-items: center; justify-content: center; flex: none;
}
.rltr-icon:hover { background: var(--dsw-alias-bg-layer-2); color: var(--dsw-alias-label-primary); }
.rltr-bar { display: flex; align-items: center; gap: 6px; padding: 7px 10px; border-bottom: 1px solid var(--dsw-alias-border-l1); flex-wrap: wrap; }
.rltr-btn {
  appearance: none; border: 1px solid var(--dsw-alias-border-l1); background: transparent;
  color: var(--dsw-alias-label-primary); border-radius: 9px; padding: 4px 9px;
  font-size: 12.5px; font-family: inherit; cursor: pointer; white-space: nowrap;
}
.rltr-btn:hover { background: var(--dsw-alias-bg-layer-2); }
.rltr-btn:disabled { opacity: .45; cursor: default; }
.rltr-btn[data-on="1"] { background: var(--dsw-alias-brand-primary); border-color: transparent; color: #fff; }
.rltr-switch {
  position: relative; width: 34px; height: 20px; border-radius: 999px; border: none; padding: 0;
  background: var(--dsw-alias-border-l2); cursor: pointer; flex: none; transition: background .16s ease;
}
.rltr-switch[aria-checked="true"] { background: var(--dsw-alias-brand-primary); }
.rltr-knob { position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%; background: #fff; transition: transform .16s ease; box-shadow: 0 1px 2px rgba(0,0,0,.2); }
.rltr-switch[aria-checked="true"] .rltr-knob { transform: translateX(14px); }
.rltr-label { display: inline-flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--dsw-alias-label-secondary); white-space: nowrap; }
.rltr-error {
  margin: 8px 10px 0; padding: 7px 9px; border-radius: 10px; font-size: 12.5px; line-height: 1.5;
  color: var(--dsw-alias-state-error-primary);
  background: var(--dsw-alias-bg-layer-2);
  border: 1px solid var(--dsw-alias-state-error-primary);
}
.rltr-hint { margin: 8px 10px 0; padding: 7px 9px; border-radius: 10px; font-size: 12px; line-height: 1.5; color: var(--dsw-alias-state-warn-primary); background: var(--dsw-alias-bg-layer-2); border: 1px solid var(--dsw-alias-border-l1); }
.rltr-feed { overflow-y: auto; padding: 8px 12px 12px; display: flex; flex-direction: column; gap: 12px; -webkit-overflow-scrolling: touch; }
.rltr-empty { padding: 22px 8px 26px; text-align: center; color: var(--dsw-alias-label-secondary); font-size: 12.5px; line-height: 1.8; }
.rltr-item { border-left: 2px solid var(--dsw-alias-brand-primary); padding-left: 9px; }
.rltr-zh { font-size: 15px; line-height: 1.72; letter-spacing: .01em; word-break: break-word; }
.rltr-en { margin-top: 5px; font-size: 12px; line-height: 1.55; color: var(--dsw-alias-label-secondary); word-break: break-word; }
.rltr-meta { margin-top: 4px; display: flex; align-items: center; gap: 8px; font-size: 11px; color: var(--dsw-alias-label-secondary); opacity: .85; }
.rltr-mini {
  appearance: none; border: 1px solid var(--dsw-alias-border-l1); background: transparent; color: var(--dsw-alias-label-secondary);
  border-radius: 6px; padding: 0 5px; height: 17px; font-size: 10.5px; font-family: inherit; cursor: pointer; line-height: 15px;
}
.rltr-mini:hover { color: var(--dsw-alias-label-primary); background: var(--dsw-alias-bg-layer-2); }
.rltr-foot { display: flex; align-items: center; gap: 8px; padding: 7px 11px; border-top: 1px solid var(--dsw-alias-border-l1); background: var(--dsw-alias-bg-layer-1); font-size: 11.5px; color: var(--dsw-alias-label-secondary); }
.rltr-foot b { font-weight: 600; color: var(--dsw-alias-label-primary); }
.rltr-settings { padding: 9px 11px 11px; border-top: 1px solid var(--dsw-alias-border-l1); display: flex; flex-direction: column; gap: 9px; background: var(--dsw-alias-bg-layer-1); }
.rltr-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rltr-row > .rltr-spacer { flex: 1; }
.rltr-card[data-focus="1"] {
  left: 0; right: 0; top: 0; bottom: 0; width: auto; max-height: none;
  border-radius: 0; border: none;
}
.rltr-card[data-focus="1"] .rltr-feed { padding: 10px 16px 96px; }
.rltr-card[data-focus="1"] .rltr-zh { font-size: 17px; line-height: 1.8; }
.rltr-card[data-focus="1"] .rltr-bar { padding: 8px 12px; }
.rltr-card[data-narrow="1"] {
  left: 6px; right: 6px; top: auto; bottom: 6px; width: auto;
  max-height: 46vh; border-radius: 14px;
}
.rltr-card[data-narrow="1"] .rltr-head { cursor: default; padding: 7px 6px 6px 10px; }
.rltr-card[data-narrow="1"] .rltr-bar { padding: 6px 8px; gap: 5px; }
.rltr-card[data-narrow="1"] .rltr-feed { padding: 6px 10px 10px; gap: 10px; }
.rltr-card[data-narrow="1"] .rltr-zh { font-size: 14.5px; line-height: 1.66; }
.rltr-card[data-narrow="1"] .rltr-foot { display: none; }
.rltr-card[data-narrow="1"] .rltr-title { font-size: 12.5px; }
.rltr-pill {
  position: fixed; z-index: 2140; pointer-events: auto;
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  height: 52px; min-width: 52px; padding: 0 12px; border-radius: 999px;
  background: linear-gradient(135deg, #F59E0B, #F97316); color: #fff;
  border: 2px solid rgba(255,255,255,.55);
  cursor: pointer; font-family: inherit; font-size: 22px; font-weight: 600; line-height: 1;
  box-shadow: 0 10px 26px rgba(0,0,0,.28);
}
.rltr-pill .rltr-dot { background: #fff; }
.rltr-pill .rltr-badge { background: #fff; color: var(--dsw-alias-brand-primary); border-radius: 999px; font-size: 11px; padding: 0 6px; height: 17px; line-height: 17px; font-weight: 700; }
.rltr-card[data-focus="1"][data-narrow="1"] { left: 0; right: 0; top: 0; bottom: 0; max-height: none; border-radius: 0; }
.rltr-card[data-focus="1"] .rltr-foot { display: none; }
`

    function Toggle({ checked, onChange, label }) {
      return h('button', {
        type: 'button',
        className: 'rltr-switch',
        role: 'switch',
        'aria-checked': checked ? 'true' : 'false',
        'aria-label': label,
        onClick: () => onChange(!checked),
      }, h('span', { className: 'rltr-knob' }))
    }

    function Entry({ item }) {
      const [enOpen, setEnOpen] = useState(false)
      return h('div', { className: 'rltr-item' },
        h('div', { className: 'rltr-zh' }, item.zh),
        enOpen ? h('div', { className: 'rltr-en' }, item.en) : null,
        h('div', { className: 'rltr-meta' },
          h('span', null, clock(item.at)),
          h('button', {
            type: 'button',
            className: 'rltr-mini',
            onClick: () => setEnOpen((value) => !value),
          }, enOpen ? '收起原文' : 'EN'),
        ),
      )
    }

    function Panel() {
      const [state, setState] = useState(null)
      const [offline, setOffline] = useState(false)
      const [diag, setDiag] = useState('')
      // 窄窗口（手机浮窗 / 分屏下半边）：卡片改成贴底停靠，按钮永远够得着
      const [narrow, setNarrow] = useState(() => (typeof window === 'undefined' ? false : window.innerWidth < 640))
      // 专注模式：整张卡铺满窗口，只剩译文和三个按钮——在 DSHA 浮窗里就是「独立的翻译器」
      const [focus, setFocus] = useState(false)
      const [open, setOpen] = useState(() => localStorage.getItem(OPEN_KEY) !== '0')
      const [pos, setPos] = useState(() => clampPos(readPos() || defaultPos()))
      const [settingsOpen, setSettingsOpen] = useState(false)
      const [pending, setPending] = useState('')
      const [lastRead, setLastRead] = useState(() => Number(localStorage.getItem(READ_KEY) || 0))
      const feedRef = useRef(null)
      const dragRef = useRef(null)

      const control = useCallback(async (action, extra) => {
        setPending(action)
        try {
          const result = await api('/control', { action, ...(extra || {}) })
          if (result && result.state) setState(result.state)
          if (result && result.error && action !== 'launch-zotero') {
            // 让 Host 的错误原样露出来（例如 Zotero 没装）
            setState((prev) => prev ? { ...prev, lastError: result.error } : prev)
          }
          setOffline(false)
          return result
        } catch (error) {
          setOffline(true)
          return null
        } finally {
          setPending('')
        }
      }, [])

      // 轮询 Host 状态（译文流 + 计数器）。面板开着就快一点，收起时慢一点。
      useEffect(() => {
        let alive = true
        let timer
        const pull = async () => {
          try {
            const next = await api('/state')
            if (!alive) return
            setState(next)
            setOffline(false)
          } catch (error) {
            if (alive) {
              setOffline(true)
              setDiag(String((error && error.message) || error).slice(0, 220))
            }
          }
          if (alive) timer = setTimeout(pull, open ? 1600 : 4000)
        }
        pull()
        return () => { alive = false; clearTimeout(timer) }
      }, [open])

      // 新译文到了就滚到底（用户自己往上翻时不打扰）
      const entries = state?.entries || []
      const entriesKey = entries.length ? entries[entries.length - 1].id : 0
      useEffect(() => {
        const node = feedRef.current
        if (!node || !open) return
        const nearBottom = node.scrollHeight - node.scrollTop - node.clientHeight < 90
        if (nearBottom) node.scrollTop = node.scrollHeight
      }, [entriesKey, open])

      useEffect(() => {
        if (open && state?.seq && state.seq !== lastRead) {
          setLastRead(state.seq)
          localStorage.setItem(READ_KEY, String(state.seq))
        }
      }, [open, state?.seq, lastRead])

      useEffect(() => { localStorage.setItem(OPEN_KEY, open ? '1' : '0') }, [open])
      useEffect(() => { localStorage.setItem(POS_KEY, JSON.stringify(pos)) }, [pos])

      // 窗口尺寸变了（旋转 / 分屏）就把卡片拉回视口内
      useEffect(() => {
        const onResize = () => {
          setPos((current) => clampPos(current))
          setNarrow(window.innerWidth < 640)
        }
        window.addEventListener('resize', onResize)
        return () => window.removeEventListener('resize', onResize)
      }, [])

      // 拖动：标题栏按下 → 跟随指针 → 松手停在视口内
      const onPointerDown = useCallback((event) => {
        if (event.target.closest('button')) return
        if (window.innerWidth < 640 || focus) return
        const startX = event.clientX
        const startY = event.clientY
        const origin = { ...pos }
        dragRef.current = { startX, startY, origin }
        try { event.currentTarget.setPointerCapture(event.pointerId) } catch { /* ignore */ }
      }, [pos])

      const onPointerMove = useCallback((event) => {
        const drag = dragRef.current
        if (!drag) return
        const width = Math.min(404, window.innerWidth - 20)
        const dx = event.clientX - drag.startX
        const dy = event.clientY - drag.startY
        const x = Math.min(Math.max(4, drag.origin.x + dx), Math.max(4, window.innerWidth - width - 4))
        const y = Math.min(Math.max(4, drag.origin.y + dy), Math.max(4, window.innerHeight - 90))
        setPos({ x, y })
      }, [])

      const onPointerUp = useCallback(() => { dragRef.current = null }, [])

      const status = state?.status || 'idle'
      const running = Boolean(state?.config?.running)
      const overlayOn = state?.config?.overlayOn !== false
      const interval = Number(state?.config?.intervalMs || 8000)
      const unread = open || !state?.seq ? 0 : Math.max(0, entries.filter((item) => item.id > lastRead).length)

      const cardStyle = (narrow || focus) ? {} : { left: pos.x + 'px', top: pos.y + 'px' }

      if (!open) {
        return h(React.Fragment, null,
          h('style', null, CSS),
          h('button', {
            type: 'button',
            className: 'rltr-pill',
            style: { right: '14px', bottom: '78px' },
            title: STATUS_TEXT[status] || status,
            onClick: () => setOpen(true),
          },
            '🐱',
            unread > 0 ? h('span', { className: 'rltr-badge' }, unread > 99 ? '99+' : String(unread)) : null,
          ),
        )
      }

      const lastError = state?.lastError
      const overlayHint = state?.overlay?.hint
      const stats = state?.stats || {}
      const lastCapture = state?.lastCaptureAt

      return h(React.Fragment, null,
        h('style', null, CSS),
        h('div', { className: 'rltr-card', style: cardStyle, 'data-narrow': narrow ? '1' : '0', 'data-focus': focus ? '1' : '0' },
          h('div', {
            className: 'rltr-head',
            onPointerDown,
            onPointerMove,
            onPointerUp,
            onPointerCancel: onPointerUp,
          },
            h('span', { className: 'rltr-title' }, '🐱 基米翻译'),
            h('span', { className: 'rltr-status' },
              h('span', { className: 'rltr-dot', 'data-s': status }),
              offline ? '与 Host 断开' : (STATUS_TEXT[status] || status),
            ),
            h('span', { className: 'rltr-spacer' }),
            h('button', {
              type: 'button', className: 'rltr-icon',
              title: focus ? '退出专注模式' : '专注模式（铺满窗口）',
              'aria-label': '专注模式',
              onClick: () => setFocus((value) => !value),
            }, focus ? '⤡' : '⤢'),
            h('button', {
              type: 'button', className: 'rltr-icon', title: '折叠', 'aria-label': '折叠',
              onClick: () => setOpen(false),
            }, '–'),
          ),

          h('div', { className: 'rltr-bar' },
            h('span', { className: 'rltr-label' }, '自动跟读', h(Toggle, {
              checked: running,
              label: '自动跟读',
              onChange: (next) => control(next ? 'start' : 'stop'),
            })),
            h('button', {
              type: 'button', className: 'rltr-btn',
              disabled: Boolean(pending) || Boolean(state?.busy),
              onClick: () => control('now'),
            }, pending === 'now' ? '读取中…' : '译一屏'),
            h('span', { className: 'rltr-spacer' }),
            h('span', { className: 'rltr-label' }, '悬浮条', h(Toggle, {
              checked: overlayOn,
              label: '推送到手机悬浮条',
              onChange: (next) => control('set', { settings: { overlayOn: next } }),
            })),
            h('button', {
              type: 'button', className: 'rltr-icon', title: '设置', 'aria-label': '设置',
              onClick: () => setSettingsOpen((value) => !value),
            }, '⚙'),
          ),

          lastError ? h('div', { className: 'rltr-error' },
            h('span', null, lastError),
            h('button', {
              type: 'button',
              className: 'rltr-mini',
              style: { marginLeft: '8px' },
              onClick: () => control('now'),
            }, '重试'),
          ) : null,
          offline ? h('div', { className: 'rltr-hint' }, '连不上 Host：' + (diag || '正在重试…')) : null,
          !lastError && !offline && overlayHint ? h('div', { className: 'rltr-hint' }, overlayHint) : null,

          h('div', { className: 'rltr-feed', ref: feedRef },
            entries.length === 0
              ? h('div', { className: 'rltr-empty' },
                  '点「译一屏」或打开「自动跟读」。',
                  h('br'),
                  '在 Zotero 里继续读、继续滚——这里只会出现还没有翻译过的段落。',
                )
              : entries.map((item) => h(Entry, { key: item.id, item })),
          ),

          settingsOpen ? h('div', { className: 'rltr-settings' },
            h('div', { className: 'rltr-row' },
              h('span', { className: 'rltr-label' }, '跟读间隔'),
              [5000, 8000, 12000, 20000].map((value) => h('button', {
                key: value,
                type: 'button',
                className: 'rltr-btn',
                'data-on': interval === value ? '1' : '0',
                onClick: () => control('set', { settings: { intervalMs: value } }),
              }, value / 1000 + 's')),
            ),
            h('div', { className: 'rltr-row' },
              h('button', {
                type: 'button', className: 'rltr-btn',
                onClick: () => control('launch-zotero'),
              }, '打开 Zotero'),
              h('button', {
                type: 'button', className: 'rltr-btn',
                onClick: () => control('overlay-test'),
              }, '测试悬浮条'),
              h('span', { className: 'rltr-spacer' }),
              h('button', {
                type: 'button', className: 'rltr-btn',
                onClick: () => control('reset-seen'),
              }, '重置去重'),
              h('button', {
                type: 'button', className: 'rltr-btn',
                onClick: () => control('clear'),
              }, '清空译文'),
            ),
            h('div', { className: 'rltr-label' },
              '截图会裁掉顶部 ' + Math.round((state?.config?.cropTopRatio || 0.035) * 100) + '% 的状态栏；模型 ' + (state?.model?.model || '—'),
            ),
          ) : null,

          h('div', { className: 'rltr-foot' },
            h('span', null, '上次 ', h('b', null, clock(lastCapture))),
            h('span', null, '· 新增 ', h('b', null, String(stats.segments || 0)), ' 段'),
            h('span', null, '· 跳过重复 ', h('b', null, String(stats.duplicates || 0))),
            state?.lastEmptyAt && !lastError ? h('span', null, '· 本屏无新正文') : null,
            h('span', { className: 'rltr-spacer' }),
            state?.overlay?.pending ? h('span', null, '待推 ' + state.overlay.pending) : null,
          ),
        ),
      )
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        ctx.slots.inject('shell.overlay', () => ctx.slots.register({
          name: 'shell.overlay',
          id: 'rl-translator.panel',
          order: 20,
        }, Panel))
      },
    }
  },
})
