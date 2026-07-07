package com.whitehawk.javaformatter.app.cli;

import com.whitehawk.javaformatter.core.Formatter;
import com.whitehawk.javaformatter.core.input.TrackedJavaFiles;
import io.avaje.inject.BeanScope;
import io.avaje.inject.External;

import jakarta.inject.Singleton;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

@NullMarked
public final class Main {
  public static void main(String[] args) throws Exception {
    try (BeanScope scope = BeanScope.builder().build()) {
      scope.get(Entry.class).run(args);
    }
  }

  @NullMarked
  @Singleton
  record Entry(@External Formatter formatter, @External TrackedJavaFiles trackedJavaFiles) {
    public void run(String... args) throws Exception {
      boolean changed = false;
      Path path = Path.of(".");
      for (String arg : args) {
        if (arg.equals("--changed")) {
          changed = true;
        } else {
          path = Path.of(arg);
        }
      }
      int threads = Runtime.getRuntime().availableProcessors();
      long start = System.nanoTime();
      int fileCount;
      try (
        ExecutorService executor = Executors.newFixedThreadPool(threads);
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
        fileCount = results.size();
      }
      long millis = (System.nanoTime() - start) / 1_000_000;
      System.out.printf(
        "Finished in %dms on %d files using %d threads.%n",
        millis,
        fileCount,
        threads
      );
    }

    private Void format(Path file) throws IOException {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      Files.writeString(file, formatter.format(source), StandardCharsets.UTF_8);
      return null;
    }
  }
}
