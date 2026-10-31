package com.hapbang.member.global.storage;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트 설정.
 * <ul>
 *     <li>서버: 액세스 키 없이 EC2 인스턴스 역할로 접근한다(SDK 기본 자격 증명 체인).</li>
 *     <li>로컬: endpoint가 있으면 S3Mock으로 보낸다. S3Mock은 서명을 검사하지 않아 가짜 키를 쓰고, path-style 주소만 지원한다.</li>
 *     <li>버킷 설정이 없으면(apps yaml을 읽지 않는 모듈 테스트 등) 만들지 않는다. 서버는 {@code ${S3_BUCKET}}이 없으면 시작에 실패한다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "hapbang.storage.s3", name = "bucket")
@EnableConfigurationProperties(S3StorageProperties.class)
class S3StorageConfig {

    @Bean
    AwsCredentialsProvider s3CredentialsProvider(S3StorageProperties properties) {
        if (properties.usesEmulator()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create("emulator", "emulator"));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean
    S3Client s3Client(S3StorageProperties properties, AwsCredentialsProvider s3CredentialsProvider) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(s3CredentialsProvider)
                // JDK HttpURLConnection 기반. Apache HttpClient를 클래스패스에 두지 않는다(global/build.gradle.kts)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                // 체크섬은 S3가 요구할 때만 계산한다(SDK 기본값은 매 요청 계산)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
        if (properties.usesEmulator()) {
            builder.endpointOverride(URI.create(properties.endpoint())).forcePathStyle(true);
        }
        return builder.build();
    }

    @Bean
    S3Presigner s3Presigner(S3StorageProperties properties, AwsCredentialsProvider s3CredentialsProvider) {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(s3CredentialsProvider);
        if (properties.usesEmulator()) {
            builder.endpointOverride(URI.create(properties.endpoint()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return builder.build();
    }

    @Bean
    S3FileStorage s3FileStorage(S3Client s3Client, S3Presigner s3Presigner, S3StorageProperties properties) {
        return new S3FileStorage(s3Client, s3Presigner, properties);
    }
}
