package com.hapbang.auxiliary.search.common.out;

import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;

/**
 * ES가 회원 문서 쓰기를 <b>그 회원의 값 때문에</b> 거절했다(400 {@code document_parsing_exception}: 값이 칸의 규칙에 맞지 않음, #772 코드 리뷰).
 * 같은 회원 정보로 다시 써도 같은 이유로 거절된다. 모든 회원에게 나는 배포 실수(script 오류, mapping에 없는 칸), ES 장애(연결 실패, 5xx, 429),
 * 겹친 쓰기(409)는 여기에 들지 않는다(PR #833 리뷰 Q1, {@code InfluencerDocumentStore#REJECTED_TYPES}).
 * <p>
 * {@link IllegalStateException}의 하위라 이벤트 소비는 지금처럼 다시 시도한 뒤 DLT로 보낸다. 증분 보정만 이 예외를 따로 잡아 그 회원을 건너뛴다
 * (멈추면 커서가 그 페이지를 영영 넘어가지 못한다).
 */
public class DocumentRejectedException extends IllegalStateException {

    public DocumentRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
