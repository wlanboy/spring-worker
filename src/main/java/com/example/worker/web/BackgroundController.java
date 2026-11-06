package com.example.worker.web;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.worker.config.ExecutorConfig;
import com.example.worker.work.AsyncJobService;
import com.example.worker.work.PlainExecutorJobService;
import com.example.worker.work.SlowJob;

/** Fire-and-forget: responds 202 immediately, the job keeps running in the background. */
@RestController
@RequestMapping("/work/background")
public class BackgroundController {

	private final SlowJob job;
	private final AsyncJobService asyncJobService;
	private final PlainExecutorJobService plainExecutorJobService;
	private final SimpleAsyncTaskExecutor virtualThreadExecutor;
	private final ThreadPoolTaskScheduler taskScheduler;

	public BackgroundController(SlowJob job, AsyncJobService asyncJobService,
			PlainExecutorJobService plainExecutorJobService,
			@Qualifier(ExecutorConfig.VIRTUAL_THREADS) SimpleAsyncTaskExecutor virtualThreadExecutor,
			@Qualifier(ExecutorConfig.TASK_SCHEDULER) ThreadPoolTaskScheduler taskScheduler) {
		this.job = job;
		this.asyncJobService = asyncJobService;
		this.plainExecutorJobService = plainExecutorJobService;
		this.virtualThreadExecutor = virtualThreadExecutor;
		this.taskScheduler = taskScheduler;
	}

	@PostMapping("/async-void")
	public ResponseEntity<Map<String, String>> asyncVoid() {
		asyncJobService.runAndForget("async-void");
		return accepted("async-void");
	}

	@PostMapping("/executor-service")
	public ResponseEntity<Map<String, String>> executorService() {
		plainExecutorJobService.submit("executor-service");
		return accepted("executor-service");
	}

	@PostMapping("/virtual-threads")
	public ResponseEntity<Map<String, String>> virtualThreads() {
		virtualThreadExecutor.execute(() -> job.run("virtual-threads"));
		return accepted("virtual-threads");
	}

	@PostMapping("/task-scheduler")
	public ResponseEntity<Map<String, String>> taskScheduler() {
		taskScheduler.execute(() -> job.run("task-scheduler"));
		return accepted("task-scheduler");
	}

	private static ResponseEntity<Map<String, String>> accepted(String implementation) {
		return ResponseEntity.accepted().body(Map.of("implementation", implementation, "status", "accepted"));
	}
}
