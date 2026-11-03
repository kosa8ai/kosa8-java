package com.kosa8;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/** A tiny HTTP/1.1 server on a unix socket that answers like kosa8d. */
final class MockDaemon implements AutoCloseable {
  record Req(String method, String path, String rawTarget, Map<String, String> headers, byte[] body) {
    String bodyText() { return new String(body, StandardCharsets.UTF_8); }
    @SuppressWarnings("unchecked")
    Map<String, Object> json() { return (Map<String, Object>) Json.parse(bodyText()); }
  }

  /** A handler's answer: JSON with a status, NDJSON frames, or raw bytes under a declared length. */
  record Reply(int status, Object json, List<Map<String, Object>> frames, byte[] raw, long length) {}

  static Reply json(int status, Object body) { return new Reply(status, body, null, null, 0); }

  @SafeVarargs
  static Reply frames(Map<String, Object>... fs) { return new Reply(200, null, List.of(fs), null, 0); }

  /** Bytes with a Content-Length of length (more than raw.length makes a short body). */
  static Reply bytes(byte[] raw, long length) { return new Reply(200, null, null, raw, length); }

  static Map<String, Object> frame(String stream, String data) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("stream", stream);
    m.put("data", Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8)));
    return m;
  }

  static Map<String, Object> done(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("done", true);
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  private record Route(String method, Pattern path, Function<Req, Reply> h) {}

  final Path dir;
  final String socketPath;
  private final ServerSocketChannel server;
  private final List<Route> routes = new ArrayList<>();
  final List<Req> requests = new ArrayList<>();
  private final Thread acceptor;

  MockDaemon() throws IOException {
    // /tmp keeps the socket path short (macOS's temp dir is long); Windows has no /tmp but takes AF_UNIX in its temp dir.
    dir = Transport.WINDOWS ? Files.createTempDirectory("k8j") : Files.createTempDirectory(Path.of("/tmp"), "k8j");
    socketPath = dir.resolve("k.sock").toString();
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socketPath));
    acceptor = new Thread(() -> {
      while (server.isOpen()) {
        try {
          SocketChannel c = server.accept();
          Thread th = new Thread(() -> serve(c));
          th.setDaemon(true);
          th.start();
        } catch (IOException e) {
          return;
        }
      }
    });
    acceptor.setDaemon(true);
    acceptor.start();
  }

  synchronized void on(String method, String pathRegex, Function<Req, Reply> h) {
    routes.add(0, new Route(method, Pattern.compile(pathRegex), h));
  }

  synchronized Req last() { return requests.get(requests.size() - 1); }

  Kosa8Client client() {
    return Kosa8Client.builder().socketPath(socketPath).checkpoints(new MemoryCheckpointStore()).getenv(k -> null).build();
  }

  private void serve(SocketChannel c) {
    try (c) {
      InputStream in = new BufferedInputStream(Channels.newInputStream(c));
      OutputStream out = Channels.newOutputStream(c);
      String line = Transport.readLine(in);
      if (line == null) return;
      String[] p = line.split(" ");
      Map<String, String> h = new LinkedHashMap<>();
      String l;
      while ((l = Transport.readLine(in)) != null && !l.isEmpty()) {
        int i = l.indexOf(':');
        h.put(l.substring(0, i).strip().toLowerCase(Locale.ROOT), l.substring(i + 1).strip());
      }
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      if (h.getOrDefault("transfer-encoding", "").contains("chunked")) {
        while (true) {
          int n = Integer.parseInt(Transport.readLine(in).strip(), 16);
          if (n == 0) {
            Transport.readLine(in);
            break;
          }
          body.write(in.readNBytes(n));
          Transport.readLine(in);
        }
      } else if (h.containsKey("content-length")) {
        body.write(in.readNBytes(Integer.parseInt(h.get("content-length"))));
      }
      String target = p[1];
      String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
      Req req = new Req(p[0], path, target, h, body.toByteArray());
      Function<Req, Reply> handler = null;
      synchronized (this) {
        requests.add(req);
        for (Route r : routes) {
          if (r.method.equals(req.method) && r.path.matcher(path).matches()) {
            handler = r.h;
            break;
          }
        }
      }
      Reply reply = handler != null ? handler.apply(req) : json(404, Map.of("error", "no route " + req.method + " " + path));
      if (reply.raw != null) {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + reply.length
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(reply.raw);
      } else if (reply.frames == null) {
        byte[] b = Json.write(reply.json).getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + reply.status + " X\r\nContent-Type: application/json\r\nContent-Length: " + b.length
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
      } else {
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
            .getBytes(StandardCharsets.UTF_8));
        for (Map<String, Object> f : reply.frames) {
          Map<String, Object> copy = new LinkedHashMap<>(f);
          Object sleep = copy.remove("_sleep");
          if (sleep instanceof Number n) Thread.sleep(n.longValue());
          byte[] b = (Json.write(copy) + "\n").getBytes(StandardCharsets.UTF_8);
          out.write((Integer.toHexString(b.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
          out.write(b);
          out.write("\r\n".getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      }
      out.flush();
    } catch (Exception e) {
      System.err.println("mock: " + e);
    }
  }

  @Override
  public void close() throws IOException {
    server.close();
    Files.deleteIfExists(Path.of(socketPath));
    Files.deleteIfExists(dir);
  }
}
