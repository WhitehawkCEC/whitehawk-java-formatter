package com.whitehawk.javaformatter.adapter.git.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

class TrackedJavaFilesGitCliTest {
  @Test
  void streamsOnlyCommittedSourceRootJavaFiles(@TempDir Path repo) throws Exception {
    git(repo, "init");
    Files.createDirectories(repo.resolve("src/main/java"));
    Files.createDirectories(repo.resolve("module/src/test/java"));
    Files.writeString(repo.resolve("src/main/java/Tracked.java"), "class Tracked {}");
    Files.writeString(
      repo.resolve("module/src/test/java/TrackedTest.java"),
      "class TrackedTest {}"
    );
    Files.writeString(repo.resolve("Outside.java"), "class Outside {}");
    Files.writeString(repo.resolve("notes.txt"), "not java");
    Files.writeString(repo.resolve("src/main/java/Uncommitted.java"), "class Uncommitted {}");
    git(
      repo,
      "add",
      "src/main/java/Tracked.java",
      "module/src/test/java/TrackedTest.java",
      "Outside.java",
      "notes.txt"
    );
    git(
      repo,
      "-c",
      "user.email=test@example.com",
      "-c",
      "user.name=Test",
      "commit",
      "-m",
      "initial"
    );

    try (Stream<Path> tracked = new TrackedJavaFilesGitCli().list(repo)) {
      assertThat(tracked.map(TrackedJavaFilesGitCliTest::real))
        .containsExactlyInAnyOrder(
          real(repo.resolve("src/main/java/Tracked.java")),
          real(repo.resolve("module/src/test/java/TrackedTest.java"))
        );
    }
  }

  @Test
  void streamsModifiedAndUntrackedSourceRootJavaFiles(@TempDir Path repo) throws Exception {
    git(repo, "init");
    Files.createDirectories(repo.resolve("src/main/java"));
    Files.createDirectories(repo.resolve("src/test/java"));
    Files.writeString(repo.resolve("src/main/java/Modified.java"), "class Modified {}");
    Files.writeString(repo.resolve("src/main/java/Unchanged.java"), "class Unchanged {}");
    Files.writeString(repo.resolve(".gitignore"), "Ignored.java\n");
    git(
      repo,
      "add",
      "src/main/java/Modified.java",
      "src/main/java/Unchanged.java",
      ".gitignore"
    );
    git(
      repo,
      "-c",
      "user.email=test@example.com",
      "-c",
      "user.name=Test",
      "commit",
      "-m",
      "initial"
    );

    Files.writeString(repo.resolve("src/main/java/Modified.java"), "class Modified { int x; }");
    Files.writeString(repo.resolve("src/test/java/New.java"), "class New {}");
    Files.writeString(repo.resolve("Outside.java"), "class Outside {}");
    Files.writeString(repo.resolve("notes.txt"), "not java");
    Files.writeString(repo.resolve("src/main/java/Ignored.java"), "class Ignored {}");

    try (Stream<Path> changed = new TrackedJavaFilesGitCli().changed(repo)) {
      assertThat(changed.map(TrackedJavaFilesGitCliTest::real))
        .containsExactlyInAnyOrder(
          real(repo.resolve("src/main/java/Modified.java")),
          real(repo.resolve("src/test/java/New.java"))
        );
    }
  }

  private static Path real(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void git(Path cwd, String... args) throws Exception {
    List<String> command = new ArrayList<>(List.of("git", "-C", cwd.toString()));
    Collections.addAll(command, args);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
    }
  }
}
