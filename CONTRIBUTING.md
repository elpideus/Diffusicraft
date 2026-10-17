# Contributing

Thanks for helping. Diffusicraft is MIT-licensed, and contributions are accepted under the same license.

## Before you start

- **Read the design.** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains the design, and
  [docs/ROADMAP.md](docs/ROADMAP.md) shows what is planned.
- **Discuss large changes first.** For a new world-generation feature, a new runtime or a new dependency, open an issue
  before writing a lot of code.
- **Report bugs with logs.** Include `logs/latest.log`. The lines starting with `Diffusicraft storage`, `Hardware` and
  `Loaded model` tell us most of what we need.

## Making changes

1. Set up the project as described in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
2. Keep each pull request to one topic, with small, focused commits. Use an imperative subject line, and a body
   saying why.
3. `./gradlew build` must pass. If you touched the screens or mixins, also run the client game test and look at the
   screenshots.
4. Update the docs in the same pull request when behaviour, settings or downloads change.

## Rules that keep the project healthy

- **Never commit model weights or native libraries.** Everything large is downloaded at runtime, pinned by SHA-256, from
  the vendor's official source.
- **No All Rights Reserved code or assets.** ARR mods may appear in the dev modpack (runtime only), never in the source
  or the jar.
- **Third-party licenses.** Libraries under licenses other than MIT/Apache (NVIDIA's, for example) are only
  downloaded after the player accepts them in game. Add new third-party code to
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Keep the render thread free.** It must never do I/O, hashing or waiting. See the conventions in DEVELOPMENT.md.
- **Translations.** All player-facing text goes in `assets/diffusicraft/lang/`. Translations are welcome.
