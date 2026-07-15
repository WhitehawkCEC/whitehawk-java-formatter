package example;

public class PreserveComments {
  private static JavaClasses allClasses() {
    return new ClassFileImporter()
      .withImportOption(
        // Ignore generated code
        (it) -> !it.contains("/jooq/generated")
      )
      .importPackages("com.xyz.backend");
  }
}
