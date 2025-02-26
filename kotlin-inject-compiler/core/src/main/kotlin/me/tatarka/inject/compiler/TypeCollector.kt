package me.tatarka.inject.compiler

import me.tatarka.kotlin.ast.AstAnnotation
import me.tatarka.kotlin.ast.AstClass
import me.tatarka.kotlin.ast.AstElement
import me.tatarka.kotlin.ast.AstMember
import me.tatarka.kotlin.ast.AstProvider
import me.tatarka.kotlin.ast.AstType
import me.tatarka.kotlin.ast.AstVisibility

class TypeCollector(private val provider: AstProvider, private val options: Options) {

    private val typeInfoCache = mutableMapOf<String, TypeInfo>()

    fun collect(astClass: AstClass, accessor: Accessor = Accessor.Empty): Result {
        val typeInfo = collectTypeInfo(astClass)
        return if (!typeInfo.valid) {
            Result(astClass, null, emptyList())
        } else {
            val result = Result(astClass, typeInfo.scopeClass, typeInfo.providerMembers)
            result.collectTypes(astClass, accessor, typeInfo)
            result
        }
    }

    inner class Result internal constructor(
        val astClass: AstClass,
        val scopeClass: AstClass?,
        val providerMembers: List<TypeInfo.ProviderMember>,
    ) {
        // Map of types to inject and how to obtain them.
        private val types = mutableMapOf<TypeKey, Member>()

        // Map of container types to inject. Used for multibinding.
        private val containerTypes = mutableMapOf<ContainerKey, MutableList<ContainerMember>>()

        // Map of types obtained from generated provider methods. This can be used for lookup when the underlying method
        // is not available (ex: because we only see an interface, or it's marked protected).
        private val providerTypes = mutableMapOf<TypeKey, ProviderMember>()

        // Map of scoped components and the accessors to obtain them
        private val scopedAccessors = mutableMapOf<AstAnnotation, ScopedComponent>()

        private val parents = mutableListOf<Result>()

        fun iterator(): Iterator<Result> = iterator {
            yield(this@Result)
            for (parent in parents) {
                yieldAll(parent.iterator())
            }
        }

        fun providerType(key: TypeKey): Pair<ProviderMember, Result>? {
            for (result in iterator()) {
                val type = result.providerTypes[key]
                if (type != null) return type to result
            }
            return null
        }

        fun type(key: TypeKey): Pair<Member, Result>? {
            for (result in iterator()) {
                val type = result.types[key]
                if (type != null) return type to result
            }
            return null
        }

        fun containerArgs(key: ContainerKey): List<Pair<ContainerMember, Result>> {
            val results = mutableListOf<Pair<ContainerMember, Result>>()
            for (result in iterator()) {
                val types = result.containerTypes[key]
                if (types != null) {
                    results.addAll(types.map { it to result })
                }
            }
            return results
        }

        fun scopedAccessor(scope: AstAnnotation): Pair<ScopedComponent, Result>? {
            for (result in iterator()) {
                val component = result.scopedAccessors[scope]
                if (component != null) return component to result
            }
            return null
        }

        @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
        internal fun collectTypes(
            astClass: AstClass,
            accessor: Accessor,
            typeInfo: TypeInfo,
        ) {
            if (accessor.isNotEmpty()) {
                for ((method, qualifier) in typeInfo.providerMembers) {
                    val returnType = method.returnTypeFor(astClass)
                    val key = TypeKey(returnType, qualifier)
                    addProviderMethod(key, method, accessor)
                }
            }

            for ((member, qualifier, scope) in typeInfo.providesMembers) {
                if (scope != null && scope != typeInfo.elementScope) {
                    if (typeInfo.elementScope != null) {
                        provider.error(
                            "@Provides with scope: $scope must match component scope: ${typeInfo.elementScope}",
                            member
                        )
                    } else {
                        provider.error(
                            "@Provides with scope: $scope cannot be provided in an unscoped component",
                            member
                        )
                    }
                }
                val scopedComponent = if (scope != null) astClass else null
                val returnType = member.returnTypeFor(astClass)
                val key = TypeKey(returnType, qualifier)
                when {
                    member.isIntoMap() -> collectIntoMapProvider(key, member, accessor, scope)
                    member.isIntoSet() -> collectIntoSetProvider(key, member, accessor, scope)
                    else -> collectProvider(key, member, accessor, scope, scopedComponent)
                }
            }

            val constructor = astClass.primaryConstructor
            if (constructor != null) {
                fun Result.getAllTypesAndMethods(): Map<TypeKey, AstMember> =
                    types.mapValues { it.value.method } +
                        providerTypes.mapValues { it.value.method }

                fun Result.getContainerTypesAndMethods(): Map<TypeKey, AstMember> =
                    containerTypes.mapKeys { it.key.containerTypeKey(provider) }
                        .mapValues { it.value.first().method }

                val childAndParentsTypes = mutableMapOf<TypeKey, AstMember>()
                val childAndParentsContainerTypes = mutableMapOf<TypeKey, AstMember>()
                val needsToCheckDuplicateTypesWithParents = !accessor.isNotEmpty()

                if (needsToCheckDuplicateTypesWithParents) {
                    // Start adding the children types
                    childAndParentsTypes.putAll(getAllTypesAndMethods())
                    childAndParentsContainerTypes.putAll(getContainerTypesAndMethods())
                }

                for (parameter in constructor.parameters) {
                    if (parameter.isComponent()) {
                        val elemAstClass = parameter.type.toAstClass()
                        val elemTypeInfo = collectTypeInfo(elemAstClass)

                        val parentResult = Result(elemAstClass, scopeClass, providerMembers)
                        parents.add(parentResult)
                        parentResult.collectTypes(
                            astClass = elemAstClass,
                            accessor = accessor + parameter.name,
                            typeInfo = elemTypeInfo
                        )

                        if (needsToCheckDuplicateTypesWithParents) {
                            val parentTypes = parentResult.getAllTypesAndMethods()
                            val parentContainerTypes = parentResult.getContainerTypesAndMethods()
                            checkDuplicateTypesBetweenResults(
                                parentTypes,
                                parentContainerTypes,
                                childAndParentsTypes,
                                childAndParentsContainerTypes
                            )

                            childAndParentsTypes.putAll(parentTypes)
                            childAndParentsContainerTypes.putAll(parentContainerTypes)
                        }
                    }
                }
            }

            if (typeInfo.elementScope != null) {
                val result = scopedAccessor(typeInfo.elementScope)
                if (result != null) {
                    val (component, _) = result
                    provider.error("Cannot apply scope: ${typeInfo.elementScope}", typeInfo.elementScope)
                    provider.error(
                        "as scope: ${typeInfo.elementScope} is already applied to parent",
                        component.type,
                    )
                } else {
                    scopedAccessors[typeInfo.elementScope] = ScopedComponent(astClass, accessor)
                }
            }
        }

        private fun collectProvider(
            key: TypeKey,
            member: AstMember,
            accessor: Accessor,
            scope: AstAnnotation?,
            scopedComponent: AstClass?,
        ) {
            if (accessor.isNotEmpty()) {
                // May have already added from a resolvable provider
                if (providerTypes.containsKey(key)) return
                // We out outside the current class, so complain if not accessible
                if (member.visibility == AstVisibility.PROTECTED) {
                    provider.error("@Provides method is not accessible", member)
                }
            }
            addMethod(key, member, accessor, scope, scopedComponent)
        }

        private fun collectIntoSetProvider(
            key: TypeKey,
            member: AstMember,
            accessor: Accessor,
            scope: AstAnnotation?,
        ) {
            val isMultiple = member.isIntoSetMultiple()
            val containerKey = if (isMultiple) {
                // Set<A> -> Set<A>
                val resolvedType = key.type.resolvedType()
                if (!resolvedType.isSet()) {
                    provider.error("@IntoSet(multiple = true) must have return type of type Set", member)
                    return
                }

                ContainerKey.SetKey(resolvedType.arguments[0], key.qualifier)
            } else {
                // A -> Set<A>
                ContainerKey.SetKey(key.type, key.qualifier)
            }
            addContainerType(provider, key, containerKey, member, accessor, scope, isMultiple)
        }

        private fun collectIntoMapProvider(
            key: TypeKey,
            member: AstMember,
            accessor: Accessor,
            scope: AstAnnotation?,
        ) {
            val resolvedType = key.type.resolvedType()
            val isMultiple = member.isIntoMapMultiple()
            if (isMultiple) {
                // Map<A, B> -> Map<A, B>
                if (!resolvedType.isMap()) {
                    provider.error("@IntoMap(multiple = true) must have return type of type Map", member)
                    return
                }
            } else {
                // Pair<A, B> -> Map<A, B>
                if (!resolvedType.isPair()) {
                    provider.error("@IntoMap(multiple = false) must have return type of type Pair", member)
                    return
                }
            }
            val containerKey = ContainerKey.MapKey(
                resolvedType.arguments[0],
                resolvedType.arguments[1],
                key.qualifier
            )
            addContainerType(provider, key, containerKey, member, accessor, scope, isMultiple)
        }

        private fun checkDuplicateTypesBetweenResults(
            result1Types: Map<TypeKey, AstMember>,
            result1ContainerTypes: Map<TypeKey, AstMember>,
            result2Types: Map<TypeKey, AstMember>,
            result2ContainerTypes: Map<TypeKey, AstMember>,
        ) {
            val result1TypesAndContainerTypes = result1Types + result1ContainerTypes

            val result2TypesAndContainerTypes = result2Types + result2ContainerTypes

            // We should allow for both Results to contribute to the same multibinding type
            result1Types.keys.intersect(result2TypesAndContainerTypes.keys).forEach {
                duplicate(it, result1Types.getValue(it), result2TypesAndContainerTypes.getValue(it))
            }

            result1TypesAndContainerTypes.keys.intersect(result2Types.keys).forEach {
                duplicate(it, result1TypesAndContainerTypes.getValue(it), result2Types.getValue(it))
            }
        }

        private fun addContainerType(
            provider: AstProvider,
            key: TypeKey,
            containerKey: ContainerKey,
            method: AstMember,
            accessor: Accessor,
            scope: AstAnnotation?,
            isMultiple: Boolean
        ) {
            val current = type(containerKey.containerTypeKey(provider))
            if (current != null) {
                val (creator, _) = current
                duplicate(key, newValue = method, oldValue = creator.method)
            }

            containerTypes.getOrPut(containerKey) { mutableListOf() }
                .add(ContainerMember(method, accessor, scope, isMultiple))
        }

        private fun addMethod(
            key: TypeKey,
            method: AstMember,
            accessor: Accessor,
            scope: AstAnnotation?,
            scopedComponent: AstClass?,
        ) {
            val oldValue = types[key]
            if (oldValue != null) {
                duplicate(key, newValue = method, oldValue = oldValue.method)
                return
            }

            val containerKey = ContainerKey.fromContainer(key)
            if (containerKey != null) {
                val oldContainerValue = containerTypes[containerKey]
                if (oldContainerValue != null) {
                    duplicate(key, newValue = method, oldValue = oldContainerValue.first().method)
                    return
                }
            }

            types[key] = Member(method, accessor, scope, scopedComponent)
        }

        private fun addProviderMethod(key: TypeKey, member: AstMember, accessor: Accessor) {
            // Skip adding if already provided by child component.
            if (!providerTypes.containsKey(key)) {
                providerTypes[key] = ProviderMember(member, accessor)
            }
        }

        private fun duplicate(key: TypeKey, newValue: AstElement, oldValue: AstElement) {
            provider.error("Cannot provide: $key", newValue)
            provider.error("as it is already provided", oldValue)
        }
    }

    @Suppress("ComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
    private fun collectTypeInfo(astClass: AstClass): TypeInfo {
        return typeInfoCache.getOrPut(astClass.toString()) {
            val isComponent = astClass.isComponent()

            val providesMembers = mutableListOf<TypeInfo.ProvidesMember>()
            val providerMembers = mutableListOf<TypeInfo.ProviderMember>()

            var scopeClass: AstClass? = null
            var elementScope: AstAnnotation? = null

            for (parentClass in astClass.inheritanceChain()) {
                val parentScope = parentClass.scope(options)
                if (parentScope != null) {
                    if (elementScope == null) {
                        scopeClass = parentClass
                        elementScope = parentScope
                    } else if (elementScope != parentScope) {
                        provider.error("Cannot apply scope: $parentScope", parentClass)
                        provider.error(
                            "as scope: $elementScope is already applied",
                            scopeClass
                        )
                    }
                }
            }

            val allMethods = astClass.allMethods
            // some methods may override others
            val methods = mutableListOf<AstMember>()
            val isProvides = mutableMapOf<AstMember, Boolean>()
            val scopes = mutableMapOf<AstMember, Set<AstAnnotation>>()
            val scopedMethodsWithScopedSuperMethod = mutableMapOf<AstMember, Pair<AstMember, Set<AstAnnotation>>>()
            for (method in allMethods) {
                val methodScopes = method.scopes(options).toSet()
                val existing = methods.firstOrNull {
                    it.name == method.name && (it.signatureEquals(method) || it.overrides(method))
                }

                when {
                    existing != null && method.overrides(existing) -> {
                        // When we find a function that overrides the existing one, then we need to update all entries
                        // for the new method and remove the existing one, because it has higher priority.
                        methods.add(method)
                        methods.remove(existing)

                        isProvides[method] = existing.isProvides() || method.isProvides()
                        isProvides.remove(existing)

                        if (methodScopes.isNotEmpty()) {
                            val existingScopes = scopes[existing]
                            if (existingScopes == null) {
                                scopes[method] = methodScopes
                            } else {
                                if (existingScopes != methodScopes) {
                                    scopedMethodsWithScopedSuperMethod[method] = existing to existingScopes
                                }
                            }
                        }
                        scopedMethodsWithScopedSuperMethod.remove(existing)
                        scopes.remove(existing)
                    }

                    existing != null -> {
                        // mark provides if it overrides one that's annotated
                        if (method.isProvides()) {
                            isProvides[existing] = true
                        }

                        if (methodScopes.isNotEmpty()) {
                            val existingScopeTypes = scopes[existing]
                            if (existingScopeTypes == null) {
                                scopes[existing] = methodScopes
                            } else {
                                if (existingScopeTypes != methodScopes) {
                                    scopedMethodsWithScopedSuperMethod[existing] = method to methodScopes
                                }
                            }
                        }
                    }

                    else -> {
                        methods.add(method)
                        isProvides[method] = method.isProvides()
                        if (methodScopes.isNotEmpty()) {
                            scopes[method] = methodScopes
                        }
                    }
                }
            }
            for (member in methods) {
                val abstract = member.isAbstract
                val methodScopes = scopes[member]
                if (isProvides.getValue(member)) {
                    var methodScope: AstAnnotation? = null
                    if (methodScopes != null) {
                        val scopedMethodWithScopedSuperMethod = scopedMethodsWithScopedSuperMethod[member]
                        if (methodScopes.size > 1) {
                            provider.error("Cannot apply multiple scopes: $methodScopes", member)
                            continue
                        } else if (scopedMethodWithScopedSuperMethod != null) {
                            val (scopedSuperMethod, superMethodScopes) = scopedMethodWithScopedSuperMethod
                            provider.error("Cannot apply scope: ${methodScopes.first()}", member)
                            provider.error(
                                "as scope: ${(superMethodScopes - methodScopes).first()} is already applied",
                                scopedSuperMethod
                            )
                            continue
                        } else {
                            methodScope = methodScopes.first()
                        }
                    }

                    if (member.visibility == AstVisibility.PRIVATE) {
                        provider.error("@Provides method must not be private", member)
                        continue
                    }
                    if (member.returnType.isUnit()) {
                        provider.error("@Provides method must return a value", member)
                        continue
                    }
                    if (member.returnType.isPlatform()) {
                        val name = member.returnType.simpleName
                        provider.error(
                            """@Provides method must not return a platform type
                                |This can happen when you call a platform method and leave off an explicit return type.
                                |You can fix this be explicitly declaring the return type as $name or $name?"""
                                .trimMargin(),
                            member
                        )
                        continue
                    }

                    if (isComponent && abstract) {
                        provider.error("@Provides method must have a concrete implementation", member)
                        continue
                    } else {
                        providesMembers.add(
                            TypeInfo.ProvidesMember(
                                member,
                                qualifier(provider, options, member, member.returnType),
                                methodScope
                            )
                        )
                    }
                } else if (member.isProvider()) {
                    if (methodScopes != null) {
                        provider.warn(
                            "Scope: ${methodScopes.first()} has no effect." +
                                " Place on @Provides function or @Inject constructor instead.",
                            member
                        )
                    }
                    providerMembers.add(
                        TypeInfo.ProviderMember(
                            member,
                            qualifier(provider, options, member, member.returnType),
                        )
                    )
                }
            }

            TypeInfo(
                providesMembers = providesMembers,
                providerMembers = providerMembers,
                scopeClass = scopeClass,
                elementScope = elementScope
            )
        }
    }
}

class TypeInfo(
    val providesMembers: List<ProvidesMember> = emptyList(),
    val providerMembers: List<ProviderMember> = emptyList(),
    val scopeClass: AstClass? = null,
    val elementScope: AstAnnotation? = null,
    val valid: Boolean = true,
) {
    data class ProvidesMember(
        val member: AstMember,
        val qualifier: AstAnnotation?,
        val scope: AstAnnotation?,
    )

    data class ProviderMember(
        val member: AstMember,
        val qualifier: AstAnnotation?,
    )
}

class ProviderMember(
    val method: AstMember,
    val accessor: Accessor,
)

class Member(
    val method: AstMember,
    val accessor: Accessor = Accessor.Empty,
    val scope: AstAnnotation? = null,
    val scopedComponent: AstClass? = null,
)

class ContainerMember(
    val method: AstMember,
    val accessor: Accessor,
    val scope: AstAnnotation?,
    val isMultiple: Boolean,
)

sealed class ContainerKey {
    abstract fun containerTypeKey(provider: AstProvider): TypeKey

    data class SetKey(val type: AstType, val qualifier: AstAnnotation? = null) : ContainerKey() {
        override fun containerTypeKey(provider: AstProvider): TypeKey {
            return TypeKey(provider.declaredTypeOf(Set::class, type), qualifier)
        }
    }

    data class MapKey(val key: AstType, val value: AstType, val qualifier: AstAnnotation? = null) : ContainerKey() {
        override fun containerTypeKey(provider: AstProvider): TypeKey {
            return TypeKey(provider.declaredTypeOf(Map::class, key, value), qualifier)
        }
    }

    companion object {
        fun fromContainer(key: TypeKey): ContainerKey? {
            if (key.type.isSet()) {
                return SetKey(key.type.arguments[0], key.qualifier)
            }
            if (key.type.isMap()) {
                return MapKey(key.type.arguments[0], key.type.arguments[1], key.qualifier)
            }
            return null
        }
    }
}

data class ScopedComponent(
    val type: AstClass,
    val accessor: Accessor,
)
