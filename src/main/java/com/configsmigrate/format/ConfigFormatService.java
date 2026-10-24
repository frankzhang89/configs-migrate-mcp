package com.configsmigrate.format;

import com.configsmigrate.core.ConfigModels.ConversionResult;
import com.configsmigrate.core.FileType;
import com.configsmigrate.core.MigrationException;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

import static com.configsmigrate.core.ConfigModels.sha256;
import static com.configsmigrate.core.MigrationException.Code.*;

@Service
public class ConfigFormatService {
    private static final int MAX_CONTENT = 1_048_576;
    private static final int MAX_DEPTH = 64;
    private static final Set<Tag> SCALAR_TAGS = Set.of(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL, Tag.TIMESTAMP);

    /** Explicitly converts supported formats, preserving original content when source and target are equal. */
    public ConversionResult convert(String content, FileType source, FileType target) {
        validate(content, source);
        if (source == target) {
            // Keep comments, whitespace, quoting, and line endings when no transformation is needed.
            return new ConversionResult(source.value(), target.value(), false, content, sha256(content));
        }
        String converted;
        if (source == FileType.PROPERTIES) {
            Tree root = new Tree();
            root.object = new LinkedHashMap<>();
            // Build a collision-checked tree before emitting YAML; insertion never overwrites a path.
            readProperties(content).forEach((key, value) -> root.insert(path(key), 0, value));
            converted = dumpYaml(root.materialize());
        } else {
            Map<String, String> flattened = new LinkedHashMap<>();
            flatten(readYaml(content), "", flattened, true);
            converted = writeProperties(flattened);
        }
        // Verify generated content against the same rules used before target publication.
        validate(converted, target);
        return new ConversionResult(source.value(), target.value(), true, converted, sha256(converted));
    }

    /** Checks declared syntax and supported structures without rewriting the document or guessing its format. */
    public void validate(String content, FileType type) {
        checkContent(content);
        switch (type) {
            case PROPERTIES -> readProperties(content);
            case YAML -> readYaml(content);
        }
    }

    /** Parses Java properties escapes and continuations while rejecting duplicate decoded keys. */
    public Map<String, String> readProperties(String content) {
        checkContent(content);
        Map<String, String> result = new LinkedHashMap<>();
        Properties properties = new Properties() {
            /** Detects duplicate keys before Properties can silently replace a previous value. */
            @Override
            public synchronized Object put(Object key, Object value) {
                if (result.putIfAbsent((String) key, (String) value) != null) {
                    throw new MigrationException(CONVERSION_CONFLICT, "Duplicate properties key");
                }
                return super.put(key, value);
            }
        };
        try {
            // A Reader keeps input Unicode intact while Properties handles escapes and continuations.
            properties.load(new StringReader(withoutBom(content)));
            return result;
        } catch (IllegalArgumentException | IOException e) {
            throw new MigrationException(INVALID_FORMAT, "Malformed properties escapes");
        }
    }

    /** Serializes ordered pairs with reversible escaping and no generated comments or timestamps. */
    public String writeProperties(Map<String, String> values) {
        StringBuilder result = new StringBuilder();
        values.forEach((key, value) -> result.append(escape(key, true)).append('=')
                .append(escape(value, false)).append('\n'));
        return result.toString();
    }

    /** Escapes properties delimiters, control characters, and significant leading whitespace. */
    private static String escape(String text, boolean key) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '\f' -> result.append("\\f");
                case ' ', '=', ':', '#', '!' -> {
                    if (key || (i == 0 && c == ' ')) result.append('\\');
                    result.append(c);
                }
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.toString();
    }

    /** Composes one supported YAML mapping without constructing arbitrary tagged objects. */
    private Object readYaml(String content) {
        checkContent(content);
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(MAX_CONTENT);
        try {
            // Compose nodes instead of constructing tagged objects; only approved structures are visited.
            Node node = new Yaml(new SafeConstructor(options)).compose(new StringReader(withoutBom(content)));
            // Empty content is a valid empty configuration; explicit null is not.
            if (node == null) return new LinkedHashMap<String, Object>();
            if (!(node instanceof MappingNode)) {
                throw new MigrationException(INVALID_FORMAT, "YAML root must be a mapping");
            }
            return yamlValue(node, Collections.newSetFromMap(new IdentityHashMap<>()), 0, new int[]{100_000});
        } catch (MigrationException e) {
            throw e;
        } catch (RuntimeException e) {
            // Parser messages may include snippets containing secrets.
            throw new MigrationException(INVALID_FORMAT, "Malformed or unsupported YAML document");
        }
    }

    /** Walks YAML nodes as strings and containers while bounding alias expansion and rejecting cycles. */
    private Object yamlValue(Node node, Set<Node> active, int depth, int[] budget) {
        // Bound expanded work as well as depth because a non-cyclic alias graph can still grow exponentially.
        if (--budget[0] < 0 || depth > MAX_DEPTH || !active.add(node)) {
            throw new MigrationException(INVALID_FORMAT, "YAML exceeds expansion/depth limit or contains a cycle");
        }
        try {
            if (node instanceof MappingNode mapping && Tag.MAP.equals(node.getTag())) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (NodeTuple tuple : mapping.getValue()) {
                    if (!(tuple.getKeyNode() instanceof ScalarNode key) || !Tag.STR.equals(key.getTag())) {
                        throw new MigrationException(INVALID_FORMAT, "YAML mapping keys must be strings; merge keys are unsupported");
                    }
                    if (result.containsKey(key.getValue())) {
                        throw new MigrationException(CONVERSION_CONFLICT, "Duplicate YAML key");
                    }
                    result.put(key.getValue(), yamlValue(tuple.getValueNode(), active, depth + 1, budget));
                }
                return result;
            }
            if (node instanceof SequenceNode sequence && Tag.SEQ.equals(node.getTag())) {
                List<Object> result = new ArrayList<>();
                for (Node child : sequence.getValue()) result.add(yamlValue(child, active, depth + 1, budget));
                return result;
            }
            if (node instanceof ScalarNode scalar && SCALAR_TAGS.contains(node.getTag())) {
                return scalar.getValue();
            }
            throw new MigrationException(INVALID_FORMAT, "YAML null and custom tags are unsupported");
        } finally {
            // Track the active path, allowing shared non-cyclic aliases to appear elsewhere in the document.
            active.remove(node);
        }
    }

    /** Emits block YAML with quoted scalar strings so numeric and boolean-looking text keeps its type. */
    private static String dumpYaml(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        // Quote scalar strings so parsers do not reinterpret identifiers, dates, or boolean-looking values.
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.DOUBLE_QUOTED);
        options.setIndent(2);
        options.setPrettyFlow(false);
        return new Yaml(options).dump(value);
    }

    /** Flattens YAML containers into unambiguous Spring-style paths or reports a lossy conversion. */
    private static void flatten(Object value, String prefix, Map<String, String> result, boolean root) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty() && !root) conflict("Empty YAML collections cannot be represented in properties");
            map.forEach((key, child) -> {
                String name = (String) key;
                if (name.isEmpty() || name.indexOf('.') >= 0 || name.indexOf('[') >= 0 || name.indexOf(']') >= 0) {
                    conflict("Literal YAML keys containing dots/brackets or empty keys are ambiguous in properties");
                }
                flatten(child, prefix.isEmpty() ? name : prefix + "." + name, result, false);
            });
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) conflict("Empty YAML collections cannot be represented in properties");
            for (int i = 0; i < list.size(); i++) flatten(list.get(i), prefix + "[" + i + "]", result, false);
        } else if (result.putIfAbsent(prefix, (String) value) != null) {
            conflict("YAML paths flatten to the same properties key");
        }
    }

    /** Splits a properties key into object names and canonical array indices with depth validation. */
    private static List<Object> path(String key) {
        List<Object> result = new ArrayList<>();
        int pos = 0;
        while (pos < key.length()) {
            int start = pos;
            while (pos < key.length() && ".[]".indexOf(key.charAt(pos)) < 0) pos++;
            if (start == pos) conflict("Properties key contains an empty or ambiguous path segment");
            result.add(key.substring(start, pos));
            while (pos < key.length() && key.charAt(pos) == '[') {
                int end = key.indexOf(']', pos);
                if (end < 0) conflict("Invalid properties array index");
                String index = key.substring(pos + 1, end);
                if (!index.matches("0|[1-9][0-9]{0,5}")) conflict("Invalid properties array index");
                result.add(Integer.parseInt(index));
                pos = end + 1;
            }
            if (pos < key.length()) {
                if (key.charAt(pos++) != '.' || pos == key.length()) conflict("Invalid properties path");
            }
            if (result.size() > MAX_DEPTH) conflict("Properties path exceeds depth limit");
        }
        if (result.isEmpty()) conflict("Empty properties key cannot be expanded");
        return result;
    }

    private static final class Tree {
        Map<String, Tree> object;
        SortedMap<Integer, Tree> array;
        String scalar;

        /** Inserts a leaf while rejecting scalar/container and object/array collisions along its path. */
        void insert(List<Object> path, int offset, String value) {
            if (offset == path.size()) {
                if (scalar != null || object != null || array != null) conflict("Scalar and container paths overlap");
                scalar = value;
                return;
            }
            if (scalar != null) conflict("Scalar and container paths overlap");
            Object token = path.get(offset);
            Tree child;
            if (token instanceof String name) {
                if (array != null) conflict("Object and array paths overlap");
                if (object == null) object = new LinkedHashMap<>();
                child = object.computeIfAbsent(name, ignored -> new Tree());
            } else {
                if (object != null) conflict("Object and array paths overlap");
                if (array == null) array = new TreeMap<>();
                child = array.computeIfAbsent((Integer) token, ignored -> new Tree());
            }
            child.insert(path, offset + 1, value);
        }

        /** Builds YAML-ready containers and rejects array gaps instead of inventing missing values. */
        Object materialize() {
            if (scalar != null) return scalar;
            if (object != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                object.forEach((key, value) -> result.put(key, value.materialize()));
                return result;
            }
            List<Object> result = new ArrayList<>();
            for (Map.Entry<Integer, Tree> entry : array.entrySet()) {
                // Sparse indices would require invented null entries, which would change configuration semantics.
                if (entry.getKey() != result.size()) conflict("Array indices must be continuous starting at zero");
                result.add(entry.getValue().materialize());
            }
            return result;
        }
    }

    /** Allows empty content but rejects null input and documents above the character limit. */
    private static void checkContent(String content) {
        if (content == null) throw new MigrationException(INVALID_ARGUMENT, "content is required (empty string is allowed)");
        if (content.length() > MAX_CONTENT) throw new MigrationException(INVALID_ARGUMENT, "Content exceeds 1 MiB character limit");
    }

    /** Removes an optional BOM for parsing only; same-format results still return the untouched input. */
    private static String withoutBom(String content) { return content.startsWith("\uFEFF") ? content.substring(1) : content; }
    /** Raises a stable conversion-conflict failure with a content-free diagnostic. */
    private static void conflict(String message) { throw new MigrationException(CONVERSION_CONFLICT, message); }
}
