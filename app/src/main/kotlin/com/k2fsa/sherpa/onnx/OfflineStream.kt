// sherpa-onnx/kotlin-api/OfflineStream.kt from https://github.com/k2-fsa/sherpa-onnx v2.0.1
//
// Copyright (c)  2022-2026  Xiaomi Corporation and sherpa-onnx contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.k2fsa.sherpa.onnx

class OfflineStream(var ptr: Long) {
    init {
        require(ptr != 0L) { "Failed to create native OfflineStream" }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    fun setOption(key: String, value: String) = setOption(ptr, key, value)

    fun getOption(key: String): String = getOption(ptr, key)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OfflineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun setOption(ptr: Long, key: String, value: String)
    private external fun getOption(ptr: Long, key: String): String
    private external fun delete(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
