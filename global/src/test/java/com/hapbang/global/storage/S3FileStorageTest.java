package com.hapbang.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    @DisplayName("업로드 URL로 올린 파일을 HEAD로 확인하면 크기와 Content-Type이 나온다")
    void presignUpload_thenFind() throws Exception {
        String key = newKey();
        PresignedUrl upload = storage.presignUpload(key, "image/png");

        HttpResponse<Void> response = put(upload.url(), "image/png", new byte[] {1, 2, 3, 4, 5});

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(storage.find(key)).contains(new StoredFile(5, "image/png"));
    }

    @Test
    @DisplayName("key 앞에 환경 prefix를 붙여 저장한다")
    void presignUpload_addsKeyPrefix() throws Exception {
        String key = newKey();
        PresignedUrl upload = storage.presignUpload(key, "text/plain");

        put(upload.url(), "text/plain", "hello".getBytes());

        assertThat(URI.create(upload.url()).getPath()).isEqualTo("/" + BUCKET + "/" + PREFIX + "/" + key);
        assertThat(s3Client.headObject(request -> request.bucket(BUCKET).key(PREFIX + "/" + key)).contentLength())
                .isEqualTo(5);
    }

    @Test
    @DisplayName("업로드 URL은 설정한 시간 뒤 만료되고 Content-Type을 서명에 넣는다")
    void presignUpload_signsExpiryAndContentType() {
        PresignedUrl upload = storage.presignUpload(newKey(), "video/mp4");

        assertThat(upload.expiresAt()).isCloseTo(Instant.now().plusSeconds(600), within(5, ChronoUnit.SECONDS));
        assertThat(URI.create(upload.url()).getQuery())
                .contains("X-Amz-Expires=600")
                .containsPattern("X-Amz-SignedHeaders=[^&]*content-type");
    }

    @Test
    @DisplayName("조회 URL로 파일을 받는다")
    void presignDownload() throws Exception {
        String key = newKey();
        s3Client.putObject(request -> request.bucket(BUCKET).key(PREFIX + "/" + key).contentType("text/plain"),
                RequestBody.fromString("hello"));

        PresignedUrl download = storage.presignDownload(key);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(download.url())).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("hello");
        assertThat(download.expiresAt()).isCloseTo(Instant.now().plusSeconds(600), within(5, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("없는 파일은 비어 있다")
    void find_missing() {
        assertThat(storage.find(newKey())).isEmpty();
    }

    @Test
    @DisplayName("key가 비었거나 '/'로 시작하면 거부한다")
    void rejectsInvalidKey() {
        assertThatThrownBy(() -> storage.presignUpload(" ", "image/png")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.presignDownload("/lecture/a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.find(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Content-Type 없이 업로드 URL을 만들 수 없다")
    void presignUpload_requiresContentType() {
        assertThatThrownBy(() -> storage.presignUpload(newKey(), "")).isInstanceOf(IllegalArgumentException.class);
    }

    private String newKey() {
        return "lecture/LECTURE_THUMBNAIL/" + UUID.randomUUID();
    }

    private HttpResponse<Void> put(String url, String contentType, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
