# kosa8 (Java)

The Java SDK for [kosa8](https://kosa8.com): microVM sandboxes for AI agents, on kosa8 Cloud or on
your own machine's daemon. No dependencies; Java 17+. Method for method it matches
[`@kosa8/sdk`](https://github.com/kosa8ai/kosa8-js), the Python `kosa8` package, `kosa8-go` and the Ruby gem.

## Install

Maven:

```xml
<dependency>
  <groupId>com.kosa8</groupId>
  <artifactId>kosa8</artifactId>
  <version>0.4.0</version>
</dependency>
```

Gradle:

```groovy
implementation "com.kosa8:kosa8:0.4.0"
```

0.4.0 adds the kosa8 Cloud features below; until it is on Maven Central the latest published
version is 0.3.1 (which has `Kosa8Cloud` with create, exec, sleep, wake and delete).

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
yet; `exec`, `sleep`, `wake`, `update` and `delete` work everywhere.

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

### The base template

The default template, `base`, is **Alpine Linux**: `sh` is busybox, and there is no bash, python or
git. Use `template("python")` (or another key from `cloud.templates()`) for Python.

- **Each exec runs in a fresh container.** Only `/workspace` persists, so `cd`, `export` and packages
  installed elsewhere are gone by the next exec. Install packages into `/workspace` (for example
  `pip install --target /workspace/lib ...`).
- **A buffered exec is cut off after about 30 s** and throws `Kosa8Exception.ExecTimeoutException`.
  Run long work in the background and poll it:
  `box.exec("nohup sh -c 'make test > /workspace/test.log 2>&1; echo $? > /workspace/test.exit' > /dev/null 2>&1 &")`,
  then read `/workspace/test.exit` later.
- A request body (command and env) is at most **64 KiB** (413 `too_large`); a file is at most **8 MiB**.

### Cloud errors

Every error from the cloud is a `Kosa8Exception` carrying its stable `code()`, `status()` and raw `body()`.

| Exception | When | What to do |
|---|---|---|
| `PaymentRequiredException` (402) | `payment_required` (no payment method), `spending_cap_reached`, `price_not_accepted` | show `upgradeUrl()` to the person; an agent relays `agentMessage()` word for word and stops |
| `OrgFrozenException` (423 `org_frozen`) | the org is frozen in the Security Center | an owner or admin must unfreeze it |
| `RateLimitException` (429) | too many requests, or `quota_exceeded` (active sandbox limit) | wait `retryAfter()` (an `Optional<Duration>`) when set, or delete sandboxes |
| `ExecTimeoutException` (504 `exec_timeout`, or today's 502 "timed out") | a buffered exec ran past about 30 s | run it in the background and poll |
| `UnsupportedException` (501 `not_available` / `not_supported`) | the host has no data plane | use exec, or try later |

A new organization runs nothing until a payment method is on file; until then creating a sandbox
throws `PaymentRequiredException` with `code()` `payment_required`.

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
