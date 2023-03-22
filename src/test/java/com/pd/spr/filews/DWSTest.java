package com.pd.spr.filews;


import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

public class DWSTest {

    @Test
    public void testStatic() throws IOException {
        var tmpDir = Files.createTempDirectory(Path.of(System.getProperty("user.home")), "tmp");
        DirectoryWatchService.startService(DWSTest::logWatchEvent, tmpDir);
        System.out.println("Started");
        var f = tmpDir.resolve("demo.xml");
        System.out.printf("File %s to be created%n", f);
        Files.createFile(f);
        System.out.printf("File %s created%n", f);
        if(!Files.deleteIfExists(f)) {
            System.err.printf("File %s could not be deleted%n", f);
        }
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
