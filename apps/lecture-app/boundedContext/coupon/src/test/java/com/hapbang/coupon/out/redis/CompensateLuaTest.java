package com.hapbang.coupon.out.redis;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.hapbang.coupon.app.port.CouponIssueStoreUnavailableException;
import com.hapbang.coupon.domain.IssueCompensation;
import com.hapbang.testsupport.RedisTestcontainersConfig;

/**
 * compensate.lua: 보상 표식·epoch 검증·재고 복구·회원 기록 삭제가 한 번에 되고, 다시 불러도 두 번 복구하지 않는지 실제 Redis로 본다.
 * 접수 기록은 issue.lua와 같은 형식으로 직접 써 둔다(접수 경로와 분리해 보상 규칙만 본다). 공유 Redis라 쿠폰 ID를 테스트마다 새로 만들고
 * 자기 key만 지운다.
 */
@SpringJUnitConfig(CompensateLuaTest.Config.class)
class CompensateLuaTest {

    private static final long EPOCH = 3;
    private static final long MEMBER_ID = 7;
    private static final String REQUEST_ID = "req-1";
    /** 접수 epoch가 없는 옛 형식 기록. 요청의 epoch는 인수(이벤트 값)다 */
    private static final String RECORD = MEMBER_ID + "|1700000000000|ACCEPTED|1702592000000";
    /** 접수 epoch(3)가 있는 새 형식 기록 */
    private static final String RECORD_WITH_EPOCH = RECORD + "|e" + EPOCH;

    @Autowired
    private RedisCouponCompensationStore store;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        redisTemplate.delete(createdKeys);
    }

    /** epoch 3, 남은 재고 {@code stock}, 회원 7이 접수한 상태(요청 기록 {@code record}) */
    private long accepted(long stock, String record) {
        long couponId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        createdKeys.addAll(List.of(CouponRedisKeys.stock(couponId), CouponRedisKeys.users(couponId),
                CouponRedisKeys.requests(couponId), CouponRedisKeys.issueControl(couponId),
                CouponRedisKeys.soldOut(couponId)));
        redisTemplate.opsForHash().putAll(CouponRedisKeys.issueControl(couponId),
                Map.of("ready", "1", "status", "OPEN", "epoch", String.valueOf(EPOCH)));
        redisTemplate.opsForValue().set(CouponRedisKeys.stock(couponId), String.valueOf(stock));
        redisTemplate.opsForSet().add(CouponRedisKeys.users(couponId), String.valueOf(MEMBER_ID), "8");
        redisTemplate.opsForHash().put(CouponRedisKeys.requests(couponId), REQUEST_ID, record);
        if (stock == 0) {
            redisTemplate.opsForValue().set(CouponRedisKeys.soldOut(couponId), "1");
        }
        return couponId;
    }

    private String stock(long couponId) {
        return redisTemplate.opsForValue().get(CouponRedisKeys.stock(couponId));
    }

    private Object record(long couponId) {
        return redisTemplate.opsForHash().get(CouponRedisKeys.requests(couponId), REQUEST_ID);
    }

    private boolean isUser(long couponId, long memberId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(CouponRedisKeys.users(couponId),
                String.valueOf(memberId)));
    }

    @Test
    void 같은_epoch의_첫_보상은_표식을_남기고_재고를_되돌리며_회원_기록과_매진_표시를_지운다() {
        long couponId = accepted(0, RECORD);

        assertThat(store.compensate(couponId, REQUEST_ID, MEMBER_ID, EPOCH)).isEqualTo(IssueCompensation.RESTORED);

        assertThat(stock(couponId)).isEqualTo("1");
        assertThat(isUser(couponId, MEMBER_ID)).isFalse();
        assertThat(isUser(couponId, 8)).isTrue();
        assertThat(record(couponId)).isEqualTo(RECORD + "|COMPENSATED");
        assertThat(redisTemplate.hasKey(CouponRedisKeys.soldOut(couponId))).isFalse();
    }

    @Test
    void 이미_보상한_요청은_다시_불러도_재고를_바꾸지_않는다() {
        long couponId = accepted(4, RECORD + "|PUBLISHED");

        assertThat(store.compensate(couponId, REQUEST_ID, MEMBER_ID, EPOCH)).isEqualTo(IssueCompensation.RESTORED);
        // 같은 회원이 보상 뒤 다시 접수했어도 이 요청의 재보상은 회원 기록을 지우지 않는다
        redisTemplate.opsForSet().add(CouponRedisKeys.users(couponId), String.valueOf(MEMBER_ID));
        assertThat(store.compensate(couponId, REQUEST_ID, MEMBER_ID, EPOCH))
                .isEqualTo(IssueCompensation.ALREADY_COMPENSATED);

        assertThat(stock(couponId)).isEqualTo("5");
        assertThat(isUser(couponId, MEMBER_ID)).isTrue();
        assertThat(record(couponId)).isEqualTo(RECORD + "|PUBLISHED|COMPENSATED");
    }

    @Test
    void 이전_epoch의_요청은_표식만_남기고_재고와_회원_기록은_그대로다() {
        long couponId = accepted(4, RECORD);

        assertThat(store.compensate(couponId, REQUEST_ID, MEMBER_ID, EPOCH - 1)).isEqualTo(IssueCompensation.MARKED_ONLY);
        assertThat(store.compensate(couponId, REQUEST_ID, MEMBER_ID, EPOCH - 1))
                .isEqualTo(IssueCompensation.ALREADY_COMPENSATED);

        assertThat(stock(couponId)).isEqualTo("4");
        assertThat(isUser(couponId, MEMBER_ID)).isTrue();
        assertThat(record(couponId)).isEqualTo(RECORD + "|COMPENSATED");
    }

    @Test
    void 새_형식_기록은_접수_epoch로_판정하고_epoch_필드를_지킨_채_표식을_붙인다() {
        long sameEpoch = accepted(4, RECORD_WITH_EPOCH + "|PUBLISHED");
        long olderEpoch = accepted(4, RECORD + "|e" + (EPOCH - 1));

        // 인수 epoch가 달라도 접수 기록의 접수 epoch가 현재 epoch면 재고를 되돌린다
        assertThat(store.compensate(sameEpoch, REQUEST_ID, MEMBER_ID, EPOCH - 1)).isEqualTo(IssueCompensation.RESTORED);
        assertThat(store.compensate(sameEpoch, REQUEST_ID, MEMBER_ID, EPOCH))
                .isEqualTo(IssueCompensation.ALREADY_COMPENSATED);
        // 접수 epoch가 이전 epoch면 인수가 현재 epoch여도 표식만 남긴다
        assertThat(store.compensate(olderEpoch, REQUEST_ID, MEMBER_ID, EPOCH)).isEqualTo(IssueCompensation.MARKED_ONLY);

        assertThat(stock(sameEpoch)).isEqualTo("5");
        assertThat(isUser(sameEpoch, MEMBER_ID)).isFalse();
        assertThat(record(sameEpoch)).isEqualTo(RECORD_WITH_EPOCH + "|PUBLISHED|COMPENSATED");
        assertThat(stock(olderEpoch)).isEqualTo("4");
        assertThat(isUser(olderEpoch, MEMBER_ID)).isTrue();
        assertThat(record(olderEpoch)).isEqualTo(RECORD + "|e" + (EPOCH - 1) + "|COMPENSATED");
    }

    @Test
    void issue_control이_없으면_epoch를_확인할_수_없어_표식만_남긴다() {
        long couponId = accepted(4, RECORD);
        redisTemplate.delete(CouponRedisKeys.issueControl(couponId));

        assertThat(store.compensate(couponId, REQUEST_ID, MEMBER_ID, EPOCH)).isEqualTo(IssueCompensation.MARKED_ONLY);

        assertThat(stock(couponId)).isEqualTo("4");
        assertThat(isUser(couponId, MEMBER_ID)).isTrue();
    }

    @Test
    void 접수_기록이_없거나_회원이_다르면_아무것도_바꾸지_않는다() {
        long couponId = accepted(4, RECORD);

        assertThat(store.compensate(couponId, "req-unknown", MEMBER_ID, EPOCH)).isEqualTo(IssueCompensation.NO_RECORD);
        assertThat(store.compensate(couponId, REQUEST_ID, 8, EPOCH)).isEqualTo(IssueCompensation.MEMBER_MISMATCH);

        assertThat(stock(couponId)).isEqualTo("4");
        assertThat(isUser(couponId, MEMBER_ID)).isTrue();
        assertThat(record(couponId)).isEqualTo(RECORD);
    }

    @Test
    void 접수_기록이나_재고_형식이_깨졌으면_아무것도_쓰지_않고_실패한다() {
        long brokenRecord = accepted(4, "garbage");
        long brokenEpoch = accepted(4, RECORD + "|e3x");
        long misplacedEpoch = accepted(4, RECORD + "|PUBLISHED|e3");
        long brokenStock = accepted(4, RECORD);
        redisTemplate.opsForValue().set(CouponRedisKeys.stock(brokenStock), "x");
        long wrongType = accepted(4, RECORD);
        redisTemplate.delete(CouponRedisKeys.users(wrongType));
        redisTemplate.opsForValue().set(CouponRedisKeys.users(wrongType), "not-a-set");

        assertThatThrownBy(() -> store.compensate(brokenRecord, REQUEST_ID, MEMBER_ID, EPOCH))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThatThrownBy(() -> store.compensate(brokenEpoch, REQUEST_ID, MEMBER_ID, EPOCH))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThatThrownBy(() -> store.compensate(misplacedEpoch, REQUEST_ID, MEMBER_ID, EPOCH))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThatThrownBy(() -> store.compensate(brokenStock, REQUEST_ID, MEMBER_ID, EPOCH))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);
        assertThatThrownBy(() -> store.compensate(wrongType, REQUEST_ID, MEMBER_ID, EPOCH))
                .isInstanceOf(CouponIssueStoreUnavailableException.class);

        // 형식 검사는 쓰기 전이라 표식도 남지 않는다
        assertThat(record(brokenStock)).isEqualTo(RECORD);
        assertThat(record(wrongType)).isEqualTo(RECORD);
        assertThat(stock(wrongType)).isEqualTo("4");
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RedisTestcontainersConfig.class, RedisCouponCompensationStore.class})
    @ImportAutoConfiguration(DataRedisAutoConfiguration.class)
    static class Config {
    }
}
