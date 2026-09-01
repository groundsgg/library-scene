package gg.grounds.scene.minestom

import gg.grounds.scene.format.*

object SceneMinestomJavaFixtures {
    @JvmStatic
    fun scene(): SceneDocument =
        SceneDocument(
            schemaVersion = 1,
            id = SceneId("test:java"),
            metadata = SceneMetadata("Java API", null, emptySet()),
            catalogs =
                SceneCatalogReferences(
                    CatalogReference(CatalogId("test:assets"), "1"),
                    CatalogReference(CatalogId("test:actions"), "1"),
                ),
            groups = emptyList(),
            elements =
                listOf(
                    Prop(
                        LocalId("java-prop"),
                        null,
                        Transform(
                            Vec3(0.0, 0.0, 0.0),
                            EulerRotation(0.0, 0.0, 0.0),
                            Vec3(1.0, 1.0, 1.0),
                        ),
                        visible = true,
                        activation = ActivationPolicy.AUTOMATIC,
                        asset = AssetKey("test:missing"),
                        initialAnimation = null,
                    )
                ),
        )

    @JvmStatic
    fun assets(): AssetCatalog =
        AssetCatalog(
            CatalogId("test:assets"),
            "1",
            CatalogVersionRange(CatalogId("test:assets"), "1", "1"),
            emptyMap(),
        )

    @JvmStatic
    fun actions(): ActionCatalog = ActionCatalog(CatalogId("test:actions"), "1", emptyMap())
}
