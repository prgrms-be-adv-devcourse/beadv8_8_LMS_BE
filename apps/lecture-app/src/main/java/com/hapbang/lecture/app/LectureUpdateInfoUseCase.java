package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 강의 정보(제목·소개·가격·썸네일) 수정. 판매자 본인, 삭제 전이면 어느 상태에서든.
 * 강의를 먼저 검사하고 썸네일은 나중에 검사한다. 썸네일이 맞지 않으면 Facade 트랜잭션이 롤백되어 수정도 취소된다 (D21)
 */
@Service
@RequiredArgsConstructor
public class LectureUpdateInfoUseCase {

    private final LectureSupport lectureSupport;
    private final LectureFileSupport lectureFileSupport;

    public void updateInfo(Long sellerUserId, Long lectureId, String title, String description, long priceHp,
                           Long thumbnailFileId) {
        lectureSupport.findLecture(lectureId).updateInfo(sellerUserId, title, description, priceHp, thumbnailFileId,
                LocalDateTime.now());
        lectureFileSupport.validateThumbnail(sellerUserId, thumbnailFileId);
    }
}
