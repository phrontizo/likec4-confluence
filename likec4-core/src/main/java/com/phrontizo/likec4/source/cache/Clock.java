package com.phrontizo.likec4.source.cache;

/** Abstraction over wall-clock time so TTL logic is unit-testable. */
public interface Clock {
  long nowMillis();

  Clock SYSTEM = System::currentTimeMillis;
}
