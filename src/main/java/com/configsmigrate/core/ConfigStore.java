package com.configsmigrate.core;

import java.util.Set;
import com.configsmigrate.core.ConfigModels.*;

public interface ConfigStore {
    /** Identifies the configuration center implemented by this adapter. */
    Provider provider();
    /** Declares implemented operations without advertising reserved future capabilities. */
    Set<Capability> capabilities();

    interface Readable extends ConfigStore {
        /** Reads a complete document with its location, declared format, and content hash. */
        ReadResult read(ReadRequest request);
    }

    interface Writable extends ConfigStore {
        /** Creates or replaces a complete document and reports the upstream acknowledgement. */
        PublishResult write(WriteRequest request);
    }
}
