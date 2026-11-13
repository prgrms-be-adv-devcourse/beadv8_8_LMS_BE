package com.hapbang.auxiliary.chatbot.llm.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;

class CachingEmbeddingModelTest {

    @TempDir
    Path dir;

    private final FakeEmbeddingModel delegate = new FakeEmbeddingModel()
            .put("게임", FakeEmbeddingModel.axis(0))
            .put("요리", FakeEmbeddingModel.axis(1));

    @Test
    void secondRunReadsFileWithoutCallingModel() {
        CachingEmbeddingModel first = new CachingEmbeddingModel(delegate, "fake-8", 8, dir);
        List<float[]> vectors = first.embed(List.of("게임 강의", "요리 강의"));

        CachingEmbeddingModel second = new CachingEmbeddingModel(delegate, "fake-8", 8, dir);
        List<float[]> again = second.embed(List.of("게임 강의", "요리 강의"));

        assertThat(first.delegateTexts()).isEqualTo(2);
        assertThat(second.delegateTexts()).isZero();
        assertThat(delegate.embeddedTexts()).isEqualTo(2);
        assertThat(again).containsExactlyElementsOf(vectors);
        assertThat(again.getFirst()).containsExactly(FakeEmbeddingModel.axis(0));
    }

    @Test
    void changedTextIsEmbeddedAgainAndOnlyIt() {
        new CachingEmbeddingModel(delegate, "fake-8", 8, dir).embed(List.of("게임 강의", "요리 강의"));

        CachingEmbeddingModel second = new CachingEmbeddingModel(delegate, "fake-8", 8, dir);
        List<float[]> vectors = second.embed(List.of("게임 강의", "요리 강의 고침"));

        assertThat(second.delegateTexts()).isEqualTo(1);
        assertThat(delegate.texts()).containsExactly("게임 강의", "요리 강의", "요리 강의 고침");
        assertThat(vectors.get(1)).containsExactly(FakeEmbeddingModel.axis(1));
    }

    @Test
    void sameTextInOneRequestIsSentOnce() {
        CachingEmbeddingModel cache = new CachingEmbeddingModel(delegate, "fake-8", 8, dir);

        List<float[]> vectors = cache.embed(List.of("게임 강의", "게임 강의"));

        assertThat(cache.delegateTexts()).isEqualTo(1);
        assertThat(vectors).hasSize(2);
    }

    @Test
    void otherModelNameDoesNotShareEntries() {
        new CachingEmbeddingModel(delegate, "fake-8", 8, dir).embed(List.of("게임 강의"));

        CachingEmbeddingModel other = new CachingEmbeddingModel(delegate, "other-8", 8, dir);
        other.embed(List.of("게임 강의"));

        assertThat(other.delegateTexts()).isEqualTo(1);
        assertThat(Files.exists(dir.resolve("fake-8.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("other-8.json"))).isTrue();
    }

    @Test
    void fileHoldsNoText() throws Exception {
        new CachingEmbeddingModel(delegate, "fake-8", 8, dir).embed(List.of("게임 강의"));

        assertThat(Files.readString(dir.resolve("fake-8.json"))).doesNotContain("게임");
    }
}
