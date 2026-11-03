package com.kosa8;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/** Where requests go: a unix socket or a remote worker. */
public record Endpoint(String socketPath, String scheme, String host, int port, String basePath, String token) {

  public boolean isUnix() { return socketPath != null; }

  /** Describes the endpoint for humans. */
  public String target() {
    return isUnix() ? "unix://" + socketPath : scheme + "://" + host + ":" + port;
  }

  /** $HOME/.kosa8/run/kosa8.sock (pkg/paths). */
  public static String defaultSocketPath() {
    return Path.of(System.getProperty("user.home"), ".kosa8", "run", "kosa8.sock").toString();
  }

  static boolean loopback(String host) {
    String h = host.replace("[", "").replace("]", "");
    return h.equals("localhost") || h.equals("::1") || h.startsWith("127.");
  }

  /** $HOME/.kosa8/worker.json: the connection {@code kosa8 wsl setup} and {@code kosa8 worker join} save. */
  public static Path defaultWorkerFile() {
    return Path.of(System.getProperty("user.home"), ".kosa8", "worker.json");
  }

  /** The fields of a saved worker connection that matter. */
  record Saved(String host, String token, boolean insecure) {}

  /**
   * Reads a saved connection; missing, unreadable or malformed files and a blank host all mean
   * none (null), silently, as in the CLI.
   */
  static Saved readSaved(Path file) {
    if (file == null) return null;
    try {
      Object v = Json.parse(Files.readString(file));
      if (!(v instanceof Map<?, ?> m)) return null;
      if (!(m.get("host") instanceof String h) || h.isBlank()) return null;
      String tok = m.get("token") instanceof String t ? t : null;
      return new Saved(h, tok, Boolean.TRUE.equals(m.get("insecure")));
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** {@link #resolve(String, String, String, boolean, Function, Path)} without a saved connection. */
  public static Endpoint resolve(String host, String socketPath, String token, boolean insecure,
      Function<String, String> getenv) {
    return resolve(host, socketPath, token, insecure, getenv, null);
  }

  /**
   * The CLI's rules. An explicit host, else a non-blank KOSA8_HOST: https://, http://, tcp://,
   * unix:// or a bare host:port select a worker, with token the explicit one, else KOSA8_TOKEN only
   * for a host from KOSA8_HOST. Else, when no socket path was given and {@code workerFile} (may be
   * null) holds a saved host, that host with the explicit token, else the saved one; its
   * {@code "insecure": true} counts like {@code insecure}. Else the local socket. Plain http to a
   * non-loopback host is refused unless insecure (or KOSA8_INSECURE=1).
   */
  public static Endpoint resolve(String host, String socketPath, String token, boolean insecure,
      Function<String, String> getenv, Path workerFile) {
    if (getenv == null) getenv = System::getenv;
    boolean explicit = host != null && !host.isBlank();
    String envHost = nz(getenv.apply("KOSA8_HOST"));
    String raw;
    String source = "KOSA8_HOST";
    if (explicit) {
      raw = host;
    } else if (!envHost.isBlank()) {
      raw = envHost;
      if (token == null || token.isEmpty()) token = getenv.apply("KOSA8_TOKEN");
    } else {
      Saved w = socketPath == null ? readSaved(workerFile) : null;
      if (w == null) return new Endpoint(socketPath != null ? socketPath : defaultSocketPath(), null, null, 0, null, null);
      raw = w.host();
      source = "saved worker host (" + workerFile + ")";
      if (token == null || token.isEmpty()) token = w.token();
      insecure = insecure || w.insecure();
    }
    raw = raw.strip().replaceAll("/+$", "");
    if (raw.isEmpty()) return new Endpoint(socketPath != null ? socketPath : defaultSocketPath(), null, null, 0, null, null);
    String h = raw;
    int sep = h.indexOf("://");
    if (sep > 0) h = h.substring(0, sep).toLowerCase() + h.substring(sep);
    if (h.startsWith("unix://")) return new Endpoint(h.substring(7), null, null, 0, null, null);
    if (h.startsWith("tcp://")) h = "http://" + h.substring(6);
    else if (sep > 0 && !h.startsWith("http://") && !h.startsWith("https://"))
      throw new Kosa8Exception(source + " scheme \"" + h.substring(0, sep)
          + "\" is not supported; use http://, https://, tcp:// or unix://");
    else if (sep <= 0) h = "http://" + h;
    URI u;
    try {
      u = URI.create(h);
    } catch (IllegalArgumentException e) {
      throw new Kosa8Exception(source + " \"" + raw + "\" names no host");
    }
    if (u.getHost() == null || u.getHost().isEmpty()) throw new Kosa8Exception(source + " \"" + raw + "\" names no host");
    int port = u.getPort() > 0 ? u.getPort() : (u.getScheme().equals("https") ? 443 : 80);
    String hp = u.getHost() + ":" + port;
    if (u.getScheme().equals("http") && !insecure && !"1".equals(getenv.apply("KOSA8_INSECURE")) && !loopback(u.getHost()))
      throw new Kosa8Exception("refusing to talk to " + hp + " over plain http: the token and every request would cross"
          + " the network unencrypted. Use https://" + hp + " (a worker behind a TLS-terminating tunnel or proxy),"
          + " or set insecure / KOSA8_INSECURE=1 if this link is already private and trusted");
    if (token != null && token.isEmpty()) token = null;
    String base = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
    return new Endpoint(null, u.getScheme(), u.getHost(), port, base, token);
  }

  static String nz(String s) { return s == null ? "" : s; }

  /** Never prints the token: an Endpoint ends up in logs and exception messages. */
  @Override
  public String toString() {
    return "Endpoint[" + (socketPath != null ? "unix://" + socketPath : scheme + "://" + host + ":" + port + nz(basePath))
        + (token != null ? ", token=***" : "") + "]";
  }

  /** For tests: an environment from a map. */
  static Function<String, String> env(Map<String, String> m) { return m::get; }
}
