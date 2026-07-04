# Changelog

All notable changes to this project will be documented in this file. See [commit-and-tag-version](https://github.com/absolute-version/commit-and-tag-version) for commit guidelines.

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
