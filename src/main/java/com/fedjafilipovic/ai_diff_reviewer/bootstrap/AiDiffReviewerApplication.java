package com.fedjafilipovic.ai_diff_reviewer.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.fedjafilipovic.ai_diff_reviewer")
public class AiDiffReviewerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiDiffReviewerApplication.class, args);
	}

}
