package com.thewinterframework.command;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.thewinterframework.command.config.CommandConfigurationRegistrar;
import com.thewinterframework.command.config.CommandManagerConfiguration;
import com.thewinterframework.command.decorator.AnnotationParserDecorator;
import com.thewinterframework.plugin.WinterPlugin;
import com.thewinterframework.plugin.module.Stage;
import com.thewinterframework.wire.condition.ComponentCondition;
import com.thewinterframework.wire.condition.annotation.Requires;
import com.thewinterframework.wire.condition.context.ConditionContext;
import io.leangen.geantyref.TypeToken;
import org.incendo.cloud.CloudCapability;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Default;
import org.incendo.cloud.annotations.processing.CommandContainer;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.component.DefaultValue;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.injection.ParameterInjector;
import org.incendo.cloud.internal.CommandRegistrationHandler;
import org.incendo.cloud.parser.ArgumentParseResult;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.util.annotation.AnnotationAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CommandModuleTest {
    @Test
    void bindsCommandComponentScopeAndRegistrarAsSingleton() {
        final var module = new TestModule(Set.of());
        final var injector = Guice.createInjector(module);

        assertSame(injector.getInstance(ScopedComponent.class), injector.getInstance(ScopedComponent.class));
        final var registrarKey = Key.get(new TypeLiteral<CommandConfigurationRegistrar<Sender>>() {});
        assertSame(injector.getInstance(registrarKey), injector.getInstance(registrarKey));
    }

    @Test
    void registersExtensionsConfiguresAndBatchParsesCrossComponentCommands() throws Exception {
        AlphaCrossComponentCommand.CONSTRUCTED.set(0);

        final var module = new TestModule(Set.of(
                ValueParser.class,
                SharedValueParser.class,
                SuperSenderInjector.class,
                ManagerConfiguration.class,
                ParserDecorator.class,
                CustomMapper.class,
                AlphaCrossComponentCommand.class,
                RecordCommand.class,
                ZetaSuggestionAndDefaultProvider.class
        ));
        final var events = new ArrayList<String>();
        final var captured = new AtomicReference<String>();
        final var manager = new TestManager(false);
        final var parser = new AnnotationParser<>(manager, Sender.class);

        final var plugin = mock(WinterPlugin.class);
        when(plugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);
        assertTrue(module.onLoad(plugin));

        final var injector = Guice.createInjector(module, binder -> {
            binder.bind(new TypeLiteral<List<String>>() {}).toInstance(events);
            binder.bind(new TypeLiteral<AtomicReference<String>>() {}).toInstance(captured);
            binder.bind(new TypeLiteral<CommandManager<Sender>>() {}).toInstance(manager);
            binder.bind(new TypeLiteral<AnnotationParser<Sender>>() {}).toInstance(parser);
        });
        when(plugin.getInjector()).thenReturn(injector);
        injector.injectMembers(module);

        assertSame(injector.getInstance(CustomMapper.class), module.selectedMapper(injector));
        assertTrue(module.onEnable(plugin));

        assertEquals(1, AlphaCrossComponentCommand.CONSTRUCTED.get());
        assertEquals(List.of("decorate", "configure", "build", "build"), events);

        final var sender = new Sender("console");
        manager.commandExecutor().executeCommand(sender, "test hello shared").join();
        assertEquals("console:hello:shared:injected:default-mode", captured.get());

        manager.commandExecutor().executeCommand(sender, "ping").join();
        assertEquals("pong:console", captured.get());

        assertTrue(module.onDisable(plugin));
    }

    @Test
    void ordersComponentsByCommandContainerPriorityThenClassName() throws Exception {
        final var module = new TestModule(Set.of(
                LowPriorityConfig.class,
                DefaultBetaConfig.class,
                DefaultAlphaConfig.class,
                HighPriorityConfig.class
        ));
        final var events = new ArrayList<String>();
        final var manager = new TestManager(false);
        final var parser = new AnnotationParser<>(manager, Sender.class);

        final var plugin = mock(WinterPlugin.class);
        when(plugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);
        assertTrue(module.onLoad(plugin));

        final var injector = Guice.createInjector(module, binder -> {
            binder.bind(new TypeLiteral<List<String>>() {}).toInstance(events);
            binder.bind(new TypeLiteral<CommandManager<Sender>>() {}).toInstance(manager);
            binder.bind(new TypeLiteral<AnnotationParser<Sender>>() {}).toInstance(parser);
        });
        when(plugin.getInjector()).thenReturn(injector);

        assertTrue(module.onEnable(plugin));
        assertEquals(List.of("high", "default-alpha", "default-beta", "low"), events);
    }

    @Test
    void skipsEnableConditionFailedSenderMapperWithoutInstantiatingIt() throws Exception {
        DisabledMapper.INSTANTIATED.set(false);

        final var module = new TestModule(Set.of(DisabledMapper.class));
        final var plugin = mock(WinterPlugin.class);
        when(plugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);
        assertTrue(module.onLoad(plugin));

        final var injector = Guice.createInjector(module);
        final SenderMapper<Integer, Sender> fallback = SenderMapper.create(i -> new Sender("fallback-" + i), s -> 0);

        assertSame(fallback, module.selectedMapper(injector, fallback));
        assertFalse(DisabledMapper.INSTANTIATED.get());
    }

    @Test
    void rejectsMalformedAndIncompatibleGenericExtensions() {
        assertExtensionRejected(Set.of(RawParser.class));
        assertExtensionRejected(Set.of(UnresolvedValueParser.class));
        assertExtensionRejected(Set.of(UnresolvedListValueParser.class));
        assertExtensionRejected(Set.of(SubSenderParser.class));
        assertExtensionRejected(Set.of(WrongSenderInjector.class));
        assertExtensionRejected(Set.of(WrongSenderConfig.class));
        assertExtensionRejected(Set.of(WrongSenderDecorator.class));
        assertExtensionRejected(Set.of(UnsatisfiedBoundedConfig.class));

        assertMapperRejected(Set.of(RawMapper.class));
        assertMapperRejected(Set.of(WrongNativeMapper.class));
        assertMapperRejected(Set.of(WrongMappedMapper.class));
    }

    @Test
    void unregistersRootCommandsWhenCapableAndHandlesUninjectedOnDisable() throws Exception {
        final var plugin = mock(WinterPlugin.class);
        when(plugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);

        final var uninjected = new TestModule(Set.of());
        assertTrue(uninjected.onLoad(plugin));
        assertTrue(uninjected.onDisable(plugin));

        final var captured = new AtomicReference<String>();
        final var module = new TestModule(Set.of(RecordCommand.class));
        final var manager = new TestManager(true);
        final var parser = new AnnotationParser<>(manager, Sender.class);

        assertTrue(module.onLoad(plugin));
        final var injector = Guice.createInjector(module, binder -> {
            binder.bind(new TypeLiteral<AtomicReference<String>>() {}).toInstance(captured);
            binder.bind(new TypeLiteral<CommandManager<Sender>>() {}).toInstance(manager);
            binder.bind(new TypeLiteral<AnnotationParser<Sender>>() {}).toInstance(parser);
        });
        when(plugin.getInjector()).thenReturn(injector);

        assertTrue(module.onEnable(plugin));
        assertTrue(manager.rootCommands().contains("ping"));

        assertTrue(module.onDisable(plugin));
        assertTrue(manager.rootCommands().isEmpty());
    }

    @Test
    void registrarValidatesArgumentsAndSupportsDynamicRegistration() {
        final var registrar = new CommandConfigurationRegistrar<Sender>();
        assertThrows(NullPointerException.class, () -> registrar.register(null));
        assertThrows(NullPointerException.class, () -> registrar.configure(null));

        final var order = new ArrayList<String>();
        final var manager = new TestManager(false);
        registrar.register(m -> {
            order.add("first");
            registrar.register(m2 -> order.add("second"));
        });
        registrar.configure(manager);
        assertEquals(List.of("first", "second"), order);
    }

    @Test
    void selectsExecutionCoordinatorFromComponentsBindingsOrFallback() throws Exception {
        DisabledCoordinator.INSTANTIATED.set(false);
        final var plugin = mock(WinterPlugin.class);
        when(plugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);
        final ExecutionCoordinator<Sender> fallback = ExecutionCoordinator.simpleCoordinator();

        final var componentModule = new TestModule(Set.of(
                DisabledCoordinator.class,
                LowPriorityCoordinator.class,
                HighPriorityCoordinator.class
        ));
        assertTrue(componentModule.onLoad(plugin));
        final var componentInjector = Guice.createInjector(componentModule);
        assertSame(
                componentInjector.getInstance(HighPriorityCoordinator.class),
                componentModule.selectedCoordinator(componentInjector, fallback)
        );
        assertFalse(DisabledCoordinator.INSTANTIATED.get());

        final ExecutionCoordinator<Sender> explicitBound = ExecutionCoordinator.asyncCoordinator();
        final var boundModule = new TestModule(Set.of(DisabledCoordinator.class));
        assertTrue(boundModule.onLoad(plugin));
        final var boundInjector = Guice.createInjector(boundModule, binder ->
                binder.bind(new TypeLiteral<ExecutionCoordinator<Sender>>() {}).toInstance(explicitBound));
        assertSame(explicitBound, boundModule.selectedCoordinator(boundInjector, fallback));

        final var fallbackModule = new TestModule(Set.of());
        assertTrue(fallbackModule.onLoad(plugin));
        final var fallbackInjector = Guice.createInjector(fallbackModule);
        assertSame(fallback, fallbackModule.selectedCoordinator(fallbackInjector, fallback));

        assertCoordinatorRejected(Set.of(RawCoordinator.class));
        assertCoordinatorRejected(Set.of(WrongSenderCoordinator.class));
    }

    @Test
    void supportsDirectCloudKotlinIntegrationAndDecoratorPrecedence() throws Exception {
        assertTrue(KotlinRuntime.HAS_COROUTINES);
        assertTrue(KotlinRuntime.HAS_COROUTINE_ANNOTATIONS);

        final var plugin = mock(WinterPlugin.class);
        when(plugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);

        final var seenParser = new AtomicBoolean();
        final var out = new AtomicReference<String>();
        final var usedScope = new AtomicReference<kotlinx.coroutines.CoroutineScope>();
        final var usedContext = new AtomicReference<kotlin.coroutines.CoroutineContext>();
        final var customContext = new DirectCustomCoroutineContext("bound-ctx");

        final var manager = new TestManager(true);
        final var parser = new AnnotationParser<>(manager, Sender.class);
        final var module = new TestModule(Set.of(
                DirectCustomScopeComponent.class,
                DirectSuspendingValueParser.class,
                DirectSuspendingExtensions.class,
                DirectVerifyParserRegisteredConfig.class,
                DirectCoroutineBackedCommand.class
        ));
        assertTrue(module.onLoad(plugin));

        final var injector = Guice.createInjector(module, binder -> {
            binder.bind(kotlin.coroutines.CoroutineContext.class).toInstance(customContext);
            binder.bind(AtomicBoolean.class).toInstance(seenParser);
            binder.bind(new TypeLiteral<AtomicReference<String>>() {}).toInstance(out);
            binder.bind(new TypeLiteral<AtomicReference<kotlinx.coroutines.CoroutineScope>>() {}).toInstance(usedScope);
            binder.bind(new TypeLiteral<AtomicReference<kotlin.coroutines.CoroutineContext>>() {}).toInstance(usedContext);
            binder.bind(new TypeLiteral<CommandManager<Sender>>() {}).toInstance(manager);
            binder.bind(new TypeLiteral<AnnotationParser<Sender>>() {}).toInstance(parser);
        });
        when(plugin.getInjector()).thenReturn(injector);
        injector.injectMembers(module);

        assertTrue(module.onEnable(plugin));
        assertTrue(seenParser.get());
        assertTrue(KotlinAnnotationsBridge.isCoroutineSupportInstalled(parser));

        final var initialSuggestionFactory = parser.suggestionProviderFactory();
        assertEquals(
                "org.incendo.cloud.kotlin.coroutines.annotations",
                initialSuggestionFactory.getClass().getPackageName()
        );

        manager.commandExecutor().executeCommand(new Sender("bob"), "kparse item").join();
        assertEquals("bob:sus-item", out.get());
        assertSame(customContext, usedContext.get());

        // Reloading on the same AnnotationParser must be idempotent and not reinstall coroutine factories
        assertTrue(module.onDisable(plugin));
        assertTrue(module.onEnable(plugin));
        assertSame(initialSuggestionFactory, parser.suggestionProviderFactory());

        // User AnnotationParserDecorator installing coroutine support takes precedence
        final var decoratedManager = new TestManager(false);
        final var decoratedParser = new AnnotationParser<>(decoratedManager, Sender.class);
        final var decoratorModule = new TestModule(Set.of(UserCoroutineDecorator.class));
        assertTrue(decoratorModule.onLoad(plugin));
        final var decoratorInjector = Guice.createInjector(decoratorModule, binder -> {
            binder.bind(new TypeLiteral<CommandManager<Sender>>() {}).toInstance(decoratedManager);
            binder.bind(new TypeLiteral<AnnotationParser<Sender>>() {}).toInstance(decoratedParser);
        });
        when(plugin.getInjector()).thenReturn(decoratorInjector);
        decoratorInjector.injectMembers(decoratorModule);
        assertTrue(decoratorModule.onEnable(plugin));
        assertSame(
                decoratorInjector.getInstance(UserCoroutineDecorator.class).installedFactory,
                decoratedParser.suggestionProviderFactory()
        );

        // Incompatible suspending generic sender parameters must fail fast before registration
        assertExtensionRejected(Set.of(WrongSenderSuspendingParser.class));
        assertExtensionRejected(Set.of(WrongSenderSuspendingSuggestion.class));
        assertExtensionRejected(Set.of(WrongSenderSuspendingHandler.class));
    }

    @Test
    void supportsKotlinSingletonsAndDetectsMissingCoroutinesWithoutReflection(
            @TempDir final Path tempDir
    ) throws Exception {
        final URL coreClassesUrl = CommandModule.class.getProtectionDomain().getCodeSource().getLocation();

        // 1. Kotlin object & companion object singletons via processor-generated CommandComponentWire
        //    in an isolated ClassLoader where cloud-kotlin and kotlinx-coroutines are absent
        final Path singletonsDir = tempDir.resolve("kt-singletons");
        final String bootPluginSource = """
                package ktsample;
                import com.google.inject.Injector;
                import com.thewinterframework.expression.ExpressionResolver;
                import com.thewinterframework.plugin.WinterBootPlugin;
                import com.thewinterframework.plugin.WinterPlugin;
                import com.thewinterframework.plugin.module.PluginModuleManager;
                import com.thewinterframework.plugin.platform.PlatformPluginManager;
                import com.thewinterframework.scheduler.PluginScheduler;
                import org.slf4j.Logger;
                import org.slf4j.helpers.NOPLogger;
                @WinterBootPlugin
                public final class TestBootPlugin implements WinterPlugin {
                    @Override public String namespace() { return "ktsample"; }
                    @Override public Injector getInjector() { return null; }
                    @Override public Logger getSLF4JLogger() { return NOPLogger.NOP_LOGGER; }
                    @Override public PluginModuleManager getModuleManager() { return null; }
                    @Override public ExpressionResolver getExpressionResolver() { return null; }
                    @Override public void setExpressionResolver(final ExpressionResolver resolver) {}
                    @Override public PluginScheduler getScheduler() { return null; }
                    @Override public PlatformPluginManager getPlatformManager() { return null; }
                }
                """;
        compileProcessedSources(singletonsDir, Map.of(
                "ktsample/TestBootPlugin.java", bootPluginSource,
                "ktsample/ObjectCommand.java", """
                        package ktsample;
                        import com.google.inject.Inject;
                        import com.thewinterframework.command.CommandComponent;
                        import com.thewinterframework.command.CommandModuleTest.Sender;
                        import java.util.List;
                        import java.util.concurrent.atomic.AtomicReference;
                        import org.incendo.cloud.annotations.Command;
                        @CommandComponent
                        public final class ObjectCommand {
                            public static final ObjectCommand INSTANCE = new ObjectCommand();
                            @Inject private static AtomicReference<String> staticOut;
                            private List<String> events;
                            private ObjectCommand() {}
                            @Inject
                            public void initInstance(final List<String> events) {
                                this.events = events;
                                events.add("object-init");
                            }
                            @Command("objcmd")
                            public void run(final Sender sender) {
                                staticOut.set("obj:" + sender.name() + ":" + events.size());
                            }
                        }
                        """,
                "ktsample/CompanionHost.java", """
                        package ktsample;
                        import com.google.inject.Inject;
                        import com.thewinterframework.command.CommandComponent;
                        import com.thewinterframework.command.CommandModuleTest.Sender;
                        import java.util.List;
                        import java.util.concurrent.atomic.AtomicReference;
                        import org.incendo.cloud.annotations.Command;
                        public final class CompanionHost {
                            public static final Companion Companion = new Companion(null);
                            @Inject private static AtomicReference<String> companionOut;
                            private CompanionHost() {}
                            @CommandComponent
                            public static final class Companion {
                                private List<String> events;
                                private Companion() {}
                                public Companion(final Void marker) {}
                                @Inject
                                public void initCompanion(final List<String> events) {
                                    this.events = events;
                                    events.add("companion-init");
                                }
                                @Command("compcmd")
                                public void run(final Sender sender) {
                                    companionOut.set("comp:" + sender.name() + ":" + events.size());
                                }
                            }
                        }
                        """,
                "ktsample/IsolatedModule.java", """
                        package ktsample;
                        import com.thewinterframework.command.CommandModule;
                        import com.thewinterframework.command.CommandModuleTest.Sender;
                        import io.leangen.geantyref.TypeToken;
                        public final class IsolatedModule extends CommandModule<Sender> {
                            public IsolatedModule() {
                                super(TypeToken.get(Sender.class));
                            }
                        }
                        """
        ));

        try (final var noKotlinLoader = new FilteringIsolatedClassLoader(
                new URL[]{singletonsDir.toUri().toURL(), coreClassesUrl},
                CommandModuleTest.class.getClassLoader(),
                Set.of("com.thewinterframework.command.", "ktsample."),
                Set.of("org/incendo/cloud/kotlin/", "kotlinx/coroutines/", "kotlin/")
        )) {
            final Class<?> isolatedRuntime = Class.forName(
                    "com.thewinterframework.command.KotlinRuntime", true, noKotlinLoader);
            final var coroutinesField = isolatedRuntime.getDeclaredField("HAS_COROUTINES");
            coroutinesField.setAccessible(true);
            final var annotationsField = isolatedRuntime.getDeclaredField("HAS_COROUTINE_ANNOTATIONS");
            annotationsField.setAccessible(true);
            assertFalse((Boolean) coroutinesField.get(null));
            assertFalse((Boolean) annotationsField.get(null));

            final var bootPlugin = (WinterPlugin) Class.forName("ktsample.TestBootPlugin", true, noKotlinLoader)
                    .getDeclaredConstructor()
                    .newInstance();
            final var isolatedModule = (com.thewinterframework.processor.module.ProcessorModule)
                    Class.forName("ktsample.IsolatedModule", true, noKotlinLoader)
                            .getDeclaredConstructor()
                            .newInstance();
            assertTrue(isolatedModule.onLoad(bootPlugin));

            final var events = new ArrayList<String>();
            final var out = new AtomicReference<String>();
            final var manager = new TestManager(false);
            final var parser = new AnnotationParser<>(manager, Sender.class);
            final var injector = Guice.createInjector(isolatedModule, binder -> {
                binder.bind(new TypeLiteral<List<String>>() {}).toInstance(events);
                binder.bind(new TypeLiteral<AtomicReference<String>>() {}).toInstance(out);
                binder.bind(new TypeLiteral<CommandManager<Sender>>() {}).toInstance(manager);
                binder.bind(new TypeLiteral<AnnotationParser<Sender>>() {}).toInstance(parser);
            });
            injector.injectMembers(isolatedModule);
            assertTrue(isolatedModule.onEnable(bootPlugin));
            assertEquals(2, events.size());
            assertTrue(events.containsAll(List.of("object-init", "companion-init")));

            final var sender = new Sender("alice");
            manager.commandExecutor().executeCommand(sender, "objcmd").join();
            assertEquals("obj:alice:2", out.get());
            manager.commandExecutor().executeCommand(sender, "compcmd").join();
            assertEquals("comp:alice:2", out.get());
        }

        // 2. Fail-fast diagnostic via CommandComponentWire when suspend @Command is enabled without
        //    org.incendo:cloud-kotlin-coroutines-annotations on the runtime classpath
        final Path missingCoroutinesDir = tempDir.resolve("kt-missing-coroutines");
        compileProcessedSources(missingCoroutinesDir, Map.of(
                "ktsample/TestBootPlugin.java", bootPluginSource,
                "ktsample/MissingCoroutineCommand.java", """
                        package ktsample;
                        import com.thewinterframework.command.CommandComponent;
                        import com.thewinterframework.command.CommandModuleTest.Sender;
                        import kotlin.coroutines.Continuation;
                        import org.incendo.cloud.annotations.Command;
                        @CommandComponent
                        public final class MissingCoroutineCommand {
                            @Command("suspendcmd")
                            public Object run(final Sender sender, final Continuation<? super Void> cont) {
                                return null;
                            }
                        }
                        """,
                "ktsample/IsolatedModule.java", """
                        package ktsample;
                        import com.thewinterframework.command.CommandModule;
                        import com.thewinterframework.command.CommandModuleTest.Sender;
                        import io.leangen.geantyref.TypeToken;
                        public final class IsolatedModule extends CommandModule<Sender> {
                            public IsolatedModule() {
                                super(TypeToken.get(Sender.class));
                            }
                        }
                        """
        ));

        try (final var missingAnnotationsLoader = new FilteringIsolatedClassLoader(
                new URL[]{missingCoroutinesDir.toUri().toURL(), coreClassesUrl},
                CommandModuleTest.class.getClassLoader(),
                Set.of("com.thewinterframework.command.", "ktsample."),
                Set.of("org/incendo/cloud/kotlin/coroutines/annotations/")
        )) {
            final Class<?> isolatedRuntime = Class.forName(
                    "com.thewinterframework.command.KotlinRuntime", true, missingAnnotationsLoader);
            final var coroutinesField = isolatedRuntime.getDeclaredField("HAS_COROUTINES");
            coroutinesField.setAccessible(true);
            final var annotationsField = isolatedRuntime.getDeclaredField("HAS_COROUTINE_ANNOTATIONS");
            annotationsField.setAccessible(true);
            assertTrue((Boolean) coroutinesField.get(null));
            assertFalse((Boolean) annotationsField.get(null));

            final var bootPlugin = (WinterPlugin) Class.forName(
                    "ktsample.TestBootPlugin", true, missingAnnotationsLoader
            ).getDeclaredConstructor().newInstance();
            final var isolatedModule = (com.thewinterframework.processor.module.ProcessorModule)
                    Class.forName("ktsample.IsolatedModule", true, missingAnnotationsLoader)
                            .getDeclaredConstructor()
                            .newInstance();
            assertTrue(isolatedModule.onLoad(bootPlugin));

            final var manager = new TestManager(false);
            final var parser = new AnnotationParser<>(manager, Sender.class);
            final var injector = Guice.createInjector(isolatedModule, binder -> {
                binder.bind(new TypeLiteral<CommandManager<Sender>>() {}).toInstance(manager);
                binder.bind(new TypeLiteral<AnnotationParser<Sender>>() {}).toInstance(parser);
            });
            injector.injectMembers(isolatedModule);

            final var ex = assertThrows(IllegalStateException.class, () -> isolatedModule.onEnable(bootPlugin));
            assertTrue(ex.getMessage().contains("org.incendo:cloud-kotlin-coroutines-annotations"));
            assertTrue(ex.getMessage().contains("MissingCoroutineCommand#run"));
        }
    }

    private static void compileProcessedSources(
            final Path outputDir, final Map<String, String> sources
    ) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            compiler = ServiceLoader.load(JavaCompiler.class).findFirst().orElse(null);
        }
        assertNotNull(compiler);

        final Path procDir = outputDir.resolveSibling(outputDir.getFileName() + "-proc");
        final Path procSrc = procDir.resolve("src/testproc/TestCommandProcessor.java");
        Files.createDirectories(procSrc.getParent());
        Files.writeString(procSrc, """
                package testproc;
                import com.thewinterframework.command.processor.AbstractCommandComponentProcessor;
                public final class TestCommandProcessor extends AbstractCommandComponentProcessor {
                    @Override
                    protected String moduleClassName() {
                        return "ktsample.IsolatedModule";
                    }
                }
                """, StandardCharsets.UTF_8);
        final Path procClasses = procDir.resolve("classes");
        Files.createDirectories(procClasses);
        try (final var fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            final var units = fm.getJavaFileObjectsFromFiles(List.of(procSrc.toFile()));
            final var opts = List.of(
                    "-source", "21",
                    "-target", "21",
                    "-proc:none",
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", procClasses.toString()
            );
            assertTrue(compiler.getTask(null, fm, null, opts, null, units).call());
        }
        final Path serviceFile = procClasses.resolve(
                "META-INF/services/com.thewinterframework.processor.handler.WinterAnnotationProcessor");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, "testproc.TestCommandProcessor\n", StandardCharsets.UTF_8);

        final Path srcDir = outputDir.resolveSibling(outputDir.getFileName() + "-src");
        final Path genDir = outputDir.resolveSibling(outputDir.getFileName() + "-gen");
        Files.createDirectories(outputDir);
        Files.createDirectories(genDir);
        final var sourceFiles = new ArrayList<File>(sources.size());
        for (final var entry : sources.entrySet()) {
            final Path file = srcDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            sourceFiles.add(file.toFile());
        }

        final String processorPath = procClasses + File.pathSeparator + System.getProperty("java.class.path");
        try (final var fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            final var units = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            final var options = List.of(
                    "-source", "21",
                    "-target", "21",
                    "-parameters",
                    "-classpath", System.getProperty("java.class.path"),
                    "-processorpath", processorPath,
                    "-s", genDir.toString(),
                    "-d", outputDir.toString()
            );
            assertTrue(compiler.getTask(null, fileManager, null, options, null, units).call());
        }
    }

    private static void assertCoordinatorRejected(final Set<Class<?>> components) {
        final var module = new TestModule(components);
        final var plugin = mock(WinterPlugin.class);
        when(plugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);

        assertThrows(IllegalArgumentException.class, () -> {
            module.onLoad(plugin);
            final var injector = Guice.createInjector(module);
            module.selectedCoordinator(injector, ExecutionCoordinator.simpleCoordinator());
        });
    }

    private static void assertExtensionRejected(final Set<Class<?>> components) {
        final var module = new TestModule(components);
        final var manager = new TestManager(false);
        final var parser = new AnnotationParser<>(manager, Sender.class);
        final var plugin = mock(WinterPlugin.class);
        when(plugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);

        assertThrows(IllegalArgumentException.class, () -> {
            module.onLoad(plugin);
            final var injector = Guice.createInjector(module, binder -> {
                binder.bind(new TypeLiteral<CommandManager<Sender>>() {}).toInstance(manager);
                binder.bind(new TypeLiteral<AnnotationParser<Sender>>() {}).toInstance(parser);
            });
            when(plugin.getInjector()).thenReturn(injector);
            module.onEnable(plugin);
        });
    }

    private static void assertMapperRejected(final Set<Class<?>> components) {
        final var module = new TestModule(components);
        final var plugin = mock(WinterPlugin.class);
        when(plugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);

        assertThrows(IllegalArgumentException.class, () -> {
            module.onLoad(plugin);
            final var injector = Guice.createInjector(module);
            module.selectedMapper(injector);
        });
    }

    public interface SuperSender {
        String name();
    }

    public static class Sender implements SuperSender {
        private final String name;

        public Sender(final String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }
    }

    public static final class SubSender extends Sender {
        public SubSender(final String name) {
            super(name);
        }
    }

    public record Value(String raw) {}

    record SharedValue(String raw) {}

    record Injected(String raw) {}

    @CommandComponent
    static final class ScopedComponent {}

    static final class TestModule extends CommandModule<Sender> {
        TestModule(final Set<Class<?>> components) {
            super(TypeToken.get(Sender.class));
            final var wired = List.copyOf(components);
            this.wire = () -> wired;
            this.activeComponents.addAll(wired);
        }

        SenderMapper<Integer, Sender> selectedMapper(final Injector injector) {
            return selectedMapper(injector, SenderMapper.create(value -> new Sender("fallback"), sender -> 0));
        }

        SenderMapper<Integer, Sender> selectedMapper(
                final Injector injector, final SenderMapper<Integer, Sender> fallback
        ) {
            return senderMapper(injector, Integer.class, fallback);
        }

        ExecutionCoordinator<Sender> selectedCoordinator(
                final Injector injector, final ExecutionCoordinator<Sender> fallback
        ) {
            return executionCoordinator(injector, fallback);
        }
    }

    static final class TestManager extends CommandManager<Sender> {
        TestManager(final boolean deletable) {
            super(ExecutionCoordinator.simpleCoordinator(), CommandRegistrationHandler.nullCommandRegistrationHandler());
            if (deletable) {
                registerCapability(CloudCapability.StandardCapabilities.ROOT_COMMAND_DELETION);
            }
        }

        @Override
        public boolean hasPermission(final Sender sender, final String permission) {
            return true;
        }
    }

    abstract static class BaseParser<T> implements ArgumentParser<Sender, T> {}

    @CommandComponent
    static final class ValueParser extends BaseParser<Value> {
        @Override
        public ArgumentParseResult<Value> parse(final CommandContext<Sender> context, final CommandInput input) {
            return ArgumentParseResult.success(new Value(input.readString()));
        }
    }

    @CommandComponent
    static final class SharedValueParser<C extends SuperSender> implements ArgumentParser<C, SharedValue> {
        @Override
        public ArgumentParseResult<SharedValue> parse(final CommandContext<C> context, final CommandInput input) {
            return ArgumentParseResult.success(new SharedValue(input.readString()));
        }
    }

    @CommandComponent
    static final class SuperSenderInjector implements ParameterInjector<SuperSender, Injected> {
        @Override
        public Injected create(final CommandContext<SuperSender> context, final AnnotationAccessor annotations) {
            return new Injected("injected");
        }
    }

    @CommandComponent
    static final class ManagerConfiguration implements CommandManagerConfiguration<Sender> {
        private final List<String> events;

        @Inject
        ManagerConfiguration(final List<String> events) {
            this.events = events;
        }

        @Override
        public void configure(final CommandManager<Sender> commandManager) {
            events.add("configure");
        }
    }

    @CommandComponent
    static final class ParserDecorator implements AnnotationParserDecorator<Sender> {
        private final List<String> events;

        @Inject
        ParserDecorator(final List<String> events) {
            this.events = events;
        }

        @Override
        public void decorate(final AnnotationParser<Sender> annotationParser) {
            events.add("decorate");
            annotationParser.registerBuilderDecorator(new org.incendo.cloud.annotations.BuilderDecorator<Sender>() {
                @Override
                public Command.Builder<Sender> decorate(final Command.Builder<Sender> builder) {
                    events.add("build");
                    return builder;
                }
            });
        }
    }

    @CommandComponent
    static final class CustomMapper implements SenderMapper<Integer, Sender> {
        @Override
        public Sender map(final Integer base) {
            return new Sender("custom-" + base);
        }

        @Override
        public Integer reverse(final Sender mapped) {
            return 1;
        }
    }

    @CommandComponent
    public static final class AlphaCrossComponentCommand {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();

        private final AtomicReference<String> captured;

        @Inject
        public AlphaCrossComponentCommand(final AtomicReference<String> captured) {
            CONSTRUCTED.incrementAndGet();
            this.captured = captured;
        }

        @org.incendo.cloud.annotations.Command("test <value> <shared> [mode]")
        public void test(
                final Sender sender,
                @Argument(value = "value", suggestions = "shared-values") final Value value,
                @Argument("shared") final SharedValue shared,
                @Argument("mode") @Default(name = "default-mode") final String mode,
                final Injected injected
        ) {
            captured.set(sender.name() + ":" + value.raw() + ":" + shared.raw() + ":" + injected.raw() + ":" + mode);
        }
    }

    @CommandComponent
    public record RecordCommand(AtomicReference<String> captured) {
        @Inject
        public RecordCommand {}

        @org.incendo.cloud.annotations.Command("ping")
        public void ping(final Sender sender) {
            captured.set("pong:" + sender.name());
        }
    }

    @CommandComponent
    public static final class ZetaSuggestionAndDefaultProvider {
        @Suggestions("shared-values")
        public List<String> suggestions(final CommandContext<Sender> context, final CommandInput input) {
            return List.of("hello", "world");
        }

        @Default(name = "default-mode")
        public DefaultValue<Sender, String> defaultMode(final java.lang.reflect.Parameter parameter) {
            return DefaultValue.constant("default-mode");
        }
    }

    @CommandComponent
    @CommandContainer(priority = 10)
    static final class HighPriorityConfig implements CommandManagerConfiguration<Sender> {
        @Inject private List<String> events;

        @Override
        public void configure(final CommandManager<Sender> commandManager) {
            events.add("high");
        }
    }

    @CommandComponent
    static final class DefaultAlphaConfig implements CommandManagerConfiguration<Sender> {
        @Inject private List<String> events;

        @Override
        public void configure(final CommandManager<Sender> commandManager) {
            events.add("default-alpha");
        }
    }

    @CommandComponent
    static final class DefaultBetaConfig implements CommandManagerConfiguration<Sender> {
        @Inject private List<String> events;

        @Override
        public void configure(final CommandManager<Sender> commandManager) {
            events.add("default-beta");
        }
    }

    @CommandComponent
    @CommandContainer(priority = 0)
    static final class LowPriorityConfig implements CommandManagerConfiguration<Sender> {
        @Inject private List<String> events;

        @Override
        public void configure(final CommandManager<Sender> commandManager) {
            events.add("low");
        }
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Requires(DisabledEnableCondition.class)
    @interface DisabledOnEnable {}

    public static final class DisabledEnableCondition implements ComponentCondition {
        public DisabledEnableCondition() {}
        @Override
        public Stage getStage() {
            return Stage.ENABLE;
        }

        @Override
        public boolean matches(final ConditionContext context, final Annotation annotation) {
            return false;
        }
    }

    @CommandComponent
    @DisabledOnEnable
    static final class DisabledMapper implements SenderMapper<Integer, Sender> {
        static final AtomicBoolean INSTANTIATED = new AtomicBoolean();

        DisabledMapper() {
            INSTANTIATED.set(true);
        }

        @Override
        public Sender map(final Integer base) {
            return new Sender("disabled");
        }

        @Override
        public Integer reverse(final Sender mapped) {
            return -1;
        }
    }

    @CommandComponent
    @SuppressWarnings("rawtypes")
    static final class RawParser implements ArgumentParser {
        @Override
        public ArgumentParseResult parse(final CommandContext commandContext, final CommandInput commandInput) {
            return ArgumentParseResult.success("raw");
        }
    }

    @CommandComponent
    static final class UnresolvedValueParser<T> implements ArgumentParser<Sender, T> {
        @Override
        public ArgumentParseResult<T> parse(final CommandContext<Sender> context, final CommandInput input) {
            throw new UnsupportedOperationException();
        }
    }

    @CommandComponent
    static final class UnresolvedListValueParser<T> implements ArgumentParser<Sender, List<T>> {
        @Override
        public ArgumentParseResult<List<T>> parse(final CommandContext<Sender> context, final CommandInput input) {
            throw new UnsupportedOperationException();
        }
    }

    @CommandComponent
    static final class SubSenderParser implements ArgumentParser<SubSender, Value> {
        @Override
        public ArgumentParseResult<Value> parse(final CommandContext<SubSender> context, final CommandInput input) {
            return ArgumentParseResult.success(new Value(input.readString()));
        }
    }

    @CommandComponent
    static final class WrongSenderInjector implements ParameterInjector<String, Injected> {
        @Override
        public Injected create(final CommandContext<String> context, final AnnotationAccessor annotations) {
            return new Injected("bad");
        }
    }

    @CommandComponent
    static final class WrongSenderConfig implements CommandManagerConfiguration<String> {
        @Override
        public void configure(final CommandManager<String> commandManager) {}
    }

    @CommandComponent
    static final class WrongSenderDecorator implements AnnotationParserDecorator<String> {
        @Override
        public void decorate(final AnnotationParser<String> annotationParser) {}
    }

    @CommandComponent
    static final class UnsatisfiedBoundedConfig<C extends CharSequence> implements CommandManagerConfiguration<C> {
        @Override
        public void configure(final CommandManager<C> commandManager) {}
    }

    @CommandComponent
    @SuppressWarnings("rawtypes")
    static final class RawMapper implements SenderMapper {
        @Override
        public Object map(final Object base) {
            return base;
        }

        @Override
        public Object reverse(final Object mapped) {
            return mapped;
        }
    }

    @CommandComponent
    static final class WrongNativeMapper implements SenderMapper<String, Sender> {
        @Override
        public Sender map(final String base) {
            return new Sender(base);
        }

        @Override
        public String reverse(final Sender mapped) {
            return mapped.name();
        }
    }

    @CommandComponent
    static final class WrongMappedMapper implements SenderMapper<Integer, SubSender> {
        @Override
        public SubSender map(final Integer base) {
            return new SubSender("sub-" + base);
        }

        @Override
        public Integer reverse(final SubSender mapped) {
            return 0;
        }
    }

    abstract static class DelegatingCoordinator<C> implements ExecutionCoordinator<C> {
        private final ExecutionCoordinator<C> delegate = ExecutionCoordinator.simpleCoordinator();

        @Override
        public CompletableFuture<org.incendo.cloud.execution.CommandResult<C>> coordinateExecution(
                final org.incendo.cloud.CommandTree<C> commandTree,
                final CommandContext<C> commandContext,
                final CommandInput commandInput
        ) {
            return delegate.coordinateExecution(commandTree, commandContext, commandInput);
        }

        @Override
        public <S extends org.incendo.cloud.suggestion.Suggestion> CompletableFuture<org.incendo.cloud.suggestion.Suggestions<C, S>> coordinateSuggestions(
                final org.incendo.cloud.CommandTree<C> commandTree,
                final CommandContext<C> context,
                final CommandInput commandInput,
                final org.incendo.cloud.suggestion.SuggestionMapper<S> mapper
        ) {
            return delegate.coordinateSuggestions(commandTree, context, commandInput, mapper);
        }
    }

    @CommandComponent
    @DisabledOnEnable
    static final class DisabledCoordinator extends DelegatingCoordinator<Sender> {
        static final AtomicBoolean INSTANTIATED = new AtomicBoolean();

        DisabledCoordinator() {
            INSTANTIATED.set(true);
        }
    }

    @CommandComponent
    @CommandContainer(priority = 1)
    static final class LowPriorityCoordinator extends DelegatingCoordinator<Sender> {}

    @CommandComponent
    @CommandContainer(priority = 5)
    static final class HighPriorityCoordinator extends DelegatingCoordinator<Sender> {}

    @CommandComponent
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class RawCoordinator implements ExecutionCoordinator {
        @Override
        public CompletableFuture coordinateExecution(
                final org.incendo.cloud.CommandTree commandTree,
                final CommandContext commandContext,
                final CommandInput commandInput
        ) {
            return ExecutionCoordinator.simpleCoordinator()
                    .coordinateExecution(commandTree, commandContext, commandInput);
        }

        @Override
        public CompletableFuture coordinateSuggestions(
                final org.incendo.cloud.CommandTree commandTree,
                final CommandContext context,
                final CommandInput commandInput,
                final org.incendo.cloud.suggestion.SuggestionMapper mapper
        ) {
            return ExecutionCoordinator.simpleCoordinator()
                    .coordinateSuggestions(commandTree, context, commandInput, mapper);
        }
    }

    @CommandComponent
    static final class WrongSenderCoordinator extends DelegatingCoordinator<String> {}

    static final class FilteringIsolatedClassLoader extends URLClassLoader {
        private final Set<String> childFirstPrefixes;
        private final Set<String> blockedResourcePrefixes;

        FilteringIsolatedClassLoader(
                final URL[] urls,
                final ClassLoader parent,
                final Set<String> childFirstPrefixes,
                final Set<String> blockedResourcePrefixes
        ) {
            super(urls, parent);
            this.childFirstPrefixes = childFirstPrefixes;
            this.blockedResourcePrefixes = blockedResourcePrefixes;
        }

        @Override
        public URL getResource(final String name) {
            for (final String blocked : blockedResourcePrefixes) {
                if (name.startsWith(blocked)) {
                    return null;
                }
            }
            return super.getResource(name);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                final String resourcePath = name.replace('.', '/') + ".class";
                for (final String blocked : blockedResourcePrefixes) {
                    if (resourcePath.startsWith(blocked)) {
                        throw new ClassNotFoundException(name);
                    }
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && isChildFirst(name)) {
                    try {
                        loaded = findClass(name);
                    } catch (final ClassNotFoundException ignored) {
                        // Fall back to parent when not in child URLs
                    }
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private boolean isChildFirst(final String name) {
            if (name.startsWith("com.thewinterframework.command.CommandModuleTest")) {
                return false;
            }
            for (final String prefix : childFirstPrefixes) {
                if (name.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class DirectCustomCoroutineContext extends kotlin.coroutines.AbstractCoroutineContextElement {
        private static final kotlin.coroutines.CoroutineContext.Key<DirectCustomCoroutineContext> KEY =
                new kotlin.coroutines.CoroutineContext.Key<>() {};
        private final String label;

        DirectCustomCoroutineContext(final String label) {
            super(KEY);
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    @CommandComponent
    public static final class DirectCustomScopeComponent implements kotlinx.coroutines.CoroutineScope {
        @Override
        public kotlin.coroutines.CoroutineContext getCoroutineContext() {
            return kotlinx.coroutines.Dispatchers.getUnconfined();
        }
    }

    @CommandComponent
    public static final class DirectSuspendingValueParser
            implements org.incendo.cloud.kotlin.coroutines.SuspendingArgumentParser<Sender, Value> {
        private final AtomicReference<kotlin.coroutines.CoroutineContext> usedContext;

        @Inject
        public DirectSuspendingValueParser(
                final AtomicReference<kotlin.coroutines.CoroutineContext> usedContext
        ) {
            this.usedContext = usedContext;
        }

        @Override
        public Object invoke(
                final CommandContext<Sender> commandContext,
                final CommandInput commandInput,
                final kotlin.coroutines.Continuation<? super ArgumentParseResult<Value>> continuation
        ) {
            usedContext.set(continuation.getContext().get(DirectCustomCoroutineContext.KEY));
            return ArgumentParseResult.success(new Value("sus-" + commandInput.readString()));
        }

        @Override
        public ArgumentParser<Sender, Value> asArgumentParser(
                final kotlinx.coroutines.CoroutineScope scope,
                final kotlin.coroutines.CoroutineContext context
        ) {
            return org.incendo.cloud.kotlin.coroutines.SuspendingArgumentParser.Companion
                    .createArgumentParser(scope, context, this);
        }
    }

    @CommandComponent
    public static final class DirectSuspendingExtensions
            implements org.incendo.cloud.kotlin.coroutines.SuspendingSuggestionProvider<Sender>,
            org.incendo.cloud.kotlin.coroutines.SuspendingExecutionHandler<Sender> {
        @Override
        public Object invoke(
                final CommandContext<Sender> context,
                final CommandInput input,
                final kotlin.coroutines.Continuation<
                        ? super Iterable<? extends org.incendo.cloud.suggestion.Suggestion>> continuation
        ) {
            return List.of();
        }

        @Override
        public Object invoke(
                final CommandContext<Sender> commandContext,
                final kotlin.coroutines.Continuation<? super kotlin.Unit> continuation
        ) {
            return kotlin.Unit.INSTANCE;
        }

        @Override
        public org.incendo.cloud.suggestion.SuggestionProvider<Sender> asSuggestionProvider(
                final kotlinx.coroutines.CoroutineScope scope,
                final kotlin.coroutines.CoroutineContext context
        ) {
            return org.incendo.cloud.kotlin.coroutines.SuspendingSuggestionProvider.Companion
                    .createSuggestionProvider(scope, context, this);
        }

        @Override
        public org.incendo.cloud.execution.CommandExecutionHandler<Sender> asCommandExecutionHandler(
                final kotlinx.coroutines.CoroutineScope scope,
                final kotlin.coroutines.CoroutineContext context
        ) {
            return org.incendo.cloud.kotlin.coroutines.SuspendingExecutionHandler.Companion
                    .createCommandExecutionHandler(scope, context, this);
        }
    }

    @CommandComponent
    public static final class DirectVerifyParserRegisteredConfig implements CommandManagerConfiguration<Sender> {
        private final AtomicBoolean seenParser;

        @Inject
        public DirectVerifyParserRegisteredConfig(final AtomicBoolean seenParser) {
            this.seenParser = seenParser;
        }

        @Override
        public void configure(final CommandManager<Sender> manager) {
            seenParser.set(
                    manager.parserRegistry()
                            .createParser(TypeToken.get(Value.class), org.incendo.cloud.parser.ParserParameters.empty())
                            .isPresent()
            );
        }
    }

    @CommandComponent
    public static final class DirectCoroutineBackedCommand {
        private final AtomicReference<String> out;

        @Inject
        public DirectCoroutineBackedCommand(final AtomicReference<String> out) {
            this.out = out;
        }

        @org.incendo.cloud.annotations.Command("kparse <val>")
        public void run(final Sender sender, @Argument("val") final Value val) {
            out.set(sender.name() + ":" + val.raw());
        }
    }

    @CommandComponent
    public static final class UserCoroutineDecorator implements AnnotationParserDecorator<Sender> {
        org.incendo.cloud.annotations.suggestion.SuggestionProviderFactory<Sender> installedFactory;

        @Override
        public void decorate(final AnnotationParser<Sender> parser) {
            org.incendo.cloud.kotlin.coroutines.annotations.KotlinAnnotatedMethodsKt.installCoroutineSupport(
                    parser,
                    kotlinx.coroutines.GlobalScope.INSTANCE,
                    kotlin.coroutines.EmptyCoroutineContext.INSTANCE,
                    true
            );
            installedFactory = parser.suggestionProviderFactory();
        }
    }

    @CommandComponent
    static final class WrongSenderSuspendingParser
            implements org.incendo.cloud.kotlin.coroutines.SuspendingArgumentParser<SubSender, Value> {
        @Override
        public Object invoke(
                final CommandContext<SubSender> commandContext,
                final CommandInput commandInput,
                final kotlin.coroutines.Continuation<? super ArgumentParseResult<Value>> continuation
        ) {
            return ArgumentParseResult.success(new Value(commandInput.readString()));
        }

        @Override
        public ArgumentParser<SubSender, Value> asArgumentParser(
                final kotlinx.coroutines.CoroutineScope scope,
                final kotlin.coroutines.CoroutineContext context
        ) {
            return org.incendo.cloud.kotlin.coroutines.SuspendingArgumentParser.Companion
                    .createArgumentParser(scope, context, this);
        }
    }

    @CommandComponent
    static final class WrongSenderSuspendingSuggestion
            implements org.incendo.cloud.kotlin.coroutines.SuspendingSuggestionProvider<String> {
        @Override
        public Object invoke(
                final CommandContext<String> context,
                final CommandInput input,
                final kotlin.coroutines.Continuation<
                        ? super Iterable<? extends org.incendo.cloud.suggestion.Suggestion>> continuation
        ) {
            return List.of();
        }

        @Override
        public org.incendo.cloud.suggestion.SuggestionProvider<String> asSuggestionProvider(
                final kotlinx.coroutines.CoroutineScope scope,
                final kotlin.coroutines.CoroutineContext context
        ) {
            return org.incendo.cloud.kotlin.coroutines.SuspendingSuggestionProvider.Companion
                    .createSuggestionProvider(scope, context, this);
        }
    }

    @CommandComponent
    static final class WrongSenderSuspendingHandler
            implements org.incendo.cloud.kotlin.coroutines.SuspendingExecutionHandler<String> {
        @Override
        public Object invoke(
                final CommandContext<String> commandContext,
                final kotlin.coroutines.Continuation<? super kotlin.Unit> continuation
        ) {
            return kotlin.Unit.INSTANCE;
        }

        @Override
        public org.incendo.cloud.execution.CommandExecutionHandler<String> asCommandExecutionHandler(
                final kotlinx.coroutines.CoroutineScope scope,
                final kotlin.coroutines.CoroutineContext context
        ) {
            return org.incendo.cloud.kotlin.coroutines.SuspendingExecutionHandler.Companion
                    .createCommandExecutionHandler(scope, context, this);
        }
    }
}
