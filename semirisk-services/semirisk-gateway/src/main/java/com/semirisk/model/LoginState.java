package com.semirisk.model;

import java.time.Instant;

public record LoginState(boolean locked, int failures, Instant lockedUntil) {
}
