package com.whitehawk.javaformatter.integration;

import com.whitehawk.javaformatter.core.Formatter;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@NullMarked
class FormatterCasesTest {
  private final Formatter toTest = new Formatter();

  static List<Path> cases() throws IOException, URISyntaxException {
    Path root = Path.of(
      Objects
        .requireNonNull(FormatterCasesTest.class.getResource("/format-cases"))
        .toURI()
    );
    try (var stream = Files.list(root)) {
      return stream.sorted().toList();
    }
  }

  @ParameterizedTest
  @MethodSource("cases")
  void expected(Path caseDir) throws IOException {
    String before = Files.readString(caseDir.resolve("before.java"));
    String after = Files.readString(caseDir.resolve("after.java"));
    assertThat(toTest.format(before)).isEqualTo(after);
  }

  @ParameterizedTest
  @MethodSource("cases")
  void canonicalFormIsStable(Path caseDir) throws IOException {
    String after = Files.readString(caseDir.resolve("after.java"));
    assertThat(toTest.format(after)).isEqualTo(after);
  }
}
