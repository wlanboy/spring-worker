package com.example.worker.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param duration        how long a single job runs
 * @param poolSize        threads per platform-thread pool
 * @param shutdownTimeout how long executors wait for running jobs on shutdown
 */
@ConfigurationProperties("worker")
public record WorkerProperties(
		@DefaultValue("30s") Duration duration,
		@DefaultValue("4") int poolSize,
		@DefaultValue("45s") Duration shutdownTimeout) {

	/** Request timeout for DeferredResult / WebAsyncTask: job duration plus headroom. */
	public long requestTimeoutMillis() {
		return duration.plus(shutdownTimeout).toMillis();
	}
}
