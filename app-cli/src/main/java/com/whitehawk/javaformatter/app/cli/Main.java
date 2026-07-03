package com.whitehawk.javaformatter.app.cli;

import com.whitehawk.javaformatter.core.Formatter;
import com.whitehawk.javaformatter.core.TrackedJavaFiles;
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
      try (ExecutorService executor =
              Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
          Stream<Path> files =
              changed ? trackedJavaFiles.changed(path) : trackedJavaFiles.list(path)) {
        List<Future<Void>> results =
            files.map(file -> executor.submit(() -> format(file))).toList();
        for (Future<Void> result : results) {
          result.get();
        }
      }
    }

    private Void format(Path file) throws IOException {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      Files.writeString(file, formatter.format(source), StandardCharsets.UTF_8);
      return null;
    }
  }
}
