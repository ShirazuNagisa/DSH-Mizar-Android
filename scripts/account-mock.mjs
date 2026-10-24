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

  send(response, 404, { error: 'NOT_FOUND', message: 'no such account endpoint' })
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

seed()
server.listen(PORT, HOST, () => {
  console.log(`account mock on http://${HOST}:${PORT}  (emulator: ${PUBLIC})`)
  console.log('account: shirazu / mizar-test-password   invite: MOCK-INVITE')
})
