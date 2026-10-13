package com.hapbang.testsupport;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@TestComponent
@SpringBootApplication(scanBasePackages = "com.hapbang")
@AutoConfigurationPackage(basePackages = "com.hapbang")
@EnableJpaAuditing
public class ModuleTestConfiguration {
}
