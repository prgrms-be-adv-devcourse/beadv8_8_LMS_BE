package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;
import com.hapbang.lecture.out.CartRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.testsupport.IntegrationTest;

/** 같은 강의를 동시에 담는 경쟁은 HTTP 테스트로는 매번 재현되지 않으므로 트랜잭션 순서를 직접 만들어 확인한다. */
@IntegrationTest
class CartAddItemUseCaseTest {

    private static final Long BUYER_ID = OTHER_USER_ID;

    @Autowired
    private CartAddItemUseCase cartAddItemUseCase;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private LectureRepository lectureRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 장바구니를_읽은_뒤_다른_요청이_같은_강의를_먼저_담으면_ALREADY_IN_CART로_거부한다() {
        Long lectureId = lectureRepository.save(onSaleLecture()).getId();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> cartRepository.insertIfAbsent(BUYER_ID, NOW));
        TransactionTemplate otherRequest = new TransactionTemplate(transactionManager);
        otherRequest.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            // 이 요청은 아직 아무것도 담기지 않은 장바구니를 읽어 둔 상태다
            cartRepository.findByUserId(BUYER_ID).orElseThrow().getItems();
            otherRequest.executeWithoutResult(other -> cartAddItemUseCase.addItem(BUYER_ID, lectureId));

            cartAddItemUseCase.addItem(BUYER_ID, lectureId);
        }))
                .isInstanceOf(LectureException.class)
                .extracting("errorCode").isEqualTo(LectureErrorCode.ALREADY_IN_CART);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from lectures.cart_item where deleted_at is null", Long.class)).isEqualTo(1);
    }
}
