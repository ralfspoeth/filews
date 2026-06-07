package io.github.ralfspoeth.filews;


import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.StreamSupport;

import static java.lang.System.*;
import static java.nio.file.StandardWatchEventKinds.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryWatchServiceTest {

    private static Path tmpDir = null;

    @BeforeAll
    static void initTmpDir() throws IOException {
        tmpDir = Files.createTempDirectory(Path.of(getProperty("user.home")), "tmp");
        tmpDir.toFile().deleteOnExit();
    }

    @Test
    void testStatic() throws IOException, InterruptedException {
        var events = new CopyOnWriteArrayList<PathEvent>();
        var _ = DirectoryWatchService.startService(events::add, List.of(tmpDir));
        var f = tmpDir.resolve("demo.xml");
        Files.createFile(f);
        f.toFile().delete();
        Thread.sleep(2_000);

        assertTrue(events.stream().anyMatch(e -> e.path().equals(f) && e.event().kind() == ENTRY_CREATE),
                "expected ENTRY_CREATE event for " + f);
        assertTrue(events.stream().anyMatch(e -> e.path().equals(f) && e.event().kind() == ENTRY_DELETE),
                "expected ENTRY_DELETE event for " + f);
    }

    private static final ConcurrentMap<Path, Long> paths = new ConcurrentHashMap<>();
    private static void checkFile(PathEvent wep) {
        out.println("from checkFile");
        var p = wep.dir().resolve(wep.event().context());
        long calls = paths.compute(p, (ignore, v)->v==null? 0L :v+1);
        if(calls==0) {
            Thread.startVirtualThread(()->{
                var f = p.toFile();
                long len = f.length();
                long tmp;
                while((tmp = f.length())!=len) {
                    len = tmp;
                }
                out.printf("File %s%n", f);
            });
        }
    }

    private static void logWatchEvent(PathEvent wep) {
        out.printf("[%s] Watched %d events in %s with kind %s of type %s%n",
                LocalDateTime.now(),
                wep.event().count(),
                wep.dir().resolve(wep.event().context()),
                wep.event().kind().name(),
                wep.event().kind().type()
        );
    }


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
        Files.walkFileTree(td, new SimpleFileVisitor<>(){
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if(exc==null) {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
                else {
                    throw exc;
                }
            }
        });
        t.join();
    }
}
