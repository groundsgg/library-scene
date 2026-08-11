package gg.grounds.scene.format

import gg.grounds.scene.format.internal.CatalogValidator
import gg.grounds.scene.format.internal.IntrinsicValidator

object SceneValidation {
    fun validateIntrinsic(scene: SceneDocument): SceneValidationResult =
        SceneValidationResult(IntrinsicValidator.validate(scene).distinct().sortedWith(SceneProblem.ORDERING))

    fun validateCatalogs(scene: SceneDocument, assets: AssetCatalog, actions: ActionCatalog): SceneValidationResult =
        SceneValidationResult((IntrinsicValidator.validate(scene) + CatalogValidator.validate(scene, assets, actions)).distinct().sortedWith(SceneProblem.ORDERING))
}
