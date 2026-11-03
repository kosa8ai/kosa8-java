package com.kosa8;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Keeps checkpoint stacks, per sandbox, oldest first. */
public interface CheckpointStore {
  /** A copy of the stacks. */
  Map<String, List<Checkpoint>> load();

  /** Runs fn on a mutable copy of the stacks and saves what it leaves, serialised against other updates. */
  <T> T update(Function<Map<String, List<Checkpoint>>, T> fn);
}
