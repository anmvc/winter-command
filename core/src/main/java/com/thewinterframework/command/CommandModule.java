package com.thewinterframework.command;

import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.util.Types;
import com.thewinterframework.command.config.CommandConfigurationRegistrar;
import com.thewinterframework.command.config.CommandManagerConfiguration;
import com.thewinterframework.command.decorator.AnnotationParserDecorator;
import com.thewinterframework.command.processor.CommandComponentWire;
import com.thewinterframework.plugin.WinterPlugin;
import com.thewinterframework.plugin.module.Stage;
import com.thewinterframework.wire.condition.context.ConditionContext;
import com.thewinterframework.wire.module.AbstractProcessorModule;
import io.leangen.geantyref.GenericTypeReflector;
import io.leangen.geantyref.TypeFactory;
import io.leangen.geantyref.TypeToken;
import org.incendo.cloud.CloudCapability;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.SenderMapper;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.processing.CommandContainer;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.injection.ParameterInjector;
import org.incendo.cloud.parser.ArgumentParser;
import org.incendo.cloud.parser.ParserDescriptor;

import java.lang.reflect.AccessFlag;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared component registration, specialized by each platform's sender type.
 * @param <C> the command sender type
 */
public abstract class CommandModule<C> extends AbstractProcessorModule {
    private static final Comparator<Class<?>> COMPONENT_COMPARATOR = Comparator
            .<Class<?>>comparingInt(clazz -> {
                final var container = clazz.getAnnotation(CommandContainer.class);
                return container != null ? container.priority() : 1;
            })
            .reversed()
            .thenComparing(Class::getName);

    private final Type senderType;
    private final List<Object> parsedComponents = new ArrayList<>();
    private final Set<Object> injectedSingletons = Collections.newSetFromMap(new IdentityHashMap<>());

    @Inject private CommandManager<C> commandManager;
    @Inject private CommandConfigurationRegistrar<C> configurationRegistrar;
    @Inject private AnnotationParser<C> annotationParser;
    @Inject private Injector injector;

    private WinterPlugin loadedPlugin;
    private Injector lastSingletonInjector;
    private Supplier<KotlinCoroutinesBridge.CoroutineEnvironment> activeCoroutineEnvironment;

    protected CommandModule() {
        super(CommandComponent.class);
        final var resolved = GenericTypeReflector.getTypeParameter(
                parameterizedComponentType(getClass()),
                CommandModule.class.getTypeParameters()[0]
        );
        this.senderType = (resolved != null && !containsTypeVariable(resolved)) ? resolved : null;
    }

    /**
     * Constructs a command module with an explicit command sender type token.
     * @param senderType the command sender type token
     */
    protected CommandModule(final TypeToken<C> senderType) {
        super(CommandComponent.class);
        Objects.requireNonNull(senderType, "senderType");
        if (containsTypeVariable(senderType.getType())) {
            throw new IllegalArgumentException(
                    "CommandModule senderType must not contain unresolved type variables: "
                            + senderType.getType().getTypeName()
            );
        }
        this.senderType = senderType.getType();
    }

    @Override
    public void configure(final Binder binder) {
        binder.bindScope(CommandComponent.class, Scopes.SINGLETON);
        if (senderType != null) {
            binder.bind(TypeLiteral.get(Types.newParameterizedType(CommandConfigurationRegistrar.class, senderType)))
                    .in(Scopes.SINGLETON);
        }
        if (wire instanceof CommandComponentWire componentWire) {
            final var singletonProviders = componentWire.singletonProviders();
            final var staticTargets = componentWire.staticInjectionTargets();
            final var registeredStaticTargets = new HashSet<Class<?>>();
            for (final var componentClass : activeComponents) {
                final var supplier = singletonProviders.get(componentClass);
                if (supplier != null) {
                    bindSingletonComponent(binder, componentClass, supplier);
                }
                final var staticTarget = staticTargets.get(componentClass);
                if (staticTarget != null) {
                    if (registeredStaticTargets.add(staticTarget)) {
                        binder.requestStaticInjection(staticTarget);
                    }
                    if (registeredStaticTargets.add(componentClass)) {
                        binder.requestStaticInjection(componentClass);
                    }
                }
            }
        }
    }

    @Override
    public boolean onLoad(final WinterPlugin plugin) throws Exception {
        Objects.requireNonNull(plugin, "plugin");
        loadedPlugin = plugin;
        var loaded = false;
        try {
            loaded = super.onLoad(plugin);
            return loaded;
        } finally {
            if (!loaded) {
                loadedPlugin = null;
            }
        }
    }

    /**
     * Selects a component sender mapper, falling back to the platform default.
     * @param injector the plugin injector
     * @param fallback the platform mapper
     * @param <N> the native platform sender type
     * @return the selected mapper
     */
    protected final <N> SenderMapper<N, C> senderMapper(
            final Injector injector, final SenderMapper<N, C> fallback
    ) {
        Objects.requireNonNull(injector, "injector");
        Objects.requireNonNull(fallback, "fallback");
        final Type expectedNative;
        if (fallback == SenderMapper.identity()) {
            expectedNative = senderType;
        } else {
            final var expandedFallback = parameterizedComponentType(fallback.getClass());
            final var inferredNative = GenericTypeReflector.getTypeParameter(
                    expandedFallback, SenderMapper.class.getTypeParameters()[0]);
            final var inferredMapped = GenericTypeReflector.getTypeParameter(
                    expandedFallback, SenderMapper.class.getTypeParameters()[1]);
            if (inferredNative != null && !containsTypeVariable(inferredNative)) {
                expectedNative = inferredNative;
            } else if (inferredNative instanceof TypeVariable<?> && inferredNative.equals(inferredMapped)) {
                expectedNative = senderType;
            } else {
                expectedNative = null;
            }
        }
        return resolveSenderMapper(injector, expectedNative, fallback);
    }

    /**
     * Selects a component sender mapper validated against the native platform sender class,
     * falling back to the platform default.
     * @param injector the plugin injector
     * @param nativeSenderType the native platform sender class
     * @param fallback the platform mapper
     * @param <N> the native platform sender type
     * @return the selected mapper
     */
    protected final <N> SenderMapper<N, C> senderMapper(
            final Injector injector, final Class<N> nativeSenderType, final SenderMapper<N, C> fallback
    ) {
        Objects.requireNonNull(nativeSenderType, "nativeSenderType");
        return senderMapper(injector, TypeToken.get(nativeSenderType), fallback);
    }

    /**
     * Selects a component sender mapper validated against the native platform sender type,
     * falling back to the platform default.
     * @param injector the plugin injector
     * @param nativeSenderType the native platform sender type token
     * @param fallback the platform mapper
     * @param <N> the native platform sender type
     * @return the selected mapper
     */
    protected final <N> SenderMapper<N, C> senderMapper(
            final Injector injector, final TypeToken<N> nativeSenderType, final SenderMapper<N, C> fallback
    ) {
        Objects.requireNonNull(injector, "injector");
        Objects.requireNonNull(nativeSenderType, "nativeSenderType");
        Objects.requireNonNull(fallback, "fallback");
        if (containsTypeVariable(nativeSenderType.getType())) {
            throw new IllegalArgumentException(
                    "Native sender type must not contain unresolved type variables: "
                            + nativeSenderType.getType().getTypeName()
            );
        }
        return resolveSenderMapper(injector, nativeSenderType.getType(), fallback);
    }

    /**
     * Selects a component or Guice-bound execution coordinator, falling back to the platform default.
     * @param injector the plugin injector
     * @param fallback the default execution coordinator
     * @return the selected execution coordinator
     */
    @SuppressWarnings("unchecked")
    protected final ExecutionCoordinator<C> executionCoordinator(
            final Injector injector, final ExecutionCoordinator<C> fallback
    ) {
        Objects.requireNonNull(injector, "injector");
        Objects.requireNonNull(fallback, "fallback");
        final var plugin = resolvePlugin(injector);
        final var candidates = activeComponents.stream()
                .filter(ExecutionCoordinator.class::isAssignableFrom)
                .sorted(COMPONENT_COMPARATOR)
                .toList();

        Class<?> selected = null;
        for (final var clazz : candidates) {
            if (!isEnableConditionMet(plugin, injector, clazz)) {
                if (plugin != null && plugin.getSLF4JLogger() != null) {
                    plugin.getSLF4JLogger().debug(
                            "Skipped ExecutionCoordinator (Condition failed) for {}", clazz.getSimpleName());
                }
                continue;
            }
            validateSenderParameter(clazz, ExecutionCoordinator.class);
            if (selected == null) {
                selected = clazz;
            }
        }
        if (selected != null) {
            return (ExecutionCoordinator<C>) resolveInstance(injector, selected);
        }
        if (senderType != null) {
            final Key<?> key = Key.get(Types.newParameterizedType(ExecutionCoordinator.class, senderType));
            final var binding = injector.getExistingBinding(key);
            if (binding != null) {
                return (ExecutionCoordinator<C>) binding.getProvider().get();
            }
        }
        return fallback;
    }

    /**
     * Returns the injected command manager, or {@code null} if the module has not been injected.
     * @return the command manager
     */
    protected final CommandManager<C> injectedCommandManager() {
        return commandManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void enableComponent(final WinterPlugin plugin, final Class<?> componentClass) {
        if (ArgumentParser.class.isAssignableFrom(componentClass)) {
            validateSenderParameter(componentClass, ArgumentParser.class);
        }
        if (KotlinRuntime.HAS_COROUTINES) {
            KotlinCoroutinesBridge.validateComponentTypes(
                    componentClass,
                    role -> validateSenderParameter(componentClass, role),
                    role -> componentType(componentClass, role)
            );
        }
        if (ParameterInjector.class.isAssignableFrom(componentClass)) {
            validateSenderParameter(componentClass, ParameterInjector.class);
        }
        if (CommandManagerConfiguration.class.isAssignableFrom(componentClass)) {
            validateSenderParameter(componentClass, CommandManagerConfiguration.class);
        }
        if (AnnotationParserDecorator.class.isAssignableFrom(componentClass)) {
            validateSenderParameter(componentClass, AnnotationParserDecorator.class);
        }

        final var parserType = ArgumentParser.class.isAssignableFrom(componentClass)
                ? componentType(componentClass, ArgumentParser.class)
                : null;
        final var injectorType = ParameterInjector.class.isAssignableFrom(componentClass)
                ? componentType(componentClass, ParameterInjector.class)
                : null;

        final var component = resolveInstance(injector, componentClass);
        if (component instanceof ArgumentParser<?, ?> parser && parserType != null) {
            commandManager.parserRegistry().registerParser(
                    ParserDescriptor.of((ArgumentParser<C, Object>) parser, parserType));
        }
        if (KotlinRuntime.HAS_COROUTINES) {
            final var coroutineEnv = activeCoroutineEnvironment != null
                    ? activeCoroutineEnvironment
                    : (Supplier<KotlinCoroutinesBridge.CoroutineEnvironment>) () ->
                            KotlinCoroutinesBridge.resolveEnvironment(
                                    injector,
                                    List.of(),
                                    clazz -> resolveInstance(injector, clazz)
                            );
            KotlinCoroutinesBridge.registerSuspendingParserIfPresent(
                    component,
                    commandManager,
                    role -> componentType(componentClass, role),
                    coroutineEnv
            );
        }
        if (component instanceof ParameterInjector<?, ?> parameterInjector && injectorType != null) {
            commandManager.parameterInjectorRegistry().registerInjector(
                    injectorType, (ParameterInjector<C, Object>) parameterInjector);
        }
        if (component instanceof CommandManagerConfiguration<?> configuration) {
            configurationRegistrar.register((CommandManagerConfiguration<C>) configuration);
        }
        if (component instanceof AnnotationParserDecorator<?> decorator) {
            ((AnnotationParserDecorator<C>) decorator).decorate(annotationParser);
        }
        parsedComponents.add(component);
    }

    @Override
    public boolean onEnable(final WinterPlugin plugin) throws Exception {
        Objects.requireNonNull(plugin, "plugin");
        if (injector == null && plugin.getInjector() != null) {
            plugin.getInjector().injectMembers(this);
        }
        if (injector == null || commandManager == null
                || configurationRegistrar == null || annotationParser == null) {
            throw new IllegalStateException("CommandModule dependencies have not been injected before onEnable");
        }

        parsedComponents.clear();
        enabledComponents.clear();
        var succeeded = false;
        try {
            final var start = System.currentTimeMillis();
            final var conditionInjector = plugin.getInjector() != null ? plugin.getInjector() : injector;
            final var sortedComponents = activeComponents.stream()
                    .sorted(COMPONENT_COMPARATOR)
                    .toList();
            final var candidatesToEnable = new ArrayList<Class<?>>(sortedComponents.size());
            for (final var componentClass : sortedComponents) {
                final var enableContext = new ConditionContext(plugin, componentClass);
                if (evaluateConditions(componentClass, Stage.ENABLE, enableContext, conditionInjector)) {
                    candidatesToEnable.add(componentClass);
                } else if (plugin.getSLF4JLogger() != null) {
                    plugin.getSLF4JLogger().debug(
                            "Skipped ENABLE (Condition failed) for {}", componentClass.getSimpleName());
                }
            }

            if (KotlinRuntime.HAS_COROUTINES) {
                activeCoroutineEnvironment = memoize(() -> KotlinCoroutinesBridge.resolveEnvironment(
                        injector,
                        candidatesToEnable,
                        clazz -> resolveInstance(injector, clazz)
                ));
            }

            for (final var componentClass : candidatesToEnable) {
                enableComponent(plugin, componentClass);
                enabledComponents.add(componentClass);
            }

            if (plugin.getSLF4JLogger() != null) {
                plugin.getSLF4JLogger().debug(
                        "Enabled {} {} components in {}ms",
                        enabledComponents.size(),
                        moduleAnnotation.getSimpleName(),
                        System.currentTimeMillis() - start
                );
            }

            configurationRegistrar.configure(commandManager);
            if (KotlinRuntime.HAS_COROUTINE_ANNOTATIONS) {
                KotlinAnnotationsBridge.installCoroutineSupport(annotationParser, activeCoroutineEnvironment);
            } else if (wire instanceof CommandComponentWire componentWire) {
                validateNoUnsupportedSuspendMethods(candidatesToEnable, componentWire.suspendingCommandMethods());
            }
            if (!parsedComponents.isEmpty()) {
                annotationParser.parse(List.copyOf(parsedComponents));
            }
            succeeded = true;
            return true;
        } finally {
            activeCoroutineEnvironment = null;
            parsedComponents.clear();
            if (!succeeded) {
                configurationRegistrar.clear();
            }
        }
    }

    /**
     * Unregisters commands registered with the command manager when supported by the platform.
     */
    protected void unregisterCommands() {
        if (commandManager != null
                && commandManager.hasCapability(CloudCapability.StandardCapabilities.ROOT_COMMAND_DELETION)) {
            final var roots = commandManager.rootCommands();
            if (roots != null) {
                for (final var rootCommand : List.copyOf(roots)) {
                    commandManager.deleteRootCommand(rootCommand);
                }
            }
        }
    }

    @Override
    public boolean onDisable(final WinterPlugin plugin) throws Exception {
        Throwable failure = null;
        try {
            unregisterCommands();
        } catch (final Throwable t) {
            failure = t;
        } finally {
            parsedComponents.clear();
            if (configurationRegistrar != null) {
                configurationRegistrar.clear();
            }
            loadedPlugin = null;
        }

        final boolean disabled;
        try {
            disabled = super.onDisable(plugin);
        } catch (final Throwable t) {
            if (failure != null) {
                failure.addSuppressed(t);
            } else {
                failure = t;
            }
            rethrow(failure);
            return false;
        }

        if (failure != null) {
            rethrow(failure);
        }
        return disabled;
    }

    private static void validateNoUnsupportedSuspendMethods(
            final List<Class<?>> enabledComponents,
            final Map<Class<?>, String> suspendingMethods
    ) {
        if (suspendingMethods.isEmpty()) {
            return;
        }
        for (final var componentClass : enabledComponents) {
            final var suspendMethod = suspendingMethods.get(componentClass);
            if (suspendMethod != null) {
                throw new IllegalStateException(
                        "Kotlin suspend command method " + suspendMethod
                                + " requires 'org.incendo:cloud-kotlin-coroutines-annotations' "
                                + "(and 'org.jetbrains.kotlin:kotlin-reflect' / "
                                + "'org.jetbrains.kotlinx:kotlinx-coroutines-core') on the runtime classpath"
                );
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <N> SenderMapper<N, C> resolveSenderMapper(
            final Injector injector, final Type expectedNative, final SenderMapper<N, C> fallback
    ) {
        final var plugin = resolvePlugin(injector);
        final var candidates = activeComponents.stream()
                .filter(SenderMapper.class::isAssignableFrom)
                .sorted(COMPONENT_COMPARATOR)
                .toList();

        Class<?> selected = null;
        for (final var clazz : candidates) {
            if (!isEnableConditionMet(plugin, injector, clazz)) {
                if (plugin != null && plugin.getSLF4JLogger() != null) {
                    plugin.getSLF4JLogger().debug(
                            "Skipped SenderMapper (Condition failed) for {}", clazz.getSimpleName());
                }
                continue;
            }
            validateSenderMapperTypes(clazz, expectedNative);
            if (selected == null) {
                selected = clazz;
            }
        }
        return selected != null ? (SenderMapper<N, C>) resolveInstance(injector, selected) : fallback;
    }

    private <T> T resolveInstance(final Injector injector, final Class<T> clazz) {
        if (lastSingletonInjector != injector) {
            injectedSingletons.clear();
            lastSingletonInjector = injector;
        }
        final T instance;
        if (injector.getExistingBinding(Key.get(clazz)) != null) {
            instance = injector.getInstance(clazz);
        } else if (wire instanceof CommandComponentWire componentWire) {
            final var supplier = componentWire.singletonProviders().get(clazz);
            instance = supplier != null ? clazz.cast(supplier.get()) : injector.getInstance(clazz);
        } else {
            instance = injector.getInstance(clazz);
        }
        if (wire instanceof CommandComponentWire componentWire
                && componentWire.singletonProviders().containsKey(clazz)
                && injectedSingletons.add(instance)) {
            injector.injectMembers(instance);
        }
        return instance;
    }

    private static <T> void bindSingletonComponent(
            final Binder binder, final Class<T> componentClass, final Supplier<?> supplier
    ) {
        binder.bind(componentClass)
                .toProvider(() -> componentClass.cast(supplier.get()))
                .in(Scopes.SINGLETON);
    }

    private static <T> Supplier<T> memoize(final Supplier<T> delegate) {
        return new Supplier<>() {
            private boolean computed;
            private T value;

            @Override
            public T get() {
                if (!computed) {
                    value = delegate.get();
                    computed = true;
                }
                return value;
            }
        };
    }

    private WinterPlugin resolvePlugin(final Injector injector) {
        if (loadedPlugin != null) {
            return loadedPlugin;
        }
        final var binding = injector.getExistingBinding(Key.get(WinterPlugin.class));
        return binding != null ? binding.getProvider().get() : null;
    }

    private boolean isEnableConditionMet(
            final WinterPlugin plugin, final Injector injector, final Class<?> componentClass
    ) {
        try {
            return evaluateConditions(
                    componentClass,
                    Stage.ENABLE,
                    new ConditionContext(plugin, componentClass),
                    injector
            );
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "Failed to evaluate enable conditions for " + componentClass.getName(), e);
        }
    }

    private void validateSenderMapperTypes(final Class<?> componentClass, final Type expectedNative) {
        final var expanded = parameterizedComponentType(componentClass);
        final var baseType = GenericTypeReflector.getTypeParameter(
                expanded, SenderMapper.class.getTypeParameters()[0]);
        final var mappedType = GenericTypeReflector.getTypeParameter(
                expanded, SenderMapper.class.getTypeParameters()[1]);
        if (baseType == null || mappedType == null) {
            throw new IllegalArgumentException(
                    "Missing generic type for SenderMapper on " + componentClass.getName());
        }
        if (baseType instanceof TypeVariable<?> baseVar && baseType.equals(mappedType)) {
            if (expectedNative != null && senderType != null && !isSameType(expectedNative, senderType)) {
                throw new IllegalArgumentException("Incompatible generic type for SenderMapper on "
                        + componentClass.getName() + ": native type " + expectedNative.getTypeName()
                        + " and sender type " + senderType.getTypeName() + " differ");
            }
            final var target = senderType != null ? senderType : expectedNative;
            if (target != null && !satisfiesBounds(baseVar, target, new HashSet<>())) {
                throw new IllegalArgumentException("Incompatible generic type bound for SenderMapper on "
                        + componentClass.getName());
            }
            return;
        }
        if (containsTypeVariable(baseType) || containsTypeVariable(mappedType)) {
            throw new IllegalArgumentException(
                    "Unresolved generic type for SenderMapper on " + componentClass.getName());
        }
        if (expectedNative != null && !isSameType(baseType, expectedNative)) {
            throw new IllegalArgumentException("Incompatible native sender type for SenderMapper on "
                    + componentClass.getName() + ": expected " + expectedNative.getTypeName()
                    + ", got " + baseType.getTypeName());
        }
        if (senderType != null && !isSameType(mappedType, senderType)) {
            throw new IllegalArgumentException("Incompatible mapped sender type for SenderMapper on "
                    + componentClass.getName() + ": expected " + senderType.getTypeName()
                    + ", got " + mappedType.getTypeName());
        }
    }

    private void validateSenderParameter(final Class<?> componentClass, final Class<?> role) {
        final var expanded = parameterizedComponentType(componentClass);
        final var declaredSender = GenericTypeReflector.getTypeParameter(expanded, role.getTypeParameters()[0]);
        if (declaredSender == null) {
            throw new IllegalArgumentException("Missing generic sender type for " + role.getSimpleName()
                    + " on " + componentClass.getName());
        }
        if (senderType == null) {
            return;
        }
        if (declaredSender instanceof TypeVariable<?> typeVar) {
            if (!satisfiesBounds(typeVar, senderType, new HashSet<>())) {
                throw new IllegalArgumentException("Incompatible generic sender type bound for "
                        + role.getSimpleName() + " on " + componentClass.getName()
                        + ": " + senderType.getTypeName() + " does not satisfy " + typeVar);
            }
            return;
        }
        if (containsTypeVariable(declaredSender)
                || !GenericTypeReflector.isSuperType(declaredSender, senderType)) {
            throw new IllegalArgumentException("Incompatible generic sender type for " + role.getSimpleName()
                    + " on " + componentClass.getName() + ": expected supertype of "
                    + senderType.getTypeName() + ", got " + declaredSender.getTypeName());
        }
    }

    @SuppressWarnings("unchecked")
    private static TypeToken<Object> componentType(final Class<?> componentClass, final Class<?> role) {
        final var expanded = parameterizedComponentType(componentClass);
        final var type = GenericTypeReflector.getTypeParameter(expanded, role.getTypeParameters()[1]);
        if (type == null || containsTypeVariable(type)) {
            throw new IllegalArgumentException("Missing or unresolved generic type for " + role.getSimpleName()
                    + " on " + componentClass.getName());
        }
        return (TypeToken<Object>) TypeToken.get(type);
    }

    private static Type parameterizedComponentType(final Class<?> clazz) {
        final var owner = (clazz.getDeclaringClass() != null && !clazz.accessFlags().contains(AccessFlag.STATIC))
                ? parameterizedComponentType(clazz.getDeclaringClass())
                : null;
        return TypeFactory.parameterizedInnerClass(owner, clazz, clazz.getTypeParameters());
    }

    private static boolean isSameType(final Type a, final Type b) {
        return GenericTypeReflector.isSuperType(a, b) && GenericTypeReflector.isSuperType(b, a);
    }

    private static boolean containsTypeVariable(final Type type) {
        if (type instanceof TypeVariable<?>) {
            return true;
        }
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getOwnerType() != null && containsTypeVariable(parameterized.getOwnerType())) {
                return true;
            }
            for (final var argument : parameterized.getActualTypeArguments()) {
                if (containsTypeVariable(argument)) {
                    return true;
                }
            }
            return false;
        }
        if (type instanceof GenericArrayType arrayType) {
            return containsTypeVariable(arrayType.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcard) {
            for (final var bound : wildcard.getUpperBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
            for (final var bound : wildcard.getLowerBounds()) {
                if (containsTypeVariable(bound)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static boolean satisfiesBounds(
            final TypeVariable<?> typeVar, final Type candidate, final Set<TypeVariable<?>> visited
    ) {
        if (!visited.add(typeVar)) {
            return true;
        }
        for (final var bound : typeVar.getBounds()) {
            if (bound instanceof TypeVariable<?> boundVar) {
                if (!satisfiesBounds(boundVar, candidate, visited)) {
                    return false;
                }
                continue;
            }
            final var resolvedBound = substituteTypeVariable(bound, typeVar, candidate);
            if (containsTypeVariable(resolvedBound) || !GenericTypeReflector.isSuperType(resolvedBound, candidate)) {
                return false;
            }
        }
        return true;
    }

    private static Type substituteTypeVariable(
            final Type type, final TypeVariable<?> target, final Type replacement
    ) {
        if (type.equals(target)) {
            return replacement;
        }
        if (type instanceof ParameterizedType parameterized) {
            final var rawArgs = parameterized.getActualTypeArguments();
            final var mappedArgs = new Type[rawArgs.length];
            var changed = false;
            for (int i = 0; i < rawArgs.length; i++) {
                mappedArgs[i] = substituteTypeVariable(rawArgs[i], target, replacement);
                changed |= mappedArgs[i] != rawArgs[i];
            }
            final var owner = parameterized.getOwnerType();
            final var mappedOwner = owner != null ? substituteTypeVariable(owner, target, replacement) : null;
            changed |= mappedOwner != owner;
            if (!changed) {
                return type;
            }
            return TypeFactory.parameterizedInnerClass(
                    mappedOwner, (Class<?>) parameterized.getRawType(), mappedArgs);
        }
        if (type instanceof GenericArrayType arrayType) {
            final var component = arrayType.getGenericComponentType();
            final var mapped = substituteTypeVariable(component, target, replacement);
            return mapped == component ? type : Types.arrayOf(mapped);
        }
        if (type instanceof WildcardType wildcard) {
            final var lower = wildcard.getLowerBounds();
            if (lower.length > 0) {
                final var mapped = substituteTypeVariable(lower[0], target, replacement);
                return mapped == lower[0] ? type : Types.supertypeOf(mapped);
            }
            final var upper = wildcard.getUpperBounds();
            if (upper.length > 0) {
                final var mapped = substituteTypeVariable(upper[0], target, replacement);
                return mapped == upper[0] ? type : Types.subtypeOf(mapped);
            }
        }
        return type;
    }

    private static void rethrow(final Throwable throwable) throws Exception {
        if (throwable instanceof Exception exception) {
            throw exception;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(throwable);
    }
}
