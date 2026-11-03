# Changelog

## 0.1.2 — 2026-11-03

- **`capture(String hostFile)` is documented as the host file the capture is written to**
  (pcapng), not a directory; the errors for `capture("")` and the deprecated
  `capture(true)` say so too. 0.1.1's jar still said "host directory".
- **Mounts must use absolute paths on both sides**; a relative one throws
  `Kosa8Exception` before the request.

## 0.1.1 — 2026-11-03

Fixes found while writing and testing the docs at https://docs.kosa8.com/guides/java-guide/.

- **Two clients in one JVM no longer crash checkpointing.** Each `FileCheckpointStore` on
  the same file used to take its own `FileLock`, and the second threw the unchecked
  `OverlappingFileLockException` (escaping `deleteSandbox` after the sandbox was gone).
  Stores on one path now share one in-process lock first.
- **The checkpoint file lock waits at most 10 s** (`new FileCheckpointStore(path, lockTimeout)`),
  as `@kosa8/sdk` does, then throws `CheckpointException` instead of hanging.
- **`Options.Create.mounts` works.** The `host:guest[:ro]` strings are sent as the objects
  the daemon takes; 0.1.0 sent strings and every mount was refused with
  `500 mount has no guest path`.
- **`Options.Create.capture(String hostFile)`** records traffic to that file (pcapng).
  `capture(boolean)` is deprecated: `capture(true)` always failed with
  `500 capture: no output path` and now fails before the request with an explanation.
- **`exec` with `Options.Run.env` throws `Kosa8Exception`** instead of silently running
  without the variables: the daemon's exec takes no environment.
- **A second `writeStdin` to the same container throws** instead of answering ok for input
  the process never sees (one body is the whole of stdin).
- **Concurrent checkpoints of one sandbox no longer collide.** The 409 loser steps to the
  next id; a 409 for any other reason is thrown.
- **A failed `rewind` says what happened**: the sandbox was deleted, not stopped, and the
  checkpoint is intact.
- **`Endpoint.toString()` no longer prints the bearer token.**
- **`Sandbox` gains `execFrames`, `startInteractive`, `writeStdin` and `attach`.**

Not changed (daemon behaviour): a timeout closes the connection but does not stop the
command; a missing snapshot on restore is a 500, not a 404.

## 0.1.0 — 2026-11-03

First release.
