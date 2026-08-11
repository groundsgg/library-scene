package gg.grounds.scene.format;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JvmPublicSurfaceJavaTest {
    private static final Set<String> ALLOWED_PACKAGES = Set.of(
            "gg.grounds.scene.format", "java.lang", "java.math", "java.util", "kotlin", "net.kyori.adventure.text");
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "tools.jackson.", "org.bukkit.", "io.papermc.", "net.minestom.", "io.quarkus.",
            "gg.grounds.scene.testkit", "org.junit.", "kotlin.test.", "gg.grounds.scene.format.internal.");

    @Test
    void publicApiContainsNoJacksonOrPlatformTypes() throws Exception {
        PublicSurface.assertPublicSignaturesUseOnly(formatClasses(), ALLOWED_PACKAGES, FORBIDDEN_PREFIXES);
    }

    @Test
    void publicSurfaceCheckerRejectsControlledJacksonLeak() {
        AssertionError failure = assertThrows(AssertionError.class, () ->
                PublicSurface.assertPublicSignaturesUseOnly(List.of(SyntheticJacksonLeak.class), ALLOWED_PACKAGES, FORBIDDEN_PREFIXES));
        if (!failure.getMessage().contains("tools.jackson.databind.ObjectMapper")) {
            throw new AssertionError("The surface checker did not identify the leaked binary name: " + failure.getMessage());
        }
    }

    private static List<Class<?>> formatClasses() throws Exception {
        URI location = SceneJson.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path root = Path.of(location).resolve("gg/grounds/scene/format");
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".class"))
                    .map(root::relativize)
                    .map(path -> "gg.grounds.scene.format." + path.toString().replace('/', '.').replace('\\', '.').replaceFirst("\\.class$", ""))
                    .filter(name -> !name.contains(".internal.") && !name.endsWith("$Companion") && !name.contains("$WhenMappings"))
                    .<Class<?>>map(name -> {
                        try { return Class.forName(name); }
                        catch (ClassNotFoundException exception) { throw new IllegalStateException(exception); }
                    })
                    .toList();
        }
    }

    public static final class SyntheticJacksonLeak {
        public tools.jackson.databind.ObjectMapper leaked() { return null; }
    }

    static final class PublicSurface {
        static void assertPublicSignaturesUseOnly(
                List<Class<?>> types, Set<String> allowedPackages, List<String> forbiddenPrefixes) {
            for (Class<?> type : types) {
                if (!Modifier.isPublic(type.getModifiers()) || type.isSynthetic() || type.isAnonymousClass()) continue;
                assertAllowed(type, allowedPackages, forbiddenPrefixes, type.getName());
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    if (Modifier.isPublic(constructor.getModifiers())) assertSignature(constructor.getParameterTypes(), allowedPackages, forbiddenPrefixes, constructor.toString());
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (!Modifier.isPublic(method.getModifiers())) continue;
                    if (method.isBridge() || (method.isSynthetic() && !isKotlinCompilerArtifact(method))) {
                        throw new AssertionError("Synthetic or bridge method leaked: " + method);
                    }
                    assertAllowed(method.getReturnType(), allowedPackages, forbiddenPrefixes, method.toString());
                    assertSignature(method.getParameterTypes(), allowedPackages, forbiddenPrefixes, method.toString());
                }
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isPublic(field.getModifiers())) assertAllowed(field.getType(), allowedPackages, forbiddenPrefixes, field.toString());
                }
            }
        }

        private static boolean isKotlinCompilerArtifact(Method method) {
            return method.getName().endsWith("$default") || method.getName().startsWith("access$") || method.getName().endsWith("-impl");
        }

        private static void assertSignature(Class<?>[] types, Set<String> allowedPackages, List<String> forbiddenPrefixes, String owner) {
            for (Class<?> type : types) assertAllowed(type, allowedPackages, forbiddenPrefixes, owner);
        }

        private static void assertAllowed(Class<?> type, Set<String> allowedPackages, List<String> forbiddenPrefixes, String owner) {
            while (type.isArray()) type = type.getComponentType();
            if (type.isPrimitive() || type == void.class) return;
            String name = type.getName();
            for (String prefix : forbiddenPrefixes) if (name.startsWith(prefix)) throw new AssertionError(owner + " exposes forbidden type " + name);
            boolean allowed = allowedPackages.stream().anyMatch(prefix -> name.equals(prefix) || name.startsWith(prefix + "."));
            if (!allowed) throw new AssertionError(owner + " exposes unapproved type " + name);
        }
    }
}
