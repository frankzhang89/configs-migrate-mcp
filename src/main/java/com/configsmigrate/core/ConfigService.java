package com.configsmigrate.core;

import com.configsmigrate.config.MigrationProperties;
import com.configsmigrate.core.ConfigModels.*;
import com.configsmigrate.format.ConfigFormatService;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.configsmigrate.core.ConfigModels.required;
import static com.configsmigrate.core.MigrationException.Code.*;

@Service
public class ConfigService {
    private final MigrationProperties settings;
    private final ConfigFormatService formats;
    private final Map<Provider, ConfigStore> stores = new EnumMap<>(Provider.class);

    /** Registers one adapter per provider so future implementations share the same routing. */
    public ConfigService(MigrationProperties settings, ConfigFormatService formats, List<ConfigStore> stores) {
        this.settings = settings;
        this.formats = formats;
        for (ConfigStore store : stores) {
            if (this.stores.putIfAbsent(store.provider(), store) != null) {
                throw new IllegalStateException("Duplicate configuration provider");
            }
        }
    }

    /** Reads a published Apollo namespace using explicit identifiers or configured source defaults. */
    public ReadResult apolloRead(String appId, String cluster, String namespace, String fileType) {
        var defaults = settings.from().apollo();
        FileType type = FileType.parse(or(fileType, defaults.fileType()));
        String name = required(or(namespace, defaults.namespace()), "namespace");
        // Non-properties Apollo namespaces are addressed by their actual file-suffixed names.
        if (type == FileType.YAML && !(name.endsWith(".yaml") || name.endsWith(".yml"))) {
            name += or(fileType, defaults.fileType()).trim().equalsIgnoreCase("yml") ? ".yml" : ".yaml";
        }
        Location location = new Location(Provider.APOLLO, required(or(appId, defaults.appId()), "appId"),
                required(or(cluster, defaults.cluster()), "cluster"), name, null, null);
        return readable(Provider.APOLLO).read(new ReadRequest(location, type));
    }

    /** Reads the exact Nacos identity without detecting or converting its declared format. */
    public ReadResult nacosRead(String dataId, String group, String namespace, String fileType) {
        return readable(Provider.NACOS).read(new ReadRequest(nacosLocation(dataId, group, namespace),
                FileType.parse(or(fileType, settings.to().nacos().fileType()))));
    }

    /** Validates target-format content and publishes it unchanged, regardless of the configured source format. */
    public PublishResult nacosPublish(String dataId, String content, String group, String namespace, String fileType) {
        Location location = nacosLocation(dataId, group, namespace);
        FileType type = FileType.parse(or(fileType, settings.to().nacos().fileType()));
        // Reject malformed target documents before login or any upstream write can occur.
        formats.validate(content, type);
        // Publication is deliberately independent of the configured source format.
        return writable(Provider.NACOS).write(new WriteRequest(location, type, content));
    }

    /** Explicitly converts content, using configured from/to formats only as omitted-argument defaults. */
    public ConversionResult convert(String content, String sourceType, String targetType) {
        return formats.convert(content, FileType.parse(or(sourceType, settings.from().apollo().fileType())),
                FileType.parse(or(targetType, settings.to().nacos().fileType())));
    }

    /** Resolves a declared read capability or reports an unsupported-capability failure. */
    public ConfigStore.Readable readable(Provider provider) {
        ConfigStore store = stores.get(provider);
        if (store instanceof ConfigStore.Readable reader && store.capabilities().contains(Capability.READ)) return reader;
        throw new MigrationException(UNSUPPORTED_CAPABILITY, provider + " read is not implemented");
    }

    /** Resolves a declared write capability without advertising reserved provider support. */
    public ConfigStore.Writable writable(Provider provider) {
        ConfigStore store = stores.get(provider);
        if (store instanceof ConfigStore.Writable writer && store.capabilities().contains(Capability.WRITE)) return writer;
        throw new MigrationException(UNSUPPORTED_CAPABILITY, provider + " write is not implemented");
    }

    /** Builds a Nacos identity, allowing explicit public selection to override a configured namespace. */
    private Location nacosLocation(String dataId, String group, String namespace) {
        var defaults = settings.to().nacos();
        String ns = or(namespace, defaults.namespace());
        // Explicit public selection must override a non-public namespace in server defaults.
        if (ns.equals("public")) ns = "";
        return new Location(Provider.NACOS, null, null, ns, required(or(group, defaults.group()), "group"), required(dataId, "dataId"));
    }

    /** Falls back only on omission; an empty string remains an explicit argument. */
    private static String or(String value, String fallback) { return value == null ? fallback : value; }
}
