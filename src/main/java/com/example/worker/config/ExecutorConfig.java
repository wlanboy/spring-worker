package com.example.worker.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * One dedicated pool per implementation so they can be observed (thread names) and
 * shut down independently.
 *
 * <p>All pools use {@code waitForTasksToCompleteOnShutdown=true}: the pool is not stopped
 * early on {@code ContextClosedEvent}, but at bean destruction (after the web server's
 * graceful phase), where it waits up to {@code worker.shutdown-timeout} for running jobs.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class ExecutorConfig {

	public static final String MVC_CALLABLE = "mvcCallableExecutor";
	public static final String WEB_ASYNC_TASK = "webAsyncTaskExecutor";
	public static final String DEFERRED_RESULT = "deferredResultExecutor";
	public static final String ASYNC_FUTURE = "asyncFutureExecutor";
	public static final String ASYNC_VOID = "asyncVoidExecutor";
	public static final String VIRTUAL_THREADS = "virtualThreadExecutor";
	public static final String TASK_SCHEDULER = "workerTaskScheduler";

	private final WorkerProperties props;

	public ExecutorConfig(WorkerProperties props) {
		this.props = props;
	}

	@Bean(MVC_CALLABLE)
	ThreadPoolTaskExecutor mvcCallableExecutor() {
		return pool("mvc-callable-");
	}

	@Bean(WEB_ASYNC_TASK)
	ThreadPoolTaskExecutor webAsyncTaskExecutor() {
		return pool("web-async-task-");
	}

	@Bean(DEFERRED_RESULT)
	ThreadPoolTaskExecutor deferredResultExecutor() {
		return pool("deferred-result-");
	}

	@Bean(ASYNC_FUTURE)
	ThreadPoolTaskExecutor asyncFutureExecutor() {
		return pool("async-future-");
	}

	@Bean(ASYNC_VOID)
	ThreadPoolTaskExecutor asyncVoidExecutor() {
		return pool("async-void-");
	}

	/** Virtual thread per task; close() waits for tracked tasks without interrupting them. */
	@Bean(VIRTUAL_THREADS)
	SimpleAsyncTaskExecutor virtualThreadExecutor() {
		var executor = new SimpleAsyncTaskExecutor("virtual-");
		executor.setVirtualThreads(true);
		executor.setTaskTerminationTimeout(props.shutdownTimeout().toMillis());
		executor.setCancelRemainingTasksOnClose(false);
		return executor;
	}

	@Bean(TASK_SCHEDULER)
	ThreadPoolTaskScheduler workerTaskScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(props.poolSize());
		scheduler.setThreadNamePrefix("task-scheduler-");
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationMillis(props.shutdownTimeout().toMillis());
		return scheduler;
	}

	/** Executor used by Spring MVC for controller methods returning {@code Callable}. */
	@Bean
	WebMvcConfigurer callableAsyncSupport(@Qualifier(MVC_CALLABLE) ThreadPoolTaskExecutor executor) {
		return new WebMvcConfigurer() {
			@Override
			public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
				configurer.setTaskExecutor(executor);
			}
		};
	}

	private ThreadPoolTaskExecutor pool(String threadNamePrefix) {
		var executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(props.poolSize());
		executor.setMaxPoolSize(props.poolSize());
		executor.setQueueCapacity(props.queueCapacity());
		executor.setThreadNamePrefix(threadNamePrefix);
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationMillis(props.shutdownTimeout().toMillis());
		return executor;
	}
}
