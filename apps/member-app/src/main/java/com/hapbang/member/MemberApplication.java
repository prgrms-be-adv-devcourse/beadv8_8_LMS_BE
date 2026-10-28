package com.hapbang.member;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** member-app의 독립 실행 진입점. 다른 애플리케이션의 빈은 등록하지 않는다. */
@SpringBootApplication(scanBasePackages = {"com.hapbang.member", "com.hapbang.shared"})
@AutoConfigurationPackage(basePackages = {"com.hapbang.member", "com.hapbang.shared"})
public class MemberApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemberApplication.class, args);
    }
}
