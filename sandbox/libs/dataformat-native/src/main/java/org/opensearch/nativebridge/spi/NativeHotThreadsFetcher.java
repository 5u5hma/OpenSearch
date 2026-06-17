/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.nativebridge.spi;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * FFM fetcher for native (Rust/Tokio) thread stacks.
 * Calls into libopensearch_native.so to capture native thread dumps
 * using eu-stack, filtering for non-JVM threads.
 */
public class NativeHotThreadsFetcher {

    private static final Logger logger = LogManager.getLogger(NativeHotThreadsFetcher.class);

    private static final MethodHandle NATIVE_HOT_THREADS;
    private static final MethodHandle SIZE_HINT;

    static {
        SymbolLookup lookup = NativeLibraryLoader.symbolLookup();
        Linker linker = Linker.nativeLinker();

        NATIVE_HOT_THREADS = linker.downcallHandle(
            lookup.find("native_hot_threads").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,    // return: bytes written or error
                ValueLayout.ADDRESS,      // out_ptr
                ValueLayout.JAVA_LONG     // out_cap
            )
        );

        SIZE_HINT = linker.downcallHandle(
            lookup.find("native_hot_threads_size_hint").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG)
        );
    }

    private NativeHotThreadsFetcher() {}

    /**
     * Captures native thread stacks from the Rust/Tokio runtime.
     * Returns a human-readable string with stack traces of all native threads,
     * or an error message if capture fails.
     */
    public static String fetch() {
        try {
            long bufSize = (long) SIZE_HINT.invokeExact();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(bufSize);
                long result = (long) NATIVE_HOT_THREADS.invokeExact(buf, bufSize);

                if (result < 0) {
                    String error = NativeLibraryLoader.readErrorMessage(result);
                    logger.warn("native_hot_threads returned error: {}", error);
                    return "Error capturing native threads: " + error;
                }

                if (result == 0) {
                    return "No native threads captured.";
                }

                return buf.getString(0, StandardCharsets.UTF_8).substring(0, (int) result);
            }
        } catch (Throwable t) {
            logger.warn("Error fetching native hot threads", t);
            return "Error fetching native hot threads: " + t.getMessage();
        }
    }
}
