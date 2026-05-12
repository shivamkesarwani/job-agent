package com.jobagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JobAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobAgentApplication.class, args);
    }
}
