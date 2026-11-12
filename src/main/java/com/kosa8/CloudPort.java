package com.kosa8;

import java.util.Map;

/** A TCP port listening in one of a cloud sandbox's running containers; loopback when bound to 127.0.0.1 / ::1 only. */
public record CloudPort(int port, String address, String container, boolean loopback) {
  static CloudPort of(Map<String, Object> m) {
    Long p = CloudSandbox.l(m.get("port"));
    return new CloudPort(p == null ? 0 : p.intValue(), CloudSandbox.s(m.get("address")), CloudSandbox.s(m.get("container")),
        Boolean.TRUE.equals(m.get("loopback")));
  }
}
