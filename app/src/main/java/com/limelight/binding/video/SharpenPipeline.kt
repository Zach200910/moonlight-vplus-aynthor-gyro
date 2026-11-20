package com.limelight.binding.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.limelight.LimeLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Client-side sharpening (Moonlight DS). The decoder renders into [inputSurface] (a
 * SurfaceTexture); every frame is drawn to [output] through a contrast-adaptive sharpening
 * shader (CAS-style: sharpens detail, limits ringing on edges) on a dedicated GL thread.
 *
 * SDR only — the caller must not use this for HDR streams. If anything fails during set-up,
 * [create] returns null and the caller keeps rendering straight to the output surface.
 */
class SharpenPipeline private constructor(
    private val output: Surface,
    private val videoWidth: Int,
    private val videoHeight: Int,
    strength: Float
) {
    @Volatile
    var strength: Float = strength.coerceIn(0f, 1f)

    lateinit var inputSurface: Surface
        private set

    private val thread = HandlerThread("SharpenPipeline").apply { start() }
    private val handler = Handler(thread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceTexture: SurfaceTexture? = null
    private var program = 0
    private var textureId = 0
    private var aPosition = 0
    private var uTexMatrix = 0
    private var uTexel = 0
    private var uSharpness = 0
    private val texMatrix = FloatArray(16)
    @Volatile
    private var released = false

    val outputSurface: Surface get() = output

    companion object {
        /** Builds the pipeline on its GL thread; null if EGL/GLES set-up fails. */
        fun create(output: Surface, videoWidth: Int, videoHeight: Int, strength: Float): SharpenPipeline? {
            if (videoWidth <= 0 || videoHeight <= 0 || !output.isValid) return null
            val pipeline = SharpenPipeline(output, videoWidth, videoHeight, strength)
            val ready = CountDownLatch(1)
            var ok = false
            pipeline.handler.post {
                ok = try {
                    pipeline.initGl()
                    true
                } catch (e: RuntimeException) {
                    LimeLog.warning("Sharpening unavailable: ${e.message}")
                    pipeline.releaseGl()
                    false
                }
                ready.countDown()
            }
            val finished = try {
                ready.await(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!finished || !ok) {
                pipeline.release()
                return null
            }
            LimeLog.info("Sharpening active: ${videoWidth}x$videoHeight, strength=${pipeline.strength}")
            return pipeline
        }

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                vec4 uv = vec4((aPosition + 1.0) * 0.5, 0.0, 1.0);
                vUv = (uTexMatrix * uv).xy;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """

        // Contrast-adaptive sharpening (after AMD FidelityFX CAS, simplified to a 5-tap cross).
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform vec2 uTexel;
            uniform float uSharpness;
            varying vec2 vUv;
            void main() {
                vec3 b = texture2D(uTex, vUv + vec2(0.0, -uTexel.y)).rgb;
                vec3 d = texture2D(uTex, vUv + vec2(-uTexel.x, 0.0)).rgb;
                vec3 e = texture2D(uTex, vUv).rgb;
                vec3 f = texture2D(uTex, vUv + vec2(uTexel.x, 0.0)).rgb;
                vec3 h = texture2D(uTex, vUv + vec2(0.0, uTexel.y)).rgb;
                vec3 mn = min(min(min(d, e), min(f, b)), h);
                vec3 mx = max(max(max(d, e), max(f, b)), h);
                vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0));
                vec3 w = amp * (-1.0 / mix(8.0, 4.5, uSharpness));
                vec3 c = (b * w + d * w + f * w + h * w + e) / (1.0 + 4.0 * w);
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """

        private val QUAD: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
                position(0)
            }
    }

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no EGL config"
        }
        val config = configs[0]!!
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
        // Present as soon as a frame is ready; the decoder already paces frames.
        EGL14.eglSwapInterval(eglDisplay, 0)

        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexel = GLES20.glGetUniformLocation(program, "uTexel")
        uSharpness = GLES20.glGetUniformLocation(program, "uSharpness")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(videoWidth, videoHeight)
        st.setOnFrameAvailableListener({ drawFrame() }, handler)
        surfaceTexture = st
        inputSurface = Surface(st)
    }

    private fun drawFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            val size = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, size, 0)
            val w = size[0]
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, size, 0)
            GLES20.glViewport(0, 0, w, size[0])

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
            GLES20.glUniform2f(uTexel, 1f / videoWidth, 1f / videoHeight)
            GLES20.glUniform1f(uSharpness, strength)
            GLES20.glEnableVertexAttribArray(aPosition)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosition)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } catch (e: RuntimeException) {
            LimeLog.warning("Sharpening frame failed: $e")
        }
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("shader compile failed: $log")
            }
            return shader
        }
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw IllegalStateException("program link failed: $log")
        }
        return prog
    }

    private fun releaseGl() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        if (::inputSurface.isInitialized) inputSurface.release()
        surfaceTexture?.release()
        surfaceTexture = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            // No eglTerminate: the default display is shared with other GL users in the process.
            EGL14.eglReleaseThread()
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        program = 0
        textureId = 0
    }

    /** Tears down GL on its own thread and stops it. Safe to call more than once. */
    fun release() {
        if (released) return
        released = true
        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                releaseGl()
            } catch (e: RuntimeException) {
                LimeLog.warning("Sharpening release failed: $e")
            } finally {
                done.countDown()
            }
        }
        if (posted) {
            try {
                done.await(1, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread.quitSafely()
    }
}
