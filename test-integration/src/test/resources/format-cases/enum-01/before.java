package example;

public enum ProductGroup {
  SOMETHING_OR_OTHER("Something or Other", ProductCategory.SOMETHING_OR_OTHER_AND_IMPERSONATION),
  EMAIL_SECURITY("Email Security", ProductCategory.SOMETHING_OR_OTHER_AND_IMPERSONATION),
  BRAND_AND_DOMAIN_ABUSE(
    "Brand & Domain Abuse",
    ProductCategory.SOMETHING_OR_OTHER_AND_IMPERSONATION
  ),
  PATCH_AND_VULNERABILITY_MANAGEMENT(
    "Patch & Vulnerability Management",
    ProductCategory.ALPHA_BETA_CHARLIE_DELTA
  ),
  ATTACK_AREA_AND_HARDWARE_EXPOSURE(
    "Attack Area & Hardware Exposure",
    ProductCategory.ALPHA_BETA_CHARLIE_DELTA
  ),
  OVERLY_COMPROMISED_SYSTEMS(
    "Overly Compromised Systems",
    ProductCategory.ALPHA_BETA_CHARLIE_DELTA
  ),
  TRANSPORT_AND_ENCRYPTION_SECURITY(
    "Transport & Encryption Security",
    ProductCategory.INTERCEPTION_AND_INFORMATION_EXPOSURE
  ),
  INFORMATION_DISCLOSURE_SECURITY(
    "Information Disclosure Security",
    ProductCategory.INTERCEPTION_AND_INFORMATION_EXPOSURE
  ),
  APPLICATION_SECURITY("Application Security", ProductCategory.SOME_KIND_OF_SECURITY_THING),
  ;

  public final String label;
  public final ProductCategory category;

  ProductGroup(String label, ProductCategory category) {
    this.label = label;
    this.category = category;
  }
}
