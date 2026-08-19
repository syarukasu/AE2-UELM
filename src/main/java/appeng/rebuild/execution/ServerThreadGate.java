package appeng.rebuild.execution;

/** Verifies that a transfer-broker operation is running on its owning server thread. */
@FunctionalInterface
public interface ServerThreadGate {
    boolean isServerThread();
}
