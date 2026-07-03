package com.whitehawk.javaformatter.adapter.eclipse.jgit;

import com.whitehawk.javaformatter.core.TrackedJavaFiles;

import jakarta.inject.Singleton;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/// [TrackedJavaFiles] backed by a JGit `TreeWalk` over the `HEAD` tree.
///
/// The walk reads tree objects incrementally from the object database, so memory stays bounded by
/// the current directory depth rather than the total tracked-file count. The returned stream keeps
/// the repository open and closes it via [Stream#onClose]; callers must close the stream.
@Singleton
@NullMarked
public final class TrackedJavaFilesJgit implements TrackedJavaFiles {
  @Override
  public Stream<Path> list(Path path) {
    Git git = null;
    RevWalk revWalk = null;
    TreeWalk treeWalk = null;
    try {
      git = Git.open(path.toFile());
      Repository repository = git.getRepository();
      Path workTree = repository.getWorkTree().toPath();

      ObjectId head = repository.resolve(Constants.HEAD);
      if (head == null) {
        // No commits yet: nothing is tracked in HEAD.
        git.close();
        return Stream.empty();
      }

      revWalk = new RevWalk(repository);
      RevCommit commit = revWalk.parseCommit(head);

      treeWalk = new TreeWalk(repository);
      treeWalk.addTree(commit.getTree());
      treeWalk.setRecursive(true);
      treeWalk.setFilter(PathSuffixFilter.create(".java"));

      Git ownerGit = git;
      RevWalk ownerWalk = revWalk;
      TreeWalk walk = treeWalk;
      return StreamSupport.stream(new PathSpliterator(walk), false)
          .map(workTree::resolve)
          .onClose(() -> {
            walk.close();
            ownerWalk.close();
            ownerGit.close();
          });
    } catch (IOException e) {
      if (treeWalk != null) {
        treeWalk.close();
      }
      if (revWalk != null) {
        revWalk.close();
      }
      if (git != null) {
        git.close();
      }
      throw new UncheckedIOException("Cannot walk git tree at " + path, e);
    }
  }

  /// Pulls one path at a time off a recursive `TreeWalk`, so the stream stays lazy.
  private static final class PathSpliterator extends Spliterators.AbstractSpliterator<String> {
    private final TreeWalk treeWalk;

    PathSpliterator(TreeWalk treeWalk) {
      super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
      this.treeWalk = treeWalk;
    }

    @Override
    public boolean tryAdvance(Consumer<? super String> action) {
      try {
        if (!treeWalk.next()) {
          return false;
        }
        action.accept(treeWalk.getPathString());
        return true;
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot walk git tree", e);
      }
    }
  }
}
