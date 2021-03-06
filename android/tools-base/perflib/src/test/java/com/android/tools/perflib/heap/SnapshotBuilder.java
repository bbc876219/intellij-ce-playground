/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.perflib.heap;

import com.android.tools.perflib.heap.io.InMemoryBuffer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

/**
 * Utility for creating Snapshot objects to be used in tests.
 *
 * As the main concern here is graph connectivity, we only initialize the app heap, creating
 * ClassInstance objects with id in [1..numNodes], each instance pointing to a unique ClassObj.
 * The class ids range in [101..100+numNodes] and their size is set to match the id of their object
 * instance. The default heap holds the roots.
 */
public class SnapshotBuilder {
    public static final int SOFT_REFERENCE_ID = 99;

    private final Snapshot mSnapshot;

    private final ClassInstance[] mNodes;

    private final int[] mOffsets;

    private final ByteBuffer mDirectBuffer;

    private final int mMaxTotalNodes;

    private short mNextAvailableSoftReferenceNodeId;

    public SnapshotBuilder(int numNodes) {
        this(numNodes, 0);
    }

    public SnapshotBuilder(int numNodes, int numSoftNodes) {
        mMaxTotalNodes = numNodes + numSoftNodes;
        InMemoryBuffer buffer = new InMemoryBuffer(2 * mMaxTotalNodes * mMaxTotalNodes);
        mDirectBuffer = buffer.getDirectBuffer();
        mOffsets = new int[mMaxTotalNodes + 1];

        mSnapshot = new Snapshot(buffer);
        mSnapshot.setHeapTo(13, "testHeap");
        mSnapshot.setIdSize(2);

        ClassObj softClazz = new ClassObj(SOFT_REFERENCE_ID, null, ClassObj.getReferenceClassName(), 0);
        softClazz.setClassLoaderId(0);
        softClazz.setFields(new Field[]{new Field(Type.OBJECT, "referent")});
        softClazz.setIsSoftReference();
        mSnapshot.addClass(SOFT_REFERENCE_ID, softClazz);

        mNodes = new ClassInstance[mMaxTotalNodes + 1];
        for (int i = 1; i <= numNodes; i++) {
            // Use same name classes on different loaders to extend test coverage
            ClassObj clazz = new ClassObj(100 + i, null, "Class" + (i / 2), 0);
            clazz.setClassLoaderId(i % 2);
            clazz.setFields(new Field[0]);
            mSnapshot.addClass(100 + i, clazz);

            mOffsets[i] = 2 * (i - 1) * mMaxTotalNodes;
            mNodes[i] = new ClassInstance(i, null, mOffsets[i]);
            mNodes[i].setClassId(100 + i);
            mNodes[i].setSize(i);
            mSnapshot.addInstance(i, mNodes[i]);
        }

        mNextAvailableSoftReferenceNodeId = (short)(numNodes + 1);
        for (int i = mNextAvailableSoftReferenceNodeId; i <= mMaxTotalNodes; ++i) {
            mOffsets[i] = 2 * (i - 1) * mMaxTotalNodes;
            mNodes[i] = new ClassInstance(i, null, mOffsets[i]);
            mNodes[i].setClassId(SOFT_REFERENCE_ID);
            mNodes[i].setSize(i);
            mSnapshot.addInstance(i, mNodes[i]);
        }
    }

    public SnapshotBuilder addReferences(int nodeFrom, int... nodesTo) {
        assert mNodes[nodeFrom].getClassObj().getFields().length == 0;

        Field[] fields = new Field[nodesTo.length];
        for (int i = 0; i < nodesTo.length; i++) {
            mDirectBuffer.putShort(mOffsets[nodeFrom] + i * 2, (short) nodesTo[i]);
            // Fields should support duplicated field names due to inheritance of private fields
            fields[i] = new Field(Type.OBJECT, "duplicated_name");
        }

        mNodes[nodeFrom].getClassObj().setFields(fields);
        return this;
    }

    /**
     * Inserts a soft reference instance between <code>nodeFrom</code> to <code>nodeTo</code>.
     *
     * @param nodeFrom the parent node
     * @param nodeTo the child node
     * @return this
     */
    public SnapshotBuilder insertSoftRefences(int nodeFrom, int... nodesTo) {
        Field[] nodeFromFields = mNodes[nodeFrom].getClassObj().getFields();
        Field[] newFields = Arrays.copyOf(nodeFromFields, nodeFromFields.length + nodesTo.length);
        for (int i = 0; i < nodesTo.length; ++i) {
            short softReferenceId = mNextAvailableSoftReferenceNodeId++;
            assert softReferenceId <= mMaxTotalNodes;
            mDirectBuffer.putShort(mOffsets[nodeFrom] + (nodeFromFields.length + i) * 2, softReferenceId);
            newFields[nodeFromFields.length + i] = new Field(Type.OBJECT, "fSoftReference" + nodesTo[i]);
        }

        mNodes[nodeFrom].getClassObj().setFields(newFields);
        return this;
    }

    public SnapshotBuilder addRoot(int node) {
        RootObj root = new RootObj(RootType.JAVA_LOCAL, node);
        mSnapshot.setToDefaultHeap();
        mSnapshot.addRoot(root);
        return this;
    }

    public Snapshot build() {
        return mSnapshot;
    }
}
