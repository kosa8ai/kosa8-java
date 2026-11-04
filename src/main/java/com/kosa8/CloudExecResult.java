package com.kosa8;

/**
 * A finished command in a cloud sandbox. exitCode is null when the command did not exit normally;
 * wokeMs is set when the sandbox was asleep and woke for this command.
 */
public record CloudExecResult(Integer exitCode, String stdout, String stderr, Long wokeMs) {}
