package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * A genuine, depth-tested OpenGL ES three-dimensional vehicle mesh.
 * Geometry is generated from 3D body, roof, windows, lamps and rotating-axis
 * cylindrical wheels. Not an upscaled 2D arrow, PNG, or simulated screenshot.
 *
 * The MapLibre location puck still drives the actual map-matched camera; the
 * 3D mesh overlays that camera's lower-center anchor only in FOLLOW mode.
 * Native overlay position requires verification on diverse Android GPU drivers.
 */
internal class Real3DVehicleSurface(context: Context) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(CarMeshRenderer())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}

@Composable
internal fun BoxWithConstraintsScope.Real3DCarAtTrackingAnchor(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val context = LocalContext.current
    val view = remember(context) { Real3DVehicleSurface(context) }
    DisposableEffect(view) {
        view.onResume()
        view.requestRender()
        onDispose { view.onPause() }
    }
    // Follow-mode MapLibre uses 44% top padding, positioning its camera puck
    // around 72% down the screen; stay aligned with the native follow anchor.
    val top = maxHeight * .72f - 59.dp
    AndroidView(
        factory = { view },
        modifier = modifier.offset(y = top).size(width = 118.dp, height = 118.dp)
    )
}

private class CarMeshRenderer : GLSurfaceView.Renderer {
    private val mesh: FloatArray = VehicleMesh.create()
    private val vertexBuffer = ByteBuffer.allocateDirect(mesh.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(mesh); position(0)
        }
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val temp = FloatArray(16)
    private val mvp = FloatArray(16)
    private var program = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vertex = """
            attribute vec3 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            uniform mat4 uMvp;
            void main() {
                vColor = aColor;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """.trimIndent()
        val fragment = """
            precision mediump float;
            varying vec3 vColor;
            void main() { gl_FragColor = vec4(vColor, 1.0); }
        """.trimIndent()
        fun shader(kind: Int, code: String): Int {
            val id = GLES20.glCreateShader(kind)
            GLES20.glShaderSource(id, code)
            GLES20.glCompileShader(id)
            return id
        }
        val vert = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val frag = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vert)
        GLES20.glAttachShader(program, frag)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vert)
        GLES20.glDeleteShader(frag)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        Matrix.perspectiveM(projection, 0, 46f, width.toFloat() / height.coerceAtLeast(1), .1f, 18f)
        Matrix.setLookAtM(view, 0, 1.55f, -3.8f, 2.8f,
            0f, .05f, .42f, 0f, 0f, 1f)
        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(temp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, temp, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"),
            1, false, mvp, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val color = GLES20.glGetAttribLocation(program, "aColor")
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT,
            false, 6 * 4, vertexBuffer)
        vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(color)
        GLES20.glVertexAttribPointer(color, 3, GLES20.GL_FLOAT,
            false, 6 * 4, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.size / 6)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(color)
    }
}

/** Original lightweight 3D car geometry; not from Sygic assets. */
internal object VehicleMesh {
    fun create(): FloatArray {
        val values = ArrayList<Float>(5000)
        fun vertex(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float) {
            values.add(x); values.add(y); values.add(z)
            values.add(r); values.add(g); values.add(b)
        }
        fun quad(p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray, rgb: FloatArray) {
            for (point in listOf(p0,p1,p2,p0,p2,p3)) {
                vertex(point[0], point[1], point[2], rgb[0],rgb[1],rgb[2])
            }
        }
        fun color(r: Float, g: Float, b: Float, factor: Float = 1f) =
            floatArrayOf(r*factor,g*factor,b*factor)

        fun box(x0: Float, x1: Float, y0: Float, y1: Float,
                z0: Float, z1: Float, paint: FloatArray) {
            val a=floatArrayOf(x0,y0,z0); val b=floatArrayOf(x1,y0,z0)
            val c=floatArrayOf(x1,y1,z0); val d=floatArrayOf(x0,y1,z0)
            val e=floatArrayOf(x0,y0,z1); val f=floatArrayOf(x1,y0,z1)
            val g=floatArrayOf(x1,y1,z1); val h=floatArrayOf(x0,y1,z1)
            quad(a,d,c,b,color(paint[0],paint[1],paint[2],.54f))
            quad(e,f,g,h,paint)
            quad(a,b,f,e,color(paint[0],paint[1],paint[2],.64f))
            quad(d,h,g,c,color(paint[0],paint[1],paint[2],.86f))
            quad(a,e,h,d,color(paint[0],paint[1],paint[2],.72f))
            quad(b,c,g,f,color(paint[0],paint[1],paint[2],.82f))
        }

        // Metal chassis and roof with visible 3D bonnet, boot and windows.
        val body = color(.89f, .65f, .13f)
        box(-.47f,.47f,-.91f,.88f,.21f,.50f,body)
        box(-.42f,.42f,-.34f,.45f,.49f,.84f,color(.83f,.61f,.14f))
        box(-.39f,.39f,-.26f,.40f,.84f,.89f,color(.98f,.83f,.36f))
        // Rear window and front windscreen. Side window inserts are recessed dark panels.
        box(-.34f,.34f,-.364f,-.338f,.52f,.79f,color(.10f,.20f,.28f))
        box(-.34f,.34f,.451f,.474f,.53f,.78f,color(.11f,.20f,.28f))
        box(-.43f,-.409f,-.20f,.32f,.57f,.80f,color(.11f,.21f,.27f))
        box(.409f,.43f,-.20f,.32f,.57f,.80f,color(.11f,.21f,.27f))
        // Tail lamps, headlights and dark bumpers.
        box(-.42f,-.27f,-.938f,-.905f,.32f,.47f,color(.94f,.06f,.065f))
        box(.27f,.42f,-.938f,-.905f,.32f,.47f,color(.94f,.06f,.065f))
        box(-.44f,.44f,-.94f,-.92f,.20f,.29f,color(.17f,.19f,.21f))
        box(-.41f,-.24f,.879f,.91f,.33f,.47f,color(.92f,.95f,.85f))
        box(.24f,.41f,.879f,.91f,.33f,.47f,color(.92f,.95f,.85f))
        box(-.41f,.41f,.90f,.92f,.23f,.32f,color(.22f,.25f,.27f))
        // 4 real extruded 14-sided wheel cylinders about the lateral axle.
        fun wheel(x0: Float, x1: Float, y: Float, z: Float) {
            val radius = .19f
            val segments = 14
            for (j in 0 until segments) {
                val a = 2.0 * Math.PI * j / segments
                val b = 2.0 * Math.PI * (j + 1) / segments
                val y0 = y + radius * cos(a).toFloat()
                val z0 = z + radius * sin(a).toFloat()
                val y1 = y + radius * cos(b).toFloat()
                val z1 = z + radius * sin(b).toFloat()
                quad(floatArrayOf(x0,y0,z0),floatArrayOf(x1,y0,z0),
                    floatArrayOf(x1,y1,z1),floatArrayOf(x0,y1,z1),
                    color(.10f,.105f,.11f))
                val outer = if (x0 < 0) x0 else x1
                for (coords in listOf(
                    floatArrayOf(outer,y,z),
                    floatArrayOf(outer,y0,z0),
                    floatArrayOf(outer,y1,z1))) {
                    vertex(coords[0],coords[1],coords[2],.22f,.24f,.26f)
                }
            }
            val hubX = if (x0 < 0) x0 - .005f else x1 + .005f
            box(hubX-.008f,hubX+.008f,y-.067f,y+.067f,z-.067f,z+.067f,
                color(.63f,.66f,.68f))
        }
        for (y in listOf(-.57f,.57f)) {
            wheel(-.57f,-.43f,y,.22f)
            wheel(.43f,.57f,y,.22f)
        }
        return values.toFloatArray()
    }
}
