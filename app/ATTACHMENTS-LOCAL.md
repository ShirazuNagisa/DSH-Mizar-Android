# Private Android attachment build

This additive build originated as a local evaluation and is shared as an unofficial source-only feature branch. It is not an upstream release; APKs, signing keys and pairing data are not published in this branch. The Android app and mizarkit do not have an explicit upstream root license grant in this checkout; this document makes no MIT claim or broader redistribution grant for them. Existing notices remain unchanged. The host plugin's own license is separate; clarify upstream authorization before redistributing binaries or commercial use.

## Behavior

- Side-by-side application ID: `me.shirazu.dshmizar.attachments`; namespace unchanged. The app does not register the official `mizar://` deep link. Internal QR/manual pairing still works.
- Photos use Android Photo Picker (system fallback on older versions); files use OpenDocument. No storage permission or persistent URI grant is requested.
- Selected bytes are copied on IO into private, no-backup files with random local names. Each nonempty item is limited to 20 MiB, each message to 5 items, and total staged data across in-process drafts to 100 MiB. The copier reserves 16 MiB of free storage and checks while copying. Provider-declared sizes are not trusted as the actual size limit.
- The file picker preserves bytes exactly. Photos accept JPEG/PNG/WebP/GIF identified by signature, with bounds-only decoding; images over 8192 pixels per side or 32 megapixels are rejected with a resize suggestion. This version does not silently transcode HEIC or allocate a full decoded bitmap.
- Attachment capability negotiation respects lower host limits. At most 64 KiB of binary data is encoded per WebSocket chunk. SHA-256 covers exactly the staged bytes.
- Text and attachment drafts are scoped to host, paired device identity and session. Switching away pauses ongoing work and retains completed private copies for returning to that exact scope. Picker results returning to another scope are ignored. Re-pairing does not reuse the old device's draft.
- Progress, removal, pause, and confirmed discard are available in the composer. Photos have an IO-decoded, subsampled preview bounded to 128 pixels per side. Sending attachments without text is supported. History renders stored image/file reference metadata as non-downloadable attachment cards, including messages with no text; it never decodes history base64 or opens host paths.
- Failed/uncertain chunk replies retain the last confirmed offset; retry replays the exact chunk. Finish is retried with the same upload ID. A known expired/missing upload may begin again only on a subsequent explicit retry before prompt admission.
- Once prompt admission starts, text, attachment IDs and request ID are frozen. Send retries the same payload and key. Editing/removal is disabled until success or confirmed discard. An uncertain message may already have arrived; the user must check the host transcript before discarding and sending a new message.
- Accepted messages remove local copies immediately. Removal/discard/forget attempts a bounded best-effort remote abort, always deleting local files first. Partially copied files are deleted on failure/cancellation. The host TTL handles uploads whose begin reply was lost or whose connection is unavailable. Accepted IDs are not aborted while DSH may still consume them.
- Drafts survive ordinary screen navigation/rotation but are not persisted across process death. Leftover private copies are swept at the next ViewModel initialization. No claim of crash-resumable sending is made.

## Validation handoff

The implementation adds pure JVM tests in `mizarkit` for bounded canonical base64 chunks, exact chunk retry after a lost response, idempotent finish, cancellation, malformed acknowledgements, advertised limits, host/device/session isolation, immutable attached prompt replay, filename bounds, expiry restart, and the production `MizarConnection.call` adapter. The Team Lead runs Gradle centrally; the Android teammate intentionally does not launch competing builds.

Recommended verification commands are `:mizarkit:test :app:assembleRelease :app:lintRelease`. Device acceptance still needs photo/file picking, pause/remove while uploading, network loss, host/session switching, unsupported host, and attachment-only submission. Release signing and any private APK distribution are the Team Lead's responsibility using a new local key, never an original author's signing key.
