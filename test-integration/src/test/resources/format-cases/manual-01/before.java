package com.whitehawk.javaformatter.integration;

import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/// Runs the generated `format-cases` resources: each case holds an `after.java` in canonical
/// style and a `before.java` with scrambled whitespace.
@NullMarked
class FormatterCasesTest {
  static List<Path> cases() throws IOException, URISyntaxException {
    Path root = Path.of(Objects
      .requireNonNull(
        FormatterCasesTest.class.getResource("/format-cases"),
        "format-cases resources are missing; run GenerateFormatCases"
      )
      .toURI());
    try (var stream = Files.list(root)) {
      return stream.sorted().toList();
    }
  }
}
