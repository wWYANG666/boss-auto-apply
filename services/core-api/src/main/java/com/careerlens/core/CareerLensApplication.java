package com.careerlens.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class CareerLensApplication {
    public static void main(String[] args) {
        SpringApplication.run(CareerLensApplication.class, args);
    }
}
