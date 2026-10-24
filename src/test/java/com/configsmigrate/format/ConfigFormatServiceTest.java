package com.configsmigrate.format;

import com.configsmigrate.core.FileType;
import com.configsmigrate.core.MigrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;
import java.util.Collections;

import static com.configsmigrate.core.FileType.*;
import static org.assertj.core.api.Assertions.*;

class ConfigFormatServiceTest {
    private final ConfigFormatService formats = new ConfigFormatService();

    /** Verifies nested paths and arrays round-trip while string-looking scalars retain their exact values. */
    @Test
    void expandsNestedPathsAndArraysWithoutInferringScalarTypes() {
        String source = "app.name=订单\napp.enabled=true\napp.code=00123\napp.date=2026-10-21\n"
                + "app.empty=\napp.url=${HOST:localhost}\nservers[0].host=a\nservers[1].host=b\n";
        String yaml = formats.convert(source, PROPERTIES, YAML).content();
        Map<?, ?> value = new Yaml().load(yaml);
        assertThat((Map<?, ?>) value.get("app")).isEqualTo(Map.of("name", "订单", "enabled", "true", "code", "00123",
                "date", "2026-10-21", "empty", "", "url", "${HOST:localhost}"));
        assertThat(formats.readProperties(formats.convert(yaml, YAML, PROPERTIES).content())).isEqualTo(formats.readProperties(source));
    }

    /** Checks reversible properties escaping, Unicode input, continuation lines, and significant whitespace. */
    @Test
    void propertiesEscapesUnicodeContinuationAndLeadingWhitespaceRoundTrip() {
        String source = "escaped\\ key=a\\nline\\tvalue\nunicode=\\u4e2d\\u6587\ncontinued=ab\\\n  cd\nempty=\n";
        var values = formats.readProperties(source);
        assertThat(values).containsEntry("escaped key", "a\nline\tvalue").containsEntry("unicode", "中文")
                .containsEntry("continued", "abcd").containsEntry("empty", "");
        assertThat(formats.readProperties(formats.writeProperties(Map.of(" a:=!#", "  value\\\n\t\r\f"))))
                .isEqualTo(Map.of(" a:=!#", "  value\\\n\t\r\f"));
    }

    /** Checks that same-format validation preserves original text and handles empty configurations. */
    @Test
    void sameFormatReturnsOriginalBytesIncludingCommentsAndLineEndings() {
        String original = "# comment\r\napp:\r\n  name: '中文'\r\n  list: []\r\n";
        var converted = formats.convert(original, FileType.parse("yml"), YAML);
        assertThat(converted.converted()).isFalse();
        assertThat(converted.content()).isEqualTo(original);
        assertThat(formats.convert("", YAML, YAML).content()).isEmpty();
        assertThat(formats.convert("", PROPERTIES, YAML).content()).contains("{}");
        assertThat(formats.convert("{}", YAML, PROPERTIES).content()).isEmpty();
    }

    /** Rejects duplicate or ambiguous properties paths rather than silently changing their meaning. */
    @ParameterizedTest
    @ValueSource(strings = {"a=x\na.b=y", "a.b=x\na[0]=y", "a[1]=x", "a[01]=x", "a..b=x", "a.=x", "=x", "a=x\na=x", "a[0]=x\na[0].b=y"})
    void rejectsAmbiguousPropertiesPaths(String input) {
        assertThatThrownBy(() -> formats.convert(input, PROPERTIES, YAML)).isInstanceOf(MigrationException.class)
                .hasMessageContaining("CONVERSION_CONFLICT");
    }

    /** Rejects unsupported YAML structures, tags, cycles, malformed input, and duplicate keys. */
    @ParameterizedTest
    @ValueSource(strings = {"a: x\na: y", "a: null", "a: !!java/object x", "a: &a {b: *a}", "---\na: x\n---\nb: y", "- item", "1: x", "a: ["})
    void rejectsInvalidOrUnsupportedYaml(String input) {
        assertThatThrownBy(() -> formats.validate(input, YAML)).isInstanceOf(MigrationException.class);
    }

    /** Checks that flattening fails when collections or literal keys cannot be represented unambiguously. */
    @ParameterizedTest
    @ValueSource(strings = {"a: []", "a: {}", "'a.b': x", "'a[0]': x", "'': x"})
    void rejectsLossyYamlFlattening(String input) {
        assertThatThrownBy(() -> formats.convert(input, YAML, PROPERTIES)).isInstanceOf(MigrationException.class)
                .hasMessageContaining("CONVERSION_CONFLICT");
    }

    /** Ensures invalid-input diagnostics do not reveal sensitive parser excerpts. */
    @Test
    void errorsDoNotExposeSecretParserSnippets() {
        assertThatThrownBy(() -> formats.validate("password: [VERY_SECRET", YAML))
                .hasMessageNotContaining("VERY_SECRET");
        assertThatThrownBy(() -> formats.validate(null, YAML)).hasMessageContaining("INVALID_ARGUMENT");
        assertThatThrownBy(() -> formats.validate("a=\\uBAD", PROPERTIES)).hasMessageContaining("INVALID_FORMAT");
    }

    /** Allows shared non-cyclic aliases while bounding the work of exponentially expanded graphs. */
    @Test
    void expandsNonCyclicAliasesButRejectsExponentialExpansion() {
        assertThat(formats.convert("base: &base {name: x}\ncopy: *base", YAML, PROPERTIES).content())
                .contains("base.name=x", "copy.name=x");
        StringBuilder input = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x, x]\n");
        for (int i = 1; i < 6; i++) {
            input.append("a").append(i).append(": &a").append(i).append(" [")
                    .append(String.join(", ", Collections.nCopies(8, "*a" + (i - 1)))).append("]\n");
        }
        assertThatThrownBy(() -> formats.validate(input.toString(), YAML)).hasMessageContaining("INVALID_FORMAT");
    }
}
