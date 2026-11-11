package com.hapbang.lecture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * lecture-app의 독립 실행 진입점. 다른 애플리케이션의 빈은 등록하지 않는다.
 * <p>
 * 같은 JVM에 실리는 쿠폰 모듈({@code com.hapbang.coupon}, Gradle {@code :coupon})도 스캔한다. 강의와 쿠폰은 서로의 코드를
 * 참조하지 않고 내부 HTTP API와 Kafka로만 통신한다.
 */
@SpringBootApplication(scanBasePackages = {"com.hapbang.lecture", "com.hapbang.coupon", "com.hapbang.shared"})
@AutoConfigurationPackage(basePackages = {"com.hapbang.lecture", "com.hapbang.coupon", "com.hapbang.shared"})
public class LectureApplication {

    public static void main(String[] args) {
        SpringApplication.run(LectureApplication.class, args);
    }
}
