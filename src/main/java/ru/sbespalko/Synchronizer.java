package ru.sbespalko;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public class Synchronizer {

  private static final String SERVICE_PROPERTIES = "service.properties";

  public static void main(String[] args) throws IOException {
    //load props
    Properties properties = new Properties();
    properties.load(Files.newBufferedReader(Paths.get(SERVICE_PROPERTIES), StandardCharsets.UTF_8));

    //create Watcher
    Map<Path, Path> pathsMap = properties.entrySet()
                                         .stream()
                                         .collect(Collectors.toMap(
                                             e -> Paths.get((String) e.getKey()),
                                             e -> Paths.get((String) e.getValue())));

    DirWatcher dirWatcher = new DirWatcher(pathsMap, true);
    dirWatcher.setEventHandler(new EventHandler());
    dirWatcher.start();
  }
}
