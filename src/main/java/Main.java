import bdv.BigDataViewer;
import bdv.cache.CacheControl;
import bdv.cache.SharedQueue;
import bdv.spimdata.WrapBasicImgLoader;
import bdv.tools.brightness.ConverterSetup;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.viewer.*;
import bvvpg.source.converters.ConverterSetupsPG;
import bvvpg.source.converters.RealARGBColorGammaConverterSetup;
import bvvpg.vistools.Bvv;
import bvvpg.vistools.BvvFunctions;
import bvvpg.vistools.BvvGamma;
import bvvpg.vistools.BvvStackSource;
import ij.plugin.LutLoader;
import mpicbg.spim.data.generic.AbstractSpimData;
import net.imglib2.FinalRealInterval;
import net.imglib2.cache.queue.BlockingFetchQueues;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5URI;
import org.janelia.saalfeldlab.n5.bdv.N5Viewer;
import org.janelia.saalfeldlab.n5.ui.DataSelection;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5MetadataUtils;
import org.janelia.saalfeldlab.n5.universe.metadata.N5Metadata;

import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static net.imglib2.type.numeric.ARGBType.rgba;
import static org.janelia.saalfeldlab.n5.bdv.N5Viewer.buildN5Sources;


public class Main {

    static double dCam = 3000;
    static double dClipNear = 1000.;
    static double dClipFar = 15000.;

    static int renderWidth = 800;
    static int renderHeight = 600;
    static int numDitherSamples = 3;
    static int cacheBlockSize = 32;
    static int maxCacheSizeInMB = 2000;
    static int ditherWidth = 3;

    public static Bvv showInBvv(N5URI uri, N5Reader n5) throws IOException {

        List sourcesAndConverters = getSourcesAndConverters(uri, n5);
        Bvv bvv = BvvFunctions.show(Bvv.options().frameTitle("BigVolumeViewer").
                dCam(dCam).
                dClipNear(dClipNear).
                dClipFar(dClipFar).
                renderWidth(renderWidth).
                renderHeight(renderHeight).
                numDitherSamples(numDitherSamples).
                cacheBlockSize(cacheBlockSize).
                maxCacheSizeInMB(maxCacheSizeInMB).
                ditherWidth(ditherWidth)
        );
//        SourceAndConverter spimData = (SourceAndConverter) sourcesAndConverters.get(0);
//        List<BvvStackSource<?>> sources = List.of();
        sourcesAndConverters.forEach(soc -> {
            final AbstractSpimData< ? > spimData = SourceToSpimDataWrapper.wrap( ((SourceAndConverter) soc).getSpimSource() );
            List<BvvStackSource<?>> bvvSource = BvvFunctions.show(spimData,
                    Bvv.options().addTo(bvv));
            prettify(bvvSource.get(0));
        });

        return bvv;

    }

    public static Bvv showInBvv(N5URI uri, N5URI uriLabels, N5Reader n5, N5Reader n5Labels) throws IOException {

        List sourcesAndConverters = getSourcesAndConverters(uri, n5);
        List sourcesAndConvertersLabels = getSourcesAndConverters(uriLabels, n5Labels);
        Bvv bvv = BvvFunctions.show(Bvv.options().frameTitle("BigVolumeViewer").
                dCam(dCam).
                dClipNear(dClipNear).
                dClipFar(dClipFar).
                renderWidth(renderWidth).
                renderHeight(renderHeight).
                numDitherSamples(numDitherSamples).
                cacheBlockSize(cacheBlockSize).
                maxCacheSizeInMB(maxCacheSizeInMB).
                ditherWidth(ditherWidth)
        );
//        SourceAndConverter spimData = (SourceAndConverter) sourcesAndConverters.get(0);
//        List<BvvStackSource<?>> sources = List.of();
        sourcesAndConverters.forEach(soc -> {
            final AbstractSpimData< ? > spimData = SourceToSpimDataWrapper.wrap( ((SourceAndConverter) soc).getSpimSource() );
            List<BvvStackSource<?>> bvvSource = BvvFunctions.show(spimData,
                    Bvv.options().addTo(bvv));
            prettify(bvvSource.get(0));
        });
        sourcesAndConvertersLabels.forEach(soc -> {
            final AbstractSpimData< ? > spimData = SourceToSpimDataWrapper.wrap( ((SourceAndConverter) soc).getSpimSource() );
            List<BvvStackSource<?>> bvvSource = BvvFunctions.show(spimData,
                    Bvv.options().addTo(bvv));
//            prettify(bvvSource.get(0));
        });

        return bvv;

    }

    public static Bvv showInBvvMasked(List<AbstractSpimData> sources) throws IOException {

        Bvv bvv = BvvFunctions.show(Bvv.options().frameTitle("BigVolumeViewer").
                dCam(dCam).
                dClipNear(dClipNear).
                dClipFar(dClipFar).
                renderWidth(renderWidth).
                renderHeight(renderHeight).
                numDitherSamples(numDitherSamples).
                cacheBlockSize(cacheBlockSize).
                maxCacheSizeInMB(maxCacheSizeInMB).
                ditherWidth(ditherWidth)
        );

        for(AbstractSpimData s : sources) {
            List<BvvStackSource<?>> bvvSource = BvvFunctions.show(s,
                    Bvv.options().addTo(bvv));
        }

        return bvv;

    }

    private static List<AbstractSpimData> getAbstractSpimData(N5URI uriSource, N5URI uriLabels, N5Reader n5, N5Reader n5Labels) throws IOException {

        List<AbstractSpimData> sources = new ArrayList();

        List<SourceAndConverter> sourcesAndConverters = getSourcesAndConverters(uriSource, n5);
        List<SourceAndConverter> sourcesAndConvertersLabels = getSourcesAndConverters(uriLabels, n5Labels);
        SourceAndConverter labelSac = sourcesAndConvertersLabels.get(0);

//        SourceAndConverter spimData = (SourceAndConverter) sourcesAndConverters.get(0);
//        List<BvvStackSource<?>> sources = List.of();
        sourcesAndConverters.forEach(soc -> {
            // Build the masked source
            MaskedSource masked = new MaskedSource(
                    soc.getSpimSource(),
                    (Source<UnsignedShortType>) labelSac.getSpimSource(),
                    getTargetLabels(),
                    (RealType) soc.getSpimSource().getType());

            // Reuse the same converter (maps T -> ARGB) as intensitySac
            SourceAndConverter maskedSac =
                    new SourceAndConverter<>(masked, soc.getConverter());
            final AbstractSpimData< ? > spimData = SourceToSpimDataWrapper.wrap( maskedSac.getSpimSource() );
            sources.add(spimData);
//            prettify(bvvSource.get(0));
        });
        return sources;
    }
    private static void prettify(BvvStackSource<?> source) {

        source.setDisplayRangeBounds( 0, 40000 );
        source.setDisplayGamma(0.5);


        //set volumetric rendering (1), instead of max intensity max intensity (0)
        source.setRenderType(1);

        //DisplayRange maps colors (or LUT values) to intensity values
        source.setDisplayRange(200, 2000);
        //it is also possible to change gamma value
        //source.setDisplayGamma(0.9);

        //alpha channel to intensity mapping can be changed independently
//        source.setAlphaRange(0, 1000);
        //it is also possible to change alpha-channel gamma value
        //source.setAlphaGamma(0.9);

        //assign a "Fire" lookup table to this source
//        source.setLUT("Fire");

        //or one can assign custom IndexColorModel + name as string
        //in this illustration we going to get IndexColorModel from IJ
        //(but it could be made somewhere else)
        //final IndexColorModel icm_lut = LutLoader.getLut("Spectrum");
        //source.setLUT( icm_lut, "SpectrumLUT" );


        //clip half of the volume along Z axis in the shaders
//        source.setClipInterval(new FinalRealInterval(new double[]{0, 0, 0}, new double[]{20000, 20000, 5000}));
        //turn on clipping
    }

    private static void prettify(RealARGBColorGammaConverterSetup source) {

        source.setDisplayGamma(0.9);
        //set volumetric rendering (1), instead of max intensity max intensity (0)
        source.setRenderType(1);


        //DisplayRange maps colors (or LUT values) to intensity values
        source.setDisplayRange(200, 500);
        //it is also possible to change gamma value
        source.setAlphaGamma(0.9);

        //alpha channel to intensity mapping can be changed independently
//        source.setAlphaRange(0, 1000);
        //it is also possible to change alpha-channel gamma value
        //source.setAlphaGamma(0.9);

        //assign a "Fire" lookup table to this source
//        source.setLUT("Fire");

        //or one can assign custom IndexColorModel + name as string
        //in this illustration we going to get IndexColorModel from IJ
        //(but it could be made somewhere else)
        final IndexColorModel icm_lut = LutLoader.getLut("Spectrum");
        source.setLUT( icm_lut, "SpectrumLUT" );


        //clip half of the volume along Z axis in the shaders
//        source.setClipInterval(new FinalRealInterval(new double[]{0, 0, 0}, new double[]{20000, 20000, 500}));
        //turn on clipping
    }

    public static void renderInBvv(N5URI uri, N5Reader n5) throws Exception {



        final List<SourceAndConverter<?>> socs = getSourcesAndConverters(uri, n5);

        final ViewerState state = new SynchronizedViewerState(new BasicViewerState());
        state.setNumTimepoints(1);

        final ConverterSetupsPG setups = new ConverterSetupsPG(state);
        for (SourceAndConverter<?> source : socs) {

            final AbstractSpimData< ? > spimData = SourceToSpimDataWrapper.wrap( ((SourceAndConverter) source).getSpimSource() );

            WrapBasicImgLoader.wrapImgLoaderIfNecessary( spimData );
            final ArrayList< SourceAndConverter< ? > > sources = new ArrayList<>();
            //BigDataViewer.initSetups( spimData, new ArrayList<>(), sources );
            ArrayList<ConverterSetup> converterSetups = new ArrayList<>();
            BvvGamma.initSetups(spimData, converterSetups, sources);

            for (int i = 0; i < converterSetups.size(); i++) {
                RealARGBColorGammaConverterSetup converterSetup = (RealARGBColorGammaConverterSetup) converterSetups.get(i);
                SourceAndConverter soc = sources.get(i);
                prettify(converterSetup);
                                // Add ONLY the non-volatile to the ViewerState
                state.addSource(soc);
                state.setSourceActive(soc, true);

                // Register converter setups for BOTH identities → lookups always succeed
                setups.put(soc, converterSetup);
                if (soc.asVolatile() != null) {
                    setups.put(soc.asVolatile(), converterSetup);
                }
            }

//            for ( final SourceAndConverter< ? > source2 : sources ) {
//
//
//
//                final RealARGBColorGammaConverterSetup gcs = (RealARGBColorGammaConverterSetup) BvvGamma.createConverterSetupBT(source2, setupId);
//
//                // Add ONLY the non-volatile to the ViewerState
//                state.addSource(source2);
//                state.setSourceActive(source2, true);
//
//                // Register converter setups for BOTH identities → lookups always succeed
//                setups.put(source2, gcs);
//                if (source2.asVolatile() != null) {
//                    setups.put(source2.asVolatile(), gcs);
//                }
//
//                // Configure display
//                prettify(gcs);
////                gcs.setColor(new ARGBType(rgba(255,255,255,255)));
//                setupId++;
//
//            }

            WrapBasicImgLoader.removeWrapperIfPresent( spimData );

        }

        final CacheControl cache = new CacheControl.CacheControls();

        BvvRotateMovie movieGenerator = new BvvRotateMovie(state, setups, cache, 1920, 1080);
        movieGenerator.recordRotateMovie(20, new File("/home/random/Development/hi/collabs/treier/bvv/frames"));
    }

    private static List getSourcesAndConverters(N5URI uri, N5Reader n5) throws IOException {


        String rootGroup = uri.getGroupPath() != null ? uri.getGroupPath() : "/";
        List<N5Metadata> metadataList = new ArrayList();
        N5Metadata rootMetadata = N5MetadataUtils.parseMetadata(n5, rootGroup);
        if (rootMetadata == null) {
            throw new RuntimeException("No image found at: " + uri);
        }

        metadataList.add(rootMetadata);

        final DataSelection selection = new DataSelection(n5, metadataList);
        final SharedQueue sharedQueue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        final List<ConverterSetup> converterSetups = new ArrayList<>();
        final List sourcesAndConverters = new ArrayList<>();

        final BdvOptions options = BdvOptions.options().frameTitle("N5 Viewer");
        try {
            buildN5Sources(
                    n5,
                    selection,
                    sharedQueue,
                    converterSetups,
                    sourcesAndConverters,
                    options);

        } catch (final IOException e1) {
            e1.printStackTrace();
            return null;
        }

        if (sourcesAndConverters.isEmpty()) {
            throw new IOException("N5ImageData: No datasets found.");
        }
        return sourcesAndConverters;
    }

    public static void showInBdv(N5URI n5URI) {
        N5Viewer.show(n5URI);
    }

    public static void showInBdv(N5URI uri, N5Reader n5) throws IOException {

        List sourcesAndConverters = getSourcesAndConverters(uri, n5);
        Bdv bdv = BdvFunctions.show( Bdv.options().frameTitle( "BigDataViewer" ) );
//        final AbstractSpimData< ? > spimData = SourceToSpimDataWrapper.wrap( ((SourceAndConverter) sourcesAndConverters.get(0)).getSpimSource() );
        SourceAndConverter spimData = (SourceAndConverter) sourcesAndConverters.get(0);
        BdvFunctions.show(spimData, Bdv.options().addTo( bdv ));
    }

	public static void main(String...args) throws Exception {

        String uri;
        if(args.length == 0) {
            uri = "https://minio-dev.openmicroscopy.org/idr/v0.4/idr0077/9836832_z_dtype_fix.zarr";
        } else {
            uri = args[0];
        }
        N5URI n5URI = new N5URI(uri);

        //        showInBdv(n5URI);

        N5Factory n5Factory = new N5Factory();
        N5Reader n5 = n5Factory.openReader(uri);

//        showInBdv(n5URI, n5);

        renderInBvv(n5URI, n5);


	}

}