package com.configsmigrate.core;

public final class MigrationException extends RuntimeException {
    public enum Code {
        INVALID_ARGUMENT, INVALID_FORMAT, CONVERSION_CONFLICT, NOT_FOUND,
        AUTHENTICATION_FAILED, UPSTREAM_ERROR, NETWORK_ERROR, TIMEOUT,
        WRITE_RESULT_UNKNOWN, PUBLISH_FAILED, UNSUPPORTED_CAPABILITY
    }

    private final Code code;

    /** Creates a tool-facing failure whose message must not contain credentials or configuration snippets. */
    public MigrationException(Code code, String safeMessage) {
        super(code + ": " + safeMessage);
        this.code = code;
    }

    /** Returns the stable error code used by MCP clients to select a recovery action. */
    public Code code() { return code; }
}
