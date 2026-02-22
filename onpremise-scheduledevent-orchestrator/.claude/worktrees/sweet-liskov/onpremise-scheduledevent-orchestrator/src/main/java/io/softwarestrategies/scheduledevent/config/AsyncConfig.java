package io.softwarestrategies.scheduledevent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configuration for async processing using virtual threads.
 *
 * Virtual threads are created per-task with no pool cap — pooling them is an anti-pattern
 * since they are cheap (~1KB each) and managed by the JVM, not an OS thread pool.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	/**
	 * Executor for async event delivery. A new virtual thread is created per task,
	 * so delivery of 100 events in a batch fires all 100 concurrently with no queuing.
	 */
	@Bean(name = "eventProcessingExecutor")
	public ExecutorService eventProcessingExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	/**
	 * Executor for scheduled tasks.
	 */
	@Bean(name = "schedulerExecutor")
	public ExecutorService schedulerExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}
}