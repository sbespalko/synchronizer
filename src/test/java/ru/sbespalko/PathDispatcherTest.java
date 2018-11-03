package ru.sbespalko;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

@RunWith(MockitoJUnitRunner.class)
public class PathDispatcherTest {
  private static final Logger log = LogManager.getLogger();
  PathDispatcher pathDispatcher;
  Map<Path, Path> pathsMap;


  @Before
  public void setUp() throws Exception {
    pathDispatcher = mock(PathDispatcher.class, CALLS_REAL_METHODS);
  }

  @Test
  public void getFirstLevelRelativeSubdirs() throws IOException {
    log.debug(pathDispatcher.getFirstLevelRelativeSubdirs(Paths.get("C:/myFiles/CalibreLibrary")));
  }

  @Test
  public void registerPaths() {
    pathsMap = new HashMap<>();
    pathsMap.put(Paths.get("C:\\myFiles\\test"), Paths.get("Z:\\test"));
    pathDispatcher = new PathDispatcher(pathsMap, true);

    assertThat(pathDispatcher.getSymmetricPath(Paths.get("C:\\myFiles\\test")), is(Paths.get("Z:\\test")));
    assertThat(pathDispatcher.getSymmetricPath(Paths.get("C:\\myFiles\\test/uniqualZ/z")), is(Paths.get("Z:\\test/uniqualZ/z")));

  }
}