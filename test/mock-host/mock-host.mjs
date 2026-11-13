#!/usr/bin/env node
/**
 * A Mizar v1 host that can be *driven*, for visual regression and UI tests.
 *
 * The real AGI Mizar host is a desktop application: it needs a model behind it, a
 * session on disk and a person asking for things. That is the wrong tool for
 * "does the process fold draw the streamed text" or "does the image lightbox
 * open a delivered file": the state is not reproducible, and the fixture is
 * whatever the operator happened to be doing.
 *
 * This mock speaks enough of protocol v1 for the phone to pair, connect, list
 * sessions, read a conversation, see pending interactions, stream a turn, replay
 * a journal, read a delivered file, and chart a usage overview -- and it can push
 * exactly the bytes the test wants, when it wants them:
 *
 *   - **binary state frames** (magic `0x53`) in the one-byte-session-id envelope
 *     the host uses, with `snapshot` / `append` / `heartbeat` / `final` chosen per
 *     step and an interval that the test controls;
 *   - **events** (`session.changed`, `turn.settled`, `approval.request`, ...);
 *   - **pending interactions** that can appear while the app is on screen.
 *
 * Zero dependencies on purpose, like `scripts/account-mock.mjs`: the workspace
 * has no npm install step for the Android repo, and a fixture tool that needs one
 * is a fixture tool nobody runs. The WebSocket server (handshake, frame parse,
 * text + binary send) is about a hundred lines and is the only non-obvious part.
 *
 * ## Running it
 *
 *   node test/mock-host/mock-host.mjs                 # http://0.0.0.0:19470
 *   node test/mock-host/mock-host.mjs --port 19470 --fixtures app/src/androidTest/assets/mock-host/fixtures.json
 *
 * From the emulator, the host machine's loopback is `10.0.2.2`, so a pairing deep
 * link is:
 *
 *   adb-5558 shell am start -a android.intent.action.VIEW \
 *     -d 'mizar://pair?h=10.0.2.2:19470&c=MOCK-1234&n=Mock%20Studio&v=1'
 *
 * A physical phone on the same Wi-Fi uses the machine's LAN address instead; no
 * `adb reverse` is needed either way (the emulator path is not a forward).
 *
 * ## Layout
 *
 * `fixtures.json` is the single source of truth for what the host serves, and it is
 * committed under `app/src/androidTest/assets/mock-host/` so the same file is also
 * readable by an instrumentation test (`MockHostFixturesTest`) that decodes the
 * fixtures with the production kit code. The mock host and the test cannot drift.
 *
 * `--emit-frames <file>` writes the state frames the timeline produces, base64 in
 * JSON, for that test to decode with `StateFrameEnvelope.decode`. Regenerate after
 * editing the timeline:
 *
 *   node test/mock-host/mock-host.mjs --emit-frames app/src/androidTest/assets/mock-host/frames.json
 *
 * ## Driving it
 *
 *   POST /ctl/push    {"type":"state", ...one timeline step...}   push one now
 *   POST /ctl/play    {"steps":[...]}                             play a list now
 *   POST /ctl/pending {...PendingInteractions...}                 replace pending
 *   POST /ctl/event   {"name":"session.changed","data":{...}}     push one event
 *   POST /ctl/reset                                               forget pushed state
 *   GET  /health                                                 {"ok":true,...}
 *
 * With `--autoplay`, the timeline in the fixtures plays once when a phone connects;
 * without it, nothing is pushed until `/ctl/play` asks. `--autoplay` is the mode a
 * screenshot session wants: pair, look, wait, capture.
 */

import { createHash, randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { deflateSync } from 'node:zlib'

const PROTOCOL_VERSION = 1
const WS_PATH = '/mizar/v1/ws'
const HELLO_PATH = '/mizar/v1/hello'
const PAIR_PATH = '/mizar/v1/pair'

/** Session state wire codes, mirroring `protocol/states.json`. */
const STATES = {
  IDLE: 0,
  THINKING: 1,
  TOOL: 2,
  AWAITING_APPROVAL: 3,
  AWAITING_USER: 4,
  STREAM_NARRATION: 5,
  STREAM_FINAL: 6,
  SUBAGENT: 7,
  BACKGROUND: 8,
  FAILED: 9,
  EDITING: 10,
  COMPLETED: 11,
  PAUSED: 12,
  COMPACTING: 13,
  RETRYING: 14,
  QUEUED: 15,
}

/** State kinds, mirroring `SessionState.Kind`: which states carry text and which heartbeat. */
const CARRIES_TEXT = new Set(['STREAM_NARRATION', 'STREAM_FINAL'])
const TERMINAL = new Set(['COMPLETED', 'FAILED', 'PAUSED'])

/** Frame flags, mirroring `StateFrameFlag`. */
const FLAGS = { heartbeat: 0b0001, append: 0b0010, snapshot: 0b0100, final: 0b1000 }

// ── args ─────────────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const args = {
    port: Number(process.env.MIZAR_MOCK_PORT ?? 19470),
    host: process.env.MIZAR_MOCK_HOST ?? '0.0.0.0',
    fixtures: 'app/src/androidTest/assets/mock-host/fixtures.json',
    autoplay: false,
    emitFrames: null,
    quiet: false,
  }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    switch (arg) {
      case '--port':
        args.port = Number(argv[++i])
        break
      case '--host':
        args.host = argv[++i]
        break
      case '--fixtures':
        args.fixtures = argv[++i]
        break
      case '--autoplay':
        args.autoplay = true
        break
      case '--quiet':
        args.quiet = true
        break
      case '--emit-frames':
        args.emitFrames = argv[++i]
        break
      case '--selftest':
        args.selftest = true
        break
      case '--help':
        console.log('usage: mock-host.mjs [--port 19470] [--host 0.0.0.0] [--fixtures file] [--autoplay] [--quiet] [--emit-frames file] [--selftest]')
        process.exit(0)
        break
      default:
        throw new Error(`unknown argument: ${arg}`)
    }
  }
  return args
}

const args = parseArgs(process.argv.slice(2))
const log = (...parts) => {
  if (!args.quiet) console.log('mock-host:', ...parts)
}

// ── fixtures ─────────────────────────────────────────────────────────────────

const fixtures = JSON.parse(readFileSync(args.fixtures, 'utf8'))

/**
 * Resolve the fixture placeholder timestamps.
 *
 * Fixtures are committed, but session rows and records want to look recent to the
 * app (`Format.relativeTime` reads the wall clock). Writing `"@now-5000"` keeps the
 * *shape* in the file and makes the value relative to the run, which is the only
 * way a committed fixture can say "five seconds ago" on every machine.
 */
function resolveTimestamps(value, now = Date.now()) {
  if (typeof value === 'string' && value.startsWith('@now')) {
    const offset = Number(value.slice(4) || '0')
    return now + offset
  }
  if (Array.isArray(value)) return value.map((entry) => resolveTimestamps(entry, now))
  if (value && typeof value === 'object') {
    const out = {}
    for (const [key, entry] of Object.entries(value)) out[key] = resolveTimestamps(entry, now)
    return out
  }
  return value
}

/** Mutable host state: what a phone has seen and what the operator pushed. */
const state = {
  pending: structuredClone(fixtures.pending ?? { approvals: [], questions: [] }),
  messages: structuredClone(fixtures.messages),
  playTimer: null,
  scriptPlaying: false,
  autoplayed: false,
  createdAt: Date.now(),
}

// ── protocol payloads ────────────────────────────────────────────────────────

const welcome = () => ({
  v: PROTOCOL_VERSION,
  type: 'welcome',
  hostId: fixtures.host.hostId,
  hostName: fixtures.host.hostName,
  protocolVersion: PROTOCOL_VERSION,
  home: fixtures.host.home,
  deviceId: fixtures.pairing.deviceId,
  engine: fixtures.host.engine ?? undefined,
})

const ok = (id, result) => ({ v: PROTOCOL_VERSION, type: 'ok', id, result })
const failure = (id, code, message, retryable = false) => ({
  v: PROTOCOL_VERSION,
  type: 'error',
  id,
  code,
  message,
  retryable,
})
const event = (name, data) => ({ v: PROTOCOL_VERSION, type: 'event', event: name, data })

/** `session.list`'s rows, with the relative timestamps resolved for this run. */
function sessions() {
  const now = Date.now()
  const resolved = resolveTimestamps(fixtures.sessions, now)
  return resolved.map((row) => ({
    sessionId: row.sessionId,
    updatedAt: row.updatedAt,
    running: Boolean(row.running),
    blank: Boolean(row.blank),
    agentAvailable: row.agentAvailable !== false,
    ...(row.cwd ? { cwd: row.cwd } : {}),
    ...(row.parentSessionId ? { parentSessionId: row.parentSessionId } : {}),
    ...(row.origin ? { origin: row.origin } : {}),
  }))
}

/** `session.messages`, with record times resolved for this run. */
function messagesFor(sessionId) {
  const fixture = fixtures.messages
  if (fixture.sessionId !== sessionId) {
    return { sessionId, asOfSeq: null, records: [], hasMore: false }
  }
  const records = resolveTimestamps(structuredClone(state.messages.records))
  return {
    sessionId,
    asOfSeq: fixture.asOfSeq ?? null,
    records,
    hasMore: Boolean(fixture.hasMore),
    ...(fixture.context ? { context: fixture.context } : {}),
  }
}

function pendingInteractions() {
  return {
    approvals: state.pending.approvals ?? [],
    questions: state.pending.questions ?? [],
  }
}

/**
 * The image `file.read` hands out.
 *
 * Generated rather than committed: a gradient exercises the lightbox's loading,
 * decoding and aspect handling without putting a binary in the repository, and its
 * size is whatever the fixture asks for. A real valueless 1x1 PNG would tell the
 * lightbox nothing.
 */
function gradientPng(width, height) {
  const stride = width * 3 + 1
  const raw = Buffer.alloc(stride * height)
  for (let y = 0; y < height; y += 1) {
    const row = y * stride
    raw[row] = 0 // filter: none
    for (let x = 0; x < width; x += 1) {
      const at = row + 1 + x * 3
      raw[at] = Math.round(24 + (175 * x) / Math.max(1, width - 1))
      raw[at + 1] = Math.round(32 + (150 * y) / Math.max(1, height - 1))
      raw[at + 2] = Math.round(64 + (120 * (x + y)) / Math.max(1, width + height - 2))
    }
  }
  const chunks = [Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])]
  const chunk = (type, data) => {
    const length = Buffer.alloc(4)
    length.writeUInt32BE(data.length, 0)
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
    const crc = Buffer.alloc(4)
    crc.writeUInt32BE(crc32(body) >>> 0, 0)
    chunks.push(length, body, crc)
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(width, 0)
  header.writeUInt32BE(height, 4)
  header[8] = 8 // bit depth
  header[9] = 2 // colour type: truecolour
  chunk('IHDR', header)
  chunk('IDAT', deflateSync(raw))
  chunk('IEND', Buffer.alloc(0))
  return Buffer.concat(chunks)
}

/** CRC-32, for the PNG chunks above. */
const CRC_TABLE = (() => {
  const table = new Int32Array(256)
  for (let n = 0; n < 256; n += 1) {
    let c = n
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c
  }
  return table
})()
function crc32(buffer) {
  let c = -1
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8)
  return c ^ -1
}

/** The bytes of one fixture file, or `null` when the path is not in the fixture. */
function fileBytes(path) {
  const file = (fixtures.files ?? []).find((entry) => entry.path === path)
  if (!file) return null
  if (file.image) {
    const [width, height] = (file.image.match(/(\d+)x(\d+)/) ?? []).slice(1).map(Number)
    return gradientPng(width || 640, height || 400)
  }
  return Buffer.from(file.text ?? `contents of ${path}\n`, 'utf8')
}

// ── state-frame encoding ─────────────────────────────────────────────────────

/** One state frame, as the 13-byte header plus UTF-8 body. */
function encodeStateFrame({ state: stateName, interval = 1, textSeq = 0, flags = [], text = '' }) {
  const code = STATES[stateName]
  if (code === undefined) throw new Error(`unknown state: ${stateName}`)
  let bits = 0
  for (const flag of flags) {
    const bit = FLAGS[flag]
    if (bit === undefined) throw new Error(`unknown state flag: ${flag}`)
    bits |= bit
  }
  const carriesText = CARRIES_TEXT.has(stateName)
  const body = carriesText && (flags.includes('append') || flags.includes('snapshot'))
    ? Buffer.from(text, 'utf8')
    : Buffer.alloc(0)
  if (body.length > 0xffff) throw new Error('state text does not fit the u16 length')
  const frame = Buffer.alloc(13 + body.length)
  frame[0] = 0x53
  frame[1] = code
  frame[2] = bits
  frame.writeUInt32BE(interval >>> 0, 3)
  frame.writeUInt32BE(textSeq >>> 0, 7)
  frame.writeUInt16BE(body.length, 11)
  body.copy(frame, 13)
  return frame
}

/** The socket envelope: one-byte session-id length, the id, then the frame. */
function encodeStateEnvelope(sessionId, step) {
  const id = Buffer.from(sessionId, 'utf8')
  if (id.length > 255) throw new Error('session id does not fit the one-byte prefix')
  return Buffer.concat([Buffer.from([id.length]), id, encodeStateFrame(step)])
}

/** The interval's text so far, so an `append` with `"textSeq":"auto"` lands where it must. */
const intervalText = new Map()
function resolveTextSeq(sessionId, step) {
  const key = `${sessionId}#${step.interval ?? 1}`
  const snapshot = step.flags?.includes('snapshot')
  const text = step.text ?? ''
  const bytes = Buffer.byteLength(text, 'utf8')
  // The track is updated even when the step declares its own seq: the next append
  // still has to land after this text.
  const previous = snapshot ? 0 : (intervalText.get(key) ?? 0)
  if (snapshot) intervalText.set(key, bytes)
  else if (step.flags?.includes('append')) intervalText.set(key, previous + bytes)
  return step.textSeq === 'auto' ? previous : (step.textSeq ?? 0)
}

/** Which steps the timeline is made of, for `--emit-frames` and for validation. */
const frameSteps = (fixtures.timeline ?? []).filter((step) => step.type === 'state')

// ── WebSocket: handshake, framing, sending ───────────────────────────────────

/** Upgrade an HTTP request; returns a connection object, or `null` when it is not a WS upgrade. */
function acceptWebSocket(request, socket, head) {
  const key = request.headers['sec-websocket-key']
  if (!key) {
    socket.destroy()
    return null
  }
  const accept = createHash('sha1')
    .update(`${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest('base64')
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
      'Upgrade: websocket\r\n' +
      'Connection: Upgrade\r\n' +
      `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  )
  const connection = { socket, buffer: Buffer.alloc(0), open: true, id: randomUUID() }
  socket.on('data', (chunk) => {
    connection.buffer = Buffer.concat([connection.buffer, chunk])
    for (;;) {
      const parsed = readFrame(connection.buffer)
      if (!parsed) break
      connection.buffer = connection.buffer.subarray(parsed.consumed)
      handleFrame(connection, parsed)
    }
  })
  socket.on('close', () => {
    connection.open = false
    clients.delete(connection)
    log(`client ${connection.id.slice(0, 8)} disconnected (${clients.size} left)`)
  })
  socket.on('error', () => {
    connection.open = false
    clients.delete(connection)
  })
  if (head?.length) socket.emit('data', head)
  return connection
}

/** Parse one client frame (always masked); `null` when more bytes are needed. */
function readFrame(buffer) {
  if (buffer.length < 2) return null
  const opcode = buffer[0] & 0x0f
  const masked = (buffer[1] & 0x80) !== 0
  let length = buffer[1] & 0x7f
  let offset = 2
  if (length === 126) {
    if (buffer.length < offset + 2) return null
    length = buffer.readUInt16BE(offset)
    offset += 2
  } else if (length === 127) {
    if (buffer.length < offset + 8) return null
    length = Number(buffer.readBigUInt64BE(offset))
    offset += 8
  }
  const maskBytes = masked ? 4 : 0
  if (buffer.length < offset + maskBytes + length) return null
  let payload = buffer.subarray(offset + maskBytes, offset + maskBytes + length)
  if (masked) {
    const mask = buffer.subarray(offset, offset + 4)
    const unmasked = Buffer.alloc(length)
    for (let i = 0; i < length; i += 1) unmasked[i] = payload[i] ^ mask[i % 4]
    payload = unmasked
  }
  return { opcode, payload, consumed: offset + maskBytes + length }
}

function writeWsFrame(socket, opcode, payload) {
  let header
  if (payload.length < 126) {
    header = Buffer.from([0x80 | opcode, payload.length])
  } else if (payload.length < 0x10000) {
    header = Buffer.alloc(4)
    header[0] = 0x80 | opcode
    header[1] = 126
    header.writeUInt16BE(payload.length, 2)
  } else {
    header = Buffer.alloc(10)
    header[0] = 0x80 | opcode
    header[1] = 127
    header.writeBigUInt64BE(BigInt(payload.length), 2)
  }
  socket.write(Buffer.concat([header, payload]))
}

function sendText(connection, frame) {
  if (!connection.open) return
  writeWsFrame(connection.socket, 0x1, Buffer.from(JSON.stringify(frame), 'utf8'))
}

function sendBinary(connection, bytes) {
  if (!connection.open) return
  writeWsFrame(connection.socket, 0x2, bytes)
}

function handleFrame(connection, { opcode, payload }) {
  switch (opcode) {
    case 0x1:
      handleText(connection, payload.toString('utf8'))
      break
    case 0x8:
      connection.open = false
      connection.socket.end()
      break
    case 0x9:
      writeWsFrame(connection.socket, 0xa, payload)
      break
    default:
      // Binary from the client is not part of v1; ignore it rather than close.
      break
  }
}

/** Route one client JSON frame: the handshake, or one correlated method call. */
function handleText(connection, text) {
  let frame
  try {
    frame = JSON.parse(text)
  } catch {
    log('client sent invalid JSON; ignoring')
    return
  }
  if (frame.type === 'hello') {
    log(`hello from device ${connection.id.slice(0, 8)} (token ${String(frame.token).slice(0, 8)}..., engine ${frame.engine ?? '-'})`)
    sendText(connection, welcome())
    return
  }
  const id = frame.id ?? null
  const reply = answer(connection, frame)
  if (!reply) {
    sendText(connection, failure(id, 'NOT_IMPLEMENTED', `the mock host does not implement ${frame.type}`))
    return
  }
  sendText(connection, ok(id, reply))
}

/** One method's result. `null` means "not implemented". */
function answer(connection, frame) {
  const payload = frame.payload ?? {}
  switch (frame.type) {
    case 'session.list':
      return { items: sessions() }
    case 'workspace.list':
      return { items: fixtures.workspaces ?? [] }
    case 'session.messages':
      return messagesFor(payload.sessionId)
    case 'session.refresh':
      return {
        messages: messagesFor(payload.sessionId),
        stats: fixtures.stats ?? null,
        pending: pendingInteractions(),
      }
    case 'session.stats':
      return { sessionId: payload.sessionId, asOfSeq: fixtures.messages?.asOfSeq ?? null, stats: fixtures.stats ?? null }
    case 'session.sync':
      return {
        items: (payload.items ?? []).map((item) => ({
          sessionId: item.sessionId,
          messages: messagesFor(item.sessionId),
        })),
      }
    case 'session.stream':
      return { ...fixtures.stream, sessionId: payload.sessionId ?? fixtures.stream?.sessionId }
    case 'session.prompt':
    case 'session.create':
    case 'session.cancel':
      if (frame.type === 'session.prompt' && fixtures.autoplayOnPrompt) playTimeline({ onlyOnce: false })
      return frame.type === 'session.create'
        ? { sessionId: fixtures.sessions?.[0]?.sessionId ?? 'mock-session-1', agentPreset: null }
        : { accepted: true, requestId: randomUUID() }
    case 'interactions.pending':
      return pendingInteractions()
    case 'approval.answer':
    case 'question.answer':
    case 'push.presence':
    case 'push.unregister':
    case 'session.model.set':
    case 'session.permission.set':
    case 'session.rename':
      return { accepted: true }
    case 'host.info':
      return {
        hostId: fixtures.host.hostId,
        hostName: fixtures.host.hostName,
        home: fixtures.host.home,
        protocolVersion: PROTOCOL_VERSION,
        engines: fixtures.host.engines ?? [],
      }
    case 'model.catalog':
      return fixtures.models ?? { defaultSelection: null, providers: [] }
    case 'permissions.catalog':
      return fixtures.permissions ?? { options: [], defaults: [], defaultPreset: '' }
    case 'usage.overview':
      return fixtures.usage ?? { sessions: 0, days: [] }
    case 'file.stat': {
      const file = (fixtures.files ?? []).find((entry) => entry.path === payload.path)
      if (!file) return null
      const bytes = fileBytes(payload.path) ?? Buffer.alloc(0)
      return {
        path: file.path,
        name: file.name ?? file.path.split('/').pop(),
        byteSize: bytes.length,
        modifiedAt: Date.now() - 60_000,
        mediaType: file.mediaType ?? 'application/octet-stream',
        previewable: file.previewable !== false,
      }
    }
    case 'file.read': {
      const bytes = fileBytes(payload.path)
      if (!bytes) return null
      const offset = Math.max(0, Number(payload.offset ?? 0))
      const length = Math.max(1, Number(payload.length ?? 256 * 1024))
      const slice = bytes.subarray(offset, Math.min(bytes.length, offset + length))
      return {
        offset,
        length: slice.length,
        byteSize: bytes.length,
        data: slice.toString('base64'),
        eof: offset + slice.length >= bytes.length,
      }
    }
    default:
      return null
  }
}

// ── pushing timeline steps ───────────────────────────────────────────────────

const clients = new Set()

/** Push one step of the scripted timeline to every connected phone. */
function pushStep(step) {
  const sessionId = step.sessionId ?? fixtures.stream?.sessionId ?? fixtures.sessions?.[0]?.sessionId
  switch (step.type) {
    case 'state': {
      const textSeq = step.flags?.includes('append') || step.flags?.includes('snapshot')
        ? resolveTextSeq(sessionId, step)
        : 0
      const bytes = encodeStateEnvelope(sessionId, { ...step, textSeq })
      for (const connection of clients) sendBinary(connection, bytes)
      log(`state ${step.state} interval=${step.interval ?? 1} flags=${(step.flags ?? []).join('+') || '-'} text=${JSON.stringify(step.text ?? '').slice(0, 60)} -> ${clients.size} client(s)`)
      break
    }
    case 'event': {
      for (const connection of clients) sendText(connection, event(step.name, step.data ?? {}))
      log(`event ${step.name} -> ${clients.size} client(s)`)
      break
    }
    case 'pending': {
      state.pending = step.pending ?? { approvals: [], questions: [] }
      for (const connection of clients) {
        sendText(connection, event('approval.request', { sessionId }))
        sendText(connection, event('question.request', { sessionId }))
      }
      log(`pending set: ${pendingInteractions().approvals.length} approval(s), ${pendingInteractions().questions.length} question(s)`)
      break
    }
    case 'messages': {
      state.messages = step.messages
      for (const connection of clients) sendText(connection, event('session.changed', { sessionId, running: false }))
      log('messages fixture replaced')
      break
    }
    default:
      log(`ignoring unknown timeline step type: ${step.type}`)
      break
  }
}

/** Play a list of steps, `afterMs` apart, in order. */
function playSteps(steps) {
  stopTimeline()
  intervalText.clear()
  state.scriptPlaying = true
  let elapsed = 0
  for (const step of steps) {
    elapsed += Number(step.afterMs ?? 0)
    const timer = setTimeout(() => {
      pushStep(step)
      if (step === steps[steps.length - 1]) {
        state.scriptPlaying = false
        log('timeline finished')
      }
    }, elapsed)
    state.playTimer = timer
  }
  log(`timeline: ${steps.length} step(s) over ${elapsed} ms`)
}

function stopTimeline() {
  if (state.playTimer) clearTimeout(state.playTimer)
  state.playTimer = null
  state.scriptPlaying = false
}

function playTimeline({ onlyOnce = true } = {}) {
  if (onlyOnce && state.autoplayed) return
  state.autoplayed = true
  playSteps(fixtures.timeline ?? [])
}

// ── HTTP: identity, pairing, control ─────────────────────────────────────────

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    request.on('data', (chunk) => chunks.push(chunk))
    request.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8').trim()
      if (!body) {
        resolve({})
        return
      }
      try {
        resolve(JSON.parse(body))
      } catch (error) {
        reject(error)
      }
    })
    request.on('error', reject)
  })
}

function json(response, status, body) {
  const text = JSON.stringify(body)
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
    'access-control-allow-origin': '*',
  })
  response.end(text)
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`)
  try {
    if (request.method === 'GET' && url.pathname === HELLO_PATH) {
      json(response, 200, {
        hostId: fixtures.host.hostId,
        hostName: fixtures.host.hostName,
        protocolVersion: PROTOCOL_VERSION,
        pairingOpen: true,
        paired: false,
        engines: fixtures.host.engines ?? [],
      })
      return
    }
    if (request.method === 'POST' && url.pathname === PAIR_PATH) {
      const body = await readBody(request)
      const expected = fixtures.pairing.code
      if (body.code !== expected) {
        json(response, 401, { error: 'PAIRING_CODE_INVALID' })
        log(`pair refused: ${JSON.stringify(body.code)} != ${expected}`)
        return
      }
      json(response, 200, {
        deviceToken: fixtures.pairing.deviceToken,
        deviceId: fixtures.pairing.deviceId,
        hostId: fixtures.host.hostId,
        hostName: fixtures.host.hostName,
        protocolVersion: PROTOCOL_VERSION,
      })
      log(`paired a device with code ${expected}`)
      return
    }
    if (request.method === 'GET' && url.pathname === '/health') {
      json(response, 200, {
        ok: true,
        host: `${args.host}:${args.port}`,
        clients: clients.size,
        pairingCode: fixtures.pairing.code,
        timelineSteps: (fixtures.timeline ?? []).length,
        autoplay: args.autoplay,
        playing: state.scriptPlaying,
        uptimeMs: Date.now() - state.createdAt,
      })
      return
    }

    // Control endpoints. JSON over POST, answer JSON; scripts drive these with curl.
    if (request.method === 'POST' && url.pathname === '/ctl/push') {
      pushStep(await readBody(request))
      json(response, 200, { ok: true })
      return
    }
    if (request.method === 'POST' && url.pathname === '/ctl/play') {
      const body = await readBody(request)
      playSteps(body.steps ?? [])
      json(response, 200, { ok: true, steps: (body.steps ?? []).length })
      return
    }
    if (request.method === 'POST' && url.pathname === '/ctl/pending') {
      pushStep({ type: 'pending', pending: await readBody(request) })
      json(response, 200, { ok: true })
      return
    }
    if (request.method === 'POST' && url.pathname === '/ctl/event') {
      const body = await readBody(request)
      pushStep({ type: 'event', name: body.name, data: body.data ?? {} })
      json(response, 200, { ok: true })
      return
    }
    if (request.method === 'POST' && url.pathname === '/ctl/reset') {
      stopTimeline()
      state.pending = structuredClone(fixtures.pending ?? { approvals: [], questions: [] })
      state.messages = structuredClone(fixtures.messages)
      state.autoplayed = false
      json(response, 200, { ok: true })
      return
    }
    json(response, 404, { error: 'not found', path: url.pathname })
  } catch (error) {
    json(response, 500, { error: String(error?.message ?? error) })
  }
})

server.on('upgrade', (request, socket, head) => {
  if ((request.url ?? '').split('?')[0] !== WS_PATH) {
    socket.destroy()
    return
  }
  const connection = acceptWebSocket(request, socket, head)
  if (!connection) return
  clients.add(connection)
  log(`client ${connection.id.slice(0, 8)} connected (${clients.size} total)`)
  // The real host greets a socket with its welcome as soon as it is accepted; the
  // phone reads before it writes, so an immediate welcome is the supported order.
  sendText(connection, welcome())
  if (args.autoplay) playTimeline({ onlyOnce: true })
})

// ── selftest: verify the wire without a phone ────────────────────────────────
//
// `--selftest` starts the host on an ephemeral port, speaks to it the way the app
// does (HTTP hello, pair, WS handshake, one method call, one pushed state frame)
// and exits non-zero on the first disagreement. It exists because everything that
// could be wrong here -- the handshake accept key, a masked client frame, a state
// envelope whose byte layout drifted, a fixture the app could not parse -- would
// otherwise be discovered only on a booted emulator, minutes later.

if (args.selftest) {
  try {
    await runSelftest()
  } catch (error) {
    console.error('mock-host selftest: FAIL —', error?.message ?? error)
    process.exitCode = 1
  }
}

async function runSelftest() {
  const { connect } = await import('node:net')
  const port = await new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server.address().port))
  })
  const base = `http://127.0.0.1:${port}`
  const expect = (condition, message) => {
    if (!condition) throw new Error(message)
  }

  // 1. Identity, the unauthenticated route pairing reads first.
  const hello = await (await fetch(`${base}${HELLO_PATH}`)).json()
  expect(hello.hostId === fixtures.host.hostId, `hello hostId was ${hello.hostId}`)
  expect(hello.protocolVersion === PROTOCOL_VERSION, 'hello protocolVersion')

  // 2. Pairing: the good code, then the refused one.
  const pair = await (
    await fetch(`${base}${PAIR_PATH}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: fixtures.pairing.code, protocolVersion: PROTOCOL_VERSION }),
    })
  ).json()
  expect(pair.deviceToken === fixtures.pairing.deviceToken, 'pair deviceToken')
  const refused = await fetch(`${base}${PAIR_PATH}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: 'WRONG-CODE' }),
  })
  expect(refused.status === 401, `a wrong code answered ${refused.status}`)

  // 3. The WebSocket: handshake, welcome, a method call, a binary state push.
  const socket = connect(port, '127.0.0.1')
  await new Promise((resolve, reject) => {
    socket.once('connect', resolve)
    socket.once('error', reject)
  })
  const pending = []
  let buffer = Buffer.alloc(0)
  let upgraded = false
  const waiters = []
  socket.on('data', (chunk) => {
    buffer = Buffer.concat([buffer, chunk])
    if (!upgraded) {
      // The 101 response and the first frames share a socket; the headers are not
      // a WebSocket frame, so they are dropped before parsing starts.
      const end = buffer.indexOf('\r\n\r\n')
      if (end < 0) return
      const head = buffer.subarray(0, end + 4).toString('utf8')
      if (!head.startsWith('HTTP/1.1 101')) expect(false, `the upgrade answered ${head.split('\r\n')[0]}`)
      buffer = buffer.subarray(end + 4)
      upgraded = true
    }
    for (;;) {
      const parsed = readServerFrame(buffer)
      if (!parsed) break
      buffer = buffer.subarray(parsed.consumed)
      const waiter = waiters.shift()
      if (waiter) waiter(parsed)
      else pending.push(parsed)
    }
  })
  const nextFrame = (timeoutMs = 3000) =>
    new Promise((resolve, reject) => {
      if (pending.length) {
        resolve(pending.shift())
        return
      }
      const timer = setTimeout(() => reject(new Error('no frame within the timeout')), timeoutMs)
      waiters.push((frame) => {
        clearTimeout(timer)
        resolve(frame)
      })
    })
  const key = Buffer.from('mock-selftest-key-0123').toString('base64')
  socket.write(
    `GET ${WS_PATH} HTTP/1.1\r\nHost: 127.0.0.1:${port}\r\nUpgrade: websocket\r\n` +
      `Connection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`,
  )
  const welcomeFrame = await nextFrame()
  expect(welcomeFrame.opcode === 0x1, 'the first socket frame must be a text welcome')
  expect(JSON.parse(welcomeFrame.payload.toString('utf8')).type === 'welcome', 'welcome frame type')

  socket.write(maskedClientFrame(0x1, Buffer.from(JSON.stringify({ v: 1, type: 'hello', token: pair.deviceToken }), 'utf8')))
  const secondWelcome = await nextFrame()
  expect(JSON.parse(secondWelcome.payload.toString('utf8')).type === 'welcome', 'the hello must be answered')

  socket.write(
    maskedClientFrame(
      0x1,
      Buffer.from(JSON.stringify({ v: 1, type: 'session.list', id: 'selftest-1' }), 'utf8'),
    ),
  )
  const listFrame = await nextFrame()
  const list = JSON.parse(listFrame.payload.toString('utf8'))
  expect(list.type === 'ok' && list.id === 'selftest-1', 'session.list must answer ok')
  expect(Array.isArray(list.result?.items) && list.result.items.length === fixtures.sessions.length, 'session.list item count')

  await fetch(`${base}/ctl/push`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(frameSteps[0]),
  })
  const binary = await nextFrame()
  expect(binary.opcode === 0x2, 'a pushed state must be a binary frame')
  const decoded = decodeStateEnvelope(binary.payload)
  expect(decoded.sessionId === (frameSteps[0].sessionId ?? ''),
    `state envelope sessionId was ${decoded.sessionId}`)
  expect(decoded.state === STATES[frameSteps[0].state], 'state code')

  socket.destroy()
  // An upgraded socket is not tracked by `server.close`; the host's own clients must
  // be torn down or the close callback never runs (and Node reports an unsettled
  // top-level await instead of an exit code).
  for (const connection of clients) connection.socket.destroy()
  clients.clear()
  server.closeAllConnections?.()
  await new Promise((resolve) => server.close(resolve))
  console.log(`mock-host selftest: PASS — ${fixtures.sessions.length} sessions, ${frameSteps.length} scripted frame(s)`)
}

/** A masked client frame, as a browser/OkHttp writes it. */
function maskedClientFrame(opcode, payload) {
  const mask = Buffer.from([0x12, 0x34, 0x56, 0x78])
  let header
  if (payload.length < 126) {
    header = Buffer.from([0x80 | opcode, 0x80 | payload.length])
  } else {
    header = Buffer.alloc(4)
    header[0] = 0x80 | opcode
    header[1] = 0x80 | 126
    header.writeUInt16BE(payload.length, 2)
  }
  const masked = Buffer.alloc(payload.length)
  for (let i = 0; i < payload.length; i += 1) masked[i] = payload[i] ^ mask[i % 4]
  return Buffer.concat([header, mask, masked])
}

/** Parse one unmasked server frame (the mock host never masks its own frames). */
function readServerFrame(buffer) {
  if (buffer.length < 2) return null
  const opcode = buffer[0] & 0x0f
  let length = buffer[1] & 0x7f
  let offset = 2
  if (length === 126) {
    if (buffer.length < 4) return null
    length = buffer.readUInt16BE(2)
    offset = 4
  } else if (length === 127) {
    if (buffer.length < 10) return null
    length = Number(buffer.readBigUInt64BE(2))
    offset = 10
  }
  if (buffer.length < offset + length) return null
  return { opcode, payload: buffer.subarray(offset, offset + length), consumed: offset + length }
}

/** Decode the one-byte-session-id state envelope, to prove the frame is what we meant. */
function decodeStateEnvelope(bytes) {
  const idLength = bytes[0]
  const sessionId = bytes.subarray(1, 1 + idLength).toString('utf8')
  const frame = bytes.subarray(1 + idLength)
  return {
    sessionId,
    state: frame[1],
    flags: frame[2],
    interval: frame.readUInt32BE(3),
    textSeq: frame.readUInt32BE(7),
    length: frame.readUInt16BE(11),
  }
}

// ── emit mode: the bytes the timeline would send, for the instrumentation test ─

if (args.emitFrames) {
  const frames = frameSteps.map((step, index) => {
    const sessionId = step.sessionId ?? fixtures.stream?.sessionId ?? fixtures.sessions?.[0]?.sessionId
    // Snapshot steps are resolved too, not just appends: the resolver is what keeps
    // each interval's text length, and an append after an untracked snapshot would
    // be emitted at textSeq 0 -- the exact drift the fixture test catches.
    const carries = step.flags?.includes('append') || step.flags?.includes('snapshot')
    const textSeq = carries ? resolveTextSeq(sessionId, step) : 0
    return {
      name: step.name ?? `step-${index + 1}`,
      sessionId,
      bytes: encodeStateEnvelope(sessionId, { ...step, textSeq }).toString('base64'),
    }
  })
  writeFileSync(args.emitFrames, `${JSON.stringify({ frames }, null, 2)}\n`)
  log(`wrote ${frames.length} frame(s) to ${args.emitFrames}`)
}

if (!args.selftest && !args.emitFrames) {
  server.listen(args.port, args.host, () => {
    log(`listening on http://${args.host}:${args.port}`)
    log(`  hello ${HELLO_PATH}   pair ${PAIR_PATH}   ws ${WS_PATH}`)
    log(`  pairing code: ${fixtures.pairing.code}  (fixtures: ${args.fixtures})`)
    log(`  emulator: mizar://pair?h=10.0.2.2:${args.port}&c=${fixtures.pairing.code}&n=Mock%20Studio&v=1`)
    log(`  timeline: ${(fixtures.timeline ?? []).length} step(s), autoplay=${args.autoplay}`)
  })
}
