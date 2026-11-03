package com.kosa8;

import java.util.Base64;
import java.util.Map;

/** One NDJSON frame of a run, exec or attach stream. */
public record Frame(String stream, byte[] data, boolean done, boolean ok, String error, Integer exit, String id) {
  @SuppressWarnings("unchecked")
  static Frame of(Object o) {
    if (!(o instanceof Map<?, ?> m0)) throw new IllegalArgumentException("frame is not an object");
    Map<String, Object> m = (Map<String, Object>) m0;
    byte[] data = m.get("data") instanceof String s && !s.isEmpty() ? Base64.getDecoder().decode(s) : new byte[0];
    Integer exit = m.get("exit") instanceof Number n ? n.intValue() : null;
    return new Frame(str(m.get("stream")), data, Boolean.TRUE.equals(m.get("done")), Boolean.TRUE.equals(m.get("ok")),
        str(m.get("error")), exit, str(m.get("id")));
  }

  private static String str(Object o) { return o instanceof String s && !s.isEmpty() ? s : null; }
}
