import bdv.cache.CacheControl;
import bdv.tools.brightness.ConverterSetup;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerState;
import bvvpg.core.multires.SourceStacks;
import bvvpg.core.multires.Stack3D;
import bvvpg.core.offscreen.OffScreenFrameBufferWithDepth;
import bvvpg.core.render.VolumeRenderer;
import bvvpg.core.render.VolumeRenderer.RepaintType;
import bvvpg.core.util.MatrixMath;
import bvvpg.source.converters.ConverterSetupsPG;
import com.jogamp.opengl.*;
import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.LinAlgHelpers;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Headless BVV movie recorder using JOGL OffscreenAutoDrawable (pbuffer).
 * No window, no Robot, no Xvfb.
 */
public class BvvRotateMovie {

    private final ConverterSetupsPG setups;
    private final ViewerState state;
    private CacheControl cache = new CacheControl.Dummy();

    // render opts
    private final int renderW, renderH;
    private final int ditherWidth = 0;          // use 0 for deterministic frames
    private final int numDitherSamples = 1;     // 1 = no stochastic sampling
    private final int[] cacheBlockSize = new int[]{64, 64, 64};      // tune if you know your setup
    private final int maxCacheMB = 1048;        // VRAM-side cache budget
    private final int maxRenderMillis = 20;     // per pass budget (refinement)
    private final double maxAllowedStepInVoxels = 1.5; // ray step clamp

    // camera
    private final double dCam = 3000.0;         // distance of the virtual camera
    private final double clipNear = 1;
    private final double clipFar  = 10000.0;
    private final int projectionType = 1;       // 0 = perspective, 1 = ortho

    public BvvRotateMovie(ViewerState state, ConverterSetupsPG setups,
                          CacheControl cache,
                          int renderWidth, int renderHeight) {
        this.state = state;
        this.setups = setups;
        this.cache = cache;
        this.renderW = renderWidth;
        this.renderH = renderHeight;
    }

    static void scaleAboutOrigin(ViewerState state, double s) {
        final AffineTransform3D T = state.getViewerTransform().copy();
        final AffineTransform3D S = new AffineTransform3D();
        S.scale(s);
        // OBJECT-SPACE op: RIGHT-multiply (concatenate)
        final AffineTransform3D out = T.copy();
        out.preConcatenate(S);   // <-- was preConcatenate(S)
        state.setViewerTransform(out);
    }

    static void fitByScalingAboutOrigin(
            ViewerState state,
            double[] globalMin, double[] globalMax,
            int screenW, int screenH,
            int projectionType, double dCam, double clipNear, double clipFar,
            double paddingFrac /* e.g. 0.85 */)
    {
        for (int iter = 0; iter < 8; iter++) {
            final Matrix4f view = bvvpg.core.util.MatrixMath.affine(state.getViewerTransform(), new Matrix4f());
            final Matrix4f proj = bvvpg.core.util.MatrixMath.screenPerspective(
                    projectionType, dCam, clipNear, clipFar, screenW, screenH, 0, new Matrix4f());
            final Matrix4f PV = new Matrix4f(proj).mul(view);

            double minX = +1e9, minY = +1e9, maxX = -1e9, maxY = -1e9;
            for (int k = 0; k < 8; k++) {
                final double x = ((k & 1) == 0) ? globalMin[0] : globalMax[0];
                final double y = ((k & 2) == 0) ? globalMin[1] : globalMax[1];
                final double z = ((k & 4) == 0) ? globalMin[2] : globalMax[2];

                Vector4f p = new Vector4f((float)x, (float)y, (float)z, 1f).mul(PV);
                if (p.w <= 0) continue;
                float ndcX = p.x / p.w, ndcY = p.y / p.w;
                float sx = (ndcX * 0.5f + 0.5f) * screenW;
                float sy = (1f - (ndcY * 0.5f + 0.5f)) * screenH;
                minX = Math.min(minX, sx); maxX = Math.max(maxX, sx);
                minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
            }

            double boxW = Math.max(1.0, maxX - minX);
            double boxH = Math.max(1.0, maxY - minY);
            double current = Math.max(boxW, boxH);
            double target  = paddingFrac * Math.min(screenW, screenH);

            if (Math.abs(current - target) / target < 0.02) break; // within 2%

            // Scale so that current → target (clamp step to keep stable)
            double s = target / current;                 // >1: grow, <1: shrink
            s = Math.max(0.5, Math.min(2.0, s));        // avoid wild jumps
            scaleAboutOrigin(state, s);
        }
    }

    static void centerPivotOnScreen(
            ViewerState state,
            double[] pivot,
            int screenW, int screenH,
            int projectionType, double dCam, double clipNear, double clipFar)
    {
        // PV maps to *screen pixels*
        final org.joml.Matrix4f view = bvvpg.core.util.MatrixMath.affine(
                state.getViewerTransform(), new org.joml.Matrix4f());
        final org.joml.Matrix4f proj = bvvpg.core.util.MatrixMath.screenPerspective(
                projectionType, dCam, clipNear, clipFar, screenW, screenH, 0, new org.joml.Matrix4f());
        final org.joml.Matrix4f PV = new org.joml.Matrix4f(proj).mul(view);

        // Project pivot -> screen (already in pixels)
        final org.joml.Vector4f ph = new org.joml.Vector4f(
                (float)pivot[0], (float)pivot[1], (float)pivot[2], 1f).mul(PV);
        if (ph.w == 0f) return;

        final double sx = ph.x / ph.w;   // pixels
        final double sy = ph.y / ph.w;   // pixels

        // Desired center in pixels
        final double cx = 0.5 * screenW;
        final double cy = 0.5 * screenH;

        // Pixel delta to move pivot to center
        final double dx = cx - sx;
        final double dy = cy - sy;

        // Viewer-space translation: LEFT-multiply
        final net.imglib2.realtransform.AffineTransform3D T = state.getViewerTransform().copy();
        final net.imglib2.realtransform.AffineTransform3D V = new net.imglib2.realtransform.AffineTransform3D();
        V.translate(dx, dy, 0.0);
        T.preConcatenate(V);
        state.setViewerTransform(T);
    }

    static void keepAABBInsideDepth(ViewerState state,
                                    double[] gmin, double[] gmax,
                                    double clipNear, double clipFar) {
        final AffineTransform3D T = state.getViewerTransform().copy();

        double zmin = 1e300, zmax = -1e300;
        for (int k = 0; k < 8; k++) {
            final double x = ((k & 1) == 0) ? gmin[0] : gmax[0];
            final double y = ((k & 2) == 0) ? gmin[1] : gmax[1];
            final double z = ((k & 4) == 0) ? gmin[2] : gmax[2];
            final double[] v = new double[3];
            T.apply(new double[]{x,y,z}, v);   // world -> viewer
            zmin = Math.min(zmin, v[2]);
            zmax = Math.max(zmax, v[2]);
        }

        // target center depth midway between near/far (any point well inside is fine)
        final double zTarget = 0.5 * (clipNear + clipFar);
        final double zCenter = 0.5 * (zmin + zmax);
        final double dz = zTarget - zCenter;

        if (Math.abs(dz) > 1e-6) {
            final AffineTransform3D Vz = new AffineTransform3D();
            Vz.translate(0, 0, dz);
            T.preConcatenate(Vz);              // viewer-space translate
            state.setViewerTransform(T);
        }
    }

    /** Main entry: render a 360° spin (around global Y) to PNG frames. */
    public void recordRotateMovie(int frames, File outDir) throws Exception {
        if (!outDir.isDirectory()) throw new IllegalArgumentException("outDir must exist: " + outDir);

        // 2) JOGL offscreen pbuffer (no window)
        final GLProfile profile = GLProfile.get(GLProfile.GL3);
        final GLCapabilities caps = new GLCapabilities(profile);
        caps.setOnscreen(false);
        caps.setPBuffer(true);
        caps.setDoubleBuffered(false);
        caps.setDoubleBuffered(false);
        final GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
        final GLAutoDrawable drawable = factory.createOffscreenAutoDrawable(
                null, caps, null, renderW, renderH);

        // 3) BVV renderer + FBOs
        final VolumeRenderer renderer = new VolumeRenderer(
                renderW, renderH,
                ditherWidth, getDitherStep(ditherWidth),
                numDitherSamples,
                cacheBlockSize, maxCacheMB
        );

        final OffScreenFrameBufferWithDepth offscreen = new OffScreenFrameBufferWithDepth(renderW, renderH, GL.GL_RGBA8);

        final AWTGLReadBufferUtil reader = new AWTGLReadBufferUtil(profile, true);

        // 5) Render loop
        final RenderLoop loop = new RenderLoop(renderer, offscreen, reader, setups);

        drawable.addGLEventListener(loop);
//        drawable.display(); // triggers init()
// 1. Calculate a single bounding box that encloses ALL visible sources.
        final List<SourceAndConverter<?>> sources = state.getSources();
        if (sources.isEmpty()) {
            throw new IllegalStateException("No sources found in ViewerState. Cannot render.");
        }

        final double[] globalMin = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
        final double[] globalMax = { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };

        final AffineTransform3D sourceToGlobal = new AffineTransform3D();
        final int t = 0, level = 0;

        for (final SourceAndConverter<?> sac : sources) {
            if (!state.isSourceVisible(sac)) continue; // Skip invisible sources

            final RandomAccessibleInterval<?> rai = sac.getSpimSource().getSource(t, level);
            final long sx = rai.dimension(0);
            final long sy = rai.dimension(1);
            final long sz = rai.dimension(2);

            sac.getSpimSource().getSourceTransform(t, level, sourceToGlobal);

            // Calculate the corners of this source's bounding box in world coordinates
            final double[] p0 = new double[3];
            final double[] p1 = new double[3];
            sourceToGlobal.apply(new double[]{0, 0, 0}, p0);
            sourceToGlobal.apply(new double[]{sx - 1, sy - 1, sz - 1}, p1);

            // Update the global min/max corners
            for (int d = 0; d < 3; ++d) {
                globalMin[d] = Math.min(globalMin[d], Math.min(p0[d], p1[d]));
                globalMax[d] = Math.max(globalMax[d], Math.max(p0[d], p1[d]));
            }
        }

// 2. Calculate pivot and size
        final double[] pivot = {
                (globalMin[0] + globalMax[0]) * 0.5,
                (globalMin[1] + globalMax[1]) * 0.5,
                (globalMin[2] + globalMax[2]) * 0.5
        };
        final double diagonal = LinAlgHelpers.distance(globalMin, globalMax);
        final double radius   = 0.5 * diagonal;
// --- Build a clean base transform once ---
// 1) center pivot at the origin (viewer space)
// 2) push scene to +Z so it's in front of the camera
// base: center pivot at origin, small push to +Z
        final AffineTransform3D base = new AffineTransform3D();
        base.translate(-pivot[0], -pivot[1], -pivot[2]);
        base.rotate(2, 2*Math.PI/4);
        base.rotate(0, 2*Math.PI/4*3);
//        base.translate(0, 0, +1.0 * radius);
        state.setViewerTransform(base);

// Fit size by uniform scale about origin (your current ortho fit)
        fitByScalingAboutOrigin(state, globalMin, globalMax, renderW, renderH,
                projectionType, dCam, clipNear, clipFar, 1);

// Now center the pivot to the screen center (one time)
        centerPivotOnScreen(state, pivot, renderW, renderH, projectionType, dCam, clipNear, clipFar);
        keepAABBInsideDepth(state, globalMin, globalMax, clipNear, clipFar);

        System.out.println("globalMin=" + Arrays.toString(globalMin));
        System.out.println("globalMax=" + Arrays.toString(globalMax));
        System.out.println("pivot=" + Arrays.toString(pivot) + "  radius=" + radius);

// 5. Loop through frames, using the same rendering logic as before.
        for (int i = 0; i < frames; i++) {
            double angle = 2.0 * Math.PI * i / frames;

            AffineTransform3D R = new AffineTransform3D();
            AffineTransform3D finalT = base.copy();
            R.rotate(1, angle);                    // spin around Y            AffineTransform3D finalT = base.copy();
            finalT.preConcatenate(R);   // object-space rotation around pivot
            state.setViewerTransform(finalT);

// Fit size by uniform scale about origin (your current ortho fit)
            fitByScalingAboutOrigin(state, globalMin, globalMax, renderW, renderH,
                    projectionType, dCam, clipNear, clipFar, 1);

// Now center the pivot to the screen center (one time)
            centerPivotOnScreen(state, pivot, renderW, renderH, projectionType, dCam, clipNear, clipFar);
            keepAABBInsideDepth(state, globalMin, globalMax, clipNear, clipFar);

            state.setCurrentTimepoint(t);
            loop.prepareFrame(state, projectionType, dCam, clipNear, clipFar,
                    renderW, renderH, maxRenderMillis, maxAllowedStepInVoxels, cache);
            do { drawable.display(); } while (loop.lastRerender != RepaintType.NONE);
            if (loop.lastFrame != null) {
                ImageIO.write(loop.lastFrame, "png", new File(outDir, String.format("bvv_%04d.png", i)));
            }
        }
        drawable.destroy();
    }


    private static int getDitherStep(final int ditherWidth) {
        final int[] steps = { 0, 1, 3, 5, 9, 11, 19, 23, 29 };
        if (ditherWidth < 0 || ditherWidth >= steps.length)
            throw new IllegalArgumentException("unsupported dither width");
        return steps[ditherWidth];
    }

    /** The actual GL listener that renders one frame each display() call. */
    private static class RenderLoop implements GLEventListener {
        private final VolumeRenderer renderer;
        private final OffScreenFrameBufferWithDepth offscreen;
        private final AWTGLReadBufferUtil reader;
        private final ConverterSetupsPG setups;

        // per-frame prepared data
        private List<Stack3D<?>> stacks = Collections.emptyList();
        private List<bdv.tools.brightness.ConverterSetup> converters = Collections.emptyList();
        private Matrix4f pv = new Matrix4f();
        private long[] ioBudget = new long[]{100L * 1000000L, 10L * 1000000L};
        private int maxRenderMillis;
        private double maxAllowedStepInVoxels;
        private CacheControl cache;

        BufferedImage lastFrame = null;
        RepaintType lastRerender = RepaintType.NONE;

        RenderLoop(VolumeRenderer renderer,
                   OffScreenFrameBufferWithDepth offscreen,
                   AWTGLReadBufferUtil reader,
                   ConverterSetupsPG setups) {
            this.renderer = renderer;
            this.offscreen = offscreen;
            this.reader = reader;
            this.setups = setups;
        }

        void prepareFrame(ViewerState st,
                          int projectionType,
                          double dCam, double clipNear, double clipFar,
                          double screenW, double screenH,
                          int maxRenderMillis, double maxAllowedStepInVoxels,
                          CacheControl cache) {
            this.maxRenderMillis = maxRenderMillis;
            this.maxAllowedStepInVoxels = maxAllowedStepInVoxels;
            this.cache = cache;

            // view-projection
            final Matrix4f view = bvvpg.core.util.MatrixMath.affine(st.getViewerTransform(), new Matrix4f());
            pv = MatrixMath.screenPerspective(projectionType, dCam, clipNear, clipFar, screenW, screenH, 0, new Matrix4f()).mul(view);

            // stacks + converters for all visible sources at current t
            final int t = st.getCurrentTimepoint();
            Set<SourceAndConverter<?>> visible = st.getVisibleAndPresentSources();

            stacks = visible.stream()
                    .map(sac -> {
                        final SourceAndConverter<?> sKey =
                                (sac.asVolatile() != null) ? sac.asVolatile() : sac; // ✅ choose volatile if present
                        return SourceStacks.getStack3D(sKey.getSpimSource(), t);
                    })
                    .collect(Collectors.toList());

            converters = visible.stream()
                    .map(sac -> {
                        final SourceAndConverter<?> sKey =
                                (sac.asVolatile() != null) ? sac.asVolatile() : sac; // ✅ same key as stacks
                        ConverterSetup css = setups.getConverterSetup(sKey);
                        if (css == null) css = setups.getConverterSetup(sac);          // fallback
                        if (css == null) throw new IllegalStateException("No ConverterSetup for " + sKey);
                        return css;
                    })
                    .collect(Collectors.toList());

        }

        @Override public void init(GLAutoDrawable drawable) {
            renderer.init(drawable.getGL().getGL3());
        }

        @Override
        public void display(GLAutoDrawable drawable) {
            final GL3 gl = drawable.getGL().getGL3();

            // --- Prep ---
            net.imglib2.cache.iotiming.CacheIoTiming.getIoTimeBudget().reset(ioBudget);
            cache.prepareNextFrame();

            // --- 1. RENDER PASS (into the custom FBO) ---
            offscreen.bind(gl, false);
            gl.glEnable(GL.GL_DEPTH_TEST);
            gl.glDepthFunc(GL.GL_LESS);
            gl.glClearColor(0, 0, 0, 0);
            gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT); // This clears the FBO.
            lastRerender = renderer.draw(gl, RepaintType.FULL, offscreen, stacks, converters, pv, maxRenderMillis, maxAllowedStepInVoxels);

            // --- 2. FINAL COPY (from FBO to the default buffer) ---
            offscreen.unbind(gl, false); // Pbuffer is now the active target.

            // ======================= INSERT THIS LINE =======================
            gl.glClear(GL.GL_COLOR_BUFFER_BIT); // This clears the Pbuffer before drawing.
            // ================================================================

            offscreen.drawQuad(gl); // Draw the final image onto the clean Pbuffer.

            // --- 3. READBACK (from the default buffer) ---
            gl.glReadBuffer(GL.GL_FRONT);
            gl.glFinish();
            lastFrame = reader.readPixelsToBufferedImage(gl, /*flipVertically*/ true);
        }

        @Override public void reshape(GLAutoDrawable d, int x, int y, int w, int h) {}
        @Override public void dispose(GLAutoDrawable d) {}
    }

}
