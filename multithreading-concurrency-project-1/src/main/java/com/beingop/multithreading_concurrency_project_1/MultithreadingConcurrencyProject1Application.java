package com.beingop.multithreading_concurrency_project_1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. @SpringBootApplication enables:
 *   - @ComponentScan: finds all @Component classes under com.beingop.*
 *   - @EnableAutoConfiguration: configures Spring based on classpath
 *
 * The actual crawl logic lives in CrawlerOrchestrator (CommandLineRunner),
 * which Spring calls automatically after the context is ready.
 */
@SpringBootApplication(scanBasePackages = "com.beingop")
public class MultithreadingConcurrencyProject1Application {

	public static void main(String[] args) {
		SpringApplication.run(MultithreadingConcurrencyProject1Application.class, args);
	}

}
