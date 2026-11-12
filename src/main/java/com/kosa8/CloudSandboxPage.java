package com.kosa8;

import java.util.List;

/**
 * One page of {@code cloud.sandboxes().listPage(...)}. nextCursor is null at the end; pass it to
 * {@link CloudOptions.List#cursor(String)} for the next page. A server that does not page answers
 * everything at once, with no cursor.
 */
public record CloudSandboxPage(List<CloudSandbox> sandboxes, String nextCursor) {
  public CloudSandboxPage {
    sandboxes = List.copyOf(sandboxes);
  }

  public boolean hasNext() { return nextCursor != null; }
}
