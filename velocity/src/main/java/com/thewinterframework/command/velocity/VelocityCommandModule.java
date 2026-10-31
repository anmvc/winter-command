package com.thewinterframework.command.velocity;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.thewinterframework.command.CommandModule;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.velocity.VelocityCommandManager;

import java.util.List;

/** Winter command integration using Velocity's native command sources. */
public final class VelocityCommandModule extends CommandModule<CommandSource> {
    @Inject(optional = true)
    private ProxyServer server;

    @Provides
    @Singleton
    VelocityCommandManager<CommandSource> velocityCommandManager(
            final PluginContainer plugin, final ProxyServer server, final Injector injector
    ) {
        return new VelocityCommandManager<>(
                plugin,
                server,
                executionCoordinator(injector, ExecutionCoordinator.simpleCoordinator()),
                senderMapper(injector, CommandSource.class, SenderMapper.identity())
        );
    }

    @Provides
    @Singleton
    CommandManager<CommandSource> commandManager(final VelocityCommandManager<CommandSource> commandManager) {
        return commandManager;
    }

    @Provides
    @Singleton
    AnnotationParser<CommandSource> annotationParser(final CommandManager<CommandSource> commandManager) {
        return new AnnotationParser<>(commandManager, CommandSource.class);
    }

    @Override
    protected void unregisterCommands() {
        super.unregisterCommands();
        final var manager = injectedCommandManager();
        if (manager == null || server == null) {
            return;
        }
        final var platformCommands = server.getCommandManager();
        if (platformCommands == null) {
            return;
        }
        final var commands = manager.commands();
        if (commands == null) {
            return;
        }
        for (final var command : List.copyOf(commands)) {
            final var root = command.rootComponent();
            if (root == null) {
                continue;
            }
            platformCommands.unregister(root.name());
            for (final var alias : root.alternativeAliases()) {
                platformCommands.unregister(alias);
            }
        }
    }
}
