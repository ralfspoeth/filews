package io.github.ralfspoeth.filews;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static java.lang.System.Logger.Level.DEBUG;
import static java.nio.file.StandardWatchEventKinds.*;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;


public class DirectoryWatchService implements Runnable, AutoCloseable {

    private final WatchService watchService;
    private final Map<WatchKey, Path> keyPathMap;
    private final Consumer<PathEvent> callback;

    public DirectoryWatchService(Consumer<PathEvent> cb, Collection<Path> paths) throws IOException {
        callback = cb.andThen(DirectoryWatchService::logDebug);
        watchService = FileSystems.getDefault().newWatchService();
        keyPathMap = paths.stream().collect(toMap(d -> watchKeyFor(d, watchService), identity()));
    }

    private static void logDebug(PathEvent pe) {
        System.getLogger(DirectoryWatchService.class.getName()).log(DEBUG, () -> pe.path() + ", " + pe.event().kind());
    }

    private static WatchKey watchKeyFor(Path p, WatchService ws) {
        try {
            return p.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private volatile boolean runnable = true;

    @Override
    public void run() {
        try (var execService = Executors.newVirtualThreadPerTaskExecutor()) {
            do {
                try {
                    // waits for the next available key
                    var key = watchService.take();

                    // may consist of multiple events
                    key.pollEvents()
                            .stream()
                            .filter(we -> Path.class.equals(we.kind().type()))
                            .map(we -> new PathEvent(keyPathMap.get(key), cast(we)))
                            .forEach(pe -> execService.submit(() -> callback.accept(pe)));

                    // reset; dir otherwise invalid
                    // stop if last key has been removed
                    if (!key.reset()) {
                        keyPathMap.remove(key);
                        if (keyPathMap.isEmpty()) {
                            runnable = false;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    runnable = false;
                }
            }
            while (runnable);
        }
    }

    public void stopWatching() {
        runnable = false;
    }

    @SuppressWarnings("unchecked")
    private static WatchEvent<Path> cast(WatchEvent<?> we) {
        return (WatchEvent<Path>) we;
    }

    @Override
    public void close() throws IOException {
        watchService.close();
    }

    /**
     * Start watch service as virtual thread.
     *
     * @param cb      callback implemented as {@link Consumer} of {@link PathEvent}s
     * @param paths a list of subdirectory paths relative to the base directory
     * @return the thread started as daemon thread
     * @throws IOException will be rethrown from {@link FileSystems} methods
     */
    public static Thread startService(Consumer<PathEvent> cb, Collection<Path> paths) throws IOException {
        return Thread.startVirtualThread(new DirectoryWatchService(cb, paths));
    }
}
