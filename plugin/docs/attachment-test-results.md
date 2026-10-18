# Host attachment validation — 0.8.3-attachments.1

Validated locally on Windows, 2026-10-18 (Asia/Shanghai). All writes and dependency installation were confined to the plugin workspace; no running DSH profile was installed into or restarted by this task.

## Commands and final outcomes

| Check | Result |
| --- | --- |
| `npm run build` | PASS: host TypeScript and browser bundle generated |
| `npm run typecheck` | PASS: host and client |
| `npm test -- --maxWorkers=2 --reporter=default --reporter=json --outputFile=attachment-tests.json` | PASS: **587/587 tests, 29/29 files**, 40.64 s |
| `git -c core.safecrlf=false diff --check -- .` | PASS |

The command above regenerates the machine-readable Vitest output locally. Raw reports are not committed because they can contain machine-specific absolute paths; the reviewed summary is recorded here.

## New coverage (84 tests)

- `attachments.test.ts`: 61 storage/security tests including private Windows ACL setup, path-safe names, exact byte reservations, canonical base64, sequential and duplicate chunk handling, simultaneous calls, streamed/on-disk integrity, MIME signatures, foreign device/session references, resource ceilings, expiry, timer cleanup, abort/read races, shutdown and file tampering.
- `attachment-bridge.test.ts`: 17 tests for true uploadStream argument shape, returned receipts only, mixed image/file content, attach-only prompts, original text semantics including >100 keyed text prompts, stable replay IDs and content conflict rejection, failed/uncertain/partial admission retry, DSH model rejection, successful staging release, and original Android turn.settled preservation.
- `attachment-socket.test.ts`: 4 real-loopback-WebSocket tests, using temporary state and a fake DSH service boundary: unauthenticated rejection/no new public HTTP routes; 70 KiB sequential upload with reconnect/duplicate chunk/foreign-device attempt and exactly one prompt submission; inbound frame limit; live revocation.
- `attachment-composition.test.ts`: 2 actual Cordis 4 Context tests for plugin mounting and optional service lookup, with and without attachment services. This specifically catches the unsupported `{required,optional}` inject shape and undeclared direct property lookup.

## Fixes discovered during validation

1. Existing method-table golden test was updated to include the five additive RPCs.
2. Windows PowerShell Security module loading was unreliable on this machine; private ACL setup now uses built-in .NET DirectoryInfo APIs directly, without module import or parent-directory changes.
3. Actual Cordis accepts arrays or service-name maps, not `{required,optional}`. Static injection remains the original array; optional services use documented `ctx.get`.
4. Ordinary text-only requests bypass the attachment replay quota, retaining old optional-field behavior. Existing attached request IDs still reject text-only content mismatches.
5. Accepted prompts free local staging and reservations immediately after recording acceptance; failed/uncertain prompts retain both verified uploads and any created DSH receipts. Three consecutive five-file sends plus replay are tested.

## Evidence boundary

The suite proves local storage/bridge/socket behavior, not a model's image understanding. DSH image decoding and model admission are intentionally delegated to DSH. The Team Lead separately runs real DSH + original local relay integration in an isolated profile (including a 20 MiB file and mixed five-attachment payload); those results belong in the Lead's end-to-end report.
