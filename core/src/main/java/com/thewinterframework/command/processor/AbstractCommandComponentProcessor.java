package com.thewinterframework.command.processor;

import com.thewinterframework.command.CommandComponent;
import com.thewinterframework.command.CommandModule;
import com.thewinterframework.processor.clazz.ClassWireProcessor;
import com.thewinterframework.processor.context.ProcessorContext;
import com.thewinterframework.processor.extension.WinterProcessorExtension;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Shared Winter annotation processor support for {@link CommandComponent} discovery, validation,
 * and reflection-free {@link CommandComponentWire} code generation.
 */
public abstract class AbstractCommandComponentProcessor implements WinterProcessorExtension {
    private static final Set<String> INJECT_ANNOTATIONS = Set.of(
            "com.google.inject.Inject",
            "jakarta.inject.Inject",
            "javax.inject.Inject"
    );

    private static final Set<String> CLOUD_METHOD_ANNOTATIONS = Set.of(
            "org.incendo.cloud.annotations.Command",
            "org.incendo.cloud.annotations.Commands",
            "org.incendo.cloud.annotations.suggestion.Suggestions",
            "org.incendo.cloud.annotations.parser.Parser"
    );

    private static final String CONTINUATION_FQCN = "kotlin.coroutines.Continuation";

    private final Set<String> wiredClasses = new TreeSet<>();
    private final Map<String, String> singletonSuppliers = new TreeMap<>();
    private final Map<String, String> staticInjectionTargets = new TreeMap<>();
    private final Map<String, String> suspendingMethods = new TreeMap<>();
    protected ProcessingEnvironment env;
    private boolean conflicting;
    private boolean conflictReported;

    /**
     * Returns the canonical class name of the platform {@link CommandModule} implementation to wire.
     * @return the platform command module class name
     */
    protected abstract String moduleClassName();

    /**
     * Allows platform processors to reject elements that do not satisfy additional constraints.
     * @param annotation the processed annotation type
     * @param element the annotated element
     * @param context the current processor context
     * @return {@code true} to include the component
     */
    protected boolean filter(
            final TypeElement annotation, final Element element, final ProcessorContext context
    ) {
        return true;
    }

    @Override
    public void initialize(final ProcessingEnvironment environment) {
        this.env = environment;
    }

    @Override
    public final Set<String> supportedAnnotationNames() {
        return Set.of(CommandComponent.class.getCanonicalName());
    }

    @Override
    public void onRoundStarted(final ProcessorContext context) {
        final var targetModule = moduleClassName();
        for (final var existing : context.getRequiredModules()) {
            if (!existing.equals(targetModule) && isCommandModule(existing)) {
                conflicting = true;
                if (!conflictReported) {
                    conflictReported = true;
                    context.messager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Multiple Winter Command platform processors are active (" + existing + " and "
                                    + targetModule + "). Add only one command platform adapter to annotationProcessor.",
                            context.getPluginClass()
                    );
                }
                return;
            }
        }
        context.wireModule(targetModule);
    }

    @Override
    public void process(
            final TypeElement annotation,
            final Set<? extends Element> elements,
            final ProcessorContext context
    ) {
        if (conflicting) {
            return;
        }
        for (final var element : elements) {
            final var kind = element.getKind();
            if (kind != ElementKind.CLASS && kind != ElementKind.RECORD) {
                context.messager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CommandComponent can only be applied to concrete classes or records, not " + kind,
                        element
                );
                continue;
            }
            final var typeElement = (TypeElement) element;
            final var nestingKind = typeElement.getNestingKind();
            if (nestingKind != NestingKind.TOP_LEVEL && nestingKind != NestingKind.MEMBER) {
                context.messager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CommandComponent cannot be applied to local or anonymous classes",
                        typeElement
                );
                continue;
            }
            if (nestingKind == NestingKind.MEMBER
                    && kind != ElementKind.RECORD
                    && !typeElement.getModifiers().contains(Modifier.STATIC)) {
                context.messager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CommandComponent nested class must be static: " + typeElement.getQualifiedName(),
                        typeElement
                );
                continue;
            }
            if (typeElement.getModifiers().contains(Modifier.ABSTRACT)) {
                context.messager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CommandComponent class must not be abstract: " + typeElement.getQualifiedName(),
                        typeElement
                );
                continue;
            }
            if (!isPubliclyAccessible(typeElement)) {
                context.messager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@CommandComponent type and any enclosing types must be public: "
                                + typeElement.getQualifiedName(),
                        typeElement
                );
                continue;
            }
            if (!filter(annotation, typeElement, context)) {
                continue;
            }

            final var classLiteral = typeElement.getQualifiedName().toString() + ".class";
            wiredClasses.add(classLiteral);

            final var singletonInfo = findSingletonInfo(typeElement);
            if (singletonInfo != null) {
                singletonSuppliers.put(classLiteral, "() -> " + singletonInfo.accessExpression());
                staticInjectionTargets.put(classLiteral, singletonInfo.staticTargetClassLiteral());
            }

            final var suspendMethod = findFirstSuspendingCloudMethod(typeElement);
            if (suspendMethod != null) {
                suspendingMethods.put(classLiteral, suspendMethod);
            }
        }
    }

    @Override
    public void onProcessingFinished(final ProcessorContext context) {
        if (conflicting) {
            return;
        }
        if (context.getPluginPackage().isUnnamed()) {
            context.messager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@WinterBootPlugin class must declare a non-empty package",
                    context.getPluginClass()
            );
            return;
        }
        final var className = ClassWireProcessor.wiredClassName(CommandComponent.class);
        final var packageName = context.getWinterModulePackageString();
        writeCommandComponentWire(context, className, packageName);
    }

    private void writeCommandComponentWire(
            final ProcessorContext context, final String className, final String packageName
    ) {
        final var fqcn = context.getWinterModuleQualifiedNameStringFor(className);
        final var sb = new StringBuilder();
        sb.append("// This file is generated automatically by thewinterframework. Do not modify this file manually.\n");
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import com.thewinterframework.command.processor.CommandComponentWire;\n\n");
        sb.append("import java.util.List;\n");
        if (!singletonSuppliers.isEmpty() || !staticInjectionTargets.isEmpty() || !suspendingMethods.isEmpty()) {
            sb.append("import java.util.Map;\n");
        }
        if (!singletonSuppliers.isEmpty()) {
            sb.append("import java.util.function.Supplier;\n");
        }
        sb.append('\n');
        sb.append("public final class ").append(className).append(" implements CommandComponentWire {\n");
        sb.append("\tpublic ").append(className).append("() {\n\t}\n\n");
        sb.append("\t@Override\n");
        sb.append("\tpublic List<Class<?>> getWiredClasses() {\n");
        sb.append("\t\treturn List.of(").append(String.join(", ", wiredClasses)).append(");\n");
        sb.append("\t}\n");

        if (!singletonSuppliers.isEmpty()) {
            sb.append("\n\t@Override\n");
            sb.append("\tpublic Map<Class<?>, Supplier<?>> singletonProviders() {\n");
            sb.append("\t\treturn Map.ofEntries(\n");
            appendMapEntries(sb, "Class<?>, Supplier<?>", singletonSuppliers, false);
            sb.append("\t\t);\n");
            sb.append("\t}\n");
        }

        if (!staticInjectionTargets.isEmpty()) {
            sb.append("\n\t@Override\n");
            sb.append("\tpublic Map<Class<?>, Class<?>> staticInjectionTargets() {\n");
            sb.append("\t\treturn Map.ofEntries(\n");
            appendMapEntries(sb, "Class<?>, Class<?>", staticInjectionTargets, false);
            sb.append("\t\t);\n");
            sb.append("\t}\n");
        }

        if (!suspendingMethods.isEmpty()) {
            sb.append("\n\t@Override\n");
            sb.append("\tpublic Map<Class<?>, String> suspendingCommandMethods() {\n");
            sb.append("\t\treturn Map.ofEntries(\n");
            appendMapEntries(sb, "Class<?>, String", suspendingMethods, true);
            sb.append("\t\t);\n");
            sb.append("\t}\n");
        }

        sb.append("}\n");

        try {
            final var file = context.filer().createSourceFile(fqcn);
            try (final var writer = file.openWriter()) {
                writer.write(sb.toString());
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to generate " + fqcn, e);
        }
    }

    private static void appendMapEntries(
            final StringBuilder sb,
            final String typeArguments,
            final Map<String, String> entries,
            final boolean quoteValues
    ) {
        final var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            final var renderedValue = quoteValues ? quoteJavaString(entry.getValue()) : entry.getValue();
            sb.append("\t\t\t\tMap.<")
                    .append(typeArguments)
                    .append(">entry(")
                    .append(entry.getKey())
                    .append(", ")
                    .append(renderedValue)
                    .append(')');
            if (iterator.hasNext()) {
                sb.append(',');
            }
            sb.append('\n');
        }
    }

    private SingletonInfo findSingletonInfo(final TypeElement typeElement) {
        for (final var enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.CONSTRUCTOR) {
                continue;
            }
            final var ctor = (ExecutableElement) enclosed;
            if (hasAnyAnnotation(ctor, INJECT_ANNOTATIONS)) {
                return null;
            }
            if (ctor.getModifiers().contains(Modifier.PUBLIC) && ctor.getParameters().isEmpty()) {
                return null;
            }
        }

        final var qualifiedName = typeElement.getQualifiedName().toString();
        for (final var enclosed : typeElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            final var field = (VariableElement) enclosed;
            if (field.getSimpleName().contentEquals("INSTANCE")
                    && isPublicStaticFinal(field)
                    && isSameErasedType(field.asType(), typeElement.asType())) {
                return new SingletonInfo(qualifiedName + ".INSTANCE", qualifiedName + ".class");
            }
        }

        if (typeElement.getNestingKind() == NestingKind.MEMBER
                && typeElement.getEnclosingElement() instanceof TypeElement enclosingType) {
            final var enclosingQualified = enclosingType.getQualifiedName().toString();
            final var matchingFields = new ArrayList<String>(1);
            for (final var enclosed : enclosingType.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.FIELD) {
                    continue;
                }
                final var field = (VariableElement) enclosed;
                if (isPublicStaticFinal(field) && isSameErasedType(field.asType(), typeElement.asType())) {
                    final var fieldName = field.getSimpleName().toString();
                    if (fieldName.equals("Companion")) {
                        return new SingletonInfo(
                                enclosingQualified + ".Companion",
                                enclosingQualified + ".class"
                        );
                    }
                    matchingFields.add(fieldName);
                }
            }
            if (matchingFields.size() == 1) {
                return new SingletonInfo(
                        enclosingQualified + "." + matchingFields.getFirst(),
                        enclosingQualified + ".class"
                );
            }
        }
        return null;
    }

    private String findFirstSuspendingCloudMethod(final TypeElement typeElement) {
        final List<? extends Element> members = env != null
                ? env.getElementUtils().getAllMembers(typeElement)
                : typeElement.getEnclosedElements();
        String selectedMethodName = null;
        for (final var member : members) {
            if (member.getKind() != ElementKind.METHOD) {
                continue;
            }
            final var method = (ExecutableElement) member;
            final var modifiers = method.getModifiers();
            if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.ABSTRACT)) {
                continue;
            }
            if (!hasAnyAnnotation(method, CLOUD_METHOD_ANNOTATIONS) || !isSuspendMethod(method)) {
                continue;
            }
            final var name = method.getSimpleName().toString();
            if (selectedMethodName == null || name.compareTo(selectedMethodName) < 0) {
                selectedMethodName = name;
            }
        }
        return selectedMethodName != null
                ? typeElement.getSimpleName() + "#" + selectedMethodName
                : null;
    }

    private boolean isSuspendMethod(final ExecutableElement method) {
        final var parameters = method.getParameters();
        if (parameters.isEmpty()) {
            return false;
        }
        final var lastType = parameters.get(parameters.size() - 1).asType();
        if (env != null) {
            final var erased = env.getTypeUtils().erasure(lastType);
            if (erased instanceof DeclaredType declared
                    && declared.asElement() instanceof TypeElement paramElement) {
                return paramElement.getQualifiedName().contentEquals(CONTINUATION_FQCN);
            }
        }
        final var rendered = lastType.toString();
        return rendered.equals(CONTINUATION_FQCN) || rendered.startsWith(CONTINUATION_FQCN + "<");
    }

    private boolean isSameErasedType(final TypeMirror first, final TypeMirror second) {
        if (env != null) {
            final var types = env.getTypeUtils();
            return types.isSameType(types.erasure(first), types.erasure(second));
        }
        return first.toString().equals(second.toString());
    }

    private static boolean isPublicStaticFinal(final VariableElement field) {
        final var modifiers = field.getModifiers();
        return modifiers.contains(Modifier.PUBLIC)
                && modifiers.contains(Modifier.STATIC)
                && modifiers.contains(Modifier.FINAL);
    }

    private static boolean hasAnyAnnotation(final Element element, final Set<String> targetNames) {
        for (final AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().asElement() instanceof TypeElement annotationType
                    && targetNames.contains(annotationType.getQualifiedName().toString())) {
                return true;
            }
        }
        return false;
    }

    private static String quoteJavaString(final String value) {
        final var sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private boolean isCommandModule(final String moduleName) {
        if (moduleName.startsWith("com.thewinterframework.command.")) {
            return true;
        }
        if (env == null) {
            return false;
        }
        final var moduleType = env.getElementUtils().getTypeElement(moduleName);
        final var commandModuleType = env.getElementUtils().getTypeElement(CommandModule.class.getCanonicalName());
        if (moduleType == null || commandModuleType == null) {
            return false;
        }
        return env.getTypeUtils().isSubtype(
                env.getTypeUtils().erasure(moduleType.asType()),
                env.getTypeUtils().erasure(commandModuleType.asType())
        );
    }

    private static boolean isPubliclyAccessible(final TypeElement typeElement) {
        Element current = typeElement;
        while (current != null && current.getKind() != ElementKind.PACKAGE) {
            if (!current.getModifiers().contains(Modifier.PUBLIC)) {
                return false;
            }
            current = current.getEnclosingElement();
        }
        return true;
    }

    private record SingletonInfo(String accessExpression, String staticTargetClassLiteral) {
    }
}
