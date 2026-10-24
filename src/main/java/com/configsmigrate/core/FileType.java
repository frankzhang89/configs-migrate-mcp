package com.configsmigrate.core;

import java.util.Locale;
import static com.configsmigrate.core.MigrationException.Code.INVALID_ARGUMENT;

public enum FileType {
    PROPERTIES("properties"), YAML("yaml");

    private final String value;
    /** Stores the canonical format name sent to upstream services and MCP clients. */
    FileType(String value) { this.value = value; }
    /** Returns the canonical wire value; both YAML aliases are represented as yaml. */
    public String value() { return value; }

    /** Resolves a case-insensitive format name and rejects unsupported or missing values. */
    public static FileType parse(String value) {
        if (value == null) throw new MigrationException(INVALID_ARGUMENT, "fileType is required");
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "properties" -> PROPERTIES;
            case "yaml", "yml" -> YAML;
            default -> throw new MigrationException(INVALID_ARGUMENT, "Supported file types: properties, yaml, yml");
        };
    }
}
