package appeng.rebuild.pattern;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Addon-neutral processing-machine intent with finite, immutable descriptive fields. */
public final class MachineIntent {
    private final String backendType;
    private final String recipeFingerprint;
    private final String capabilityFingerprint;
    private final Map<String, String> attributes;

    public MachineIntent(String backendType, String recipeFingerprint, String capabilityFingerprint,
            Map<String, String> attributes) {
        this.backendType = requireField(backendType, "backendType", PatternLimits.MAX_MACHINE_FIELD_LENGTH);
        this.recipeFingerprint = requireField(recipeFingerprint, "recipeFingerprint",
                PatternLimits.MAX_MACHINE_FIELD_LENGTH);
        this.capabilityFingerprint = requireField(capabilityFingerprint, "capabilityFingerprint",
                PatternLimits.MAX_MACHINE_FIELD_LENGTH);
        Objects.requireNonNull(attributes, "attributes");
        if (attributes.size() > PatternLimits.MAX_MACHINE_ATTRIBUTES) {
            throw new IllegalArgumentException("Machine intent has too many attributes: " + attributes.size());
        }
        Map<String, String> copiedAttributes = new TreeMap<>();
        attributes.forEach((key, value) -> copiedAttributes.put(
                requireField(key, "attribute key", PatternLimits.MAX_MACHINE_ATTRIBUTE_KEY_LENGTH),
                requireField(value, "attribute value", PatternLimits.MAX_MACHINE_ATTRIBUTE_VALUE_LENGTH)));
        this.attributes = Collections.unmodifiableMap(copiedAttributes);
    }

    public String backendType() {
        return backendType;
    }

    public String recipeFingerprint() {
        return recipeFingerprint;
    }

    public String capabilityFingerprint() {
        return capabilityFingerprint;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MachineIntent other)) {
            return false;
        }
        return backendType.equals(other.backendType) && recipeFingerprint.equals(other.recipeFingerprint)
                && capabilityFingerprint.equals(other.capabilityFingerprint) && attributes.equals(other.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(backendType, recipeFingerprint, capabilityFingerprint, attributes);
    }

    private static String requireField(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}
