package ru.sbespalko;

import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class PathDispatcher {
  private final BidiMap<Path, Path> originalMap;
  private final BidiMap<Path, Path> pathsMap;

  public PathDispatcher(Map<Path, Path> pathsMap, boolean recursive) {
    originalMap = new DualHashBidiMap<>();
    pathsMap.forEach(this.originalMap::put);
    this.pathsMap = new DualHashBidiMap<>();
    pathsMap.forEach(this::registerPaths);
  }

  public Path getSymmetricPath(Path path) {
    //проверять изменения на другой стороне на идентичность файлов/папок, чтобы не попасть в цикл
    Path symmetricDir = pathsMap.get(path);
    if (symmetricDir == null) {
      symmetricDir = pathsMap.getKey(path);
    }
    if (symmetricDir == null) {
      throw new RuntimeException("not found symmetry for " + path);
    }
    return symmetricDir;
  }

  public void registerPaths(Path root1, Path root2) {
    registerPaths(root1, root2, (x, y) -> {
      pathsMap.put(x, y);
      return null;
    });
  }

  public void registerPaths(Path root1) {
    Path root2 = pathsMap.get(root1.getParent());
    if(root2 == null) {
      root2 = pathsMap.getKey(root1.getParent());
    }
    if (root2 == null) {
      throw new RuntimeException("not found symmetry for " + root1);
    }
    registerPaths(root1, root2.resolve(root1.getFileName()));

  }

  public void unregisterPaths(Path root1, Path root2) {
    registerPaths(root1, root2, (x, y) -> {
      pathsMap.remove(x, y);
      return null;
    });
  }

  public void unregisterPaths(Path root1) {
    Path root2 = pathsMap.get(root1);
    if(root2 == null) {
      root2 = pathsMap.getKey(root1);
    }
    if (root2 == null) {
      throw new RuntimeException("not found symmetry for " + root1);
    }
    unregisterPaths(root1, root2);
  }

  private void registerPaths(Path root1, Path root2, BiFunction<Path, Path, Void> func) {
    try {
      func.apply(root1, root2);
      List<Path> subDirs1 = getFirstLevelRelativeSubdirs(root1);
      List<Path> subDirs2 = getFirstLevelRelativeSubdirs(root2);
      ListIterator<Path> iter1 = subDirs1.listIterator();
      ListIterator<Path> iter2 = subDirs2.listIterator();
      while (iter1.hasNext() && iter2.hasNext()) {
        Path path1 = iter1.next();
        Path path2 = iter2.next();
        if (path1.equals(path2)) {
          registerPaths(root1.resolve(path1), root2.resolve(path2), func);
          continue;
        }
        if (path1.compareTo(path2) < 0) {
          registerPaths(root1.resolve(path1), root2.resolve(path1), func);
          iter2.previous();
        } else {
          registerPaths(root1.resolve(path2), root2.resolve(path2), func);
          iter1.previous();
        }
      }
      extraPaths(root1, root2, iter1, func);
      extraPaths(root1, root2, iter2, func);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private void extraPaths(Path root1, Path root2, ListIterator<Path> iter, BiFunction<Path, Path, Void> func) {
    while (iter.hasNext()) {
      Path path = iter.next();
      registerPaths(root1.resolve(path), root2.resolve(path), func);
    }
  }

  List<Path> getFirstLevelRelativeSubdirs(Path root) throws IOException {
    if (!Files.exists(root)) {
      return new ArrayList<>();
    }
    return Files.list(root)
                .filter(Files::isDirectory)
                .map(root::relativize)
                .sorted()
                .collect(Collectors.toList());
  }
}
