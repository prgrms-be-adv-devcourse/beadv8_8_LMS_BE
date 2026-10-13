package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.global.storage.S3FileStorage;
import com.hapbang.lecture.app.dto.FileUploadResponse;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.out.FileResourceRepository;

import lombok.RequiredArgsConstructor;

/** 업로드 URL 발급. 파일 메타데이터를 PENDING으로 만들고 Presigned PUT URL을 준다(서명만, S3 호출 없음) */
@Service
@RequiredArgsConstructor
public class FileUploadUseCase {

    private final FileResourceRepository fileResourceRepository;
    private final S3FileStorage s3FileStorage;

    public FileUploadResponse createUploadUrl(Long uploaderUserId, FileType fileType, String originalFilename,
                                              String contentType, long fileSize) {
        FileResource file = fileResourceRepository.save(FileResource.create(uploaderUserId, fileType,
                originalFilename, contentType, fileSize, LocalDateTime.now()));
        return FileUploadResponse.of(file.getId(),
                s3FileStorage.presignUpload(file.getObjectKey(), file.getContentType()));
    }
}
