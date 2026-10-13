# Crucible

Minecraft 1.21.1 mod for NeoForge 21.1.

## Requirements

- JDK 21
- Gradle (only once, to create the wrapper, see below)

## First-time setup

This template does not include the Gradle wrapper jar. Create it once:

    gradle wrapper

After that, use `./gradlew` (Windows: `gradlew.bat`). If you already have a NeoForge MDK
(or any Gradle project on 8.x), you can copy `gradlew`, `gradlew.bat` and
`gradle/wrapper/gradle-wrapper.jar` from it instead.

## Common commands

    ./gradlew runClient    # launch the game with the mod
    ./gradlew runServer    # launch a dedicated server
    ./gradlew build        # build the jar into build/libs/

Open the folder as a Gradle project in IntelliJ IDEA.

## Layout

- `gradle.properties`: mod id, name, version and NeoForge version
- `src/main/java/com/crucible/Crucible.java`: mod entry point
- `src/main/templates/META-INF/neoforge.mods.toml`: mod metadata (filled in from gradle.properties)
