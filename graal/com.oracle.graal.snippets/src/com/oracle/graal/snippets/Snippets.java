/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.snippets;

import java.lang.reflect.*;
import java.util.concurrent.*;

import com.oracle.graal.api.code.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.*;
import com.oracle.graal.compiler.phases.*;
import com.oracle.graal.compiler.util.*;
import com.oracle.graal.cri.*;
import com.oracle.graal.debug.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.java.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.extended.*;
import com.oracle.graal.nodes.java.*;

/**
 * Utilities for snippet installation and management.
 */
public class Snippets {

    public static void install(ExtendedRiRuntime runtime, TargetDescription target, SnippetsInterface obj) {
        Class<? extends SnippetsInterface> clazz = obj.getClass();
        BoxingMethodPool pool = new BoxingMethodPool(runtime);
        if (clazz.isAnnotationPresent(ClassSubstitution.class)) {
            installSubstitution(runtime, target, clazz, pool, clazz.getAnnotation(ClassSubstitution.class).value());
        } else {
            installSnippets(runtime, target, clazz, pool);
        }
    }

    private static void installSnippets(ExtendedRiRuntime runtime, TargetDescription target, Class< ? extends SnippetsInterface> clazz, BoxingMethodPool pool) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getAnnotation(Snippet.class) != null) {
                Method snippet = method;
                int modifiers = snippet.getModifiers();
                if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                    throw new RuntimeException("Snippet must not be abstract or native");
                }
                ResolvedJavaMethod snippetRiMethod = runtime.getResolvedJavaMethod(snippet);
                if (snippetRiMethod.compilerStorage().get(Graph.class) == null) {
                    buildSnippetGraph(snippetRiMethod, runtime, target, pool);
                }
            }
        }
    }

    private static void installSubstitution(ExtendedRiRuntime runtime, TargetDescription target, Class< ? extends SnippetsInterface> clazz,
                    BoxingMethodPool pool, Class<?> original) throws GraalInternalError {
        for (Method snippet : clazz.getDeclaredMethods()) {
            try {
                Method method = original.getDeclaredMethod(snippet.getName(), snippet.getParameterTypes());
                if (!method.getReturnType().isAssignableFrom(snippet.getReturnType())) {
                    throw new RuntimeException("Snippet has incompatible return type");
                }
                int modifiers = snippet.getModifiers();
                if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) {
                    throw new RuntimeException("Snippet must not be abstract or native");
                }
                ResolvedJavaMethod snippetRiMethod = runtime.getResolvedJavaMethod(snippet);
                StructuredGraph graph = buildSnippetGraph(snippetRiMethod, runtime, target, pool);
                runtime.getResolvedJavaMethod(method).compilerStorage().put(Graph.class, graph);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Could not resolve method to substitute with: " + snippet.getName(), e);
            }
        }
    }

    private static StructuredGraph buildSnippetGraph(final ResolvedJavaMethod snippetRiMethod, final ExtendedRiRuntime runtime, final TargetDescription target, final BoxingMethodPool pool) {
        final StructuredGraph graph = new StructuredGraph(snippetRiMethod);
        return Debug.scope("BuildSnippetGraph", new Object[] {snippetRiMethod, graph}, new Callable<StructuredGraph>() {
            @Override
            public StructuredGraph call() throws Exception {
                GraphBuilderConfiguration config = GraphBuilderConfiguration.getSnippetDefault();
                GraphBuilderPhase graphBuilder = new GraphBuilderPhase(runtime, config, OptimisticOptimizations.NONE);
                graphBuilder.apply(graph);

                Debug.dump(graph, "%s: %s", snippetRiMethod.name(), GraphBuilderPhase.class.getSimpleName());

                new SnippetIntrinsificationPhase(runtime, pool).apply(graph);

                for (Invoke invoke : graph.getInvokes()) {
                    MethodCallTargetNode callTarget = invoke.callTarget();
                    ResolvedJavaMethod targetMethod = callTarget.targetMethod();
                    ResolvedJavaType holder = targetMethod.holder();
                    if (enclosedInSnippetsClass(holder)) {
                        StructuredGraph targetGraph = (StructuredGraph) targetMethod.compilerStorage().get(Graph.class);
                        if (targetGraph == null) {
                            targetGraph = buildSnippetGraph(targetMethod, runtime, target, pool);
                        }
                        InliningUtil.inline(invoke, targetGraph, true);
                        Debug.dump(graph, "after inlining %s", targetMethod);
                        if (GraalOptions.OptCanonicalizer) {
                            new WordTypeRewriterPhase(target).apply(graph);
                            new CanonicalizerPhase(target, runtime, null).apply(graph);
                        }
                    }
                }

                new SnippetIntrinsificationPhase(runtime, pool).apply(graph);

                new WordTypeRewriterPhase(target).apply(graph);

                new DeadCodeEliminationPhase().apply(graph);
                if (GraalOptions.OptCanonicalizer) {
                    new CanonicalizerPhase(target, runtime, null).apply(graph);
                }

                for (LoopEndNode end : graph.getNodes(LoopEndNode.class)) {
                    end.disableSafepoint();
                }

                new InsertStateAfterPlaceholderPhase().apply(graph);

                Debug.dump(graph, "%s: Final", snippetRiMethod.name());

                snippetRiMethod.compilerStorage().put(Graph.class, graph);

                return graph;
            }

            private boolean enclosedInSnippetsClass(ResolvedJavaType holder) {
                Class enclosingClass = holder.toJava();
                while (enclosingClass != null) {
                    if (SnippetsInterface.class.isAssignableFrom(enclosingClass)) {
                        return true;
                    }
                    enclosingClass = enclosingClass.getEnclosingClass();
                }
                return false;
            }
        });

    }
}