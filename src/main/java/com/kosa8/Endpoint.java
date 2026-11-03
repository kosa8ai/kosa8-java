package com.kosa8;

import java.net.URI;
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

  /**
   * The CLI's KOSA8_HOST rules: no host means the local socket; https://, http://, tcp://,
   * unix:// or a bare host:port select a worker; plain http to a non-loopback host is refused
   * unless insecure (or KOSA8_INSECURE=1). KOSA8_TOKEN is used only for a host that came from
   * KOSA8_HOST.
   */
  public static Endpoint resolve(String host, String socketPath, String token, boolean insecure,
      Function<String, String> getenv) {
    if (getenv == null) getenv = System::getenv;
    boolean explicit = host != null && !host.isEmpty();
    String raw = explicit ? host : nz(getenv.apply("KOSA8_HOST"));
    raw = raw.strip().replaceAll("/+$", "");
    if (raw.isEmpty()) return new Endpoint(socketPath != null ? socketPath : defaultSocketPath(), null, null, 0, null, null);
    String h = raw;
    int sep = h.indexOf("://");
    if (sep > 0) h = h.substring(0, sep).toLowerCase() + h.substring(sep);
    if (h.startsWith("unix://")) return new Endpoint(h.substring(7), null, null, 0, null, null);
    if (h.startsWith("tcp://")) h = "http://" + h.substring(6);
    else if (sep > 0 && !h.startsWith("http://") && !h.startsWith("https://"))
      throw new Kosa8Exception("KOSA8_HOST scheme \"" + h.substring(0, sep)
          + "\" is not supported; use http://, https://, tcp:// or unix://");
    else if (sep <= 0) h = "http://" + h;
    URI u;
    try {
      u = URI.create(h);
    } catch (IllegalArgumentException e) {
      throw new Kosa8Exception("KOSA8_HOST \"" + raw + "\" names no host");
    }
    if (u.getHost() == null || u.getHost().isEmpty()) throw new Kosa8Exception("KOSA8_HOST \"" + raw + "\" names no host");
    int port = u.getPort() > 0 ? u.getPort() : (u.getScheme().equals("https") ? 443 : 80);
    String hp = u.getHost() + ":" + port;
    if (u.getScheme().equals("http") && !insecure && !"1".equals(getenv.apply("KOSA8_INSECURE")) && !loopback(u.getHost()))
      throw new Kosa8Exception("refusing to talk to " + hp + " over plain http: the token and every request would cross"
          + " the network unencrypted. Use https://" + hp + " (a worker behind a TLS-terminating tunnel or proxy),"
          + " or set insecure / KOSA8_INSECURE=1 if this link is already private and trusted");
    if ((token == null || token.isEmpty()) && !explicit) token = getenv.apply("KOSA8_TOKEN");
    if (token != null && token.isEmpty()) token = null;
    String base = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
    return new Endpoint(null, u.getScheme(), u.getHost(), port, base, token);
  }

  static String nz(String s) { return s == null ? "" : s; }

  /** For tests: an environment from a map. */
  static Function<String, String> env(Map<String, String> m) { return m::get; }
}
