package com.whitehawk.javaformatter.app.maven;

import com.whitehawk.javaformatter.core.Formatter;
import com.whitehawk.javaformatter.core.TrackedJavaFiles;
import io.avaje.inject.BeanScope;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/// Formats a selection of the project's `.java` files in place; subclasses pick the selection via
/// [#files].
///
/// [Formatter] and [TrackedJavaFiles] are resolved from an avaje [BeanScope] built off this
/// plugin's realm, mirroring the CLI wiring: `core` provides the formatter and the
/// `maven-deps-adapters` set provides the git-backed file enumeration.
@NullMarked
abstract class AbstractFormatMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  /// Skip execution entirely.
  @Parameter(property = "javaformatter.skip", defaultValue = "false")
  private boolean skip;

  /// The files to format.
  abstract Stream<Path> files(TrackedJavaFiles trackedJavaFiles, Path path);

  @Override
  public final void execute() throws MojoExecutionException {
    if (skip) {
      getLog().info("Skipping (javaformatter.skip=true)");
      return;
    }

    Path path = project.getBasedir().toPath();
    try (BeanScope scope = BeanScope.builder().classLoader(getClass().getClassLoader()).build()) {
      Formatter formatter = scope.get(Formatter.class);
      TrackedJavaFiles trackedJavaFiles = scope.get(TrackedJavaFiles.class);
      try (
        ExecutorService executor = Executors.newFixedThreadPool(
          Runtime.getRuntime().availableProcessors()
        );
        Stream<Path> files = files(trackedJavaFiles, path)
      ) {
        List<Future<Void>> results = files
          .map((var file) -> executor.submit(() -> format(formatter, file)))
          .toList();
        for (Future<Void> result : results) {
          result.get();
        }
      }
    } catch (ExecutionException e) {
      throw new MojoExecutionException("Failed to format", e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MojoExecutionException("Interrupted while formatting", e);
    }
  }

  private static Void format(Formatter formatter, Path file) {
    try {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      Files.writeString(file, formatter.format(source), StandardCharsets.UTF_8);
      return null;
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot format " + file, e);
    }
  }
}
