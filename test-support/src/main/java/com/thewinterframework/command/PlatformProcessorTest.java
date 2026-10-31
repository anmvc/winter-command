package com.thewinterframework.command;

import com.thewinterframework.processor.handler.WinterAnnotationProcessor;
import com.thewinterframework.processor.wire.ClassListWire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformProcessorTest {
    @TempDir Path directory;

    @Test
    void discoversOnlyTheSelectedCommandProcessor() {
        final var platform = System.getProperty("command.test.platform");
        final var handlers = ServiceLoader.load(WinterAnnotationProcessor.class).stream()
                .map(ServiceLoader.Provider::type)
                .filter(type -> type.getName().startsWith("com.thewinterframework.command."))
                .toList();
        assertEquals(1, handlers.size());
        assertTrue(handlers.getFirst().getName().contains("." + platform + "."));
        if (platform.equals("velocity")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("org.bukkit.plugin.java.JavaPlugin"));
        } else if (platform.equals("paper")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("com.velocitypowered.api.proxy.ProxyServer"));
        }
    }

    @Test
    void compilesAConsumerAndGeneratesItsPlatformModule() throws Exception {
        final var platform = System.getProperty("command.test.platform");
        final var moduleName = platform.equals("paper") ? "PaperCommandModule" : "VelocityCommandModule";
        final var senderType = platform.equals("paper")
                ? "org.incendo.cloud.paper.util.sender.Source"
                : "com.velocitypowered.api.command.CommandSource";
        final var body = platform.equals("paper")
                ? "sender.source().sendMessage(\"Hello from Paper!\");"
                : "sender.sendMessage(net.kyori.adventure.text.Component.text(\"Hello from Velocity!\"));";

        final var sources = Files.createDirectories(directory.resolve("sources/example"));
        final var plugin = writePluginSource(sources, platform);
        final var testCommand = Files.writeString(sources.resolve("TestCommand.java"), """
                package example;
                @com.thewinterframework.command.CommandComponent
                public final class TestCommand {
                    @org.incendo.cloud.annotations.Command("hello")
                    public void hello(%s sender) { %s }
                }
                """.formatted(senderType, body));
        final var recordCommand = Files.writeString(sources.resolve("RecordCommand.java"), """
                package example;
                @com.thewinterframework.command.CommandComponent
                public record RecordCommand() {
                    @org.incendo.cloud.annotations.Command("ping")
                    public void ping(%s sender) { %s }
                }
                """.formatted(senderType, body));
        final var outerCommands = Files.writeString(sources.resolve("OuterCommands.java"), """
                package example;
                public final class OuterCommands {
                    @com.thewinterframework.command.CommandComponent
                    public static final class NestedCommand {
                        @org.incendo.cloud.annotations.Command("nested")
                        public void nested(%s sender) { %s }
                    }
                }
                """.formatted(senderType, body));
        final var genericComponent = Files.writeString(sources.resolve("GenericComponent.java"), """
                package example;
                @com.thewinterframework.command.CommandComponent
                public final class GenericComponent<C> {
                }
                """);

        final var output = Files.createDirectories(directory.resolve("classes"));
        final var generated = Files.createDirectories(directory.resolve("generated"));
        final var result = compile(
                List.of(plugin, testCommand, recordCommand, outerCommands, genericComponent),
                output,
                generated,
                null
        );
        assertTrue(result.success(), () -> result.diagnostics().toString());

        final var provider = Files.readString(generated.resolve(
                "example/autogenerated_winter_modules/ProcessorModuleProvider.java"));
        assertTrue(provider.contains("com.thewinterframework.command." + platform + "." + moduleName));
        assertFalse(provider.contains(platform.equals("paper") ? "VelocityCommandModule" : "PaperCommandModule"));

        try (final var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            final var wire = (ClassListWire) loader.loadClass(
                    "example.autogenerated_winter_modules.CommandComponentListWire").getConstructor().newInstance();
            assertEquals(
                    List.of(
                            loader.loadClass("example.GenericComponent"),
                            loader.loadClass("example.OuterCommands$NestedCommand"),
                            loader.loadClass("example.RecordCommand"),
                            loader.loadClass("example.TestCommand")
                    ),
                    List.copyOf(wire.getWiredClasses())
            );
            final var componentWire = (com.thewinterframework.command.processor.CommandComponentWire) wire;
            assertTrue(componentWire.singletonProviders().isEmpty());
            assertTrue(componentWire.staticInjectionTargets().isEmpty());
            assertTrue(componentWire.suspendingCommandMethods().isEmpty());
        }
    }

    @Test
    void generatesDirectSingletonSuppliersAndSuspendMetadataInCommandComponentWire() throws Exception {
        final var platform = System.getProperty("command.test.platform");
        final var root = directory.resolve("kotlin-wire");
        final var sources = Files.createDirectories(root.resolve("sources"));
        final var exampleDir = Files.createDirectories(sources.resolve("example"));
        final var kotlinCoroutinesDir = Files.createDirectories(sources.resolve("kotlin/coroutines"));

        final var plugin = writePluginSource(exampleDir, platform);
        final var continuation = Files.writeString(kotlinCoroutinesDir.resolve("Continuation.java"), """
                package kotlin.coroutines;
                public interface Continuation<T> {}
                """);
        final var objectCmd = Files.writeString(exampleDir.resolve("ObjectCommand.java"), """
                package example;
                import com.thewinterframework.command.CommandComponent;
                @CommandComponent
                public final class ObjectCommand {
                    public static final ObjectCommand INSTANCE = new ObjectCommand();
                    private ObjectCommand() {}
                }
                """);
        final var companionHost = Files.writeString(exampleDir.resolve("CompanionHost.java"), """
                package example;
                import com.thewinterframework.command.CommandComponent;
                public final class CompanionHost {
                    public static final Companion Companion = new Companion(null);
                    private CompanionHost() {}
                    @CommandComponent
                    public static final class Companion {
                        private Companion() {}
                        public Companion(final Void marker) {}
                    }
                }
                """);
        final var namedHost = Files.writeString(exampleDir.resolve("NamedCompanionHost.java"), """
                package example;
                import com.thewinterframework.command.CommandComponent;
                public final class NamedCompanionHost {
                    public static final Commands Commands = new Commands(null);
                    private NamedCompanionHost() {}
                    @CommandComponent
                    public static final class Commands {
                        private Commands() {}
                        public Commands(final Void marker) {}
                    }
                }
                """);
        final var suspendCmd = Files.writeString(exampleDir.resolve("SuspendCommand.java"), """
                package example;
                import com.thewinterframework.command.CommandComponent;
                import kotlin.coroutines.Continuation;
                import org.incendo.cloud.annotations.Command;
                @CommandComponent
                public final class SuspendCommand {
                    @Command("suspendtest")
                    public Object run(final Continuation<? super Void> continuation) {
                        return null;
                    }
                }
                """);

        final var output = Files.createDirectories(root.resolve("classes"));
        final var generated = Files.createDirectories(root.resolve("generated"));
        final var result = compile(
                List.of(plugin, continuation, objectCmd, companionHost, namedHost, suspendCmd),
                output,
                generated,
                null
        );
        assertTrue(result.success(), () -> result.diagnostics().toString());

        try (final var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            final var wire = (com.thewinterframework.command.processor.CommandComponentWire) loader.loadClass(
                    "example.autogenerated_winter_modules.CommandComponentListWire").getConstructor().newInstance();
            final var objectClass = loader.loadClass("example.ObjectCommand");
            final var hostClass = loader.loadClass("example.CompanionHost");
            final var companionClass = loader.loadClass("example.CompanionHost$Companion");
            final var namedHostClass = loader.loadClass("example.NamedCompanionHost");
            final var namedCompanionClass = loader.loadClass("example.NamedCompanionHost$Commands");
            final var suspendClass = loader.loadClass("example.SuspendCommand");

            assertSame(
                    objectClass.getField("INSTANCE").get(null),
                    wire.singletonProviders().get(objectClass).get()
            );
            assertSame(
                    hostClass.getField("Companion").get(null),
                    wire.singletonProviders().get(companionClass).get()
            );
            assertSame(
                    namedHostClass.getField("Commands").get(null),
                    wire.singletonProviders().get(namedCompanionClass).get()
            );
            assertEquals(objectClass, wire.staticInjectionTargets().get(objectClass));
            assertEquals(hostClass, wire.staticInjectionTargets().get(companionClass));
            assertEquals(namedHostClass, wire.staticInjectionTargets().get(namedCompanionClass));
            assertEquals(Map.of(suspendClass, "SuspendCommand#run"), wire.suspendingCommandMethods());
        }
    }

    @Test
    void rejectsInvalidCommandComponentTargetsAtCompileTime() throws Exception {
        final var platform = System.getProperty("command.test.platform");

        assertCompilationFails(platform, "interface-target", Map.of(
                "BadInterface.java", """
                        package example;
                        @com.thewinterframework.command.CommandComponent
                        public interface BadInterface {}
                        """
        ));
        assertCompilationFails(platform, "abstract-target", Map.of(
                "BadAbstract.java", """
                        package example;
                        @com.thewinterframework.command.CommandComponent
                        public abstract class BadAbstract {}
                        """
        ));
        assertCompilationFails(platform, "package-private-target", Map.of(
                "BadPackagePrivate.java", """
                        package example;
                        @com.thewinterframework.command.CommandComponent
                        final class BadPackagePrivate {}
                        """
        ));
        assertCompilationFails(platform, "non-public-enclosing-target", Map.of(
                "PackageOuter.java", """
                        package example;
                        final class PackageOuter {
                            @com.thewinterframework.command.CommandComponent
                            public static final class HiddenNested {}
                        }
                        """
        ));
        assertCompilationFails(platform, "inner-class-target", Map.of(
                "OuterWithInner.java", """
                        package example;
                        public final class OuterWithInner {
                            @com.thewinterframework.command.CommandComponent
                            public final class InnerComponent {}
                        }
                        """
        ));
    }

    @Test
    void reportsErrorWhenMultipleCommandPlatformProcessorsAreActive() throws Exception {
        final var platform = System.getProperty("command.test.platform");
        final var extraProcSrc = Files.createDirectories(directory.resolve("extra-proc-src/example/proc"));
        final var extraProcOut = Files.createDirectories(directory.resolve("extra-proc-classes"));
        final var extraProcJava = Files.writeString(extraProcSrc.resolve("OtherCommandProcessor.java"), """
                package example.proc;
                import com.thewinterframework.command.processor.AbstractCommandComponentProcessor;
                public final class OtherCommandProcessor extends AbstractCommandComponentProcessor {
                    @Override
                    protected String moduleClassName() {
                        return "com.thewinterframework.command.other.OtherCommandModule";
                    }
                }
                """);
        final var compiler = systemCompiler();
        assertNotNull(compiler);
        final var procDiag = new DiagnosticCollector<JavaFileObject>();
        try (final var manager = compiler.getStandardFileManager(procDiag, null, null)) {
            final var opts = List.of(
                    "-source", "21",
                    "-target", "21",
                    "-classpath", System.getProperty("command.test.processorpath"),
                    "-proc:none",
                    "-d", extraProcOut.toString()
            );
            assertTrue(
                    Boolean.TRUE.equals(compiler.getTask(
                            null, manager, procDiag, opts, null, manager.getJavaFileObjectsFromPaths(List.of(extraProcJava))
                    ).call()),
                    () -> procDiag.getDiagnostics().toString()
            );
        }
        final var servicesDir = Files.createDirectories(extraProcOut.resolve("META-INF/services"));
        Files.writeString(
                servicesDir.resolve(WinterAnnotationProcessor.class.getName()),
                "example.proc.OtherCommandProcessor\n"
        );

        final var root = directory.resolve("conflict-target");
        final var sources = Files.createDirectories(root.resolve("sources/example"));
        final var plugin = writePluginSource(sources, platform);
        final var output = Files.createDirectories(root.resolve("classes"));
        final var generated = Files.createDirectories(root.resolve("generated"));

        final var combinedProcessorPath = System.getProperty("command.test.processorpath")
                + File.pathSeparator + extraProcOut;
        final var result = compile(List.of(plugin), output, generated, combinedProcessorPath);
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(
                d -> d.getKind() == Diagnostic.Kind.ERROR
                        && d.getMessage(null).contains("Multiple Winter Command platform processors are active")
        ));
    }

    private void assertCompilationFails(
            final String platform, final String label, final Map<String, String> extraSources
    ) throws Exception {
        final var root = directory.resolve(label);
        final var sources = Files.createDirectories(root.resolve("sources/example"));
        final var files = new ArrayList<Path>();
        files.add(writePluginSource(sources, platform));
        for (final var entry : extraSources.entrySet()) {
            files.add(Files.writeString(sources.resolve(entry.getKey()), entry.getValue()));
        }
        final var output = Files.createDirectories(root.resolve("classes"));
        final var generated = Files.createDirectories(root.resolve("generated"));
        final var result = compile(files, output, generated, null);
        assertFalse(result.success());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
    }

    private static Path writePluginSource(final Path sourcesDir, final String platform) throws IOException {
        final var pluginBody = platform.equals("paper")
                ? "public final class TestPlugin extends com.thewinterframework.paper.PaperWinterPlugin {}"
                : """
                    @com.velocitypowered.api.plugin.Plugin(id = "command-test", name = "Command Test", version = "1.0")
                    public final class TestPlugin extends com.thewinterframework.velocity.VelocityWinterPlugin {
                        @com.google.inject.Inject
                        public TestPlugin(com.google.inject.Injector injector) { super(injector); }
                    }
                    """;
        return Files.writeString(sourcesDir.resolve("TestPlugin.java"), """
                package example;
                @com.thewinterframework.plugin.WinterBootPlugin
                %s
                """.formatted(pluginBody));
    }

    private static CompilationResult compile(
            final List<Path> sourceFiles,
            final Path output,
            final Path generated,
            final String processorPathOverride
    ) throws IOException {
        final var diagnostics = new DiagnosticCollector<JavaFileObject>();
        final var compiler = systemCompiler();
        assertNotNull(compiler);
        try (final var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            final var processorPath = processorPathOverride != null
                    ? processorPathOverride
                    : System.getProperty("command.test.processorpath");
            final var options = List.of(
                    "-source", "21",
                    "-target", "21",
                    "-classpath", System.getProperty("command.test.classpath"),
                    "-processorpath", processorPath,
                    "-d", output.toString(),
                    "-s", generated.toString(),
                    "-parameters"
            );
            final var task = compiler.getTask(
                    null,
                    manager,
                    diagnostics,
                    options,
                    null,
                    manager.getJavaFileObjectsFromPaths(sourceFiles)
            );
            final boolean ok = Boolean.TRUE.equals(task.call());
            return new CompilationResult(ok, diagnostics.getDiagnostics());
        }
    }

    private static JavaCompiler systemCompiler() {
        final var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler != null) {
            return compiler;
        }
        return java.util.ServiceLoader.load(JavaCompiler.class).findFirst().orElse(null);
    }

    private record CompilationResult(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {}
}
