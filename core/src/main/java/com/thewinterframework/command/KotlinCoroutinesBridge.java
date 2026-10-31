package com.thewinterframework.command;

import com.google.inject.Injector;
import com.google.inject.Key;
import io.leangen.geantyref.TypeToken;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.GlobalScope;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.kotlin.coroutines.SuspendingArgumentParser;
import org.incendo.cloud.kotlin.coroutines.SuspendingExecutionHandler;
import org.incendo.cloud.kotlin.coroutines.SuspendingSuggestionProvider;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Direct-reference bridge to {@code org.incendo:cloud-kotlin-coroutines} and {@code kotlinx-coroutines}.
 * Loaded only when {@link KotlinRuntime#HAS_COROUTINES} is {@code true}.
 */
final class KotlinCoroutinesBridge {
    private static final Key<CoroutineScope> COROUTINE_SCOPE_KEY = Key.get(CoroutineScope.class);
    private static final Key<CoroutineContext> COROUTINE_CONTEXT_KEY = Key.get(CoroutineContext.class);

    private KotlinCoroutinesBridge() {
    }

    static void validateComponentTypes(
            final Class<?> componentClass,
            final Consumer<Class<?>> senderValidator,
            final Function<Class<?>, TypeToken<Object>> valueTypeResolver
    ) {
        if (SuspendingArgumentParser.class.isAssignableFrom(componentClass)) {
            senderValidator.accept(SuspendingArgumentParser.class);
            valueTypeResolver.apply(SuspendingArgumentParser.class);
        }
        if (SuspendingSuggestionProvider.class.isAssignableFrom(componentClass)) {
            senderValidator.accept(SuspendingSuggestionProvider.class);
        }
        if (SuspendingExecutionHandler.class.isAssignableFrom(componentClass)) {
            senderValidator.accept(SuspendingExecutionHandler.class);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static <C> void registerSuspendingParserIfPresent(
            final Object component,
            final CommandManager<C> commandManager,
            final Function<Class<?>, TypeToken<Object>> valueTypeResolver,
            final Supplier<CoroutineEnvironment> environmentSupplier
    ) {
        if (!(component instanceof SuspendingArgumentParser<?, ?> suspendingParser)) {
            return;
        }
        final TypeToken<Object> valueType = valueTypeResolver.apply(SuspendingArgumentParser.class);
        final CoroutineEnvironment env = environmentSupplier.get();
        final ArgumentParser<C, Object> adapted = SuspendingArgumentParser.Companion.createArgumentParser(
                env.scope(),
                env.context(),
                (SuspendingArgumentParser) suspendingParser
        );
        commandManager.parserRegistry().registerParser(ParserDescriptor.of(adapted, valueType));
    }

    static CoroutineEnvironment resolveEnvironment(
            final Injector injector,
            final List<Class<?>> enabledComponents,
            final Function<Class<?>, Object> instanceResolver
    ) {
        CoroutineScope scope = null;
        for (final Class<?> clazz : enabledComponents) {
            if (CoroutineScope.class.isAssignableFrom(clazz)) {
                scope = (CoroutineScope) instanceResolver.apply(clazz);
                break;
            }
        }
        if (scope == null) {
            final var scopeBinding = injector.getExistingBinding(COROUTINE_SCOPE_KEY);
            scope = scopeBinding != null ? scopeBinding.getProvider().get() : GlobalScope.INSTANCE;
        }

        CoroutineContext context = null;
        for (final Class<?> clazz : enabledComponents) {
            if (CoroutineContext.class.isAssignableFrom(clazz)
                    && !CoroutineScope.class.isAssignableFrom(clazz)) {
                context = (CoroutineContext) instanceResolver.apply(clazz);
                break;
            }
        }
        if (context == null) {
            final var contextBinding = injector.getExistingBinding(COROUTINE_CONTEXT_KEY);
            context = contextBinding != null
                    ? contextBinding.getProvider().get()
                    : EmptyCoroutineContext.INSTANCE;
        }

        return new CoroutineEnvironment(scope, context);
    }

    record CoroutineEnvironment(CoroutineScope scope, CoroutineContext context) {
    }
}
