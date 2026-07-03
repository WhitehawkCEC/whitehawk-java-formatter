package com.whitehawk.javaformatter.adapter.eclipse.jgit;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

class TrackedJavaFilesJgitTest {
  @Test
  void streamsOnlyCommittedJavaFiles(@TempDir Path repo) throws Exception {
    try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
      Files.writeString(repo.resolve("Tracked.java"), "class Tracked {}");
      Files.writeString(repo.resolve("notes.txt"), "not java");
      Files.writeString(repo.resolve("Uncommitted.java"), "class Uncommitted {}");
      git.add().addFilepattern("Tracked.java").addFilepattern("notes.txt").call();
      git.commit()
          .setMessage("initial")
          .setAuthor("Test", "test@example.com")
          .setCommitter("Test", "test@example.com")
          .call();
    }

    try (Stream<Path> tracked = new TrackedJavaFilesJgit().list(repo)) {
      assertThat(tracked).containsExactly(repo.resolve("Tracked.java"));
    }
  }
}
