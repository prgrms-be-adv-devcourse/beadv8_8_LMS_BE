package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.global.storage.S3FileStorage;
import com.hapbang.global.storage.StoredFile;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.LectureErrorCode;
import com.hapbang.lecture.domain.LectureException;

import lombok.RequiredArgsConstructor;

/**
 * 업로드 완료. 업로더 본인만, S3 HEAD로 객체가 있는지와 크기를 확인한 뒤 UPLOADED로 바꾼다.
 * 이미 완료한 파일은 S3를 다시 확인하지 않고 그대로 둔다(D20).
 */
@Service
@RequiredArgsConstructor
public class FileCompleteUseCase {

    private final LectureFileSupport lectureFileSupport;
    private final S3FileStorage s3FileStorage;

    public void complete(Long userId, Long fileId) {
        FileResource file = lectureFileSupport.findFile(fileId);
        file.validateUploader(userId);
        if (file.isUploaded()) {
            return;
        }
        StoredFile stored = s3FileStorage.find(file.getObjectKey())
                .orElseThrow(() -> new LectureException(LectureErrorCode.FILE_NOT_UPLOADED));
        file.markUploaded(stored.size(), LocalDateTime.now());
    }
}
