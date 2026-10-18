# Local attachment extension v1

This is an additive Mizar v1 extension for an unofficial, side-by-side Android build. Existing text-only clients remain compatible. All calls require the existing authenticated WebSocket. No new public HTTP routes. No filesystem paths or DSH receipt IDs are accepted from the phone.

## RPC contract (frame v=1, type=method, id=request, payload=object)

- `attachment.capabilities {}` -> `{version:1,maxFileBytes:20971520,maxImageBytes:20971520,maxAttachments:5,chunkBytes:65536,expiresSeconds:1800,imageMediaTypes:["image/png","image/jpeg","image/webp","image/gif"]}`. Host may lower image limit to DSH limit. Client must respect advertised limits. NOT_IMPLEMENTED means install attachment-enabled plugin.
- `attachment.begin {sessionId,name,bytes,sha256,kind,mediaType?}` -> `{uploadId,chunkBytes,offset:0}`. `kind` is `image` or `file`; SHA-256 is lowercase hex of exact bytes. Images carry one of supported MIME types. Nonempty bounded leaf display names, positive integral bytes.
- `attachment.chunk {uploadId,offset,data}` -> `{offset}` (next expected byte offset). data is canonical base64, at most chunkBytes decoded. Sequential chunks. An exact retry of the most recently acknowledged chunk is idempotent; conflicting/out-of-order chunks fail. Client uses offset from reply.
- `attachment.finish {uploadId}` -> `{attachmentId,name,bytes,kind,mediaType?}`. Verify complete byte count and SHA-256 before making ready. Repeat finish on same upload returns same object. attachmentId is opaque, scoped to authenticated device and original session. Host retains ready objects until expiry for failed-prompt retry. After successful DSH prompt admission, host removes the corresponding staging objects; the bounded request ledger still answers an identical prompt retry without requiring those objects.
- `attachment.abort {uploadId}` -> `{aborted:true}` for own existing upload (including ready); unknown treated idempotently; foreign upload must not be touched.
- Extend `session.prompt` payload with optional `attachments: string[]` (attachmentIds). `text` can be empty only when attachments nonempty. Existing text semantics unchanged. Up to 5 distinct attachments, all ready and owned by connection device and target session. `requestId` should be required for attached prompts and retained on retries. Do not auto-retry a new requestId after uncertain delivery.

## Errors

Use existing MALFORMED_FRAME / NOT_IMPLEMENTED / REMOTE_ERROR plus additive UPLOAD_NOT_FOUND, UPLOAD_EXPIRED, UPLOAD_TOO_LARGE, UPLOAD_LIMIT_REACHED, UPLOAD_OFFSET_MISMATCH, UPLOAD_INTEGRITY, ATTACHMENT_INVALID. Client displays message and retains draft on failure, not falsely reporting sent. Unknown/foreign upload references may both be NOT_FOUND to avoid information disclosure.

## Host security and lifecycle

Use private random temp directory, server-generated random filenames, sanitized display names only. Bound active uploads and aggregate reserved bytes (e.g. 200 MiB globally, max 10/device), TTL 30 minutes, regular cleanup, stop cleanup. Never automatically execute/open documents. Serialize chunk/finish/abort per upload. Ignore user paths. Enforce base64, actual bytes, SHA-256, MIME signature, device/session ownership, duplicate IDs, limits. Prompt replay with same requestId+same content reuses admission; mismatched content with same requestId rejected. Files go via DSH fileUploads.uploadStream and its returned file receipt; images go via DSH prompt image parts, allowing DSH's validation/normalization/model admission. No arbitrary host read or write RPC.

## Android UX and packaging

Application ID `me.shirazu.dshmizar.attachments`; display name `Mizar Attachments` (Chinese `Mizar 附件版`); versionName `0.8.21-attachments.2`, versionCode 38. The code namespace stays unchanged. The app does not claim the original mizar:// deep link; internal QR/manual pairing continues working. Photo Picker / OpenDocument inputs are copied to bounded private staging files without broad storage permissions. Supports attachment-only sends, preview/list/removal, progress, failure retention/retry, session/host-switch isolation, and cancellation cleanup. The picker preserves source bytes; unsupported image formats or oversized dimensions are rejected rather than silently transcoded. DSH performs its own image validation/normalization on admission. Upload runs on IO, with stable attached-prompt request IDs after uncertain failures. A local build uses its own signing key, never an original author's release key; no key or APK is included in this source branch.

## Evidence boundary

Upstream Android app/mizarkit licensing is not explicit in this checkout. This source-only feature branch preserves upstream notices and does not assert MIT licensing or grant broader redistribution rights for Android. The host plugin has its own MIT license. No APK release or signing material is published by this branch; clarify upstream authorization before binary redistribution or commercial use.
