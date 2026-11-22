package com.kosa8;

import java.util.List;

/** What a drain deleted, and what it could not. */
public record DrainResult(int deleted, List<String> ids, List<FanoutFailure> failed) {}
