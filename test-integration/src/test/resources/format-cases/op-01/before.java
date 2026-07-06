package example;

public final class OpWrapping extends PretendHelper {
  public String asSvg() {
    String viewBox = String.format("%s %s %s %s", MIN_X, MIN_Y, WIDTH, HEIGHT);
    return ""
      + "<svg"
      + " xmlns=\"http://www.w3.org/2000/svg\""
      + " image-rendering=\"optimizeQuality\""
      + attrValue("viewBox", viewBox)
      + "  >"
      + "    <g transform=\"rotate(135)\">"
      + "      <!-- Foo -->"
      + bar(GRAY_200, 100, WIDTH * .48) + bar(PRIME_NAVY, value.foo(), WIDTH * .48)
      + "      <!-- Herp -->"
      + bar(GRAY_200, 100, WIDTH * .42) + bar(PRIME_BLUE, value.herp(), WIDTH * .42)
      + "      <!-- Bar -->"
      + bar(GRAY_200, 100, WIDTH * .36) + bar(PRIME_TEAL, value.bar(), WIDTH * .36)
      + "    </g>"

      + centeredText("-20%", value.standardCode())

      + dot(PRIME_NAVY, "-10%")
      + text("-10%", "Foo:")
      + rightText("-10%", DisplayFormat.ofPercentage(value.foo()))

      + dot(PRIME_BLUE, "0%")
      + text("0%", "Herp:")
      + rightText("0%", DisplayFormat.ofPercentage(value.herp()))

      + dot(PRIME_TEAL, "10%")
      + text("10%", "Bar:")
      + rightText("10%", DisplayFormat.ofPercentage(value.bar()))

      + "  </svg>";
  }
}
