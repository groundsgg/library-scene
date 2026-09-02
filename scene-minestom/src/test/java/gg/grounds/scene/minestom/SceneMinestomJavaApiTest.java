package gg.grounds.scene.minestom;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.grounds.scene.format.AssetKind;
import java.util.concurrent.atomic.AtomicInteger;
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
        assertEquals("test:java", identity.getSceneIdValue());
        assertEquals("java-prop", SceneMinestomJavaFixtures.problem().getElementIdValue());
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger registryLookups = new AtomicInteger();
        JavaSceneAssetRendererRegistry renderers =
                new JavaSceneAssetRendererRegistry() {
                    @Override
                    public SceneAssetRendererFactory rendererFor(String asset, AssetKind kind) {
                        if (registryLookups.incrementAndGet() != 1) {
                            return null;
                        }
                        return context -> {
                            factoryCalls.incrementAndGet();
                            assertEquals("java-prop", context.getElementIdValue());
                            assertEquals(null, context.getPartIdValue());
                            assertEquals("test:prop", context.getAssetValue());
                            return CompletableFuture.completedFuture(
                                    new JavaRenderedAssetHandle() {
                                        @Override
                                        public void applyTransform(SceneRenderTransform transform) {}

                                        @Override
                                        public void applyViewerState(net.minestom.server.entity.Player player, SceneViewerVisualState state) {}

                                        @Override
                                        public void clearViewerState(net.minestom.server.entity.Player player) {}

                                        @Override
                                        public void startAnimation(String animation, long elapsedMillis) {}

                                        @Override
                                        public void stopAnimation(String animation) {}

                                        @Override
                                        public void advanceAnimation(long elapsedMillis) {}

                                        @Override
                                        public void close() {}
                                    });
                        };
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
                        return context -> {
                            assertNotNull(context.getElementIdValue());
                            return CompletableFuture.completedFuture(SceneActionResult.Success.INSTANCE);
                        };
                    }
                };
        Instance instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        SceneRuntimeRequest request =
                new SceneRuntimeRequest(
                        SceneMinestomJavaFixtures.scene(),
                        SceneMinestomJavaFixtures.assets(),
                        SceneMinestomJavaFixtures.actions(),
                        identity,
                        instance,
                        renderers,
                        effects,
                        policy,
                        actions,
                        () -> 1L,
                        config);

        CompletionStage<SceneRuntimeCreationResult> stage = SceneRuntimeFactory.INSTANCE.create(request);
        tickUntil(instance, stage.toCompletableFuture());
        SceneRuntimeCreationResult.Success success =
                assertInstanceOf(SceneRuntimeCreationResult.Success.class, stage.toCompletableFuture().join());
        assertEquals(1, factoryCalls.get());
        assertEquals(1, registryLookups.get());
        CompletionStage<Void> close = consumeClose(success.getRuntime());
        tickUntil(instance, close.toCompletableFuture());
        close.toCompletableFuture().join();
    }

    private static CompletionStage<Void> consumeClose(SceneRuntime runtime) {
        return runtime.close();
    }

    private static void tickUntil(Instance instance, CompletableFuture<?> future) {
        for (int attempt = 0; attempt < 20 && !future.isDone(); attempt++) {
            instance.tick(0L);
        }
        assertTrue(future.isDone());
    }

    @BeforeAll
    static void bootMinestom() {
        MinecraftServer.init();
    }
}
