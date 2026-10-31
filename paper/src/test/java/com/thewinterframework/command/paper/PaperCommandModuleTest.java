package com.thewinterframework.command.paper;

import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.ProvisionException;
import com.google.inject.TypeLiteral;
import com.google.inject.util.Modules;
import com.thewinterframework.command.CommandComponent;
import com.thewinterframework.command.config.CommandConfigurationRegistrar;
import com.thewinterframework.plugin.WinterPlugin;
import com.thewinterframework.plugin.module.Stage;
import com.thewinterframework.wire.condition.ComponentCondition;
import com.thewinterframework.wire.condition.annotation.Requires;
import com.thewinterframework.wire.condition.context.ConditionContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.CloudCapability;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.CommandTree;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.execution.CommandResult;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.paper.util.sender.Source;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "UnstableApiUsage"})
class PaperCommandModuleTest {
    @Test
    void buildsThePaperManagerOnPluginEnableAndSharesItAcrossBindings() {
        final var plugin = mock(JavaPlugin.class);
        final PaperCommandManager<Source> manager = mock(PaperCommandManager.class);
        final PaperCommandManager.Builder<Source> builder = mock(PaperCommandManager.Builder.class);
        final PaperCommandManager.CoordinatedBuilder<Source> coordinated = mock(PaperCommandManager.CoordinatedBuilder.class);
        when(builder.executionCoordinator(any())).thenReturn(coordinated);
        when(coordinated.buildOnEnable(plugin)).thenReturn(manager);
        final AnnotationParser<Source> parser = mock(AnnotationParser.class);

        final var module = new PaperCommandModule();
        try (final var factory = mockStatic(PaperCommandManager.class)) {
            factory.when(() -> PaperCommandManager.builder(any())).thenReturn(builder);

            final var injector = Guice.createInjector(Modules.override(module).with(binder -> {
                binder.bind(JavaPlugin.class).toInstance(plugin);
                binder.bind(new TypeLiteral<AnnotationParser<Source>>() {}).toInstance(parser);
            }));
            injector.injectMembers(module);

            assertSame(manager, injector.getInstance(Key.get(new TypeLiteral<PaperCommandManager<Source>>() {})));
            assertSame(manager, injector.getInstance(Key.get(new TypeLiteral<CommandManager<Source>>() {})));
            final var registrarKey = Key.get(new TypeLiteral<CommandConfigurationRegistrar<Source>>() {});
            assertSame(injector.getInstance(registrarKey), injector.getInstance(registrarKey));
            verify(coordinated).buildOnEnable(plugin);
            factory.verify(() -> PaperCommandManager.builder(any()));
        }
    }

    @Test
    void selectsValidPaperSenderMapperSkipsDisabledAndRejectsInvalidMapper() throws Exception {
        DisabledPaperMapper.INSTANTIATED.set(false);
        final var winterPlugin = mock(WinterPlugin.class);
        when(winterPlugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);
        final var javaPlugin = mock(JavaPlugin.class);
        final PaperCommandManager<Source> manager = mock(PaperCommandManager.class);
        final PaperCommandManager.Builder<Source> builder = mock(PaperCommandManager.Builder.class);
        final PaperCommandManager.CoordinatedBuilder<Source> coordinated = mock(PaperCommandManager.CoordinatedBuilder.class);
        when(builder.executionCoordinator(any())).thenReturn(coordinated);
        when(coordinated.buildOnEnable(javaPlugin)).thenReturn(manager);
        final AnnotationParser<Source> parser = mock(AnnotationParser.class);

        final var validModule = new PaperCommandModule();
        wireComponents(validModule, List.of(DisabledPaperMapper.class, ValidPaperMapper.class));
        assertTrue(validModule.onLoad(winterPlugin));

        try (final var factory = mockStatic(PaperCommandManager.class)) {
            factory.when(() -> PaperCommandManager.builder(any())).thenReturn(builder);
            final var injector = Guice.createInjector(Modules.override(validModule).with(binder -> {
                binder.bind(JavaPlugin.class).toInstance(javaPlugin);
                binder.bind(new TypeLiteral<AnnotationParser<Source>>() {}).toInstance(parser);
            }));
            assertSame(manager, injector.getInstance(Key.get(new TypeLiteral<PaperCommandManager<Source>>() {})));
            assertFalse(DisabledPaperMapper.INSTANTIATED.get());
            factory.verify(() -> PaperCommandManager.builder(injector.getInstance(ValidPaperMapper.class)));
        }

        final var invalidModule = new PaperCommandModule();
        wireComponents(invalidModule, List.of(WrongPaperMapper.class));
        assertTrue(invalidModule.onLoad(winterPlugin));

        try (final var factory = mockStatic(PaperCommandManager.class)) {
            factory.when(() -> PaperCommandManager.builder(any())).thenReturn(builder);
            final var injector = Guice.createInjector(Modules.override(invalidModule).with(binder -> {
                binder.bind(JavaPlugin.class).toInstance(javaPlugin);
                binder.bind(new TypeLiteral<AnnotationParser<Source>>() {}).toInstance(parser);
            }));
            final var thrown = assertThrows(
                    ProvisionException.class,
                    () -> injector.getInstance(Key.get(new TypeLiteral<PaperCommandManager<Source>>() {}))
            );
            assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        }
    }

    @Test
    void deletesRootCommandsOnDisableAndHandlesUninjectedOnDisableSafely() throws Exception {
        final var winterPlugin = mock(WinterPlugin.class);
        when(winterPlugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);

        final var uninjected = new PaperCommandModule();
        assertTrue(uninjected.onDisable(winterPlugin));

        final var javaPlugin = mock(JavaPlugin.class);
        final PaperCommandManager<Source> manager = mock(PaperCommandManager.class);
        when(manager.hasCapability(CloudCapability.StandardCapabilities.ROOT_COMMAND_DELETION)).thenReturn(true);
        when(manager.rootCommands()).thenReturn(List.of("alpha", "beta"));
        final PaperCommandManager.Builder<Source> builder = mock(PaperCommandManager.Builder.class);
        final PaperCommandManager.CoordinatedBuilder<Source> coordinated = mock(PaperCommandManager.CoordinatedBuilder.class);
        when(builder.executionCoordinator(any())).thenReturn(coordinated);
        when(coordinated.buildOnEnable(javaPlugin)).thenReturn(manager);
        final AnnotationParser<Source> parser = mock(AnnotationParser.class);

        final var module = new PaperCommandModule();
        try (final var factory = mockStatic(PaperCommandManager.class)) {
            factory.when(() -> PaperCommandManager.builder(any())).thenReturn(builder);
            final var injector = Guice.createInjector(Modules.override(module).with(binder -> {
                binder.bind(JavaPlugin.class).toInstance(javaPlugin);
                binder.bind(new TypeLiteral<AnnotationParser<Source>>() {}).toInstance(parser);
            }));
            injector.injectMembers(module);
        }

        assertTrue(module.onDisable(winterPlugin));
        verify(manager).deleteRootCommand("alpha");
        verify(manager).deleteRootCommand("beta");
    }

    @Test
    void passesCustomExecutionCoordinatorToPaperBuilderAndRejectsInvalidCoordinator() throws Exception {
        final var winterPlugin = mock(WinterPlugin.class);
        when(winterPlugin.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);
        final var javaPlugin = mock(JavaPlugin.class);
        final PaperCommandManager<Source> manager = mock(PaperCommandManager.class);
        final PaperCommandManager.Builder<Source> builder = mock(PaperCommandManager.Builder.class);
        final PaperCommandManager.CoordinatedBuilder<Source> coordinated = mock(PaperCommandManager.CoordinatedBuilder.class);
        when(builder.executionCoordinator(any())).thenReturn(coordinated);
        when(coordinated.buildOnEnable(javaPlugin)).thenReturn(manager);
        final AnnotationParser<Source> parser = mock(AnnotationParser.class);

        final var validModule = new PaperCommandModule();
        wireComponents(validModule, List.of(ValidPaperCoordinator.class));
        assertTrue(validModule.onLoad(winterPlugin));

        try (final var factory = mockStatic(PaperCommandManager.class)) {
            factory.when(() -> PaperCommandManager.builder(any())).thenReturn(builder);
            final var injector = Guice.createInjector(Modules.override(validModule).with(binder -> {
                binder.bind(JavaPlugin.class).toInstance(javaPlugin);
                binder.bind(new TypeLiteral<AnnotationParser<Source>>() {}).toInstance(parser);
            }));
            assertSame(manager, injector.getInstance(Key.get(new TypeLiteral<PaperCommandManager<Source>>() {})));
            verify(builder).executionCoordinator(injector.getInstance(ValidPaperCoordinator.class));
        }

        final var invalidModule = new PaperCommandModule();
        wireComponents(invalidModule, List.of(WrongPaperCoordinator.class));
        assertTrue(invalidModule.onLoad(winterPlugin));

        try (final var factory = mockStatic(PaperCommandManager.class)) {
            factory.when(() -> PaperCommandManager.builder(any())).thenReturn(builder);
            final var injector = Guice.createInjector(Modules.override(invalidModule).with(binder -> {
                binder.bind(JavaPlugin.class).toInstance(javaPlugin);
                binder.bind(new TypeLiteral<AnnotationParser<Source>>() {}).toInstance(parser);
            }));
            final var thrown = assertThrows(
                    ProvisionException.class,
                    () -> injector.getInstance(Key.get(new TypeLiteral<PaperCommandManager<Source>>() {}))
            );
            assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        }
    }

    private static void wireComponents(final PaperCommandModule module, final List<Class<?>> components)
            throws Exception {
        Class<?> current = module.getClass();
        while (current != null) {
            try {
                final Field field = current.getDeclaredField("wire");
                field.setAccessible(true);
                final com.thewinterframework.processor.wire.ClassListWire listWire = () -> components;
                field.set(module, listWire);
                return;
            } catch (final NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("wire field not found");
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Requires(DisabledCondition.class)
    @interface Disabled {}

    public static final class DisabledCondition implements ComponentCondition {
        public DisabledCondition() {}
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
    @Disabled
    public static final class DisabledPaperMapper implements SenderMapper<CommandSourceStack, Source> {
        static final AtomicBoolean INSTANTIATED = new AtomicBoolean();

        public DisabledPaperMapper() {
            INSTANTIATED.set(true);
        }

        @Override
        public Source map(final CommandSourceStack base) {
            return mock(Source.class);
        }

        @Override
        public CommandSourceStack reverse(final Source mapped) {
            return mock(CommandSourceStack.class);
        }
    }

    @CommandComponent
    public static final class ValidPaperMapper implements SenderMapper<CommandSourceStack, Source> {
        public ValidPaperMapper() {}

        @Override
        public Source map(final CommandSourceStack base) {
            return mock(Source.class);
        }

        @Override
        public CommandSourceStack reverse(final Source mapped) {
            return mock(CommandSourceStack.class);
        }
    }

    @CommandComponent
    public static final class WrongPaperMapper implements SenderMapper<CommandSender, Source> {
        public WrongPaperMapper() {}

        @Override
        public Source map(final CommandSender base) {
            return mock(Source.class);
        }

        @Override
        public CommandSender reverse(final Source mapped) {
            return mock(CommandSender.class);
        }
    }

    @CommandComponent
    public static final class ValidPaperCoordinator implements ExecutionCoordinator<Source> {
        private final ExecutionCoordinator<Source> delegate = ExecutionCoordinator.simpleCoordinator();

        public ValidPaperCoordinator() {}

        @Override
        public CompletableFuture<CommandResult<Source>> coordinateExecution(
                final CommandTree<Source> commandTree,
                final CommandContext<Source> commandContext,
                final CommandInput commandInput
        ) {
            return delegate.coordinateExecution(commandTree, commandContext, commandInput);
        }

        @Override
        public <S extends org.incendo.cloud.suggestion.Suggestion> CompletableFuture<org.incendo.cloud.suggestion.Suggestions<Source, S>> coordinateSuggestions(
                final CommandTree<Source> commandTree,
                final CommandContext<Source> context,
                final CommandInput commandInput,
                final org.incendo.cloud.suggestion.SuggestionMapper<S> mapper
        ) {
            return delegate.coordinateSuggestions(commandTree, context, commandInput, mapper);
        }
    }

    @CommandComponent
    public static final class WrongPaperCoordinator implements ExecutionCoordinator<String> {
        private final ExecutionCoordinator<String> delegate = ExecutionCoordinator.simpleCoordinator();

        public WrongPaperCoordinator() {}

        @Override
        public CompletableFuture<CommandResult<String>> coordinateExecution(
                final CommandTree<String> commandTree,
                final CommandContext<String> commandContext,
                final CommandInput commandInput
        ) {
            return delegate.coordinateExecution(commandTree, commandContext, commandInput);
        }

        @Override
        public <S extends org.incendo.cloud.suggestion.Suggestion> CompletableFuture<org.incendo.cloud.suggestion.Suggestions<String, S>> coordinateSuggestions(
                final CommandTree<String> commandTree,
                final CommandContext<String> context,
                final CommandInput commandInput,
                final org.incendo.cloud.suggestion.SuggestionMapper<S> mapper
        ) {
            return delegate.coordinateSuggestions(commandTree, context, commandInput, mapper);
        }
    }
}
