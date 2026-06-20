package com.semirisk.util;

import java.util.AbstractList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.RandomAccess;

/**
 * 有界循环缓冲区。当元素数量超过容量时，新元素会淘汰最旧的元素。
 * 用于审计日志等需要限制内存占用的场景。
 */
public class CircularBuffer<E> extends AbstractList<E> implements RandomAccess, Cloneable {

    private final E[] buffer;
    private final int capacity;
    private int head = 0;  // 下一个写入位置
    private int size = 0;  // 当前元素数量

    @SuppressWarnings("unchecked")
    public CircularBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = (E[]) new Object[capacity];
    }

    @Override
    public E set(int index, E element) {
        throw new UnsupportedOperationException("CircularBuffer is append-only");
    }

    @Override
    public void add(int index, E element) {
        if (index != size) {
            throw new IndexOutOfBoundsException("Can only append to CircularBuffer");
        }
        add(element);
    }

    @Override
    public boolean add(E element) {
        if (element == null) return false;
        buffer[head] = element;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
        return true;
    }

    @Override
    public E get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return buffer[(head - size + index + capacity) % capacity];
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<>() {
            private int cursor = 0;

            @Override
            public boolean hasNext() {
                return cursor < size;
            }

            @Override
            public E next() {
                return get(cursor++);
            }
        };
    }

    @Override
    public void clear() {
        head = 0;
        size = 0;
    }
}
