# filews — Directory Watch Service

A small Java library that wraps the JDK `WatchService` API into a simple, callback-based interface with virtual thread support.

## Requirements

- Java 25+
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
| `DirectoryWatchService(Consumer<PathEvent> cb, Collection<Path> paths, Predicate<Path> autoRegister)` | As above, additionally watching directories created below a watched directory whenever `autoRegister` accepts them. |
| `void register(Path dir)` | Starts watching a directory. May be called at any time, including from the callback. Idempotent. |
| `void registerTree(Path root)` | Registers `root` at OS level and every directory below it accepted by `autoRegister`. |
| `boolean unregister(Path dir)` | Stops watching a directory; returns whether it had been watched. |
| `Set<Path> watched()` | The directories passed at construction plus any auto-registered ones accepted by the predicate. Intermediate directories registered at OS level solely to propagate events are excluded. |
| `void run()` | Starts the watch loop. Intended to run on a virtual thread. Blocks until interrupted or `close()` is called. |
| `void close()` | Closes the underlying `WatchService`, unblocking `run()`. |
| `static Thread startService(Consumer<PathEvent> cb, Collection<Path> paths)` | Convenience factory: creates a service and starts it on a virtual thread. |
| `static Thread startService(Consumer<PathEvent> cb, Collection<Path> paths, Predicate<Path> autoRegister)` | As above, with automatic registration of new subdirectories. |
| `static final Predicate<Path> NONE` | The default `autoRegister`: never register anything automatically. |

### Automatic registration

`autoRegister` is a predicate rather than a flag so that it can express exclusions. Watching a tree wholesale is rarely
what you want — a blanket "watch everything below" also picks up output, archive and temp directories, and those grow
without bound.

```java
// only directories named "in", exactly two levels below root
Predicate<Path> onlyInboxes = dir ->
        dir.getFileName().toString().equals("in") && dir.getParent().getParent().equals(root);
```

A directory moved in complete with content produces a single `ENTRY_CREATE` for its top level and no events at all for
what is inside it, so a newly created directory is walked with `registerTree`, not merely registered. Even so, a file
arriving *during* that walk can be missed: watch events reduce latency, but only a periodic scan is a guarantee.

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
    <version>1.0.0</version>
</dependency>
```

## License

[MIT](https://opensource.org/license/mit/)
