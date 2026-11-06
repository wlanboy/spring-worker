# spring-worker

Spring Boot 4.1 (Java 25) mit acht Endpunkten. Jeder setzt einen asynchronen Worker anders um und blockiert `worker.duration` lang (Standard 30 s). Damit lässt sich Graceful Shutdown pro Variante testen.

## Endpunkte

**Request-gebunden:** Der Client wartet auf das Ergebnis, der Servlet-Thread wird freigegeben.

| Endpunkt | Umsetzung |
|---|---|
| `GET /work/request/callable` | `Callable<T>` auf dem MVC-Async-Executor |
| `GET /work/request/web-async-task` | `WebAsyncTask<T>` mit eigenem Executor und Timeout |
| `GET /work/request/deferred-result` | `DeferredResult<T>`, das Ergebnis setzt ein eigener Pool |
| `GET /work/request/async-future` | `@Async`-Methode, die ein `CompletableFuture<T>` liefert |

**Fire-and-forget:** sofort `202 Accepted`, der Job läuft im Hintergrund.

| Endpunkt | Umsetzung |
|---|---|
| `POST /work/background/async-void` | `@Async void` auf einem `ThreadPoolTaskExecutor` |
| `POST /work/background/executor-service` | JDK-`ExecutorService`, Shutdown per `@PreDestroy` |
| `POST /work/background/virtual-threads` | `SimpleAsyncTaskExecutor` mit Virtual Threads |
| `POST /work/background/task-scheduler` | `ThreadPoolTaskScheduler` |

Jede Variante hat einen eigenen Pool. Im Log ist sie am Thread-Namen erkennbar, z. B. `deferred-result-1` oder `virtual-1`.

## Actuator

- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`: Meldet beim Shutdown sofort `OUT_OF_SERVICE`.

## Was bei jeder Implementierung zu beachten ist

### Für alle Varianten

- Der Executor muss eine Spring-Bean sein oder von einer Bean per `@PreDestroy` geschlossen werden. Ein Pool, den niemand herunterfährt, wird beim Beenden der JVM einfach abgebrochen.
- `waitForTasksToCompleteOnShutdown=true` allein reicht nicht. Ohne `awaitTerminationMillis` ruft Spring zwar `shutdown()` auf, wartet aber nicht, und die JVM beendet sich mit laufenden Jobs.
- Die Timeouts hängen voneinander ab: `worker.duration` < `worker.shutdown-timeout` und `worker.duration` < Request-Timeout. Die Shutdown-Phasen laufen nacheinander, im schlechtesten Fall dauert der Shutdown also `timeout-per-shutdown-phase` + `worker.shutdown-timeout`. `terminationGracePeriodSeconds` in Kubernetes muss größer sein, sonst kommt vorher `SIGKILL`.
- Ein Job, der `InterruptedException` fängt, setzt das Interrupt-Flag wieder (`Thread.currentThread().interrupt()`), damit der Pool den Abbruch mitbekommt.
- Ist die Queue voll, wird der Job abgelehnt (`TaskRejectedException` bzw. `RejectedExecutionException`). Der Aufrufer muss das behandeln, z. B. mit `503`.

### Request-gebunden

| Variante | Darauf achten |
|---|---|
| `Callable` | Läuft auf dem Executor aus `configureAsyncSupport`. Fehlt er, nimmt Spring MVC den `applicationTaskExecutor` von Spring Boot oder, falls es keinen gibt, einen `SimpleAsyncTaskExecutor` ohne Thread-Limit. Timeout ist `spring.mvc.async.request-timeout`. |
| `WebAsyncTask` | Timeout und Executor im Konstruktor überschreiben die globalen Werte. Ohne Executor-Angabe läuft der Task auf dem MVC-Executor. |
| `DeferredResult` | Exceptions im Worker abfangen und `setErrorResult()` aufrufen. Sonst hängt der Request bis zum Timeout. Ohne Timeout im Konstruktor gilt `spring.mvc.async.request-timeout`. |
| `@Async` + `CompletableFuture` | Siehe `@Async void` unten. Der Request-Timeout ist `spring.mvc.async.request-timeout`. |

### Fire-and-forget

| Variante | Darauf achten |
|---|---|
| `@Async void` | Braucht `@EnableAsync`. Die Methode muss `public` sein und in einer anderen Bean liegen: Ein Aufruf über `this` umgeht den Proxy und läuft synchron. Ohne Qualifier in `@Async("...")` landet der Job auf dem `applicationTaskExecutor`. Exceptions sieht nur der `AsyncUncaughtExceptionHandler`, standardmäßig werden sie nur geloggt. |
| JDK-`ExecutorService` | Spring kennt den Lebenszyklus nicht, `shutdown()` + `awaitTermination()` + `shutdownNow()` gehören in `@PreDestroy`. `ExecutorService.close()` wartet unbegrenzt und ist deshalb ungeeignet. Bei `submit()` landen Exceptions im `Future` und gehen verloren, wenn es niemand abfragt; besser `execute()` nutzen oder im Job selbst `try/catch` setzen. |
| `SimpleAsyncTaskExecutor` (Virtual Threads) | Ohne `taskTerminationTimeout` merkt sich der Executor keine Tasks, und `close()` wartet nicht. Er startet pro Job einen neuen Thread ohne Obergrenze; bei Bedarf `setConcurrencyLimit()` setzen. |
| `ThreadPoolTaskScheduler` | Standard-Poolgröße ist 1. Die Queue ist unbegrenzt, Jobs werden also nie abgelehnt, sondern stauen sich. Periodische Tasks (`scheduleAtFixedRate` usw.) werden nach dem Shutdown nicht mehr neu gestartet. Ein gerade laufender Durchlauf und einmalige Jobs laufen zu Ende. |

## Graceful Shutdown

| Einstellung | Wirkung |
|---|---|
| `server.shutdown=graceful` | Tomcat nimmt keine neuen Requests mehr an und wartet auf laufende Requests, auch auf asynchrone. |
| `spring.lifecycle.timeout-per-shutdown-phase=45s` | Maximale Wartezeit pro Shutdown-Phase. **Für request-gebundene Varianten die entscheidende Einstellung.** |
| `spring.mvc.async.request-timeout=60s` | Muss größer als `worker.duration` sein, sonst bricht Tomcat den Request nach seinem Standard-Timeout von 30 s ab. |
| `worker.shutdown-timeout=45s` | Gibt an, wie lange die Executoren beim Zerstören der Beans auf laufende Jobs warten (`waitForTasksToCompleteOnShutdown` bzw. `awaitTermination`). **Für Fire-and-forget die entscheidende Einstellung.** |

Ist der Phase-Timeout zu kurz, verliert der Client bei den request-gebundenen Varianten die Verbindung. Bei Fire-and-forget werden die Jobs nur dann unterbrochen, wenn zusätzlich `waitForTasksToCompleteOnShutdown` fehlt.

### Ablauf bei SIGTERM

Der Shutdown läuft in drei Schritten. Die Reihenfolge ist wichtig: Die Executoren müssen so lange leben, bis der Webserver alle Requests abgeschlossen hat.

**1. Context schließen, Readiness abschalten**

Die JVM führt den Shutdown-Hook von Spring aus, der `context.close()` aufruft. Der Readiness-State wechselt auf `REFUSING_TRAFFIC`, `/actuator/health/readiness` meldet ab jetzt `OUT_OF_SERVICE`. Ein Load Balancer oder Kubernetes schickt keinen Traffic mehr an die Instanz.

**2. Webserver fährt geordnet herunter** (begrenzt durch `spring.lifecycle.timeout-per-shutdown-phase`)

Wegen `server.shutdown=graceful` schließt Tomcat den Listen-Socket. Neue Verbindungen werden abgelehnt. Die Meldung `Socket accept failed` im Log stammt genau von diesem Schritt und ist kein Fehler.

Bereits laufende Requests dürfen bis zu 45 s weiterlaufen. Das gilt auch für asynchrone Requests mit `Callable`, `WebAsyncTask`, `DeferredResult` und `CompletableFuture`. Die Executoren dieser Varianten sind in diesem Schritt noch aktiv, deshalb kann der Job sein Ergebnis noch an den Client liefern.

**3. Beans zerstören, Executoren warten auf ihre Jobs** (begrenzt durch `worker.shutdown-timeout`)

Ein `ThreadPoolTaskExecutor` oder `ThreadPoolTaskScheduler` mit `waitForTasksToCompleteOnShutdown=true` reagiert nicht auf das `ContextClosedEvent` und wird nicht schon in Schritt 1 gestoppt. Erst beim Zerstören der Bean ruft Spring `shutdown()` auf. Danach nimmt der Pool keine neuen Jobs mehr an und wartet bis zu 45 s auf die laufenden.

Die beiden anderen Varianten verhalten sich gleich, nur mit anderem Mechanismus:

| Variante | Warten auf laufende Jobs |
|---|---|
| `SimpleAsyncTaskExecutor` (Virtual Threads) | `close()` wartet `taskTerminationTimeout` lang, `cancelRemainingTasksOnClose=false` verhindert einen Interrupt |
| JDK-`ExecutorService` | `@PreDestroy` ruft `shutdown()` und `awaitTermination()` auf, erst nach Ablauf des Timeouts folgt `shutdownNow()` |

**Ergebnis:** Die Anwendung nimmt nach SIGTERM keine neuen Requests mehr an. Laufende Requests und Hintergrund-Jobs werden zu Ende ausgeführt, solange sie innerhalb der Timeouts fertig werden.

## Konfiguration

```properties
worker.duration=30s
worker.pool-size=4
worker.shutdown-timeout=45s
```

## Tests

```bash
./mvnw test
```

`GracefulShutdownTest` hat einen Test pro Variante. Jeder Test startet die App auf einem zufälligen Port mit 3 s Job-Dauer, löst einen Job aus und fährt die App herunter, während der Job läuft. Geprüft wird:

- Readiness wechselt auf `REFUSING_TRAFFIC`.
- Das Herunterfahren wartet auf das Ende des Jobs.
- Request-gebunden: Der Client erhält `200` mit dem Ergebnis.
- Fire-and-forget: Der Job ist abgeschlossen und wurde nicht unterbrochen.

## Manuell testen

```bash
./mvnw spring-boot:run
curl localhost:8080/work/request/deferred-result &   # oder: curl -X POST localhost:8080/work/background/virtual-threads
kill -TERM <pid>                                      # die Anwendung wartet, bis der Job fertig ist
```
