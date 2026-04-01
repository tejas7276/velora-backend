package com.velora.aijobflow;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import com.velora.aijobflow.service.EmailService;

@SpringBootTest
@ActiveProfiles("test")
class AijobflowApplicationTests {

    @Test
    void contextLoads() {
    }

    @MockBean
    private EmailService emailService;
}
