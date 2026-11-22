package com.kosa8;

/**
 * A finished command in a cloud sandbox. exitCode is null when the command did not exit normally;
 * wokeMs is set when the sandbox was asleep and woke for this command. failureClass is kosa8 Cloud's word
 * for it: ok (exit 0), exit_nonzero (the command failed), or infra (the host gave no exit code; retryable).
 */
public record CloudExecResult(Integer exitCode, String stdout, String stderr, Long wokeMs, String failureClass, boolean retryable) {
  /** The 0.4 constructor: failureClass and retryable follow from the exit code. */
  public CloudExecResult(Integer exitCode, String stdout, String stderr, Long wokeMs) {
    this(exitCode, stdout, stderr, wokeMs, FailureClass.ofExit(exitCode).failureClass(), FailureClass.ofExit(exitCode).retryable());
  }
}
