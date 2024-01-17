/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.groupby.hyperloglog;

import io.questdb.griffin.engine.groupby.GroupByAllocator;
import io.questdb.std.Unsafe;
import org.jetbrains.annotations.TestOnly;

import static io.questdb.griffin.engine.groupby.hyperloglog.HyperLogLogDenseRepresentation.MAX_PRECISION;
import static io.questdb.griffin.engine.groupby.hyperloglog.HyperLogLogDenseRepresentation.MIN_PRECISION;

/**
 * This is an implementation of HyperLogLog++ described in the paper
 * <a href="http://static.googleusercontent.com/media/research.google.com/fr//pubs/archive/40671.pdf">'HyperLogLog in
 * Practice: Algorithmic Engineering of a State of The Art Cardinality Estimation Algorithm'</a>.
 * <p>
 * This class assumes that specific HyperLogLog representations reserve the first byte in their memory layouts for
 * information about their type and the subsequent 8 bytes for cached cardinality.
 */
public class HyperLogLog {
    private static final int DEFAULT_PRECISION = 14;
    private static final long CARDINALITY_NULL_VALUE = -1;
    private static final long CACHED_CARDINALITY_OFFSET = Byte.BYTES;
    private static final byte SPARSE = 1;
    private static final byte DENSE = 0;

    private final int precision;
    private final HyperLogLogDenseRepresentation dense;
    private final HyperLogLogSparseRepresentation sparse;

    private long ptr;

    public HyperLogLog() {
        this(DEFAULT_PRECISION);
    }

    public HyperLogLog(int precision) {
        if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("Precision must be within the range of 4 to 18, inclusive.");
        }
        this.dense = new HyperLogLogDenseRepresentation(precision);
        this.sparse = new HyperLogLogSparseRepresentation(precision);
        this.precision = precision;
    }

    public void add(long hash) {
        if (getType() == SPARSE) {
            sparse.add(hash);
            ptr = sparse.ptr();
            if (sparse.isFull()) {
                convertToDense();
            }
        } else {
            dense.add(hash);
        }
        setCachedCardinality(CARDINALITY_NULL_VALUE);
    }

    private void convertToDense() {
        sparse.convertToDense(dense);
        this.ptr = dense.ptr();
        setType(DENSE);
    }

    public long computeCardinality() {
        long cardinality = getCachedCardinality();
        if (cardinality != CARDINALITY_NULL_VALUE) {
            return cardinality;
        }
        if (getType() == SPARSE) {
            cardinality = sparse.computeCardinality();
        } else {
            cardinality = dense.computeCardinality();
        }
        setCachedCardinality(cardinality);
        return cardinality;
    }

    public static long merge(HyperLogLog first, HyperLogLog second) {
        byte firstType = first.getType();
        byte secondType = second.getType();

        if (firstType == DENSE && secondType == DENSE) {
            return mergeDenseWithDense(first, second);
        } else if (firstType == SPARSE && secondType == SPARSE) {
            if (second.sparse.size() < first.sparse.size()) {
                return mergeSparseWithSparse(second, first);
            } else {
                return mergeSparseWithSparse(first, second);
            }
        } else if (firstType == SPARSE && secondType == DENSE) {
            return mergeSparseWithDense(first, second);
        } else if (firstType == DENSE && secondType == SPARSE) {
            return mergeSparseWithDense(second, first);
        }
        throw new IllegalStateException("Unexpected combination of HyperLogLog types.");
    }

    private static long mergeDenseWithDense(HyperLogLog src, HyperLogLog dst) {
        src.dense.copyTo(dst.dense);
        dst.of(dst.dense.ptr());
        dst.setCachedCardinality(CARDINALITY_NULL_VALUE);
        return dst.ptr();
    }

    private static long mergeSparseWithSparse(HyperLogLog src, HyperLogLog dst) {
        src.sparse.copyTo(dst.sparse);
        dst.of(dst.sparse.ptr());
        if (dst.sparse.isFull()) {
            dst.convertToDense();
        }
        dst.setCachedCardinality(CARDINALITY_NULL_VALUE);
        return dst.ptr();
    }

    private static long mergeSparseWithDense(HyperLogLog src, HyperLogLog dst) {
        src.sparse.copyTo(dst.dense);
        dst.of(dst.dense.ptr());
        dst.setCachedCardinality(CARDINALITY_NULL_VALUE);
        return dst.ptr();
    }

    public HyperLogLog of(long ptr) {
        if (ptr == 0) {
            if (HyperLogLogSparseRepresentation.calculateSparseSetMaxSize(precision) > 0) {
                this.ptr = sparse.of(0).ptr();
                setType(SPARSE);
            } else {
                this.ptr = dense.of(0).ptr();
                setType(DENSE);
            }
            setCachedCardinality(CARDINALITY_NULL_VALUE);
        } else {
            this.ptr = ptr;
            if (getType() == SPARSE) {
                sparse.of(ptr);
            } else {
                dense.of(ptr);
            }
        }
        return this;
    }

    public long ptr() {
        return ptr;
    }

    public void resetPtr() {
        ptr = 0;
    }

    public void setAllocator(GroupByAllocator allocator) {
        sparse.setAllocator(allocator);
        dense.setAllocator(allocator);
    }

    private byte getType() {
        return Unsafe.getUnsafe().getByte(ptr);
    }

    private void setType(byte type) {
        Unsafe.getUnsafe().putByte(ptr, type);
    }

    private void setCachedCardinality(long estimate) {
        Unsafe.getUnsafe().putLong(ptr + CACHED_CARDINALITY_OFFSET, estimate);
    }

    private long getCachedCardinality() {
        return Unsafe.getUnsafe().getLong(ptr + CACHED_CARDINALITY_OFFSET);
    }

    @TestOnly
    public boolean isSparse() {
        return getType() == SPARSE;
    }
}
