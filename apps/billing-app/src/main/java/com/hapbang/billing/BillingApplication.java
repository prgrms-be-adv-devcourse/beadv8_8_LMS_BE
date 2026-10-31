package com.hapbang.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** billing-app의 독립 실행 진입점. 다른 애플리케이션의 빈은 등록하지 않는다. */
@SpringBootApplication(scanBasePackages = {"com.hapbang.billing", "com.hapbang.shared"})
@AutoConfigurationPackage(basePackages = {"com.hapbang.billing", "com.hapbang.shared"})
public class BillingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}
