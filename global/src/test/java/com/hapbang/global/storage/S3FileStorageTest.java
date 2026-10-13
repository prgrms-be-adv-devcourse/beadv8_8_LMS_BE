package com.hapbang.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.hapbang.testsupport.S3MockTestcontainersConfig;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

// 로컬 개발과 같은 S3Mock 컨테이너로 SDK 호출까지 검증한다. S3Mock은 서명·만료를 검사하지 않으므로 서명 내용은 URL로 확인한다.
@SpringBootTest(classes = S3StorageConfig.class,
        properties = "hapbang.storage.s3.bucket=" + S3MockTestcontainersConfig.BUCKET)
@Import(S3MockTestcontainersConfig.class)
class S3FileStorageTest {

    private static final String BUCKET = S3MockTestcontainersConfig.BUCKET;
    private static final String PREFIX = S3MockTestcontainersConfig.KEY_PREFIX;

    @Autowired
    S3FileStorage storage;

    @Autowired
    S3Client s3Client;

    HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("업로드한 파일을 HEAD로 확인하면 크기와 Content-Type이 나온다")
    void upload_thenFind() {
        String key = newKey();

        storage.upload(key, "image/png", 5, new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5}));

        assertThat(storage.find(key)).contains(new StoredFile(5, "image/png"));
    }

    @Test
    @DisplayName("업로드는 key 앞에 환경 prefix를 붙여 저장한다")
    void upload_addsKeyPrefix() {
        String key = newKey();

        storage.upload(key, "text/plain", 5, new ByteArrayInputStream("hello".getBytes()));

        assertThat(s3Client.headObject(request -> request.bucket(BUCKET).key(PREFIX + "/" + key)).contentLength())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("본문이 선언한 크기보다 짧으면 실패하고 객체가 남지 않는다")
    void upload_truncatedBody() {
        String key = newKey();

        assertThatThrownBy(() -> storage.upload(key, "video/mp4", 10, new ByteArrayInputStream(new byte[3])))
                .hasMessageContaining("fewer bytes");
        assertThat(storage.find(key)).isEmpty();
    }

    @Test
    @DisplayName("본문을 읽다 끊기면 재시도하지 않고 원래 오류로 실패하며 객체가 남지 않는다")
    void upload_clientAborted() {
        String key = newKey();
        // 클라이언트가 끊긴 요청 본문(Tomcat ClientAbortException은 IOException)처럼 되감을 수 없고 중간에 실패한다
        InputStream aborted = new InputStream() {
            private boolean sent;

            @Override
            public int read() throws IOException {
                return read(new byte[1], 0, 1);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (sent) {
                    throw new IOException("client aborted");
                }
                sent = true;
                b[off] = 1;
                return 1;
            }
        };

        assertThatThrownBy(() -> storage.upload(key, "video/mp4", 10, aborted))
                .hasRootCauseMessage("client aborted");
        assertThat(storage.find(key)).isEmpty();
    }

    @Test
    @DisplayName("Content-Type이 없거나 크기가 음수거나 본문이 없으면 업로드하지 않는다")
    void upload_rejectsInvalidArguments() {
        InputStream body = new ByteArrayInputStream(new byte[1]);

        assertThatThrownBy(() -> storage.upload(newKey(), " ", 1, body)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.upload(newKey(), "image/png", -1, body))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.upload(newKey(), "image/png", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.upload("/lecture/a", "image/png", 1, body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("조회 URL은 지정한 만료 시간으로 발급되고 파일을 받을 수 있다")
    void presignDownload() throws Exception {
        String key = newKey();
        s3Client.putObject(request -> request.bucket(BUCKET).key(PREFIX + "/" + key).contentType("text/plain"),
                RequestBody.fromString("hello"));

        PresignedUrl download = storage.presignDownload(key, Duration.ofHours(1));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(download.url())).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("hello");
        assertThat(download.expiresAt()).isCloseTo(Instant.now().plusSeconds(3600), within(5, ChronoUnit.SECONDS));
        assertThat(URI.create(download.url()).getQuery()).contains("X-Amz-Expires=3600");
    }

    @Test
    @DisplayName("조회 URL 만료는 0보다 크고 7일 이하여야 한다")
    void presignDownload_validatesExpiry() {
        String key = newKey();

        assertThatThrownBy(() -> storage.presignDownload(key, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.presignDownload(key, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.presignDownload(key, Duration.ofDays(7).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.presignDownload(key, Duration.ofDays(7))).isNotNull();
    }

    @Test
    @DisplayName("없는 파일은 비어 있다")
    void find_missing() {
        assertThat(storage.find(newKey())).isEmpty();
    }

    @Test
    @DisplayName("key가 비었거나 '/'로 시작하면 거부한다")
    void rejectsInvalidKey() {
        assertThatThrownBy(() -> storage.presignDownload(" ", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.presignDownload("/lecture/a", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.find(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private String newKey() {
        return "lecture/LECTURE_THUMBNAIL/" + UUID.randomUUID();
    }
}
