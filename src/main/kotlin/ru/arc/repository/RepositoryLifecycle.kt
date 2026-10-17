package ru.arc.repository

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/**
 * Memory-only completion signal after the facade detaches. Accepted children
 * finish normally; repository timers have a separate supervisor. Callers must
 * reject new operations through their detached runtime/availability flag.
 */
internal fun sealRepositoryOperations(scope: CoroutineScope) {
    checkNotNull(scope.coroutineContext[Job] as? CompletableJob) { "Repository operation scope must be completable" }.complete()
}

/** Worker-only: finish finite accepted operations before flushing their dirty entities. */
internal fun drainRepository(repository: CachedRepository<*>, operations: CoroutineScope) {
    runBlocking {
        operations.coroutineContext[Job]?.join()
        repository.shutdown()
    }
}
