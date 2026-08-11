package gg.grounds.scene.testkit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmPublicSurfaceJavaTest {
    @Test
    void publicTestkitApiDoesNotExposeTestFrameworkTypes() throws Exception {
        assertPublicSignaturesContainNoTestFrameworkType(Class.forName("gg.grounds.scene.testkit.SceneAssertionsKt"));
        assertPublicSignaturesContainNoTestFrameworkType(SceneFixtures.class);
    }

    private void assertPublicSignaturesContainNoTestFrameworkType(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            assertFalse(method.getReturnType().getName().startsWith("org.junit."));
            assertFalse(method.getReturnType().getName().startsWith("kotlin.test."));
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter.getName().startsWith("org.junit."));
                assertFalse(parameter.getName().startsWith("kotlin.test."));
            }
        }
        assertTrue(type.getDeclaredMethods().length > 0);
    }
}
