import bdv.cache.CacheControl;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerState;
import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.RandomAccessibleInterval;

import bvvpg.core.multires.SourceStacks;
import bvvpg.core.multires.Stack3D;
import bvvpg.core.offscreen.OffScreenFrameBufferWithDepth;
import bvvpg.core.render.RenderData;
import bvvpg.core.render.VolumeRenderer;
import bvvpg.core.render.VolumeRenderer.RepaintType;
import bvvpg.core.util.MatrixMath;
import bvvpg.source.converters.ConverterSetupsPG;

import com.jogamp.opengl.*;
import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
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
    private final int maxCacheMB = 2048;        // VRAM-side cache budget
    private final int maxRenderMillis = 20;     // per pass budget (refinement)
    private final double maxAllowedStepInVoxels = 1.5; // ray step clamp

    // camera
    private final double dCam = 2000.0;         // distance of the virtual camera
    private final double clipNear = 0.01;
    private final double clipFar  = 10000.0;
    private final int projectionType = 0;       // 0 = perspective, 1 = ortho

    public BvvRotateMovie(ViewerState state, ConverterSetupsPG setups,
                          CacheControl cache,
                          int renderWidth, int renderHeight) {
        this.state = state;
        this.setups = setups;
        this.cache = cache;
        this.renderW = renderWidth;
        this.renderH = renderHeight;
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


// 1. Define the center of our screen (the pivot point for rotation).
        final double cX = renderW / 2.0;
        final double cY = renderH / 2.0;

// 2. Calculate the object's center in world coordinates to create an initial view.
        final SourceAndConverter<?> sac0 = state.getSources().get(0);
        final int t = 0, level = 0;
        final RandomAccessibleInterval<?> rai = sac0.getSpimSource().getSource(t, level);
        final long sx = rai.dimension(0), sy = rai.dimension(1), sz = rai.dimension(2);
        final double[] voxelCenter = { (sx - 1) / 2.0, (sy - 1) / 2.0, (sz - 1) / 2.0 };
        final AffineTransform3D s2g = new AffineTransform3D();
        sac0.getSpimSource().getSourceTransform(t, level, s2g);
        final double[] pivot = new double[3];
        s2g.apply(voxelCenter, pivot);

// 3. Create the initial transform that centers the object. This is our starting point.
        final AffineTransform3D initialTransform = new AffineTransform3D();
        initialTransform.translate(-pivot[0], -pivot[1], -pivot[2]);


// 4. Loop through frames, applying the correct rotation logic each time.
        for (int i = 0; i < frames; i++) {
            // Calculate the TOTAL angle for the current frame.
            final double totalAngle = (2.0 * Math.PI * i) / frames;
            final AffineTransform3D rotationTransform = new AffineTransform3D();
            rotationTransform.rotate(1, totalAngle); // Rotation around the Y-axis

            // Start with the initial object-centered view.
            AffineTransform3D finalTransform = initialTransform.copy();

            // === REPLICATE THE RotationAnimator LOGIC ===
            // a) Translate the view so the screen center (cX, cY) is at the origin.
            finalTransform.set(finalTransform.get(0, 3) - cX, 0, 3);
            finalTransform.set(finalTransform.get(1, 3) - cY, 1, 3);

            // b) Apply the rotation. preConcatenate multiplies on the right (T_new = T_old * R).
            finalTransform.preConcatenate(rotationTransform);

            // c) Translate the view back.
            finalTransform.set(finalTransform.get(0, 3) + cX, 0, 3);
            finalTransform.set(finalTransform.get(1, 3) + cY, 1, 3);
            // === END OF RotationAnimator LOGIC ===

            // Set the final transform for this frame.
            state.setViewerTransform(finalTransform);
            state.setCurrentTimepoint(t);

            // --- The rest of your rendering loop remains the same ---
            loop.prepareFrame(state, projectionType, dCam, clipNear, clipFar, renderW, renderH, maxRenderMillis, maxAllowedStepInVoxels, cache);
            do {
                drawable.display();
            } while (loop.lastRerender != RepaintType.NONE);

            // Read back and save
            final BufferedImage bi = loop.lastFrame;
            if (bi != null) {
                final File f = new File(outDir, String.format("bvv_%04d.png", i));
                ImageIO.write(bi, "png", f);
                System.out.println("Saved frame " + i);
            }
        }

        drawable.destroy();
    }

    // ---------- helpers ----------

    private static AffineTransform3D translate(double tx, double ty, double tz) {
        AffineTransform3D M = new AffineTransform3D();
        M.set(
                1,0,0,tx,
                0,1,0,ty,
                0,0,1,tz
        );
        return M;
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
        private RenderData renderData;
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
            stacks = st.getVisibleAndPresentSources().stream().map(sac -> {
                SourceAndConverter<?> s = sac;
//                if (s.asVolatile() != null) s = s.asVolatile();
                return SourceStacks.getStack3D(s.getSpimSource(), t);
            }).collect(Collectors.toList());

            converters = st.getVisibleAndPresentSources().stream()
                    .map(setups::getConverterSetup)
                    .collect(Collectors.toList());

            renderData = new RenderData(pv, t, st.getViewerTransform(), dCam, clipNear, clipFar, screenW, screenH);
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
