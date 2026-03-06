package jp.mikumiku.lal.core;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class DisableRemoveSet implements Set<UUID> {
    private final Set<UUID> internal = ConcurrentHashMap.newKeySet();

    @Override public boolean remove(Object o) { return false; }
    @Override public void clear() { }
    @Override public boolean removeAll(Collection<?> c) { return false; }
    @Override public boolean removeIf(Predicate<? super UUID> filter) { return false; }
    @Override public boolean retainAll(Collection<?> c) { return false; }

    public boolean internalRemove(Object o) {
        if (!LALAccessChecker.isCallerFromLAL()) return false;
        return internal.remove(o);
    }

    public void internalClear() {
        if (!LALAccessChecker.isCallerFromLAL()) return;
        internal.clear();
    }

    @Override public int size() { return internal.size(); }
    @Override public boolean isEmpty() { return internal.isEmpty(); }
    @Override public boolean contains(Object o) { return internal.contains(o); }

    @Override
    public Iterator<UUID> iterator() {
        final Iterator<UUID> delegate = internal.iterator();
        return new Iterator<UUID>() {
            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public UUID next() {
                return delegate.next();
            }

            @Override
            public void remove() {
            }

            @Override
            public void forEachRemaining(Consumer<? super UUID> action) {
                delegate.forEachRemaining(action);
            }
        };
    }

    @Override
    public Object[] toArray() {
        Object[] src = internal.toArray();
        Object[] copy = new Object[src.length];
        System.arraycopy(src, 0, copy, 0, src.length);
        return copy;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        Object[] src = internal.toArray();
        if (a.length < src.length) {
            T[] result = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), src.length);
            System.arraycopy(src, 0, result, 0, src.length);
            return result;
        }
        System.arraycopy(src, 0, a, 0, src.length);
        if (a.length > src.length) a[src.length] = null;
        return a;
    }

    @Override public boolean add(UUID uuid) { return internal.add(uuid); }
    @Override public boolean containsAll(Collection<?> c) { return internal.containsAll(c); }
    @Override public boolean addAll(Collection<? extends UUID> c) { return internal.addAll(c); }

    @Override
    public Spliterator<UUID> spliterator() {
        UUID[] snapshot = internal.toArray(new UUID[0]);
        return java.util.Arrays.spliterator(snapshot);
    }

    @Override
    public Stream<UUID> stream() {
        UUID[] snapshot = internal.toArray(new UUID[0]);
        return java.util.Arrays.stream(snapshot);
    }

    @Override
    public Stream<UUID> parallelStream() {
        UUID[] snapshot = internal.toArray(new UUID[0]);
        return java.util.Arrays.stream(snapshot).parallel();
    }

    @Override
    public void forEach(Consumer<? super UUID> action) {
        internal.forEach(action);
    }
}
