package io.github.ralfspoeth.filews;

import java.nio.file.Path;
import java.nio.file.WatchEvent;

public record PathEvent(Path dir, WatchEvent<Path> event) {
    /**
     * The full path to the created, modified or deleted file.
     *
     * @return dir().resolve(event.context())
     */
    public Path path() {
        return dir.resolve(event.context());
    }
}
