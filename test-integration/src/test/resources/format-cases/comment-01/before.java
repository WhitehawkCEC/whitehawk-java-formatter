package example;

public class PreserveComments {
  private static ReallyLongFooBarName deserialize(
    com.whitehawk.zzz.www.yyy.xxjgiowjogoiwjgiowiogiwogowji.v1.FooBar value

  ) {
    return switch (value) {
      // @formatter:off
      case FOO_BAR_APP_BATCH_APPLICATIONS -> ReallyLongFooBarName.BATCH_APPLICATIONS;
      case FOO_BAR_APP_BATCH_OPERATING_SYSTEMS -> ReallyLongFooBarName.BATCH_OPERATING_SYSTEMS;
      case FOO_BAR_APP_MULTI_FACTOR_AUTHENTICATION -> ReallyLongFooBarName.MULTI_FACTOR_AUTHENTICATION;
      case FOO_BAR_APP_RESTRICT_WHATEVER_PRIVILEGES -> ReallyLongFooBarName.RESTRICT_WHATEVER_PRIVILEGES;
      case FOO_BAR_APP_CONSUMER_CONTROL -> ReallyLongFooBarName.CONSUMER_CONTROL;
      case FOO_BAR_APP_RESTRICT_MICROSOFT_OFFICE_MACROS -> ReallyLongFooBarName.RESTRICT_MICROSOFT_OFFICE_MACROS;
      case FOO_BAR_APP_USER_CONSUMER_SOFTENING -> ReallyLongFooBarName.USER_CONSUMER_SOFTENING;
      case FOO_BAR_APP_REGULAR_BACKUPS -> ReallyLongFooBarName.REGULAR_BACKUPS;

      // Errors
      case FOO_BAR_APP_UNSPECIFIED -> throw new IllegalArgumentException("Unspecified");
      case UNRECOGNIZED -> throw new AssertionError("Unrecognized: " + value);
      // @formatter:on
    };
  }
}
