package com.example.worker.work;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.worker.config.WorkerProperties;

import jakarta.annotation.PreDestroy;

/** Plain JDK {@link ExecutorService} without Spring wrapper; lifecycle handled by hand. */
@Service
public class PlainExecutorJobService {

	private static final Logger log = LoggerFactory.getLogger(PlainExecutorJobService.class);

	private final SlowJob job;
	private final WorkerProperties props;
	private final ExecutorService executor;

	public PlainExecutorJobService(SlowJob job, WorkerProperties props) {
		this.job = job;
		this.props = props;
		this.executor = Executors.newFixedThreadPool(props.poolSize(),
				Thread.ofPlatform().name("executor-service-", 1).factory());
	}

	public void submit(String implementation) {
		executor.submit(() -> job.run(implementation));
	}

	@PreDestroy
	void shutdown() throws InterruptedException {
		log.info("shutting down plain ExecutorService, waiting up to {}", props.shutdownTimeout());
		executor.shutdown();
		if (!executor.awaitTermination(props.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
			log.warn("ExecutorService did not terminate in time, interrupting remaining jobs");
			executor.shutdownNow();
		}
	}
}
