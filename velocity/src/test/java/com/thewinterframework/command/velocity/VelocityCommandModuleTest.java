package com.thewinterframework.command.velocity;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.ProvisionException;
import com.google.inject.TypeLiteral;
import com.thewinterframework.command.CommandComponent;
import com.thewinterframework.command.config.CommandConfigurationRegistrar;
import com.thewinterframework.plugin.WinterPlugin;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.CommandTree;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.execution.CommandResult;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.velocity.VelocityCommandManager;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.slf4j.helpers.NOPLogger;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityCommandModuleTest {
    @Test
    void inheritsVelocityBindingsAndSharesTheCommandManager() {
        final var server = mock(ProxyServer.class);
        final var eventManager = mock(EventManager.class);
        when(server.getEventManager()).thenReturn(eventManager);
        final var plugin = mock(PluginContainer.class);
        final var parent = Guice.createInjector(binder -> {
            binder.bind(ProxyServer.class).toInstance(server);
            binder.bind(PluginContainer.class).toInstance(plugin);
        });
        final var module = new VelocityCommandModule();
        final var injector = parent.createChildInjector(module);
        final var secondInjector = parent.createChildInjector(new VelocityCommandModule());
        final var registrarKey = Key.get(new TypeLiteral<CommandConfigurationRegistrar<CommandSource>>() {});
        assertNotSame(injector.getInstance(registrarKey), secondInjector.getInstance(registrarKey));

        injector.injectMembers(module);
        final var manager = injector.getInstance(Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {}));
        assertSame(manager, injector.getInstance(Key.get(new TypeLiteral<CommandManager<CommandSource>>() {})));
        assertSame(manager, injector.getInstance(Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {})));
        assertNotNull(injector.getInstance(Key.get(new TypeLiteral<AnnotationParser<CommandSource>>() {})));
        final var sender = mock(CommandSource.class);
        assertSame(sender, manager.senderMapper().map(sender));
        assertSame(sender, manager.senderMapper().reverse(sender));
        when(sender.hasPermission("command.test")).thenReturn(true);
        assertTrue(manager.hasPermission(sender, "command.test"));
        verify(eventManager).register(eq(plugin), eq(ServerPreConnectEvent.class), any());
    }

    @Test
    void registersExecutesAndUnregistersCommandsOnDisable() throws Exception {
        final var plugin = mock(PluginContainer.class);
        final var server = mock(ProxyServer.class);
        final var eventManager = mock(EventManager.class);
        final var commands = mock(com.velocitypowered.api.command.CommandManager.class);
        final var metaBuilder = mock(CommandMeta.Builder.class, Answers.RETURNS_SELF);
        when(server.getEventManager()).thenReturn(eventManager);
        when(server.getCommandManager()).thenReturn(commands);
        when(commands.metaBuilder(any(BrigadierCommand.class))).thenReturn(metaBuilder);
        when(metaBuilder.build()).thenReturn(mock(CommandMeta.class));

        final var executed = new AtomicBoolean();
        final var parent = Guice.createInjector(binder -> {
            binder.bind(PluginContainer.class).toInstance(plugin);
            binder.bind(ProxyServer.class).toInstance(server);
            binder.bind(AtomicBoolean.class).toInstance(executed);
        });

        final var module = new VelocityCommandModule();
        final var winter = mock(WinterPlugin.class);
        when(winter.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);
        wireComponents(module, List.of(PingCommand.class));
        assertTrue(module.onLoad(winter));

        final var child = parent.createChildInjector(module);
        child.injectMembers(module);
        when(winter.getInjector()).thenReturn(child);

        assertTrue(module.onEnable(winter));

        final var manager = child.getInstance(Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {}));
        final var sender = mock(CommandSource.class);
        when(sender.hasPermission(any())).thenReturn(true);
        manager.commandExecutor().executeCommand(sender, "ping").join();
        assertTrue(executed.get());

        assertTrue(module.onDisable(winter));
        verify(commands).unregister("ping");
        verify(commands).unregister("pong");
    }

    @Test
    void validatesVelocitySenderMapperAndHandlesUninjectedOnDisableSafely() throws Exception {
        final var winter = mock(WinterPlugin.class);
        when(winter.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);

        final var uninjected = new VelocityCommandModule();
        assertTrue(uninjected.onDisable(winter));

        final var plugin = mock(PluginContainer.class);
        final var server = mock(ProxyServer.class);
        when(server.getEventManager()).thenReturn(mock(EventManager.class));
        when(server.getCommandManager()).thenReturn(mock(com.velocitypowered.api.command.CommandManager.class));

        final var parent = Guice.createInjector(binder -> {
            binder.bind(PluginContainer.class).toInstance(plugin);
            binder.bind(ProxyServer.class).toInstance(server);
        });

        final var validModule = new VelocityCommandModule();
        wireComponents(validModule, List.of(GenericIdentityMapper.class));
        assertTrue(validModule.onLoad(winter));
        final var validChild = parent.createChildInjector(validModule);
        assertSame(
                validChild.getInstance(GenericIdentityMapper.class),
                validChild.getInstance(Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {})).senderMapper()
        );

        final var invalidModule = new VelocityCommandModule();
        wireComponents(invalidModule, List.of(WrongVelocityMapper.class));
        assertTrue(invalidModule.onLoad(winter));
        final var invalidChild = parent.createChildInjector(invalidModule);
        final var thrown = assertThrows(
                ProvisionException.class,
                () -> invalidChild.getInstance(Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {}))
        );
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }

    @Test
    void usesCustomExecutionCoordinatorComponentAndRejectsIncompatibleCoordinator() throws Exception {
        final var winter = mock(WinterPlugin.class);
        when(winter.getSLF4JLogger()).thenReturn(NOPLogger.NOP_LOGGER);
        final var plugin = mock(PluginContainer.class);
        final var server = mock(ProxyServer.class);
        final var commands = mock(com.velocitypowered.api.command.CommandManager.class);
        final var metaBuilder = mock(CommandMeta.Builder.class, Answers.RETURNS_SELF);
        when(server.getEventManager()).thenReturn(mock(EventManager.class));
        when(server.getCommandManager()).thenReturn(commands);
        when(commands.metaBuilder(any(BrigadierCommand.class))).thenReturn(metaBuilder);
        when(metaBuilder.build()).thenReturn(mock(CommandMeta.class));

        final var executed = new AtomicBoolean();
        final var coordinated = new AtomicBoolean();
        final var parent = Guice.createInjector(binder -> {
            binder.bind(PluginContainer.class).toInstance(plugin);
            binder.bind(ProxyServer.class).toInstance(server);
            binder.bind(AtomicBoolean.class).toInstance(executed);
        });

        final var module = new VelocityCommandModule();
        wireComponents(module, List.of(TrackingVelocityCoordinator.class, PingCommand.class));
        assertTrue(module.onLoad(winter));
        final var child = parent.createChildInjector(module);
        child.getInstance(TrackingVelocityCoordinator.class).coordinated = coordinated;
        child.injectMembers(module);
        when(winter.getInjector()).thenReturn(child);
        assertTrue(module.onEnable(winter));

        final var sender = mock(CommandSource.class);
        when(sender.hasPermission(any())).thenReturn(true);
        child.getInstance(Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {}))
                .commandExecutor()
                .executeCommand(sender, "ping")
                .join();
        assertTrue(coordinated.get());
        assertTrue(executed.get());

        final var badModule = new VelocityCommandModule();
        wireComponents(badModule, List.of(WrongVelocityCoordinator.class));
        assertTrue(badModule.onLoad(winter));
        final var badChild = parent.createChildInjector(badModule);
        final var thrown = assertThrows(
                ProvisionException.class,
                () -> badChild.getInstance(Key.get(new TypeLiteral<VelocityCommandManager<CommandSource>>() {}))
        );
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }

    private static void wireComponents(
            final VelocityCommandModule module, final List<Class<?>> components
    ) throws Exception {
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

    @CommandComponent
    public static final class PingCommand {
        private final AtomicBoolean executed;

        @Inject
        public PingCommand(final AtomicBoolean executed) {
            this.executed = executed;
        }

        @Command("ping|pong")
        public void ping(final CommandSource source) {
            executed.set(true);
        }
    }

    @CommandComponent
    public static final class GenericIdentityMapper<S> implements SenderMapper<S, S> {
        @Override
        public S map(final S base) {
            return base;
        }

        @Override
        public S reverse(final S mapped) {
            return mapped;
        }
    }

    @CommandComponent
    public static final class WrongVelocityMapper implements SenderMapper<String, CommandSource> {
        @Override
        public CommandSource map(final String base) {
            return mock(CommandSource.class);
        }

        @Override
        public String reverse(final CommandSource mapped) {
            return "";
        }
    }

    @CommandComponent
    public static final class TrackingVelocityCoordinator implements ExecutionCoordinator<CommandSource> {
        private final ExecutionCoordinator<CommandSource> delegate = ExecutionCoordinator.simpleCoordinator();
        AtomicBoolean coordinated;

        @Override
        public CompletableFuture<CommandResult<CommandSource>> coordinateExecution(
                final CommandTree<CommandSource> commandTree,
                final CommandContext<CommandSource> commandContext,
                final CommandInput commandInput
        ) {
            if (coordinated != null) {
                coordinated.set(true);
            }
            return delegate.coordinateExecution(commandTree, commandContext, commandInput);
        }

        @Override
        public <S extends org.incendo.cloud.suggestion.Suggestion> CompletableFuture<org.incendo.cloud.suggestion.Suggestions<CommandSource, S>> coordinateSuggestions(
                final CommandTree<CommandSource> commandTree,
                final CommandContext<CommandSource> context,
                final CommandInput commandInput,
                final org.incendo.cloud.suggestion.SuggestionMapper<S> mapper
        ) {
            return delegate.coordinateSuggestions(commandTree, context, commandInput, mapper);
        }
    }

    @CommandComponent
    public static final class WrongVelocityCoordinator implements ExecutionCoordinator<String> {
        private final ExecutionCoordinator<String> delegate = ExecutionCoordinator.simpleCoordinator();

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
