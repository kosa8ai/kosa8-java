package com.kosa8;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a batch create or a fork made: the sandboxes (in index order), the ones it could not make
 * ({@link #failed()}, with partialOk), how many were asked for and made, and the run they belong to.
 */
public record SandboxBatch(List<CloudSandbox> sandboxes, List<FanoutFailure> failed, int requested, int created, String runId) {
  static SandboxBatch of(Kosa8Cloud cloud, Map<String, Object> r, int n) {
    List<CloudSandbox> boxes = new ArrayList<>();
    for (Map<String, Object> m : Kosa8Cloud.objs(r.get("sandboxes"))) boxes.add(new CloudSandbox(cloud, m));
    List<FanoutFailure> failed = new ArrayList<>();
    for (Map<String, Object> m : Kosa8Cloud.objs(r.get("failed"))) failed.add(FanoutFailure.of(m));
    int req = r.get("requested") instanceof Number x ? x.intValue() : n;
    int made = r.get("created") instanceof Number x ? x.intValue() : boxes.size();
    return new SandboxBatch(List.copyOf(boxes), List.copyOf(failed), req, made, CloudSandbox.s(r.get("run_id")));
  }

  /** Whether every one asked for was made. */
  public boolean complete() { return failed.isEmpty() && sandboxes.size() == requested; }

  /** Their ids. */
  public List<String> ids() {
    List<String> out = new ArrayList<>();
    for (CloudSandbox b : sandboxes) out.add(b.id());
    return out;
  }
}
