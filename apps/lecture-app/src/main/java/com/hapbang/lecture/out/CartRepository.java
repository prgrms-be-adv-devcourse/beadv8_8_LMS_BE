package com.hapbang.lecture.out;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.Cart;
import com.hapbang.lecture.domain.CartItem;

import jakarta.persistence.LockModeType;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUserId(Long userId);

    /**
     * 장바구니를 행 잠금으로 읽는다. 장바구니에서 빼는 변경(직접 빼기, 결제 뒤 자동 빼기)을 한 줄로 세워, 같은 항목을 동시에 빼도
     * 뒤 요청이 앞 요청의 결과를 보고 할 일이 없게 한다. 장바구니 항목의 버전(#712) 충돌을 막는다 (D82). 항목은 잠근 뒤에 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.userId = :userId")
    Optional<Cart> findForUpdateByUserId(@Param("userId") Long userId);

    /**
     * 장바구니 항목 동기화 내부 API(#712): {@code afterId}보다 큰 항목을 ID 오름차순으로 {@code limit}개. 뺀 항목도 싣는다
     * ({@code Cart.items}의 삭제 필터를 거치지 않는다). 회원 ID를 실으므로 장바구니를 함께 읽는다
     */
    @Query("select ci from CartItem ci join fetch ci.cart where ci.id > :afterId order by ci.id")
    List<CartItem> findSyncCartItems(@Param("afterId") long afterId, Limit limit);

    /**
     * 장바구니가 없을 때만 만든다. 같은 회원의 첫 담기가 동시에 와도 유일 제약 위반 없이 하나만 남는다.
     * 다른 트랜잭션이 먼저 넣고 아직 커밋하지 않았으면 그 결과를 기다린 뒤 무시한다.
     */
    @Modifying
    @Query(value = "insert into lectures.cart (user_id, created_at, updated_at) values (:userId, :now, :now) "
            + "on conflict (user_id) do nothing", nativeQuery = true)
    void insertIfAbsent(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
