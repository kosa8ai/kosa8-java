# kosa8 (Java)

**[kosa8](https://kosa8.com): Zero Trust cybersecurity infrastructure for AI agents.**

The Java SDK for [kosa8](https://kosa8.com): each agent in its own microVM, on kosa8 Cloud or on
your own machine's daemon. No dependencies; Java 17+. Method for method it matches
[`@kosa8/sdk`](https://github.com/kosa8ai/kosa8-js), the Python `kosa8` package, `kosa8-go` and the Ruby gem.

## Install

Maven:

```xml
<dependency>
  <groupId>com.kosa8</groupId>
  <artifactId>kosa8</artifactId>
  <version>0.9.0</version>
</dependency>
```

Gradle:

```groovy
implementation "com.kosa8:kosa8:0.9.0"
```

0.9.0 is the latest release on Maven Central
([com.kosa8:kosa8](https://central.sonatype.com/artifact/com.kosa8/kosa8)); the CHANGELOG lists what each version added.

## kosa8 Cloud quickstart

Make an API key in the cloud dashboard ([app.kosa8.com](https://app.kosa8.com)) and set it:

```sh
export KOSA8_API_KEY=k8c_live_...
```

```java
import com.kosa8.*;

Kosa8Cloud cloud = Kosa8Cloud.builder().build();          // KOSA8_API_KEY; or .apiKey("k8c_live_...")

// Deleted automatically in an hour, even if this program never gets to box.delete().
CloudSandbox box = cloud.sandboxes().create(new CloudOptions.Create().ttlSeconds(3600));

CloudExecResult r = box.exec("echo hello > /workspace/hello.txt && cat /workspace/hello.txt");
System.out.println(r.exitCode() + " " + r.stdout());       // 0 hello

box.files().write("/workspace/data.csv", "a,b\n1,2\n");    // data plane, see below
String csv = box.files().readText("/workspace/data.csv");
for (CloudFileInfo f : box.files().list()) System.out.println(f.path() + " " + f.size());

box.delete();
```

Python and other runtimes come from templates: `cloud.templates()` lists what the org can start, and
`new CloudOptions.Create().template("python")` starts one.

| Builder method | Default |
|---|---|
| `apiKey` / `KOSA8_API_KEY` | required; starts with `k8c_live_` |
| `baseUrl` / `KOSA8_CLOUD_URL` | `https://api.kosa8.com`; `https` only, except `localhost`, `127.0.0.1` and `::1` |
| `timeout` | 120 s per request |

### Sandboxes

```java
CloudSandbox box = cloud.sandboxes().create(new CloudOptions.Create()
    .template("python")           // a key from cloud.templates(), or a Store item creator/name@1.2.3
    .region("eu-hel1")
    .sleepAfterS(600)             // idle seconds before it sleeps; 0 never
    .ttlSeconds(3600));           // 60 s to 90 days; or .deleteAfter(unixMs), not both
                                  // .acceptPrice(usd) for a paid Store item

box.update(new CloudOptions.Update().ttlSeconds(7200));    // two hours from now
box.update(new CloudOptions.Update().clearLifetime());     // never deleted automatically
box.deleteAfter();                                         // Unix ms, or null
box.sleep();                                               // costs only snapshot storage while asleep
box.exec("ls /workspace");                                 // wakes it, files and all

CloudSandboxPage page = cloud.sandboxes().listPage(new CloudOptions.List().limit(50));
for (CloudSandbox s : cloud.sandboxes().listAll()) System.out.println(s.id() + " " + s.state());
```

`list()`, `list(all)` and `list(CloudOptions.List)` return one list as before; `listPage` gives the
next cursor (`nextCursor()`, null at the end) and `listAll` follows every page. Today's server
answers every sandbox as one page.

A bad lifetime (outside 60 s to 90 days, or both `ttlSeconds` and `deleteAfter`) throws
`IllegalArgumentException` before any request. `CloudSandbox` keeps the raw answer in `info()` and has
typed getters: `template()`, `region()`, `parentId()`, `source()` (pool, restore, cold, fork),
`deleteAfter()`, `sleepAfterS()`, `createdAt()`, `ownHost()`, `hostUnreachable()`, `costUsd()`,
`originItemVersion()`, `lastError()`.

### Files, checkpoints, rewind, fork and ports (data plane)

```java
box.files().write("/workspace/app.py", "print('hi')\n");        // String or byte[]; at most 8 MiB
byte[] raw = box.files().read("/workspace/app.py");
box.files().mkdir("/workspace/out");
box.files().delete("/workspace/out", true);                     // recursive

CloudCheckpoint cp = box.checkpoint("clean");                   // memory, processes and disk; newest 10 kept
box.checkpoints();                                              // oldest first
CloudRewindResult back = box.rewind(cp.id());                   // back.backup() is the state just before
List<CloudSandbox> forks = box.fork(3);                         // 1 to 100 live copies
List<CloudPort> ports = box.ports();                            // TCP ports listening inside
```

These need the host's data plane. On hosts without it the API answers 501 (`not_available` /
`not_supported`), thrown as `Kosa8Exception.UnsupportedException`. Not every kosa8 Cloud host has it
yet; `exec`, `update` and `delete` work everywhere. A sandbox placed on kosa8's overflow capacity
(Cloudflare, used when kosa8's own hosts have no room) has no fork, checkpoint, rewind or sleep: those
calls throw `UnsupportedException` with code `not_available` and the server's reason; the SDK does not
emulate them.

### Sized and Docker sandboxes, env, snapshots

```java
CloudSandbox box = cloud.sandboxes().create(new CloudOptions.Create()
    .size("4x8").image("kosa8-template/universal-24.04-20261115.2").docker(true)
    .env(Map.of("NODE_ENV", "production")).name("web").labels(Map.of("team", "a")).ttlSeconds(3600));
// a sized create boots cold; create waits until it runs (.noWait() to skip)

// Small sizes for fleets of mostly idle sandboxes (RL rollouts, evals): a fraction of a shared vCPU,
// bursting while the host is idle. 0.25 vCPU and 512 MiB; also .size("0.25x0.5"), "0.5x1", "1x2".
CloudSandbox tiny = cloud.sandboxes().create(new CloudOptions.Create().cpus(0.25, 512).ttlSeconds(600));

CloudSnapshot snap = box.snapshot("warm");          // durable: kept until deleted
CloudSandbox copy = cloud.sandboxes().create(new CloudOptions.Create().snapshot(snap.id()));
cloud.snapshots().delete(snap.id());                // also list, get
```

Sizes: `CloudOptions.CREATE_SIZES` (`0.25x0.5`, `0.5x1`, `1x2`, `2x4`, `4x8`, `8x16`, `16x32`); template builds take
the standard ones (`CloudOptions.SIZES`). Also `cpus(cpus, memMb)`, `record(true)` (desktop recording),
`aiGateway(true)`, `diskGb`, `persistent`, `cpu("dedicated")`, `region` (`us`, `eu` or an
id), `secrets` and `egress`. `CloudOptions.List` takes `name` and `labels`.

### Background commands, previews, terminal and more

```java
CloudBackgroundExec job = box.execBackground("npm run build");   // no 90 s limit
job = box.waitBackground(job.id(), Duration.ofMinutes(30), Duration.ofSeconds(2));
System.out.println(job.exitCode() + " " + job.output());

System.out.println(box.previews().create(3000).url());           // private signed URL
box.terminal(120, 40, null).get("url");                          // a wss:// PTY URL
```

- Files: `files().download(path)` / `upload(path, bytes)` (no 8 MiB cap), `files().link(path,
  "PUT", null)` (presigned URL).
- `egress()`, `setEgress(false, allow, deny, presets)`, `resetEgress()`; `cloud.egressPresets()`.
- `runCode("print(6*7)", "a", null)` (stateful Python; persistent sandbox with python3),
  `codeContexts`, `createCodeContext`, `resetCodeContext`, `deleteCodeContext`.
- `schedules().create("0 * * * *", List.of("date"))`, `list`, `run`, `delete`.
- `stopBackground(execId)` (SIGTERM, then SIGKILL), `embeds().create(origins, mode, ui, ttlS, command)` /
  `list` / `revoke` (signed iframe URLs), `recordings()`, `previews().domains(port)` (`list`, `add`, `get`,
  `remove`: custom domains; also `cloud.previewDomains(previewId)`), `previews().delete(port)`.
- `metrics()`, `logs(limit)`, `setLabels`, `secrets()`; `cloud.capabilities()`.

### Secrets and Pipelines

```java
// Read the value from a file or System.in, never from argv or code.
cloud.secrets().set("npm-token", value, "https://registry.npmjs.org", true); // also list, delete

Map<String, Object> run = cloud.pipelines().run("https://github.com/me/app",
    Map.of("ref", "main", "secrets", List.of("npm-token")));
run = cloud.pipelines().waitFor((String) run.get("id"), Duration.ofHours(1), Duration.ofSeconds(5));
cloud.pipelines().logs((String) run.get("id"), null, null);
```

Also `runs`, `get`, `cancel`, `artifacts`, `downloadArtifact`, `createHook` (webhook URL and
optional cron), `hooks`, `deleteHook`.

### GitHub repos

An admin connects GitHub once (app.kosa8.com Settings, or `kosa8 github connect`). Then:

```java
cloud.github().repos("api");                 // also status(), reposPage(query, page), branches("acme/api")
CloudSandbox box = cloud.sandboxes().create(new CloudOptions.Create().repo("acme/api@main"));
box.exec(List.of("sh", "-c", "cd /workspace/api && make fix"));
Map<String, Object> pr = box.pullRequest(new CloudOptions.PullRequest().title("Fix the build"));
// or box.push(new CloudOptions.Push().branch("fix")); box.repo() shows the changes
```

kosa8 mints a GitHub token for one repo and one operation (at most an hour); it never enters the
sandbox. Errors: `GitHubNotConnectedException` (`connectUrl()`), `GitHubPermissionsException`
(`missingPermissions()`, `settingsUrl()`), `NoChangesException`, `RepoNotFoundException`,
`GitHubUnavailableException`.

### Autopilot bots

Bots are small automations on the org's sandboxes (sleep idle boxes, guard spend, block a threat). They are saved as
drafts, tried with a dry run, and only a person turns one on.

```java
Map<String, Object> plan = cloud.bots().plan(new CloudOptions.Bot().template("spend-guard").replay(true));
Map<String, Object> bot = cloud.bots().create(new CloudOptions.Bot().template("spend-guard")); // a draft
Map<String, Object> run = cloud.bots().run((String) bot.get("id")); // a dry run; changes nothing
List<Map<String, Object>> pending = cloud.approvals().list();
```

An agent never approves its own action: `bots().enable`, `bots().resumeAll` and `approvals().approve` / `reject` need a
person's key and otherwise throw `Kosa8Exception.HumanApprovalRequiredException`. A person approves in the web app, the
desktop app, the CLI or Slack. `bots().pauseAll()` stops everything.

### Functions

```java
cloud.functions().deploy("hello", Map.of(
    "runtime", "python3.12",
    "code", "def handler(event):\n    return {\"hello\": event.get(\"name\", \"world\")}\n"));
Map<String, Object> out = cloud.functions().invoke("hello", Map.of("name", "kosa8"));
System.out.println(out.get("ok") + " " + out.get("result")); // true {hello=kosa8}
```

Also: `cloud.me()`, `cloud.usage()`, `cloud.templates()`, `sandboxes().get(id)`, `box.wake()` / `refresh()`,
`functions().list()` / `get` / `delete` / `map(name, events, concurrency)`.

### Browsers, AI gateway, jobs and benchmarks

```java
try (CloudBrowser b = cloud.browsers().create(120, null, null)) {   // $0.12 per browser-hour, per second
  byte[] png = b.screenshot("https://example.com", false);
  String cdp = b.cdpUrl();                                        // Playwright: chromium().connectOverCDP(cdp)
}

cloud.ai().setKey("openai", key);                                 // sealed; never sent back
String base = cloud.ai().gatewayUrl("openai");                    // https://api.kosa8.com/v1/ai/gw/openai/
cloud.ai().usage();                                               // also settings, updateSettings, keys, deleteKey, call

Map<String, Object> job = cloud.jobs().submit(Map.of(
    "name", "eval-1", "template", "python", "tasks", List.of(Map.of("id", "t1", "input", "2+2")),
    "solver", Map.of("cmd", "python solve.py", "timeout_s", 300), "grader", Map.of("cmd", "python grade.py")));
job = cloud.jobs().waitFor((String) job.get("id"), Duration.ofHours(2), Duration.ofSeconds(10));
                                                                  // also estimate, list, get, cancel
cloud.benchmarks().list();                                        // instances, instance, prepare
```

Also on `cloud`: `recordings()` (`list`, `link`, `delete`), `webhooks()` (`create`, `list`, `delete`,
`deliveries`, `test`), `apiKeys()` (`list`, `create`, `revoke`, `rotate`), `audit()` (`list`, `verify`,
`export`), `rl()` (`compare`, `alerts`, `createAlert`, `updateAlert`, `deleteAlert`), `limits()`,
`requestLimit`, `usageInsights()`, `pricing()`, `checkpoints()`, `lineage()`, `images()`, `orgEgress()` /
`setOrgEgress` / `clearOrgEgress`, `burst()` / `activateBurst`, `attackScorecards()`, and `functions().invocations(name, limit)`.

### Retries

Every request retries with exponential backoff and full jitter (3 tries by default, `RetryPolicy`), waiting
at least the server's `Retry-After` (the header, or `retry_after` / `retry_after_s` in the body). Reads,
PUTs and deletes retry on infra failures; creates, forks, grades and job submits carry an Idempotency-Key.
The SDK's own key is reused after no answer (the first try may still be running and may have made the
sandbox), a 429 and a 409 `idempotency_in_progress` / `template_building`; after a 5xx (502 `host_failed`,
503 `no_capacity`, ...) the retry gets a new key, so it is a new attempt. A key you pass is never changed. Any call, a plain POST included, retries on 429, on 503 `database_busy` and on 409
`template_building`, which the server sends before doing anything. An answer with `retryable: false` is not
retried. `Kosa8Cloud.builder().retry(RetryPolicy.NONE)` turns it off.

### The base template

The default template, `base`, is **Alpine Linux**: `sh` is busybox, and there is no bash, python or
git. Use `template("python")` (or another key from `cloud.templates()`) for Python.

- **Each exec is a fresh process; files in `/workspace` persist for the sandbox's life.** An exec may
  also run in a fresh container, so `cd`, `export`, processes it leaves running and packages installed
  outside `/workspace` may be gone by the next exec; installed packages need `.persistent(true)`. Install packages into `/workspace` (for example `pip install --target /workspace/lib ...`), or
  create with `.persistent(true)` (one long-lived container) to keep the whole filesystem and background
  processes; the code interpreter (`runCode`) needs it, and it counts against your plan's
  persistent-sandbox limit.
- **A buffered exec runs at most 90 s** and throws `Kosa8Exception.ExecTimeoutException`.
  `box.exec(cmd, null, null, 30)` (image, env, timeoutS) stops a command sooner, in the sandbox.
  For longer commands stream them, which is not held to 90 s and prints as it goes:
  `box.execStream(List.of("make", "test"), null, null, 3600, System.out::print, System.err::print)`
  (timeoutS up to a day; the result is a `CloudExecResult`). Or start them in the background and poll:
  `box.execBackground(List.of("make", "test"), null, null, 3600)`, then `box.waitBackground(id, ...)`.
- A request body (command and env) is at most **64 KiB** (413 `too_large`). One file request is at most
  **100 MB** through the API: `box.files().uploadFile(path, localPath)` sends a bigger file in parts and
  `downloadTo(path, localPath)` streams one to disk; `readRange`, `writeAt` and `append` work on parts of a file.

### RL training and fan-out

Many sandboxes at once, leases so a crashed trainer leaks none, a drain, and a sealed grader. Every
call that makes sandboxes (create, `createMany`, `fork`, `grade`) carries an `Idempotency-Key`
(yours, or a new UUID) that its retries reuse after a timeout, so a retry makes nothing twice.

```java
// Up to 1000 in one call, labelled kosa8.run=run-1, deleted 600 s after the last heartbeat.
SandboxBatch b = cloud.sandboxes().createMany(64, new CloudOptions.Batch()
    .create(new CloudOptions.Create().template("python")).runId("run-1").leaseS(600).prefix("roll"));
b.failed();                                   // FanoutFailure: index, code, failureClass, retryable
SandboxBatch kids = base.fork(new CloudOptions.Fork().n(250).runId("run-1").leaseS(600));
cloud.sandboxes().heartbeat("run-1");         // push the leases back (every lease / 3)
cloud.sandboxes().drain("run-1");             // delete every sandbox of the run

// Grade a solved sandbox in a sealed fork: no network, hidden files only there.
GradeResult g = box.grade(List.of("python", "/grader/test.py"),
    new CloudOptions.Grade().file("/grader/test.py", hiddenTest).timeoutS(60));
g.reward();                                   // null when the grader did not finish: mask it, not 0

// fn in a fresh fork per item, 16 at a time; retryable failures retried on a new fork with backoff,
// every child deleted, even when fn throws.
List<ForkResult<String, Integer>> rs = cloud.forkMap(base, items, (child, item) -> work(child, item),
    new CloudOptions.ForkMap().concurrency(16).retries(2).leaseS(600));
```

An RL run (`Run`) records rollouts on kosa8 Cloud (app.kosa8.com, RL runs), heartbeats its sandboxes
on a daemon thread, and drains them when it finishes:

```java
Rollouts rs = Run.with(cloud, "my-grpo-run", new Run.Options().leaseS(600), run ->
    run.map(base, tasks, (box, task) -> { agent.solve(box, task); return null; },
        new Run.MapOptions().gradeCmd(List.of("python", "/grader/test.py")).groupSize(8)));
rs.rewards();   // null where masked (infra, quota, your own exception): leave those out of the gradient
rs.groups();    // per task, for group-relative advantages
```

`Run.start(cloud, name, options)` / `Run.attach(cloud, runId, options)` give the run itself:
`record` (buffered, sent 500 at a time), `flush`, `summary`, `rollouts`, `export`, `drain`,
`update`, `finish("finished" | "failed")`; `close()` finishes it. Each task's hidden grader files
are its `"files"` map (or `MapOptions.files(fn)`).

**Errors and retries.** Every `Kosa8Exception` has `failureClass()` (`ok`, `exit_nonzero`,
`timeout`, `infra`, `quota`, `client`; `error` for your own exceptions), `retryable()` and
`retryAfterSeconds()`; exec results carry `failureClass()` and `retryable()` too.
`Kosa8Exception.classifyFailure(t)` classifies any `Throwable`. The client retries (default
`RetryPolicy.DEFAULT`: 3 tries, exponential backoff with full jitter, at least the server's
`Retry-After`) only where it is safe: reads, PUTs, deletes, heartbeats, drains, the keyed calls above,
and any request answered 429. `Kosa8Cloud.builder().retry(RetryPolicy.NONE)` turns it off.

**Org templates** (`cloud.orgTemplates()`): `build(name, new CloudOptions.TemplateBuild().image(ref))`
(or `.dockerfile(text)`), `importImages(images, options)`, `list`, `get`, `waitReady`, `delete`; then
create with `new CloudOptions.Create().template(name)`, a restore of about a second.

**Capability tokens** (`cloud.tokens()`): `create(List.of("sandboxes:read", "sandboxes:write"),
new CloudOptions.Token().sandboxIds(List.of(id)).ttlS(3600))` returns a `k8c_tok_` secret once;
`list`, `revoke`. A token works as a `Kosa8Cloud` API key.

### Cloud errors

Every error from the cloud is a `Kosa8Exception` carrying its stable `code()`, `status()` and raw `body()`.

| Exception | When | What to do |
|---|---|---|
| `PaymentRequiredException` (402) | `payment_required` (no payment method), `spending_cap_reached`, `price_not_accepted` | show `upgradeUrl()` to the person; an agent relays `agentMessage()` word for word and stops |
| `OrgFrozenException` (423 `org_frozen`) | the org is frozen in the Security Center | an owner or admin must unfreeze it |
| `RateLimitException` (429) | too many requests, or `quota_exceeded` (active sandbox limit) | wait `retryAfter()` (an `Optional<Duration>`) when set, or delete sandboxes |
| `ExecTimeoutException` (504 `exec_timeout`, or today's 502 "timed out") | a buffered exec ran past its 90 s limit | run it in the background and poll |
| `UnsupportedException` (501 `not_available` / `not_supported`) | the host has no data plane | use exec, or try later |

A new organization runs nothing until a payment method is on file; until then creating a sandbox
throws `PaymentRequiredException` with `code()` `payment_required`.

## The same thing in each language

The five kosa8 SDKs (Python `kosa8`, JS `@kosa8/sdk`, Go `kosa8-go`, Ruby `kosa8`, Java
`com.kosa8:kosa8`) call the same kosa8 Cloud API, but some names differ by language. The ones
that catch people switching:

| | Python | JS / TS | Go | Ruby | Java |
|---|---|---|---|---|---|
| client | `Kosa8Cloud()` | `new Kosa8Cloud()` | `kosa8.NewCloud(kosa8.CloudOptions{})`, returns `(cloud, err)` | `Kosa8::Cloud.new` | `Kosa8Cloud.builder().build()` |
| lifetime on create | `ttl_s=3600` | `{ ttlS: 3600 }` | `CloudCreateOptions{TTLSeconds: 3600}` | `ttl_s: 3600` | `new CloudOptions.Create().ttlSeconds(3600)` |
| persistent sandbox | `persistent=True` | `{ persistent: true }` | `CloudCreateOptions{Persistent: true}` | `persistent: true` | `.persistent(true)` |
| sandbox id | `box.id` | `box.id` | `box.ID()`, a method (`fmt.Println(box.ID)` prints a func value; `go vet` reports it) | `box.id` | `box.id()` |
| shell command | `box.exec("ls /")` | `box.exec('ls /')` | `box.ExecShell(ctx, "ls /", nil)` | `box.exec("ls /")` | `box.exec("ls /")` |
| argv command | `box.exec(["ls", "/"])` | `box.exec(['ls', '/'])` | `box.Exec(ctx, []string{"ls", "/"}, nil)` | `box.exec(["ls", "/"])` | `box.exec(List.of("ls", "/"))` |
| per-exec timeout | `exec(cmd, timeout_s=30)` | `exec(cmd, { timeoutS: 30 })` | `&kosa8.CloudExecOptions{TimeoutS: 30}` | `exec(cmd, timeout_s: 30)` | `exec(cmd, null, null, 30)` |
| exit code | `r.exit_code` | `r.exitCode` | `r.ExitCode`, an `*int` (nil when the cloud sent none; `r.OK()` checks for 0) | `r.exit_code` | `r.exitCode()`, an `Integer` (may be null) |
| files | `box.files.write` / `read_text` / `list` | `box.files.write` / `readText` / `list` | `box.Files().WriteString` / `ReadText` / `List(ctx, "")` | `box.files.write` / `read_text` / `list` | `box.files().write` / `readText` / `list()` |
| no such sandbox | `Kosa8NotFoundError` | `Kosa8NotFoundError` | `*kosa8.Error` with `Kind` `KindNotFound` (`errors.Is(err, kosa8.ErrNotFound)`) | `Kosa8::NotFoundError` | `Kosa8Exception.NotFoundException` |

In every SDK a non-zero exit is returned in the result, not raised. Only `/workspace` is sure to
persist between commands, unless the sandbox is persistent.

## Local daemon

The same API against the kosa8 daemon on your machine (or a worker you run):

```java
Kosa8Client k = Kosa8Client.builder().build();          // local daemon at ~/.kosa8/run/kosa8.sock
k.createSandbox(new Options.Create().name("demo").egressAllow(List.of("pypi.org")));
Sandbox sb = k.sandbox("demo");
sb.pull("python:3.12-alpine");
System.out.print(sb.run("python:3.12-alpine", List.of("python", "-c", "print(6*7)")).stdout()); // 42

sb.checkpoint("clean");                                 // restore point, shared with `kosa8 rewind`
sb.rewind("clean");                                     // back to it, processes resumed
var best = sb.explore(List.of("make test", "make test-fast"), null); // best-of-N on forks
sb.delete();
```

java.net.http cannot talk over a unix socket, so the SDK speaks HTTP/1.1 itself over
`UnixDomainSocketAddress` (or TCP/TLS for a remote worker) and carries a small JSON codec.

## Connecting

| Builder method | Default |
|---|---|
| `socketPath` | `~/.kosa8/run/kosa8.sock` |
| `host` / `KOSA8_HOST` | a remote worker: `https://`, `http://` (loopback only unless `insecure(true)` / `KOSA8_INSECURE=1`), `tcp://`, `unix://` |
| `token` / `KOSA8_TOKEN` | sent as `Authorization: Bearer` to a remote worker |
| `actor` | `tool` (`human`, `agent`, `tool`), sent as `X-Kosa8-Actor` |
| `agentToken` / `KOSA8_AGENT_TOKEN` | sent as `X-Kosa8-Agent-Token` |
| `timeout` | none; per call with `Options.Run.timeout` |
| `workerFile` | `~/.kosa8/worker.json`; `null` reads none |

Without a `host` or `KOSA8_HOST`, and with no `socketPath` given, the client follows the
connection the CLI saved in `~/.kosa8/worker.json` (written by `kosa8 wsl setup` on Windows, where
the engine runs in WSL2, and by `kosa8 worker join` on any OS): its `host`, with its `token` unless
you pass `token`, and its `"insecure": true` allowing plain http to a LAN address. A saved token is
never sent to a host from `host` or `KOSA8_HOST`. A missing or malformed file, or a blank `host`,
means the local socket. `client.endpoint().target()` shows where requests go.

## Errors

All exceptions are unchecked and extend `Kosa8Exception`, carrying the daemon's message and remediation:
`ConnectionException`, `ApiException` › (`BadRequestException`, `AuthException`, `PolicyException` › `ApprovalRequiredException`
(`approval()`), `NotFoundException`, `ConflictException`, `UnsupportedException`, and from kosa8 Cloud
`PaymentRequiredException` (402), `OrgFrozenException` (423), `RateLimitException` (429) and `ExecTimeoutException`), `StreamException` (`stdout()`/`stderr()` so far),
`CheckpointException`, `TimeoutException`. Calls a policy may gate take `.approval(sig)` on their options.

## API

Daemon: `health`, `version`, `pull`.
Sandboxes: `listSandboxes`, `getSandbox`, `createSandbox`, `startSandbox`, `stopSandbox`, `deleteSandbox`,
`pullIntoSandbox`, `sandboxImages`, `sandboxContainers`, `sandboxEgress`.
Processes: `run`, `startContainer`, `exec`, `execFrames`, `startInteractive`, `writeStdin`, `attach`.
Snapshots: `snapshot`, `listSnapshots`, `restoreSnapshot`, `deleteSnapshot`, `fork`, `explore`.
Checkpoints: `checkpoint`, `listCheckpoints`, `rewind`.
Toolbox (daemon 0.13+): see below. `client.sandbox(id)` binds all of these to one sandbox.

Checkpoints live in the CLI's own `~/.kosa8/checkpoints.json`. Java can take only fcntl locks while the CLI
takes flock locks, which do not exclude each other on Linux: a Java checkpoint at the very instant a CLI
checkpoint is taken can lose one record. Java processes exclude each other, and every write is an atomic
rename. Use `MemoryCheckpointStore` to keep the SDK's checkpoints to itself.

## Toolbox

Run code, edit files, use git, keep a shell, open a terminal or a preview link, set a lifecycle and
build templates, each one call (see the [native API](https://docs.kosa8.com/guides/native-api/)):

```java
Sandbox sb = k.sandbox("demo");
Results.CodeResult r = sb.runCode("import sys; print(sys.version)", new Options.Code().timeout(Duration.ofSeconds(30)));
r.artifacts();                                          // matplotlib charts as PNG byte[]

sb.writeFile("/workspace/app.py", "print('hi')\n");
sb.listFiles("/workspace");                             // List<Results.FileEntry>
String text = sb.readText("/workspace/app.py");
sb.moveFile("/workspace/app.py", "/workspace/main.py");
sb.deleteFile("/workspace/build", new Options.DeleteFile().recursive(true));

sb.gitClone("https://github.com/octocat/Hello-World.git", "/workspace/hw");
sb.gitCommit("/workspace/hw", "change", new Options.Git().authorName("me").authorEmail("me@x")); // stages everything first
sb.git("log", new Options.Git().path("/workspace/hw").n(5)).log();

sb.shell("cd /workspace && export X=1");                // session "main", made on first use
sb.shell("echo $X; pwd").cwd();                         // cd and export are kept

String pty = sb.startPty(new Options.Pty().size(120, 40)); // then WebSocket to …/containers/{pty}/tty
Results.Preview p = sb.createPreview(8000, new Options.Preview().session("main"));
sb.setLifecycle(new Options.Lifecycle().autoStopMin(30).ttlMin(0)); // unset fields keep their value

k.buildTemplate("py-data", Path.of("."), new Options.Build().buildArgs(Map.of("V", "1")).onOutput(System.out::writeBytes));
k.createSandbox(new Options.Create().name("job").template("py-data").ephemeral(true).autoStopMin(10));
```

| Area | Methods |
|---|---|
| Code | `runCode` → `CodeResult` (`ok()` is exit 0 and not timed out; a non-zero exit is not an exception) |
| Files | `listFiles`, `statFile`, `readFile` (`byte[]`), `readText`, `writeFile` (`String`, `byte[]` or `InputStream`, `mode`), `deleteFile` (`recursive`), `makeDir`, `moveFile` |
| Git | `git(op, Options.Git)` for clone, status, add, commit, push, pull, checkout, branches, log, diff; `gitClone`, `gitStatus`, `gitCommit`. A git failure is `ok() == false` |
| Sessions | `createSession`, `listSessions`, `sessionExec`, `shell`, `deleteSession` |
| Terminals | `startPty`, `resizePty` |
| Previews | `createPreview`, `revokePreviews` (ends every link) |
| Lifecycle | `setLifecycle`; `Options.Create` takes `autoStopMin`, `ttlMin`, `autoDeleteMin`, `ephemeral`, `template` |
| Templates | `listTemplates`, `deleteTemplate`, `buildTemplate` |

`buildTemplate` tars the folder itself (no dependency), leaving out the paths `.dockerignore` lists
(as prefixes, like `kosa8 build`); names too long for ustar go in PAX headers, symlinks stay links. A failed
build throws `StreamException` with the build's error; the client's default timeout does not apply to it.

## Development

```sh
./test.sh                                              # mock daemon on a unix socket, no test deps
java -cp build/main examples/Smoke.java                # live, against a running `kosa8 daemon`
```

MIT licensed.
