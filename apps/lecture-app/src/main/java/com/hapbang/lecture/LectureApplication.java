package com.hapbang.lecture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** lecture-app의 독립 실행 진입점. 다른 애플리케이션의 빈은 등록하지 않는다. */
@SpringBootApplication(scanBasePackages = {"com.hapbang.lecture", "com.hapbang.shared"})
@AutoConfigurationPackage(basePackages = {"com.hapbang.lecture", "com.hapbang.shared"})
public class LectureApplication {

    public static void main(String[] args) {
        SpringApplication.run(LectureApplication.class, args);
    }
}
