# Formatter Style

2-space indent · +2 continuation · 100-col max · LF · UTF-8 · trailing newline · no
trailing whitespace. 

Golden examples: [Test cases](../test-integration/src/test/resources/format-cases/).

## Normalizations (always)

Drop unused imports (wildcards kept; imports never reordered):

```java
import a.b.Used;
import a.b.Unused;
```

```java
import a.b.Used;
```

Add `var` to implicit lambda params:

```java
map.forEach((k, v) -> log(k));
```

```java
map.forEach((var k, var v) -> log(k));
```

Brace control-flow bodies:

```java
if (x) return null;
```

```java
if (x) {
  return null;
}
```

Terminate enum constant lists with trailing comma then `;`:

```java
enum E { A, B }
```

```java
enum E {
  A,
  B,
  ;
}
```

## Braces & blanks

K&R braces, empty body `{}`, ≤1 blank between members, none after `{` or before `}`:

```java
class C {


  void m() {
  }
}
```

```java
class C {
  void m() {}
}
```

## Wrapping (>100 cols → one element per line)

Method chain — break before every `.`, receiver stays on `=`:

```java
var x = foo.newBuilder().a(1).b(2).build(); // ...pushed past 100 cols
```

```java
var x = foo.newBuilder()
  .a(1)
  .b(2)
  .build();
```

Ternary — break before `?` and `:`:

```java
int i = cond ? whenTrue : whenFalse; // ...pushed past 100 cols
```

```java
int i = cond
  ? whenTrue
  : whenFalse;
```

Logical operators — break at every same-precedence op, operator-leading:

```java
if (a == X || b == Y || c == Z) { // ...pushed past 100 cols
```

```java
if (
  a == X
    || b == Y
    || c == Z
) {
```

Arguments — one per line; a single fitting arg collapses back inline:

```java
foo(first, second); // ...pushed past 100 cols
```

```java
foo(
  first,
  second
);
```

## Spacing

```java
foo (a ,b) ;
List < Map < ? , ? > > g;
public < T > T get () {}
@ Override
(String)key
a::b   a . b
```

```java
foo(a, b);
List<Map<?, ?>> g;
public <T> T get() {}
@Override
(String) key
a::b   a.b
```
