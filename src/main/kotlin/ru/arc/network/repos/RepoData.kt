package ru.arc.network.repos

abstract class RepoData<SELF : RepoData<SELF>> {

    @Transient
    var isDirty: Boolean = true

    abstract fun id(): String
    abstract val isRemove: Boolean
    abstract fun merge(other: SELF)
}
