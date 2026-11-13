package com.hapbang.auxiliary.chatbot.llm.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Activity;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Answer;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Appearance;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Member;
import com.hapbang.auxiliary.chatbot.llm.recommend.RecommendEvalData.Split;

/**
 * 더미 데이터 생성 규칙과 커밋한 파일(#867).
 * 생성기를 고치면 {@code RECOMMEND_EVAL_REGENERATE=yes}로 {@link #committedFileMatchesGenerator()}를 한 번 돌려 파일을 다시 쓰고
 * 함께 커밋한다(파일이 바뀌면 임베딩 캐시도 바뀐 글만 다시 만든다).
 */
class RecommendEvalDataGeneratorTest {

    private static final Path SOURCE = Path.of("src/test/resources", RecommendEvalData.RESOURCE);

    private final RecommendEvalData data = RecommendEvalDataGenerator.generate(RecommendEvalDataGenerator.SEED);

    @Test
    void committedFileMatchesGenerator() throws Exception {
        String generated = data.toJson();
        if ("yes".equals(System.getenv("RECOMMEND_EVAL_REGENERATE"))) {
            Files.createDirectories(SOURCE.getParent());
            Files.writeString(SOURCE, generated);
            return;
        }
        String committed;
        try (InputStream in = new ClassPathResource(RecommendEvalData.RESOURCE).getInputStream()) {
            committed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(committed.replace("\r\n", "\n")).as("생성기와 파일이 다르면 RECOMMEND_EVAL_REGENERATE=yes로 다시 만든다")
                .isEqualTo(generated);
        assertThat(RecommendEvalData.read()).as("파일을 읽은 값").isEqualTo(data);
    }

    @Test
    void sameSeedSameData() {
        assertThat(RecommendEvalDataGenerator.generate(RecommendEvalDataGenerator.SEED)).isEqualTo(data);
        assertThat(RecommendEvalDataGenerator.generate(1)).isNotEqualTo(data);
    }

    @Test
    void sizesAndNoiseRatio() {
        assertThat(data.fields()).hasSize(8);
        assertThat(data.lectures()).hasSize(120);
        assertThat(data.sellerIds()).hasSize(24);
        assertThat(data.members()).hasSize(100);
        assertThat(data.influencers()).hasSize(100);

        List<Activity> signals = new ArrayList<>(data.purchases());
        signals.addAll(data.carts());
        long noise = signals.stream().filter(signal -> !signal.taste()).count();
        assertThat((double) noise / signals.size()).as("잡음 비율 약 20%").isBetween(0.12, 0.28);
        Map<Long, Integer> purchases = new HashMap<>();
        for (Activity purchase : data.purchases()) {
            purchases.merge(purchase.memberId(), 1, Integer::sum);
        }
        assertThat(purchases.values()).allSatisfy(count -> assertThat(count).isBetween(3, 8));
    }

    @Test
    void lectureAnswerIsOwnPurchaseAndLeavesSignals() {
        Map<Long, Long> answers = RecommendEvalData.byRequester(data.lectureAnswers());
        assertThat(answers.keySet()).hasSize(100);
        RecommendEvalData visible = RecommendEvalHoldout.hide(data);
        for (Member member : data.members()) {
            long answer = answers.get(member.id());
            assertThat(data.purchases()).as("회원 %d의 정답은 자기 구매", member.id())
                    .anyMatch(purchase -> purchase.memberId() == member.id() && purchase.lectureId() == answer);
            assertThat(visible.purchases().stream().filter(purchase -> purchase.memberId() == member.id()).count())
                    .as("숨긴 뒤에도 구매가 2개 이상").isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void influencerAnswerIsPartnerAndLeavesOnePartner() {
        RecommendEvalData visible = RecommendEvalHoldout.hide(data);
        Map<Long, Set<Long>> before = partners(data.appearances());
        Map<Long, Set<Long>> after = partners(visible.appearances());
        for (Answer answer : data.influencerAnswers()) {
            assertThat(before.get(answer.requesterId())).contains(answer.answerId());
            assertThat(after.get(answer.requesterId())).as("인플루언서 %d", answer.requesterId())
                    .doesNotContain(answer.answerId())
                    .isNotEmpty();
        }
        assertThat(data.influencerAnswers()).hasSize(100);
        assertThat(before.values()).as("README: 함께 출연을 마친 상대 2~8명")
                .allSatisfy(partners -> assertThat(partners.size()).isBetween(2, 8));
    }

    @Test
    void splitIsEightyTwentyWithoutOverlap() {
        for (Split split : List.of(data.lectureSplit(), data.influencerSplit())) {
            Set<Long> all = new HashSet<>(split.tuning());
            all.addAll(split.holdout());
            assertThat(split.tuning()).hasSize(80);
            assertThat(split.holdout()).hasSize(20);
            assertThat(all).hasSize(100);
        }
    }

    private static Map<Long, Set<Long>> partners(List<Appearance> appearances) {
        Map<Long, Set<Long>> partners = new HashMap<>();
        for (Appearance appearance : appearances) {
            partners.computeIfAbsent(appearance.hostId(), id -> new HashSet<>()).add(appearance.guestId());
            partners.computeIfAbsent(appearance.guestId(), id -> new HashSet<>()).add(appearance.hostId());
        }
        return partners;
    }
}
