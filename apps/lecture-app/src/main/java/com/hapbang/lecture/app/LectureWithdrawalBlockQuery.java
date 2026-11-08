package com.hapbang.lecture.app;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.hapbang.lecture.config.LectureConfig;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;
import com.hapbang.lecture.out.WithdrawalBlockTotal;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

/**
 * 회원 탈퇴가 막힌 강의 쪽 이유 (#625, D75). 회원은 구매자로도 판매자로도 관련될 수 있어 양쪽을 다 본다.
 * 읽기만 하고, 주문 취소·환불·강의 삭제를 대신 하지 않는다
 */
@Component
public class LectureWithdrawalBlockQuery {

    private final LectureOrderRepository lectureOrderRepository;
    private final LectureRepository lectureRepository;
    private final Clock clock;

    public LectureWithdrawalBlockQuery(LectureOrderRepository lectureOrderRepository,
                                       LectureRepository lectureRepository,
                                       @Qualifier(LectureConfig.CLOCK) Clock clock) {
        this.lectureOrderRepository = lectureOrderRepository;
        this.lectureRepository = lectureRepository;
        this.clock = clock;
    }

    /** 그 회원의 탈퇴가 막힌 이유. 주문·강의가 없는 회원(복제 회원이 없는 회원 포함)은 빈 목록이다 */
    public WithdrawalBlocksDto getBlocks(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<WithdrawalBlockTotal> totals = new ArrayList<>(
                lectureOrderRepository.summarizeBuyerWithdrawalBlocks(userId, now));
        totals.add(lectureOrderRepository.summarizeSellerWithdrawalBlocks(userId, now));
        return LectureWithdrawalBlocks.of(totals, lectureRepository.countWithdrawalBlockingLectures(userId), now);
    }
}
