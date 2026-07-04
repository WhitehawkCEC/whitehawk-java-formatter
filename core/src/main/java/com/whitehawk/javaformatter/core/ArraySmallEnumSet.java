package com.whitehawk.javaformatter.core;

import org.jspecify.annotations.NullMarked;

/// A fixed-size array of small enum sets, each packed into a single `byte`. The enum type must have
/// at most [Byte#SIZE] constants so every set of its values fits in a byte; a value's membership
/// bit is `1 << ordinal()`.
@NullMarked
public final class ArraySmallEnumSet<E extends Enum<E>> {
  private final byte[] bits;

  public ArraySmallEnumSet(Class<E> type, int size) {
    int count = type.getEnumConstants().length;
    if (count > Byte.SIZE) {
      throw new IllegalArgumentException(
        type.getName() + " has " + count + " constants, more than a byte can hold"
      );
    }
    this.bits = new byte[size];
  }

  /// Adds `value` to the set at `i`; returns whether it was not already present.
  public boolean set(int i, E value) {
    int bit = 1 << value.ordinal();
    if ((bits[i] & bit) != 0) {
      return false;
    }
    bits[i] |= bit;
    return true;
  }

  public boolean has(int i, E value) {
    return (bits[i] & 1 << value.ordinal()) != 0;
  }
}
