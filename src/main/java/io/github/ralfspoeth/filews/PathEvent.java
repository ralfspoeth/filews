package io.github.ralfspoeth.filews;

import java.nio.file.Path;
import java.nio.file.WatchEvent;

public record PathEvent(Path dir, WatchEvent<Path> event) {
}
