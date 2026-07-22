package io.github.ralfspoeth.filews;


import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.StreamSupport;

import static java.lang.System.getProperty;
import static java.lang.System.out;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryWatchServiceTest {

    private static Path tmpDir = null;

    @BeforeAll
    static void initTmpDir() throws IOException {
        tmpDir = Files.createTempDirectory(Path.of(getProperty("user.home")), "tmp");
        tmpDir.toFile().deleteOnExit();
    }

    @Test
    @Timeout(10)
    void testStatic() throws IOException, InterruptedException {
        var events = new ConcurrentLinkedQueue<PathEvent>();
        var _ = DirectoryWatchService.startService(events::add, List.of(tmpDir));
        var f = tmpDir.resolve("demo.xml");
        var cf = Files.createFile(f);
        while(events.isEmpty()) {
            Thread.sleep(Duration.ofMillis(10));
        }
        Files.delete(cf);
        while(events.size()<2) {
            Thread.sleep(Duration.ofMillis(10));
        }
        assertAll(
                () -> assertTrue(events.stream().anyMatch(e -> e.event().kind() == ENTRY_CREATE)),
                () -> assertTrue(events.stream().anyMatch(e -> e.event().kind() == ENTRY_DELETE)),
                () -> assertTrue(events.stream().allMatch(e -> e.path().endsWith("demo.xml")))
        );
    }

    /**
     * A directory created below a watched one is picked up when the predicate
     * accepts it, and ignored when it does not.
     */
    @Test
    @Timeout(10)
    void testAutoRegister() throws IOException, InterruptedException {
        var root = Files.createTempDirectory(tmpDir, "auto");
        var events = new ConcurrentLinkedQueue<PathEvent>();
        // only directories named "in" are to be watched
        try (var ws = new DirectoryWatchService(events::add, List.of(root),
                dir -> dir.getFileName().toString().equals("in"))) {
            var thread = Thread.ofVirtual().start(ws);

            var feed = Files.createDirectory(root.resolve("feed"));
            var in = Files.createDirectory(feed.resolve("in"));
            var archive = Files.createDirectory(feed.resolve("archive"));
            while (!ws.watched().contains(in)) {
                Thread.sleep(Duration.ofMillis(10));
            }
            assertAll(
                    () -> assertTrue(ws.watched().contains(in), "in should be watched"),
                    () -> assertFalse(ws.watched().contains(archive), "archive should not be watched"),
                    () -> assertFalse(ws.watched().contains(feed), "feed itself should not be watched")
            );

            // a file in the newly registered directory must now be reported
            var arrived = Files.createFile(in.resolve("data.csv"));
            while (events.stream().noneMatch(e -> e.path().equals(arrived))) {
                Thread.sleep(Duration.ofMillis(10));
            }

            assertTrue(ws.unregister(in));
            assertFalse(ws.watched().contains(in));
            assertFalse(ws.unregister(in), "unregistering twice reports false");

            thread.interrupt();
            thread.join();
        }
    }

    /**
     * A directory moved in complete with content announces only itself, so
     * registerTree has to walk it.
     */
    @Test
    @Timeout(10)
    void testMovedInTreeIsWalked() throws IOException, InterruptedException {
        var root = Files.createTempDirectory(tmpDir, "moved");
        var staging = Files.createTempDirectory(tmpDir, "staging");
        var _ = Files.createDirectories(staging.resolve("feed/in"));

        var events = new ConcurrentLinkedQueue<PathEvent>();
        try (var ws = new DirectoryWatchService(events::add, List.of(root),
                dir -> dir.getFileName().toString().equals("in"))) {
            var thread = Thread.ofVirtual().start(ws);

            // one ENTRY_CREATE for "feed" only - nothing for feed/in
            Files.move(staging.resolve("feed"), root.resolve("feed"));
            var in = root.resolve("feed/in");
            while (!ws.watched().contains(in)) {
                Thread.sleep(Duration.ofMillis(10));
            }
            assertTrue(ws.watched().contains(in));

            thread.interrupt();
            thread.join();
        }
    }

    // Exploratory test: verifies that create, move, and delete events across multiple
    // watched directories produce console output without throwing exceptions.
    // No assertions — correctness is checked by inspection of the printed output.
    @Test
    void testmulti() throws IOException, InterruptedException {
        Path td = Files.createDirectories(Path.of(getProperty("user.home")).resolve("td"));
        Path a = Files.createDirectories(td.resolve("a"));
        Path b = Files.createDirectories(td.resolve("b"));
        var ds = new DirectoryWatchService(pe -> out.printf(
                "Event dir: %s, event context: %s, context relative to dir: %s, path elements: %s, event-kind-name: %s%n",
                pe.dir(), pe.event().context(),
                pe.dir().resolve(pe.event().context()),
                StreamSupport.stream(pe.dir().resolve(pe.event().context()).spliterator(), false).toList(),
                pe.event().kind().name()
        ), List.of(td, a, b));
        var t = Thread.ofVirtual().start(ds);
        Thread.sleep(Duration.ofMillis(500));
        Files.createFile(td.resolve(a).resolve("one.txt"));
        Files.move(td.resolve(a).resolve("one.txt"), td.resolve(b).resolve("two.txt"));
        Files.delete(td.resolve(b).resolve("two.txt"));
        t.interrupt();
        Files.walkFileTree(td, new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult postVisitDirectory(@NonNull Path dir, @Nullable IOException exc) throws IOException {
                if (exc == null) {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                } else {
                    throw exc;
                }
            }
        });
        t.join();
    }
}
