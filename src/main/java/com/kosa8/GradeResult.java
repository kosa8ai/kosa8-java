package com.kosa8;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A sealed grade. reward is from the grader's last stdout line that is JSON with a numeric "reward", else 1.0 on
 * exit 0 and 0.0 otherwise; null when the command did not finish (failureClass timeout or infra), so a trainer
 * masks it rather than scoring it 0. recorded says kosa8 Cloud recorded it as a rollout of runId.
 */
public record GradeResult(Double reward, Map<String, Object> metrics, String rewardFrom, Integer exitCode, String stdout, String stderr,
    String failureClass, boolean retryable, Long ms, String graderId, boolean kept, String sandboxId, String runId, Boolean recorded,
    String error, String message) {
  static GradeResult of(Map<String, Object> r) {
    Integer exit = r.get("exit") instanceof Number n ? n.intValue() : null;
    String fc = r.get("failure_class") instanceof String s ? s : FailureClass.ofExit(exit).failureClass();
    boolean rt = r.get("retryable") instanceof Boolean b ? b : FailureClass.ofExit(exit).retryable();
    Map<String, Object> metrics = r.get("metrics") instanceof Map<?, ?> ? Kosa8Cloud.obj(r.get("metrics")) : new LinkedHashMap<>();
    return new GradeResult(r.get("reward") instanceof Number n ? n.doubleValue() : null, metrics, CloudSandbox.s(r.get("reward_from")), exit,
        r.get("stdout") instanceof String s ? s : "", r.get("stderr") instanceof String s ? s : "", fc, rt, CloudSandbox.l(r.get("ms")),
        CloudSandbox.s(r.get("grader_id")), Boolean.TRUE.equals(r.get("kept")), CloudSandbox.s(r.get("sandbox_id")), CloudSandbox.s(r.get("run_id")),
        r.get("recorded") instanceof Boolean b ? b : null, CloudSandbox.s(r.get("error")), CloudSandbox.s(r.get("message")));
  }

  /** Whether the grader ran to the end (it may still have failed: exit_nonzero). */
  public boolean finished() { return reward != null; }
}
