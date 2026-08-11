package gg.grounds.scene.format;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
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

    @Test
    void scannerRejectsConstructorReturnGenericAndFieldLeaks() {
        for (Class<?> leak : List.of(ConstructorLeak.class, GenericReturnLeak.class, FieldLeak.class, MapperLeak.class, ComparatorLeak.class, ValidatorLeak.class, DtoLeak.class)) {
            AssertionError failure = assertThrows(AssertionError.class, () ->
                    PublicSurface.assertPublicSignaturesUseOnly(List.of(leak), ALLOWED_PACKAGES, FORBIDDEN_PREFIXES));
            if (!failure.getMessage().contains("tools.jackson.databind.ObjectMapper") && !failure.getMessage().contains("unapproved type"))
                throw new AssertionError("Missing offending ABI evidence: " + failure.getMessage());
        }
    }

    @Test
    void scannerRejectsBridgeLeak() {
        AssertionError failure = assertThrows(AssertionError.class, () ->
                PublicSurface.assertPublicSignaturesUseOnly(List.of(BridgeLeak.class), ALLOWED_PACKAGES, FORBIDDEN_PREFIXES));
        if (!failure.getMessage().contains("bridge")) throw new AssertionError(failure.getMessage());
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
    public static final class ConstructorLeak { public ConstructorLeak(tools.jackson.databind.ObjectMapper value) {} }
    public static final class GenericReturnLeak { public List<tools.jackson.databind.ObjectMapper> leaked() { return List.of(); } }
    public static final class FieldLeak { public tools.jackson.databind.ObjectMapper leaked; }
    public static final class MapperLeak { public tools.jackson.databind.ObjectMapper mapper(tools.jackson.databind.ObjectMapper value) { return value; } }
    public static final class ComparatorLeak { public java.util.Comparator<tools.jackson.databind.ObjectMapper> comparator; }
    public static final class ValidatorLeak { public boolean validate(tools.jackson.databind.ObjectMapper value) { return false; } }
    public static final class DtoLeak { public tools.jackson.databind.ObjectMapper value; }
    interface Generic<T> { T value(); }
    public static final class BridgeLeak implements Generic<String> { public String value() { return ""; } }

    static final class PublicSurface {
        static void assertPublicSignaturesUseOnly(
                List<Class<?>> types, Set<String> allowedPackages, List<String> forbiddenPrefixes) {
            for (Class<?> type : types) {
                if (!Modifier.isPublic(type.getModifiers()) || type.isSynthetic() || type.isAnonymousClass()) continue;
                assertAllowed(type, allowedPackages, forbiddenPrefixes, type.getName());
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    if (Modifier.isPublic(constructor.getModifiers())) assertSignature(constructor.getGenericParameterTypes(), allowedPackages, forbiddenPrefixes, constructor.toString());
                }
                for (Method method : type.getDeclaredMethods()) {
                    if (!Modifier.isPublic(method.getModifiers())) continue;
                    if (method.isBridge() || (method.isSynthetic() && !isKotlinCompilerArtifact(method))) {
                        throw new AssertionError("Synthetic or bridge method leaked: " + method);
                    }
                    assertAllowed(method.getReturnType(), allowedPackages, forbiddenPrefixes, method.toString());
                    assertType(method.getGenericReturnType(), allowedPackages, forbiddenPrefixes, method.toString());
                    assertSignature(method.getGenericParameterTypes(), allowedPackages, forbiddenPrefixes, method.toString());
                }
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isPublic(field.getModifiers())) assertType(field.getGenericType(), allowedPackages, forbiddenPrefixes, field.toString());
                }
            }
        }

        private static boolean isKotlinCompilerArtifact(Method method) {
            return method.getName().endsWith("$default") || method.getName().startsWith("access$") || method.getName().endsWith("-impl");
        }

        private static void assertSignature(Type[] types, Set<String> allowedPackages, List<String> forbiddenPrefixes, String owner) {
            for (Type type : types) assertType(type, allowedPackages, forbiddenPrefixes, owner);
        }
        private static void assertType(Type type, Set<String> allowedPackages, List<String> forbiddenPrefixes, String owner) {
            if (type instanceof Class<?> clazz) { assertAllowed(clazz, allowedPackages, forbiddenPrefixes, owner); return; }
            if (type instanceof ParameterizedType parameterized) { assertType(parameterized.getRawType(), allowedPackages, forbiddenPrefixes, owner); for (Type argument : parameterized.getActualTypeArguments()) assertType(argument, allowedPackages, forbiddenPrefixes, owner); return; }
            if (type instanceof WildcardType wildcard) { for (Type bound : wildcard.getUpperBounds()) assertType(bound, allowedPackages, forbiddenPrefixes, owner); for (Type bound : wildcard.getLowerBounds()) assertType(bound, allowedPackages, forbiddenPrefixes, owner); return; }
            if (type instanceof TypeVariable<?> variable) for (Type bound : variable.getBounds()) assertType(bound, allowedPackages, forbiddenPrefixes, owner);
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
