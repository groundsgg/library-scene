package gg.grounds.scene.minestom;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.grounds.scene.format.AssetKind;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.Instance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SceneMinestomJavaApiTest {
    @Test
    void JavaCanConstructTheHostBoundaryAndConsumeFactoryAndCloseShapes() {
        SceneRuntimeConfig config = new SceneRuntimeConfig();
        SceneRuntimeIdentity identity = SceneRuntimeJava.identity("test:java", "test:map", 1L);
        JavaSceneAssetRendererRegistry renderers =
                new JavaSceneAssetRendererRegistry() {
                    @Override
                    public SceneAssetRendererFactory rendererFor(String asset, AssetKind kind) {
                        return null; // Exercises readiness failure without requiring an Instance tick.
                    }
                };
        JavaSceneEffectSink effects =
                new JavaSceneEffectSink() {
                    @Override
                    public boolean supports(String asset, AssetKind kind) {
                        return true;
                    }

                    @Override
                    public void playSound(net.minestom.server.entity.Player player, String sound, double volume, double pitch) {}

                    @Override
                    public void emitParticle(Instance instance, String particle, net.minestom.server.coordinate.Point point, int count, gg.grounds.scene.format.Vec3 offset, double speed) {}
                };
        ScenePlayerPolicy policy =
                new ScenePlayerPolicy() {
                    @Override
                    public boolean isEligible(net.minestom.server.entity.Player player) {
                        return true;
                    }

                    @Override
                    public boolean hasPermission(net.minestom.server.entity.Player player, String permission) {
                        return true;
                    }
                };
        JavaSceneActionRegistry actions =
                new JavaSceneActionRegistry() {
                    @Override
                    public SceneActionHandler handlerFor(String key) {
                        return context -> CompletableFuture.completedFuture(SceneActionResult.Success.INSTANCE);
                    }
                };
        SceneRuntimeRequest request =
                new SceneRuntimeRequest(
                        SceneMinestomJavaFixtures.scene(),
                        SceneMinestomJavaFixtures.assets(),
                        SceneMinestomJavaFixtures.actions(),
                        identity,
                        MinecraftServer.getInstanceManager().createInstanceContainer(),
                        renderers,
                        effects,
                        policy,
                        actions,
                        () -> 1L,
                        config);

        CompletionStage<SceneRuntimeCreationResult> stage = SceneRuntimeFactory.INSTANCE.create(request);
        assertTrue(stage.toCompletableFuture().isDone());
        assertInstanceOf(SceneRuntimeCreationResult.Failure.class, stage.toCompletableFuture().join());
    }

    private static CompletionStage<Void> consumeClose(SceneRuntime runtime) {
        return runtime.close();
    }

    @BeforeAll
    static void bootMinestom() {
        MinecraftServer.init();
    }
}
