package ru.arc.network.repos

/**
 * Simple test implementation of RepoData.
 */
class TestRepoData(
    private val _id: String,
    var value: String = "",
    var counter: Int = 0,
    private var _remove: Boolean = false
) : RepoData<TestRepoData>() {

    override fun id(): String = _id

    override fun isRemove(): Boolean = _remove

    fun markForRemoval() {
        _remove = true
    }

    override fun merge(other: TestRepoData) {
        this.value = other.value
        this.counter = other.counter
        this._remove = other._remove
    }

    /**
     * Modify value and mark as dirty.
     */
    fun updateValue(newValue: String) {
        this.value = newValue
        isDirty = true
    }

    /**
     * Increment counter and mark as dirty.
     */
    fun incrementCounter() {
        this.counter++
        isDirty = true
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TestRepoData) return false
        return _id == other._id
    }

    override fun hashCode(): Int = _id.hashCode()

    override fun toString(): String = "TestRepoData(id=$_id, value=$value, counter=$counter, remove=$_remove)"
}


