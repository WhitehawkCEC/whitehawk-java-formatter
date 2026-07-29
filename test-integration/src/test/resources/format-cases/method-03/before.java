package example;

public record ConstructorPlusMethodCall() {
  DSLContext context = new DslContextFactory(SQLDialect.POSTGRES).dslContext(
    () -> dataSource,
    () -> true
  );
}
