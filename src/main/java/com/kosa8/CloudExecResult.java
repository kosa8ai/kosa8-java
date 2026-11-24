package com.kosa8;

/**
 * A finished command in a cloud sandbox. exitCode is null when the command did not exit normally;
 * wokeMs is set when the sandbox was asleep and woke for this command. failureClass is kosa8 Cloud's word
 * for it: ok (exit 0), exit_nonzero (the command failed), oom, or infra (the host gave no exit code; retryable).
 *
 * <p>A buffered exec keeps the first and the last 1 MiB of each stream: stdoutTruncated / stderrTruncated are
 * true when the middle was dropped (a line "[k8agent: N bytes of output dropped]" marks where), and
 * stdoutBytes / stderrBytes are how many bytes the command wrote (null when the server did not say). Use
 * {@link CloudSandbox#execStream} for all of it.
 */
public record CloudExecResult(Integer exitCode, String stdout, String stderr, Long wokeMs, String failureClass, boolean retryable,
    boolean stdoutTruncated, boolean stderrTruncated, Long stdoutBytes, Long stderrBytes) {
  /** The 0.6 constructor: nothing truncated, byte counts unknown. */
  public CloudExecResult(Integer exitCode, String stdout, String stderr, Long wokeMs, String failureClass, boolean retryable) {
    this(exitCode, stdout, stderr, wokeMs, failureClass, retryable, false, false, null, null);
  }

  /** The 0.4 constructor: failureClass and retryable follow from the exit code. */
  public CloudExecResult(Integer exitCode, String stdout, String stderr, Long wokeMs) {
    this(exitCode, stdout, stderr, wokeMs, FailureClass.ofExit(exitCode).failureClass(), FailureClass.ofExit(exitCode).retryable());
  }
}
