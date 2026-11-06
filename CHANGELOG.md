# Changelog

## [0.4.6](https://github.com/groundsgg/library-scene/compare/v0.4.5...v0.4.6) (2026-11-06)


### Performance Improvements

* **scene:** reuse unchanged NPC world bounds ([#28](https://github.com/groundsgg/library-scene/issues/28)) ([a0a815c](https://github.com/groundsgg/library-scene/commit/a0a815c55e13bfddbee051d24cc85e4864da85c8))

## [0.4.5](https://github.com/groundsgg/library-scene/compare/v0.4.4...v0.4.5) (2026-11-05)


### Bug Fixes

* **scene:** accept valid native SOLID corner movement ([#22](https://github.com/groundsgg/library-scene/issues/22)) ([d7a03b8](https://github.com/groundsgg/library-scene/commit/d7a03b8ccc01582fe0bb8415c81259a0e530672d))


### Performance Improvements

* **scene:** avoid temporary objects in prepared hover rays ([#26](https://github.com/groundsgg/library-scene/issues/26)) ([0bd7f98](https://github.com/groundsgg/library-scene/commit/0bd7f983737e717cef340a14c32b69759bfa33fc))
* **scene:** filter NPC collision candidates before collection ([#27](https://github.com/groundsgg/library-scene/issues/27)) ([ca87b78](https://github.com/groundsgg/library-scene/commit/ca87b78f79ffa5fc9abb5b2bbbfc3bf2ef08d6f4))
* **scene:** resolve hover owners during tracker collection ([#24](https://github.com/groundsgg/library-scene/issues/24)) ([1934266](https://github.com/groundsgg/library-scene/commit/19342662aa9af2ac4482097052c7e1e83243fab6))
* **scene:** reuse prepared hover bounds across viewers ([#25](https://github.com/groundsgg/library-scene/issues/25)) ([078e135](https://github.com/groundsgg/library-scene/commit/078e1358d39d5690ea5b50c9ee2e78522b20c695))

## [0.4.4](https://github.com/groundsgg/library-scene/compare/v0.4.3...v0.4.4) (2026-11-01)


### Bug Fixes

* **scene:** share hover selection and keep head tracking upright ([#20](https://github.com/groundsgg/library-scene/issues/20)) ([45de6db](https://github.com/groundsgg/library-scene/commit/45de6db86772c91ec00a5207325b13d477213f47))

## [0.4.3](https://github.com/groundsgg/library-scene/compare/v0.4.2...v0.4.3) (2026-10-13)


### Bug Fixes

* **scene-minestom:** keep airborne NPC contacts from slamming players down ([#18](https://github.com/groundsgg/library-scene/issues/18)) ([90f9aaa](https://github.com/groundsgg/library-scene/commit/90f9aaae6c91a3c68ae62f940d9da32f4046f857))

## [0.4.2](https://github.com/groundsgg/library-scene/compare/v0.4.1...v0.4.2) (2026-10-13)


### Bug Fixes

* **scene-minestom:** add gentle NPC bounce lift and anchor labels ([#16](https://github.com/groundsgg/library-scene/issues/16)) ([649cc17](https://github.com/groundsgg/library-scene/commit/649cc170f2d8f66a37c8cafa7c54e0887532824a))

## [0.4.1](https://github.com/groundsgg/library-scene/compare/v0.4.0...v0.4.1) (2026-10-13)


### Bug Fixes

* **scene-minestom:** deliver NPC bounce within the same player tick ([#13](https://github.com/groundsgg/library-scene/issues/13)) ([6b863d4](https://github.com/groundsgg/library-scene/commit/6b863d4544e6dbd06e6f2155a1249aafe6a7ca22))

## [0.4.0](https://github.com/groundsgg/library-scene/compare/v0.3.0...v0.4.0) (2026-10-13)


### Features

* fractional NPC bounce distance and retryable soft impulses ([#11](https://github.com/groundsgg/library-scene/issues/11)) ([dccdafc](https://github.com/groundsgg/library-scene/commit/dccdafcff2f1909271cfd5e1a757e25c790e1b9c))

## [0.3.0](https://github.com/groundsgg/library-scene/compare/v0.2.1...v0.3.0) (2026-10-12)


### Features

* add optional NPC collision and configurable bounce ([#9](https://github.com/groundsgg/library-scene/issues/9)) ([62461a5](https://github.com/groundsgg/library-scene/commit/62461a536ca17f9d57d72a493cc3fc0e9e2e162d))

## [0.2.1](https://github.com/groundsgg/library-scene/compare/v0.2.0...v0.2.1) (2026-09-07)


### Bug Fixes

* **scene:** drain asynchronous attachments on close ([#7](https://github.com/groundsgg/library-scene/issues/7)) ([0ff2bc8](https://github.com/groundsgg/library-scene/commit/0ff2bc8a28c32cf98eb14a0de51475227d73fd9e))

## [0.2.0](https://github.com/groundsgg/library-scene/compare/v0.1.0...v0.2.0) (2026-09-02)


### Features

* **scene:** add Minestom scene runtime ([#4](https://github.com/groundsgg/library-scene/issues/4)) ([023cc57](https://github.com/groundsgg/library-scene/commit/023cc5721dab3a915a704dcd0674e964f0dbf229))


### Bug Fixes

* **release:** skip empty Java snapshot bumps ([#5](https://github.com/groundsgg/library-scene/issues/5)) ([68d52c2](https://github.com/groundsgg/library-scene/commit/68d52c285ec54b587ba4507eb2f25eeff306e898))

## 0.1.0 (2026-08-12)


### Features

* **scene:** add strict scene format ([#1](https://github.com/groundsgg/library-scene/issues/1)) ([ea8e824](https://github.com/groundsgg/library-scene/commit/ea8e824c18b160b7fa731a90869e88bde7474225))


### Bug Fixes

* **release:** validate manifest lifecycle version ([#3](https://github.com/groundsgg/library-scene/issues/3)) ([e712add](https://github.com/groundsgg/library-scene/commit/e712add532c6626c63f25d2e6271658fbed696a9))
