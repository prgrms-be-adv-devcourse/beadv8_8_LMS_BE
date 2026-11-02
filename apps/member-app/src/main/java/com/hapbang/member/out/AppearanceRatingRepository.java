package com.hapbang.member.out;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hapbang.member.domain.AppearanceRating;

/** 회원별 출연 평점 합계 저장소 (#555) */
public interface AppearanceRatingRepository extends JpaRepository<AppearanceRating, Long> {

    /**
     * 회원의 합계에 더한다. 행이 없으면 만들고, 있으면 DB에서 원자적으로 더해 같은 회원의 리뷰가 동시에 와도 값이 맞는다.
     * 호스트로 받은 리뷰면 호스트 몫에, 게스트로 받은 리뷰면 게스트 몫에 (별점, 1)을 넘기고 다른 쪽은 (0, 0)이다
     */
    @Modifying
    @Query(value = """
            insert into members.appearance_rating
                (user_id, host_rating_sum, host_rating_count, guest_rating_sum, guest_rating_count, created_at, updated_at)
            values (:userId, :hostSum, :hostCount, :guestSum, :guestCount, :now, :now)
            on conflict (user_id) do update set
                host_rating_sum = appearance_rating.host_rating_sum + excluded.host_rating_sum,
                host_rating_count = appearance_rating.host_rating_count + excluded.host_rating_count,
                guest_rating_sum = appearance_rating.guest_rating_sum + excluded.guest_rating_sum,
                guest_rating_count = appearance_rating.guest_rating_count + excluded.guest_rating_count,
                updated_at = excluded.updated_at
            """, nativeQuery = true)
    void add(@Param("userId") long userId,
             @Param("hostSum") long hostSum,
             @Param("hostCount") long hostCount,
             @Param("guestSum") long guestSum,
             @Param("guestCount") long guestCount,
             @Param("now") LocalDateTime now);
}
