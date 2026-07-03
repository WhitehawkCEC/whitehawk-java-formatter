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
    public void run(String... args) throws IOException {
      Path path = Path.of(args.length > 0 ? args[0] : ".");
      try (Stream<Path> files = trackedJavaFiles.list(path)) {
        for (Path file : (Iterable<Path>) files::iterator) {
          format(file);
        }
      }
    }

    private void format(Path file) throws IOException {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      Files.writeString(file, formatter.format(source), StandardCharsets.UTF_8);
    }
  }
}
