package com.velora.aijobflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @EnableAsync  — activates @Async in EmailService.
 *                 Without this, @Async is silently ignored and email
 *                 still blocks the HTTP thread (30s SMTP timeout).
 *
 * @EnableScheduling — activates @Scheduled in SchedulerConfig.
 *                     (Already declared on SchedulerConfig itself,
 *                     but explicit here is cleaner.)
 */
@SpringBootApplication(scanBasePackages = "com.velora.aijobflow")
@EnableAsync
@EnableScheduling
public class AijobflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(AijobflowApplication.class, args);
    }
}