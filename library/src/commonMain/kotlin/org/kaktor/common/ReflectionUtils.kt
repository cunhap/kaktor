package org.kaktor.common

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

fun <T : Any> KClass<T>.callByArguments(vararg args: Any): T? {
    val primaryConstructor = primaryConstructor
    val arguments = args.toList()

    return primaryConstructor?.let {
        val parameterNames = mutableListOf<Pair<KParameter, Any>>()
        val parameterList = primaryConstructor.parameters.toMutableList()

        for (obj in arguments) {
            val matchingParameter = parameterList.firstOrNull { it.type.classifier == obj::class }
            if (matchingParameter != null) {
                parameterNames.add(matchingParameter to obj)
                parameterList.remove(matchingParameter)  // Remove matched parameter to handle repeated class matches
            }
        }

        return primaryConstructor.callBy(parameterNames.toMap())
    }
}