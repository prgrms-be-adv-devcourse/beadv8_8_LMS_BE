package com.hapbang.appearance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** appearance-app의 독립 실행 진입점. 다른 애플리케이션의 빈은 등록하지 않는다. */
@SpringBootApplication(scanBasePackages = {"com.hapbang.appearance", "com.hapbang.shared"})
@AutoConfigurationPackage(basePackages = {"com.hapbang.appearance", "com.hapbang.shared"})
public class AppearanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppearanceApplication.class, args);
    }
}
