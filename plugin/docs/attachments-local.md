# Private local attachment build — 0.8.3-attachments.1

This is a local evaluation extension, not an upstream Mizar release. The package name stays `dsh-mizar` for explicit replacement/rollback; do not publish this build. Existing plugin MIT notices are preserved. This does not assign a license to the Android app/mizarkit source.

## Wire and DSH integration

The parent `ATTACHMENT-PROTOCOL.md` is the mobile/host contract. All five `attachment.*` methods use the existing authenticated WebSocket; there are no new HTTP routes. Text-only prompts retain the original DSH text part. Both original Android `turn.settled` notifications (live broadcast and missed-completion replay) are retained.

- `attachment.begin` reserves bytes before opening an exclusive random staging file. It accepts no host path. Display names are safe leaf names of at most 240 UTF-8 bytes; Windows reserved names, separators, control/bidi characters and invalid filename characters are rejected. Android sanitizes names before this call.
- Chunks use canonical padded base64, max 65,536 decoded bytes, with exact sequential offsets. Retrying only the last acknowledged identical chunk is safe, including simultaneous/reconnected retries. Conflicts and older offsets fail.
- `finish` verifies declared/actual length, streamed and on-disk SHA-256, and image magic bytes. Ready metadata has a separate random attachment ID. Retried finish returns the same metadata. Ready bytes are hashed again while reading for DSH, detecting on-disk changes.
- Files stream through the **actual** `ctx.fileUploads.uploadStream({sessionId,data,signal,name})` API. Only its returned receipt enters `{type:'file',receiptId}` prompt parts; receipts and local paths are never accepted from the phone or returned to it.
- Images enter `{type:'image',mediaType,data,name}` prompt parts. DSH remains responsible for complete decoding, normalization, pixel limits and the selected model's image support. Magic checks do not purport to decode an image. Default image size is 20 MiB and is lowered to the composed DSH attachment service's limit.
- Cordis 4 static injection retains the original service-name array; optional `fileUploads` / `attachments` are read using Cordis's documented `ctx.get` API. This version of Cordis does not accept `{required,optional}` dependency maps. Tests mount the declaration in a real Cordis Context, both with and without the optional services.
- DSH errors are propagated; `session/attachment-invalid` becomes `ATTACHMENT_INVALID`. A failed prompt does not delete verified uploads. Once DSH accepts, local staging is released immediately (DSH already owns durable data), so successive five-attachment sends do not fill the ten-upload device quota. Accepted retries are answered from the admission ledger, without needing the deleted staging. The client must keep the draft and stable request ID on failure; it must not infer success from a finished upload alone.

## Resource and authorization boundaries

- Private per-mount random temporary directory. POSIX: mode 0700, files 0600. Windows: an OS Windows PowerShell child (no profile, no shell expansion or downloaded script) sets a protected directory ACL granting only the current user's SID, inherited by its files. It touches only the directory just created, never its parent or global TEMP. If ACL setup fails, startup fails closed before storing any attachment bytes. Administrative/OS control of the user's process is outside this boundary.
- Random exclusive filenames and retained file handles; a phone-supplied name is never used as a path. Documents are never automatically opened or executed.
- Maximum 20 MiB/file, 20 MiB/image (or lower DSH image limit), five distinct attachments/prompt, 100 active uploads globally, ten/device, 200 MiB aggregate reserved bytes. Ready uploads count toward these limits too.
- Upload age is absolute: 30 minutes from begin, not extended by chunks or retries. A minute timer removes expired incomplete and ready files. Abort is serialized and idempotent for unknown IDs; foreign existing IDs cannot be modified. Normal unload cancels streaming and removes this mount's directory. Failed deletion retains the reservation until cleanup succeeds.
- Uploads and ready references are scoped to the authenticated device and original session, not a client-provided owner. They survive socket reconnect but not process restart. No arbitrary filesystem RPC exists.
- Per-upload locking covers chunk/finish/abort/read/cleanup. Multiple-file lock order is deterministic. Pending begins and per-upload operations are bounded. The WebSocket limits input to 512 KiB and eight outstanding methods per socket; attachment chunks fit comfortably.
- Attached prompts require `requestId`. The 30-minute bounded admission ledger binds it to device, session, exact text, attachment order, mode and timezone. Concurrent identical requests share one admission, completed ones reuse acceptance, conflicting content is rejected. Failed/uncertain delivery reuses its cached DSH file receipts and the same request ID. Files are cached per prompt admission, not shared as one bound receipt across different prompt requests. The ledger caps at 1,000 global / 100 per device for attached prompts. Ordinary text prompts bypass it (preserving original optional fields and throughput), but a text retry that removes attachments from an existing attached request ID is rejected.
- Only two attached admissions globally and one/device execute simultaneously, bounding the base64 materialization required by DSH's image prompt API.

## Lifecycle caveats

The staging directory and admission ledger are intentionally ephemeral. A hard process kill/power loss cannot run cleanup; a private orphan directory may remain in the OS temporary directory. No broad startup deletion of other instances' temporary files is attempted. Graceful plugin unload and TTL cleanup are covered by tests. Restarted clients must upload again rather than retry stale attachment IDs; they must not invent a new prompt request ID after uncertain delivery. DSH's durable prompt idempotency remains the final protection once a request has reached its inbox/history.

The bridge owns local staging, not DSH's durable attachment/receipt lifecycle. `uploadStream` offers no public delete/retire-upload operation; once admitted there, retention is owned by DSH. The plugin never fabricates or manually deletes DSH's storage files.

## Reproduction and evidence

From the plugin directory:

```bash
npm install --ignore-scripts --no-audit --no-fund --proxy=socks5://127.0.0.1:10808 --https-proxy=socks5://127.0.0.1:10808 --registry=https://registry.npmjs.org/
npm run build
npm run typecheck
npm test -- --maxWorkers=2
```

The initial install was isolated in this plugin directory; it did not install to or restart any live DSH profile. Build output under `lib/` is intentionally tracked by the upstream repository. Tests use temporary directories and loopback listeners on ephemeral ports, not the running DSH GUI.

New tests cover malformed/unsafe names and metadata, canonical base64, chunk boundaries/retries/concurrency, limits, TTL/cleanup/disposal, complete and incomplete finish, hash failures and tampering, four image signatures/MIME spoofing, ownership, abort/read races, ordered mixed DSH content, attachment-only sends, receipt caching after partial failure, replay/conflict handling, model rejection, old text behavior, authenticated real-socket upload/reconnect, revocation and transport bounds. A real DSH offline-model end-to-end test is separately coordinated by the Team Lead in an isolated profile; the plugin's fake-boundary tests are not claimed as proof of a live model call.
