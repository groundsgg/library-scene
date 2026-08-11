package gg.grounds.scene.testkit;

import gg.grounds.scene.format.ActionCatalog;
import gg.grounds.scene.format.AssetCatalog;
import gg.grounds.scene.format.SceneDocument;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JvmPublicSurfaceJavaTest {
    @Test
    void publicTestkitApiIsExactlyTheApprovedHelpers() throws Exception {
        Class<?> assertions = Class.forName("gg.grounds.scene.testkit.SceneAssertionsKt");
        assertApprovedPublicMethods(
                assertions,
                Set.of(
                        signature("assertValidScene", void.class, SceneDocument.class),
                        signature("assertCatalogCompatible", void.class, SceneDocument.class, AssetCatalog.class, ActionCatalog.class),
                        signature("assertCanonicalScene", void.class, SceneDocument.class, byte[].class)));
        assertApprovedPublicMethods(
                SceneFixtures.class,
                Set.of(signature("minimal", SceneDocument.class), signature("complete", SceneDocument.class)));
        assertNoPublicFields(assertions);
        assertOnlyFixtureSingletonField(SceneFixtures.class);
        assertArtifactHasNoProhibitedReferences(assertions);
        assertArtifactHasNoProhibitedReferences(SceneFixtures.class);
    }

    @Test
    void approvedSurfaceCheckRejectsAnUnexpectedPublicHelper() {
        assertThrows(
                AssertionError.class,
                () -> assertApprovedPublicMethods(UnexpectedPublicHelper.class, Set.of()));
    }

    @Test
    void approvedSurfaceCheckRejectsASyntheticPublicHelper() {
        assertThrows(
                AssertionError.class,
                () -> assertApprovedPublicMethods(SyntheticPublicSurfaceFixture.class, Set.of()));
    }

    private void assertApprovedPublicMethods(Class<?> type, Set<MethodSignature> allowed) {
        Set<MethodSignature> actual =
                Arrays.stream(type.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(this::signature)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(allowed, actual, "Unexpected public methods on " + type.getName());
        actual.forEach(signature -> assertNoProhibitedType(signature));
    }

    private void assertOnlyFixtureSingletonField(Class<?> type) {
        List<Field> publicFields =
                Arrays.stream(type.getDeclaredFields())
                        .filter(field -> Modifier.isPublic(field.getModifiers()))
                        .toList();
        assertEquals(1, publicFields.size());
        Field singleton = publicFields.getFirst();
        assertEquals("INSTANCE", singleton.getName());
        assertEquals(SceneFixtures.class, singleton.getType());
    }

    private void assertNoPublicFields(Class<?> type) {
        long publicFieldCount =
                Arrays.stream(type.getDeclaredFields())
                        .filter(field -> Modifier.isPublic(field.getModifiers()))
                        .count();
        assertEquals(0, publicFieldCount, "Unexpected public fields on " + type.getName());
    }

    private void assertArtifactHasNoProhibitedReferences(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        byte[] bytes = type.getClassLoader().getResourceAsStream(resource).readAllBytes();
        String classFile = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String prohibited : List.of("org/junit/", "kotlin/test/", "org/bukkit/", "io/papermc/", "net/minestom/")) {
            assertFalse(classFile.contains(prohibited), type.getName() + " references " + prohibited);
        }
    }

    private void assertNoProhibitedType(MethodSignature signature) {
        for (String type : signature.types()) {
            for (String prohibited : List.of("org.junit.", "kotlin.test.", "org.bukkit.", "io.papermc.", "net.minestom.")) {
                assertFalse(type.startsWith(prohibited), signature + " exposes " + prohibited);
            }
        }
    }

    private MethodSignature signature(String name, Class<?> returnType, Class<?>... parameters) {
        return new MethodSignature(name, returnType.getName(), Arrays.stream(parameters).map(Class::getName).toList());
    }

    private MethodSignature signature(Method method) {
        return signature(method.getName(), method.getReturnType(), method.getParameterTypes());
    }

    private record MethodSignature(String name, String returnType, List<String> parameterTypes) {
        List<String> types() {
            return java.util.stream.Stream.concat(java.util.stream.Stream.of(returnType), parameterTypes.stream()).toList();
        }
    }

    static final class UnexpectedPublicHelper {
        public void accidentalHelper() {}
    }
}
