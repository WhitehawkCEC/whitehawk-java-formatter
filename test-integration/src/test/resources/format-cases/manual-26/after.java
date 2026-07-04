package example;

import unrelated.UnrelatedTrackedJavaFiles;
import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

@NullMarked
public final class TryWithLongStatements {
  private void whatever() {
    UnrelatedTrackedJavaFiles trackedJavaFiles = Scope.get(UnrelatedTrackedJavaFiles.class);
    try (
      ExecutorService executor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors()
      );
      Stream<Path> files = changed
        ? trackedJavaFiles.changed(path)
        : trackedJavaFiles.list(path)
    ) {
      List<Future<Void>> results = files
        .map((var file) -> executor.submit(() -> format(file)))
        .toList();
      for (Future<Void> result : results) {
        result.get();
      }
    }
  }
}
