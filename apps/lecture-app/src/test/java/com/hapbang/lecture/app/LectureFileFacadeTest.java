package com.hapbang.lecture.app;

import static com.hapbang.lecture.fixture.LectureFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.hapbang.lecture.global.storage.S3FileStorage;
import com.hapbang.lecture.domain.FileResource;
import com.hapbang.lecture.domain.FileStatus;
import com.hapbang.lecture.domain.FileType;
import com.hapbang.lecture.out.FileResourceRepository;
import com.hapbang.testsupport.IntegrationTest;
import com.hapbang.testsupport.S3MockTestcontainersConfig;

/** HTTP로는 본문 스트림을 바꿀 수 없으므로, 끊긴 본문과 트랜잭션 경계는 Facade를 직접 불러 확인한다 (D25). */
@IntegrationTest
@Import(S3MockTestcontainersConfig.class)
class LectureFileFacadeTest {

    private static final int SIZE = 1_024;

    @Autowired
    private LectureFileFacade lectureFileFacade;

    @Autowired
    private FileResourceRepository fileResourceRepository;

    @Autowired
    private S3FileStorage s3FileStorage;

    @Test
    void 본문을_읽다_끊기면_PENDING으로_남고_처음부터_다시_올릴_수_있다() {
        FileResource file = fileResourceRepository.save(pendingFile(SELLER_ID, FileType.LECTURE_VIDEO));

        assertThatThrownBy(() -> lectureFileFacade.upload(SELLER_ID, file.getId(), "video/mp4", SIZE,
                abortedAfter(100)))
                .hasRootCauseMessage("client aborted");
        assertThat(fileResourceRepository.findById(file.getId()).orElseThrow().getStatus())
                .isEqualTo(FileStatus.PENDING);
        assertThat(s3FileStorage.find(file.getObjectKey())).isEmpty();

        lectureFileFacade.upload(SELLER_ID, file.getId(), "video/mp4", SIZE, new ByteArrayInputStream(new byte[SIZE]));

        assertThat(fileResourceRepository.findById(file.getId()).orElseThrow().getStatus())
                .isEqualTo(FileStatus.UPLOADED);
    }

    @Test
    void 본문은_DB_트랜잭션_밖에서_읽는다() {
        FileResource file = fileResourceRepository.save(pendingFile(SELLER_ID, FileType.LECTURE_VIDEO));
        List<Boolean> transactionActiveOnRead = new ArrayList<>();
        InputStream body = new ByteArrayInputStream(new byte[SIZE]) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                transactionActiveOnRead.add(TransactionSynchronizationManager.isActualTransactionActive());
                return super.read(b, off, len);
            }
        };

        lectureFileFacade.upload(SELLER_ID, file.getId(), "video/mp4", SIZE, body);

        assertThat(transactionActiveOnRead).isNotEmpty().containsOnly(false);
    }

    /** 클라이언트가 끊긴 요청 본문(Tomcat ClientAbortException은 IOException)처럼 일정 바이트 뒤 계속 실패한다 */
    private static InputStream abortedAfter(int bytes) {
        return new InputStream() {
            private int sent;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0];
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (sent >= bytes) {
                    throw new IOException("client aborted");
                }
                int count = Math.min(len, bytes - sent);
                sent += count;
                return count;
            }
        };
    }
}
