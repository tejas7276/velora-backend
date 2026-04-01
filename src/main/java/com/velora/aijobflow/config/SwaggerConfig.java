package com.velora.aijobflow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI veloraOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Velora AI JobFlow API")
                .description(
                    "**Distributed AI Job Processing Platform**\n\n" +
                    "Queue, process, and monitor AI workloads at scale.\n\n" +
                    "## Authentication\n" +
                    "1. Call `POST /auth/login` to get a JWT token\n" +
                    "2. Click **Authorize** above and enter: `Bearer <your-token>`\n" +
                    "3. All protected endpoints will now work\n\n" +
                    "## Job Types\n" +
                    "SUMMARIZE · AI_ANALYSIS · SENTIMENT · EXTRACT_KEYWORDS · " +
                    "TRANSLATE · GENERATE_REPORT · CODE_REVIEW · CLASSIFY · " +
                    "QUESTION_ANSWER · COMPARE_DOCUMENTS · RESUME_SCORE · " +
                    "INTERVIEW_PREP · JD_MATCH · LINKEDIN_BIO · EMAIL_WRITER · " +
                    "MEETING_SUMMARY · BUG_EXPLAINER"
                )
                .version("1.0.0")
                .contact(new Contact()
                    .name("Velora Team")
                    .email("support@velora.ai")))

            // Adds a padlock icon to the Swagger UI
            // ELI5: This tells Swagger "some endpoints need a login token"
            .addSecurityItem(new SecurityRequirement().addList("Bearer Auth"))
            .components(new Components()
                .addSecuritySchemes("Bearer Auth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Paste your JWT token here (without the 'Bearer' prefix)")));
    }
}