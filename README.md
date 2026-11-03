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
Checkpoints: `checkpoint`, `listCheckpoints`, `rewind`. `client.sandbox(id)` binds them to one sandbox.

Checkpoints live in the CLI's own `~/.kosa8/checkpoints.json`. Java can take only fcntl locks while the CLI
takes flock locks, which do not exclude each other on Linux: a Java checkpoint at the very instant a CLI
checkpoint is taken can lose one record. Java processes exclude each other, and every write is an atomic
rename. Use `MemoryCheckpointStore` to keep the SDK's checkpoints to itself.

## Development

```sh
./test.sh                                              # mock daemon on a unix socket, no test deps
java -cp build/main examples/Smoke.java                # live, against a running `kosa8 daemon`
```

MIT licensed.
