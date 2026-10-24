#!/usr/bin/env node
/**
 * KI-2 verification host — a headless Mizar host built from the plugin's own
 * listener and broker, plus a control channel.
 *
 * Why this exists: KI-2 ("a pending question is not rehydrated after
 * reconnect") needs an approval/question to exist on the host at a chosen
 * moment, with a chosen number of clients connected. The real DSH host cannot
 * be driven that way, and the dev profile needs a model behind it. The plugin's
 * listener and interaction broker are transport-agnostic by design, so this
 * composes exactly those two production pieces — the same fail-close wiring
 * (`onClientCount -> broker.setClientCount`) as the real host — and adds:
 *
 *   - two fake sessions with a small transcript (renders in the app),
 *   - a control HTTP server (127.0.0.1 only) to trigger approvals/questions,
 *     to hold a second client open (keeps the count > 0 while the app is
 *     away), and to read the broker's ground truth,
 *   - a settle log: every interaction records how it ended and when.
 *
 * Unknown methods (e.g. `session.refresh`) are answered NOT_IMPLEMENTED by the
 * listener itself, same as the real plugin, so the app's three-read fallback is
 * the path that runs.
 *
 * Usage:
 *   node scripts/ki2-host.mjs            # KI2_PORT=19444 KI2_CTL=19445
 *
 * Control endpoints (POST unless noted):
 *   GET  /ctl/state                      # clients, pending, settle log
 *   POST /ctl/code                       # -> {display} a fresh pairing code
 *   POST /ctl/approval  {toolName?, reason?, sessionId?}
 *   POST /ctl/question  {sessionId?, questions:[{id,question,options:[{label}],multiSelect}]}
 *   POST /ctl/hold                       # open the second client
 *   POST /ctl/release                    # close it
 *   POST /ctl/answer   {kind, id, choice}  # answer as if the desktop GUI did
 *   POST /ctl/turn     {outcome?}          # append a finished turn: completed | aborted | error
 */
import { mkdirSync } from 'node:fs'
import { createServer } from 'node:http'
import { join, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'
import process from 'node:process'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = join(HERE, '..', 'plugin')
// Bare 'ws' would not resolve from scripts/ (no node_modules above it), so it is
// required through the plugin's own dependency tree.
const require = createRequire(join(PLUGIN, 'package.json'))
const WebSocket = require('ws')

// import() on Windows needs a file:// URL, not a bare `E:\...` path.
const pluginModule = (relative) => pathToFileURL(join(PLUGIN, relative)).href
const { MizarStore } = await import(pluginModule('lib/auth/store.js'))
const { MizarListener } = await import(pluginModule('lib/server/listener.js'))
const { InteractionBroker } = await import(pluginModule('lib/bridge/interactions.js'))
const { MethodError } = await import(pluginModule('lib/bridge/method-error.js'))

const PORT = Number(process.env['KI2_PORT'] ?? 19444)
const CTL_PORT = Number(process.env['KI2_CTL'] ?? 19445)
const STATE_DIR = process.env['KI2_STATE'] ?? join(HERE, '..', '.devstate', 'ki2-host-state')

/** Timestamped line to stdout; the whole run is read back as a timeline. */
function log(what) {
  const now = new Date()
  const t = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}.${String(now.getMilliseconds()).padStart(3, '0')}`
  console.log(`${t} ${what}`)
}

// ── fake sessions ──────────────────────────────────────────────────────────

const T0 = Date.now() - 6 * 60_000
const records = [
  // The order and the shapes the real host writes: the operator's message first
  // (it carries no turn number -- the turn does not exist yet), then the turn that
  // claims it, then a message that names its own turn.
  {
    type: 'event',
    event: {
      type: 'user/message',
      seq: 2,
      time: T0,
      sessionId: 'session-ki2-alpha',
      data: {
        content: [{ type: 'text', text: '请在这台机器上验证 KI-2：重连之后待办卡片能不能回来。' }],
        source: { kind: 'user', rpcId: 'ki2-seed-1' },
        role: 'user',
        id: 'ki2-seed-user-1',
      },
      surfaceOp: 'append',
    },
  },
  { type: 'event', event: { type: 'turn/start', seq: 3, time: T0 + 100, sessionId: 'session-ki2-alpha', data: { turn: 1 } } },
  { type: 'event', event: { type: 'step/start', seq: 4, time: T0 + 800, sessionId: 'session-ki2-alpha', data: { turn: 1, step: 1 } } },
  {
    type: 'event',
    event: {
      type: 'assistant/message',
      seq: 5,
      time: T0 + 1500,
      sessionId: 'session-ki2-alpha',
      data: {
        turn: 1,
        step: 1,
        content: [{ type: 'text', text: '好的。卡片是否出现以截图和宿主结算日志为准。' }],
        role: 'assistant',
        id: 'ki2-seed-assistant-1',
      },
      surfaceOp: 'append',
    },
  },
  {
    type: 'event',
    event: {
      type: 'turn/end',
      seq: 6,
      time: T0 + 1600,
      sessionId: 'session-ki2-alpha',
      data: { turn: 1, reason: { kind: 'completed' } },
    },
  },
]

const sessions = [
  {
    sessionId: 'session-ki2-alpha',
    updatedAt: T0 + 1600,
    running: false,
    blank: false,
    agentAvailable: true,
    cwd: 'E:\\Projects\\DSH-Mizar-Android',
    parentSessionId: null,
    origin: null,
  },
  {
    sessionId: 'session-ki2-beta',
    updatedAt: T0 + 900,
    running: false,
    blank: true,
    agentAvailable: true,
    cwd: 'E:\\Projects\\DSH-Mizar-Android',
    parentSessionId: null,
    origin: null,
  },
  // A delegated child. Its log is only readable through its parent -- the host
  // refuses `{kind: "session"}` for it before touching the log -- so this row is
  // what makes the "parentSessionId travels with the page request" rule testable
  // without a real DSH.
  {
    sessionId: 'session-ki2-child',
    updatedAt: T0 + 1200,
    running: false,
    blank: false,
    agentAvailable: true,
    parentSessionId: 'session-ki2-alpha',
    origin: 'subagent',
  },
]

let nextSeq = 7

/** The last `session.messages` request, so a test can assert what travelled. */
let lastPageRequest = null

/** Turns appended through the control endpoint, so each one gets its own number. */
let turnCounter = 1

function pageFor(sessionId, { maxMessages = 200, afterSeq = null, beforeSeq = null }) {
  const own = records.filter((record) => record.event.sessionId === sessionId)
  const filtered = own.filter((record) => {
    const seq = record.event.seq
    if (afterSeq !== null && afterSeq !== undefined && seq <= afterSeq) return false
    if (beforeSeq !== null && beforeSeq !== undefined && seq >= beforeSeq) return false
    return true
  })
  const tail = maxMessages >= filtered.length ? filtered : filtered.slice(-maxMessages)
  const asOfSeq = own.reduce((max, record) => Math.max(max, record.event.seq), 0)
  return {
    sessionId,
    asOfSeq,
    records: tail,
    hasMore: tail.length < filtered.length,
  }
}

// ── the broker, wired exactly like the real host ───────────────────────────

const broker = new InteractionBroker()

/** Every interaction's ending: the ground truth the app's screen is judged against. */
const settleLog = []
const handles = new Map()

function trackApproval(handle, origin) {
  handles.set(handle.requestId, { kind: 'approval', handle, origin })
  handle.outcome.then((outcome) => {
    settleLog.push({ at: Date.now(), kind: 'approval', id: handle.requestId, origin, outcome })
    log(`SETTLED approval ${handle.requestId} (${origin}) -> ${outcome}`)
  })
}

function trackQuestion(handle, origin) {
  handles.set(handle.callId, { kind: 'question', handle, origin })
  handle.answers.then((answers) => {
    settleLog.push({
      at: Date.now(),
      kind: 'question',
      id: handle.callId,
      origin,
      outcome: answers.length === 0 ? 'empty' : `${answers.length} answer(s)`,
    })
    log(`SETTLED question ${handle.callId} (${origin}) -> ${answers.length} answer(s)`)
  })
}

// ── the listener ───────────────────────────────────────────────────────────

mkdirSync(STATE_DIR, { recursive: true })
const store = await MizarStore.open(STATE_DIR)

const listener = await MizarListener.start({
  plan: { addresses: ['127.0.0.1'], mode: 'addresses', fellBack: false, reason: 'ki2-host' },
  port: PORT,
  portFallback: false,
  store,
  pairingTtlSeconds: 300,
  maxAttemptsPerMinute: 60,
  heartbeatMs: 15_000,
  pongTimeoutMs: 20_000,
  log: (message) => log(`listener: ${message}`),
  onSession: (session) => log(`client connected: ${session.deviceName} (${session.deviceId}) from ${session.remote}`),
  // The production fail-close wiring: dropping to zero clients settles every
  // pending approval `unavailable` and every pending question empty.
  onClientCount: (count) => {
    log(`client count -> ${count}`)
    broker.setClientCount(count)
  },
  methods: {
    'session.list': async () => ({ items: sessions }),
    'workspace.list': async () => ({
      items: [{ workspaceId: 'ws-ki2', path: 'E:\\Projects\\DSH-Mizar-Android', title: 'DSH-Mizar-Android' }],
    }),
    'session.messages': async (payload) => {
      const record = payload ?? {}
      const sessionId = record['sessionId']
      const parentSessionId = record['parentSessionId']
      lastPageRequest = {
        sessionId,
        parentSessionId: typeof parentSessionId === 'string' ? parentSessionId : null,
      }
      // The refusal the real host gives a delegated child addressed as a session.
      // Reproduced here so a page request that forgets the parent fails loudly in
      // this harness instead of quietly working.
      const row = sessions.find((candidate) => candidate.sessionId === sessionId)
      if (row?.origin === 'subagent' && typeof parentSessionId !== 'string') {
        throw new MethodError(
          'REMOTE_ERROR',
          'session/agent-busy: subagent Sessions require their durable parent address',
        )
      }
      // `full: true` with a `turn` is the client asking for one turn's whole log --
      // the tool calls a summary page drops. The harness keeps raw records, so the
      // filter is the whole implementation.
      if (record['full'] === true && typeof record['turn'] === 'number') {
        const only = records.filter(
          (candidate) => candidate.event.sessionId === sessionId &&
            (candidate.event.data ?? {})['turn'] === record['turn'],
        )
        return { sessionId, asOfSeq: only.at(-1)?.event.seq ?? null, records: only, hasMore: false }
      }
      const page = pageFor(sessionId, {
        maxMessages: typeof record['maxMessages'] === 'number' ? record['maxMessages'] : 200,
        afterSeq: typeof record['afterSeq'] === 'number' ? record['afterSeq'] : null,
        beforeSeq: typeof record['beforeSeq'] === 'number' ? record['beforeSeq'] : null,
      })
      // Every page carries the occupancy since plugin 0.8.9; the app folds it into
      // the composer's figure without asking for session.stats.
      log(`session.messages ${sessionId} parent=${lastPageRequest.parentSessionId ?? '-'} +context`)
      return {
        ...page,
        context: {
          window: 128000,
          pressureTokens: 42000,
          projectedTokens: 45000,
          percent: 35,
          breakdown: { systemTokens: 1000, toolsTokens: 2000, messageTokens: 39000 },
        },
      }
    },
    'session.stats': async (payload) => {
      const id = payload?.['sessionId'] ?? ''
      return {
        sessionId: id,
        asOfSeq: records.reduce((max, record) => Math.max(max, record.event.seq), 0),
        // The child has no title in its stats frame on purpose: that is what makes
        // the app fall through to reading the child's *page*, which is the path
        // that has to name the parent.
        stats: {
          title:
            id === 'session-ki2-beta'
              ? '空白会话'
              : id === 'session-ki2-child'
                ? undefined
                : 'KI-2 验证会话',
          permissionPreset: 'default',
        },
      }
    },
    'interactions.pending': async () => ({
      approvals: broker.listApprovals(),
      questions: broker.listQuestions(),
    }),
    'approval.answer': async (payload) => {
      const requestId = payload?.['requestId']
      const outcome = payload?.['outcome']
      if (typeof requestId !== 'string' || (outcome !== 'allowed-once' && outcome !== 'rejected')) {
        throw new MethodError(
          'MALFORMED_FRAME',
          'approval.answer needs {requestId, outcome}',
        )
      }
      const accepted = broker.answerApproval(requestId, outcome)
      log(`approval.answer ${requestId} -> ${outcome} (accepted=${accepted})`)
      return { accepted }
    },
    'question.answer': async (payload) => {
      const callId = payload?.['callId']
      const answers = Array.isArray(payload?.['answers']) ? payload['answers'] : []
      const accepted = broker.answerQuestion(callId, answers)
      log(`question.answer ${callId} (${answers.length} answer(s), accepted=${accepted})`)
      return { accepted }
    },
    'push.register': async () => ({ registered: true }),
    'push.presence': async (payload) => {
      log(`presence foreground=${payload?.['foreground']}`)
      return { accepted: true }
    },
    'session.prompt': async (payload) => {
      const text = typeof payload?.['text'] === 'string' ? payload['text'] : ''
      const id = payload?.['requestId'] ?? ''
      records.push({
        type: 'event',
        event: {
          type: 'user/message',
          seq: (nextSeq += 1),
          time: Date.now(),
          sessionId: payload?.['sessionId'] ?? 'session-ki2-alpha',
          data: {
            content: [{ type: 'text', text }],
            source: { kind: 'user', rpcId: id },
            role: 'user',
            id: `ki2-${id}`,
          },
          surfaceOp: 'append',
        },
      })
      log(`prompt recorded (${text.length} chars)`)
      return { accepted: true, requestId: id }
    },
    'session.cancel': async () => ({ accepted: true }),
  },
})

broker.setSender((frame) => listener.broadcast(frame))
log(`headless host listening on ${listener.authorities.join(', ')} (ws path /mizar/v1/ws)`)

// ── the hold client: a second authenticated device ─────────────────────────

let holdSocket = null

async function pairHoldDevice() {
  const { display } = await listener.issuePairingCode()
  const response = await fetch(`http://127.0.0.1:${listener.port}/mizar/v1/pair`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: display, deviceName: 'ki2-hold' }),
  })
  if (!response.ok) throw new Error(`hold pairing failed: ${response.status}`)
  return (await response.json())
}

async function openHold() {
  if (holdSocket !== null) return { held: true, note: 'already held' }
  const paired = await pairHoldDevice()
  holdSocket = new WebSocket(`ws://127.0.0.1:${listener.port}/mizar/v1/ws`)
  await new Promise((resolve, reject) => {
    holdSocket.once('open', resolve)
    holdSocket.once('error', reject)
  })
  holdSocket.send(JSON.stringify({ v: 1, type: 'hello', id: 'hold-1', token: paired.deviceToken, appVersion: 'ki2-host' }))
  holdSocket.on('message', (data) => {
    const frame = JSON.parse(data.toString('utf8'))
    if (frame.type === 'welcome') log(`hold client authenticated (${frame.hostName})`)
  })
  holdSocket.on('close', () => {
    log('hold client closed')
    holdSocket = null
  })
  return { held: true, note: `paired ${paired.deviceId}` }
}

function releaseHold() {
  if (holdSocket === null) return { held: false, note: 'not held' }
  holdSocket.close(1000, 'released')
  holdSocket = null
  return { held: false, note: 'released' }
}

// ── the control server ─────────────────────────────────────────────────────

const ctl = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://127.0.0.1:${CTL_PORT}`)
  const reply = (status, body) => {
    response.writeHead(status, { 'content-type': 'application/json' })
    response.end(JSON.stringify(body))
  }
  if (request.socket.remoteAddress !== '127.0.0.1' && request.socket.remoteAddress !== '::1') {
    return reply(403, { error: 'localhost only' })
  }
  try {
    if (request.method === 'GET' && url.pathname === '/ctl/state') {
      return reply(200, {
        port: listener.port,
        clients: listener.clientCount,
        hold: holdSocket !== null,
        approvals: broker.listApprovals(),
        questions: broker.listQuestions(),
        settleLog,
        // What the last page read asked for: the parent a delegated child has to
        // name, and whether this build still sends it.
        lastPageRequest,
      })
    }
    if (request.method !== 'POST') return reply(405, { error: 'POST or GET /ctl/state' })
    const body = await new Promise((resolve) => {
      let text = ''
      request.on('data', (chunk) => (text += chunk))
      request.on('end', () => resolve(text === '' ? {} : JSON.parse(text)))
    })
    switch (url.pathname) {
      case '/ctl/turn': {
        // A turn that ended the way the caller says. `aborted` is what a Stop
        // produces, and it is the one the transcript has to draw as unfinished --
        // the summary row reads the reason, so the reason is what this fabricates.
        const outcome = body['outcome'] ?? 'aborted'
        const turn = (turnCounter += 1)
        const base = Date.now()
        const push = (event) => {
          records.push({ type: 'event', event: { seq: (nextSeq += 1), sessionId: 'session-ki2-alpha', ...event } })
        }
        // The order the real host writes: the operator's message first (it carries
        // no turn number -- the turn does not exist yet), then the turn that
        // claims it.
        push({
          type: 'user/message',
          time: base,
          data: {
            content: [{ type: 'text', text: `第 ${turn} 轮：跑一个会被中断的长任务。` }],
            source: { kind: 'user' },
            role: 'user',
            id: `ki2-user-${turn}`,
          },
          surfaceOp: 'append',
        })
        push({ type: 'turn/start', time: base + 100, data: { turn } })
        // `aborted-silent`: stopped before the model said anything, which is the
        // case that used to lose its reason and draw as 已完成.
        if (outcome === 'aborted-silent') {
          push({ type: 'turn/end', time: base + 500, data: { turn, reason: { kind: 'aborted', reason: { kind: 'user' } } } })
          log(`turn ${turn} appended: aborted (silent)`)
          return reply(200, { turn, outcome })
        }
        push({ type: 'step/start', time: base + 600, data: { turn, step: 1 } })
        push({
          type: 'tool/call',
          time: base + 900,
          data: { turn, step: 1, callId: `ki2-call-${turn}`, name: 'bash', arguments: '{"command":"ls -la ~/projects"}' },
        })
        push({
          type: 'tool/result',
          time: base + 1200,
          data: {
            turn,
            step: 1,
            message: {
              role: 'tool',
              toolCallId: `ki2-call-${turn}`,
              content: [{ type: 'text', text: 'README.md\nplugin/\nsrc/' }],
              isError: false,
            },
          },
        })
        push({
          type: 'assistant/message',
          time: base + 1500,
          data: {
            turn,
            step: 1,
            content: [{ type: 'text', text: '先做第一步，然后是第二步……' }],
            role: 'assistant',
            id: `ki2-assistant-${turn}`,
          },
          surfaceOp: 'append',
        })
        const reason =
          outcome === 'aborted'
            ? { kind: 'aborted', reason: { kind: 'user' } }
            : outcome === 'error'
              ? { kind: 'error', error: { message: 'boom', code: 'UNKNOWN' } }
              : { kind: 'completed' }
        push({ type: 'turn/end', time: base + 2000, data: { turn, reason } })
        log(`turn ${turn} appended: ${outcome}`)
        return reply(200, { turn, outcome })
      }
      case '/ctl/code': {
        const { display, expiresAt } = await listener.issuePairingCode()
        log(`pairing code issued: ${display}`)
        return reply(200, { display, expiresAt })
      }
      case '/ctl/approval': {
        const handle = broker.beginApproval({
          sessionId: body.sessionId ?? 'session-ki2-alpha',
          toolName: body.toolName ?? 'Bash',
          reason: body.reason ?? 'rm -rf build/ && git clean -fd  (KI-2 验证用)',
          displayReason: body.displayReason ?? { en: 'Clean the build directory (KI-2)', zh: '清理构建目录（KI-2 验证）' },
        })
        trackApproval(handle, 'ctl')
        log(`approval begun: ${handle.requestId}`)
        return reply(200, { requestId: handle.requestId })
      }
      case '/ctl/question': {
        const questions = (body.questions ?? [
          {
            id: 'q1',
            question: 'KI-2 验证：选择一个继续',
            options: [{ label: '继续' }, { label: '停下' }],
            multiSelect: false,
          },
        ]).map((question, index) => ({
          id: question.id ?? `q${index + 1}`,
          question: question.question ?? 'KI-2 验证问题',
          options: (question.options ?? [{ label: '继续' }, { label: '停下' }]).map((option) =>
            typeof option === 'string' ? { label: option } : option,
          ),
          multiSelect: question.multiSelect === true,
        }))
        const handle = broker.beginQuestions({
          sessionId: body.sessionId ?? 'session-ki2-alpha',
          callId: body.callId ?? `ki2-q-${Date.now().toString(36)}`,
          questions,
        })
        trackQuestion(handle, 'ctl')
        log(`question begun: ${handle.callId} (${questions.length} question(s))`)
        return reply(200, { callId: handle.callId })
      }
      case '/ctl/hold':
        return reply(200, await openHold())
      case '/ctl/release':
        return reply(200, releaseHold())
      case '/ctl/answer': {
        const entry = handles.get(body.id)
        if (entry === undefined) return reply(404, { error: `no handle ${body.id}` })
        if (entry.kind === 'approval') {
          entry.handle.abandon(body.choice ?? 'rejected')
        } else {
          entry.handle.abandon()
        }
        log(`ctl answered ${body.id} (${body.choice ?? 'abandoned'})`)
        return reply(200, { answered: body.id })
      }
      default:
        return reply(404, { error: `unknown ${url.pathname}` })
    }
  } catch (error) {
    log(`control error: ${error?.stack ?? error}`)
    return reply(500, { error: String(error?.message ?? error) })
  }
})

await new Promise((resolve) => ctl.listen(CTL_PORT, '127.0.0.1', resolve))
log(`control server on http://127.0.0.1:${CTL_PORT} (GET /ctl/state, POST code|approval|question|hold|release|answer)`)

const shutdown = async () => {
  log('shutting down')
  releaseHold()
  broker.dispose()
  await listener.stop()
  ctl.close()
  process.exit(0)
}
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
