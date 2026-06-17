/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

//! Native hot threads capture via eu-stack.
//!
//! Provides an FFI function that captures stack traces of all native (non-JVM)
//! threads in the current process using `eu-stack`, filters for Rust/Tokio/Rayon
//! frames, and writes the result as UTF-8 text to a caller-provided buffer.

use crate::error::{ffm_wrap, into_error_ptr};
use std::process::Command;

/// FFI: Captures native thread stacks and writes them to the provided buffer.
///
/// # Arguments
/// * `out_ptr` - pointer to output buffer
/// * `out_cap` - capacity of the output buffer in bytes
///
/// # Returns
/// * `>= 0` - number of bytes written to the buffer
/// * `< 0`  - negated pointer to a heap-allocated error string
///
/// The output is UTF-8 text with thread stacks separated by newlines.
/// If the output exceeds `out_cap`, it is truncated to fit.
#[no_mangle]
pub unsafe extern "C" fn native_hot_threads(out_ptr: *mut u8, out_cap: i64) -> i64 {
    ffm_wrap("native_hot_threads", || {
        if out_ptr.is_null() {
            return Err("null output pointer".to_string());
        }
        if out_cap <= 0 {
            return Err(format!("invalid output capacity: {}", out_cap));
        }

        let pid = std::process::id();
        let output = capture_native_threads(pid)?;

        let bytes = output.as_bytes();
        let cap = out_cap as usize;
        let to_write = bytes.len().min(cap);

        std::ptr::copy_nonoverlapping(bytes.as_ptr(), out_ptr, to_write);

        Ok(to_write as i64)
    })
}

/// FFI: Returns the estimated output size for native hot threads (for buffer pre-allocation).
#[no_mangle]
pub extern "C" fn native_hot_threads_size_hint() -> i64 {
    // Typical eu-stack output for OpenSearch with ~50 native threads is 50-200KB
    256 * 1024
}

fn capture_native_threads(pid: u32) -> Result<String, String> {
    let result = Command::new("eu-stack")
        .args(["-l", "-m", "-p", &pid.to_string()])
        .output()
        .map_err(|e| format!("failed to execute eu-stack: {}", e))?;

    if !result.status.success() {
        let stderr = String::from_utf8_lossy(&result.stderr);
        return Err(format!(
            "eu-stack exited with {}: {}",
            result.status,
            stderr.trim()
        ));
    }

    let raw_output = String::from_utf8_lossy(&result.stdout).to_string();

    // Filter to show only native (non-JVM) threads
    Ok(filter_native_threads(&raw_output))
}

/// Filters eu-stack output to keep only native threads (Tokio, Rayon, DataFusion).
/// Removes JVM internal threads (GC, compiler, signal handler) to avoid duplicating
/// what the JVM hot threads API already provides.
fn filter_native_threads(raw: &str) -> String {
    let mut result = String::with_capacity(raw.len());
    let mut current_thread = String::new();
    let mut is_native = false;

    for line in raw.lines() {
        if line.starts_with("TID ") || line.starts_with("Thread ") || line.starts_with("PID ") {
            // Flush previous thread if it was native
            if is_native && !current_thread.is_empty() {
                result.push_str(&current_thread);
                result.push('\n');
            }
            current_thread.clear();
            current_thread.push_str(line);
            current_thread.push('\n');
            // Assume native until we see JVM-only indicators
            is_native = true;
        } else if line.contains("libjvm.so")
            || line.contains("JavaThread")
            || line.contains("GC Thread")
            || line.contains("VM Thread")
            || line.contains("C1 CompilerThread")
            || line.contains("C2 CompilerThread")
            || line.contains("Signal Dispatcher")
            || line.contains("Finalizer")
            || line.contains("Reference Handler")
        {
            is_native = false;
            current_thread.push_str(line);
            current_thread.push('\n');
        } else {
            current_thread.push_str(line);
            current_thread.push('\n');
        }
    }

    // Flush last thread
    if is_native && !current_thread.is_empty() {
        result.push_str(&current_thread);
    }

    if result.is_empty() {
        result.push_str("No native threads captured (eu-stack may not be available or process has no native threads)\n");
    }

    result
}
