package org.apache.camel.kit.test.fixture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ExistingProjectApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExistingProjectApplication.class, args);
    }
}
