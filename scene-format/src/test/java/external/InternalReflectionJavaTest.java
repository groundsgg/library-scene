package external;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class InternalReflectionJavaTest {
    @Test
    void implementationOwnersAreNotReflectivelyAccessibleWithoutAccessOverride() throws Exception {
        for (String name : new String[] {
            "gg.grounds.scene.format.SceneMapper",
            "gg.grounds.scene.format.SceneWire",
            "gg.grounds.scene.format.WireMapping",
            "gg.grounds.scene.format.CanonicalJson",
            "gg.grounds.scene.format.ComponentWireValidator",
            "gg.grounds.scene.format.IntrinsicValidator",
            "gg.grounds.scene.format.CatalogValidator",
            "gg.grounds.scene.format.CollectionCopiesKt"
        }) {
            Class<?> type = Class.forName(name);
            assertFalse(Modifier.isPublic(type.getModifiers()), name);
            for (var constructor : type.getDeclaredConstructors()) {
                assertFalse(constructor.canAccess(null), constructor.toString());
                assertThrows(
                        IllegalAccessException.class,
                        () -> constructor.newInstance(new Object[constructor.getParameterCount()]),
                        constructor.toString());
            }
        }
    }
}
