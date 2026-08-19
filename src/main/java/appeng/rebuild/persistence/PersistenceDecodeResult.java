package appeng.rebuild.persistence;

import java.util.Objects;

/** Strict decode result: malformed data never yields a partial or fallback value. */
public sealed interface PersistenceDecodeResult<T>
        permits PersistenceDecodeResult.Success, PersistenceDecodeResult.Failure {
    record Success<T>(T value) implements PersistenceDecodeResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failure<T>(Reason reason) implements PersistenceDecodeResult<T> {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum Reason {
        MALFORMED, UNSUPPORTED_VERSION, LIMIT_EXCEEDED, UNKNOWN_KEY, GENERATION_MISMATCH
    }
}
