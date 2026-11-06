package com.example.worker.work;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.worker.config.WorkerProperties;

/** The actual "work": blocks for {@code worker.duration}. */
@Component
public class SlowJob {

	private static final Logger log = LoggerFactory.getLogger(SlowJob.class);

	private final WorkerProperties props;
	private final WorkTracker tracker;

	public SlowJob(WorkerProperties props, WorkTracker tracker) {
		this.props = props;
		this.tracker = tracker;
	}

	public String run(String implementation) {
		var thread = Thread.currentThread();
		var start = Instant.now();
		log.info("[{}] job started on {} (virtual={}), duration {}", implementation, thread.getName(),
				thread.isVirtual(), props.duration());
		tracker.onStarted();
		try {
			Thread.sleep(props.duration());
		}
		catch (InterruptedException e) {
			tracker.onInterrupted();
			log.warn("[{}] job interrupted after {}", implementation, Duration.between(start, Instant.now()));
			thread.interrupt();
			throw new IllegalStateException("job interrupted: " + implementation, e);
		}
		tracker.onCompleted();
		var took = Duration.between(start, Instant.now());
		log.info("[{}] job completed after {}", implementation, took);
		return "%s completed on %s after %dms".formatted(implementation, thread.getName(), took.toMillis());
	}
}
