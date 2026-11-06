package com.example.worker.work;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/** Counts job state transitions; used by the graceful shutdown tests. */
@Component
public class WorkTracker {

	private final AtomicInteger started = new AtomicInteger();
	private final AtomicInteger completed = new AtomicInteger();
	private final AtomicInteger interrupted = new AtomicInteger();
	private final Semaphore startedSignal = new Semaphore(0);

	void onStarted() {
		started.incrementAndGet();
		startedSignal.release();
	}

	void onCompleted() {
		completed.incrementAndGet();
	}

	void onInterrupted() {
		interrupted.incrementAndGet();
	}

	public boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
		return startedSignal.tryAcquire(timeout, unit);
	}

	public int started() {
		return started.get();
	}

	public int completed() {
		return completed.get();
	}

	public int interrupted() {
		return interrupted.get();
	}
}
