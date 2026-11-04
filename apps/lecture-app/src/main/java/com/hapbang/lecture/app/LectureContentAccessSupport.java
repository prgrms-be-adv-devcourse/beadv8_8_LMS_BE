package com.hapbang.lecture.app;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.global.storage.PresignedUrl;
import com.hapbang.lecture.global.storage.S3FileStorage;
import com.hapbang.lecture.app.dto.ContentAccessResponse;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.LectureContent;
import com.hapbang.lecture.domain.LectureUser;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 강의 콘텐츠(영상·자료)를 볼 수 있는 Presigned GET URL을 발급한다 (설계 8.1, D26, D38).
 * 강의 없음 → 볼 수 있는 회원인지 → 콘텐츠가 이 강의에 있는지 순서로 검사한다. 권한 없는 회원에게 콘텐츠 ID가 있는지 드러내지 않는다.
 * 관리자 여부와 시청 가능한 항목은 판매자가 아닐 때만 읽는다. 관리자는 회원 복제본({@link LectureUser})으로 판정하고,
 * 시청 가능한 항목은 구매자 ID와 강의 ID로 찾는다(구매자·강의당 하나뿐).
 */
@Service
@RequiredArgsConstructor
public class LectureContentAccessSupport {

    /** 시청 URL 만료 (D23) */
    static final Duration ACCESS_URL_EXPIRY = Duration.ofHours(1);

    private final LectureSupport lectureSupport;
    private final LectureFileSupport lectureFileSupport;
    private final LectureUserRepository lectureUserRepository;
    private final LectureOrderSupport lectureOrderSupport;
    private final S3FileStorage s3FileStorage;

    public ContentAccessResponse issue(Long userId, Long lectureId, Long contentId) {
        Lecture lecture = lectureSupport.findLecture(lectureId);
        boolean seller = lecture.isSeller(userId);
        boolean admin = !seller && isAdmin(userId);
        OrderItem heldItem = seller || admin ? null
                : lectureOrderSupport.findViewableItem(userId, lectureId).orElse(null);
        lecture.validateContentViewer(userId, admin, heldItem);
        LectureContent content = lecture.findContent(contentId);
        FileResource file = lectureFileSupport.findFile(content.getFileId());
        return ContentAccessResponse.from(presign(content, file));
    }

    /** 회원 복제본으로 판정한다. 복제본이 아직 없으면 관리자로 보지 않는다 */
    private boolean isAdmin(Long userId) {
        return lectureUserRepository.findById(userId).map(LectureUser::isAdmin).orElse(false);
    }

    /** 영상은 브라우저에서 재생하고, 자료는 원본 파일명으로 내려받는다 (D27) */
    private PresignedUrl presign(LectureContent content, FileResource file) {
        return switch (content.getContentType()) {
            case VIDEO -> s3FileStorage.presignDownload(file.getObjectKey(), ACCESS_URL_EXPIRY);
            case DOCUMENT -> s3FileStorage.presignDownload(file.getObjectKey(), ACCESS_URL_EXPIRY,
                    file.getOriginalFilename());
        };
    }
}
