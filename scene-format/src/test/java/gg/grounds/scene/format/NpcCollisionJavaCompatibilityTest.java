package gg.grounds.scene.format;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.util.List;
import kotlin.jvm.internal.DefaultConstructorMarker;
import org.junit.jupiter.api.Test;

class NpcCollisionJavaCompatibilityTest {
    @Test
    void oldJvmConstructorStillConstructsAnNpcWithDefaultCollision() throws Exception {
        Constructor<Npc> oldConstructor = Npc.class.getConstructor(
                String.class,
                String.class,
                Transform.class,
                boolean.class,
                ActivationPolicy.class,
                String.class,
                net.kyori.adventure.text.Component.class,
                Vec3.class,
                LookBehavior.class,
                String.class,
                LocalBounds.class,
                ProximitySensor.class,
                List.class,
                int.class,
                DefaultConstructorMarker.class);

        Npc npc = oldConstructor.newInstance(
                "npc",
                null,
                new Transform(
                        new Vec3(0.0, 0.0, 0.0),
                        new EulerRotation(0.0, 0.0, 0.0),
                        new Vec3(1.0, 1.0, 1.0)),
                true,
                ActivationPolicy.AUTOMATIC,
                "test:npc",
                null,
                new Vec3(0.0, 1.0, 0.0),
                LookBehavior.Fixed.INSTANCE,
                null,
                new LocalBounds(new Vec3(0.0, 1.0, 0.0), new Vec3(1.0, 2.0, 1.0)),
                null,
                List.of(),
                0,
                null);

        assertEquals(new NpcCollision(), npc.getCollision());
    }
}
