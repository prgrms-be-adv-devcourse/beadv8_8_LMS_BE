package com.hapbang.customersupport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** customer-support-app의 독립 실행 진입점. 다른 애플리케이션의 빈은 등록하지 않는다. */
@SpringBootApplication(scanBasePackages = {"com.hapbang.customersupport", "com.hapbang.shared"})
@AutoConfigurationPackage(basePackages = {"com.hapbang.customersupport", "com.hapbang.shared"})
public class CustomerSupportApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerSupportApplication.class, args);
    }
}
