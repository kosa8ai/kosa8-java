package com.kosa8;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal HTTP/1.1 client: java.net.http cannot speak over a unix socket, so the SDK
 * writes requests and reads responses itself, one connection per request.
 */
final class Transport {
  static final String APPROVAL_HEADER = "X-Kosa8-Approval";
  static final String ACTOR_HEADER = "X-Kosa8-Actor";
  static final String AGENT_TOKEN_HEADER = "X-Kosa8-Agent-Token";

  private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "kosa8-timeout");
    t.setDaemon(true);
    return t;
  });

  final Endpoint endpoint;
  private final String actor;
  private final String agentToken;
  private final Map<String, String> headers;
  private final Duration timeout;

  Transport(Endpoint endpoint, String actor, String agentToken, Map<String, String> headers, Duration timeout) {
    this.endpoint = endpoint;
    if (!actor.equals("human") && !actor.equals("agent") && !actor.equals("tool"))
      throw new Kosa8Exception("actor must be human, agent or tool, not \"" + actor + "\"");
    this.actor = actor;
    this.agentToken = agentToken;
    this.headers = headers == null ? Map.of() : headers;
    this.timeout = timeout;
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** One request. Body is a JSON value, a byte[] or an InputStream (sent chunked). */
  static final class Request {
    final String method;
    final String path;
    final Map<String, Object> query = new LinkedHashMap<>();
    Object body;
    String approval;
    Duration timeout;

    Request(String method, String path) {
      this.method = method;
      this.path = path;
    }

    Request body(Object b) { body = b; return this; }
    Request approval(String a) { approval = a; return this; }
    Request timeout(Duration t) { timeout = t; return this; }
    Request query(String k, Object v) { query.put(k, v); return this; }
  }

  /** An open response; close it to drop the connection. */
  static final class Response implements Closeable {
    final int status;
    final InputStream body;
    private final Closeable conn;
    private final ScheduledFuture<?> watchdog;
    final AtomicBoolean timedOut;
    /** Response headers, lower-cased names. */
    final Map<String, String> headers;

    Response(int status, Map<String, String> headers, InputStream body, Closeable conn, ScheduledFuture<?> watchdog,
        AtomicBoolean timedOut) {
      this.status = status;
      this.headers = headers;
      this.body = body;
      this.conn = conn;
      this.watchdog = watchdog;
      this.timedOut = timedOut;
    }

    byte[] readAll() throws IOException { return body.readAllBytes(); }

    @Override
    public void close() {
      if (watchdog != null) watchdog.cancel(false);
      try {
        conn.close();
      } catch (IOException ignored) {
        // already gone
      }
    }
  }

  Response open(Request r) {
    StringBuilder target = new StringBuilder(endpoint.isUnix() ? "" : endpoint.basePath()).append(r.path);
    String sep = "?";
    for (Map.Entry<String, Object> q : r.query.entrySet()) {
      Object v = q.getValue();
      if (v == null || Boolean.FALSE.equals(v)) continue;
      target.append(sep).append(enc(q.getKey())).append('=').append(enc(Boolean.TRUE.equals(v) ? "1" : v.toString()));
      sep = "&";
    }
    Duration t = r.timeout != null ? r.timeout : timeout;
    AtomicBoolean timedOut = new AtomicBoolean();
    Closeable conn = null;
    ScheduledFuture<?> dog = null;
    try {
      InputStream in;
      OutputStream out;
      if (endpoint.isUnix()) {
        SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
        conn = ch;
        ch.connect(UnixDomainSocketAddress.of(endpoint.socketPath()));
        in = Channels.newInputStream(ch);
        out = Channels.newOutputStream(ch);
      } else {
        Socket s = endpoint.scheme().equals("https") ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        conn = s;
        s.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), 30_000);
        in = s.getInputStream();
        out = s.getOutputStream();
      }
      if (t != null && !t.isZero()) {
        Closeable c = conn;
        dog = WATCHDOG.schedule(() -> {
          timedOut.set(true);
          try {
            c.close();
          } catch (IOException ignored) {
            // closing is the point
          }
        }, t.toMillis(), TimeUnit.MILLISECONDS);
      }
      writeRequest(out, r, target.toString());
      BufferedInputStream bin = new BufferedInputStream(in);
      String statusLine = readLine(bin);
      if (statusLine == null) throw new IOException("connection closed before a response (EOF)");
      String[] parts = statusLine.split(" ", 3);
      int status = Integer.parseInt(parts[1]);
      Map<String, String> h = new LinkedHashMap<>();
      String line;
      while ((line = readLine(bin)) != null && !line.isEmpty()) {
        int i = line.indexOf(':');
        if (i > 0) h.put(line.substring(0, i).strip().toLowerCase(Locale.ROOT), line.substring(i + 1).strip());
      }
      InputStream body;
      if (h.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")) body = new ChunkedIn(bin);
      else if (h.containsKey("content-length")) body = new LimitedIn(bin, Long.parseLong(h.get("content-length")));
      else body = bin;
      return new Response(status, h, body, conn, dog, timedOut);
    } catch (IOException | RuntimeException e) {
      if (dog != null) dog.cancel(false);
      closeQuietly(conn);
      if (e instanceof Kosa8Exception k) throw k;
      if (timedOut.get()) throw timeout(r);
      String msg = String.valueOf(e.getMessage());
      String hint = msg.contains("No such file") || msg.contains("Connection refused") || msg.contains("does not exist")
          || e instanceof java.net.ConnectException || e instanceof java.nio.file.NoSuchFileException
          ? " (is kosa8 installed and its daemon running? start it with `kosa8 daemon start`)" : "";
      throw new Kosa8Exception.ConnectionException("kosa8 daemon unreachable at " + endpoint.target() + ": "
          + e.getClass().getSimpleName() + ": " + msg + hint, r.method, r.path, e);
    }
  }

  Kosa8Exception timeout(Request r) {
    return new Kosa8Exception.TimeoutException(r.method + " " + r.path + " timed out talking to " + endpoint.target(),
        r.method, r.path);
  }

  private void writeRequest(OutputStream out, Request r, String target) throws IOException {
    StringBuilder h = new StringBuilder();
    h.append(r.method).append(' ').append(target).append(" HTTP/1.1\r\n");
    h.append("Host: ").append(endpoint.isUnix() ? "kosa8" : endpoint.host()).append("\r\n");
    h.append("Connection: close\r\nAccept: application/json\r\n");
    h.append(ACTOR_HEADER).append(": ").append(actor).append("\r\n");
    for (Map.Entry<String, String> e : headers.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
    if (r.approval != null && !r.approval.isBlank()) h.append(APPROVAL_HEADER).append(": ").append(r.approval.strip()).append("\r\n");
    if (agentToken != null && !agentToken.isEmpty()) h.append(AGENT_TOKEN_HEADER).append(": ").append(agentToken).append("\r\n");
    if (!endpoint.isUnix() && endpoint.token() != null) h.append("Authorization: Bearer ").append(endpoint.token()).append("\r\n");
    byte[] fixed = null;
    InputStream stream = null;
    if (r.body instanceof InputStream is) {
      stream = is;
      h.append("Content-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\n");
    } else if (r.body instanceof byte[] b) {
      fixed = b;
      h.append("Content-Type: application/octet-stream\r\n");
    } else if (r.body != null) {
      fixed = Json.write(r.body).getBytes(StandardCharsets.UTF_8);
      h.append("Content-Type: application/json\r\n");
    }
    if (fixed != null) h.append("Content-Length: ").append(fixed.length).append("\r\n");
    else if (stream == null && !r.method.equals("GET") && !r.method.equals("DELETE")) h.append("Content-Length: 0\r\n");
    h.append("\r\n");
    out.write(h.toString().getBytes(StandardCharsets.UTF_8));
    if (fixed != null) out.write(fixed);
    if (stream != null) {
      byte[] buf = new byte[16 * 1024];
      int n;
      while ((n = stream.read(buf)) > 0) {
        out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(buf, 0, n);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
      }
      out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    }
    out.flush();
  }

  static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) >= 0) {
      if (c == '\n') break;
      if (c != '\r') b.write(c);
    }
    if (c < 0 && b.size() == 0) return null;
    return b.toString(StandardCharsets.UTF_8);
  }

  static Object decode(byte[] b) {
    String s = new String(b, StandardCharsets.UTF_8);
    if (s.isBlank()) return null;
    try {
      return Json.parse(s);
    } catch (IllegalArgumentException e) {
      return s;
    }
  }

  /** A request whose answer is JSON. */
  Object json(Request r) {
    try (Response resp = open(r)) {
      byte[] b;
      try {
        b = resp.readAll();
      } catch (IOException e) {
        if (resp.timedOut.get()) throw timeout(r);
        throw new Kosa8Exception.ConnectionException("read " + r.method + " " + r.path + ": " + e.getMessage(), r.method, r.path, e);
      }
      Object v = decode(b);
      if (resp.status >= 400) throw Kosa8Exception.fromResponse(resp.status, v, r.method, r.path);
      return v;
    }
  }

  /** A request whose answer is NDJSON frames; each is passed to fn with data decoded from base64. */
  void frames(Request r, Consumer<Frame> fn) {
    framesRaw(r, m -> fn.accept(Frame.of(m)));
  }

  /** A request whose answer is NDJSON frames; each is passed to fn as the decoded JSON object. */
  void framesRaw(Request r, Consumer<Object> fn) {
    try (Response resp = open(r)) {
      if (resp.status >= 400) {
        byte[] b;
        try {
          b = resp.readAll();
        } catch (IOException e) {
          b = new byte[0];
        }
        throw Kosa8Exception.fromResponse(resp.status, decode(b), r.method, r.path);
      }
      try {
        String line;
        while ((line = readLine(resp.body)) != null) {
          if (line.isBlank()) continue;
          fn.accept(Json.parse(line));
        }
      } catch (IOException e) {
        if (resp.timedOut.get()) throw timeout(r);
        throw new Kosa8Exception.StreamException("stream broke: " + e.getMessage(), "", "", null);
      } catch (IllegalArgumentException e) {
        throw new Kosa8Exception.StreamException("bad frame from " + r.path + ": " + e.getMessage(), "", "", null);
      }
    }
  }

  private static void closeQuietly(Closeable c) {
    if (c == null) return;
    try {
      c.close();
    } catch (IOException ignored) {
      // best effort
    }
  }

  private static final class LimitedIn extends InputStream {
    private final InputStream in;
    private long left;

    LimitedIn(InputStream in, long n) {
      this.in = in;
      this.left = n;
    }

    @Override
    public int read() throws IOException {
      if (left <= 0) return -1;
      int c = in.read();
      if (c >= 0) left--;
      return c;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (left <= 0) return -1;
      int n = in.read(b, off, (int) Math.min(len, left));
      if (n > 0) left -= n;
      return n;
    }
  }

  private static final class ChunkedIn extends InputStream {
    private final InputStream in;
    private long left;
    private boolean eof;

    ChunkedIn(InputStream in) { this.in = in; }

    private boolean fill() throws IOException {
      if (eof) return false;
      if (left > 0) return true;
      String line = readLine(in);
      if (line == null) throw new IOException("stream ended mid-chunk (EOF)");
      if (line.isEmpty()) line = readLine(in); // CRLF after the previous chunk
      if (line == null) throw new IOException("stream ended mid-chunk (EOF)");
      int semi = line.indexOf(';');
      left = Long.parseLong((semi >= 0 ? line.substring(0, semi) : line).strip(), 16);
      if (left == 0) {
        eof = true;
        return false;
      }
      return true;
    }

    @Override
    public int read() throws IOException {
      if (!fill()) return -1;
      int c = in.read();
      if (c >= 0) left--;
      return c;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (!fill()) return -1;
      int n = in.read(b, off, (int) Math.min(len, left));
      if (n > 0) left -= n;
      return n;
    }
  }
}
