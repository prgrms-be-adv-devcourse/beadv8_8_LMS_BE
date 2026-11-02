package com.hapbang.lecture.app;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.FileUploadResponse;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.out.FileResourceRepository;

import lombok.RequiredArgsConstructor;

/** 파일 정보 등록. 메타데이터를 PENDING으로 만든다. 본문은 서버가 받아 S3에 저장한다(#106, D22) */
@Service
@RequiredArgsConstructor
public class FileUploadUseCase {

    private final FileResourceRepository fileResourceRepository;

    public FileUploadResponse register(Long uploaderUserId, FileType fileType, String originalFilename,
                                       String contentType, long fileSize) {
        FileResource file = fileResourceRepository.save(FileResource.create(uploaderUserId, fileType,
                originalFilename, contentType, fileSize, LocalDateTime.now()));
        return new FileUploadResponse(file.getId());
    }
}
