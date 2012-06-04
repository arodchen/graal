/*
 * Copyright (c) 2011, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.graph;

import java.util.*;

import com.oracle.graal.graph.iterators.*;



public final class NodeBitMap extends NodeIterable<Node>{
    private final boolean autoGrow;
    private final BitMap bitMap;
    private final Graph graph;

    public NodeBitMap(Graph graph) {
        this(graph, false);
    }

    public NodeBitMap(Graph graph, boolean autoGrow) {
        this(graph, autoGrow, new BitMap(graph.nodeIdCount()));
    }

    private NodeBitMap(Graph graph, boolean autoGrow, BitMap bits) {
        this.graph = graph;
        this.autoGrow = autoGrow;
        bitMap = bits;
    }

    public Graph graph() {
        return graph;
    }

    public boolean setIntersect(NodeBitMap other) {
        return bitMap.setIntersect(other.bitMap);
    }

    public void setUnion(NodeBitMap other) {
        bitMap.setUnion(other.bitMap);
    }

    public void negate() {
        grow();
        bitMap.negate();
    }

    public boolean isNotNewMarked(Node node) {
        return !isNew(node) && isMarked(node);
    }

    public boolean isNotNewNotMarked(Node node) {
        return !isNew(node) && !isMarked(node);
    }

    public boolean isMarked(Node node) {
        if (autoGrow && isNew(node)) {
            return false;
        }
        assert check(node);
        return bitMap.get(node.id());
    }

    public boolean isNew(Node node) {
        return node.id() >= bitMap.size();
    }

    public void mark(Node node) {
        if (autoGrow && isNew(node)) {
            grow();
        }
        assert check(node);
        bitMap.set(node.id());
    }

    public void clear(Node node) {
        if (autoGrow && isNew(node)) {
            return;
        }
        assert check(node);
        bitMap.clear(node.id());
    }

    public void clearAll() {
        bitMap.clearAll();
    }

    public void grow(Node node) {
        bitMap.grow(node.id() + 1);
    }

    public void grow() {
        bitMap.grow(graph.nodeIdCount());
    }

    private boolean check(Node node) {
        assert node.graph() == graph : "this node is not part of the graph";
        assert !isNew(node) : "node was added to the graph after creating the node bitmap";
        assert node.isAlive() : "node is deleted!";
        return true;
    }

    @Override
    public String toString() {
        return bitMap.toBinaryString();
    }

    public <T extends Node> void markAll(Iterable<T> nodes) {
        for (Node node : nodes) {
            mark(node);
        }
    }

    private static class MarkedNodeIterator implements Iterator<Node> {
        private final NodeBitMap visited;
        private Iterator<Node> nodes;
        private Node nextNode;

        public MarkedNodeIterator(NodeBitMap visited, Iterator<Node> nodes) {
            this.visited = visited;
            this.nodes = nodes;
            forward();
        }

        private void forward() {
            do {
                if (!nodes.hasNext()) {
                    nextNode = null;
                    return;
                }
                nextNode = nodes.next();
                if (visited.isNew(nextNode)) {
                    nextNode = null;
                    return;
                }
            } while (!visited.isMarked(nextNode));
        }

        @Override
        public boolean hasNext() {
            return nextNode != null;
        }

        @Override
        public Node next() {
            try {
                return nextNode;
            } finally {
                forward();
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

    @Override
    public Iterator<Node> iterator() {
        return new MarkedNodeIterator(NodeBitMap.this, graph().getNodes().iterator());
    }

    public int cardinality() {
        return bitMap.cardinality();
    }

    public NodeBitMap copy() {
        return new NodeBitMap(graph, autoGrow, bitMap.copy());
    }

    @Override
    public NodeIterable<Node> distinct() {
        return this;
    }

    @Override
    public int count() {
        return bitMap.cardinality();
    }

    @Override
    public boolean contains(Node node) {
        return isMarked(node);
    }
}
