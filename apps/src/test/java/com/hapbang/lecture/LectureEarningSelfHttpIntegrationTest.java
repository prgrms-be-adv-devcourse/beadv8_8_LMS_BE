package com.hapbang.lecture;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.web.client.RestClient;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.domain.ContentType;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContentSpec;
import com.hapbang.lecture.domain.LectureOrder;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.shared.lecture.domain.LectureEarningStatus;
import com.hapbang.shared.lecture.dto.LectureEarningAmountDto;
import com.hapbang.shared.lecture.dto.LectureEarningItemDto;
import com.hapbang.shared.lecture.dto.LectureEarningPageDto;
import com.hapbang.shared.lecture.dto.LectureEarningSummaryDto;
import com.hapbang.shared.lecture.out.LectureApiCallException;
import com.hapbang.shared.lecture.out.LectureApiClient;
import com.hapbang.shared.lecture.out.LectureClientProperties;
import com.hapbang.testsupport.DatabaseCleanUpListener;
import com.hapbang.testsupport.PostgresTestcontainersConfig;

// 다른 모듈이 쓰는 shared LectureApiClient가 실제 HTTP로 이 앱의 강의 내부 API를 부르는 전체 경로를 확인한다 (D62, #300).
// 내부 API 보안 체인·RsData 해제·직렬화가 한 번에 맞물리는지 본다. 상태 분류는 강의 모듈 테스트가 맡는다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainersConfig.class)
@TestExecutionListeners(listeners = DatabaseCleanUpListener.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class LectureEarningSelfHttpIntegrationTest {

    private static final Long SELLER_ID = 999_300L;
    private static final Long BUYER_ID = 999_301L;
    private static final long PRICE_HP = 10_000L;

    @LocalServerPort
    int port;

    @Value("${hapbang.internal-api.key}")
    String internalApiKey;

    @Autowired
    RestClient.Builder restClientBuilder;

    @Autowired
    LectureRepository lectureRepository;

    @Autowired
    LectureOrderRepository lectureOrderRepository;

    @Test
    void LectureApiClient로_자기_자신의_내부_수익_API를_부른다() {
        LocalDateTime now = LocalDateTime.now(LectureConfig.ZONE);
        LectureOrder order = lectureOrderRepository.save(LectureOrder.place(BUYER_ID,
                List.of(saveLecture("첫 강의"), saveLecture("둘째 강의")), Set.of(), now));
        LectureApiClient client = client(internalApiKey);

        LectureEarningSummaryDto summary = client.getEarningSummary(SELLER_ID);
        LectureEarningPageDto page = client.getEarnings(SELLER_ID, List.of(), 0, 1);

        assertThat(summary.confirmationPending()).isEqualTo(new LectureEarningAmountDto(PRICE_HP * 2, 2));
        assertThat(summary.scheduled()).isEqualTo(LectureEarningAmountDto.ZERO);
        // 응답은 +09:00이지만 앱 Jackson이 읽으면서 같은 시각의 UTC로 바꾼다. 시각만 비교한다
        assertThat(summary.asOf().toInstant()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(page.page().totalElements()).isEqualTo(2);
        assertThat(page.page().totalPages()).isEqualTo(2);
        // 같은 시각에 산 항목은 항목 ID 역순이다
        LectureEarningItemDto first = page.content().getFirst();
        assertThat(first.sourceId()).isEqualTo(order.getItems().get(1).getId());
        assertThat(first.sourceType()).isEqualTo(LectureEarningItemDto.SOURCE_TYPE);
        assertThat(first.title()).isEqualTo("둘째 강의");
        assertThat(first.amountHp()).isEqualTo(PRICE_HP);
        assertThat(first.status()).isEqualTo(LectureEarningStatus.CONFIRMATION_PENDING);
        assertThat(first.payoutScheduledAt()).isNotNull();
    }

    @Test
    void 키가_틀린_Client는_인증_실패_예외를_받는다() {
        LectureApiClient client = client(internalApiKey + "-wrong");

        assertThatThrownBy(() -> client.getEarningSummary(SELLER_ID))
                .isInstanceOfSatisfying(LectureApiCallException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(LectureApiCallException.Reason.REJECTED);
                    assertThat(e.getStatus()).isEqualTo(401);
                });
    }

    private LectureApiClient client(String apiKey) {
        return new LectureApiClient(restClientBuilder, new LectureClientProperties("http://localhost:" + port,
                Duration.ofSeconds(2), Duration.ofSeconds(5)), apiKey);
    }

    private Lecture saveLecture(String title) {
        LocalDateTime registeredAt = LocalDateTime.of(2026, 10, 1, 12, 0);
        Lecture lecture = Lecture.register(SELLER_ID, title, "설명", PRICE_HP, null,
                List.of(new LectureContentSpec(101L, "1강", ContentType.VIDEO)), registeredAt);
        lecture.approve(1L, registeredAt);
        return lectureRepository.save(lecture);
    }
}
