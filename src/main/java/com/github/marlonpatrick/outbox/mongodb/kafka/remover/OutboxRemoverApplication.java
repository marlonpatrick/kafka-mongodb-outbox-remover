package com.github.marlonpatrick.outbox.mongodb.kafka.remover;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OutboxRemoverApplication {

	public static void main(String[] args) {
		SpringApplication.run(OutboxRemoverApplication.class, args);
	}
}
