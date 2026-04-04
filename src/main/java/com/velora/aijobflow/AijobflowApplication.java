package com.velora.aijobflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @EnableAsync: Required for @Async in EmailService to work.
 * Without this, @Async is silently ignored — email still runs
 * synchronously and blocks the HTTP request thread.
 *
 * @EnableScheduling: Already implicitly enabled by SchedulerConfig,
 * but explicitly adding it here is cleaner.
 */
@SpringBootApplication(scanBasePackages = "com.velora.aijobflow")
@EnableAsync
@EnableScheduling
public class AijobflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(AijobflowApplication.class, args);
    }
}