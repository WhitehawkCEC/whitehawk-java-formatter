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
  void streamsOnlyCommittedJavaFiles(@TempDir Path repo) throws Exception {
    git(repo, "init");
    Files.writeString(repo.resolve("Tracked.java"), "class Tracked {}");
    Files.writeString(repo.resolve("notes.txt"), "not java");
    Files.writeString(repo.resolve("Uncommitted.java"), "class Uncommitted {}");
    git(repo, "add", "Tracked.java", "notes.txt");
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
        .containsExactly(real(repo.resolve("Tracked.java")));
    }
  }

  @Test
  void streamsModifiedAndUntrackedJavaFiles(@TempDir Path repo) throws Exception {
    git(repo, "init");
    Files.writeString(repo.resolve("Modified.java"), "class Modified {}");
    Files.writeString(repo.resolve("Unchanged.java"), "class Unchanged {}");
    Files.writeString(repo.resolve(".gitignore"), "Ignored.java\n");
    git(repo, "add", "Modified.java", "Unchanged.java", ".gitignore");
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

    Files.writeString(repo.resolve("Modified.java"), "class Modified { int x; }");
    Files.writeString(repo.resolve("New.java"), "class New {}");
    Files.writeString(repo.resolve("notes.txt"), "not java");
    Files.writeString(repo.resolve("Ignored.java"), "class Ignored {}");

    try (Stream<Path> changed = new TrackedJavaFilesGitCli().changed(repo)) {
      assertThat(changed.map(TrackedJavaFilesGitCliTest::real))
        .containsExactlyInAnyOrder(
          real(repo.resolve("Modified.java")),
          real(repo.resolve("New.java"))
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
