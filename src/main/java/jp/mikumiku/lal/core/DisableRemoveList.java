package jp.mikumiku.lal.core;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class DisableRemoveList<T> implements List<T> {

    private final CopyOnWriteArrayList<T> internal = new CopyOnWriteArrayList<>();

    private static boolean isCallerFromLAL() {
        return LALAccessChecker.isCallerFromLAL();
    }

    public boolean internalRemove(T element) {
        if (!isCallerFromLAL()) return false;
        return internal.remove(element);
    }

    public void internalClear() {
        if (!isCallerFromLAL()) return;
        internal.clear();
    }

    @Override
    public boolean remove(Object o) {
        if (!isCallerFromLAL()) return false;
        return internal.remove(o);
    }

    @Override
    public T remove(int index) {
        if (!isCallerFromLAL()) return null;
        return internal.remove(index);
    }

    @Override
    public void clear() {
        if (!isCallerFromLAL()) return;
        internal.clear();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        if (!isCallerFromLAL()) return false;
        return internal.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        if (!isCallerFromLAL()) return false;
        return internal.retainAll(c);
    }

    @Override
    public T set(int index, T element) {
        if (!isCallerFromLAL()) return null;
        return internal.set(index, element);
    }

    @Override
    public void replaceAll(UnaryOperator<T> operator) {
        if (!isCallerFromLAL()) return;
        internal.replaceAll(operator);
    }

    @Override
    public boolean removeIf(Predicate<? super T> filter) {
        if (!isCallerFromLAL()) return false;
        return internal.removeIf(filter);
    }

    @Override
    public Iterator<T> iterator() {
        final Iterator<T> delegate = internal.iterator();
        return new Iterator<T>() {
            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public T next() {
                return delegate.next();
            }

            @Override
            public void remove() {
            }

            @Override
            public void forEachRemaining(Consumer<? super T> action) {
                delegate.forEachRemaining(action);
            }
        };
    }

    @Override
    public ListIterator<T> listIterator() {
        return listIterator(0);
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        Object[] snapshot = internal.toArray();
        @SuppressWarnings("unchecked")
        T[] snap = (T[]) snapshot;
        return new ListIterator<T>() {
            int cursor = index;

            @Override public boolean hasNext() { return cursor < snap.length; }
            @Override public T next() { return snap[cursor++]; }
            @Override public boolean hasPrevious() { return cursor > 0; }
            @Override public T previous() { return snap[--cursor]; }
            @Override public int nextIndex() { return cursor; }
            @Override public int previousIndex() { return cursor - 1; }
            @Override public void remove() { }
            @Override public void set(T t) { }
            @Override public void add(T t) { }
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
    public <E> E[] toArray(E[] a) {
        Object[] src = internal.toArray();
        if (a.length < src.length) {
            E[] result = (E[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), src.length);
            System.arraycopy(src, 0, result, 0, src.length);
            return result;
        }
        System.arraycopy(src, 0, a, 0, src.length);
        if (a.length > src.length) a[src.length] = null;
        return a;
    }

    @Override
    public Spliterator<T> spliterator() {
        Object[] snap = internal.toArray();
        @SuppressWarnings("unchecked")
        T[] typed = (T[]) snap;
        return java.util.Arrays.spliterator(typed);
    }

    @Override
    public Stream<T> stream() {
        Object[] snap = internal.toArray();
        @SuppressWarnings("unchecked")
        T[] typed = (T[]) snap;
        return java.util.Arrays.stream(typed);
    }

    @Override
    public Stream<T> parallelStream() {
        Object[] snap = internal.toArray();
        @SuppressWarnings("unchecked")
        T[] typed = (T[]) snap;
        return java.util.Arrays.stream(typed).parallel();
    }

    @Override
    public boolean add(T t) {
        return internal.add(t);
    }

    @Override
    public void add(int index, T element) {
        internal.add(index, element);
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        return internal.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        return internal.addAll(index, c);
    }

    @Override
    public T get(int index) {
        return internal.get(index);
    }

    @Override
    public int size() {
        return internal.size();
    }

    @Override
    public boolean isEmpty() {
        return internal.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return internal.contains(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return internal.containsAll(c);
    }

    @Override
    public int indexOf(Object o) {
        return internal.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return internal.lastIndexOf(o);
    }

    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        return internal.subList(fromIndex, toIndex);
    }

    @Override
    public void sort(Comparator<? super T> c) {
        internal.sort(c);
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        internal.forEach(action);
    }
}
