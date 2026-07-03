package com.whitehawk.javaformatter.adapter.eclipse.jgit;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class TrackedJavaFilesJgitTest {
  @Test
  void listsOnlyTrackedJavaFiles(@TempDir Path repo) throws Exception {
    try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
      Files.writeString(repo.resolve("Tracked.java"), "class Tracked {}");
      Files.writeString(repo.resolve("notes.txt"), "not java");
      Files.writeString(repo.resolve("Untracked.java"), "class Untracked {}");
      git.add().addFilepattern("Tracked.java").addFilepattern("notes.txt").call();
    }

    assertThat(new TrackedJavaFilesJgit().list(repo))
        .containsExactly(repo.resolve("Tracked.java"));
  }
}
