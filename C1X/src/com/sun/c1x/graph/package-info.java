/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */

/**
 * IR Graph building.
 *
 * The {@link com.sun.c1x.graph.IR} class drives the generation of the HIR graph for a method, making use of other
 * utility classes in this package.
 *
 * The graph building is separated into a basic build phase ({@link com.sun.c1x.graph.IR#buildGraph} method)and
 * (currently) two optimization phases ({@link com.sun.c1x.graph.IR#optimize1} and
 * {@link com.sun.c1x.graph.IR#optimize2}) although the basic phase also does some (basic) optimizations.
 *
 * <H2>Basic Graph Build Phase</H2>
 *
 * {@code IR.buildGraph} creates an {@link com.sun.c1x.ir.IRScope topScope} object,
 * that represents a context for inlining, and then invokes the constructor for the
 * {@link com.sun.c1x.graph.GraphBuilder} class, passing the {@link com.sun.c1x.C1XCompilation}, and {@code IR}
 * instances, which are cached. The following support objects are created in the constructor:
 *
 * <ul>
 * <li>{@code memoryMap}: an instance of {@link com.sun.c1x.graph.MemoryMap}
 * <li>{@code localValueMap}: an instance of {@link com.sun.c1x.opt.ValueMap}
 * <li>{@code canonicalizer}: an instance of {@link com.sun.c1x.opt.Canonicalizer}
 * </ul>
 *
 * Now the {@link com.sun.c1x.graph.GraphBuilder#build} is invoked with {@code topScope} as argument.
 *
 * <H3>{@code GraphBuilder.build}</H3>
 *
 * <ol>
 * <li>The {@link com.sun.c1x.graph.IR#startBlock} field of the cached {@code IR} instance is set to a newly created
 * {@link com.sun.c1x.ir.BlockBegin} node, with bytecode index 0 and then the {@link com.sun.c1x.graph.BlockMap} is
 * constructed by calling {@link com.sun.c1x.C1XCompilation#getBlockMap}. This behaves slightly differently depending on
 * whether this is an OSR compilation. If so, a new {@code BlockBegin} node is added to the map at the OSR bytecode
 * index. The map is then built by the{@link com.sun.c1x.graph.BlockMap#build}, which takes a boolean argument that
 * controls whether a second pass is made over the bytecodes to compute stores in loops. This always false for an OSR
 * compilation (why?). Otherwise, it is only true if enabled by the {@link com.sun.c1x.C1XOptions#PhiLoopStores}
 * compilation option. FInally some unneeded state from the map is removed by the {@link com.sun.c1x.graph.BlockMap#cleanup} method, and
 * the statistics are updated.
 * </li>
 *
 * <li>Next the {@link com.sun.c1x.graph.GraphBuilder#pushRootScope} method is called, with the passed-in {@code IRScope}
 * object, the {@code BlockMap} returned by build and the {@code startBlock}. (Note: Unlike
 * {@link com.sun.c1x.graph.GraphBuilder#pushScope}, this method does not propagate the
 * {@link com.sun.c1x.graph.BlockMap#storesInLoops} field to the {@code IRScope} object, which means that
 * {@link com.sun.c1x.ir.BlockBegin#insertLoopPhis} will always get null for this value. Is this a bug?).
 * {@code pushRootScope} initializes the {@link com.sun.c1x.graph.GraphBuilder#scopeData} field with a
 * {@link com.sun.c1x.graph.ScopeData} instance, with null parent. The
 * {@link com.sun.c1x.graph.GraphBuilder#compilation} instance is called to get an {@link com.sun.c1x.ri.RiConstantPool}
 * , which is C1X's interface to constant pool information. The {@link com.sun.c1x.graph.GraphBuilder#curBlock} field is
 * set to the {@code startBlock}.
 * <p>
 *
 * Now a {@link com.sun.c1x.value.FrameState initialState} object is created by
 * {@link com.sun.c1x.graph.GraphBuilder#stateAtEntry}. If the method is not static, then a {@link com.sun.c1x.ir.Local}
 * instance is created at index 0. Since the receiver cannot be {@code null}, the
 * {@link com.sun.c1x.ir.Value.Flag#NonNull} flag is set. Additional {@code Local} instances are created for the
 * arguments to the method. The index is incremented by the number of slots occupied by the
 * {@link com.sun.c1x.ci.CiKind} corresponding to the argument type. All the {@code Local} instances are stored in the
 * {@code FrameState} using the {@link com.sun.c1x.value.FrameState#storeLocal} method. This {@code FrameState} is then
 * merged into the {@link com.sun.c1x.ir.BlockBegin#stateBefore} for the {@code startBlock}, which just results in a
 * copy since {@code stateBefore} will be {@code null}.
 * </li>
 * <li>
 * This step sets up three instance fields: {@link com.sun.c1x.graph.GraphBuilder#curBlock} and
 * {@link com.sun.c1x.graph.GraphBuilder#lastInstr} to {@code startBlock} and
 * {@link com.sun.c1x.graph.GraphBuilder#curState} to {@code initialState}. (N.B. the setting of {@code curBlock} is
 * redundant as it is done in {@code pushScope}).
 * </li>
 * <li>
 * Step 4 contains special handling for synchronized methods (TBD), otherwise it calls
 * {@link com.sun.c1x.graph.GraphBuilder#finishStartBlock} which adds a {@link com.sun.c1x.ir.Base} block as the end of
 * the {@code startBlock}. The {@code Base} block has one successor set to the (entry) block with flag
 * {@link com.sun.c1x.ir.BlockBegin.BlockFlag#StandardEntry}, that was created by {@code BlockMap.build} (and possibly a
 * successor to an OSREntry block).
 * </li>
 * <li>
 * Then the {@link com.sun.c1x.ir.IRScope#lockStackSize} is computed. (TBD)
 * </li>
 * <li>
 * Then the method is checked for being intrinsic, i.e., one that has a hard-wired implementation known to C1X. If so,
 * and {@link com.sun.c1x.C1XOptions#OptIntrinsify} is set, an attempt is made to inline it (TBD). Otherwise, or if the
 * intrinsification fails, normal processing continues by adding the entry block to the
 * {@link com.sun.c1x.graph.ScopeData} work list (kept topologically sorted) and calling
 * {@link com.sun.c1x.graph.GraphBuilder#iterateAllBlocks}.
 * </li>
 * <li>
 * Finally there is some cleanup code for synchronized blocks and OSR compilations.
 * </li>
 * </ol>
 *
 * <H3>{@code GraphBuilder.iterateAllBlocks}</H3>
 * {@code iterateAllBlocks} repeatedly removes a block from the work list and, if not already visited, marks it so,
 * kills the current memory map, sets {@code curBlock}, {@code curState} and {@code lastInstr} and then calls
 * {@link com.sun.c1x.graph.GraphBuilder#iterateBytecodesForBlock}.
 *
 * This process continues until all the blocks have been visited (processed) after which control returns to {@code
 * build}.
 * <p>

 * <H3>{@code GraphBuilder.iterateBytecodesForBlock}</H3>
 *
 * {@code iterateBytecodesForBlock} performs an abstract interpretation of the bytecodes in the block, appending new
 * nodes as necessary, until the last added node is an instance of {@link com.sun.c1x.ir.BlockEnd}. (Note: It has an
 * explicit check for finding a new {@code BlockBegin} before a {@code BlockEnd} but
 * {@link com.sun.c1x.graph.BlockMap#moveSuccessorLists} has a similar check so this may be redundant). For example,
 * consider the following bytecodes:
 *
 * <pre>
 * <code>
 *         0: iconst_0
 *         1: istore_2
 *         2: goto 22
 *         <code>
 * </pre>
 *
 * The {@code iconst_0} bytecode causes a {@link com.sun.c1x.ir.Constant} node representing zero to be pushed on the
 * {@code curState} stack and the node to be appended to the {@code BlockBegin} (entry) node associated with index 0.
 * The {@code istore_2} causes the node to be popped of the stack and stored in the local slot 2. No IR node is
 * generated for the {@code istore_2}. The {@code goto} creates a {@link com.sun.c1x.ir.Goto} node which is a subclass
 * of {@code BlockEnd}, so this terminates the iteration. As part of termination the {@code Goto} node is marked as the
 * end node of the current block and the {@code FrameState} is propagated to the successor node(s) by merging any
 * existing {@code FrameState} with the current state. If the target is a loop header node this involves inserting
 * {@link com.sun.c1x.ir.Phi} nodes. Finally, the target node is added to the {@code scopeData} work list.
 * <p>
 *
 *
 * @author Ben Titzer
 * @author Mick Jordan
 *
 */
package com.sun.c1x.graph;
