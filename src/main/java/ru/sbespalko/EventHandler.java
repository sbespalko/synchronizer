package ru.sbespalko;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;

public class EventHandler {
  public static final String CREATE = "ENTRY_CREATE";
  public static final String DELETE = "ENTRY_DELETE";
  public static final String MODIFY = "ENTRY_MODIFY";

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
      default: throw new IllegalArgumentException("NOT EXIST: " + kindName);
    }
  }

  private synchronized void handleCreate(Path path, Path symmetricPath) throws IOException {
    if (Files.exists(symmetricPath)) {
      return;
    }
    if (Files.isDirectory(path)) {
      Files.createDirectory(symmetricPath);
    } else {
      if (Files.exists(path)) {
        Files.copy(path, symmetricPath, COPY_ATTRIBUTES);
      }
    }
  }

  private synchronized void handleDelete(Path path, Path symmetricPath) throws IOException {
    if (!Files.exists(symmetricPath)) {
      return;
    }
    if (Files.isDirectory(symmetricPath)) {
      Files.walk(symmetricPath)
           .sorted(Comparator.reverseOrder())
           .map(Path::toFile)
           .forEach(File::delete);
    } else {
      Files.delete(symmetricPath);
    }
  }

  private synchronized void handleModify(Path path, Path symmetricPath) throws IOException {
    if (FileUtils.contentEquals(path.toFile(), symmetricPath.toFile())) {
      return;
    }
    if (Files.exists(path)) {
      try (OutputStream outputStream = Files.newOutputStream(symmetricPath)) {
        Files.copy(path, outputStream);
      }
    }
  }
}
