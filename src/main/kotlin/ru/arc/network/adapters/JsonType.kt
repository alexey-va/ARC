package ru.arc.network.adapters

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class JsonType(val property: String, val subtypes: Array<JsonSubtype>)
