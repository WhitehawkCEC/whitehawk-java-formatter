# Changelog

All notable changes to this project will be documented in this file. See [commit-and-tag-version](https://github.com/absolute-version/commit-and-tag-version) for commit guidelines.

## [0.4.1](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.4.0...0.4.1) (2026-07-16)


### Bug Fixes

* **core:** don't break before a type declaration's extends/implements ([0f619b1](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/0f619b1532e27724e170be8592b024ce6326fbae))

## [0.4.0](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.3.4...0.4.0) (2026-07-15)


### ⚠ BREAKING CHANGES

* **core:** construct a Printer with `new Printer(TokenContext.from(tokens))`
instead of `new Printer(tokens)`.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>

### Features

* **core:** add compound-assignment symbols to Sym ([cb81f92](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/cb81f92540b86063b81db9b609a0776475b89c61))
* **core:** add modifier and keyword symbols to Sym ([53774c5](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/53774c5e8e7bcb0a5682ae9c6140170eee1c1993))
* **core:** add primitive-type symbols to Sym ([78944c4](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/78944c4d3632a223d8c5e9dc9e45f26fe7929a0a))
* **core:** add Printer(TokenContext) constructor ([3258ea3](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/3258ea3a80e64e7e3d57c90bb591dca0aa394607))
* **core:** add public Sym enum in core ([196e6bb](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/196e6bbbec0ad76d7acb7b657db8ad4656a300f0))
* **core:** add Token.of factory ([676b07c](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/676b07cc18151e45a95be41903656ff5fa8c2b74))
* **core:** build the printer's TokenContext in Formatter ([447bc45](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/447bc45020139bcb6ab9b527c13bd5625810d953))
* **core:** build the printer's TokenContext in the benchmark ([5a47629](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/5a476293751a44147626a350e73c6f70d849c3fa))
* **core:** build tokens via Token.of in the lexer ([0a0110b](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/0a0110b7d05a55a91c4b566c94e67f1bfab731a8))
* **core:** build tokens via Token.of in TokenPreprocessor ([fbed3aa](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/fbed3aa094f8f71898dbdf8646431ea50fcf5bc9))
* **core:** expand Sym to cover every dispatched symbol ([2c09a96](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/2c09a964f1d7902a07840849b9b514389d2ad66b))
* **core:** prefer `EnumSet` ([ad17565](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/ad1756582c4c2170a45ea90e8066ff5e9cc72122))
* **core:** remove Printer(List<Token>) constructor ([a523b83](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/a523b8305d83db9175ab2308b0efd27c38e25b5b))


### Bug Fixes

* **core:** don't collapse chain-call args containing a line comment ([1b01979](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/1b019795c8ff56f6e328a6c9b120efc95a75c5e7))

## [0.3.4](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.3.3...0.3.4) (2026-07-08)


### Features

* **app-cli:** print stats upon completion ([a16f965](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/a16f9656ef1ced79a256fb7cf7695c3a1dceeb30))
* **core:** add JavaLexer.tokenize to lex directly into a list ([e247dbe](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/e247dbee673be0e34106649b6c3cd7087f007d3b))
* **core:** break enum constant argument lists as siblings ([ee93b3d](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/ee93b3deda9050820a9b7c8bb2b2cd5bc5931bf6))
* **core:** break switch case arrows as siblings ([4e679db](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/4e679db54d9b3ee7e9d1ffa6ae6373b3490a0e3d))
* **core:** lex via JavaLexer.tokenize in Formatter ([ae09f94](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/ae09f94a28fad1cbb59d37948eafd34040690658))
* **core:** use switch for text comparison ([0b37748](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/0b37748b418dd3f115bed64efc283e9af247ee7c))


### Bug Fixes

* **adapter-git-cli:** properly close `InputStream` ([78b0ca1](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/78b0ca19a194c3927e19012125fedfa8599ccfeb))

## [0.3.3](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.3.2...0.3.3) (2026-07-07)


### Features

* add `@NullMarked` ([6ee8606](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/6ee860624e4e605e0d75a79f25d256fdb64e9f65))
* add `@NullMarked` ([8ce3603](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/8ce36033f7f3a9f6dc148ee95faaf7e109c3cddd))
* **core:** align a comment before an arrow case with the label ([618f243](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/618f2438f759f16349b919e01d8b093d763d5e78))
* **core:** break a single-line condition at its logical operators ([b20ab82](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/b20ab822a38b0990b00297996c98e82a67416beb))
* **core:** break a too-long method chain instead of its last argument ([564bfee](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/564bfee6c3492f96f6846dbae5384840e513ae1e))
* **core:** break and unwrap a chain whose first call has a type witness ([cbfffa8](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/cbfffa85afcdf7919beffc233f16079a55b02324))
* **core:** break every logical operator when a mixed condition wraps ([6b5d98f](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/6b5d98f47a52ed67cbf5e3a6bddc24cddb73fbcf))
* **core:** hang logical operators under a broken arrow-case body ([3c6ed36](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/3c6ed368fcff0dde7dfe558a7c74356942331cb5))
* **core:** indent a comment before a case label as case body ([8a364db](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/8a364db6c3b8278cbc09c676c8836cce35fba330))
* **core:** indent a parenthesized conditional's body an extra level ([e70cfd1](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/e70cfd13ec8d32963905b963c2d0500e5143edbe))
* **core:** indent a wrapped switch body from its keyword column ([ea68255](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/ea6825538830f0e415f0362a3842ea11ae9a2eff))
* **core:** keep a cast paren on its line when wrapping ([bef1136](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/bef11366d7f33c56b3ca7fcd50a92015fe935ad6))
* **core:** keep single indent for a parenthesized-conditional argument ([df6ff8c](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/df6ff8c0882d27e2bc6a73b2928d0227a364ac44))
* **core:** keep single indent for a sole-argument parenthesized conditional ([e8eda7b](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/e8eda7b6a2d14523a62a07260547bd774860b6ae))
* **core:** only parenthesize a chain that follows a binary operator ([e12470e](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/e12470e8719b1d700cbf3efe745d4c315ad0f31b))
* **core:** parenthesize a multiline method chain used as a binary operand ([a588a51](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/a588a5170d2480b140fc34425833feea58356cc8))
* **core:** parenthesize switch expression used as a binary operand ([d05debf](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/d05debfc529682f2b9046128fc23af0f6ccbec43))
* **core:** seat a broken binary RHS's first operand on the assignment line ([97f32ad](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/97f32adb477e695bdeb97cf7c979d18bb9e63c74))
* **core:** space adjacent annotations after `@Foo(..)` ([d206f44](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/d206f44306c33d9a04785d6140258c6160b41db2))
* **core:** spread a concat break to every `+` of the chain ([214aab6](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/214aab61454db31788d94bcfe1ef186648ce084f))

## [0.3.2](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.3.1...0.3.2) (2026-07-04)


### Features

* **core:** add ArraySmallEnumSet ([7e8f7ff](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/7e8f7ff089db6a5d5419a8265ac4f2f3a0ee1d54))
* **core:** insert trailing comma before an enum's terminating `;` ([4ab26f2](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/4ab26f277406cc85f868a80026664cdac991cbd6))
* **core:** terminate enum constant list with `;` after trailing comma ([763c07a](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/763c07a0e5a97a2e92bd2fc103a5a3825075c9fa))


### Bug Fixes

* **core:** break long ternary at `?`/`:` instead of isolating a bracket ([624d118](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/624d1182ab2d740ac7fb8b6e0872354c653c7399))

## [0.3.1](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.3.0...0.3.1) (2026-07-04)

## [0.3.0](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.2.3...0.3.0) (2026-07-04)


### ⚠ BREAKING CHANGES

* **app-maven-plugin:** remove the changed parameter from format

### Features

* **adapter-git-cli:** restrict tracked .java files to Maven source roots ([194db12](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/194db125900c69d2b24614cb690437f3a29ef00f))
* **app-maven-plugin:** add format-changed goal ([b185e2d](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/b185e2d50bb0ee92fb020242a7b3adb5b7560ca5))
* **app-maven-plugin:** remove the changed parameter from format ([589a593](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/589a5931ecf5ce3cbf1530644634c654d5ef49c9))
* **core:** add isKeyword/isPrimitive/isModifier to Token ([01cc6d2](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/01cc6d2a33ab3569fbcfcb92b2f2ec49a54a476c))
* **core:** break a wrapped logical condition at every same operator ([98542e7](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/98542e7cdbbcff0760f70663aabed7e7972dfe35))
* **core:** classify bracket openers/closers once per token ([86b135a](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/86b135a66ce14741d9a52fb796ad8be480d0b282))
* **core:** migrate Printer to Token classification helpers ([ec32f77](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/ec32f7735168f0e00a9f7c76781b40f2e5c4b34c))
* **core:** move an input break after = into the right-hand side ([34b84b1](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/34b84b12d183d619ed83c1816071b8d1a11573e6))

## [0.2.3](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.2.2...0.2.3) (2026-07-03)


### Features

* **core:** collapse an empty bracket group onto one line ([4ccdc13](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/4ccdc136d986fc9383686e7100664f3bd90decc9))
* **core:** drop unused single imports ([c865cfc](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/c865cfc48df4d0d8646482619084e157060743a0))

## [0.2.2](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.2.1...0.2.2) (2026-07-03)


### Features

* **core:** keep a broken chain call's broken multi-argument argument broken ([c3b6d5f](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/c3b6d5fbbe8f2b7391b686016079a42bbc5fae32))

## [0.2.1](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.2.0...0.2.1) (2026-07-03)


### Features

* **core:** keep a broken chain call's multi-argument or nested arguments broken ([06a9af7](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/06a9af7f72eeced482d0151e2dd04bf01eb98d6a))

## [0.2.0](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.1.1...0.2.0) (2026-07-03)


### ⚠ BREAKING CHANGES

* **app-maven-plugin:** change goal prefix

### Features

* **app-maven-plugin:** change goal prefix ([0c206bf](https://github.com/whitehawkcec/whitehawk-java-formatter/commit/0c206bf15aaac054020c76c10f4b2fa838bf4ad9))

## [0.1.1](https://github.com/whitehawkcec/whitehawk-java-formatter/compare/0.1.0...0.1.1) (2026-07-03)
