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
import com.jogamp.nativewindow.AbstractGraphicsDevice;
import com.jogamp.nativewindow.x11.X11GraphicsDevice;
import com.jogamp.opengl.*;
import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Headless BVV screenshot recorder using JOGL OffscreenAutoDrawable (pbuffer).
 * Takes a single, auto-framed screenshot of the current ViewerState.
 * No window, no Robot, no Xvfb.
 */
public class BvvSingleScreenshot {

    private final ConverterSetupsPG setups;
    private final ViewerState state;
    private final CacheControl cache;

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

    public BvvSingleScreenshot(ViewerState state, ConverterSetupsPG setups,
                               CacheControl cache,
                               int renderWidth, int renderHeight) {
        this.state = state;
        this.setups = setups;
        this.cache = cache;
        this.renderW = renderWidth;
        this.renderH = renderHeight;
    }

    // Static helper methods for camera manipulation (unchanged)
    static void scaleAboutOrigin(ViewerState state, double s) {
        final AffineTransform3D T = state.getViewerTransform().copy();
        final AffineTransform3D S = new AffineTransform3D();
        S.scale(s);
        final AffineTransform3D out = T.copy();
        out.preConcatenate(S);
        state.setViewerTransform(out);
    }

    static void fitByScalingAboutOrigin(
            ViewerState state,
            double[] globalMin, double[] globalMax,
            int screenW, int screenH,
            int projectionType, double dCam, double clipNear, double clipFar,
            double paddingFrac /* e.g. 0.9 */)
    {
        for (int iter = 0; iter < 8; iter++) {
            final Matrix4f view = MatrixMath.affine(state.getViewerTransform(), new Matrix4f());
            final Matrix4f proj = MatrixMath.screenPerspective(
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

            double s = target / current;
            s = Math.max(0.5, Math.min(2.0, s));
            scaleAboutOrigin(state, s);
        }
    }

    static void centerPivotOnScreen(
            ViewerState state,
            double[] pivot,
            int screenW, int screenH,
            int projectionType, double dCam, double clipNear, double clipFar)
    {
        final Matrix4f view = MatrixMath.affine(
                state.getViewerTransform(), new Matrix4f());
        final Matrix4f proj = MatrixMath.screenPerspective(
                projectionType, dCam, clipNear, clipFar, screenW, screenH, 0, new Matrix4f());
        final Matrix4f PV = new Matrix4f(proj).mul(view);

        final Vector4f ph = new Vector4f(
                (float)pivot[0], (float)pivot[1], (float)pivot[2], 1f).mul(PV);
        if (ph.w == 0f) return;

        final double sx = ph.x / ph.w;
        final double sy = ph.y / ph.w;
        final double cx = 0.5 * screenW;
        final double cy = 0.5 * screenH;
        final double dx = cx - sx;
        final double dy = cy - sy;

        final AffineTransform3D T = state.getViewerTransform().copy();
        final AffineTransform3D V = new AffineTransform3D();
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
            T.apply(new double[]{x,y,z}, v);
            zmin = Math.min(zmin, v[2]);
            zmax = Math.max(zmax, v[2]);
        }

        final double zTarget = 0.5 * (clipNear + clipFar);
        final double zCenter = 0.5 * (zmin + zmax);
        final double dz = zTarget - zCenter;

        if (Math.abs(dz) > 1e-6) {
            final AffineTransform3D Vz = new AffineTransform3D();
            Vz.translate(0, 0, dz);
            T.preConcatenate(Vz);
            state.setViewerTransform(T);
        }
    }

    /** Main entry: render a single, auto-framed screenshot to a PNG file. */
    public void capture(File outputFile) throws Exception {
        if (outputFile.isDirectory()) {
            throw new IllegalArgumentException("outputFile must be a file, not a directory: " + outputFile);
        }
        File parentDir = outputFile.getParentFile();
        if (parentDir != null) {
            parentDir.mkdirs();
        }

        final String displayName = System.getenv().getOrDefault("DISPLAY", ":99");
        final AbstractGraphicsDevice device = new X11GraphicsDevice(displayName, AbstractGraphicsDevice.DEFAULT_UNIT);
        device.open();

        try {
            final GLProfile profile = GLProfile.get(device, GLProfile.GL3);
            final GLCapabilities caps = new GLCapabilities(profile);
            caps.setOnscreen(false);
            caps.setPBuffer(true);
            caps.setDoubleBuffered(false);

            final GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
            final GLAutoDrawable drawable = factory.createOffscreenAutoDrawable(device, caps, null, renderW, renderH);

            final VolumeRenderer renderer = new VolumeRenderer(
                    renderW, renderH,
                    ditherWidth, getDitherStep(ditherWidth),
                    numDitherSamples,
                    cacheBlockSize, maxCacheMB
            );
            final OffScreenFrameBufferWithDepth offscreen = new OffScreenFrameBufferWithDepth(renderW, renderH, GL.GL_RGBA8);
            final AWTGLReadBufferUtil reader = new AWTGLReadBufferUtil(profile, true);
            final RenderLoop loop = new RenderLoop(renderer, offscreen, reader, setups);
            drawable.addGLEventListener(loop);

            // --- AUTO-FRAME THE SCENE ---

            // 1. Calculate a single bounding box that encloses ALL visible sources.
            final List<SourceAndConverter<?>> sources = state.getSources();
            if (sources.isEmpty()) {
                throw new IllegalStateException("No sources found in ViewerState. Cannot render.");
            }

            final double[] globalMin = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
            final double[] globalMax = { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
            final AffineTransform3D sourceToGlobal = new AffineTransform3D();
            final int t = state.getCurrentTimepoint();

            for (final SourceAndConverter<?> sac : sources) {
                if (!state.isSourceVisible(sac)) continue;

                final RandomAccessibleInterval<?> rai = sac.getSpimSource().getSource(t, 0);
                sac.getSpimSource().getSourceTransform(t, 0, sourceToGlobal);

                for (int k = 0; k < 8; ++k) {
                    final double[] corner = {
                            ((k & 1) == 0) ? 0 : rai.dimension(0) - 1,
                            ((k & 2) == 0) ? 0 : rai.dimension(1) - 1,
                            ((k & 4) == 0) ? 0 : rai.dimension(2) - 1
                    };
                    sourceToGlobal.apply(corner, corner);
                    for (int d = 0; d < 3; ++d) {
                        globalMin[d] = Math.min(globalMin[d], corner[d]);
                        globalMax[d] = Math.max(globalMax[d], corner[d]);
                    }
                }
            }

            // 2. Calculate pivot point for centering.
            final double[] pivot = {
                    (globalMin[0] + globalMax[0]) * 0.5,
                    (globalMin[1] + globalMax[1]) * 0.5,
                    (globalMin[2] + globalMax[2]) * 0.5
            };

            // 3. Set and adjust the camera transform to frame the scene.
            // If a specific view is desired, set state.setViewerTransform() BEFORE calling this method.
            // Otherwise, we start with a simple default.
            if (state.getViewerTransform().isIdentity()) {
                final AffineTransform3D base = new AffineTransform3D();
                base.translate(-pivot[0], -pivot[1], -pivot[2]);
                state.setViewerTransform(base);
            }

            fitByScalingAboutOrigin(state, globalMin, globalMax, renderW, renderH,
                    projectionType, dCam, clipNear, clipFar, 0.9); // 90% padding
            centerPivotOnScreen(state, pivot, renderW, renderH, projectionType, dCam, clipNear, clipFar);
            keepAABBInsideDepth(state, globalMin, globalMax, clipNear, clipFar);

            // --- RENDER AND SAVE THE FRAME ---
            loop.prepareFrame(state, projectionType, dCam, clipNear, clipFar,
                    renderW, renderH, maxRenderMillis, maxAllowedStepInVoxels, cache);

            // Loop handles progressive refinement until image is complete.
            do {
                drawable.display();
            } while (loop.lastRerender != RepaintType.NONE);

            if (loop.lastFrame != null) {
                ImageIO.write(loop.lastFrame, "png", outputFile);
                System.out.println("Screenshot saved to: " + outputFile.getAbsolutePath());
            } else {
                System.err.println("Rendering failed, no image was produced.");
            }

            drawable.destroy();
        } finally {
            device.close();
        }
    }


    private static int getDitherStep(final int ditherWidth) {
        final int[] steps = { 0, 1, 3, 5, 9, 11, 19, 23, 29 };
        if (ditherWidth < 0 || ditherWidth >= steps.length)
            throw new IllegalArgumentException("unsupported dither width");
        return steps[ditherWidth];
    }

    /** The actual GL listener that renders one frame each display() call. (Unchanged) */
    private static class RenderLoop implements GLEventListener {
        private final VolumeRenderer renderer;
        private final OffScreenFrameBufferWithDepth offscreen;
        private final AWTGLReadBufferUtil reader;
        private final ConverterSetupsPG setups;

        private List<Stack3D<?>> stacks = Collections.emptyList();
        private List<ConverterSetup> converters = Collections.emptyList();
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

            final Matrix4f view = MatrixMath.affine(st.getViewerTransform(), new Matrix4f());
            pv = MatrixMath.screenPerspective(projectionType, dCam, clipNear, clipFar, screenW, screenH, 0, new Matrix4f()).mul(view);

            final int t = st.getCurrentTimepoint();
            Set<SourceAndConverter<?>> visible = st.getVisibleAndPresentSources();

            stacks = visible.stream()
                    .map(sac -> {
                        final SourceAndConverter<?> sKey = (sac.asVolatile() != null) ? sac.asVolatile() : sac;
                        return SourceStacks.getStack3D(sKey.getSpimSource(), t);
                    })
                    .collect(Collectors.toList());

            converters = visible.stream()
                    .map(sac -> {
                        final SourceAndConverter<?> sKey = (sac.asVolatile() != null) ? sac.asVolatile() : sac;
                        ConverterSetup css = setups.getConverterSetup(sKey);
                        if (css == null) css = setups.getConverterSetup(sac);
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
            net.imglib2.cache.iotiming.CacheIoTiming.getIoTimeBudget().reset(ioBudget);
            cache.prepareNextFrame();

            offscreen.bind(gl, false);
            gl.glEnable(GL.GL_DEPTH_TEST);
            gl.glDepthFunc(GL.GL_LESS);
            gl.glClearColor(0, 0, 0, 0);
            gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
            lastRerender = renderer.draw(gl, RepaintType.FULL, offscreen, stacks, converters, pv, maxRenderMillis, maxAllowedStepInVoxels);

            offscreen.unbind(gl, false);
            gl.glClear(GL.GL_COLOR_BUFFER_BIT);
            offscreen.drawQuad(gl);

            gl.glReadBuffer(GL.GL_FRONT);
            gl.glFinish();
            lastFrame = reader.readPixelsToBufferedImage(gl, true);
        }

        @Override public void reshape(GLAutoDrawable d, int x, int y, int w, int h) {}
        @Override public void dispose(GLAutoDrawable d) {}
    }
}