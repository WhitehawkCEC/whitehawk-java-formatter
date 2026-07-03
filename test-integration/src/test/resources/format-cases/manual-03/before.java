package target;

import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@NullMarked
public final class Blah {
  private static void deleteRecursively(Path dir) throws IOException {
    try (var stream = Files.walk(dir)) {
      stream
        .sorted(Comparator.reverseOrder())
        .forEach(
          (p) -> {
            try {
              Files.delete(p);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          }
        );
    }
  }
}
