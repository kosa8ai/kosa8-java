package com.kosa8;

/**
 * One item of a {@link Kosa8Cloud#forkMap}: what fn returned (value) or the last error, its failure class
 * (ok, or as {@link Kosa8Exception#classifyFailure}), how many tries it took, the last child it ran in, and how
 * long it took.
 */
public record ForkResult<T, R>(T item, R value, Throwable error, String failureClass, int attempts, String sandboxId, long ms) {
  public boolean ok() { return error == null; }
}
