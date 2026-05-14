/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.tools.cli.heapprof;

import org.opensearch.cli.Command;
import org.opensearch.cli.Terminal;
import org.opensearch.common.cli.CommandLoggingConfigurator;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI tool for native (jemalloc) heap profiling on a running OpenSearch node.
 * <p>
 * Connects to the heap profiling Unix domain socket at {@code <data_dir>/heap-prof.sock}
 * and sends commands to control profiling. This is the native equivalent of {@code jcmd}
 * for heap profiling.
 * <p>
 * Usage:
 * <pre>
 *   bin/opensearch-heap-prof activate [--data-dir /path/to/data]
 *   bin/opensearch-heap-prof deactivate [--data-dir /path/to/data]
 *   bin/opensearch-heap-prof dump --path /tmp/heap.prof [--data-dir /path/to/data]
 *   bin/opensearch-heap-prof reset --lg-sample 15 [--data-dir /path/to/data]
 *   bin/opensearch-heap-prof status [--data-dir /path/to/data]
 * </pre>
 */
public class HeapProfCli {

    public static void main(String[] args) throws Exception {
        CommandLoggingConfigurator.configureLoggingWithoutConfig();

        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String action = args[0];
        String dataDir = getOption(args, "--data-dir", findDefaultDataDir());
        String socketPath = dataDir + "/heap-prof.sock";

        // Build the command string to send over UDS
        String command;
        switch (action) {
            case "activate":
                command = "activate";
                break;
            case "deactivate":
                command = "deactivate";
                break;
            case "dump":
                String dumpPath = getOption(args, "--path", "");
                if (dumpPath.isEmpty()) {
                    System.err.println("Error: --path is required for dump command");
                    System.exit(1);
                }
                command = "dump " + dumpPath;
                break;
            case "reset":
                String lgSample = getOption(args, "--lg-sample", "17");
                command = "reset " + lgSample;
                break;
            case "status":
                command = "status";
                break;
            default:
                System.err.println("Unknown action: " + action);
                printUsage();
                System.exit(1);
                return;
        }

        // Connect to UDS and send command
        Path sock = Path.of(socketPath);
        if (!Files.exists(sock)) {
            System.err.println("Error: Socket not found at " + socketPath);
            System.err.println("Is OpenSearch running? Is the data directory correct?");
            System.err.println("Use --data-dir to specify the data directory.");
            System.exit(1);
        }

        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(sock))) {
            // Send command
            channel.write(ByteBuffer.wrap((command + "\n").getBytes(StandardCharsets.UTF_8)));

            // Read response
            ByteBuffer buf = ByteBuffer.allocate(4096);
            channel.read(buf);
            buf.flip();
            String response = StandardCharsets.UTF_8.decode(buf).toString().trim();
            System.out.println(response);

            // Exit with error code if response starts with ERR
            if (response.startsWith("ERR")) {
                System.exit(1);
            }
        } catch (IOException e) {
            System.err.println("Error connecting to " + socketPath + ": " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: opensearch-heap-prof <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  activate                  Enable jemalloc heap profiling");
        System.out.println("  deactivate                Disable jemalloc heap profiling");
        System.out.println("  dump --path <file>        Dump heap profile to file");
        System.out.println("  reset --lg-sample <N>     Reset profiling with new sample interval");
        System.out.println("                            (15=32KB, 17=128KB, 19=512KB, 21=2MB)");
        System.out.println("  status                    Show profiling status");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --data-dir <path>         OpenSearch data directory (default: auto-detect)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  opensearch-heap-prof activate");
        System.out.println("  opensearch-heap-prof dump --path /tmp/before.heap");
        System.out.println("  opensearch-heap-prof dump --path /tmp/after.heap");
        System.out.println("  opensearch-heap-prof deactivate");
    }

    private static String getOption(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static String findDefaultDataDir() {
        // Try common locations
        String[] candidates = {
            System.getProperty("opensearch.path.data", ""),
            System.getenv("OPENSEARCH_PATH_DATA"),
            "./data",
            "/var/lib/opensearch",
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                Path sock = Path.of(candidate, "heap-prof.sock");
                if (Files.exists(sock)) {
                    return candidate;
                }
            }
        }
        // Fallback
        return "./data";
    }
}
