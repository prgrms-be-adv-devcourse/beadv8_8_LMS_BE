package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.hapbang.auxiliary.chatbot.domain.Faq;
import com.hapbang.auxiliary.chatbot.domain.FaqCategory;
import com.hapbang.auxiliary.chatbot.domain.FaqContent;

// 초기 FAQ 원본(시드 파일)의 형태와 FAQ 엔티티의 색인 판단(#715)
class FaqSeedReaderTest {

    @Test
    @DisplayName("시드는 53건, 분류 11개를 모두 쓰고, code가 겹치지 않으며, 테이블 길이 안이다")
    void seedFile() {
        List<FaqSeed> seeds = new FaqSeedReader().read();

        assertThat(seeds).hasSize(53);
        assertThat(seeds.stream().map(FaqSeed::code).distinct()).hasSize(53);
        assertThat(seeds.stream().map(FaqSeed::category).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(FaqCategory.values());
        assertThat(seeds).allSatisfy(seed -> {
            assertThat(seed.code()).matches("FAQ-\\d{3}");
            assertThat(seed.question()).hasSizeLessThanOrEqualTo(200);
            assertThat(seed.answer()).hasSizeLessThanOrEqualTo(2000);
            assertThat(seed.policyRef()).isNotBlank().hasSizeLessThanOrEqualTo(300);
        });
    }

    @Test
    @DisplayName("새 FAQ는 색인이 필요하고, 색인 뒤 같은 값으로 고치면 필요 없고, 내용이 바뀌면 다시 필요하다")
    void needsIndexing() {
        Faq faq = Faq.create("FAQ-001", new FaqContent(FaqCategory.ACCOUNT, "질문", "답변", "근거", 0));
        assertThat(faq.needsIndexing()).isTrue();

        faq.markIndexed(faq.getContentHash(), LocalDateTime.now());
        assertThat(faq.update(new FaqContent(FaqCategory.ACCOUNT, "질문", "답변", "근거", 0))).isFalse();
        assertThat(faq.needsIndexing()).isFalse();

        // 근거·순서만 바뀌면 임베딩 내용은 같다
        assertThat(faq.update(new FaqContent(FaqCategory.ACCOUNT, "질문", "답변", "다른 근거", 1))).isTrue();
        assertThat(faq.needsIndexing()).isFalse();

        assertThat(faq.update(new FaqContent(FaqCategory.ACCOUNT, "질문", "바뀐 답변", "다른 근거", 1))).isTrue();
        assertThat(faq.needsIndexing()).isTrue();
    }
}
