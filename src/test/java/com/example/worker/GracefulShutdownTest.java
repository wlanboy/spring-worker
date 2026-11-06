package com.example.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.example.worker.work.WorkTracker;

/**
 * Starts a real application (random port) per test, triggers a job, closes the context
 * while the job is running and verifies that the job finished instead of being killed.
 */
class GracefulShutdownTest {

	private static final Duration JOB_DURATION = Duration.ofSeconds(3);
	private static final long TIMEOUT_SECONDS = 20;

	private final HttpClient http = HttpClient.newHttpClient();
	private ConfigurableApplicationContext app;

	@AfterEach
	void tearDown() {
		if (app != null && app.isActive()) {
			app.close();
		}
	}

	// --- request-bound: client waits for the result -------------------------------

	@Test
	void callableCompletesInFlightRequestOnShutdown() throws Exception {
		assertRequestSurvivesShutdown("/work/request/callable", "callable");
	}

	@Test
	void webAsyncTaskCompletesInFlightRequestOnShutdown() throws Exception {
		assertRequestSurvivesShutdown("/work/request/web-async-task", "web-async-task");
	}

	@Test
	void deferredResultCompletesInFlightRequestOnShutdown() throws Exception {
		assertRequestSurvivesShutdown("/work/request/deferred-result", "deferred-result");
	}

	@Test
	void asyncCompletableFutureCompletesInFlightRequestOnShutdown() throws Exception {
		assertRequestSurvivesShutdown("/work/request/async-future", "async-future");
	}

	// --- fire-and-forget: 202, job runs in background -----------------------------

	@Test
	void asyncVoidFinishesBackgroundJobOnShutdown() throws Exception {
		assertBackgroundJobSurvivesShutdown("/work/background/async-void");
	}

	@Test
	void executorServiceFinishesBackgroundJobOnShutdown() throws Exception {
		assertBackgroundJobSurvivesShutdown("/work/background/executor-service");
	}

	@Test
	void virtualThreadsFinishBackgroundJobOnShutdown() throws Exception {
		assertBackgroundJobSurvivesShutdown("/work/background/virtual-threads");
	}

	@Test
	void taskSchedulerFinishesBackgroundJobOnShutdown() throws Exception {
		assertBackgroundJobSurvivesShutdown("/work/background/task-scheduler");
	}

	// --- new requests during shutdown ---------------------------------------------

	@Test
	void rejectsNewConnectionDuringShutdown() throws Exception {
		assertNewRequestRejectedDuringShutdown(HttpClient.newHttpClient());
	}

	@Test
	void rejectsRequestOnKeepAliveConnectionDuringShutdown() throws Exception {
		// same client as the health checks in startApp(), so the pooled connection is reused
		assertNewRequestRejectedDuringShutdown(http);
	}

	// --- helpers -------------------------------------------------------------------

	private void assertRequestSurvivesShutdown(String path, String implementation) throws Exception {
		startApp();
		var tracker = app.getBean(WorkTracker.class);

		CompletableFuture<HttpResponse<String>> response = http.sendAsync(
				HttpRequest.newBuilder(uri(path)).GET().build(), BodyHandlers.ofString());
		assertThat(tracker.awaitStarted(5, TimeUnit.SECONDS)).as("job started").isTrue();

		CompletableFuture<Void> shutdown = closeAppAsync();

		var result = response.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(result.body()).startsWith(implementation + " completed");

		shutdown.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertJobCompletedNotInterrupted(tracker);
	}

	private void assertBackgroundJobSurvivesShutdown(String path) throws Exception {
		startApp();
		var tracker = app.getBean(WorkTracker.class);

		long start = System.nanoTime();
		var response = http.send(HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
				BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).as("responds before job is done")
				.isLessThan(JOB_DURATION);
		assertThat(tracker.awaitStarted(5, TimeUnit.SECONDS)).as("job started").isTrue();
		assertThat(tracker.completed()).as("job still running").isZero();

		closeAppAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		// close() only returns after the executor waited for the running job
		assertJobCompletedNotInterrupted(tracker);
	}

	/**
	 * Keeps a job running so the shutdown is in progress, then sends a second request.
	 * It must not reach the application: either the connection is refused or Tomcat
	 * answers 503.
	 */
	private void assertNewRequestRejectedDuringShutdown(HttpClient client) throws Exception {
		startApp();
		var tracker = app.getBean(WorkTracker.class);

		CompletableFuture<HttpResponse<String>> inFlight = http.sendAsync(
				HttpRequest.newBuilder(uri("/work/request/callable")).GET().build(), BodyHandlers.ofString());
		assertThat(tracker.awaitStarted(5, TimeUnit.SECONDS)).as("job started").isTrue();

		var port = app.getEnvironment().getProperty("local.server.port");
		CompletableFuture<Void> shutdown = closeAppAsync();

		var lateRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/work/background/async-void"))
				.POST(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(5)).build();
		try {
			var response = client.send(lateRequest, BodyHandlers.ofString());
			assertThat(response.statusCode()).as("late request during shutdown").isEqualTo(503);
		}
		catch (IOException e) {
			// ConnectException for a new connection, closed connection for a reused one
			assertThat(e).isNotInstanceOf(HttpTimeoutException.class);
		}

		assertThat(inFlight.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
		shutdown.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		// the late request did not start a second job
		assertJobCompletedNotInterrupted(tracker);
	}

	private void startApp() throws Exception {
		// command line args, because builder.properties() would be overridden by application.properties
		app = new SpringApplicationBuilder(WorkerApplication.class)
				.run("--server.port=0", "--worker.duration=" + JOB_DURATION.toMillis() + "ms",
						"--worker.shutdown-timeout=15s", "--spring.lifecycle.timeout-per-shutdown-phase=15s");

		assertThat(http.send(HttpRequest.newBuilder(uri("/actuator/health/liveness")).build(),
				BodyHandlers.ofString()).statusCode()).isEqualTo(200);
		assertThat(http.send(HttpRequest.newBuilder(uri("/actuator/health/readiness")).build(),
				BodyHandlers.ofString()).statusCode()).isEqualTo(200);
	}

	/** Closes the context in the background and waits until readiness reports REFUSING_TRAFFIC. */
	private CompletableFuture<Void> closeAppAsync() throws InterruptedException {
		var availability = app.getBean(ApplicationAvailability.class);
		var context = app;
		var shutdown = CompletableFuture.runAsync(context::close);

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (availability.getReadinessState() != ReadinessState.REFUSING_TRAFFIC && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		assertThat(availability.getReadinessState()).as("readiness during shutdown")
				.isEqualTo(ReadinessState.REFUSING_TRAFFIC);
		assertThat(shutdown).as("shutdown waits for running job").isNotDone();
		return shutdown;
	}

	private static void assertJobCompletedNotInterrupted(WorkTracker tracker) {
		assertThat(tracker.started()).isEqualTo(1);
		assertThat(tracker.completed()).isEqualTo(1);
		assertThat(tracker.interrupted()).isZero();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + app.getEnvironment().getProperty("local.server.port") + path);
	}
}
