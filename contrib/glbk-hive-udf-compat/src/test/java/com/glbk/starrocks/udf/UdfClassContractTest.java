// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.glbk.starrocks.udf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks the StarRocks Java UDF class constraints. */
class UdfClassContractTest {
    private static final Class<?>[] VARARGS_CLASSES = {GlDateDelta.class, GlDateDiff.class, GlDateFormat.class,
        GlNow.class, GlHowManyDay.class, GlDateFirst.class, GlDateLast.class};

    @Test
    void exactlyOnePublicEvaluate() throws Exception {
        List<Class<?>> all = new ArrayList<>(java.util.Arrays.asList(VARARGS_CLASSES));
        all.add(GlNowNoArg.class);
        for (Class<?> c : all) {
            assertTrue(Modifier.isPublic(c.getModifiers()), c.getName());
            assertTrue(c.getConstructor() != null, c.getName());
            List<Method> evaluates = new ArrayList<>();
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() == Object.class) {
                    continue;
                }
                assertTrue(!Modifier.isStatic(m.getModifiers()),
                        "no public static methods in " + c.getName());
                if (m.getName().equals("evaluate")) {
                    evaluates.add(m);
                }
            }
            assertEquals(1, evaluates.size(), c.getName());
            assertEquals(String.class, evaluates.get(0).getReturnType(), c.getName());
            // no mutable shared state
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                assertTrue(Modifier.isFinal(f.getModifiers()) && Modifier.isStatic(f.getModifiers()),
                        c.getName() + "." + f.getName());
            }
        }
    }

    @Test
    void signatures() throws Exception {
        for (Class<?> c : VARARGS_CLASSES) {
            Method m = c.getMethod("evaluate", String[].class);
            assertTrue(m.isVarArgs(), c.getName());
        }
        Method noArg = GlNowNoArg.class.getMethod("evaluate");
        assertEquals(0, noArg.getParameterCount());
    }
}
