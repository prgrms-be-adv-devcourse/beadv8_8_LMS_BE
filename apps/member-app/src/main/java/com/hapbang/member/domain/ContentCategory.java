package com.hapbang.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 콘텐츠 분야 (#711). 인플루언서가 프로필에 0~5개 고르는 주제다. 다른 모듈에는 코드만 넘기고(UserDto), 이름은 분야 목록 API로 준다.
 * <p>
 * 행은 마이그레이션 SQL로만 넣고 끈다(첫 버전은 추가·끄기 API 없음). 그래서 생성 팩토리가 없다.
 * active를 끄면 분야 목록 API·검색 필터 후보에서만 빠지고, 이미 고른 회원의 값은 그대로 둔다(Q50)
 */
@Entity
@Table(name = "content_category", schema = "members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentCategory {

    /** 영문 UPPER_SNAKE 코드. 한 번 정하면 바꾸지 않는다(다른 모듈이 저장한다) */
    @Id
    @Column(length = 50)
    private String code;

    /** 한글 이름. 바꿀 수 있다 */
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /** 새로 고를 수 있는지 */
    @Column(nullable = false)
    private boolean active;
}
