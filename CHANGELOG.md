# Changelog

## 0.3.0 — 2026-11-03

kosa8 Cloud's API (api.kosa8.cloud) is not open yet; the `Kosa8Cloud` client is ready for when it is.

- **Saved worker connection**: with no `host`, `KOSA8_HOST` or `socketPath`, the client uses the
  connection the CLI saved in `~/.kosa8/worker.json` (`kosa8 wsl setup`, `kosa8 worker join`), so it
  works on Windows by default. Its token goes only to its host; its `"insecure": true` allows plain
  http to a LAN address. `Builder.workerFile(Path)` moves it, `null` reads none;
  `Endpoint.resolve(..., workerFile)` is the six-argument form.
- **Windows**: an unreachable default socket says to run `kosa8 wsl setup` or set `KOSA8_HOST`;
  mount host paths starting with `/` (WSL paths) are accepted on Windows; `test.sh` picks the
  classpath separator; CI runs the unit tests on Ubuntu, macOS and Windows.
- **kosa8 Cloud**: `Kosa8Cloud` (builder: `apiKey` / `KOSA8_API_KEY`, `baseUrl` / `KOSA8_CLOUD_URL`, `timeout`)
  talks to the cloud control plane over `java.net.http`, matching the Python and TypeScript SDKs.
  `sandboxes()`: `create`, `list`, `get` returning `CloudSandbox` (`exec` → `CloudExecResult`, `sleep`, `wake`,
  `refresh`, `delete`); `functions()`: `deploy`, `list`, `get`, `delete`, `invoke`, `map`; `me`, `usage`.
  Keys must start with `k8c_live_`; plain http only to loopback; `toString()` never shows the key.
- **Errors**: `Kosa8Exception.code()` carries the cloud's stable error code; new
  `PaymentRequiredException` (402) and `RateLimitException` (429), mapped like the other statuses.

## 0.2.0 — 2026-11-03

The sandbox toolbox of daemon 0.13, matching the Python SDK's.

- **Code**: `runCode` (Python, JavaScript, TypeScript) returns `Results.CodeResult`; artifacts
  (matplotlib charts) come back decoded to `byte[]`.
- **Files**: `listFiles`, `statFile` (`Results.FileEntry`), `readFile`, `readText`, `writeFile`
  (text, bytes or a chunked stream, with `mode`), `deleteFile` (`recursive`), `makeDir`, `moveFile`.
  A file body cut short throws `StreamException`.
- **Git**: `git(op, Options.Git)` and `gitClone`, `gitStatus`, `gitCommit` (stages everything first
  unless `addAll(false)`) returning `Results.GitResult`; a git failure is `ok() == false`.
- **Sessions**: `createSession`, `listSessions`, `sessionExec`, `deleteSession`, and `shell`, which
  makes the session (default `main`) the first time the daemon says there is no such session.
- **Terminals and previews**: `startPty`, `resizePty`, `createPreview` (`Results.Preview`), `revokePreviews`.
- **Lifecycle**: `setLifecycle(Options.Lifecycle)` sends only the fields set (0 turns one off);
  `Options.Create` gains `autoStopMin`, `ttlMin`, `autoDeleteMin`, `ephemeral` and `template`.
- **Templates**: `listTemplates`, `deleteTemplate`, `buildTemplate`. The build context is packed by a
  built-in ustar writer (PAX headers for long names and link targets; symlinks kept as links)
  honouring `.dockerignore` prefixes, spooled to a temp file and streamed; output frames go to
  `onOutput`; a build error throws `StreamException`. No timeout applies to a build.
- Every toolbox method is also on the `Sandbox` handle.

Not changed (daemon behaviour): a 0.13.0 daemon built before the session fix answers
`deleteSession` with `500 cannot delete running task` (the SIGKILL has not landed yet); the
session is gone a moment later. The SDK does not retry.

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
