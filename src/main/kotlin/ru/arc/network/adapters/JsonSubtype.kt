package ru.arc.network.adapters

import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
annotation class JsonSubtype(val clazz: KClass<*>, val name: String)
