package appeng.me.cluster.implementations;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import appeng.rebuild.execution.ExactRecoveryCheckpoint;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.persistence.ExactRecoveryCheckpointNbtCodec;
import appeng.rebuild.persistence.PersistenceDecodeResult;

/**
 * Durable, fail-closed ownership of an exact CPU recovery tag.
 *
 * <p>
 * This deliberately does not adapt the legacy crafting CPU inventory into a revisioned exact endpoint. Such an adapter
 * would claim physical ownership without the guarantees required by the exact broker. Native activation is deferred
 * until Phase 9 supplies a durability-aware exact execution session. Until then, a successfully decoded checkpoint is
 * inert and legacy crafting never resumes it.
 */
final class ExactCpuRecoveryPersistence {
    static final String TAG_EXACT_RECOVERY = "ae2_rebuild_exact_recovery";
    private static final String TAG_REJECTION = "exact_recovery_rejected";

    private final Runnable changed;
    @Nullable
    private Tag rawTag;
    @Nullable
    private ExactRecoveryCheckpoint checkpoint;
    private State state = State.ABSENT;
    @Nullable
    private PersistenceDecodeResult.Reason decodeFailure;

    ExactCpuRecoveryPersistence(Runnable changed) {
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    /** Loads raw evidence first; malformed or not-yet-decodable evidence is never discarded. */
    void readFromNbt(CompoundTag owner, @Nullable KeyRegistry registry) {
        Objects.requireNonNull(owner, "owner");
        Tag source = owner.contains(TAG_EXACT_RECOVERY) ? owner.get(TAG_EXACT_RECOVERY) : null;
        ExactRecoveryCheckpointNbtCodec.EnvelopePreflight envelope = ExactRecoveryCheckpointNbtCodec
                .preflightEnvelope(source);
        rawTag = envelope == ExactRecoveryCheckpointNbtCodec.EnvelopePreflight.LIMIT_EXCEEDED
                ? rejectionTombstone(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED)
                : source == null ? null : source.copy();
        checkpoint = null;
        decodeFailure = null;
        state = rawTag == null ? State.ABSENT : State.PENDING_KEY_REGISTRY;
        if (envelope == ExactRecoveryCheckpointNbtCodec.EnvelopePreflight.LIMIT_EXCEEDED) {
            state = State.DECODE_REJECTED;
            decodeFailure = PersistenceDecodeResult.Reason.LIMIT_EXCEEDED;
            return;
        }
        if (envelope == ExactRecoveryCheckpointNbtCodec.EnvelopePreflight.MALFORMED_TYPE) {
            state = State.DECODE_REJECTED;
            decodeFailure = PersistenceDecodeResult.Reason.MALFORMED;
            return;
        }
        PersistenceDecodeResult.Reason tombstoneReason = tombstoneReason(rawTag);
        if (tombstoneReason != null) {
            state = State.DECODE_REJECTED;
            decodeFailure = tombstoneReason;
            return;
        }
        decodeIfPossible(registry);
    }

    /** Decodes once after the CPU has joined a grid with a concrete exact key registry. */
    void decodeIfPossible(@Nullable KeyRegistry registry) {
        if (state != State.PENDING_KEY_REGISTRY || rawTag == null || registry == null) {
            return;
        }
        if (!(rawTag instanceof CompoundTag tag)) {
            state = State.DECODE_REJECTED;
            decodeFailure = PersistenceDecodeResult.Reason.MALFORMED;
            return;
        }
        PersistenceDecodeResult<ExactRecoveryCheckpoint> decoded = ExactRecoveryCheckpointNbtCodec.decode(tag,
                registry);
        if (decoded instanceof PersistenceDecodeResult.Success<ExactRecoveryCheckpoint> success) {
            checkpoint = success.value();
            state = State.PENDING_ACTIVATION;
        } else {
            decodeFailure = ((PersistenceDecodeResult.Failure<ExactRecoveryCheckpoint>) decoded).reason();
            state = State.DECODE_REJECTED;
        }
    }

    /**
     * Writes the retained evidence exactly as loaded. The codec is intentionally not invoked during ordinary saves:
     * decoding already performed the single permitted key-table rebind, while re-encoding could not make a pending
     * physical authority safer.
     */
    void writeToNbt(CompoundTag owner) {
        Objects.requireNonNull(owner, "owner");
        if (rawTag != null) {
            owner.put(TAG_EXACT_RECOVERY, rawTag.copy());
        } else {
            owner.remove(TAG_EXACT_RECOVERY);
        }
    }

    /**
     * Installs a newly captured, semantically valid checkpoint. Encoding completes before publication, so an encoding
     * failure changes neither durable state nor the native dirty bit. The native dirty notification is admitted before
     * assignment, so a throwing notification leaves the prior authority untouched.
     */
    void replace(ExactRecoveryCheckpoint next, KeyRegistry registry) {
        Objects.requireNonNull(next, "next");
        CompoundTag encoded = ExactRecoveryCheckpointNbtCodec.encode(next,
                Objects.requireNonNull(registry, "registry"));
        changed.run();
        rawTag = encoded.copy();
        checkpoint = next;
        decodeFailure = null;
        state = State.PENDING_ACTIVATION;
    }

    State state() {
        return state;
    }

    @Nullable
    PersistenceDecodeResult.Reason decodeFailure() {
        return decodeFailure;
    }

    private static CompoundTag rejectionTombstone(PersistenceDecodeResult.Reason reason) {
        CompoundTag tombstone = new CompoundTag();
        tombstone.putString(TAG_REJECTION, reason.name());
        return tombstone;
    }

    @Nullable
    private static PersistenceDecodeResult.Reason tombstoneReason(@Nullable Tag tag) {
        if (!(tag instanceof CompoundTag tombstone) || tombstone.size() != 1
                || !tombstone.contains(TAG_REJECTION, Tag.TAG_STRING)) {
            return null;
        }
        try {
            return PersistenceDecodeResult.Reason.valueOf(tombstone.getString(TAG_REJECTION));
        } catch (IllegalArgumentException malformed) {
            return PersistenceDecodeResult.Reason.MALFORMED;
        }
    }

    enum State {
        ABSENT,
        PENDING_KEY_REGISTRY,
        PENDING_ACTIVATION,
        DECODE_REJECTED
    }
}
