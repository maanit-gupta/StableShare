# Phase 6, feature 2 — Instant upload

Steps: 6.2a (server), 6.2b (client and UI).

GOAL: if the server already holds a file with the same SHA-256 and size, an upload completes immediately with no chunks sent. It is a visible, demo-friendly feature and a real bandwidth saver.

DESIGN (fixed; do not change without asking)
- The protocol change is additive: the existing create-session response gains fields. Old behaviour for new files is unchanged.
- The server keeps a hash index of completed files. A new upload whose declared sha256 and size match an indexed, still-existing file is created already COMPLETED and linked to that file.
- Hash-only matching does not prove the client holds the bytes. For this local mock server that is acceptable; document it as a known limitation (a production server would add a challenge, for example asking for a hash of a random byte range).
- The client still compares the server's returned sha256 with its own locally computed hash before marking COMPLETED, so verification is preserved.

## 6.2a Server

READ: `server/CLAUDE.md`, the upload handlers and storage code (grep for the session-create and finalize handlers), DESIGN.md section 3 by heading.

DO
1. Index: `storage/index/<sha256>.json` containing `{sha256, size, path, createdAt}`, written with the usual atomic pattern (temp file, fsync, rename) when a finalize succeeds.
2. On `PUT /api/uploads/:uploadId` with fileSize > 0: if an index entry exists with the same sha256 AND the same size AND its file exists on disk with that size, create the session in state COMPLETED. Link the data with a hard link into `storage/completed/<uploadId>-<fileName>` (fall back to a copy if linking fails), so deleting one upload never harms another. Respond 200 with the usual body plus `"instant": true`, `"state": "COMPLETED"`, `"sha256"`, and `receivedChunks` listing every chunk index.
3. For every other case the response is unchanged and includes `"instant": false`.
4. If an index entry points at a missing or wrong-sized file, delete the stale entry and treat the request as a normal new upload.
5. Zero-byte files are never instant (nothing to save).
6. Afterwards the COMPLETED session behaves exactly like any completed upload: GET status works, POST complete is idempotent and returns the same result, DELETE removes only that session's link, a chunk PUT returns 409.
7. `/admin/stats` gains an `instantUploads` counter. Fault injection applies as usual.
8. Update the root CLAUDE.md "Protocol summary" with one line about `instant`, and DESIGN.md section 3 with an example response.

TESTS (node:test, fresh temp STORAGE_DIR)
Second upload of identical content under a new id is instant and its completed file exists; same size but different content is not instant; same hash with the index file deleted or the completed file missing is not instant and the stale entry is removed; the index survives a server restart; zero-byte is not instant; two sessions of identical content created concurrently both end valid with no corruption; deleting one session leaves the other's file intact; POST complete on an instant session is idempotent; a chunk PUT to an instant session returns 409; `instantUploads` counts correctly.

DONE WHEN: `npm test` green; DESIGN.md and CLAUDE.md updated; PROGRESS.md updated.

## 6.2b Client and UI

READ: `UploadPipeline`, `ProtocolClient` DTOs, `TransferRepository` event types, UI-SPEC sections 5.4.1, 5.8.6 and 8 by heading.

ENGINE
1. Add the `instant` field to the create-session DTO (default false when absent, so older servers still work).
2. In the upload pipeline, after the local sha256 is known and the session exists: if the response says `instant` (or the status shows state COMPLETED with every chunk received), skip all chunk requests. Mark every chunk DONE with `applyServerReceivedChunks`, log the new event type `INSTANT_UPLOAD`, transition to VERIFYING, call POST complete (idempotent), compare the returned sha256 with the local one. Match gives COMPLETED plus the VERIFIED event; mismatch gives FAILED FILE_HASH_MISMATCH.
3. The normal resume path must already handle "server state COMPLETED with all chunks received" (for example after a restart mid-verify). Make sure it routes to the same verification, not to a chunk loop.
4. Add `INSTANT_UPLOAD` to the event type enum. Check how it is persisted; if not by name, add a migration with an exported schema and a migration test. Add a DAO query that tells whether a transfer has an `INSTANT_UPLOAD` event (observable as a Flow).

UI — EXACT COPY (add to docs/UI-SPEC.md in the same commit, and to strings.xml)
- Section 8, event INSTANT_UPLOAD: "The server already had this exact file, so nothing needed sending".
- A pill "Already on server" on a transfer that has an INSTANT_UPLOAD event, in the same style and position rule as the "Restored after restart" pill (section 5.4.1); shown on Transfers rows, History rows and the detail screen's file line area.
- Detail, Details card (section 5.8.6): a new row labelled "Data sent", value "None, the server already had this file" for instant uploads. For other transfers, the row is not shown.
- No other copy, mascot or colour changes.

TESTS
Engine, with the fake or recording client: instant path sends zero chunk requests, ends COMPLETED with VERIFIED and INSTANT_UPLOAD events; a returned sha256 that differs from the local one fails with FILE_HASH_MISMATCH; cancel during an instant upload ends CANCELLED; process death after instant detection resumes to verification and completes; a non-instant upload is unchanged. Compose tests for the pill and the Details row. Run the Phase 5 fuzz suite: extend the FakeServer with the instant rule and make the fuzz scenarios occasionally re-upload identical content.

MANUAL CHECK: upload the 200 MB test file, then upload the identical file again (generate it once, keep the picked URI): the second finishes in seconds, shows the pill, and `/admin/stats` shows `instantUploads: 1`.

DONE WHEN: tests green, manual check reported, docs updated, PROGRESS.md updated.
