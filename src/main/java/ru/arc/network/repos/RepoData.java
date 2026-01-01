package ru.arc.network.repos;

import lombok.ToString;

@ToString
public abstract class RepoData<SELF extends RepoData<SELF>> {

    private transient boolean dirty = true;

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public abstract String id();
    public abstract boolean isRemove();
    public abstract void merge(SELF other);
}
