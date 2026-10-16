package com.hapbang.appearance.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.config.AppearanceConfig;
import com.hapbang.appearance.fixture.AppearanceRecruitFixture;
import com.hapbang.appearance.fixture.AppearanceRequestFixture;
import com.hapbang.appearance.fixture.AppearanceUserFixture;
import com.hapbang.appearance.support.AppearanceIntegrationTest;

/**
 * Billing 호출 때문에 트랜잭션 없이 도는 Facade 메서드도, 작업 뒤 상세를 다시 읽을 때는 읽기 전용 트랜잭션 하나에서 읽는다
 * (ISSUE.md APR-37 R1). 상세 조회가 처음 읽는 Support 호출 시점의 트랜잭션 상태를 기록해 확인한다.
 */
@AppearanceIntegrationTest
@WithMockUser(roles = "INFLUENCER")
class AppearanceDetailReadTransactionTest {

    private static final Long HOST = 1L;
    private static final Long GUEST = 2L;

    @Autowired
    AppearanceRequestFacade appearanceRequestFacade;

    @Autowired
    AppearanceRecruitFacade appearanceRecruitFacade;

    @Autowired
    AppearanceFacade appearanceFacade;

    @Autowired
    AppearanceUserFacade appearanceUserFacade;

    @MockitoSpyBean
    AppearanceRequestSupport appearanceRequestSupport;

    @MockitoSpyBean
    AppearanceRecruitSupport appearanceRecruitSupport;

    @MockitoSpyBean
    AppearanceSupport appearanceSupport;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AppearanceConfig.CLOCK)
    Clock clock;

    /** getById가 불릴 때마다 "트랜잭션 안인지, 읽기 전용인지"를 남긴다 */
    private final List<String> transactionStates = new ArrayList<>();

    @BeforeEach
    void setUp() {
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(HOST));
        appearanceUserFacade.syncJoined(AppearanceUserFixture.influencer(GUEST));
    }

    private void recordTransactionOnGetById() {
        doAnswer(invocation -> {
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive() + "/"
                    + TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.callRealMethod();
        }).when(appearanceRequestSupport).getById(any());
        doAnswer(invocation -> {
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive() + "/"
                    + TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.callRealMethod();
        }).when(appearanceRecruitSupport).getById(any());
        doAnswer(invocation -> {
            transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive() + "/"
                    + TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.callRealMethod();
        }).when(appearanceSupport).getById(any());
    }

    /** 마지막 getById(= 작업 뒤 상세 조회)가 읽기 전용 트랜잭션 안이었는지 */
    private void assertLastReadInReadOnlyTransaction() {
        assertThat(transactionStates).isNotEmpty();
        assertThat(transactionStates.getLast()).isEqualTo("true/true");
    }

    @Test
    @DisplayName("요청서 거절(트랜잭션 없는 Facade): 반환 실행 뒤 상세를 읽기 전용 트랜잭션에서 읽는다")
    void requestReject() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(LocalDateTime.now(clock)));
        recordTransactionOnGetById();

        appearanceRequestFacade.reject(GUEST, sent.id(), null);

        assertLastReadInReadOnlyTransaction();
    }

    @Test
    @DisplayName("모집글 조기 마감(트랜잭션 없는 Facade): 반환 실행 뒤 상세를 읽기 전용 트랜잭션에서 읽는다")
    void recruitCloseEarly() {
        Long recruitId = appearanceRecruitFacade.create(HOST,
                AppearanceRecruitFixture.content(LocalDateTime.now(clock))).id();
        recordTransactionOnGetById();

        appearanceRecruitFacade.closeEarly(HOST, recruitId);

        assertLastReadInReadOnlyTransaction();
    }

    @Test
    @DisplayName("출연 확인(트랜잭션 없는 Facade, 회원 자격 확인 없는 상세): 지급 실행 뒤 상세를 읽기 전용 트랜잭션에서 읽는다")
    void appearanceConfirm() {
        AppearanceRequestResDto sent = appearanceRequestFacade.create(HOST, GUEST,
                AppearanceRequestFixture.content(LocalDateTime.now(clock)));
        Long appearanceId = appearanceRequestFacade.accept(GUEST, sent.id(), sent.version()).appearanceId();
        jdbcTemplate.update("update appearances.appearances set scheduled_at = ? where id = ?",
                LocalDateTime.now(clock).minusHours(1), appearanceId);
        recordTransactionOnGetById();

        appearanceFacade.confirm(HOST, appearanceId);

        assertLastReadInReadOnlyTransaction();
    }
}
