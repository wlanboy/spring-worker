package com.example.worker.work;

import java.util.concurrent.CompletableFuture;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.example.worker.config.ExecutorConfig;

/** {@code @Async} methods must live in a separate bean so the proxy is used. */
@Service
public class AsyncJobService {

	private final SlowJob job;

	public AsyncJobService(SlowJob job) {
		this.job = job;
	}

	@Async(ExecutorConfig.ASYNC_FUTURE)
	public CompletableFuture<String> runForResult(String implementation) {
		return CompletableFuture.completedFuture(job.run(implementation));
	}

	@Async(ExecutorConfig.ASYNC_VOID)
	public void runAndForget(String implementation) {
		job.run(implementation);
	}
}
