package com.hapbang.shared.lecture.dto;

import java.util.List;
import java.util.Objects;

/**
 * AI 추천용 강의 한 개 (#712, D82). {@code LectureChangedEvent}와 강의 동기화 내부 API가 같은 값을 싣는다.
 * 썸네일·검수 정보는 싣지 않는다.
 *
 * @param id           강의 ID
 * @param sellerUserId 판매자 회원 ID. 본인 강의를 추천에서 빼는 데 쓴다
 * @param title        제목
 * @param description  소개. 없으면 {@code null}
 * @param priceHp      가격(HP). 강의는 늘 채운다. 이 필드가 생기기 전(#885)에 발행된 이벤트·응답을 읽으면 {@code null}이고,
 *                     받는 쪽은 가격을 모르는 것으로 다룬다(같은 eventVersion 안의 선택 필드 추가)
 * @param onSale       추천해도 되는 강의인지. 판단 기준은 강의 모듈이 정한다 (지금은 판매 중이고 삭제되지 않음)
 * @param deleted      삭제(Soft Delete)되었는지
 * @param contents     삭제되지 않은 콘텐츠, 순서대로
 * @param version      강의 원본의 버전. 실린 값이 바뀌면 반드시 오른다(콘텐츠만 바뀌어도)
 */
public record LectureSyncDto(
        Long id,
        Long sellerUserId,
        String title,
        String description,
        Long priceHp,
        boolean onSale,
        boolean deleted,
        List<LectureContentSyncDto> contents,
        long version
) {

    public LectureSyncDto {
        Objects.requireNonNull(id, "id는 필수입니다.");
        Objects.requireNonNull(sellerUserId, "sellerUserId는 필수입니다.");
        Objects.requireNonNull(title, "title은 필수입니다.");
        contents = List.copyOf(Objects.requireNonNull(contents, "contents는 필수입니다."));
    }

    /**
     * 가격 없이 만든다({@code priceHp = null}). 가격을 더하기 전 모양으로 DTO를 만드는 다른 모듈 테스트가 그대로 컴파일되게 잠시 둔다.
     *
     * @deprecated #909에서 이 생성자를 쓰는 테스트를 고치고 지운다. 새 코드는 정식 생성자를 쓴다
     */
    @Deprecated(forRemoval = true)
    public LectureSyncDto(Long id, Long sellerUserId, String title, String description, boolean onSale,
                          boolean deleted, List<LectureContentSyncDto> contents, long version) {
        this(id, sellerUserId, title, description, null, onSale, deleted, contents, version);
    }
}
