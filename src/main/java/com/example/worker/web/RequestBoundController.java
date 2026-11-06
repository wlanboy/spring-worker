package com.example.worker.web;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;

import com.example.worker.config.ExecutorConfig;
import com.example.worker.config.WorkerProperties;
import com.example.worker.work.AsyncJobService;
import com.example.worker.work.SlowJob;

/**
 * The client waits for the job result; the servlet thread is released while the job runs
 * on a worker pool.
 */
@RestController
@RequestMapping("/work/request")
public class RequestBoundController {

	private final SlowJob job;
	private final AsyncJobService asyncJobService;
	private final WorkerProperties props;
	private final ThreadPoolTaskExecutor webAsyncTaskExecutor;
	private final ThreadPoolTaskExecutor deferredResultExecutor;

	public RequestBoundController(SlowJob job, AsyncJobService asyncJobService, WorkerProperties props,
			@Qualifier(ExecutorConfig.WEB_ASYNC_TASK) ThreadPoolTaskExecutor webAsyncTaskExecutor,
			@Qualifier(ExecutorConfig.DEFERRED_RESULT) ThreadPoolTaskExecutor deferredResultExecutor) {
		this.job = job;
		this.asyncJobService = asyncJobService;
		this.props = props;
		this.webAsyncTaskExecutor = webAsyncTaskExecutor;
		this.deferredResultExecutor = deferredResultExecutor;
	}

	/** Runs on the MVC async executor configured in {@link ExecutorConfig}. */
	@GetMapping("/callable")
	public Callable<String> callable() {
		return () -> job.run("callable");
	}

	/** Like Callable, but with its own executor and timeout. */
	@GetMapping("/web-async-task")
	public WebAsyncTask<String> webAsyncTask() {
		return new WebAsyncTask<>(props.requestTimeoutMillis(), webAsyncTaskExecutor,
				() -> job.run("web-async-task"));
	}

	/** The result is set from any thread, here from a dedicated pool. */
	@GetMapping("/deferred-result")
	public DeferredResult<String> deferredResult() {
		var result = new DeferredResult<String>(props.requestTimeoutMillis());
		deferredResultExecutor.execute(() -> {
			try {
				result.setResult(job.run("deferred-result"));
			}
			catch (RuntimeException e) {
				result.setErrorResult(e);
			}
		});
		return result;
	}

	/** {@code @Async} method returning a CompletableFuture. */
	@GetMapping("/async-future")
	public CompletableFuture<String> asyncFuture() {
		return asyncJobService.runForResult("async-future");
	}
}
