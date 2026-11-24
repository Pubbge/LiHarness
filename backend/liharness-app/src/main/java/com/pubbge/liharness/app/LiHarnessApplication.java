package com.pubbge.liharness.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LiHarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiHarnessApplication.class, args);
    }

}
