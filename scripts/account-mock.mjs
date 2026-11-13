#!/usr/bin/env node
/**
 * A stand-in for the account service, for the AVD.
 *
 * The relay's real service needs a GitHub OAuth App and a deployment; this one
 * answers the same shapes locally so the Android client and its UI can be tested
 * end to end. It is deliberately small and dependency-free: one file, one port,
 * everything in memory.
 *
 *   node scripts/account-mock.mjs --port 8787
 *
 * The emulator reaches the host as 10.0.2.2, so the authorize URL it hands back
 * points there by default. From the host, curl the same URLs directly.
 *
 * Fixed data:
 *   account  shirazu / mizar-test-password   (or any account you register)
 *   invite   MOCK-INVITE                     (+30 days)
 *   github   one identity: octocat / octo@example.com
 *
 * Testing hooks:
 *   GET /account/v1/me?fail=revoked   → 401 SESSION_REVOKED
 *   POST /account/v1/reset            → forget every account, session and code
 *   WS /app/v1/announcements          → snapshot, then one frame per created banner
 *                                       (auth: `x-mizar-session`; `?token=` for tests)
 *   GET /account/v1/announcements     → the polling twin of the socket
 *   GET /account/v1/surveys           → two live questionnaires and one closed one
 *   POST /account/v1/surveys/submit   → pays the reward, then marks it done per account
 *   POST /mock/announcements          → create a banner and broadcast it on the socket
 *   GET /mock/announcements           → every banner this mock knows about
 *   POST /mock/surveys/reset          → forget every submission (repeatable UI runs)
 *
 *   node scripts/account-mock.mjs --selftest
 *       Runs the whole account/survey/announcement loop against itself on a random
 *       local port and prints ok/FAIL per check; exits non-zero on failure. No
 *       emulator needed.
 */
import { createHash, randomBytes } from 'node:crypto'
import { createServer } from 'node:http'

const args = process.argv.slice(2)
const valueOf = (name, fallback) => {
  const index = args.indexOf(`--${name}`)
  return index >= 0 && args[index + 1] !== undefined ? args[index + 1] : fallback
}

const PORT = Number(valueOf('port', '8787'))
const HOST = valueOf('host', '0.0.0.0')
const PUBLIC = valueOf('public', `http://10.0.2.2:${PORT}`)
const DAY = 86_400_000
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/** @type {Map<string, any>} */
const users = new Map()
/** @type {Map<string, { userId: string, deviceId: string, revoked: boolean }>} */
const sessions = new Map()

/**
 * Outstanding password-reset codes, by lowercased address.
 *
 * Fixed at 123456 on purpose: this mock exists so the flow can be walked end to
 * end, and a random code would only mean reading the log to type it.
 */
const resetCodes = new Map()
/** @type {Map<string, { deviceId: string, userId: string | null, token: string | null, expiresAt: number }>} */
const flows = new Map()
/** @type {Map<string, { token: string, deviceId: string, expiresAt: number }>} */
const handoffs = new Map()

let sequence = 0
const nextId = (prefix) => `${prefix}-${++sequence}`

function newToken() {
  return `mza_${randomBytes(32).toString('base64url')}`
}

function newHandoff() {
  let out = ''
  for (const byte of randomBytes(8)) out += ALPHABET[byte % ALPHABET.length]
  return `MZ-${out.slice(0, 4)}-${out.slice(4, 8)}`
}

function hash(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex')
}

function publicUser(user, deviceId) {
  const until = user.grantedPaidUntil
  const paid = until !== null && until > Date.now()
  return {
    id: user.id,
    username: user.username,
    email: user.email,
    emailVerified: user.emailVerified,
    plan: paid ? 'paid' : 'free',
    paidUntil: until,
    paidSources: paid ? ['invite'] : [],
    grantedPaidUntil: until,
    appStore: null,
    avatar: { kind: 'generated', colors: ['#5b8def', '#8f6bff'], initials: user.username.slice(0, 1).toUpperCase() },
    apple: false,
    github: user.githubId !== null,
    githubLogin: user.githubLogin,
    createdAt: user.createdAt,
    lastSeenAt: Date.now(),
    devices: user.devices.map((device) => ({
      id: device.id,
      name: device.name,
      model: device.model,
      platform: device.platform,
      loginAt: device.loginAt,
      lastSeenAt: device.lastSeenAt,
      current: device.id === deviceId,
    })),
  }
}

function createUser({ username, email, passwordHash, githubId = null, githubLogin = null, emailVerified = false }) {
  const user = {
    id: nextId('user'),
    username,
    usernameKey: username.toLowerCase(),
    email,
    emailKey: email === null ? null : email.toLowerCase(),
    emailVerified,
    passwordHash,
    githubId,
    githubLogin,
    devices: [],
    grantedPaidUntil: null,
    createdAt: Date.now(),
  }
  users.set(user.id, user)
  return user
}

function findByIdentifier(identifier) {
  const key = identifier.trim().toLowerCase()
  for (const user of users.values()) {
    if (user.usernameKey === key || user.emailKey === key) return user
  }
  return null
}

function issueSession(user, device) {
  let record = user.devices.find((candidate) => candidate.id === device.id)
  if (record === undefined) {
    record = { ...device, loginAt: Date.now(), lastSeenAt: Date.now() }
    user.devices.push(record)
  } else {
    record.lastSeenAt = Date.now()
  }
  // The same device replaces its old session.
  for (const [token, session] of sessions) {
    if (session.userId === user.id && session.deviceId === device.id) sessions.delete(token)
  }
  // Password-only accounts cap at two devices; GitHub accounts do not.
  let notice
  if (user.githubId === null && user.appleSub === null) {
    const active = new Set(
      [...sessions.values()].filter((s) => s.userId === user.id && !s.revoked).map((s) => s.deviceId),
    )
    if (active.size >= 2) {
      const oldest = user.devices
        .filter((candidate) => active.has(candidate.id) && candidate.id !== device.id)
        .sort((a, b) => a.loginAt - b.loginAt)[0]
      if (oldest !== undefined) {
        for (const [token, session] of sessions) {
          if (session.userId === user.id && session.deviceId === oldest.id) sessions.delete(token)
        }
        notice = { code: 'DEVICE_LIMIT', revokedDevice: oldest.name }
      }
    }
  }
  const token = newToken()
  sessions.set(token, { userId: user.id, deviceId: device.id, revoked: false })
  return { token, notice }
}

function bearer(request) {
  const header = request.headers.authorization
  if (typeof header !== 'string') return null
  const match = /^Bearer\s+(.+)$/i.exec(header.trim())
  return match === null ? null : match[1].trim()
}

function sessionOf(request) {
  const token = bearer(request)
  if (token === null) return null
  const session = sessions.get(token)
  if (session === undefined || session.revoked) return null
  const user = users.get(session.userId)
  if (user === undefined) return null
  return { token, session, user }
}

function deviceOf(body) {
  const raw = body?.device
  if (typeof raw !== 'object' || raw === null) return null
  const id = typeof raw.id === 'string' ? raw.id : ''
  if (!/^[A-Za-z0-9._-]{8,64}$/.test(id)) return null
  return {
    id,
    name: typeof raw.name === 'string' ? raw.name : 'Android',
    model: typeof raw.model === 'string' ? raw.model : 'unknown',
    platform: typeof raw.platform === 'string' ? raw.platform : 'android',
    appVersion: typeof raw.appVersion === 'string' ? raw.appVersion : 'unknown',
  }
}

function send(response, status, body, headers = {}) {
  const text = JSON.stringify(body)
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    ...headers,
  })
  response.end(text)
}

function sendHtml(response, status, html) {
  response.writeHead(status, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' })
  response.end(html)
}

function readBody(request) {
  return new Promise((resolve) => {
    let text = ''
    request.on('data', (chunk) => {
      text += chunk
      if (text.length > 64 * 1024) request.destroy()
    })
    request.on('end', () => {
      try {
        resolve(text === '' ? {} : JSON.parse(text))
      } catch {
        resolve(null)
      }
    })
  })
}

function donePage(handoff, error) {
  const title = handoff === null ? 'Sign-in did not finish' : 'Signed in'
  const body = handoff === null
    ? `<p class="muted">${error === 'denied' ? 'You cancelled the GitHub authorization.' : 'Something went wrong while signing in.'}</p>`
    : `<p class="muted">Return to the Mizar app to finish signing in.</p>
       <p><a class="button" href="intent://auth?handoff=${handoff}#Intent;scheme=mizar;package=me.shirazu.dshmizar;end">Open Mizar</a></p>
       <p><a class="link" href="mizar://auth?handoff=${handoff}">Open Mizar (other browsers)</a></p>
       <p class="label">Or type this code into the app:</p>
       <p class="code">${handoff}</p>`
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${title} · Mizar mock</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
font:16px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#f4f4f5;color:#18181b}
main{max-width:22rem;padding:2rem;text-align:center}h1{font-size:1.35rem}.muted{color:#52525b}
.button{display:inline-block;padding:.75rem 1.25rem;border-radius:.75rem;background:#18181b;color:#fff;text-decoration:none}
.link{color:#2563eb}.label{margin-top:1.5rem;font-size:.85rem;color:#71717a}
.code{font:600 1.25rem/1.4 ui-monospace,Menlo,monospace;letter-spacing:.08em}</style></head>
<body><main><h1>${title}</h1>${body}</main></body></html>`
}

// ── questionnaires (0.9.9 operator channel) ────────────────────────────────

/**
 * The questionnaires the operator dispatched, fixed like the fixed account.
 *
 * Three question types and one closed questionnaire, because the client has to draw
 * all three and must not draw a form for a survey nobody can answer any more.
 */
const SURVEYS = [
  {
    id: 'survey-turn-cycle',
    title: 'What should the turn cycle do next?',
    summary: 'Three short questions.',
    rewardDays: 7,
    active: true,
    expiresAt: null,
    questions: [
      {
        id: 'q1',
        type: 'single',
        prompt: 'Pick the update you want first',
        required: true,
        options: [
          { id: 'o1', label: 'Faster syncing' },
          { id: 'o2', label: 'Better Markdown' },
        ],
      },
      {
        id: 'q2',
        type: 'multi',
        prompt: 'Which platforms do you use?',
        required: false,
        options: [
          { id: 'm1', label: 'Android' },
          { id: 'm2', label: 'iOS' },
          { id: 'm3', label: 'Desktop' },
        ],
      },
      {
        id: 'q3',
        type: 'text',
        prompt: 'Anything else?',
        required: true,
        maxLength: 400,
      },
    ],
  },
  {
    id: 'survey-notifications',
    title: 'How are the alerts?',
    summary: 'One question.',
    rewardDays: 3,
    active: true,
    expiresAt: null,
    questions: [
      {
        id: 'n1',
        type: 'single',
        prompt: 'Do the alerts arrive while the turn runs?',
        required: true,
        options: [
          { id: 'yes', label: 'Always' },
          { id: 'late', label: 'Sometimes late' },
        ],
      },
    ],
  },
  {
    id: 'survey-archive',
    title: 'The 0.9.8 survey',
    summary: 'Closed.',
    rewardDays: 1,
    active: false,
    expiresAt: null,
    questions: [],
  },
]

/** Who answered what, by `${userId}:${surveyId}`. In memory, like everything here. */
const surveyCompletions = new Map()

// Account computer discovery: the relay would broker these; the mock answers with
// two computers so the phone's list and its open flow can be walked on an AVD.
const desktopConnects = []
let desktopTokens = 0

function isExpired(survey) {
  return typeof survey.expiresAt === 'number' && survey.expiresAt <= Date.now()
}

/** The surveys as this account sees them: the completion state is per account. */
function surveysFor(user) {
  return SURVEYS.map((survey) => {
    const record = surveyCompletions.get(`${user.id}:${survey.id}`)
    return {
      id: survey.id,
      title: survey.title,
      summary: survey.summary,
      rewardDays: survey.rewardDays,
      questions: survey.questions,
      active: survey.active,
      // Omitted rather than null, as the real relay does.
      ...(survey.expiresAt === null ? {} : { expiresAt: survey.expiresAt }),
      completed: record !== undefined,
      ...(record === undefined ? {} : { completedAt: record.completedAt }),
    }
  })
}

/**
 * The answers the relay would accept, as a map by question id, or `null` when the
 * shape is wrong. It is deliberately strict: the point of the mock is to catch a
 * client that sends a choice array where a single string belongs.
 */
function validAnswers(survey, answers) {
  if (!Array.isArray(answers)) return null
  const byId = new Map()
  for (const answer of answers) {
    if (typeof answer !== 'object' || answer === null) return null
    const question = survey.questions.find((candidate) => candidate.id === answer.questionId)
    if (question === undefined || byId.has(question.id)) return null
    if (question.type === 'multi') {
      if (!Array.isArray(answer.value) || answer.value.length === 0) return null
      if (answer.value.some((id) => typeof id !== 'string' || !question.options.some((option) => option.id === id))) {
        return null
      }
      byId.set(question.id, [...answer.value])
    } else {
      if (typeof answer.value !== 'string' || answer.value.trim() === '') return null
      if (question.type === 'single' && !question.options.some((option) => option.id === answer.value)) return null
      byId.set(question.id, answer.value)
    }
  }
  // Every required question has to be there, as the submit button promised.
  for (const question of survey.questions) {
    if (question.required && !byId.has(question.id)) return null
  }
  return byId
}

/** Add the reward to whatever paid time the account has already. */
function paySurvey(user, survey) {
  const base = Math.max(Date.now(), user.grantedPaidUntil ?? 0)
  user.grantedPaidUntil = base + survey.rewardDays * DAY
}

// ── announcements (0.9.9 operator channel) ─────────────────────────────────

/**
 * The banners the operator is broadcasting.
 *
 * Two are seeded so a fresh install has something to show: one that names a settings
 * page, and one that is a link with the `notice` tone. `POST /mock/announcements`
 * pushes more, and every connected socket receives it as a `created` frame.
 */
const ANNOUNCEMENTS = [
  {
    id: 'mock-announcement-seed-1',
    title: '0.9.9 is on its way',
    body: 'The new settings directory and the live turn card are ready to try.',
    symbol: 'megaphone',
    tone: 'info',
    button: { label: 'Open notifications settings', target: 'notifications' },
    createdAt: Date.now() - 3_600_000,
    expiresAt: null,
    active: true,
  },
  {
    id: 'mock-announcement-seed-2',
    title: 'Read the release notes',
    body: 'Every change in this build, in one page.',
    symbol: 'sparkles',
    tone: 'notice',
    button: { label: 'Open the release page', target: 'https://github.com/ShirazuNagisa/DSH-Mizar/releases' },
    createdAt: Date.now() - 7_200_000,
    expiresAt: null,
    active: true,
  },
]

let announcementSequence = 0

/** Connected `/app/v1/announcements` sockets. */
const announcementSockets = new Set()

/** What is live right now, oldest first, as both the socket snapshot and the poll serve it. */
function liveAnnouncements() {
  const now = Date.now()
  return ANNOUNCEMENTS
    .filter((announcement) => announcement.active !== false)
    .filter((announcement) => announcement.expiresAt === null || announcement.expiresAt > now)
    .slice()
    .sort((left, right) => (left.createdAt ?? 0) - (right.createdAt ?? 0))
}

/** Build one banner from a mock POST body, or `null` when it is not one. */
function createAnnouncement(body) {
  const title = typeof body?.title === 'string' ? body.title.trim() : ''
  const text = typeof body?.body === 'string' ? body.body.trim() : ''
  if (title === '' || text === '') return null
  const rawButton = body?.button
  const button = typeof rawButton === 'object' && rawButton !== null
    && typeof rawButton.label === 'string' && typeof rawButton.target === 'string'
    ? { label: rawButton.label.trim(), target: rawButton.target.trim() }
    : null
  const expiresInMs = typeof body?.expiresInMs === 'number' ? body.expiresInMs : 7 * DAY
  return {
    id: `mock-announcement-${++announcementSequence}`,
    title,
    body: text,
    symbol: typeof body?.symbol === 'string' ? body.symbol : null,
    tone: typeof body?.tone === 'string' ? body.tone : 'info',
    button,
    createdAt: Date.now(),
    expiresAt: Date.now() + expiresInMs,
    active: body?.active !== false,
  }
}

// The WebSocket handshake and framing. Twenty lines of RFC 6455 rather than a
// dependency: this mock is one file on purpose, and the client only needs text
// frames down and pings up.

const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

/** One server frame; the server never masks. */
function wsFrame(opcode, payload) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload), 'utf8')
  const length = data.length
  let header
  if (length < 126) {
    header = Buffer.from([0x80 | opcode, length])
  } else if (length < 65_536) {
    header = Buffer.alloc(4)
    header[0] = 0x80 | opcode
    header[1] = 126
    header.writeUInt16BE(length, 2)
  } else {
    header = Buffer.alloc(10)
    header[0] = 0x80 | opcode
    header[1] = 127
    header.writeBigUInt64BE(BigInt(length), 2)
  }
  return Buffer.concat([header, data])
}

/** Parse whatever complete client frames arrived; the socket buffers between reads. */
function parseClientFrames(client, chunk) {
  client.buffer = Buffer.concat([client.buffer, chunk])
  const frames = []
  while (client.buffer.length >= 2) {
    const first = client.buffer[0]
    const second = client.buffer[1]
    const opcode = first & 0x0f
    const masked = (second & 0x80) !== 0
    let length = second & 0x7f
    let offset = 2
    if (length === 126) {
      if (client.buffer.length < 4) break
      length = client.buffer.readUInt16BE(2)
      offset = 4
    } else if (length === 127) {
      if (client.buffer.length < 10) break
      length = Number(client.buffer.readBigUInt64BE(2))
      offset = 10
    }
    if (length > 1_000_000) {
      client.buffer = Buffer.alloc(0)
      frames.push({ opcode: 0x8, payload: Buffer.alloc(0) })
      break
    }
    const maskLength = masked ? 4 : 0
    if (client.buffer.length < offset + maskLength + length) break
    let payload = client.buffer.subarray(offset + maskLength, offset + maskLength + length)
    if (masked) {
      const mask = client.buffer.subarray(offset, offset + 4)
      payload = Buffer.from(payload.map((byte, index) => byte ^ mask[index % 4]))
    }
    client.buffer = client.buffer.subarray(offset + maskLength + length)
    frames.push({ opcode, payload })
  }
  return frames
}

/** Refuse an upgrade in HTTP terms, so the client's WebSocket call fails cleanly. */
function rejectUpgrade(socket, status) {
  const reason = status === 401 ? 'Unauthorized' : status === 404 ? 'Not Found' : 'Bad Request'
  socket.write(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`)
  socket.destroy()
}

function broadcastAnnouncement(announcement) {
  const frame = wsFrame(0x1, JSON.stringify({ type: 'announcement', announcement }))
  for (const client of announcementSockets) {
    if (!client.socket.destroyed) client.socket.write(frame)
  }
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`)
  const path = url.pathname
  const method = request.method ?? 'GET'
  console.log(`${method} ${path}`)

  if (path === '/healthz') {
    send(response, 200, { ok: true, users: users.size, sessions: sessions.size })
    return
  }

  // ── the mock GitHub ───────────────────────────────────────────────────────
  if (path === '/mock-github/authorize') {
    const state = url.searchParams.get('state') ?? ''
    sendHtml(response, 200, `<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Mock GitHub</title>
<style>body{font:16px/1.5 -apple-system,sans-serif;padding:2rem;text-align:center}</style></head>
<body><h1>Mock GitHub</h1><p>Authorize <b>DSH-Mizar</b>?</p>
<p><a href="/account/v1/github/callback?code=mock-code&state=${encodeURIComponent(state)}">Authorize</a>
 · <a href="/account/v1/github/callback?error=access_denied&state=${encodeURIComponent(state)}">Cancel</a></p>
</body></html>`)
    return
  }

  // ── mock-only scripts for the operator channels ───────────────────────────
  if (method === 'GET' && path === '/mock/announcements') {
    send(response, 200, { announcements: ANNOUNCEMENTS })
    return
  }

  if (method === 'POST' && path === '/mock/announcements') {
    const body = await readBody(request)
    const announcement = createAnnouncement(body)
    if (announcement === null) {
      send(response, 400, { error: 'INVALID_INPUT', message: 'title and body are required' })
      return
    }
    ANNOUNCEMENTS.push(announcement)
    // Push it to whoever is listening: the socket is the path a real banner takes.
    broadcastAnnouncement(announcement)
    send(response, 200, { announcement })
    return
  }

  if (method === 'POST' && path === '/mock/surveys/reset') {
    surveyCompletions.clear()
    send(response, 200, { ok: true })
    return
  }

  if (!path.startsWith('/account/v1/')) {
    send(response, 404, { error: 'NOT_FOUND', message: 'no such endpoint' })
    return
  }
  const route = path.slice('/account/v1'.length).replace(/\/+$/, '') || '/'

  if (method === 'POST' && route === '/reset') {
    users.clear()
    sessions.clear()
    flows.clear()
    handoffs.clear()
    surveyCompletions.clear()
    for (const client of announcementSockets) client.socket.end()
    announcementSockets.clear()
    seed()
    send(response, 200, { ok: true })
    return
  }

  // Test-only: the browser leg is hard to drive on an emulator without a
  // signed-in Chrome, so a test can read the state the app just created and
  // call the callback itself.
  if (method === 'GET' && route === '/debug/flows') {
    send(response, 200, {
      flows: [...flows.entries()].map(([state, flow]) => ({
        state,
        userId: flow.userId,
        deviceId: flow.device.id,
        expiresAt: flow.expiresAt,
      })),
    })
    return
  }

  // ── GitHub flow ───────────────────────────────────────────────────────────
  if (method === 'POST' && route === '/github/start') {
    const body = await readBody(request)
    const device = deviceOf(body)
    if (device === null) {
      send(response, 400, { error: 'INVALID_INPUT', message: 'device is required' })
      return
    }
    const current = sessionOf(request)
    const state = randomBytes(16).toString('base64url')
    flows.set(state, {
      device,
      userId: current?.user.id ?? null,
      token: current?.token ?? null,
      expiresAt: Date.now() + 10 * 60_000,
    })
    send(response, 200, {
      authorizeUrl: `${PUBLIC}/mock-github/authorize?state=${encodeURIComponent(state)}`,
      state,
      expiresAt: Date.now() + 10 * 60_000,
    })
    return
  }

  if (method === 'GET' && route === '/github/callback') {
    const state = url.searchParams.get('state') ?? ''
    const flow = flows.get(state)
    if (flow === undefined || flow.expiresAt <= Date.now()) {
      flows.delete(state)
      response.writeHead(303, { location: '/account/v1/github/done?error=GITHUB_STATE_EXPIRED' })
      response.end()
      return
    }
    flows.delete(state)
    if (url.searchParams.get('error') !== null) {
      response.writeHead(303, { location: '/account/v1/github/done?error=denied' })
      response.end()
      return
    }

    let user = [...users.values()].find((candidate) => candidate.githubId === 'mock-1') ?? null
    if (user === null) {
      user = createUser({
        username: 'octocat',
        email: 'octo@example.com',
        passwordHash: null,
        githubId: 'mock-1',
        githubLogin: 'octocat',
        emailVerified: true,
      })
    }
    let token
    if (flow.userId !== null && flow.token !== null) {
      user = users.get(flow.userId) ?? user
      user.githubId = 'mock-1'
      user.githubLogin = 'octocat'
      token = flow.token
    } else {
      token = issueSession(user, flow.device).token
    }
    const handoff = newHandoff()
    handoffs.set(hash(handoff), { token, deviceId: flow.device.id, expiresAt: Date.now() + 5 * 60_000 })
    response.writeHead(303, { location: `/account/v1/github/done?handoff=${encodeURIComponent(handoff)}` })
    response.end()
    return
  }

  if (method === 'GET' && route === '/github/done') {
    const handoff = url.searchParams.get('handoff')
    const error = url.searchParams.get('error')
    sendHtml(response, handoff === null ? 400 : 200, donePage(handoff, error))
    return
  }

  if (method === 'POST' && route === '/github/exchange') {
    const body = await readBody(request)
    const device = deviceOf(body)
    const handoff = typeof body?.handoff === 'string' ? body.handoff.trim().toUpperCase() : ''
    const record = handoffs.get(hash(handoff))
    if (device === null || record === undefined) {
      send(response, 400, { error: 'GITHUB_HANDOFF_INVALID', message: 'that sign-in code is not valid' })
      return
    }
    if (record.expiresAt <= Date.now()) {
      handoffs.delete(hash(handoff))
      send(response, 400, { error: 'GITHUB_HANDOFF_EXPIRED', message: 'that sign-in code has expired' })
      return
    }
    if (record.deviceId !== device.id) {
      send(response, 400, { error: 'GITHUB_DEVICE_MISMATCH', message: 'finish signing in on the same phone' })
      return
    }
    handoffs.delete(hash(handoff))
    const session = sessions.get(record.token)
    const user = session === undefined ? undefined : users.get(session.userId)
    if (session === undefined || user === undefined) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'this session is not valid' })
      return
    }
    send(response, 200, { token: record.token, user: publicUser(user, device.id) })
    return
  }

  // ── password accounts ─────────────────────────────────────────────────────
  if (method === 'POST' && route === '/register') {
    const body = await readBody(request)
    const username = typeof body?.username === 'string' ? body.username.trim() : ''
    const email = typeof body?.email === 'string' ? body.email.trim() : ''
    const password = typeof body?.password === 'string' ? body.password : ''
    const device = deviceOf(body)
    if (username.length < 2 || username.length > 32 || /[\s@]/.test(username)) {
      send(response, 400, { error: 'INVALID_INPUT', message: 'usernames are 2-32 characters' })
      return
    }
    if (!email.includes('@') || password.length < 8 || password.length > 128 || device === null) {
      send(response, 400, { error: 'INVALID_INPUT', message: 'that email or password is not valid' })
      return
    }
    if ([...users.values()].some((user) => user.usernameKey === username.toLowerCase())) {
      send(response, 409, { error: 'USERNAME_TAKEN', message: 'that username is already in use' })
      return
    }
    if ([...users.values()].some((user) => user.emailKey === email.toLowerCase())) {
      send(response, 409, { error: 'EMAIL_TAKEN', message: 'that email is already registered' })
      return
    }
    // This mock behaves like a server with mail configured: the account is not
    // created until the code from `/register/start` comes back. The client tries
    // the one-step call first and lands here with 428, which is the path worth
    // exercising.
    const code = typeof body?.code === 'string' ? body.code.trim() : ''
    const issuedCode = resetCodes.get(email.toLowerCase())
    if (code === '') {
      send(response, 428, { error: 'EMAIL_VERIFICATION_REQUIRED', message: 'request a code with /register/start and send it as "code"' })
      return
    }
    if (issuedCode === undefined || issuedCode.code !== code || issuedCode.expiresAt < Date.now()) {
      send(response, 401, { error: 'VERIFICATION_INVALID', message: 'that code is not right' })
      return
    }
    resetCodes.delete(email.toLowerCase())
    const user = createUser({ username, email, passwordHash: hash(password), emailVerified: true })
    const issued = issueSession(user, device)
    send(response, 200, {
      token: issued.token,
      user: publicUser(user, device.id),
      ...(issued.notice === undefined ? {} : { notice: issued.notice }),
    })
    return
  }

  // ── password reset ────────────────────────────────────────────────────────
  if (method === 'POST' && route === '/password/reset/start') {
    const body = await readBody(request)
    const email = typeof body?.email === 'string' ? body.email.trim() : ''
    const expiresAt = Date.now() + 10 * 60_000
    // The real service answers the same way for an address it does not know, and
    // so does this one: otherwise the screen's copy ("if that address has an
    // account") would be a lie the UI tells on the mock's behalf.
    if (email.includes('@')) resetCodes.set(email.toLowerCase(), { code: '123456', expiresAt })
    send(response, 200, { expiresAt, resendAfterSeconds: 60 })
    return
  }

  if (method === 'POST' && route === '/password/reset/complete') {
    const body = await readBody(request)
    const email = typeof body?.email === 'string' ? body.email.trim() : ''
    const code = typeof body?.code === 'string' ? body.code.trim() : ''
    const password = typeof body?.password === 'string' ? body.password : ''
    const device = deviceOf(body)
    const issued = resetCodes.get(email.toLowerCase())
    const user = findByIdentifier(email)
    if (issued === undefined || issued.code !== code || issued.expiresAt < Date.now()) {
      send(response, 401, { error: 'INVALID_CODE', message: 'that code is not right' })
      return
    }
    if (user === null || password.length < 8 || password.length > 128 || device === null) {
      send(response, 400, { error: 'INVALID_INPUT', message: 'that password is not valid' })
      return
    }
    user.passwordHash = hash(password)
    resetCodes.delete(email.toLowerCase())
    const session = issueSession(user, device)
    send(response, 200, {
      token: session.token,
      user: publicUser(user, device.id),
      ...(session.notice === undefined ? {} : { notice: session.notice }),
    })
    return
  }

  if (method === 'POST' && route === '/register/start') {
    const body = await readBody(request)
    const username = typeof body?.username === 'string' ? body.username.trim() : ''
    const email = typeof body?.email === 'string' ? body.email.trim() : ''
    const password = typeof body?.password === 'string' ? body.password : ''
    if (username.length < 2 || username.length > 32 || /[\s@]/.test(username)
      || !email.includes('@') || password.length < 8 || password.length > 128) {
      send(response, 400, { error: 'INVALID_INPUT', message: 'that username, email or password is not valid' })
      return
    }
    if ([...users.values()].some((user) => user.usernameKey === username.toLowerCase())) {
      send(response, 409, { error: 'USERNAME_TAKEN', message: 'that username is already in use' })
      return
    }
    if ([...users.values()].some((user) => user.emailKey === email.toLowerCase())) {
      send(response, 409, { error: 'EMAIL_TAKEN', message: 'that email is already registered' })
      return
    }
    const expiresAt = Date.now() + 10 * 60_000
    resetCodes.set(email.toLowerCase(), { code: '123456', expiresAt })
    send(response, 200, { email, expiresAt, resendAfterSeconds: 60 })
    return
  }

  if (method === 'POST' && route === '/login') {
    const body = await readBody(request)
    const identifier = typeof body?.identifier === 'string' ? body.identifier : ''
    const password = typeof body?.password === 'string' ? body.password : ''
    const device = deviceOf(body)
    const user = findByIdentifier(identifier)
    if (user === null || user.passwordHash !== hash(password) || device === null) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'identifier or password is not right' })
      return
    }
    const issued = issueSession(user, device)
    send(response, 200, {
      token: issued.token,
      user: publicUser(user, device.id),
      ...(issued.notice === undefined ? {} : { notice: issued.notice }),
    })
    return
  }

  if (method === 'GET' && route === '/desktops') {
    const current = sessionOf(request)
    if (current === null) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'a session token is required' })
      return
    }
    send(response, 200, {
      desktops: [
        {
          deviceId: 'mock-desktop-1',
          name: 'Mock Studio',
          model: 'Windows 11',
          platform: 'windows',
          online: true,
          room: 'MOCKROOM1',
        },
        {
          deviceId: 'mock-desktop-2',
          name: 'Mock Mac mini',
          model: 'Mac mini',
          platform: 'macos',
          online: false,
        },
      ],
    })
    return
  }

  if (method === 'POST' && route === '/connect') {
    const current = sessionOf(request)
    if (current === null) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'a session token is required' })
      return
    }
    const body = await readBody(request).catch(() => null)
    const deviceId = typeof body?.deviceId === 'string' ? body.deviceId : ''
    if (deviceId === 'mock-desktop-2') {
      send(response, 409, { error: 'DESKTOP_OFFLINE', message: 'the computer is not connected' })
      return
    }
    if (deviceId !== 'mock-desktop-1') {
      send(response, 404, { error: 'DESKTOP_NOT_FOUND', message: 'no such computer on this account' })
      return
    }
    desktopTokens += 1
    desktopConnects.push({ deviceId, engine: body?.engine ?? null })
    send(response, 200, { room: 'MOCKROOM1', token: `aco_mock_${desktopTokens}` })
    return
  }

  if (method === 'GET' && route === '/me') {
    if (url.searchParams.get('fail') === 'revoked') {
      send(response, 401, { error: 'SESSION_REVOKED', message: 'this phone was signed out' })
      return
    }
    const current = sessionOf(request)
    if (current === null) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'a session token is required' })
      return
    }
    const deviceId = request.headers['x-mizar-client-id']
    send(response, 200, { user: publicUser(current.user, typeof deviceId === 'string' ? deviceId : null) })
    return
  }

  if (method === 'POST' && route === '/logout') {
    const token = bearer(request)
    if (token !== null) sessions.delete(token)
    send(response, 200, { ok: true })
    return
  }

  if (method === 'POST' && route === '/delete') {
    const body = await readBody(request)
    if (body?.confirmation !== 'DELETE') {
      send(response, 400, { error: 'INVALID_INPUT', message: 'send confirmation: "DELETE"' })
      return
    }
    const current = sessionOf(request)
    if (current === null) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'a session token is required' })
      return
    }
    users.delete(current.user.id)
    for (const [token, session] of sessions) if (session.userId === current.user.id) sessions.delete(token)
    send(response, 200, { ok: true })
    return
  }

  if (method === 'POST' && route === '/invite/redeem') {
    const current = sessionOf(request)
    if (current === null) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'a session token is required' })
      return
    }
    const body = await readBody(request)
    const code = typeof body?.code === 'string' ? body.code.trim().toUpperCase() : ''
    if (code !== 'MOCK-INVITE') {
      send(response, 400, { error: 'INVITE_INVALID', message: 'that invite code is not valid' })
      return
    }
    const base = Math.max(Date.now(), current.user.grantedPaidUntil ?? 0)
    current.user.grantedPaidUntil = base + 30 * DAY
    send(response, 200, { user: publicUser(current.user, current.session.deviceId) })
    return
  }

  // ── announcements: the polling twin of the socket ─────────────────────────
  if (method === 'GET' && route === '/announcements') {
    if (sessionOf(request) === null) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'a session token is required' })
      return
    }
    send(response, 200, { announcements: liveAnnouncements() })
    return
  }

  // ── surveys ───────────────────────────────────────────────────────────────
  if (method === 'GET' && route === '/surveys') {
    const current = sessionOf(request)
    if (current === null) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'a session token is required' })
      return
    }
    send(response, 200, { surveys: surveysFor(current.user) })
    return
  }

  if (method === 'POST' && route === '/surveys/submit') {
    const current = sessionOf(request)
    if (current === null) {
      send(response, 401, { error: 'UNAUTHENTICATED', message: 'a session token is required' })
      return
    }
    const body = await readBody(request)
    const survey = SURVEYS.find((candidate) => candidate.id === body?.surveyId)
    if (survey === undefined) {
      send(response, 404, { error: 'SURVEY_NOT_FOUND', message: 'no such questionnaire' })
      return
    }
    if (survey.active === false || isExpired(survey)) {
      send(response, 410, { error: 'SURVEY_UNAVAILABLE', message: 'this questionnaire is closed' })
      return
    }
    if (surveyCompletions.has(`${current.user.id}:${survey.id}`)) {
      send(response, 409, { error: 'SURVEY_ALREADY_DONE', message: 'this account already answered it' })
      return
    }
    // `validAnswers` returns the map when the shape is right; an empty map is a
    // valid answer only for a survey with no required questions.
    if (validAnswers(survey, body?.answers) === null) {
      send(response, 400, { error: 'SURVEY_INVALID_ANSWERS', message: 'some answers were not accepted' })
      return
    }
    surveyCompletions.set(`${current.user.id}:${survey.id}`, {
      completedAt: Date.now(),
      answers: body?.answers ?? [],
    })
    paySurvey(current.user, survey)
    // The relay answers with the profile the reward was just applied to.
    send(response, 200, {
      user: publicUser(current.user, current.session.deviceId),
      rewardDays: survey.rewardDays,
    })
    return
  }

  send(response, 404, { error: 'NOT_FOUND', message: 'no such account endpoint' })
})

/**
 * The announcement socket.
 *
 * The relay serves it on the relay *origin* (`/app/v1/announcements`), not under
 * `/account/v1`, because a WebSocket upgrade cannot be proxied by the account API's
 * forwarder. Auth is the session token in `x-mizar-session`; the query fallback
 * exists only so `--selftest` and curl can drive it without custom headers.
 *
 * A fresh connection gets a `snapshot` of everything live; `POST /mock/announcements`
 * then pushes each new one as an `announcement` frame.
 */
server.on('upgrade', (request, socket) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`)
  if (url.pathname !== '/app/v1/announcements') {
    rejectUpgrade(socket, 404)
    return
  }
  const headerToken = request.headers['x-mizar-session']
  const token = (typeof headerToken === 'string' && headerToken !== '' ? headerToken : '')
    || (url.searchParams.get('token') ?? '')
  const session = sessions.get(token)
  if (session === undefined || session.revoked) {
    rejectUpgrade(socket, 401)
    return
  }
  const key = request.headers['sec-websocket-key']
  if (typeof key !== 'string' || request.headers['sec-websocket-version'] !== '13') {
    rejectUpgrade(socket, 400)
    return
  }
  const accept = createHash('sha1').update(`${key}${WS_GUID}`, 'utf8').digest('base64')
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n'
    + 'Upgrade: websocket\r\n'
    + 'Connection: Upgrade\r\n'
    + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
  )
  console.log('WS /app/v1/announcements connected')
  socket.write(wsFrame(0x1, JSON.stringify({ type: 'snapshot', announcements: liveAnnouncements() })))
  const client = { socket, token, userId: session.userId, buffer: Buffer.alloc(0) }
  announcementSockets.add(client)
  const forget = () => {
    announcementSockets.delete(client)
    console.log('WS /app/v1/announcements closed')
  }
  socket.on('data', (chunk) => {
    for (const frame of parseClientFrames(client, chunk)) {
      if (frame.opcode === 0x8) {
        socket.end()
        return
      }
      // Ping: answer with the same payload. Anything else (text/binary) is not part
      // of this protocol and is ignored, as the client ignores unknown frames.
      if (frame.opcode === 0x9) socket.write(wsFrame(0xA, frame.payload))
    }
  })
  socket.on('close', forget)
  socket.on('error', forget)
})

function seed() {
  const user = createUser({
    username: 'shirazu',
    email: 'shirazu@example.com',
    passwordHash: hash('mizar-test-password'),
    emailVerified: true,
  })
  void user
}

// ── self test ──────────────────────────────────────────────────────────────

/** One JSON request against the mock, with the token when there is one. */
async function json(url, { method = 'GET', token = null, body } = {}) {
  const headers = {}
  if (token !== null) headers.authorization = `Bearer ${token}`
  if (body !== undefined) headers['content-type'] = 'application/json'
  const response = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const parsed = await response.json().catch(() => null)
  return { status: response.status, body: parsed }
}

/** Resolve with the next WebSocket message, or reject after `timeoutMs`. */
function nextSocketMessage(socket, timeoutMs = 4_000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.removeEventListener('message', onMessage)
      reject(new Error('timed out waiting for a socket frame'))
    }, timeoutMs)
    function onMessage(event) {
      clearTimeout(timer)
      socket.removeEventListener('message', onMessage)
      resolve(JSON.parse(String(event.data)))
    }
    socket.addEventListener('message', onMessage)
  })
}

/**
 * Walk the whole 0.9.9 operator channel against this very process: login, the
 * surveys list, a submitted reward, the already-done refusal, the polling twin and
 * the socket's snapshot + created frames.
 *
 *     node scripts/account-mock.mjs --selftest
 */
async function selftest() {
  const failures = []
  const check = (name, ok, detail = '') => {
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok || detail === '' ? '' : ` — ${detail}`}`)
    if (!ok) failures.push(name)
  }
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const port = server.address().port
  const origin = `http://127.0.0.1:${port}`
  const account = `${origin}/account/v1`
  const device = {
    id: 'selftest-device',
    name: 'Self test',
    model: 'node',
    platform: 'android',
    appVersion: '0.0.0',
  }
  let socket = null
  try {
    const login = await json(`${account}/login`, {
      method: 'POST',
      body: { identifier: 'shirazu', password: 'mizar-test-password', device },
    })
    check('login', login.status === 200 && typeof login.body?.token === 'string')
    const token = login.body?.token
    if (typeof token !== 'string') throw new Error('no session token')

    const me = await json(`${account}/me`, { token })
    check('me', me.status === 200 && me.body?.user?.username === 'shirazu')

    const list = await json(`${account}/surveys`, { token })
    const surveys = list.body?.surveys ?? []
    check('surveys list', list.status === 200 && surveys.length === 3)
    const survey = surveys.find((candidate) => candidate.id === 'survey-turn-cycle')
    check(
      'three question types',
      survey !== undefined && survey.questions.map((question) => question.type).join(',') === 'single,multi,text',
    )
    check('completion starts false', survey?.completed === false)

    const invalid = await json(`${account}/surveys/submit`, {
      method: 'POST',
      token,
      body: { surveyId: 'survey-turn-cycle', answers: [{ questionId: 'q1', value: { nested: true } }] },
    })
    check('invalid answers rejected', invalid.status === 400 && invalid.body?.error === 'SURVEY_INVALID_ANSWERS')

    const missingRequired = await json(`${account}/surveys/submit`, {
      method: 'POST',
      token,
      body: { surveyId: 'survey-turn-cycle', answers: [{ questionId: 'q1', value: 'o2' }] },
    })
    check('required answers enforced', missingRequired.status === 400)

    const answers = [
      { questionId: 'q1', value: 'o2' },
      { questionId: 'q2', value: ['m1', 'm2'] },
      { questionId: 'q3', value: 'selftest' },
    ]
    const submit = await json(`${account}/surveys/submit`, {
      method: 'POST',
      token,
      body: { surveyId: 'survey-turn-cycle', answers },
    })
    check(
      'submit pays the reward',
      submit.status === 200 && submit.body?.rewardDays === 7 && submit.body?.user?.plan === 'paid',
    )

    const again = await json(`${account}/surveys/submit`, {
      method: 'POST',
      token,
      body: { surveyId: 'survey-turn-cycle', answers },
    })
    check('one answer per account', again.status === 409 && again.body?.error === 'SURVEY_ALREADY_DONE')

    const unknown = await json(`${account}/surveys/submit`, {
      method: 'POST',
      token,
      body: { surveyId: 'no-such-survey', answers: [] },
    })
    check('unknown survey', unknown.status === 404 && unknown.body?.error === 'SURVEY_NOT_FOUND')

    const after = await json(`${account}/surveys`, { token })
    check(
      'completion is remembered',
      after.body?.surveys?.find((candidate) => candidate.id === 'survey-turn-cycle')?.completed === true,
    )
    check(
      'closed survey is still listed',
      after.body?.surveys?.find((candidate) => candidate.id === 'survey-archive')?.active === false,
    )

    const poll = await json(`${account}/announcements`, { token })
    check('polling announcements', poll.status === 200 && (poll.body?.announcements?.length ?? 0) >= 2)

    const unauthorised = await json(`${account}/surveys`)
    check('surveys need a session', unauthorised.status === 401)

    // The socket. Node's built-in WebSocket cannot set custom headers here, which is
    // why the mock accepts the token as a query parameter for tests.
    socket = new WebSocket(`ws://127.0.0.1:${port}/app/v1/announcements?token=${encodeURIComponent(token)}`)
    const snapshot = await nextSocketMessage(socket)
    check('socket snapshot', snapshot.type === 'snapshot' && snapshot.announcements.length >= 2)

    const createdFrame = nextSocketMessage(socket)
    const created = await json(`${origin}/mock/announcements`, {
      method: 'POST',
      body: {
        title: 'Self test banner',
        body: 'Created by --selftest.',
        tone: 'notice',
        button: { label: 'Open about', target: 'about' },
      },
    })
    check('mock create announcement', created.status === 200 && created.body?.announcement?.id !== undefined)
    const pushed = await createdFrame
    check(
      'socket created frame',
      pushed.type === 'announcement' && pushed.announcement?.title === 'Self test banner',
    )
  } catch (error) {
    console.error(`FAIL selftest threw — ${error instanceof Error ? error.message : error}`)
    failures.push('exception')
  } finally {
    if (socket !== null) socket.close()
    await new Promise((resolve) => server.close(resolve))
  }
  if (failures.length > 0) {
    console.error(`\n${failures.length} check(s) failed: ${failures.join(', ')}`)
    process.exitCode = 1
    return
  }
  console.log('\nall checks passed')
}

seed()
if (args.includes('--selftest')) {
  await selftest()
} else {
  server.listen(PORT, HOST, () => {
    console.log(`account mock on http://${HOST}:${PORT}  (emulator: ${PUBLIC})`)
    console.log('account: shirazu / mizar-test-password   invite: MOCK-INVITE')
    console.log(`announcements: ws://${HOST}:${PORT}/app/v1/announcements (POST /mock/announcements to push one)`)
  })
}
