package com.hapbang.billing.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.hapbang.billing.app.BillingUserSyncUseCase.Outcome;
import com.hapbang.billing.domain.BillingUser;
import com.hapbang.billing.out.BillingUserRepository;
import com.hapbang.billing.out.WalletRepository;
import com.hapbang.shared.member.domain.Role;
import com.hapbang.shared.member.domain.UserStatus;
import com.hapbang.shared.member.dto.UserDto;
import com.hapbang.testsupport.IntegrationTest;

// Billing 회원 복제본(#294)을 실제 PostgreSQL로 검증한다: 가입·변경 이벤트 모두 같은 Upsert 규칙, #292 회원 버전 비교(같거나 낮으면 무시,
// 높으면 반영), 역순·동시 반영, 버전 없는 정보 무시. Kafka로 받는 경로(지갑 생성 그룹과의 분리 포함)는 BillingMemberEventKafkaTest (#430).
// 이 클래스만 쓰는 컨텍스트라 연결 풀을 작게 두고 끝나면 닫는다(다른 테스트 컨텍스트와 함께 DB 최대 연결 수를 넘지 않게).
@IntegrationTest
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BillingUserSyncIntegrationTest {

    private static final Long USER_ID = 100L;

    @Autowired
    BillingUserSyncUseCase syncUseCase;

    @Autowired
    BillingUserRepository billingUserRepository;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("가입 정보로 복제본을 만들고 회원 버전을 기록한다")
    void createdByJoin() {
        assertThat(syncUseCase.apply(dto("민수", Role.USER, UserStatus.ACTIVE, 0L))).isEqualTo(Outcome.CREATED);

        assertThat(replica()).satisfies(user -> {
            assertThat(user.getNickname()).isEqualTo("민수");
            assertThat(user.getRole()).isEqualTo(Role.USER);
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getMemberVersion()).isZero();
        });
    }

    @Test
    @DisplayName("가입보다 변경 정보가 먼저 와도 복제본을 만든다(다른 모듈과 달리 실패로 남기지 않는다)")
    void createdByModification() {
        assertThat(syncUseCase.apply(dto("민수", Role.INFLUENCER, UserStatus.ACTIVE, 3L))).isEqualTo(Outcome.CREATED);

        assertThat(replica().getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(replica().getMemberVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("같거나 낮은 버전은 무시하고, 높은 버전은 건너뛴 버전이 있어도 반영한다")
    void versionComparison() {
        syncUseCase.apply(dto("민수", Role.USER, UserStatus.ACTIVE, 2L));

        assertThat(syncUseCase.apply(dto("같은버전", Role.ADMIN, UserStatus.ACTIVE, 2L))).isEqualTo(Outcome.IGNORED_STALE);
        assertThat(syncUseCase.apply(dto("옛버전", Role.ADMIN, UserStatus.ACTIVE, 1L))).isEqualTo(Outcome.IGNORED_STALE);
        assertThat(replica().getNickname()).isEqualTo("민수");

        assertThat(syncUseCase.apply(dto("영희", Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, 7L)))
                .isEqualTo(Outcome.UPDATED);
        assertThat(replica()).satisfies(user -> {
            assertThat(user.getNickname()).isEqualTo("영희");
            assertThat(user.getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
            assertThat(user.getMemberVersion()).isEqualTo(7L);
        });
    }

    @Test
    @DisplayName("역순: 높은 버전 변경 뒤 늦게 온 가입(버전 0)은 복제본을 덮어쓰지 않는다")
    void lateJoinDoesNotOverwrite() {
        syncUseCase.apply(dto("인플루언서", Role.INFLUENCER, UserStatus.SUSPENDED, 4L));

        assertThat(syncUseCase.apply(dto("가입때", Role.USER, UserStatus.ACTIVE, 0L))).isEqualTo(Outcome.IGNORED_STALE);

        assertThat(replica()).satisfies(user -> {
            assertThat(user.getRole()).isEqualTo(Role.INFLUENCER);
            assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(user.getMemberVersion()).isEqualTo(4L);
        });
    }

    @Test
    @DisplayName("버전 없는 정보는 최신으로 보지 않는다: 복제본을 만들지도, 고치지도 않는다")
    void versionlessIgnored() {
        assertThat(syncUseCase.apply(new UserDto(USER_ID, "버전없음", Role.ADMIN, UserStatus.ACTIVE)))
                .isEqualTo(Outcome.IGNORED_VERSIONLESS);
        assertThat(billingUserRepository.findById(USER_ID)).isEmpty();

        syncUseCase.apply(dto("민수", Role.INFLUENCER, UserStatus.ACTIVE, 1L));
        assertThat(syncUseCase.apply(new UserDto(USER_ID, "버전없음", Role.ADMIN, UserStatus.WITHDRAWN)))
                .isEqualTo(Outcome.IGNORED_VERSIONLESS);
        assertThat(replica().getRole()).isEqualTo(Role.INFLUENCER);
        assertThat(replica().getMemberVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 회원의 이벤트를 동시에(순서 섞어) 반영해도 복제본은 한 건이고 가장 높은 버전이 남는다")
    void concurrentUpsert() throws Exception {
        List<Long> versions = new ArrayList<>();
        for (long version = 0; version < 10; version++) {
            versions.add(version);
        }
        Collections.shuffle(versions);
        ExecutorService executor = Executors.newFixedThreadPool(versions.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Long version : versions) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return syncUseCase.apply(dto("v" + version,
                            version % 2 == 0 ? Role.INFLUENCER : Role.VERIFIED_INFLUENCER, UserStatus.ACTIVE, version));
                }));
            }
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).filteredOn(outcome -> outcome == Outcome.CREATED).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM billing.billing_user WHERE id = ?", Integer.class,
                USER_ID)).isEqualTo(1);
        assertThat(replica().getMemberVersion()).isEqualTo(9L);
        assertThat(replica().getNickname()).isEqualTo("v9");
        assertThat(replica().getRole()).isEqualTo(Role.VERIFIED_INFLUENCER);
    }

    private BillingUser replica() {
        return billingUserRepository.findById(USER_ID).orElseThrow();
    }

    private static UserDto dto(String nickname, Role role, UserStatus status, Long version) {
        return new UserDto(USER_ID, nickname, role, status, version);
    }
}
