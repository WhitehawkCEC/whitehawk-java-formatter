package com.whitehawk.javaformatter.adapter.eclipse.jgit;

import com.whitehawk.javaformatter.core.TrackedJavaFiles;

import jakarta.inject.Singleton;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/// [TrackedJavaFiles] backed by JGit's index (`DirCache`).
@Singleton
@NullMarked
public final class TrackedJavaFilesJgit implements TrackedJavaFiles {
  @Override
  public Stream<Path> list(Path path) {
    // The index is read fully into memory here, so the repository can be closed before the
    // (lazy) stream is consumed -- iterating DirCacheEntry needs no open repository.
    DirCache index;
    Path workTree;
    try (Git git = Git.open(path.toFile())) {
      Repository repository = git.getRepository();
      workTree = repository.getWorkTree().toPath();
      index = repository.readDirCache();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read git index at " + path, e);
    }

    return IntStream.range(0, index.getEntryCount())
        .mapToObj(index::getEntry)
        .map(DirCacheEntry::getPathString)
        .filter(entryPath -> entryPath.endsWith(".java"))
        .map(workTree::resolve);
  }
}
