# Winter Command

Integrates [Incendo Cloud](https://cloud.incendo.org/) with Winter components and dependency injection for **Paper** and **Velocity**. Core and Velocity require **Java 21**; Paper requires **Java 25**.

## Modules

| Artifact | Contents | Command sender |
| --- | --- | --- |
| `com.thewinterframework:command-core:3.1.0` | `@CommandComponent`, generic extensions, and shared registration | Generic (`C`) |
| `com.thewinterframework:command-paper:3.1.0` | Command manager, annotation parser, and Winter processor for Paper | `org.incendo.cloud.paper.util.sender.Source` |
| `com.thewinterframework:command-velocity:3.1.0` | Command manager, annotation parser, and Winter processor for Velocity | `com.velocitypowered.api.command.CommandSource` |

Each adapter includes `command-core` transitively. Use one adapter per plugin and annotation processor path. The core module and Velocity adapter have no dependencies on Bukkit or Paper.

## Versions

| Dependency | Version |
| --- | --- |
| Winter Core / Paper / Velocity | `2.3.0` |
| Cloud Core / Annotations | `2.1.0` |
| Cloud Paper / Velocity | `2.0.1` |
| Velocity API used for compilation | `3.4.0-SNAPSHOT` |
| Paper API used for compilation | `26.2.build.134-stable` |
| Adventure | `5.2.0` |

Cloud versions its [core and annotations](https://github.com/Incendo/cloud/releases) independently from its [Minecraft integrations](https://github.com/Incendo/cloud-minecraft/releases). Dependency versions are centralized in `gradle.properties`.

## Installation

The following examples use Gradle Kotlin DSL and Maven Central coordinates for Winter `2.3.0` and Winter Command `3.1.0`.

```kotlin
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
```

### Paper

```kotlin
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    implementation("com.thewinterframework:paper:2.3.0")
    annotationProcessor("com.thewinterframework:paper:2.3.0")

    implementation("com.thewinterframework:command-paper:3.1.0")
    annotationProcessor("com.thewinterframework:command-paper:3.1.0")
}
```

The plugin must extend `PaperWinterPlugin` and declare `@WinterBootPlugin`. `PaperCommandModule` creates the command manager during plugin enable using `PaperCommandManager.buildOnEnable`. This adapter targets Paper **26.2** with Winter **2.3.0** and Java **25**.

```java
import com.thewinterframework.command.CommandComponent;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.paper.util.sender.Source;

@CommandComponent
public final class HelloCommand {
    @Command("hello")
    public void hello(final Source source) {
        source.source().sendMessage("Hello from Paper!");
    }
}
```

You can inject `PaperCommandManager<Source>`, `CommandManager<Source>`, and `AnnotationParser<Source>`. Both command manager types resolve to the same instance.

### Velocity

```kotlin
dependencies {
    implementation("com.thewinterframework:velocity:2.3.0")
    annotationProcessor("com.thewinterframework:velocity:2.3.0")

    implementation("com.thewinterframework:command-velocity:3.1.0")
    annotationProcessor("com.thewinterframework:command-velocity:3.1.0")
}
```

The plugin must extend `VelocityWinterPlugin` and declare both `@WinterBootPlugin` and Velocity's `@Plugin`. Its injected constructor receives Velocity's `Injector` and passes it to `super(injector)`. Winter inherits the bindings for `ProxyServer` and the plugin's own `PluginContainer`. The adapter creates `VelocityCommandManager` during module enable and uses `CommandSource` directly.

```java
import com.thewinterframework.command.CommandComponent;
import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.annotations.Command;

@CommandComponent
public final class HelloCommand {
    @Command("hello")
    public void hello(final CommandSource source) {
        source.sendMessage(Component.text("Hello from Velocity!"));
    }
}
```

You can inject `VelocityCommandManager<CommandSource>`, `CommandManager<CommandSource>`, and `AnnotationParser<CommandSource>`. Both command manager types resolve to the same instance.

## Command extensions

Winter discovers classes annotated with `@CommandComponent` and resolves their dependencies as singletons through the plugin injector. In addition to commands declared with Cloud annotations, it supports:

- `ArgumentParser<C, T>`: registers a parser for `T`, including inherited and sender-generic (`<C>`) implementations.
- `ParameterInjector<C, T>`: registers parameter injection for `T`, including inherited and sender-generic (`<C>`) implementations.
- `CommandManagerConfiguration<C>`: configures the command manager before annotated commands are parsed.
- `AnnotationParserDecorator<C>`: customizes the annotation parser before commands are registered.
- `SenderMapper<N, C>`: replaces the default sender mapper. `N` is `CommandSourceStack` on Paper and `CommandSource` on Velocity. `C` remains the adapter's command sender type.
- `ExecutionCoordinator<C>`: replaces the default `ExecutionCoordinator.simpleCoordinator()` on Paper and Velocity (can also be supplied via an explicit Guice `ExecutionCoordinator<C>` binding).

For example, a Velocity configuration implements:

```java
@CommandComponent
public final class Commands implements CommandManagerConfiguration<CommandSource> {
    @Override
    public void configure(final CommandManager<CommandSource> manager) {
        // Register Cloud options, parsers, or processors.
    }
}
```

Components can optionally declare Cloud's `@CommandContainer(priority = ...)` to control registration order (higher priority runs first). Parsers, parameter injectors, and decorators are registered first, configurations are applied next, and all active components are then batch-parsed together so cross-component `@Suggestions`, `@Parser`, `@Default`, and `@ExceptionHandler` methods resolve regardless of class order. Registered commands are automatically unregistered when the module disables. Use constructor injection for component dependencies. Command managers default to `ExecutionCoordinator.simpleCoordinator()` unless overridden; handlers must respect the platform's threading requirements.

## Optional Kotlin and `cloud-kotlin` support

Winter Command compiles against `cloud-kotlin` as a `compileOnly` dependency and exports **no transitive compile-time or runtime dependency** on Kotlin (`kotlin-stdlib`, `kotlin-reflect`, `kotlinx-coroutines`) or `cloud-kotlin`. Pure Java plugins need no extra libraries. At compile time, Winter's `@CommandComponent` processor generates direct singleton accessors and suspend-method metadata in `CommandComponentListWire`; at runtime, `CommandModule` probes the plugin `ClassLoader` without reflection and invokes available [Incendo Cloud Kotlin](https://cloud.incendo.org/kotlin/) APIs via direct typed bytecode calls:

- **Kotlin `object` and `companion object` components**: `@CommandComponent` can be placed on Kotlin classes, `object` declarations, or `companion object`s (including `@Inject` properties/methods) when processed with `kotlin("kapt")` and `-java-parameters`.
- **`org.incendo:cloud-kotlin-coroutines-annotations`**: `installCoroutineSupport` is automatically applied to `AnnotationParser<C>` (unless a custom `AnnotationParserDecorator<C>` already installed it), enabling `suspend` `@Command`, `@Suggestions` (including `Sequence<Suggestion>`), and `@Parser` functions as well as Kotlin default parameter values.
- **`org.incendo:cloud-kotlin-coroutines`**: `@CommandComponent` implementations of `SuspendingArgumentParser<C, T>` are automatically adapted and registered in `ParserRegistry<C>` before `CommandManagerConfiguration<C>` runs, and `@CommandComponent` implementations of `SuspendingSuggestionProvider<C>` and `SuspendingExecutionHandler<C>` are sender-type validated and injectable into builders.
- **Custom coroutine scope and context**: Bind `CoroutineScope` and/or `CoroutineContext` in Guice or annotate an implementation with `@CommandComponent`; otherwise `GlobalScope` and `EmptyCoroutineContext` are used (matching Cloud's defaults).
- **`org.incendo:cloud-kotlin-extensions`**: Builder DSL helpers (`buildAndRegister`, `MutableCommandBuilder`, `suspendingHandler`, etc.) work inside `CommandManagerConfiguration<C>`, and `asyncCoordinator()` can be wired via an `ExecutionCoordinator<C>` binding or `@CommandComponent`.

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("kapt") version "2.1.0"
}

kotlin {
    compilerOptions {
        javaParameters.set(true)
    }
}

dependencies {
    implementation("com.thewinterframework:velocity:2.3.0")
    kapt("com.thewinterframework:velocity:2.3.0")

    implementation("com.thewinterframework:command-velocity:3.1.0")
    kapt("com.thewinterframework:command-velocity:3.1.0")

    // Add only the cloud-kotlin artifacts you want to use:
    implementation("org.incendo:cloud-kotlin-coroutines-annotations:2.1.0")
    implementation("org.incendo:cloud-kotlin-coroutines:2.1.0")
    implementation("org.incendo:cloud-kotlin-extensions:2.1.0")
}
```

## Migration from 2.0.0

The 3.x line changes artifact coordinates and extension contracts:

1. Replace `com.thewinterframework:command:2.0.0` with `command-paper:3.1.0` or `command-velocity:3.1.0` in both your dependencies and `annotationProcessor` configuration.
2. Keep the `com.thewinterframework.command.CommandComponent` import.
3. Add the command sender type to `CommandManagerConfiguration<C>`, `CommandConfigurationRegistrar<C>`, and `AnnotationParserDecorator<C>`.
4. Change the parameter of `CommandManagerConfiguration.configure` from `PaperCommandManager<Source>` to `CommandManager<Source>` on Paper, or to `CommandManager<CommandSource>` on Velocity. Inject the concrete command manager when you need platform-specific features.
5. If you registered the module manually, replace `CommandModule` with `com.thewinterframework.command.paper.PaperCommandModule` or `com.thewinterframework.command.velocity.VelocityCommandModule`. The adapter's annotation processor generates this registration automatically.

## Building and publishing locally

```powershell
.\gradlew.bat clean build
.\gradlew.bat publishToMavenLocal
```

Build with the included Gradle 9.1.0 wrapper and JDK 21 and JDK 25 available for the module toolchains.

For an unpublished Winter version in a sibling checkout, validate with `.\gradlew.bat build --include-build ..\winter`.

On Linux and macOS, use `./gradlew`. The build generates binary, source, and Javadoc JARs in `core/build/libs`, `paper/build/libs`, and `velocity/build/libs`. Published artifacts are named `command-core`, `command-paper`, and `command-velocity`.

Tests cover command execution and extensions, module generation for each platform, the separate annotation processor path, and injection of the real Velocity command manager. Verify execution on a running Paper server or Velocity proxy in the consuming plugin's integration environment.
