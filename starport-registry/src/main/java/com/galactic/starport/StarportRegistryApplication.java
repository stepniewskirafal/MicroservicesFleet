package com.galactic.starport;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StarportRegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarportRegistryApplication.class, args);
    }
}
