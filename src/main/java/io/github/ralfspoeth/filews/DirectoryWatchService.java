package io.github.ralfspoeth.filews;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;
import static java.nio.file.StandardWatchEventKinds.*;


/**
 * Watches a set of directories and hands every event to a callback.
 * <p>
 * The set is not fixed: {@link #register(Path)} and {@link #unregister(Path)}
 * may be called at any time, including from inside the callback, since a
 * {@link WatchService} is safe for use by several threads. Directories created
 * below a watched directory can be picked up automatically by supplying an
 * {@code autoRegister} predicate.
 * <p>
 * {@link #run()} blocks until the service is closed or the running thread is
 * interrupted. It does <em>not</em> stop when the last watched directory
 * disappears - with registration being dynamic, an empty set is a normal
 * transient state rather than the end of the service.
 */
public class DirectoryWatchService implements Runnable, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DirectoryWatchService.class.getName());

    /**
     * The default: no directory is ever registered automatically.
     */
    public static final Predicate<Path> NONE = _ -> false;

    private final WatchService watchService;
    private final Map<Path, WatchKey> watchKeys = new ConcurrentHashMap<>();
    private final Consumer<PathEvent> callback;
    private final Predicate<Path> autoRegister;
    private final boolean autoRegistering;
    // paths passed explicitly at construction — always included in watched()
    private final Set<Path> initialPaths;

    public DirectoryWatchService(Consumer<PathEvent> cb, Collection<Path> paths) throws IOException {
        this(cb, paths, NONE);
    }

    /**
     * @param cb           invoked for every event, on a virtual thread
     * @param paths        the directories to watch initially
     * @param autoRegister decides whether a directory created below a watched
     *                     directory is itself watched; use {@link #NONE} for none
     */
    public DirectoryWatchService(Consumer<PathEvent> cb, Collection<Path> paths, Predicate<Path> autoRegister)
            throws IOException {
        this.callback = cb.andThen(DirectoryWatchService::logDebug);
        this.autoRegister = autoRegister;
        this.autoRegistering = autoRegister != NONE;
        this.initialPaths = Set.copyOf(paths);
        this.watchService = FileSystems.getDefault().newWatchService();
        for (var path : paths) {
            register(path);
        }
    }

    /**
     * Starts watching {@code dir}. Registering an already watched directory is a
     * no-op - the platform returns the existing key.
     */
    public void register(Path dir) throws IOException {
        var key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        watchKeys.put(dir, key);
        LOG.log(DEBUG, () -> "watching " + dir);
    }

    /**
     * Registers {@code root} and every directory below it accepted by the
     * {@code autoRegister} predicate.
     * <p>
     * Needed because a directory that is moved in complete with content produces
     * a single event for its top level and none for anything inside it; also
     * useful to pick up directories that already exist at startup.
     */
    public void registerTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.equals(root) || autoRegister.test(dir)) {
                    register(dir);
                }
                // descend regardless: a directory that is not itself watched may
                // still contain one that should be
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Stops watching {@code dir}.
     *
     * @return whether the directory had been watched
     */
    public boolean unregister(Path dir) {
        var key = watchKeys.remove(dir);
        if (key == null) {
            return false;
        }
        key.cancel();
        LOG.log(DEBUG, () -> "stopped watching " + dir);
        return true;
    }

    /**
     * The directories currently reported as watched: those passed at construction
     * plus any auto-registered directories accepted by the predicate. Intermediate
     * directories registered solely to observe events inside them are excluded.
     */
    public Set<Path> watched() {
        if (!autoRegistering) {
            return Set.copyOf(watchKeys.keySet());
        }
        return watchKeys.keySet().stream()
                .filter(p -> initialPaths.contains(p) || autoRegister.test(p))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void logDebug(PathEvent pe) {
        LOG.log(DEBUG, () -> pe.path() + ", " + pe.event().kind());
    }

    @Override
    public void run() {
        // try-with-resources ensures all submitted callbacks complete before run() returns
        try (var execService = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                try {
                    // waits for the next available key
                    var key = watchService.take();
                    // the registered directory, straight from the key - no lookup,
                    // so a concurrent register() cannot race us to it
                    var dir = (Path) key.watchable();

                    // may consist of multiple events
                    for (var we : key.pollEvents()) {
                        if (OVERFLOW.equals(we.kind())) {
                            LOG.log(WARNING, () -> "event overflow in " + dir
                                    + "; events were lost and only a full scan can recover them");
                            continue;
                        }
                        if (!Path.class.equals(we.kind().type())) {
                            continue;
                        }
                        var pe = new PathEvent(dir, cast(we));
                        if (autoRegistering && ENTRY_CREATE.equals(we.kind())) {
                            autoRegister(pe.path());
                        }
                        // skip anything unregistered while the events were queued
                        if (watchKeys.containsKey(dir)) {
                            execService.submit(() -> callback.accept(pe));
                        }
                    }

                    // reset; dir otherwise invalid
                    if (!key.reset()) {
                        watchKeys.remove(dir, key);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ClosedWatchServiceException e) {
                    break;
                }
            }
        }
    }

    private void autoRegister(Path created) {
        try {
            // guards the walk: most created entries are plain files
            if (Files.isDirectory(created)) {
                registerTree(created);
            }
        } catch (IOException e) {
            // the entry may already be gone again; never let it kill the loop
            LOG.log(WARNING, () -> "could not register " + created + ": " + e);
        }
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
     * @param cb    callback implemented as {@link Consumer} of {@link PathEvent}s
     * @param paths the directories to watch
     * @return the virtual thread running the service
     * @throws IOException will be rethrown from {@link FileSystems} methods
     */
    public static Thread startService(Consumer<PathEvent> cb, Collection<Path> paths) throws IOException {
        return startService(cb, paths, NONE);
    }

    /**
     * Start watch service as virtual thread, registering new subdirectories
     * accepted by {@code autoRegister} as they appear.
     *
     * @return the virtual thread running the service
     */
    public static Thread startService(Consumer<PathEvent> cb, Collection<Path> paths, Predicate<Path> autoRegister)
            throws IOException {
        return Thread.startVirtualThread(new DirectoryWatchService(cb, paths, autoRegister));
    }
}
