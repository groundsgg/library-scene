package gg.grounds.scene.format;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class InternalJvmAccessJavaTest {
    @Test
    void externalJavaCannotNameOrCallSerializationAndValidationInternals() throws Exception {
        Path directory = Files.createTempDirectory("scene-internal-access-");
        try {
            Path source = directory.resolve("ExternalProbe.java");
            Files.writeString(
                    source,
                    """
                    package external;
                    import gg.grounds.scene.format.CanonicalJson;
                    import gg.grounds.scene.format.CatalogValidator;
                    import gg.grounds.scene.format.CollectionCopiesKt;
                    import gg.grounds.scene.format.IntrinsicValidator;
                    import gg.grounds.scene.format.SceneMapper;
                    import gg.grounds.scene.format.SceneWire;
                    import gg.grounds.scene.format.WireMapping;
                    public final class ExternalProbe {
                        SceneWire wire;
                        Object read(byte[] bytes) { return SceneMapper.INSTANCE.read(bytes); }
                        Object map() { return WireMapping.INSTANCE.toDomain(wire); }
                        Object write() { return CanonicalJson.INSTANCE.compact(null); }
                        Object intrinsic() { return IntrinsicValidator.INSTANCE.validate(null); }
                        Object catalogs() {
                            return CatalogValidator.INSTANCE.validate(null, null, null);
                        }
                        Object copy() { return CollectionCopiesKt.immutableListCopy(java.util.List.of()); }
                    }
                    """,
                    StandardCharsets.UTF_8);
            ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            int exit = ToolProvider.getSystemJavaCompiler().run(
                    null,
                    diagnostics,
                    diagnostics,
                    "-classpath",
                    System.getProperty("sceneFormatJar"),
                    "-d",
                    directory.toString(),
                    source.toString());
            String output = diagnostics.toString(StandardCharsets.UTF_8);

            assertNotEquals(0, exit, "external probe unexpectedly compiled");
            assertTrue(output.contains("gg.grounds.scene.format"), output);
            assertTrue(output.contains("SceneMapper"), output);
            assertTrue(output.contains("SceneWire"), output);
            assertTrue(output.contains("WireMapping"), output);
            assertTrue(output.contains("CanonicalJson"), output);
            assertTrue(output.contains("IntrinsicValidator"), output);
            assertTrue(output.contains("CatalogValidator"), output);
            assertTrue(output.contains("CollectionCopiesKt"), output);
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
