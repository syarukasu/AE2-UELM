package appeng.rebuild.pattern;

import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

/** Explicit result of compiling normalized source data against a key registry. */
public sealed interface PatternCompileResult permits PatternCompileResult.Success, PatternCompileResult.Failure {
    static Success success(CompiledPattern pattern) {
        return new Success(pattern);
    }

    static Failure failure(FailureReason reason, String sourcePath, @Nullable AEKey key) {
        return new Failure(reason, sourcePath, key);
    }

    boolean isSuccess();

    record Success(CompiledPattern pattern) implements PatternCompileResult {
        public Success {
            Objects.requireNonNull(pattern, "pattern");
        }

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    record Failure(FailureReason reason, String sourcePath, @Nullable AEKey key) implements PatternCompileResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(sourcePath, "sourcePath");
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        public Optional<AEKey> keyOptional() {
            return Optional.ofNullable(key);
        }
    }

    enum FailureReason {
        UNKNOWN_KEY,
        BOUNDED_SHAPE_REJECTED
    }
}
