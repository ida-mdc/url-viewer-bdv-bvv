import bdv.viewer.Source;
import bdv.viewer.Interpolation;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.BiConverter;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;

import java.util.List;

public class MaskedSource<T extends RealType<T>> implements Source<T> {

    private final Source<T> intensity;
    private final Source<UnsignedShortType> labels;
    private final List<Long> targetLabels;
    private final T type;

    private final AffineTransform3D i2g = new AffineTransform3D();
    private final AffineTransform3D l2g = new AffineTransform3D();
    private final AffineTransform3D i2l = new AffineTransform3D();

    private final BiConverter<T, UnsignedShortType, T> bi;

    public MaskedSource(Source<T> intensity,
                        Source<UnsignedShortType> labels,
                        List<Long> targetLabels,
                        T typePrototype)
    {
        this.intensity = intensity;
        this.labels = labels;

        this.targetLabels = targetLabels;
        this.type = typePrototype.createVariable();
        bi = (in, l, out) -> {
//            if (targetLabels.contains(l.getIntegerLong())) out.set(in);
            if (l.getIntegerLong()> 0) out.set(in);
            else out.setZero();
        };
    }

    @Override public boolean isPresent(int t) {
        return intensity.isPresent(t) && labels.isPresent(t);
    }

    @Override public int getNumMipmapLevels() {
        // IMPORTANT: expose all levels of the intensity source
        return Math.min(intensity.getNumMipmapLevels(), labels.getNumMipmapLevels());
    }

    @Override public T getType() { return type; }

    @Override public String getName() {
        return intensity.getName() + " [mask]";
    }

    @Override
    public void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {
        // Space of the masked source = intensity space
        intensity.getSourceTransform(t, level, transform);
    }


    @Override
    public RandomAccessibleInterval<T> getSource(final int t, final int level) {
        final RandomAccessibleInterval<T> img = intensity.getSource(t, level);
        final RandomAccessibleInterval<UnsignedShortType> lab = labels.getSource(t, level);

        // Work on extended RAs (safe if bounds differ), then crop back to the img interval
        final RandomAccessible<T> imgEx  = Views.extendZero(img);
        final RandomAccessible<UnsignedShortType> labEx = Views.extendZero(lab);

        final BiConverter<T, UnsignedShortType, T> bi = (in, l, out) -> {
            if (targetLabels.contains(l.getIntegerLong())) out.set(in);
            else out.setZero();
        };

        final RandomAccessible<T> maskedRA =
                Converters.convert(imgEx, labEx, bi, type.copy());

        return Views.interval(maskedRA, img); // same interval as intensity
    }

    @Override
    public RealRandomAccessible<T> getInterpolatedSource(final int t, final int level, final Interpolation interpolation) {
        // Interpolated intensity with the user’s chosen mode
        final RealRandomAccessible<T> intRRA = intensity.getInterpolatedSource(t, level, interpolation);

        // Interpolated labels with NEAREST to avoid mixing label IDs
        final RealRandomAccessible<UnsignedShortType> labRRA = labels.getInterpolatedSource(t, level, Interpolation.NEARESTNEIGHBOR);

        final BiConverter<T, UnsignedShortType, T> bi = (in, l, out) -> {
            if (targetLabels.contains(l.getIntegerLong())) out.set(in);
            else out.setZero();
        };

        // Combine at RealRandomAccessible level
        return Converters.convert(intRRA, labRRA, bi, type.copy());
    }

    @Override
    public VoxelDimensions getVoxelDimensions() {
        return intensity.getVoxelDimensions();
    }
}
