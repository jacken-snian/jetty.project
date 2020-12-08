//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>A Ternary Trie String lookup data structure.</p>
 * <p>
 * This Trie is of a fixed size and cannot grow (which can be a good thing with regards to DOS when used as a cache).
 * </p>
 * <p>
 * The Trie is stored in 3 arrays:
 * </p>
 * <dl>
 * <dt>char[] _tree</dt><dd>This is semantically 2 dimensional array flattened into a 1 dimensional char array. The second dimension
 * is that every 4 sequential elements represents a row of: character; hi index; eq index; low index, used to build a
 * ternary trie of key strings.</dd>
 * <dt>String[] _key</dt><dd>An array of key values where each element matches a row in the _tree array. A non zero key element
 * indicates that the _tree row is a complete key rather than an intermediate character of a longer key.</dd>
 * <dt>V[] _value</dt><dd>An array of values corresponding to the _key array</dd>
 * </dl>
 * <p>The lookup of a value will iterate through the _tree array matching characters. If the equal tree branch is followed,
 * then the _key array is looked up to see if this is a complete match.  If a match is found then the _value array is looked up
 * to return the matching value.
 * </p>
 * <p>
 * This Trie may be instantiated either as case sensitive or insensitive.
 * </p>
 * <p>This Trie is not Threadsafe and contains no mutual exclusion
 * or deliberate memory barriers.  It is intended for an ArrayTrie to be
 * built by a single thread and then used concurrently by multiple threads
 * and not mutated during that access.  If concurrent mutations of the
 * Trie is required external locks need to be applied.
 * </p>
 *
 * @param <V> the Entry type
 */
class TernaryTrie<V> extends AbstractTrie<V>
{
    private static final int LO = 1;
    private static final int EQ = 2;
    private static final int HI = 3;

    /**
     * The Size of a Trie row is the char, and the low, equal and high
     * child pointers
     */
    private static final int ROW_SIZE = 4;

    /**
     * The maximum capacity of the implementation. Over that,
     * the 16 bit indexes can overflow and the trie
     * cannot find existing entries anymore.
     */
    private static final int MAX_CAPACITY = Character.MAX_VALUE;

    @SuppressWarnings("unchecked")
    private static class Node<V>
    {
        String _key;
        V _value;
        Table<V>[] _next;

        public Node()
        {
        }

        public Node(String s, V v)
        {
            _key = s;
            _value = v;
        }

        public void set(String s, V v)
        {
            _key = s;
            _value = v;
        }

        @Override
        public String toString()
        {
            return _key + "=" + _value;
        }

        public String dump()
        {
            return String.format("'%s'='%s' [%x,%x,%x]",
                _key,
                _value,
                _next == null ? null : Objects.hashCode(_next[0]),
                _next == null ? null : Objects.hashCode(_next[1]),
                _next == null ? null : Objects.hashCode(_next[2])
                );
        }
    }

    private static class Table<V>
    {
        /**
         * The Trie rows in a single array which allows a lookup of row,character
         * to the next row in the Trie.  This is actually a 2 dimensional
         * array that has been flattened to achieve locality of reference.
         */
        final char[] _tree;
        final Node<V>[] _nodes;
        char _rows;

        @SuppressWarnings("unchecked")
        Table(int capacity)
        {
            _tree = new char[capacity * ROW_SIZE];
            _nodes = new Node[capacity];
        }

        public void clear()
        {
            _rows = 0;
            Arrays.fill(_nodes, null);
            Arrays.fill(_tree, (char)0);
        }
    }

    /**
     * The number of rows allocated
     */
    private final Table<V> _root;
    private final List<Table<V>> _tables = new ArrayList<>();
    private Table<V> _tail;

    public static <V> AbstractTrie<V> from(int capacity, int maxCapacity, boolean caseSensitive, Set<Character> alphabet, Map<String, V> contents)
    {
        if (capacity > MAX_CAPACITY || maxCapacity < 0)
            return null;

        AbstractTrie<V> trie = new TernaryTrie<V>(!caseSensitive, Math.max(capacity, maxCapacity));

        if (contents != null && !trie.putAll(contents))
            return null;
        return trie;
    }

    /**
     * Create a Trie
     *
     * @param insensitive true if the Trie is insensitive to the case of the key.
     * @param capacity The capacity of the Trie, which is in the worst case
     * is the total number of characters of all keys stored in the Trie.
     * The capacity needed is dependent of the shared prefixes of the keys.
     * For example, a capacity of 6 nodes is required to store keys "foo"
     * and "bar", but a capacity of only 4 is required to
     * store "bar" and "bat".
     */
    @SuppressWarnings("unchecked")
    TernaryTrie(boolean insensitive, int capacity)
    {
        super(insensitive);
        if (capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("ArrayTernaryTrie maximum capacity overflow (" + capacity + " > " + MAX_CAPACITY + ")");
        _root = new Table<V>(capacity);
        _tail = _root;
        _tables.add(_root);
    }

    @Override
    public void clear()
    {
        _root.clear();
        _tables.clear();
        _tables.add(_root);
        _tail = _root;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean put(String s, V v)
    {
        if (v == null)
            throw new IllegalArgumentException("Value cannot be null");

        Table<V> table = _root;
        int row = 0;
        int end = s.length();
        for (int i = 0; i < end; i++)
        {
            char c = s.charAt(i);
            if (c > 0xff)
                throw new IllegalArgumentException("Not ISO-8859-1");
            if (isCaseInsensitive() && c < 0x80)
                c = StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;

                // Do we need to create the new row?
                char nc = table._tree[idx];
                if (nc == 0)
                {
                    nc = table._tree[idx] = c;
                    if (row == table._rows)
                        table._rows++;
                }

                Node<V> node = table._nodes[row];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else if (table._rows < table._nodes.length)
                {
                    // point to a new row in this table;
                    row = table._rows;
                    table._tree[idx + branch] = (char)row;
                }
                else if (table != _tail && _tail._rows < _tail._nodes.length)
                {
                    // point to a new row in the tail table;
                    if (node == null)
                        node = table._nodes[row] = new Node<V>();
                    if (node._next == null)
                        node._next = new Table[3];
                    row = _tail._rows;
                    table._tree[idx + branch] = (char)row;
                    node._next[branch - 1] = _tail;
                    table = _tail;
                }
                else
                {
                    // point to a new row in a new table;
                    if (node == null)
                        node = table._nodes[row] = new Node<V>();
                    if (node._next == null)
                        node._next = new Table[3];
                    table = _tail = new Table<>(table._nodes.length);
                    _tables.add(table);
                    row = 0;
                    node._next[branch - 1] = table;
                }

                if (diff == 0)
                    break;
            }
        }

        if (row == table._rows)
            table._rows++;

        // Put the key and value
        Node<V> node = table._nodes[row];
        if (node == null)
            table._nodes[row] = new Node<V>(s, v);
        else
            node.set(s, v);

        return true;
    }

    @Override
    public V get(String s, int offset, int len)
    {
        Table<V> table = _root;
        int row = 0;
        if (table._rows == 0)
            return null;
        for (int i = 0; i < len; i++)
        {
            char c = s.charAt(offset + i);
            if (c > 0xff)
                return null;
            if (isCaseInsensitive() && c < 0x80)
                c = StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;

                Node<V> node = table._nodes[row];
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    return null;
                }

                if (diff == 0)
                    break;
            }
        }

        // Put the key and value
        Node<V> node = table._nodes[row];
        return node != null && node._key != null ? node._value : null;
    }

    @Override
    public V get(ByteBuffer b, int offset, int len)
    {
        Table<V> table = _root;
        int row = 0;
        if (table._rows == 0)
            return null;
        for (int i = 0; i < len; i++)
        {
            byte c = b.get(b.position() + offset + i);
            if (isCaseInsensitive() && c > 0)
                c = (byte)StringUtil.lowercases[c];

            while (true)
            {
                int idx = ROW_SIZE * row;

                Node<V> node = table._nodes[row];
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    return null;
                }

                if (diff == 0)
                    break;
            }
        }

        Node<V> node = table._nodes[row];
        return node != null && node._key != null ? node._value : null;
    }

    @Override
    public V getBest(String s)
    {
        return getBest(s, 0, s.length());
    }

    @Override
    public V getBest(String s, int offset, int len)
    {
        return getBest(_root, 0, s, offset, len);
    }

    private V getBest(Table<V> table, int row, String s, int offset, int len)
    {
        Node<V> match = table == _root && row == 0 && table._nodes[0] != null ? table._nodes[0] : null;
        loop : for (int i = 0; i < len; i++)
        {
            char c = s.charAt(offset + i);
            if (c > 0xFF)
                break;
            if (isCaseInsensitive() && c < 0x7f)
                c = StringUtil.lowercases[c];

            Node<V> node = table._nodes[row];
            while (true)
            {
                int idx = ROW_SIZE * row;
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    break loop;
                }

                node = table._nodes[row];
                if (diff == 0)
                {
                    if (node != null && node._key != null)
                    {
                        // found a match, recurse looking for a better one.
                        match = node;
                        V better = getBest(table, row, s, offset + i + 1, len - i - 1);
                        if (better != null)
                            return better;
                    }
                    break;
                }
            }
        }

        return (match != null && match._key != null) ? match._value : null;
    }

    @Override
    public V getBest(ByteBuffer b, int offset, int len)
    {
        if (_root._rows == 0)
            return null;
        if (b.hasArray())
            return getBest(_root, 0, b.array(), b.arrayOffset() + b.position() + offset, len);
        return getBest(_root, 0, b, offset, len);
    }

    @Override
    public V getBest(byte[] b, int offset, int len)
    {
        return getBest(_root, 0, b, offset, len);
    }

    private V getBest(Table<V> table, int row, byte[] b, int offset, int len)
    {
        Node<V> match = table == _root && row == 0 && table._nodes[0] != null ? table._nodes[0] : null;
        loop : for (int i = 0; i < len; i++)
        {
            byte c = b[offset + i];
            if (isCaseInsensitive() && c > 0)
                c = (byte)StringUtil.lowercases[c];

            Node<V> node = table._nodes[row];
            while (true)
            {
                int idx = ROW_SIZE * row;
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    break loop;
                }

                node = table._nodes[row];
                if (diff == 0)
                {
                    if (node != null && node._key != null)
                    {
                        // found a match, recurse looking for a better one.
                        match = node;
                        V better = getBest(table, row, b, offset + i + 1, len - i - 1);
                        if (better != null)
                            return better;
                    }
                    break;
                }
            }
        }

        return (match != null && match._key != null) ? match._value : null;
    }

    private V getBest(Table<V> table, int row, ByteBuffer b, int offset, int len)
    {
        Node<V> match = table == _root && row == 0 && table._nodes[0] != null ? table._nodes[0] : null;
        loop : for (int i = 0; i < len; i++)
        {
            byte c = b.get(b.position() + offset + i);
            if (isCaseInsensitive() && c > 0)
                c = (byte)StringUtil.lowercases[c];

            Node<V> node = table._nodes[row];
            while (true)
            {
                int idx = ROW_SIZE * row;
                char nc = table._tree[idx];
                int diff = (int)c - nc;
                int branch = diff == 0 ? EQ : hilo(diff);

                char next = table._tree[idx + branch];
                if (node != null && node._next != null && node._next[branch - 1] != null)
                {
                    // The branch is to a different table;
                    table = node._next[branch - 1];
                    row = next;
                }
                else if (next != 0)
                {
                    // The branch is to this table
                    row = next;
                }
                else
                {
                    break loop;
                }

                node = table._nodes[row];
                if (diff == 0)
                {
                    if (node != null && node._key != null)
                    {
                        // found a match, recurse looking for a better one.
                        match = node;
                        V better = getBest(table, row, b, offset + i + 1, len - i - 1);
                        if (better != null)
                            return better;
                    }
                    break;
                }
            }
        }

        return (match != null && match._key != null) ? match._value : null;
    }

    @Override
    public String toString()
    {
        StringBuilder buf = new StringBuilder();
        buf.append("TT@").append(Integer.toHexString(hashCode())).append('{');
        buf.append("ci=").append(isCaseInsensitive()).append(';');
        buf.append(_tables.stream().flatMap(t -> Arrays.stream(t._nodes))
            .filter(Objects::nonNull)
            .filter(n -> n._key != null)
            .map(Node::toString)
            .collect(Collectors.joining(",")));
        buf.append('}');
        return buf.toString();
    }

    @Override
    public Set<String> keySet()
    {
        return _tables.stream().flatMap(t -> Arrays.stream(t._nodes))
            .filter(Objects::nonNull)
            .filter(n -> n._key != null)
            .map(n -> n._key)
            .collect(Collectors.toSet());
    }

    public int size()
    {
        return (int)_tables.stream().flatMap(t -> Arrays.stream(t._nodes))
            .filter(Objects::nonNull)
            .filter(n -> n._key != null)
            .count();
    }

    public boolean isEmpty()
    {
        return _tables.stream().flatMap(t -> Arrays.stream(t._nodes))
            .filter(Objects::nonNull)
            .anyMatch(n -> n._key != null);
    }

    public Set<Map.Entry<String, V>> entrySet()
    {
        return _tables.stream().flatMap(t -> Arrays.stream(t._nodes))
            .filter(Objects::nonNull)
            .filter(n -> n._key != null)
            .map(n -> new AbstractMap.SimpleEntry<>(n._key, n._value))
            .collect(Collectors.toSet());
    }

    public static int hilo(int diff)
    {
        // branchless equivalent to return ((diff<0)?LO:HI);
        // return 3+2*((diff&Integer.MIN_VALUE)>>Integer.SIZE-1);
        return 1 + (diff | Integer.MAX_VALUE) / (Integer.MAX_VALUE / 2);
    }

    public void dump()
    {
        for (Table<V> table : _tables)
        {
            System.err.println(Integer.toHexString(Objects.hashCode(table)));
            for (int r = 0; r < table._rows; r++)
            {
                char c = table._tree[r * ROW_SIZE];
                System.err.printf("%4d [%s,%d,%d,%d] : %s%n",
                    r,
                    (c < ' ' || c > 127) ? Integer.toHexString(c) : ("'" + c + "'"),
                    (int)table._tree[r * ROW_SIZE + LO],
                    (int)table._tree[r * ROW_SIZE + EQ],
                    (int)table._tree[r * ROW_SIZE + HI],
                    table._nodes[r] == null ? null : table._nodes[r].dump());
            }
        }
    }

    public static void main(String... arg)
    {
        TernaryTrie<String> trie = new TernaryTrie<>(false, 8);
        trie.put("hi", "hi");
        trie.put("hip", "hip");
        trie.put("hell", "hell");
        trie.put("foo", "foo");
        trie.put("foobar", "foobar");
        trie.put("fop", "fop");
        trie.put("hit", "hit");
        trie.put("zip", "zip");
        trie.dump();
        System.err.println(trie.get("hi"));
        System.err.println(trie.get("hip"));
        System.err.println(trie.get("hell"));
        System.err.println(trie.get("foo"));
        System.err.println(trie.get("foobar"));
        System.err.println(trie.get("fop"));
        System.err.println(trie.get("hit"));
        System.err.println(trie.get("zip"));
        System.err.println("---");
        System.err.println(trie.getBest("hi"));
        System.err.println(trie.getBest("hixxx"));
        System.err.println(trie.getBest("foobar"));
        System.err.println(trie.getBest("foobarxxx"));
        System.err.println(trie.getBest("xxxfoobarxxx", 3, 9));
        System.err.println(trie.getBest("foobor"));
        System.err.println("---");
        trie.put("", "empty");
        trie.dump();
        System.err.println(trie.getBest("whatever"));
    }
}