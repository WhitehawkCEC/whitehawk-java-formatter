package example;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;

public final class MavenWrapperDownloader {
  private static void downloadFileFromURL(URL wrapperUrl, Path wrapperJarPath)
    throws IOException {
    log(" - Downloading to: " + wrapperJarPath);
  }

  private static void log(String msg) {
    if (VERBOSE) {
      System.out.println(msg);
    }
  }
}
