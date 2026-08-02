# Third-party code in this repository

Everything here is copied in rather than fetched at runtime: this project deliberately ships without
external dependencies. Copies are kept unmodified apart from their package name, so that upgrading
one is a copy rather than a merge.

## FastBoard

- **Where** `src/main/java/de/raindancer/core/internal/fastboard/`
- **Upstream** <https://github.com/MrMicky-FR/FastBoard> — version 2.2.0
- **Author** MrMicky
- **Licence** MIT
- **Copied** 2026-08-03
- **Changed** the package, from `fr.mrmicky.fastboard` to
  `de.raindancer.core.internal.fastboard`. Nothing else — no local edits, so upgrading is
  unpack-and-rename rather than a merge. The String-based `FastBoard` class was not copied; only the
  Adventure one is used.
- **How it fits our code** everything of ours reaches it through `de.raindancer.core.scoreboard`:
  `Board`/`BoardFactory` are the seam, `FastBoardFactory` is the only class allowed to import it, and
  `Scoreboards` adds what a library has no business deciding — which plugin owns a player's sidebar.
  It never logs; it throws, and `Scoreboards` turns that into our `Log` and into "this server has no
  sidebars, stop asking". `NoExternalDependenciesTest` keeps the import boundary and the copyright
  headers honest.
- **Why** a packet-level scoreboard does not flicker, can be written from any thread, and does not
  fight other plugins over Bukkit's team API. Version 2.2.0 is the first release to state Paper 26.2
  support, and its Adventure variant is generic over `net.kyori.adventure.text.Component` — the same
  class Adventure 5.2 ships, so it is binary-compatible with what Paper 26.2 provides.

The MIT licence requires that the copyright notice travels with the code. Each copied file still
carries MrMicky's header, and that must stay.

### Upgrading

    mvn dependency:copy -Dartifact=fr.mrmicky:fastboard:<version>:jar:sources \
        -DoutputDirectory=target/fbsrc

Unpack, re-apply the package rename, and check `THIRD-PARTY.md` and the version noted above.
