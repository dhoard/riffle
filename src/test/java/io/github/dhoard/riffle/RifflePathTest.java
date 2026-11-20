package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A path names the text a diagnostic is about, in the syntax of LANGUAGE_SPEC.md §10.1, and a host
 * that stores a problem keeps that path.
 */
class RifflePathTest {

    @Test
    void displaysTheRootAsTheEmptyPath() {
        assertThat(RifflePath.root()).isEqualTo(RifflePath.ROOT);
        assertThat(RifflePath.root().isRoot()).isTrue();
        assertThat(RifflePath.root().depth()).isZero();
        assertThat(RifflePath.root().segments()).isEmpty();
        assertThat(RifflePath.root().toString()).isEqualTo("(root)");
    }

    @Test
    void rendersBareKeysAndElementSegments() {
        assertThat(RifflePath.root().key("port").toString()).isEqualTo("port");
        assertThat(RifflePath.root().key("servers").index(3).key("port").toString())
                .isEqualTo("servers[3].port");
        assertThat(RifflePath.root().key("a_b-c").toString()).isEqualTo("a_b-c");
        assertThat(RifflePath.root().index(0).index(1).toString()).isEqualTo("[0][1]");
        assertThat(RifflePath.root().key("env").key("region").toString()).isEqualTo("env.region");
    }

    @Test
    void quotesAKeyThatIsNotBare() {
        assertThat(RifflePath.root().key("display name").toString()).isEqualTo("\"display name\"");
        assertThat(RifflePath.root().key("a.b").toString()).isEqualTo("\"a.b\"");
        assertThat(RifflePath.root().key("a").index(0).key("display name").toString())
                .isEqualTo("a[0].\"display name\"");
        assertThat(RifflePath.root().key("").toString()).isEqualTo("\"\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"port", "display name", "a.b", "", "say \"hi\"", "tab\tkey", "[bracket]", "#hash", "é"})
    void rendersAKeySoThatTheSameTextParsesBackToIt(String key) {
        RifflePath path = RifflePath.root().key(key);
        RiffleObject document = Riffle.parse(path + " : value\n");

        assertThat(document.entries().keySet()).containsExactly(key);
        assertThat(document.get(key).path()).contains(path);
    }

    @Test
    void reportsThePathOfEveryParsedValue() {
        RiffleObject document = Riffle.parse("servers : [\n    {\n        port : 1\n    }\n]\n");

        assertThat(document.path()).contains(RifflePath.root());
        assertThat(document.get("servers").path()).contains(RifflePath.root().key("servers"));
        assertThat(document.getArray("servers").get(0).path())
                .contains(RifflePath.root().key("servers").index(0));
        assertThat(document.getArray("servers").getObject(0).get("port").path())
                .contains(RifflePath.root().key("servers").index(0).key("port"));
    }

    @Test
    void walksSegmentsAndParents() {
        RifflePath path = RifflePath.root().key("servers").index(2);

        assertThat(path.depth()).isEqualTo(2);
        assertThat(path.isRoot()).isFalse();
        assertThat(path.parent()).isEqualTo(RifflePath.root().key("servers"));
        assertThat(path.parent().parent()).isEqualTo(RifflePath.root());
        assertThat(path.segments()).containsExactly(new RifflePath.Key("servers"), new RifflePath.Index(2));
        assertThat(catchThrowableOfType(
                        IllegalStateException.class, () -> RifflePath.root().parent()))
                .isNotNull();
    }

    @Test
    void comparesTheSegmentsItHolds() {
        assertThat(RifflePath.root().key("a")).isEqualTo(RifflePath.root().key("a"));
        assertThat(RifflePath.root().key("a")).isNotEqualTo(RifflePath.root().key("b"));
        assertThat(RifflePath.root().key("a"))
                .isNotEqualTo(RifflePath.root().key("a").index(0));
        assertThat(RifflePath.root().index(1)).isNotEqualTo(RifflePath.root().key("1"));

        Set<RifflePath> paths = new HashSet<>();
        paths.add(RifflePath.root().key("a").index(1));
        paths.add(RifflePath.root().key("a").index(1));
        assertThat(paths).hasSize(1);
        assertThat(paths.iterator().next().hashCode())
                .isEqualTo(RifflePath.root().key("a").index(1).hashCode());
    }

    @Test
    void acceptsANullPathWhereTheRootIsMeant() {
        assertThat(RifflePath.orRoot(null)).isEqualTo(RifflePath.root());
        RifflePath path = RifflePath.root().key("a");
        assertThat(RifflePath.orRoot(path)).isSameAs(path);
    }

    @Test
    void survivesJavaSerialization() throws IOException {
        RifflePath path = RifflePath.root().key("servers").index(2).key("display name");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(path);
        }

        Object restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = in.readObject();
        } catch (ClassNotFoundException impossible) {
            throw new AssertionError("the path type disappeared", impossible);
        }
        assertThat(restored).isEqualTo(path).isInstanceOf(RifflePath.class);
    }

    @Test
    void buildsADeepPathWithoutRecursion() {
        RifflePath path = RifflePath.root();
        for (int i = 0; i < 10_000; i++) {
            path = path.key("k");
        }

        assertThat(path.depth()).isEqualTo(10_000);
        assertThat(path.segments()).hasSize(10_000);
        assertThat(path.toString()).startsWith("k.k.k").endsWith(".k");
        assertThat(path.parent().depth()).isEqualTo(9_999);
    }

    @Test
    void describesASegmentOnItsOwn() {
        assertThat(new RifflePath.Key("display name")).hasToString("\"display name\"");
        assertThat(new RifflePath.Index(-1)).hasToString("[-1]");
    }
}
