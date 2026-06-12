package com.semirisk.model;

import java.time.Instant;

public record LoginCounter(int failures, Instant windowStarted, Instant lockedUntil) {
}
