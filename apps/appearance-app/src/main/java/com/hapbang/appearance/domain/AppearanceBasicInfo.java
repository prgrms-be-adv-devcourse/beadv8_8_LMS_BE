package com.hapbang.appearance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 출연 요청서·모집글의 기본 정보(제목·호스트 채널 URL·콘텐츠 설명). 등록 후에도 수정할 수 있는 값만 묶은 값 객체다.
 * <ul>
 *     <li>형식 제한({@link AppearanceContentRules})은 {@link #of}와 {@link #withChanges}에서 확인한다.</li>
 *     <li>값을 바꾸지 않고 새 객체를 돌려준다. 누가·언제 수정할 수 있는지는 각 엔티티가 확인한다.</li>
 *     <li>JPA는 기본 생성자로 읽으므로 DB에서 읽을 때는 검증하지 않는다. 형식 제한이 나중에 바뀌어도
 *     이전 데이터를 읽을 수 있게 하기 위해서다 (record로 만들지 않은 이유).</li>
 * </ul>
 * 컬럼 이름은 요청서·모집글 테이블의 {@code title}, {@code host_channel_url}, {@code content}와 같다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppearanceBasicInfo {

    /** 제목. 공백 불가, 100자 이하 */
    private String title;

    /** 호스트 채널 URL. http(s)://로 시작, 255자 이하 */
    private String hostChannelUrl;

    /** 콘텐츠 설명. 공백 불가, 5,000자 이하 */
    @Column(columnDefinition = "text")
    private String content;

    private AppearanceBasicInfo(String title, String hostChannelUrl, String content) {
        this.title = title;
        this.hostChannelUrl = hostChannelUrl;
        this.content = content;
    }

    /**
     * @throws AppearanceException 형식 오류({@code INVALID_REQUEST})
     */
    public static AppearanceBasicInfo of(String title, String hostChannelUrl, String content) {
        AppearanceContentRules.validateTitle(title);
        AppearanceContentRules.validateHostChannelUrl(hostChannelUrl);
        AppearanceContentRules.validateContent(content);
        return new AppearanceBasicInfo(title, hostChannelUrl, content);
    }

    /**
     * 보낸 값만 바꾼 새 기본 정보를 만든다. null인 값은 지금 값을 그대로 쓴다.
     * 값을 모두 확인한 뒤에 만들므로, 하나라도 형식이 틀리면 아무것도 바뀌지 않는다.
     *
     * @throws AppearanceException 형식 오류({@code INVALID_REQUEST})
     */
    public AppearanceBasicInfo withChanges(String newTitle, String newContent, String newHostChannelUrl) {
        String changedTitle = title;
        String changedContent = content;
        String changedHostChannelUrl = hostChannelUrl;
        if (newTitle != null) {
            changedTitle = newTitle;
        }
        if (newContent != null) {
            changedContent = newContent;
        }
        if (newHostChannelUrl != null) {
            changedHostChannelUrl = newHostChannelUrl;
        }
        return of(changedTitle, changedHostChannelUrl, changedContent);
    }
}
