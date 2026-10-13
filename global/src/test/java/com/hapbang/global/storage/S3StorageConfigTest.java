package com.hapbang.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.util.ClassUtils;

class S3StorageConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(S3StorageConfig.class);

    @Test
    @DisplayName("버킷 설정이 없으면 S3 빈을 만들지 않는다 (S3를 쓰지 않는 모듈 테스트)")
    void skipsWithoutBucket() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(S3FileStorage.class);
        });
    }

    @Test
    @DisplayName("버킷이 환경 변수를 가리키는데 값이 없으면 시작에 실패한다 (서버에 S3_BUCKET 누락)")
    void failsWhenBucketVariableIsMissing() {
        runner.withPropertyValues(
                        "hapbang.storage.s3.bucket=${S3_BUCKET_NOT_SET_FOR_TEST}",
                        "hapbang.storage.s3.region=ap-northeast-2",
                        "hapbang.storage.s3.key-prefix=dev")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("AWS SDK가 Apache HttpClient 5를 클래스패스에 올리지 않는다 (Spring RestClient가 자동으로 골라 쿠키·리다이렉트 처리가 바뀐다)")
    void doesNotBringApacheHttpClient() {
        assertThat(ClassUtils.isPresent("org.apache.hc.client5.http.impl.classic.HttpClients", null)).isFalse();
    }

    @Test
    @DisplayName("설정이 모두 있으면 S3FileStorage를 만든다")
    void createsStorage() {
        runner.withPropertyValues(
                        "hapbang.storage.s3.endpoint=http://localhost:9",
                        "hapbang.storage.s3.bucket=test",
                        "hapbang.storage.s3.region=ap-northeast-2",
                        "hapbang.storage.s3.key-prefix=test")
                .run(context -> assertThat(context).hasSingleBean(S3FileStorage.class));
    }
}
