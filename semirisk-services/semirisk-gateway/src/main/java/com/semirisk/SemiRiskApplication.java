package com.semirisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SemiRiskApplication {

	public static void main(String[] args) {
		SpringApplication.run(SemiRiskApplication.class, args);
	}

}
