# Changelog

## Unreleased

- RL rollouts: each flushed chunk carries an Idempotency-Key that stays with those rows until kosa8
  Cloud answers, so a flush whose answer was lost and is resent later is stored once.
- `CloudExecResult` gains `stdoutTruncated()`, `stderrTruncated()`, `stdoutBytes()` and `stderrBytes()`: a
  buffered exec keeps the first and last 1 MiB of each stream, and now says when it dropped the middle.
- `CloudOptions.Create.kvm(boolean)` (preview, /dev/kvm). `CloudOptions.List.limit` checks 1 to 500, the
  server's range; the `listPage` docs say the server pages (they said it did not), and `list()` is the first page.
- Files: `readRange(path, start, end)` (HTTP Range), `writeAt(path, data, offset)`, `append(path, data)`,
  `uploadFile(path, localPath)` (64 MiB parts at their offsets, so files over the API's 100 MB request limit
  work) and `downloadTo(path, localPath)` (streamed to disk, never held in memory). An append is never retried
  once it may have reached the host (a retry would add the data twice); an offset write is.
- `CloudSandbox.execStream(cmd, image, env, timeoutS, onStdout, onStderr)`: a streamed exec (`stream: true`),
  as Python's `exec_stream` and TypeScript's `execStream`. Output reaches the callbacks as UTF-8 text while
  the command prints (heartbeat frames skipped, characters never split across frames); not held to 90 s,
  timeoutS up to a day. A stop at timeoutS throws `ExecTimeoutException`, any other break `StreamException`,
  both with the output so far. Not retried once sent, except after a 429.
- `execBackground(cmd, image, env, timeoutS)`: the host ends a background command after timeoutS.
- `ExecTimeoutException.stdout()` / `stderr()`: the output the command printed before it was stopped.
- Every `Kosa8Exception.ApiException` reads kosa8 Cloud's error contract: `agentMessage()`, `upgradeUrl()`,
  `requestUrl()`, `limitName()`, `limit()`, `used()`, `plan()`, `nextPlan()`, `feature()`, `packUrl()` and
  `options()`. A 429 `quota_exceeded` (`RateLimitException`) now gives its `agent_message` and `request_url`,
  which only a 402 did before, and its message ends with `ask for more: <request_url>`.
- TLS to a remote worker (`https://` in `KOSA8_HOST` or a saved worker) checks that the certificate names the
  host and sends SNI; before, any valid certificate for any name was accepted.
- Retries of creates, batches, forks, grades, job submits and rollout flushes use a new SDK-made
  `Idempotency-Key` after a 5xx (502 `host_failed`, 503 `no_capacity` / `host_restarting` /
  `host_unavailable`, 504), so each retry of a server-side failure is a new attempt. The key is still kept
  after no answer (the first try may have created the sandbox), a 429 and a 409 `idempotency_in_progress` /
  `template_building`. A key you pass is never changed. Retry-After is honoured as before.

## 0.6.0 - 2026-11-22

Java catches up with the Python and JS SDKs on the customer-facing kosa8 Cloud API.

- **Browsers**: `cloud.browsers()` (`create`, `list`, `info`, `get`, `delete`, `cdpUrl`) and `CloudBrowser`
  (`page`, `goTo`, `screenshot`, `refresh`, `cdpUrl`, `delete`; `AutoCloseable`). $0.12 per browser-hour.
- **AI gateway**: `cloud.ai()` (`settings`, `updateSettings`, `setKey`, `keys`, `deleteKey`, `usage`,
  `gatewayUrl(provider)` for pointing an OpenAI/Anthropic client at `/v1/ai/gw/{provider}/`, `call`).
  `CloudOptions.Create.aiGateway(true)`.
- **Hosted jobs**: `cloud.jobs()` (`submit` with an Idempotency-Key, `estimate`, `list`, `get`, `cancel`, `waitFor`).
- **Benchmarks**: `cloud.benchmarks()` (`list`, `get`, `instances`, `instance`, `prepare`).
- **Background commands**: `CloudSandbox.stopBackground(execId)`.
- **Recordings, embeds, custom preview domains**: `cloud.recordings()`, `CloudOptions.Create.record(true)`,
  `box.recordings()`, `box.embeds()`, `box.previews().domains(port)`, `cloud.previewDomains(previewId)`.
- **Org and account**: `cloud.webhooks()`, `cloud.apiKeys()` (incl. `rotate`), `cloud.audit()` (`list`,
  `verify`, `export` NDJSON), `cloud.rl()` (`compare`, alert rules), `limits()`, `requestLimit`,
  `usageInsights()`, `pricing()`, `checkpoints()`, `lineage()`, `images()`, `orgEgress()` / `setOrgEgress` /
  `clearOrgEgress`, `burst()` / `activateBurst`, `functions().invocations(name, limit)`, `attackScorecards` /
  `attackScorecard` / `uploadAttackScorecard`.
- **Small sizes**: `CloudOptions.Create.size("0.25x0.5")` / `"0.5x1"` / `"1x2"` and `cpus(0.25, 512)`
  (`CloudOptions.SMALL_SIZES`, `CREATE_SIZES`).
- **Retries**: any request, a plain POST included, is retried with backoff and jitter on 429, 503
  `database_busy` and 409 `template_building` (refused before anything ran), honouring `Retry-After` and the
  body's `retry_after` / `retry_after_s`. A body's `retryable` is honoured on its own (`retryable: false` is
  never retried). `POST /v1/jobs` and rollout flushes carry an Idempotency-Key, so they retry on infra failures too.
- Overflow sandboxes (kosa8 Cloud's Cloudflare capacity) refuse fork, checkpoint, rewind and sleep with 501
  `not_available`, thrown as `UnsupportedException` with the server's reason.
- README: install snippets name 0.6.0; persistence wording matches the API (each exec a fresh process,
  `/workspace` kept for the sandbox's life, packages need `persistent(true)`).

## 0.5.3

- `CloudSandbox.exec(cmd, image, env, timeoutS)` (String or argv): the API's exec `timeout_s` (1 to 90) stops the command in the sandbox when it runs that long and throws `ExecTimeoutException` (without the hint about the 90 s buffered limit). Python, JS, Go and Ruby have the same option. Any other value is an `IllegalArgumentException` before the call. The three-argument `exec` is unchanged.
- README: the install snippets name 0.5.2, the version on Maven Central (they said 0.5.0, and that Central had only 0.3.1); what persists between commands (only `/workspace`, unless the sandbox is persistent) matches the server; a "same thing in each language" table for the five SDKs.

## 0.5.2

- **Actionable errors.** A kosa8 Cloud error's `getMessage()` is the server's sentence followed by what to act on,
  e.g. `no host has room right now [HTTP 503 no_capacity; retry in 10 s; request a5e77ddee9ef8120-BOS]`; a 402 adds
  `upgrade: <url>` when the sentence lacks it, and a 401 adds "check KOSA8_API_KEY; make a new key at
  https://app.kosa8.com/app#keys". The bare sentence is still `daemonMessage()`.
- `Kosa8Exception.requestId()`: the answer's `X-Request-Id`, else Cloudflare's `cf-ray`, to quote to support.
- Client-side errors say what to do next: a missing or malformed key points at `KOSA8_API_KEY` and
  https://app.kosa8.com/app#keys, a timeout at retrying or `builder().timeout(Duration)`, a connection failure at the
  network and `KOSA8_CLOUD_URL`.

## 0.5.1

- Failure class `oom`: exit 137 with the guest kernel's OOM count raised (kosa8 Cloud reports it on exec and grade; a grade killed for memory has no reward). It can be added to a rollout mask.

## 0.5.0 — unreleased

RL training and fan-out on kosa8 Cloud, matching the Python SDK's `kosa8.rl`.

- **Fan-out**: `sandboxes().createMany(count, CloudOptions.Batch)` (up to 1000: `labels`, `runId`,
  `leaseS`, `prefix`, `partialOk`) and `fork(CloudOptions.Fork)` (n up to 1000, same options), both
  returning `SandboxBatch` (`sandboxes`, `failed` as `FanoutFailure`, `requested`, `created`, `runId`).
  `fork(int)` is unchanged. `CloudOptions.Create.leaseS`, `heartbeat(Selector, extendS)`,
  `CloudSandbox.heartbeat()`, `drain(Selector)` (follows `more`), `CloudSandbox.leaseS()`.
- **Sealed grader**: `CloudSandbox.grade(cmd, CloudOptions.Grade)` and `sandboxes().grade(id, ...)`
  return `GradeResult` (reward, metrics, exit code, failure class, grader id, recorded).
- **Idempotency-Key** on create, batch, fork and grade (the caller's or a new UUID), reused on every retry.
- **Failure classes**: `Kosa8Exception.failureClass()`, `retryable()`, `retryAfterSeconds()`,
  `Kosa8Exception.classifyFailure(Throwable)`, `FailureClass`; `CloudExecResult` gains
  `failureClass` and `retryable` (the 4-argument constructor still works).
- **Retries**: `RetryPolicy` (exponential backoff, full jitter, Retry-After) applied by default
  (3 tries) where safe; `Kosa8Cloud.builder().retry(RetryPolicy.NONE)` turns it off.
- **`cloud.forkMap(base, items, fn, CloudOptions.ForkMap)`**: bounded concurrency, retries on a
  fresh fork, per-item timeout, every child deleted.
- **`Run`**: RL runs (create or attach, buffered rollouts sent 500 at a time, background lease
  heartbeats, finish with drain, summary, rollouts, export, list) and `Run.map` with sealed grading,
  groups and masking (`Rollouts`, `RolloutResult`).
- **Org templates** (`cloud.orgTemplates()`) and **capability tokens** (`cloud.tokens()`); a
  `k8c_tok_` token is accepted as the API key.

## 0.4.0 — 2026-11-15

The rest of the kosa8 Cloud API:

- **Sized and Docker creates**: `CloudOptions.Create` `size`, `cpus(cpus, memMb)`, `image`,
  `diskGb`, `persistent`, `docker`, `cpu`, `name`, `labels`, `env`, `secrets`, `egress`,
  `snapshot`, `noWait`; a cold boot is waited for with the new `CloudSandbox.waitRunning`.
  `CloudOptions.List` takes `name` and `labels`; `CloudSandbox` gains `persistent()`, `name()`,
  `image()`, `snapshotId()`, `cpu()`, `labels()`.
- **Background commands** (`execBackground` -> `CloudBackgroundExec`, `backgroundExec`,
  `waitBackground`), **durable snapshots** (`snapshot()` -> `CloudSnapshot`,
  `cloud.snapshots()`), **previews** (`previews()` -> `CloudPreview`), **terminal**, raw
  `files().download` / `upload` and `files().link`, **egress**, **secrets** (`cloud.secrets()`),
  **schedules**, **code interpreter**, `metrics`, `logs`, `setLabels`, `cloud.capabilities()`.
- **Pipelines** (`cloud.pipelines()`): `run`, `runs`, `get`, `waitFor`, `logs`, `cancel`,
  `artifacts`, `downloadArtifact`, `createHook`, `hooks`, `deleteHook`.
- Fix: `CloudFileInfo.mode` is an `Integer` (permission bits); the API sends a number, so it was
  always null.

kosa8 Cloud parity with the Python, TypeScript and Go SDKs. The default Cloud address stays
`https://api.kosa8.com` (0.3.0 defaulted to `api.kosa8.cloud`; do not use 0.3.0).

- **Lifetimes**: `sandboxes().create(CloudOptions.Create)` takes `template`, `region`, `sleepAfterS`,
  `ttlSeconds` (60 s to 90 days), `deleteAfter` (Unix ms; not with `ttlSeconds`) and `acceptPrice`
  (paid Store items), checked before any request. `box.update(CloudOptions.Update)` changes
  `ttlSeconds`, `deleteAfter` or `sleepAfterS`; `clearLifetime()` removes the automatic delete.
- **Listing**: `list(CloudOptions.List)` (`all`, `limit`, `cursor`), `listPage(...)` returning
  `CloudSandboxPage` (`sandboxes()`, `nextCursor()`), and `listAll(...)`, which follows cursors and
  stops if one repeats. `list()` and `list(all)` are unchanged.
- **Files** (data plane): `box.files()` → `CloudSandboxFiles` with `read` (`byte[]`), `readText`,
  `write` (`byte[]` or `String`, sent as raw bytes), `list` (default `/workspace`), `mkdir`,
  `delete(path, recursive)`, returning `CloudFileInfo`.
- **Checkpoints, rewind, fork, ports** (data plane): `box.checkpoint(label)` → `CloudCheckpoint`,
  `box.checkpoints()`, `box.rewind(id)` → `CloudRewindResult` (`checkpoint`, `backup`, `ms`),
  `box.fork(n)` (1 to 100) → new `CloudSandbox`es, `box.ports()` → `CloudPort`. On hosts without
  the data plane these throw `UnsupportedException` (501); not every host has it yet.
- `cloud.templates()`. Typed getters on `CloudSandbox`: `template`, `region`, `parentId`, `source`,
  `originItemVersion`, `deleteAfter`, `sleepAfterS`, `createdAt`, `ownHost`, `hostUnreachable`,
  `costUsd`, `lastError` (`info()` still has the raw map).
- **Errors**: `PaymentRequiredException.upgradeUrl()` / `agentMessage()`; new `OrgFrozenException`
  (423 `org_frozen`) and `ExecTimeoutException` (504, code `exec_timeout`, or today's 502 "timed out"
  on `/exec` after about 30 s; also a client timeout on `/exec`); `RateLimitException.retryAfter()`
  from the Retry-After header.
- Requests send `User-Agent: kosa8-java/0.4.0`.
- README: Maven and Gradle coordinates, Cloud quickstart first, the base template (Alpine, fresh
  container per exec, only `/workspace` persists, 30 s exec limit, 64 KiB bodies, 8 MiB files),
  Cloud errors.

## 0.3.1 — 2026-11-03

- The default kosa8 Cloud address is now `https://api.kosa8.com` (0.3.0 defaulted to `api.kosa8.cloud`, which kosa8 Cloud does not use). `KOSA8_CLOUD_URL` still overrides it.

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
