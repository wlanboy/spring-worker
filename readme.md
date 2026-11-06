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

## Graceful Shutdown

| Einstellung | Wirkung |
|---|---|
| `server.shutdown=graceful` | Tomcat nimmt keine neuen Requests mehr an und wartet auf laufende Requests, auch auf asynchrone. |
| `spring.lifecycle.timeout-per-shutdown-phase=45s` | Maximale Wartezeit pro Shutdown-Phase. **Für request-gebundene Varianten die entscheidende Einstellung.** |
| `spring.mvc.async.request-timeout=60s` | Muss größer als `worker.duration` sein, sonst bricht Tomcat den Request nach seinem Standard-Timeout von 30 s ab. |
| `worker.shutdown-timeout=45s` | Gibt an, wie lange die Executoren beim Zerstören der Beans auf laufende Jobs warten (`waitForTasksToCompleteOnShutdown` bzw. `awaitTermination`). **Für Fire-and-forget die entscheidende Einstellung.** |

Ist der Phase-Timeout zu kurz, verliert der Client bei den request-gebundenen Varianten die Verbindung. Bei Fire-and-forget werden die Jobs nur dann unterbrochen, wenn zusätzlich `waitForTasksToCompleteOnShutdown` fehlt.

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
