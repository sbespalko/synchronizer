package ru.sbespalko;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;

public class EventHandler {
  private static final Logger log = LogManager.getLogger();
  private static final String CREATE = "ENTRY_CREATE";
  private static final String DELETE = "ENTRY_DELETE";
  private static final String MODIFY = "ENTRY_MODIFY";

  public void processEvent(Path path, Path symmetricPath, String kindName) throws IOException {
    switch (kindName) {
      case CREATE:
        handleCreate(path, symmetricPath);
        break;
      case DELETE:
        handleDelete(path, symmetricPath);
        break;
      case MODIFY:
        handleModify(path, symmetricPath);
        break;
      default:
        throw new IllegalArgumentException("NOT EXIST: " + kindName);
    }
  }

  private void handleCreate(Path path, Path symmetricPath) throws IOException {
    if (Files.exists(symmetricPath)) {
      return;
    }
    if (Files.isDirectory(path)) {
      Files.createDirectories(symmetricPath);
      log.info("Create dir {}: ", symmetricPath);
    } else {
      if (Files.exists(path)) {
        if (!Files.exists(symmetricPath.getParent())) {
          Files.createDirectories(symmetricPath.getParent());
          log.info("Create dirs: {}", symmetricPath.getParent());
        }
        Files.copy(path, symmetricPath, COPY_ATTRIBUTES);
        log.info("Create file: {}", symmetricPath);
      }
    }
  }

  private void handleDelete(Path path, Path symmetricPath) throws IOException {
    if (!Files.exists(symmetricPath)) {
      return;
    }
    if (Files.isDirectory(symmetricPath)) {
      Files.walk(symmetricPath)
           .sorted(Comparator.reverseOrder())
           .map(Path::toFile)
           .forEach(File::delete);
      log.info("Delete tree from: {}", symmetricPath);
    } else {
      if (!Files.exists(path)) {
        Files.delete(symmetricPath);
        log.info("Delete file: {}", symmetricPath);
      }
    }
  }

  private void handleModify(Path path, Path symmetricPath) throws IOException {
    if (Files.exists(path)) {
      if (Files.isDirectory(path)) {
        return;
      }
      if (FileUtils.contentEquals(path.toFile(), symmetricPath.toFile())) {
        return;
      }
      if (!Files.exists(symmetricPath.getParent())) {
        handleCreate(path, symmetricPath);
      }
      try (OutputStream outputStream = Files.newOutputStream(symmetricPath)) {
        Files.copy(path, outputStream);
        log.info("Copy files: {} -> {}", path, symmetricPath);
      }
    }
  }
}
