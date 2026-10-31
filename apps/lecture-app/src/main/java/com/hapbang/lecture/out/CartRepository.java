package com.hapbang.lecture.out;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.lecture.domain.Cart;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUserId(Long userId);

    /**
     * 장바구니가 없을 때만 만든다. 같은 회원의 첫 담기가 동시에 와도 유일 제약 위반 없이 하나만 남는다.
     * 다른 트랜잭션이 먼저 넣고 아직 커밋하지 않았으면 그 결과를 기다린 뒤 무시한다.
     */
    @Modifying
    @Query(value = "insert into lectures.cart (user_id, created_at, updated_at) values (:userId, :now, :now) "
            + "on conflict (user_id) do nothing", nativeQuery = true)
    void insertIfAbsent(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
