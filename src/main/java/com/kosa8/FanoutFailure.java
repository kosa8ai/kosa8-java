package com.kosa8;

import java.util.Map;

/**
 * One sandbox a batch or fork did not make (index counts from 0), or one a drain could not delete (id): its
 * error code and message, and whose fault it was.
 */
public record FanoutFailure(Integer index, String id, String code, String message, String failureClass, boolean retryable) {
  static FanoutFailure of(Map<String, Object> m) {
    String fc = m.get("failure_class") instanceof String s ? s : "infra";
    boolean rt = m.get("retryable") instanceof Boolean b ? b : "infra".equals(fc);
    return new FanoutFailure(m.get("index") instanceof Number n ? n.intValue() : null, CloudSandbox.s(m.get("id")),
        CloudSandbox.s(m.get("code")), CloudSandbox.s(m.get("message")), fc, rt);
  }

  /** As an exception, for a sandbox that could not be made. */
  Kosa8Exception toException() {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("error", code);
    body.put("message", message);
    body.put("failure_class", failureClass);
    body.put("retryable", retryable);
    return new Kosa8Exception.ApiException(message != null ? message : String.valueOf(code), 0, message, null, "POST", null, body).withCode(code);
  }
}
