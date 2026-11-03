package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.domain.HpReconciliationMismatch;
import com.hapbang.appearance.domain.HpReconciliationRule;
import com.hapbang.appearance.domain.HpReconciliationRun;
import com.hapbang.appearance.domain.HpReconciliationStatus;
import com.hapbang.appearance.domain.HpTaskRefType;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.out.HpReconciliationCursorRepository;
import com.hapbang.appearance.out.HpReconciliationMismatchRepository;
import com.hapbang.appearance.support.AppearanceTestClock;
import com.hapbang.appearance.support.MockBilling;
import com.hapbang.appearance.support.MockBillingIntegrationTest;
import com.hapbang.shared.billing.domain.BillingReferenceType;

/**
 * 서비스 간 HP 대사의 지갑 HTTP 연동 (#581). 운영과 같은 {@code BillingClient} 구현이 HTTP로 Mock Billing 서버의 대사 조회(#577)를 부른다.
 * 요청 모양(내부 API 키, 쿼리, JSON 본문)과 응답 해석·실패 처리를 본다. 비교 규칙은 {@code AppearanceHpReconciliationTest}가 본다.
 */
@WithMockUser(roles = "INFLUENCER")
class AppearanceHpReconciliationHttpTest extends MockBillingIntegrationTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;
    private static final long FEE = AppearanceRequestFixture.FEE;

    @Autowired
    HpReconciliationUseCase hpReconciliationUseCase;

    @Autowired
    HpReconciliationMismatchRepository mismatchRepository;

    @Autowired
    HpReconciliationCursorRepository cursorRepository;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    AppearanceTestClock clock;

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }

    /** 거절해 전부 반환한 요청서. 변경 시각을 진행 중 유예(2시간) 밖으로 옮긴다 */
    private Long rejectedRequest() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(clock.now()));
        appearanceRequestFacade.reject(GUEST, sent.id(), "일정이 맞지 않습니다.");
        LocalDateTime settled = clock.now().minusHours(3);
        jdbcTemplate.update("update appearances.requests set updated_at = ?", settled);
        jdbcTemplate.update("update appearances.hp_tasks set updated_at = ?", settled);
        return sent.id();
    }

    private static String amount(long total) {
        return "{\"chargeHp\":%d,\"revenueHp\":0,\"totalHp\":%d}".formatted(total, total);
    }

    private static String releasedRequest(long requestId) {
        return """
                {"referenceType":"APPEARANCE_REQUEST","referenceId":%d,
                 "hold":{"status":"RELEASED","payerUserId":%d,"original":%s,"remaining":%s,"released":%s,"paid":%s,"captured":%s},
                 "voided":null,"settlements":[]}
                """.formatted(requestId, HOST, amount(FEE), amount(0), amount(FEE), amount(0), amount(0));
    }

    private static String page(String items, String nextUpdatedAfter, long nextAfterId) {
        return "{\"items\":[%s],\"nextUpdatedAfter\":\"%s\",\"nextAfterId\":%d,\"hasMore\":false}"
                .formatted(items, nextUpdatedAfter, nextAfterId);
    }

    @Test
    @DisplayName("목록은 GET(참조 유형·처음 커서·500건, 내부 API 키), 조회는 POST JSON(참조 유형·ID 목록) → 응답을 읽어 비교하고 커서를 KST로 저장한다")
    void requestShapeAndCompare() {
        Long requestId = rejectedRequest();
        String orphan = """
                {"referenceType":"APPEARANCE_REQUEST","referenceId":777,
                 "hold":{"status":"HELD","payerUserId":%d,"original":%s,"remaining":%s,"released":%s,"paid":%s,"captured":%s},
                 "voided":null,"settlements":[]}
                """.formatted(HOST, amount(FEE), amount(FEE), amount(0), amount(0), amount(0));
        MockBilling.holdListReturns(BillingReferenceType.APPEARANCE_REQUEST, page(orphan, "2026-11-03T01:00:00Z", 5));
        MockBilling.holdListReturns(BillingReferenceType.APPEARANCE_RECRUIT, page("", "1970-01-01T00:00:00Z", 0));
        MockBilling.holdQueryReturns("[" + releasedRequest(requestId) + "]");

        HpReconciliationRun run = hpReconciliationUseCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.COMPLETED);
        assertThat(run.getCheckedCount()).isEqualTo(2);
        assertThat(mismatchRepository.findByRunIdOrderByIdAsc(run.getId()))
                .extracting(HpReconciliationMismatch::getReferenceId, HpReconciliationMismatch::getRule)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(777L, HpReconciliationRule.WALLET_ONLY_HOLD));

        List<MockBilling.ReceivedRequest> lists = MockBilling.holdListRequests();
        assertThat(lists).hasSize(2);
        assertThat(lists.getFirst().path()).contains("referenceType=APPEARANCE_REQUEST", "updatedAfter=1970-01-01T00",
                "afterId=0", "size=500");
        assertThat(lists).allSatisfy(request -> assertThat(request.apiKey()).isNotBlank());

        List<MockBilling.ReceivedRequest> queries = MockBilling.holdQueryRequests();
        assertThat(queries).hasSize(1);
        assertThat(queries.getFirst().apiKey()).isNotBlank();
        assertThat(queries.getFirst().contentType()).startsWith("application/json");
        assertThat(queries.getFirst().text("referenceType")).isEqualTo("APPEARANCE_REQUEST");
        assertThat(queries.getFirst().body().get("referenceIds")).isEqualTo(List.of(requestId.intValue()));

        assertThat(cursorRepository.findById(HpTaskRefType.APPEARANCE_REQUEST)).hasValueSatisfying(cursor -> {
            assertThat(cursor.getUpdatedAfter()).isEqualTo(LocalDateTime.of(2026, 11, 3, 10, 0));
            assertThat(cursor.getAfterId()).isEqualTo(5L);
        });
    }

    @Test
    @DisplayName("보류 조회가 500이면(결과 불명) INCOMPLETE로 끝나고 커서를 저장하지 않는다")
    void queryServerError() {
        rejectedRequest();
        MockBilling.holdListReturns(BillingReferenceType.APPEARANCE_REQUEST, page("", "2026-11-03T01:00:00Z", 5));
        MockBilling.holdListReturns(BillingReferenceType.APPEARANCE_RECRUIT, page("", "1970-01-01T00:00:00Z", 0));
        MockBilling.holdQueryFails(500);

        HpReconciliationRun run = hpReconciliationUseCase.runScheduled();

        assertThat(run.getStatus()).isEqualTo(HpReconciliationStatus.INCOMPLETE);
        assertThat(run.getFailureReason()).contains("지갑 보류 조회", "결과를 받지 못함");
        assertThat(cursorRepository.findAll()).isEmpty();
    }
}
