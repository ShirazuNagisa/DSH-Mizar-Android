#!/usr/bin/env node
/**
 * Rebuild `plugin/` from a local DSH-Mizar checkout, plus this repo's patches.
 *
 * `plugin/` is a mirror rather than a dependency, so a sync is: take upstream's
 * tracked tree at HEAD, lay it down here, re-apply the deltas Android needs, and
 * prove the result still builds. Doing that by hand is four steps people skip
 * one of; this is the four steps.
 *
 * The deltas are `scripts/patches/*.patch` against the plugin's own paths, so
 * they are reviewable in a diff and cannot silently rot into a hand edit.
 *
 * Usage:
 *   node scripts/sync-plugin.mjs [source] [--no-build] [--no-test] [--client]
 *
 * `source` defaults to `$DSH_MIZAR_SOURCE`, then `../DSH-Mizar` beside this
 * repo. It must be a git checkout, and it is never modified: the mirror is
 * built from `git archive HEAD`, so an uncommitted edit in the checkout is
 * reported and ignored rather than half-copied.
 *
 * The browser half (`lib/client.js`) is not rebuilt by default: upstream
 * commits its build output, the deltas never touch it, and rebuilding it here
 * only rewrites a comment that embeds the absolute path of `node_modules`.
 */

import { execFileSync, spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)))
const PLUGIN = join(ROOT, 'plugin')
const PATCHES = join(ROOT, 'scripts', 'patches')

const argv = process.argv.slice(2)
const flags = argv.filter((arg) => arg.startsWith('--'))
const positional = argv.filter((arg) => !arg.startsWith('--'))
const source = resolve(
  positional[0] ?? process.env['DSH_MIZAR_SOURCE'] ?? join(ROOT, '..', 'DSH-Mizar'),
)
const build = !flags.includes('--no-build')
const test = build && !flags.includes('--no-test')
const withClient = flags.includes('--client')

/** Run a command, inheriting stdout/stderr, and fail loudly. */
function run(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: 'inherit', ...options })
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} exited ${result.status ?? result.signal}`)
  }
}

/** Run a command and return its trimmed stdout. */
function capture(command, args, options = {}) {
  return execFileSync(command, args, { encoding: 'utf8', ...options }).trim()
}

function git(args, options = {}) {
  return capture('git', args, { cwd: source, ...options })
}

function fail(message) {
  console.error(`sync-plugin: ${message}`)
  process.exit(1)
}

if (!existsSync(join(source, '.git'))) fail(`${source} is not a git checkout`)

const sha = git(['rev-parse', '--short', 'HEAD'])
const subject = git(['log', '-1', '--pretty=%s'])
const dirty = git(['status', '--porcelain'])
if (dirty !== '') {
  // Not fatal: the archive is HEAD, so the mirror stays reproducible. Saying so
  // is the difference between "the mirror is stale" and "you forgot to commit".
  console.warn(`sync-plugin: ${source} has uncommitted changes; mirroring HEAD (${sha}) anyway`)
}

console.log(`sync-plugin: ${source} at ${sha} — ${subject}`)

// 1. Take the tracked tree. `git archive` is the one copy of upstream that
//    cannot include build leftovers or a stale `node_modules`.
const staging = mkdtempSync(join(tmpdir(), 'dsh-mizar-plugin-'))
const tarball = join(staging, 'plugin.tar')
try {
  run('git', ['archive', '--format=tar', '-o', tarball, 'HEAD'], { cwd: source })

  // 2. Replace everything except the local dependency link.
  if (existsSync(PLUGIN)) {
    for (const entry of readdirSync(PLUGIN)) {
      if (entry === 'node_modules') continue
      rmSync(join(PLUGIN, entry), { recursive: true, force: true })
    }
  } else {
    throw new Error('plugin/ is missing; create it first')
  }
  run('tar', ['-xf', tarball, '-C', PLUGIN])

  // 3. Re-apply the Android-side deltas.
  const patches = existsSync(PATCHES)
    ? readdirSync(PATCHES).filter((name) => name.endsWith('.patch')).sort()
    : []
  for (const patch of patches) {
    const file = join(PATCHES, patch)
    // `core.autocrlf` is on for most Windows checkouts, and it would rewrite the
    // patched file with CRLF endings while the archive kept LF -- a tree that
    // diffs as empty and still shows up in `git status`. Pinned to LF for both.
    const eol = ['-c', 'core.autocrlf=false', '-c', 'core.eol=lf']
    // Checked first so a patch upstream has outgrown fails before it writes a
    // half-applied tree.
    run('git', [...eol, 'apply', '--directory=plugin', '--check', file], { cwd: ROOT })
    run('git', [...eol, 'apply', '--directory=plugin', file], { cwd: ROOT })
    console.log(`sync-plugin: applied ${patch}`)
  }

  // 4. Prove it. The deltas change `src/index.ts`, so `lib/` has to be rebuilt
  //    for them to reach a host that loads the checkout without a build step.
  if (build) {
    const pluginModules = join(PLUGIN, 'node_modules')
    if (!existsSync(join(pluginModules, 'typescript'))) {
      fail('plugin/node_modules is missing; install its dependencies, or pass --no-build')
    }
    run(process.execPath, [join(pluginModules, 'typescript', 'bin', 'tsc'), '-p', 'tsconfig.json'], {
      cwd: PLUGIN,
    })
    if (withClient) run(process.execPath, ['client/build.mjs'], { cwd: PLUGIN })
  }
  if (test) {
    run(process.execPath, [join(PLUGIN, 'node_modules', 'vitest', 'vitest.mjs'), 'run'], {
      cwd: PLUGIN,
    })
  }

  // 5. What a human still has to do: read the diff and commit it. Staging first
  //    because a replaced tree leaves every file's stat stale, and `git status`
  //    would then report a hundred files that are byte-identical to what is
  //    committed -- a review nobody can trust is a review nobody reads.
  capture('git', ['add', '-A', '--', 'plugin'], { cwd: ROOT })
  const changed = capture('git', ['diff', '--cached', '--stat', '--', 'plugin'], { cwd: ROOT })
  console.log(
    changed === ''
      ? 'sync-plugin: plugin/ already matched upstream'
      : `sync-plugin: plugin/ rebuilt and staged; review and commit:\n${changed}`,
  )
  if (patches.length > 0) console.log(`sync-plugin: ${patches.length} patch(es) applied on top`)
  console.log('sync-plugin: the host loads plugin/ on its next restart')
  // Cheap and it catches a bad extraction or a compiler that silently wrote nothing.
  if (build && !existsSync(join(PLUGIN, 'lib', 'index.js'))) fail('lib/index.js was not produced')
} catch (error) {
  fail(error instanceof Error ? error.message : String(error))
} finally {
  rmSync(staging, { recursive: true, force: true })
}
