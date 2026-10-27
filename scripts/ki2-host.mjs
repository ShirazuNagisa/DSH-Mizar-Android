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
 *   POST /ctl/model    {provider, model, effort?}  # change the model as the desktop would
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

/** The host's own chunk size, which the client must slice to exactly. */
const UPLOAD_CHUNK_BYTES = 131_072

/** Live uploads, keyed by the id `upload.begin` handed out. */
const uploads = new Map()

/** One counter for both ids, so a log line names one thing. */
let nextUploadId = 0

/** What `session.model.set` was last told, per session. */
const chosenModels = new Map()

/** What `session.permission.set` was last told, per session. */
const chosenPermissions = new Map()

/** The one provider the harness offers, with one reasoning model and one plain. */
const CATALOG = {
  default: { provider: 'deepseek', model: 'deepseek-v4', reasoningEffort: 'high' },
  providers: [
    {
      id: 'deepseek',
      name: 'DeepSeek',
      models: [
        {
          id: 'deepseek-v4',
          name: 'DeepSeek V4',
          efforts: [{ id: 'low', name: 'Low' }, { id: 'high', name: 'High' }],
          defaultEffort: 'high',
        },
        { id: 'deepseek-lite', name: 'DeepSeek Lite' },
      ],
    },
  ],
  failures: [],
}

const PERMISSIONS = {
  options: [
    { value: 'read-only', name: 'Read only' },
    { value: 'default', name: 'Default' },
    { value: 'danger-full-access', name: 'Full access' },
    { value: 'custom', name: 'Custom' },
  ],
  defaults: [],
  defaultPreset: 'default',
}

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
  // ── attachments ─────────────────────────────────────────────────────────
  //
  // The four `upload.*` methods, implemented to the same rules the plugin's own
  // store enforces, because the point of this harness is to catch a client that
  // has the protocol slightly wrong: index-based offsets, canonical base64, and
  // every chunk but the last exactly `chunkBytes`.
  methods: {
    'session.list': async () => ({ items: sessions }),

    // The host's own DeepSeek wallets. Two of them, plus a bonus one, so the
    // screen's sections and its figures both get exercised.
    // The preview's only source of bytes. A tiny Markdown file and a 1x1 PNG, so
    // both preview states can be walked without a real workspace.
    'file.read': async (payload) => {
      const record = payload ?? {}
      const path = typeof record['path'] === 'string' ? record['path'] : ''
      const offset = typeof record['offset'] === 'number' ? record['offset'] : 0
      const length = typeof record['length'] === 'number' ? record['length'] : 0
      if (path === '') throw new MethodError('MALFORMED_FRAME', 'file.read: payload."path" is required')
      if (offset < 0 || length <= 0) throw new MethodError('MALFORMED_FRAME', 'file.read: bad offset or length')
      const body = path.endsWith('.pdf')
        ? Buffer.from('JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvRm9udCAvU3VidHlwZSAvVHlwZTEgL0Jhc2VGb250IC9IZWx2ZXRpY2EgPj4KZW5kb2JqCjIgMCBvYmoKPDwgL0xlbmd0aCA0OCA+PgpzdHJlYW0KQlQgL0YxIDM2IFRmIDcyIDcyMCBUZCAoTWl6YXIgZGVsaXZlcmFibGUpIFRqIEVUCmVuZHN0cmVhbQplbmRvYmoKMyAwIG9iago8PCAvVHlwZSAvUGFnZSAvUGFyZW50IDYgMCBSIC9NZWRpYUJveCBbMCAwIDU5NSA4NDJdIC9SZXNvdXJjZXMgPDwgL0ZvbnQgPDwgL0YxIDEgMCBSID4+ID4+IC9Db250ZW50cyAyIDAgUiA+PgplbmRvYmoKNCAwIG9iago8PCAvTGVuZ3RoIDQyID4+CnN0cmVhbQpCVCAvRjEgMzYgVGYgNzIgNzIwIFRkIChTZWNvbmQgcGFnZSkgVGogRVQKZW5kc3RyZWFtCmVuZG9iago1IDAgb2JqCjw8IC9UeXBlIC9QYWdlIC9QYXJlbnQgNiAwIFIgL01lZGlhQm94IFswIDAgNTk1IDg0Ml0gL1Jlc291cmNlcyA8PCAvRm9udCA8PCAvRjEgMSAwIFIgPj4gPj4gL0NvbnRlbnRzIDQgMCBSID4+CmVuZG9iago2IDAgb2JqCjw8IC9UeXBlIC9QYWdlcyAvQ291bnQgMiAvS2lkcyBbMyAwIFIgNSAwIFJdID4+CmVuZG9iago3IDAgb2JqCjw8IC9UeXBlIC9DYXRhbG9nIC9QYWdlcyA2IDAgUiA+PgplbmRvYmoKeHJlZgowIDgKMDAwMDAwMDAwMCA2NTUzNSBmIAowMDAwMDAwMDA5IDAwMDAwIG4gCjAwMDAwMDAwNzkgMDAwMDAgbiAKMDAwMDAwMDE3NyAwMDAwMCBuIAowMDAwMDAwMzAzIDAwMDAwIG4gCjAwMDAwMDAzOTUgMDAwMDAgbiAKMDAwMDAwMDUyMSAwMDAwMCBuIAowMDAwMDAwNTg0IDAwMDAwIG4gCnRyYWlsZXIKPDwgL1NpemUgOCAvUm9vdCA3IDAgUiA+PgpzdGFydHhyZWYKNjMzCiUlRU9GCg==', 'base64')
        : path.endsWith('.png')
        ? Buffer.from('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', 'base64')
        : Buffer.from(`# ${path}\n\n这是一份示例交付文件。\n\n- 第一行\n- 第二行\n`, 'utf8')
      const slice = body.subarray(offset, Math.min(offset + length, body.length))
      return {
        offset,
        length: slice.length,
        byteSize: body.length,
        data: slice.toString('base64'),
        eof: offset + slice.length >= body.length,
      }
    },

    'account.balance': async (payload) => {
      const client = (payload ?? {})['client'] ?? {}
      if (typeof client['version'] !== 'string' || typeof client['locale'] !== 'string'
        || typeof client['timezoneOffsetSeconds'] !== 'number') {
        throw new MethodError('MALFORMED_FRAME', 'account.balance: invalid client metadata')
      }
      return {
        status: 'ready',
        wallets: [{ currency: 'CNY', balance: '48.20' }, { currency: 'USD', balance: '6.75' }],
        bonusWallets: [{ currency: 'CNY', balance: '5.00' }],
        topUpURL: 'https://platform.deepseek.com/top_up',
      }
    },
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
          // Read back what was set, the way the real host does: the client is
          // supposed to trust this projection over its own optimistic copy.
          permissionPreset: chosenPermissions.get(id) ?? 'default',
          ...(chosenModels.has(id) ? { model: { lastUsed: chosenModels.get(id), next: chosenModels.get(id) } } : {}),
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
    'session.create': async (payload) => ({
      sessionId: typeof payload?.['sessionId'] === 'string' && payload['sessionId'] !== ''
        ? payload['sessionId']
        : 'session-ki2-alpha',
      agentPreset: 'default',
    }),
    'model.catalog': async () => CATALOG,
    'session.model.set': async (payload) => {
      const sessionId = payload?.['sessionId']
      const provider = payload?.['provider']
      const model = payload?.['model']
      if (typeof sessionId !== 'string' || typeof provider !== 'string' || typeof model !== 'string') {
        throw new MethodError('MALFORMED_FRAME', 'session.model.set needs sessionId, provider and model')
      }
      const option = CATALOG.providers.find((group) => group.id === provider)?.models.find((item) => item.id === model)
      if (option === undefined) throw new MethodError('MODEL_UNAVAILABLE', `no model ${provider}/${model}`)
      const efforts = option.efforts ?? []
      const effort = payload?.['reasoningEffort']
      const selected = {
        provider,
        model,
        ...(efforts.length === 0 ? {} : { reasoningEffort: effort ?? option.defaultEffort ?? efforts[0].id }),
      }
      chosenModels.set(sessionId, selected)
      log(`session.model.set ${provider}/${model} effort=${selected.reasoningEffort ?? '-'}`)
      return { sessionId, selected }
    },
    'permissions.catalog': async () => PERMISSIONS,
    'session.permission.set': async (payload) => {
      const sessionId = payload?.['sessionId']
      const preset = payload?.['preset']
      if (typeof sessionId !== 'string' || typeof preset !== 'string') {
        throw new MethodError('MALFORMED_FRAME', 'session.permission.set needs sessionId and preset')
      }
      if (!PERMISSIONS.options.some((option) => option.value === preset)) {
        throw new MethodError('REMOTE_ERROR', `unknown preset "${preset}"`)
      }
      chosenPermissions.set(sessionId, preset)
      log(`session.permission.set ${sessionId} -> ${preset}`)
      // The client reads `kind == 'success'`; a bare boolean is a missing field.
      return { commandResult: { kind: 'success', text: `permission set to ${preset}` } }
    },

    'upload.capabilities': async () => ({
      chunkBytes: UPLOAD_CHUNK_BYTES,
      imageBytes: 10 * 1024 * 1024,
      fileBytes: 32 * 1024 * 1024,
      promptBytes: 64 * 1024 * 1024,
      attachments: 8,
      images: 4,
      expiresSeconds: 1800,
      imageMediaTypes: ['image/png', 'image/jpeg', 'image/webp', 'image/gif'],
      sha256: true,
    }),
    'upload.begin': async (payload, context) => {
      const sessionId = payload?.['sessionId']
      const kind = payload?.['kind']
      const name = payload?.['name']
      const mediaType = payload?.['mediaType']
      const byteSize = payload?.['byteSize']
      if (typeof sessionId !== 'string' || sessionId === '') throw new MethodError('MALFORMED_FRAME', 'upload.begin needs sessionId')
      if (kind !== 'image' && kind !== 'file') throw new MethodError('MALFORMED_FRAME', 'upload.begin needs kind image|file')
      if (typeof name !== 'string' || name === '') throw new MethodError('MALFORMED_FRAME', 'upload.begin needs name')
      if (typeof mediaType !== 'string' || mediaType === '') throw new MethodError('MALFORMED_FRAME', 'upload.begin needs mediaType')
      if (!Number.isInteger(byteSize) || byteSize <= 0) throw new MethodError('MALFORMED_FRAME', 'upload.begin needs a positive byteSize')
      const limit = kind === 'image' ? 10 * 1024 * 1024 : 32 * 1024 * 1024
      if (byteSize > limit) throw new MethodError('UPLOAD_TOO_LARGE', `upload.begin: ${byteSize} exceeds ${limit}`)
      const uploadId = `up-${(nextUploadId += 1)}`
      uploads.set(uploadId, {
        uploadId, deviceId: context?.deviceId ?? 'unknown', sessionId, kind, name, mediaType, byteSize,
        received: 0, chunks: 0, data: Buffer.alloc(0),
      })
      log(`upload.begin ${uploadId} ${kind} ${name} ${byteSize} bytes`)
      return { uploadId, chunkBytes: UPLOAD_CHUNK_BYTES }
    },
    'upload.chunk': async (payload) => {
      const upload = uploads.get(payload?.['uploadId'])
      if (upload === undefined) throw new MethodError('UPLOAD_UNKNOWN', 'upload.chunk: no such upload')
      const index = payload?.['index']
      const data = payload?.['data']
      if (!Number.isInteger(index) || index < 0) throw new MethodError('MALFORMED_FRAME', 'upload.chunk needs a non-negative index')
      if (typeof data !== 'string' || data === '') throw new MethodError('MALFORMED_FRAME', 'upload.chunk needs data')
      if (!/^[A-Za-z0-9+/]+={0,2}$/.test(data)) throw new MethodError('UPLOAD_BAD_CHUNK', 'upload.chunk: not base64')
      const bytes = Buffer.from(data, 'base64')
      // The round trip is the canonical check: Node decodes "AAA" happily.
      if (bytes.toString('base64') !== data) throw new MethodError('UPLOAD_BAD_CHUNK', 'upload.chunk: not canonical base64')
      if (bytes.length > UPLOAD_CHUNK_BYTES) throw new MethodError('UPLOAD_TOO_LARGE', 'upload.chunk: chunk too large')
      if (index > upload.chunks) throw new MethodError('UPLOAD_BAD_CHUNK', `upload.chunk: ${index} arrived before ${upload.chunks}`)
      if (index < upload.chunks) {
        // A replay: the bytes must match what is already held for that index.
        const start = index * UPLOAD_CHUNK_BYTES
        const held = upload.data.subarray(start, start + bytes.length)
        if (!held.equals(bytes)) throw new MethodError('UPLOAD_BAD_CHUNK', 'upload.chunk: replay differs')
        return { received: index + 1 }
      }
      if (bytes.length !== UPLOAD_CHUNK_BYTES && upload.received + bytes.length !== upload.byteSize) {
        throw new MethodError('UPLOAD_BAD_CHUNK', `upload.chunk: all but the last must be ${UPLOAD_CHUNK_BYTES} bytes`)
      }
      upload.data = Buffer.concat([upload.data, bytes])
      upload.received += bytes.length
      upload.chunks += 1
      return { received: upload.chunks }
    },
    'upload.finish': async (payload) => {
      const upload = uploads.get(payload?.['uploadId'])
      if (upload === undefined) throw new MethodError('UPLOAD_UNKNOWN', 'upload.finish: no such upload')
      if (upload.received !== upload.byteSize) {
        throw new MethodError('UPLOAD_INCOMPLETE', `upload.finish: ${upload.received} of ${upload.byteSize} bytes`)
      }
      if (upload.attachmentId === undefined) upload.attachmentId = `att-${(nextUploadId += 1)}`
      log(`upload.finish ${upload.attachmentId} ${upload.name} ${upload.received} bytes`)
      return {
        attachmentId: upload.attachmentId,
        kind: upload.kind,
        name: upload.name,
        mediaType: upload.mediaType,
        byteSize: upload.received,
      }
    },
    'upload.abort': async (payload) => {
      uploads.delete(payload?.['uploadId'])
      return { aborted: true }
    },

    'session.prompt': async (payload) => {
      const text = typeof payload?.['text'] === 'string' ? payload['text'] : ''
      const id = payload?.['requestId'] ?? ''
      const ids = Array.isArray(payload?.['attachmentIds']) ? payload['attachmentIds'] : []
      // What the host stores for an attachment: a reference, never the bytes. The
      // app reads the same shape back out of history.
      const parts = ids.map((attachmentId) => {
        const upload = [...uploads.values()].find((u) => u.attachmentId === attachmentId)
        if (upload === undefined) throw new MethodError('ATTACHMENT_UNKNOWN', `session.prompt: no attachment "${attachmentId}"`)
        return {
          type: upload.kind,
          attachment: {
            attachmentId,
            name: upload.name,
            bytes: upload.received,
            mediaType: upload.mediaType,
          },
        }
      })
      if (text === '' && parts.length === 0) throw new MethodError('MALFORMED_FRAME', 'session.prompt needs text or attachments')
      records.push({
        type: 'event',
        event: {
          type: 'user/message',
          seq: (nextSeq += 1),
          time: Date.now(),
          sessionId: payload?.['sessionId'] ?? 'session-ki2-alpha',
          data: {
            content: [...(text === '' ? [] : [{ type: 'text', text }]), ...parts],
            source: { kind: 'user', rpcId: id },
            role: 'user',
            id: `ki2-${id}`,
          },
          surfaceOp: 'append',
        },
      })
      log(`prompt recorded (${text.length} chars, ${parts.length} attachment(s))`)
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
        // The host attaches the files a turn declared through `present`; a summary
        // page carries no tool traffic, so this field is the only route to a card.
        const delivered =
          outcome === 'completed'
            ? {
                mizarDeliverables: [
                  { path: 'reports/demo.md', description: '示例交付：一份 Markdown 报告' },
                  { path: 'reports/chart.png', description: '示例交付：一张图' },
                  { path: 'reports/q3.pdf', description: '示例交付：一份两页 PDF' },
                ],
              }
            : {}
        push({ type: 'turn/end', time: base + 2000, data: { turn, reason, ...delivered } })
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
      case '/ctl/model': {
        // The desktop changed the model behind the phone's back. Nothing tells the
        // phone: it has to notice on its own next read of `session.stats`, which is
        // exactly the behaviour worth checking.
        const sessionId = body['sessionId'] ?? 'session-ki2-alpha'
        const provider = body['provider']
        const model = body['model']
        if (typeof provider !== 'string' || typeof model !== 'string') {
          return reply(400, { error: 'model needs provider and model' })
        }
        const selected = {
          provider,
          model,
          ...(typeof body['effort'] === 'string' ? { reasoningEffort: body['effort'] } : {}),
        }
        chosenModels.set(sessionId, selected)
        log(`desktop model change: ${sessionId} -> ${provider}/${model}`)
        return reply(200, { selected })
      }
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
