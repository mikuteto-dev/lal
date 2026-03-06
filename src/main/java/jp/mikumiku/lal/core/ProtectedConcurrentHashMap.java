package jp.mikumiku.lal.core;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public class ProtectedConcurrentHashMap<K, V> extends ConcurrentHashMap<K, V> {

    @Override
    public V put(K key, V value) {
        if (!LALAccessChecker.isCallerFromLAL()) return null;
        return super.put(key, value);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        if (!LALAccessChecker.isCallerFromLAL()) return null;
        return super.putIfAbsent(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        if (!LALAccessChecker.isCallerFromLAL()) return;
        super.putAll(m);
    }

    @Override
    public V remove(Object key) {
        if (!LALAccessChecker.isCallerFromLAL()) return null;
        return super.remove(key);
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (!LALAccessChecker.isCallerFromLAL()) return false;
        return super.remove(key, value);
    }

    @Override
    public void clear() {
        if (!LALAccessChecker.isCallerFromLAL()) return;
        super.clear();
    }

    @Override
    public V replace(K key, V value) {
        if (!LALAccessChecker.isCallerFromLAL()) return null;
        return super.replace(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        if (!LALAccessChecker.isCallerFromLAL()) return false;
        return super.replace(key, oldValue, newValue);
    }

    @Override
    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (!LALAccessChecker.isCallerFromLAL()) return null;
        return super.compute(key, remappingFunction);
    }

    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (!LALAccessChecker.isCallerFromLAL()) return null;
        return super.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (!LALAccessChecker.isCallerFromLAL()) return null;
        return super.computeIfPresent(key, remappingFunction);
    }

    @Override
    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (!LALAccessChecker.isCallerFromLAL()) return null;
        return super.merge(key, value, remappingFunction);
    }

    @Override
    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        if (!LALAccessChecker.isCallerFromLAL()) return;
        super.replaceAll(function);
    }
}
