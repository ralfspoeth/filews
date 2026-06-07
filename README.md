# filews — Directory Watch Service

A small Java library that wraps the JDK `WatchService` API into a simple, callback-based interface with virtual thread support.

## Requirements

- Java 21+
- No runtime dependencies

## Usage

Implement a `Consumer<PathEvent>` and pass it along with the directories to watch.

**One-liner (static factory):**

```java
Thread t = DirectoryWatchService.startService(
    event -> System.out.println(event.path() + " -> " + event.event().kind().name()),
    List.of(Path.of("/some/directory"))
);
```

**Manual (for lifecycle control):**

```java
var service = new DirectoryWatchService(this::handleEvent, List.of(dirA, dirB));
var thread = Thread.ofVirtual().start(service);

// later, to stop:
thread.interrupt();   // or: service.close()
thread.join();
```

## API

### `DirectoryWatchService`

| Member | Description |
|---|---|
| `DirectoryWatchService(Consumer<PathEvent> cb, Collection<Path> paths)` | Creates a new instance watching the given directories. |
| `void run()` | Starts the watch loop. Intended to run on a virtual thread. Blocks until interrupted, `close()` is called, or all watched directories become invalid. |
| `void close()` | Closes the underlying `WatchService`, unblocking `run()`. |
| `static Thread startService(Consumer<PathEvent> cb, Collection<Path> paths)` | Convenience factory: creates a service and starts it on a virtual thread. |

### `PathEvent`

A record carrying the event context:

| Member | Description |
|---|---|
| `Path dir()` | The watched directory in which the event occurred. |
| `WatchEvent<Path> event()` | The raw watch event (kind: `ENTRY_CREATE`, `ENTRY_MODIFY`, or `ENTRY_DELETE`). |
| `Path path()` | Convenience method: `dir().resolve(event().context())` — the full path of the affected file. |

## Stopping the service

Stop by either interrupting the thread or calling `close()` on the service. Both cleanly terminate the watch loop and wait for any in-flight callbacks to complete before returning.

## Maven

```xml
<dependency>
    <groupId>io.github.ralfspoeth</groupId>
    <artifactId>filews</artifactId>
    <version>0.2-SNAPSHOT</version>
</dependency>
```

## License

[MIT](https://opensource.org/license/mit/)
