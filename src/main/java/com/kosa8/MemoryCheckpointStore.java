package com.kosa8;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Keeps stacks in memory; nothing is shared with the CLI. */
public final class MemoryCheckpointStore implements CheckpointStore {
  private Map<String, List<Checkpoint>> data = new HashMap<>();

  static Map<String, List<Checkpoint>> copy(Map<String, List<Checkpoint>> d) {
    Map<String, List<Checkpoint>> out = new HashMap<>();
    d.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
    return out;
  }

  @Override
  public synchronized Map<String, List<Checkpoint>> load() { return copy(data); }

  @Override
  public synchronized <T> T update(Function<Map<String, List<Checkpoint>>, T> fn) {
    Map<String, List<Checkpoint>> d = copy(data);
    T r = fn.apply(d);
    data = d;
    return r;
  }
}
