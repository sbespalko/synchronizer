package ru.sbespalko;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Example to watch a directory (or tree) for changes to files.
 */

public class DirWatcher {
  private static final Logger log = LogManager.getLogger();

  private final Map<Path, Path> pathsMap;
  private final WatchService watcher;
  private final Map<WatchKey, Path> keys;
  private final boolean recursive;
  private EventHandler eventHandler;
  private boolean trace;

  @SuppressWarnings("unchecked")
  static <T> WatchEvent<T> cast(WatchEvent<?> event) {
    return (WatchEvent<T>) event;
  }

  public EventHandler getEventHandler() {
    return eventHandler;
  }

  public void setEventHandler(EventHandler eventHandler) {
    this.eventHandler = eventHandler;
  }

  /**
   * Register the given directory with the WatchService
   */
  private void register(Path dir) throws IOException {
    WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
    if (trace) {
      Path prev = keys.get(key);
      if (prev == null) {
        log.info("Register: {}", dir);
      } else {
        if (!dir.equals(prev)) {
          log.info("Update: {} -> {}", prev, dir);
        }
      }
    }
    keys.put(key, dir);
  }

  /**
   * Register the given directory, and all its sub-directories, with the
   * WatchService.
   */
  private void registerDirTree(final Path start) throws IOException {
    // register directory and sub-directories
    Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
          throws IOException {
        register(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /**
   * Creates a WatchService and registers the given directory
   */
  public DirWatcher(Map<Path, Path> pathsMap, boolean recursive) throws IOException {
    this.pathsMap = pathsMap;
    this.watcher = FileSystems.getDefault()
                              .newWatchService();
    this.keys = new HashMap<WatchKey, Path>();
    this.recursive = recursive;

    List<Path> paths = new ArrayList<>(pathsMap.keySet());
    paths.addAll(pathsMap.values());
    if (recursive) {
      for (Path path : paths) {
        log.info("Register DirTree {}", path);
        registerDirTree(path);
      }
    } else {
      for (Path path : paths) {
        log.info("Register Dir {}", path);
        register(path);
      }
    }
    log.info("Done.");

    // enable trace after initial registration
    this.trace = true;
  }

  /**
   * Process all events for keys queued to the watcher
   */
  void start() throws IOException {
    for (; ; ) {

      // wait for key to be signalled
      WatchKey key;
      try {
        key = watcher.take();
      } catch (InterruptedException x) {
        return;
      }

      Path dir = keys.get(key);
      if (dir == null) {
        log.error("WatchKey not recognized!!");
        continue;
      }

      for (WatchEvent<?> event : key.pollEvents()) {
        WatchEvent.Kind kind = event.kind();

        // TBD - provide example of how OVERFLOW event is handled
        if (kind == OVERFLOW) {
          continue;
        }

        // Context for directory entry event is the file name of entry
        WatchEvent<Path> ev = cast(event);
        Path name = ev.context();
        Path path = dir.resolve(name);

        // print out event
        Path symmetricPath = getSymmetricPath(dir).resolve(name);
        CompletableFuture.runAsync(() -> {
          try {
            eventHandler.processEvent(path, symmetricPath, ev.kind()
                                                             .name());
          } catch (IOException e) {
            resync(path, symmetricPath);
          }
        });
        log.debug("{}: {}", event.kind()
                                           .name(), path);

        // if directory is created, and watching recursively, then
        // register it and its sub-directories
        if (recursive && (kind == ENTRY_CREATE)) {
          try {
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
              registerDirTree(path);
            }
          } catch (IOException x) {
            // ignore to keep sample readbale
          }
        }
      }

      // reset key and remove from set if directory no longer accessible
      boolean valid = key.reset();
      if (!valid) {
        keys.remove(key);

        // all directories are inaccessible
        if (keys.isEmpty()) {
          break;
        }
      }
    }
  }

  private void resync(Path path, Path symmetricPath) {
    try {
      if (Files.exists(path) && !Files.exists(symmetricPath)) {
        eventHandler.processEvent(path, symmetricPath, ENTRY_CREATE.name());
      } else if (Files.exists(symmetricPath) && !Files.exists(path)) {
        eventHandler.processEvent(symmetricPath, path, ENTRY_CREATE.name());
      } else if (Files.exists(path) && Files.exists(symmetricPath)) {
        BasicFileAttributeView view = Files.getFileAttributeView(path, BasicFileAttributeView.class);
        BasicFileAttributeView symmetricView = Files.getFileAttributeView(symmetricPath, BasicFileAttributeView.class);

        BasicFileAttributes attributes = view.readAttributes();
        BasicFileAttributes symmetricAttributes = symmetricView.readAttributes();

        int compareLastModTime = attributes.lastModifiedTime()
                                           .compareTo(symmetricAttributes.lastModifiedTime());
        if (compareLastModTime > 0) {
          eventHandler.processEvent(symmetricPath, path, ENTRY_MODIFY.name());
        } else if (compareLastModTime < 0) {
          eventHandler.processEvent(path, symmetricPath, ENTRY_MODIFY.name());
        } else {
          int compareCreateTime = attributes.creationTime()
                                            .compareTo(symmetricAttributes.creationTime());
          if (compareCreateTime > 0) {
            eventHandler.processEvent(symmetricPath, path, ENTRY_MODIFY.name());
          } else if (compareCreateTime < 0) {
            eventHandler.processEvent(path, symmetricPath, ENTRY_MODIFY.name());
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
      resync(path, symmetricPath);
    }
  }

  private Path getSymmetricPath(Path path) {
    //проверять изменения на другой стороне на идентичность файлов/папок, чтобы не попасть в цикл
    Path symmetricDir = pathsMap.get(path);
    if (symmetricDir == null) {
      for (Map.Entry<Path, Path> entry : pathsMap.entrySet()) {
        if (path.equals(entry.getValue())) {
          symmetricDir = entry.getKey();
          break;
        }
      }
    }
    if (symmetricDir == null) {
      throw new RuntimeException("not found symmetry");
    }
    return symmetricDir;
  }


}
