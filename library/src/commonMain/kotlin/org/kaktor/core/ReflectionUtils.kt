package org.kaktor.core

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

/**
 * Calls the primary constructor of the specified class with the given arguments.
 *
 * @param args The arguments to pass to the constructor.
 * @return The instance created by the constructor, or null if the constructor is null or no matching parameters
 *         are found for the given arguments.
 */
fun <T : Any> KClass<T>.callByArguments(args: List<Any>): T? {
    val primaryConstructor = primaryConstructor

    return primaryConstructor?.let {
        val parameterNames = mutableListOf<Pair<KParameter, Any>>()
        val parameterList = primaryConstructor.parameters.toMutableList()

        for (obj in args) {
            val matchingParameter = parameterList.firstOrNull { it.type.classifier == obj::class }
            if (matchingParameter != null) {
                parameterNames.add(matchingParameter to obj)
                parameterList.remove(matchingParameter)  // Remove matched parameter to handle repeated class matches
            }
        }

        return primaryConstructor.callBy(parameterNames.toMap())
    }
}

fun <T: Any> KClass<T>.callByMap(args: Map<String, Any>): T? {
    val primaryConstructor = primaryConstructor
    return primaryConstructor?.let {
        val parameters = it.parameters.associateBy { p -> p.name }.filterKeys { pName-> pName != null }
        val argumentsMap = args.mapKeys { (key, _) -> parameters[key] ?: throw NoSuchElementException() }

        primaryConstructor.callBy(argumentsMap)
    }
}