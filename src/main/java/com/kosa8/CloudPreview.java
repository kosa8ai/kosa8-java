package com.kosa8;

import java.util.Map;

/**
 * A port of a cloud sandbox at its own https URL. A private preview's url carries its signed token
 * (keep it secret); a listing has no tokens.
 */
public record CloudPreview(int port, boolean isPublic, String url, String host, String token, String expiresAt,
    boolean wake, Integer wakePerHour) {

  static CloudPreview of(Map<String, Object> m) {
    Long p = CloudSandbox.l(m.get("port"));
    Long w = CloudSandbox.l(m.get("wake_per_hour"));
    return new CloudPreview(p == null ? 0 : p.intValue(), Boolean.TRUE.equals(m.get("public")), CloudSandbox.s(m.get("url")),
        CloudSandbox.s(m.get("host")), CloudSandbox.s(m.get("token")), CloudSandbox.s(m.get("expires_at")),
        Boolean.TRUE.equals(m.get("wake")), w == null ? null : w.intValue());
  }

  /** Never the token. */
  @Override
  public String toString() { return "CloudPreview(port=" + port + ", public=" + isPublic + ", host=" + host + ")"; }
}
