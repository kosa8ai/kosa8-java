package com.kosa8;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The results of a {@link Run#map}, in input order (task-major, groupSize per task). */
public final class Rollouts extends ArrayList<RolloutResult> {
  private static final long serialVersionUID = 1L;

  public Rollouts() {}

  public Rollouts(Collection<RolloutResult> c) { super(c); }

  /** Each reward, null where masked. */
  public List<Double> rewards() {
    List<Double> out = new ArrayList<>();
    for (RolloutResult r : this) out.add(r.reward);
    return out;
  }

  /** true where the rollout should train (not masked). */
  public List<Boolean> mask() {
    List<Boolean> out = new ArrayList<>();
    for (RolloutResult r : this) out.add(!r.masked);
    return out;
  }

  /** The mean of the rewards that are not null, or null. */
  public Double meanReward() {
    double sum = 0;
    int n = 0;
    for (RolloutResult r : this) if (r.reward != null) { sum += r.reward; n++; }
    return n == 0 ? null : sum / n;
  }

  /** How many of each failure class. */
  public Map<String, Integer> byClass() {
    Map<String, Integer> out = new TreeMap<>();
    for (RolloutResult r : this) out.merge(r.failureClass, 1, Integer::sum);
    return out;
  }

  /** The rollouts of each task together, for group-relative advantages (GRPO). */
  public List<List<RolloutResult>> groups() {
    Map<Integer, List<RolloutResult>> g = new TreeMap<>();
    for (RolloutResult r : this) g.computeIfAbsent(r.group, k -> new ArrayList<>()).add(r);
    return new ArrayList<>(g.values());
  }
}
