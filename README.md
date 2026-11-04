# kosa8 (Java)

The Java SDK for [kosa8](https://kosa8.com): microVM sandboxes for AI agents. No dependencies; Java 17+.
Method for method it matches [`@kosa8/sdk`](https://github.com/kosa8ai/kosa8-js), the Python `kosa8` package, `kosa8-go` and the Ruby gem.

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

## Errors

All exceptions are unchecked and extend `Kosa8Exception`, carrying the daemon's message and remediation:
`ConnectionException`, `ApiException` › (`BadRequestException`, `AuthException`, `PolicyException` › `ApprovalRequiredException`
(`approval()`), `NotFoundException`, `ConflictException`, `UnsupportedException`), `StreamException` (`stdout()`/`stderr()` so far),
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
