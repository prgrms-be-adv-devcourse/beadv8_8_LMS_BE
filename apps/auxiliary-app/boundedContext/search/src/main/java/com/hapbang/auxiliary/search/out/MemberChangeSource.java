package com.hapbang.auxiliary.search.out;

import java.time.LocalDateTime;

import com.hapbang.shared.member.dto.MemberChangePageDto;

/**
 * 검색이 회원 앱에서 읽는 회원 변경분(#772, 내부 API #769). 테스트는 가짜로 바꿔 끼운다.
 * 실패는 {@code MemberApiCallException}(shared)으로 나간다.
 */
public interface MemberChangeSource {

    /**
     * (updatedAt, 회원 ID)가 커서보다 뒤인 회원 한 페이지. 모든 역할·상태를 준다.
     *
     * @param afterUpdatedAt 커서 시각(회원 앱 시간대, 오프셋 없음)
     * @param afterId        커서 회원 ID
     */
    MemberChangePageDto changes(LocalDateTime afterUpdatedAt, long afterId, int size);

    /**
     * 다음 커서가 빠진 응답을 거절한다(#769 계약: 항목이 있으면 커서를 주고, 빈 페이지는 hasNext=false). 받아들이면 같은 커서로 끝없이 다시 부르거나
     * 커서를 저장하지 못한다. 인플루언서 증분 보정과 판매자 사본 보정(#884)이 함께 쓴다.
     *
     * @throws IllegalStateException 다음 커서가 빠진 경우
     */
    static void requireNextCursor(MemberChangePageDto page) {
        boolean noCursor = page.nextAfterUpdatedAt() == null || page.nextAfterId() == null;
        boolean partialCursor = (page.nextAfterUpdatedAt() == null) != (page.nextAfterId() == null);
        if (partialCursor || (noCursor && (page.hasNext() || !page.items().isEmpty()))) {
            throw new IllegalStateException("회원 변경분 응답에 다음 커서가 없습니다 items=" + page.items().size()
                    + ", hasNext=" + page.hasNext() + ", nextAfterUpdatedAt=" + page.nextAfterUpdatedAt()
                    + ", nextAfterId=" + page.nextAfterId());
        }
    }
}
