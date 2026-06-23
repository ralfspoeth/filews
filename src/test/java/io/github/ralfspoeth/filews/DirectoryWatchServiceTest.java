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
