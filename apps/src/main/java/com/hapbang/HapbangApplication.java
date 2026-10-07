package com.hapbang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(sharedModules = "shared")
@SpringBootApplication
public class HapbangApplication {

    public static void main(String[] args) {
        SpringApplication.run(HapbangApplication.class, args);
    }

}
