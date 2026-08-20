package appeng.rebuild.storage;

import java.util.Objects;

/** Typed result of capturing a consistent exact storage snapshot without reconciliation or fallback. */
public sealed interface StorageSnapshotCaptureResult
        permits StorageSnapshotCaptureResult.Success, StorageSnapshotCaptureResult.Failure {
    record Success(StorageSnapshot snapshot) implements StorageSnapshotCaptureResult {
        public Success {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Failure(FailureReason reason) implements StorageSnapshotCaptureResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum FailureReason {
        UNAVAILABLE,
        KEY_LIMIT,
        LEGACY_EXCEPTION
    }
}
