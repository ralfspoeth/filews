package com.pd.spr.filews;


import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

public class DWSTest {

    private static Path tmpDir = null;

    @BeforeAll
    static void initTmpDir() throws IOException {
        tmpDir = Files.createTempDirectory(Path.of(System.getProperty("user.home")), "tmp");
    }

    @Test
    public void testStatic() throws IOException, InterruptedException {
        var t = DirectoryWatchService.startService(DWSTest::logWatchEvent, tmpDir);
        System.out.println("Started");
        long msecs = 60_000;
        var f = tmpDir.resolve("demo.xml");
        Files.createFile(f);
        Thread.sleep(msecs);
        System.out.printf("Ende nach %d msec%n", msecs);
    }

    private static void logWatchEvent(DirectoryWatchService.PathEvent wep) {
        System.out.printf("[%s] Watched %d events in %s with kind %s of type %s%n",
                LocalDateTime.now(),
                wep.event().count(),
                wep.dir().resolve(wep.event().context()),
                wep.event().kind().name(),
                wep.event().kind().type()
        );
    }
}
