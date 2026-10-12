package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** 강의 정보(제목·소개·가격·썸네일) 수정. 판매자 본인, 삭제 전이면 어느 상태에서든 */
@Service
@RequiredArgsConstructor
public class LectureUpdateInfoUseCase {

    private final LectureSupport lectureSupport;

    public void updateInfo(Long sellerUserId, Long lectureId, String title, String description, long priceHp,
                           Long thumbnailFileId) {
        lectureSupport.findLecture(lectureId).updateInfo(sellerUserId, title, description, priceHp, thumbnailFileId,
                LocalDateTime.now());
    }
}
