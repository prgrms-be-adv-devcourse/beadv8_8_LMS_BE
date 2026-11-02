package com.hapbang.lecture.app;

import java.io.InputStream;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hapbang.lecture.global.storage.S3FileStorage;
import com.hapbang.lecture.domain.FileResource;

/**
 * 파일 본문 업로드 (D24). 업로더 본인이 PENDING 파일에 등록한 크기·형식 그대로 올리면 S3에 저장하고 UPLOADED로 바꾼다.
 * <p>
 * 본문은 DB 트랜잭션 밖에서 S3로 스트리밍한다(D25). 영상은 업로드에 수십 분이 걸릴 수 있어, 그동안 DB 연결을 잡지 않도록
 * 검사와 상태 변경만 각각 짧은 트랜잭션으로 묶는다. 저장에 실패하거나 연결이 끊기면 PENDING으로 남아 다시 올릴 수 있다.
 * 같은 파일을 동시에 올리면 나중에 끝난 쪽이 S3를 덮어쓰고, 상태는 한 번만 UPLOADED가 된다.
 */
@Service
public class FileContentUploadUseCase {

    private final LectureFileSupport lectureFileSupport;
    private final S3FileStorage s3FileStorage;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;

    public FileContentUploadUseCase(LectureFileSupport lectureFileSupport, S3FileStorage s3FileStorage,
                                    PlatformTransactionManager transactionManager) {
        this.lectureFileSupport = lectureFileSupport;
        this.s3FileStorage = s3FileStorage;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    public void upload(Long userId, Long fileId, String contentType, long contentLength, InputStream body) {
        FileResource file = readOnlyTransaction.execute(status -> {
            FileResource found = lectureFileSupport.findFile(fileId);
            found.validateUploadable(userId, contentType, contentLength);
            return found;
        });

        // S3에는 등록한 Content-Type으로 저장한다(요청 값은 대소문자·파라미터만 다를 수 있다)
        s3FileStorage.upload(file.getObjectKey(), file.getContentType(), contentLength, body);

        transaction.executeWithoutResult(status ->
                lectureFileSupport.findFile(fileId).markUploaded(LocalDateTime.now()));
    }
}
