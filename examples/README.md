# Using Rain's Core from another project

`RainsCore` is a library plugin. Another plugin **compiles against it** and **depends on it at
runtime** — it is never shaded in, and nothing is ever merged into anything.

`DemoPlugin.java.txt` is a complete example, and is not decoration: it is compiled by
`ConsumerCompilesTest`, so an API change that would break a downstream plugin fails this build
rather than that one.

## Getting it

    cd RainsCore && mvn install

That puts it in your local Maven repository. Published properly one day, this is where the repository
URL goes instead.

## Maven

```xml
<dependency>
    <groupId>de.raindancer</groupId>
    <artifactId>RainsCore</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

## Gradle — `TheHungerGames` and anything else on Gradle

```kotlin
repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("de.raindancer:RainsCore:1.0.0")
}
```

`provided` / `compileOnly` in both, and that is the important part: the classes come from the
`RainsCore` jar the server has already loaded. Shading them in would give you a second copy of every
registry — your own action bar manager, your own item registry, your own scoreboard owner — none of
which would know about anybody else's. That is the exact problem this library exists to remove.

## Declaring the dependency to the server

This is the part that is easy to get wrong, and getting it wrong does not look like a dependency
problem — it looks like `NoClassDefFoundError: de/raindancer/core/gui/Menu` at load time.

### `paper-plugin.yml` — what to write

```yaml
name: YourPlugin
main: com.example.YourPlugin
api-version: '1.21'

dependencies:
  server:
    RainsCore:
      load: BEFORE
      required: true
      join-classpath: true
```

`join-classpath: true` is what actually puts RainsCore's classes on your plugin's classpath. Paper
plugins get their own isolated classloader, so without it your plugin loads and then dies the moment
it touches one of these classes.

### What **not** to write

```yaml
depend: [RainsCore]        # legacy plugin.yml only — silently ignored in paper-plugin.yml
```

`depend:` is the old `plugin.yml` syntax. A `paper-plugin.yml` containing it declares no dependency
at all: your plugin may load before RainsCore, with no access to its classes, and fails with a stack
trace naming a class you never wrote. This was written wrongly here first and found by booting a real
server, which is the only thing that would have found it.

### Old-style `plugin.yml`

If your plugin still uses `plugin.yml` rather than `paper-plugin.yml`, then `depend: [RainsCore]` is
correct and sufficient — the isolation described above is a paper-plugin thing.

## Using it

```java
RainsCore core = RainsCore.get();

Chat chat = core.chatFor("YourTag");
SettingsStore<YourConfig> settings = core.settingsFor(this, YourConfig.class, YourConfig.DEFAULTS);
core.itemAbilities().register(...);
core.achievements().defineIfAbsent(...);
```

`RainsCore.isAvailable()` is there for a plugin that treats it as optional.
