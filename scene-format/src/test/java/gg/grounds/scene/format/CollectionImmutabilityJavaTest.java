package gg.grounds.scene.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollectionImmutabilityJavaTest {
    @Test
    void exposedCollectionsAndBytesCannotMutateStoredState() {
        List<SceneProblem> problems = List.of(problem("/z"), problem("/a"));
        byte[] sourceBytes = {1, 2};

        SceneDocument scene = CollectionImmutabilityFixtures.document();
        SceneDecodeResult.Failure decode = new SceneDecodeResult.Failure(problems);
        SceneEncodeResult.Failure encode = new SceneEncodeResult.Failure(problems);
        SceneValidationResult validation = new SceneValidationResult(problems);
        SceneEncodeResult.Success success = new SceneEncodeResult.Success(sourceBytes);
        sourceBytes[0] = 9;

        assertThrows(UnsupportedOperationException.class, () -> scene.getMetadata().getTags().add("mutate"));
        assertThrows(UnsupportedOperationException.class, () -> scene.getGroups().add(CollectionImmutabilityFixtures.group()));
        assertThrows(UnsupportedOperationException.class, () -> scene.getElements().add(null));
        assertThrows(UnsupportedOperationException.class, () -> decode.getProblems().clear());
        assertThrows(UnsupportedOperationException.class, () -> encode.getProblems().clear());
        assertThrows(UnsupportedOperationException.class, () -> validation.getProblems().clear());
        byte[] returned = success.getBytes(); returned[0] = 8;

        assertEquals(List.of("a", "z"), new ArrayList<>(scene.getMetadata().getTags()));
        assertEquals(1, scene.getGroups().size());
        assertEquals(0, scene.getElements().size());
        assertEquals(List.of("/a", "/z"), decode.getProblems().stream().map(SceneProblem::getPath).toList());
        assertEquals(1, success.getBytes()[0]);
    }

    private static SceneProblem problem(String path) { return new SceneProblem(path, SceneProblemCode.INVALID_SCALE, null, "bad"); }
}
