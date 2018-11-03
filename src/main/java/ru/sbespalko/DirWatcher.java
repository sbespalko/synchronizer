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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

  private final ScheduledThreadPoolExecutor executor;
  private final WatchService watcher;
  private EventHandler eventHandler;
  private PathDispatcher pathDispatcher;
  private ConcurrentMap<Path, Future<?>> planner;
  private long startDelay = 10000L;

  private final Map<WatchKey, Path> keys;
  private final boolean recursive;
  private boolean trace;

  @SuppressWarnings("unchecked")
  static <T> WatchEvent<T> cast(WatchEvent<?> event) {
    return (WatchEvent<T>) event;
  }

  public void setEventHandler(EventHandler eventHandler) {
    this.eventHandler = eventHandler;
  }

  public void setPathDispatcher(PathDispatcher pathDispatcher) {
    this.pathDispatcher = pathDispatcher;
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
    executor = new ScheduledThreadPoolExecutor(10);
    executor.setRemoveOnCancelPolicy(true);

    planner = new ConcurrentHashMap<>();

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
        Path symmetricPath = pathDispatcher.getSymmetricPath(dir)
                                           .resolve(name);
        planner.compute(symmetricPath, (k, future) -> {
          if (future != null) {
            future.cancel(true);
          }
          return executor.schedule(() -> {
            try {
              eventHandler.processEvent(path, symmetricPath, ev.kind()
                                                               .name());
            } catch (IOException e) {
              log.error("IOEXCEPTION: {}", e.getMessage());
              resync(path, symmetricPath);
            }
          }, startDelay, TimeUnit.MILLISECONDS);
        });
        log.debug("{}: {}", event.kind()
                                 .name(), path);

        // if directory is created, and watching recursively, then
        // register it and its sub-directories
        if (recursive && (kind == ENTRY_CREATE)) {
          try {
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
              pathDispatcher.registerPaths(path);
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
        Path path = keys.remove(key);
        pathDispatcher.unregisterPaths(path);
        // all directories are inaccessible
        if (keys.isEmpty()) {
          break;
        }
      }
    }
  }

  private synchronized void resync(Path path, Path symmetricPath) {
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
      try {
        Thread.sleep(startDelay);
        resync(path, symmetricPath);
      } catch (InterruptedException e1) {
        log.error("Interrupt: {}", e.getMessage());
      }
    }
  }
}
