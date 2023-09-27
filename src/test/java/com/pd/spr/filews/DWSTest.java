package com.pd.spr.filews;


import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.StreamSupport;

import static java.lang.System.*;

public class DWSTest {

    private static Path tmpDir = null;

    @BeforeAll
    static void initTmpDir() throws IOException {
        tmpDir = Files.createTempDirectory(Path.of(getProperty("user.home")), "tmp");
        tmpDir.toFile().deleteOnExit();
    }

    @Test
    public void testStatic() throws IOException, InterruptedException {
        DirectoryWatchService.startService(DWSTest::checkFile, tmpDir);
        out.println("Started");
        long msecs = 20_000;
        var f = tmpDir.resolve("demo.xml");
        out.printf("File %s to be created%n", f);
        Files.createFile(f);
        out.printf("File %s created%n", f);
        if(f.toFile().delete()) {
            out.printf("File %s deleted%n", f);
        } else {
            err.printf("File %s could not be deleted%n", f);
        }
        Thread.sleep(msecs);
        out.printf("Ende nach %d msec%n", msecs);
    }

    private static final ConcurrentMap<Path, Long> paths = new ConcurrentHashMap<>();
    private static void checkFile(DirectoryWatchService.PathEvent wep) {
        out.println("from checkFile");
        var p = wep.dir().resolve(wep.event().context());
        long calls = paths.compute(p, (k, v)->v==null? 0L :v+1);
        if(calls==0) {
            Thread.startVirtualThread(()->{
                var f = p.toFile();
                long len = f.length();
                long tmp;
                while((tmp = f.length())>len) {
                    len = tmp;
                }
                out.printf("File %s%n", f);
            });
        }
    }

    private static void logWatchEvent(DirectoryWatchService.PathEvent wep) {
        out.printf("[%s] Watched %d events in %s with kind %s of type %s%n",
                LocalDateTime.now(),
                wep.event().count(),
                wep.dir().resolve(wep.event().context()),
                wep.event().kind().name(),
                wep.event().kind().type()
        );
    }


    @Test
    public void testmulti() throws IOException, InterruptedException {
        var td = Path.of(getProperty("user.home")).resolve("td");
        Files.createDirectories(td);
        Files.createDirectories(td.resolve("a"));
        Files.createDirectories(td.resolve("b"));
        DirectoryWatchService.startService(pe -> out.printf("Event %s, Folder %s, Local File %s, abs. file %s, parent %s%n",
                pe.dir(), pe.event().context(),
                pe.dir().resolve(pe.event().context()),
                StreamSupport.stream(pe.dir().resolve(pe.event().context()).spliterator(), false).toList(),
                pe.event().kind().name()
        ), td, Path.of("a"), Path.of("b"));
        Thread.sleep(Duration.ofSeconds(20));
    }
}
