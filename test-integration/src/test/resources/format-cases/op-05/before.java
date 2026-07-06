package example;

public final class AlreadyWrappedAssignment extends Helper {
  private void something(double assumedSecurityCount, double assumedSupportCount) {
    double effectiveSecurityCount =
      assumedSecurityCount
        + (assumedSupportCount / SUPPORT_TO_SECURITY_CONVERSION_RATIO);
  }
}
