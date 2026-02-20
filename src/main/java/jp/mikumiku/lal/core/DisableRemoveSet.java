package jp.mikumiku.lal.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class DisableRemoveSet implements Set<UUID> {
    private final Set<UUID> internal = ConcurrentHashMap.newKeySet();

    @Override public boolean remove(Object o) { return false; }
    @Override public void clear() { }
    @Override public boolean removeAll(Collection<?> c) { return false; }
    @Override public boolean removeIf(Predicate<? super UUID> filter) { return false; }
    @Override public boolean retainAll(Collection<?> c) { return false; }

    public boolean internalRemove(Object o) { return internal.remove(o); }
    public void internalClear() { internal.clear(); }

    @Override public int size() { return internal.size(); }
    @Override public boolean isEmpty() { return internal.isEmpty(); }
    @Override public boolean contains(Object o) { return internal.contains(o); }
    @Override public Iterator<UUID> iterator() { return internal.iterator(); }
    @Override public Object[] toArray() { return internal.toArray(); }
    @Override public <T> T[] toArray(T[] a) { return internal.toArray(a); }
    @Override public boolean add(UUID uuid) { return internal.add(uuid); }
    @Override public boolean containsAll(Collection<?> c) { return internal.containsAll(c); }
    @Override public boolean addAll(Collection<? extends UUID> c) { return internal.addAll(c); }
}
