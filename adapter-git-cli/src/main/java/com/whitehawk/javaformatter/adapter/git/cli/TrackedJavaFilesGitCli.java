package com.whitehawk.javaformatter.adapter.git.cli;

import com.whitehawk.javaformatter.core.input.TrackedJavaFiles;

import jakarta.inject.Singleton;
import org.jspecify.annotations.NullMarked;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// [TrackedJavaFiles] backed by the `git` CLI (`git ls-tree` over the `HEAD` tree).
///
/// Requires `git` on the `PATH`. Names are read NUL-delimited straight off the subprocess's stdout
/// and streamed one at a time, so memory stays bounded by the current record rather than the total
/// tracked-file count. The returned stream owns the subprocess and terminates it via
/// [Stream#onClose]; callers must close the stream.
@Singleton
@NullMarked
public final class TrackedJavaFilesGitCli implements TrackedJavaFiles {
  @Override
  public Stream<Path> list(Path path) {
    // Fails (throwing) when `path` is not inside a work tree, matching a git-library open.
    Path workTree = Path.of(run(path, "rev-parse", "--show-toplevel"));
    if (!succeeds(path, "rev-parse", "--verify", "--quiet", "HEAD")) {
      // Unborn HEAD: no commit, so nothing is tracked.
      return Stream.empty();
    }

    Process process = start(
      path,
      false,
      "ls-tree",
      "-r",
      "-z",
      "--full-tree",
      "--name-only",
      "HEAD"
    );
    InputStream stdout = new BufferedInputStream(process.getInputStream());
    NulRecordSpliterator records = new NulRecordSpliterator(stdout);
    return StreamSupport
      .stream(records, false)
      .filter(TrackedJavaFilesGitCli::underSourceRoot)
      .map(workTree::resolve)
      .onClose(() -> finish(process, records, stdout));
  }

  @Override
  public Stream<Path> changed(Path path) {
    // Fails (throwing) when `path` is not inside a work tree, matching a git-library open.
    Path workTree = Path.of(run(path, "rev-parse", "--show-toplevel"));

    // Run from the work-tree root so names are repo-root-relative and resolve against it.
    // -m: tracked files modified in the work tree; -o: untracked files;
    // --exclude-standard: drop anything git ignores.
    Process process = start(workTree, false, "ls-files", "-z", "-m", "-o", "--exclude-standard");
    InputStream stdout = new BufferedInputStream(process.getInputStream());
    NulRecordSpliterator records = new NulRecordSpliterator(stdout);
    return StreamSupport
      .stream(records, false)
      .filter(TrackedJavaFilesGitCli::underSourceRoot)
      .map(workTree::resolve)
      // `ls-files -m` also reports deletions; skip anything no longer on disk.
      .filter(Files::exists)
      .onClose(() -> finish(process, records, stdout));
  }

  /// Restricts to `**/src/main/java/**.java` and `**/src/test/java/**.java`, where the leading
  /// `**/` also matches the repository root. Filtered here rather than via a git pathspec because
  /// `ls-tree` does not support wildcard pathspecs.
  private static boolean underSourceRoot(String name) {
    if (!name.endsWith(".java")) {
      return false;
    }
    String rooted = "/" + name;
    return rooted.contains("/src/main/java/") || rooted.contains("/src/test/java/");
  }

  /// Runs `git` to completion and returns its trimmed stdout, throwing on a non-zero exit.
  private static String run(Path cwd, String... args) {
    Process process = start(cwd, true, args);
    try (InputStream stdout = process.getInputStream()) {
      String output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
      int exit = process.waitFor();
      if (exit != 0) {
        throw new UncheckedIOException(
          new IOException(
            "git " + String.join(" ", args) + " failed (exit " + exit + "): "
              + output.strip()
          )
        );
      }
      return output.strip();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot run git " + String.join(" ", args), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted running git " + String.join(" ", args), e);
    }
  }

  /// Runs `git` to completion, discarding output, and reports whether it exited zero.
  private static boolean succeeds(Path cwd, String... args) {
    Process process = start(cwd, true, args);
    try (InputStream stdout = process.getInputStream()) {
      stdout.readAllBytes();
      return process.waitFor() == 0;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot run git " + String.join(" ", args), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted running git " + String.join(" ", args), e);
    }
  }

  private static Process start(Path cwd, boolean mergeError, String... args) {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.add("-C");
    command.add(cwd.toString());
    for (String arg : args) {
      command.add(arg);
    }
    ProcessBuilder builder = new ProcessBuilder(command);
    // Merged into stdout so error text surfaces in exceptions; discarded for ls-tree so stderr
    // cannot corrupt the NUL-delimited name stream.
    if (mergeError) {
      builder.redirectErrorStream(true);
    } else {
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    }
    try {
      return builder.start();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot start git", e);
    }
  }

  private static void finish(Process process, NulRecordSpliterator records, InputStream stdout) {
    IOException closeError = null;
    try {
      stdout.close();
    } catch (IOException e) {
      closeError = e;
    }

    if (records.drained) {
      try {
        int exit = process.waitFor();
        if (closeError == null && exit != 0) {
          throw new UncheckedIOException(
            new IOException("git ls-tree failed with exit code " + exit)
          );
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted awaiting git", e);
      }
    } else {
      // Caller closed the stream early; abandon the subprocess rather than block draining it.
      process.destroy();
    }

    if (closeError != null) {
      throw new UncheckedIOException("Cannot close git output", closeError);
    }
  }

  /// Pulls one NUL-delimited name at a time off the subprocess stdout, so the stream stays lazy.
  private static final class NulRecordSpliterator extends Spliterators.AbstractSpliterator<String> {
    private final InputStream in;
    private volatile boolean drained;

    NulRecordSpliterator(InputStream in) {
      super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
      this.in = in;
    }

    @Override
    public boolean tryAdvance(Consumer<? super String> action) {
      try {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0) {
          record.write(b);
        }
        if (b == -1 && record.size() == 0) {
          drained = true;
          return false;
        }
        action.accept(record.toString(StandardCharsets.UTF_8));
        if (b == -1) {
          // Final record was not NUL-terminated; there is nothing left to read.
          drained = true;
        }
        return true;
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot read git output", e);
      }
    }
  }
}
