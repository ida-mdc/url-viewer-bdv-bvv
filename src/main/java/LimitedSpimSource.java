import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.Type;

/**
 * A SpimSource wrapper that prevents access to resolution levels
 * finer than a specified minimum level.
 *
 * @param <T> the pixel type
 */
public class LimitedSpimSource<T extends Type<T>> implements Source<T> {

    private final Source<T> wrapped;
    private final int minLevel;

    public LimitedSpimSource(final Source<T> source, final int minLevel) {
        this.wrapped = source;
        this.minLevel = minLevel;
    }

    private int clampLevel(final int level) {
        return Math.max(level, minLevel);
    }

    @Override
    public RandomAccessibleInterval<T> getSource(final int t, final int level) {
        return wrapped.getSource(t, clampLevel(level));
    }

    @Override
    public RealRandomAccessible<T> getInterpolatedSource(int i, int i1, Interpolation interpolation) {
        return wrapped.getInterpolatedSource(i, i1, interpolation);
    }

    @Override
    public void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {
        wrapped.getSourceTransform(t, clampLevel(level), transform);
    }
    
    // --- All other methods are simply delegated ---

    @Override
    public boolean isPresent(final int t) {
        return wrapped.isPresent(t);
    }

    @Override
    public T getType() {
        return wrapped.getType();
    }

    @Override
    public String getName() {
        return wrapped.getName() + " [minLevel=" + minLevel + "]";
    }

    @Override
    public VoxelDimensions getVoxelDimensions() {
        return wrapped.getVoxelDimensions();
    }

    @Override
    public int getNumMipmapLevels() {
        return wrapped.getNumMipmapLevels();
    }
}