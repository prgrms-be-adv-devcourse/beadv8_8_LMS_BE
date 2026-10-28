package com.hapbang.lecture.app;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.hapbang.lecture.global.storage.S3FileStorage;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.out.FileResourceRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * 강의 썸네일 조회 URL(Presigned GET). 응답할 때마다 발급한다 (D28).
 * <p>
 * 썸네일이 없거나, 업로드가 끝나지 않았거나, 삭제된 파일이면 URL이 없다(null).
 * 서명 중 AWS SDK 예외가 나면(서버가 자격 증명을 가져오지 못함 등) 응답 전체를 실패시키지 않고 남은 썸네일도 서명하지 않는다.
 * 그 밖의 예외는 버그이므로 그대로 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class LectureThumbnailSupport {

    /** D23 */
    private static final Duration URL_EXPIRY = Duration.ofHours(1);

    private final FileResourceRepository fileResourceRepository;
    private final S3FileStorage s3FileStorage;

    /** 썸네일이 없거나 볼 수 없으면 null */
    String urlOf(Long thumbnailFileId) {
        if (thumbnailFileId == null) {
            return null;
        }
        return urlsOf(List.of(thumbnailFileId)).get(thumbnailFileId);
    }

    /** 썸네일 파일 ID → 조회 URL. 파일을 한 번에 읽는다. 볼 수 없는 파일은 빠진다 */
    Map<Long, String> urlsOf(Collection<Long> thumbnailFileIds) {
        Map<Long, String> urls = new HashMap<>();
        for (FileResource file : fileResourceRepository.findAllById(
                thumbnailFileIds.stream().filter(Objects::nonNull).toList())) {
            if (!file.isViewable()) {
                continue;
            }
            try {
                urls.put(file.getId(), s3FileStorage.presignDownload(file.getObjectKey(), URL_EXPIRY).url());
            } catch (SdkException e) {
                // URL에는 서명이 들어 있으므로 남기지 않는다
                log.error("썸네일 조회 URL을 발급하지 못해 남은 썸네일을 비워 응답합니다: fileId={}", file.getId(), e);
                break;
            }
        }
        return urls;
    }
}
