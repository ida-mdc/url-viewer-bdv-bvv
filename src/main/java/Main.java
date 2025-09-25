import bdv.cache.CacheControl;
import bdv.cache.SharedQueue;
import bdv.spimdata.WrapBasicImgLoader;
import bdv.tools.brightness.ConverterSetup;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.*;
import bvvpg.source.converters.ConverterSetupsPG;
import bvvpg.source.converters.RealARGBColorGammaConverterSetup;
import bvvpg.vistools.Bvv;
import bvvpg.vistools.BvvFunctions;
import bvvpg.vistools.BvvGamma;
import bvvpg.vistools.BvvStackSource;
import mpicbg.spim.data.generic.AbstractSpimData;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5URI;
import org.janelia.saalfeldlab.n5.bdv.N5Viewer;
import org.janelia.saalfeldlab.n5.ui.DataSelection;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5MetadataUtils;
import org.janelia.saalfeldlab.n5.universe.metadata.N5Metadata;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.janelia.saalfeldlab.n5.bdv.N5Viewer.buildN5Sources;


public class Main {

    // Per-channel styling (EDIT THESE TO YOUR DATA)
//    private static final IndexColorModel[] CHANNEL_LUTS = new IndexColorModel[] {
//            LutLoader.getLut("Cyan"),     // ch 0
//            LutLoader.getLut("Magenta"),  // ch 1
//            LutLoader.getLut("Yellow")    // ch 2
//    };
    private static final ARGBType[] CHANNEL_LUTS = new ARGBType[] {
            new ARGBType(ARGBType.rgba(204, 255, 204, 255)),     // ch 0
            new ARGBType(ARGBType.rgba(51, 255, 255, 255)),     // ch 0
            new ARGBType(ARGBType.rgba(255, 153, 204, 255)),     // ch 0
    };

    private static final double[][] CHANNEL_RANGES = new double[][] {
            {300, 400},   // ch 0 min..max
            {300, 400},   // ch 1
            {300, 400}   // ch 2
    };

    private static final double[][] CHANNEL_ALPHA_RANGES = new double[][] {
            {300, 20000},   // ch 0 min..max
            {300, 20000},   // ch 1
            {300, 20000}   // ch 2
    };


    // optional per-channel gamma (or keep one global if you prefer)
    private static final double[] CHANNEL_GAMMA = new double[] { 1.0, 1.0, 1.0 };

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

    public static Bvv showInBvvMasked(N5Factory n5Factory, String uriLabels, List<String> uriChannels, List<Integer> colors) throws IOException, URISyntaxException {

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

        N5Reader n5Labels = n5Factory.openReader(uriLabels);

        for (int i = 0; i < uriChannels.size(); i++) {

            List<AbstractSpimData> spimData = getAbstractSpimData(
                    new N5URI(uriChannels.get(i)),
                    new N5URI(uriLabels),
                    n5Factory.openReader(uriChannels.get(i)), n5Labels);

            for (AbstractSpimData s : spimData) {
                List<BvvStackSource<?>> bvvSource = BvvFunctions.show(s,
                        Bvv.options().addTo(bvv));
                for (int j = 0; j < bvvSource.size(); j++) {
                    BvvStackSource<?> bvvStackSource = bvvSource.get(j);
                    bvvStackSource.setColor(new ARGBType(colors.get(i)));
                    if (j == 1) {
                        bvvStackSource.setDisplayRange(0, 600);
                    }
                    if (j == 2) {
                        bvvStackSource.setDisplayRange(0, 2000);
                    }
                }
            }

        }

        return bvv;

    }

    public static void screenshotInBvvMasked(N5Factory n5Factory, String uriLabels, List<String> uriChannels, List<Integer> colors, String screenshotTarget) throws Exception {

        final ViewerState state = new SynchronizedViewerState(new BasicViewerState());
        state.setNumTimepoints(1);
        state.setDisplayMode(DisplayMode.FUSED);

        final ConverterSetupsPG setups = new ConverterSetupsPG(state);

        N5Reader n5Labels = n5Factory.openReader(uriLabels);

        for (int i = 0; i < uriChannels.size(); i++) {

            List<AbstractSpimData> spimDatas = getAbstractSpimData(
                    new N5URI(uriChannels.get(i)),
                    new N5URI(uriLabels),
                    n5Factory.openReader(uriChannels.get(i)), n5Labels);

            final ArrayList<SourceAndConverter<?>> sources = new ArrayList<>();
            final ArrayList<ConverterSetup> converterSetups = new ArrayList<>();

            for (AbstractSpimData spimData : spimDatas) {
                BvvGamma.initSetups(spimData, converterSetups, sources);

                for (int j = 0; j < converterSetups.size(); j++) {
                    final RealARGBColorGammaConverterSetup cs = (RealARGBColorGammaConverterSetup) converterSetups.get(j);
                    final SourceAndConverter<?> soc = sources.get(j);

//                List<BvvStackSource<?>> cscs = BvvFunctions.show(spimData,
//                        Bvv.options().addTo(bvv));
//
//                for (BvvStackSource<?> csc : cscs) {
//                    for (ConverterSetup converterSetup : csc.getConverterSetups()) {
//                        prettify((RealARGBColorGammaConverterSetup)converterSetup, globalChannel);
//                    }
//                }
//
//                double displayRangeMax = cscs.get(0).getConverterSetups().get(0).getDisplayRangeMax();
//                double displayRangeMin = cscs.get(0).getConverterSetups().get(0).getDisplayRangeMin();
//                BvvStackSource<?> bvvStackSource = cscs.get(0);
//                final HashSet< MinMaxGroup > groups = new HashSet<>();
//                final SetupAssignmentsPG sa = bvvStackSource.getBvvHandle().getSetupAssignments();
//                for ( final ConverterSetup setup : bvvStackSource.getConverterSetups() )
//                    groups.add( sa.getMinMaxGroup( setup ) );
//                for ( final MinMaxGroup group : groups )
//                {
//                    group.getMinBoundedValue().setCurrentValue(displayRangeMin);
//                    group.getMaxBoundedValue().setCurrentValue(displayRangeMax);
//                }

                    // show only non-volatile in ViewerState
                    state.addSource(soc);
                    state.setSourceActive(soc, true);

                    // register setup for both identities so lookups work
                    setups.put(soc, cs);
                    if (soc.asVolatile() != null) {
                        setups.put(soc.asVolatile(), cs);
                    }

                }

            }

//            for (AbstractSpimData s : spimData) {
//                List<BvvStackSource<?>> bvvSource = BvvFunctions.show(s,
//                        Bvv.options().addTo(bvv));
//                for (int j = 0; j < bvvSource.size(); j++) {
//                    BvvStackSource<?> bvvStackSource = bvvSource.get(j);
//                    bvvStackSource.setColor(new ARGBType(colors.get(i)));
//                    if (j == 1) {
//                        bvvStackSource.setDisplayRange(0, 600);
//                    }
//                    if (j == 2) {
//                        bvvStackSource.setDisplayRange(0, 2000);
//                    }
//                }
//            }

        }


        final CacheControl cache = new CacheControl.CacheControls();
        final BvvSingleScreenshot screenshot = new BvvSingleScreenshot(state, setups, cache, 1920, 1080);
        screenshot.capture(new File(screenshotTarget));

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

    private static List<Long> getTargetLabels() {
        Integer[] labelsHY = new Integer[]{
                10752, 10753, 10754, 10755, 10756, 10757, 10758, 10759, 10760, 10761,
                10762, 10763, 10764, 10765, 10766, 10767, 10768, 10769, 10770, 10771,
                10772, 10773, 10774, 10775, 10776, 10777, 10778, 10779, 10780, 10781,
                10782, 10783, 10784, 10785, 10786, 10787, 10788, 10789, 10790, 10791,
                10792, 10793, 10794, 10795, 10796, 10797, 10798, 10799, 10800, 10801,
                10802, 10803, 10804, 10805, 716, 717, 718, 719, 720, 721, 722, 723,
                724, 725, 726, 727, 728, 729, 730, 731, 732, 733, 734, 735, 736, 737,
                738, 739, 740, 741, 742, 743, 744, 745, 746, 747, 748, 749, 750, 751,
                752, 753, 754, 755, 756, 757, 758, 759, 760, 761, 762, 763, 764, 765,
                766, 767, 768, 769, 770, 771, 772, 773, 774, 775, 776, 777, 778, 779,
                780, 781, 782, 783, 784, 785, 786, 787, 788, 789, 790, 791, 792, 793,
                794, 795, 796, 797, 798, 799, 800, 801, 802, 803, 804, 805,
                10716, 10717, 10718, 10719, 10720, 10721, 10722, 10723, 10724, 10725,
                10726, 10727, 10728, 10729, 10730, 10731, 10732, 10733, 10734, 10735,
                10736, 10737, 10738, 10739, 10740, 10741, 10742, 10743, 10744, 10745,
                10746, 10747, 10748, 10749, 10750, 10751
        };

        Integer[] labelsDMH = new Integer[]{739, 740, 741, 742, 10739, 10740, 10741, 10742};
        Integer[] labelsPH = new Integer[]{792, 10792};
        Integer[] labelsTU = new Integer[]{801, 10801};

        Stream<Integer> labels = Stream.concat(
                Stream.concat(
                        Arrays.stream(labelsDMH),
                        Arrays.stream(labelsPH)
                ),
                Arrays.stream(labelsTU)
        );
//        Stream<Integer> labels = Arrays.stream(labelsTU);
        return labels.map(Long::valueOf)
                .collect(Collectors.toList());
    }

    private static void prettify(BvvStackSource<?> source) {

        source.setDisplayRangeBounds( 0, 40000 );
        source.setDisplayGamma(0.5);


        //set volumetric rendering (1), instead of max intensity max intensity (0)
        source.setRenderType(0);

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

    private static void prettify(final RealARGBColorGammaConverterSetup setup, final int ch) {
        final int k = Math.min(ch, CHANNEL_LUTS.length - 1);  // clamp if more channels than presets
//        setup.setDisplayGamma(CHANNEL_GAMMA[k]);
//        setup.setAlphaGamma(CHANNEL_GAMMA[k]);

        // per-channel window/level
        final double min = CHANNEL_RANGES[k][0];
        final double max = CHANNEL_RANGES[k][1];
        setup.setDisplayRange(min, max);

        // per-channel colorization
        setup.setColor(CHANNEL_LUTS[k]);
        // global-ish
        setup.setRenderType(1);
        setup.setAlphaRange(CHANNEL_ALPHA_RANGES[k][0], CHANNEL_ALPHA_RANGES[k][1]);
    }

    private static void prettify(final BvvStackSource setup, final int ch) {
        final int k = Math.min(ch, CHANNEL_LUTS.length - 1);  // clamp if more channels than presets
//        setup.setDisplayGamma(CHANNEL_GAMMA[k]);
//        setup.setAlphaGamma(CHANNEL_GAMMA[k]);

        // per-channel window/level
        final double min = CHANNEL_RANGES[k][0];
        final double max = CHANNEL_RANGES[k][1];
        setup.setDisplayRange(min, max);
        setup.setDisplayRangeBounds(min, max);

        // per-channel colorization
        setup.setColor(CHANNEL_LUTS[k]);
        // global-ish
        setup.setRenderType(1);
        setup.setAlphaRange(CHANNEL_ALPHA_RANGES[k][0], CHANNEL_ALPHA_RANGES[k][1]);
        setup.setAlphaRangeBounds(CHANNEL_ALPHA_RANGES[k][0], CHANNEL_ALPHA_RANGES[k][1]);
    }

    public static void renderInBvv(final N5URI uri, final N5Reader n5) throws Exception {

//        Bvv bvv = BvvFunctions.show(Bvv.options().frameTitle("BigVolumeViewer").
//                dCam(dCam).
//                dClipNear(dClipNear).
//                dClipFar(dClipFar).
//                renderWidth(renderWidth).
//                renderHeight(renderHeight).
//                numDitherSamples(numDitherSamples).
//                cacheBlockSize(cacheBlockSize).
//                maxCacheSizeInMB(maxCacheSizeInMB).
//                ditherWidth(ditherWidth)
//        );

        final ViewerState state = new SynchronizedViewerState(new BasicViewerState());
        final ConverterSetupsPG setups = new ConverterSetupsPG(state);
        final List<SourceAndConverter<?>> socs = getSourcesAndConverters(uri, n5);

        state.setNumTimepoints(1);
        state.setDisplayMode(DisplayMode.FUSED);


        int globalChannel = 0; // <- track channel index across created sources

        for (SourceAndConverter<?> source : socs) {

            final AbstractSpimData<?> spimData = SourceToSpimDataWrapper.wrap(((SourceAndConverter) source).getSpimSource());
            WrapBasicImgLoader.wrapImgLoaderIfNecessary(spimData);

            final ArrayList<SourceAndConverter<?>> sources = new ArrayList<>();
            final ArrayList<ConverterSetup> converterSetups = new ArrayList<>();
            BvvGamma.initSetups(spimData, converterSetups, sources);

            for (int i = 0; i < converterSetups.size(); i++) {
                final RealARGBColorGammaConverterSetup cs = (RealARGBColorGammaConverterSetup) converterSetups.get(i);
                final SourceAndConverter<?> soc = sources.get(i);

                System.out.println(globalChannel);

                prettify(cs, globalChannel);

//                List<BvvStackSource<?>> cscs = BvvFunctions.show(spimData,
//                        Bvv.options().addTo(bvv));
//
//                for (BvvStackSource<?> csc : cscs) {
//                    for (ConverterSetup converterSetup : csc.getConverterSetups()) {
//                        prettify((RealARGBColorGammaConverterSetup)converterSetup, globalChannel);
//                    }
//                }
//
//                double displayRangeMax = cscs.get(0).getConverterSetups().get(0).getDisplayRangeMax();
//                double displayRangeMin = cscs.get(0).getConverterSetups().get(0).getDisplayRangeMin();
//                BvvStackSource<?> bvvStackSource = cscs.get(0);
//                final HashSet< MinMaxGroup > groups = new HashSet<>();
//                final SetupAssignmentsPG sa = bvvStackSource.getBvvHandle().getSetupAssignments();
//                for ( final ConverterSetup setup : bvvStackSource.getConverterSetups() )
//                    groups.add( sa.getMinMaxGroup( setup ) );
//                for ( final MinMaxGroup group : groups )
//                {
//                    group.getMinBoundedValue().setCurrentValue(displayRangeMin);
//                    group.getMaxBoundedValue().setCurrentValue(displayRangeMax);
//                }

                // show only non-volatile in ViewerState
                state.addSource(soc);
                state.setSourceActive(soc, true);

                // register setup for both identities so lookups work
                setups.put(soc, cs);
                if (soc.asVolatile() != null) {
                    setups.put(soc.asVolatile(), cs);
                }

                globalChannel++;
            }


            WrapBasicImgLoader.removeWrapperIfPresent(spimData);
        }

        final CacheControl cache = new CacheControl.CacheControls();
        final BvvRotateMovie movie = new BvvRotateMovie(state, setups, cache, 1920, 1080);
        movie.recordRotateMovie(20, new File("/home/random/Development/hi/collabs/treier/bvv/frames"));
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
        BdvStackSource bs = BdvFunctions.show(spimData, Bdv.options().addTo(bdv));
    }

	public static void main(String...args) throws Exception {

        String uri;
        if(args.length == 0) {
//            uri = "https://hifis-storage.desy.de:2443/Helmholtz/HIP/collaborations/2405_MDC_Treier/public/G5182-S1/561nm_tdTomato?";
//            uri = "/media/data/Development/hi/collabs/treier/data/ome-zarr/G5182-S1/561nm_tdTomato.ome.zarr";
//            uri = "https://hifis-storage.desy.de:2880/Helmholtz/HIP/collaborations/2405_MDC_Treier/public/G5182-S1/all.ome.zarr?";
//            uri = "https://minio-dev.openmicroscopy.org/idr/v0.4/idr0077/9836832_z_dtype_fix.zarr";
//            uri = "/home/random/Development/hi/collabs/treier/example/9836832_z_dtype_fix.zarr";
//            uri = "https://uk1s3.embassy.ebi.ac.uk/idr/zarr/v0.1/6001252.zarr";
//            uri = "https://uk1s3.embassy.ebi.ac.uk/idr/zarr/v0.4/idr0062A/6001247.zarr?";
//            uri = "/media/data/Development/hi/collabs/treier/data/notebook/ngff/G5111-S4/488nm_NeuN.ome.zarr";
//            uri = "http://127.0.0.1:8096/atlas-space-bigchunks/G5217-S5/scale10.ome.zarr?";
//            uri = "/fast/TP_ImageDataAnalysis/collaborations/24 Treier/ome-zarr-nochunkchannels/atlas-space-bigchunks/G5111-S4/x10.ome.zarr/";
//            uri = "/home/random/Development/hi/collabs/treier/data/ome-zarr/G5182-S1/561nm_tdTomato.ome.zarr";
//            uri = "/home/random/mnt/max211/G5111-S4/561nm_tdTomato.ome.zarr?";
            uri = "/media/data/Development/hi/collabs/treier/data/uv2/G5111-S4_NeuN.ome.zarr";
        } else {
            uri = args[0];
        }

        N5URI n5URI = new N5URI(uri);

        //        showInBdv(n5URI);

        N5Factory n5Factory = new N5Factory();
        N5Reader n5 = n5Factory.openReader(uri);

//        showInBdv(n5URI, n5);

//        Bvv bvv = showInBvv(n5URI, n5);
//        Bvv bvv = showInBvv(n5URI, n5URILabels, n5, n5Labels);

        String uriG5217 = "http://127.0.0.1:8097/atlas-space-bigchunks/G5217-S5/scale10.ome.zarr?";
//        String uriG5182 = "http://127.0.0.1:8097/atlas-space-bigchunks/G5111-S4/scale10.ome.zarr?";
        String uriG5182 = "http://127.0.0.1:8097/atlas-space-bigchunks/G5182-S1/scale10.ome.zarr?";
        String uriLabels = "http://127.0.0.1:8097/atlas.upscaled.ome.zarr?";
        String screenshotTarget = "/fast/TP_ImageDataAnalysis/collaborations/24 Treier/ome-zarr-nochunkchannels/atlas-space-bigchunks/screenshot.png";
//
        List<String> channels = List.of(uriG5217, uriG5182);
        List<Integer> colors = List.of(ARGBType.rgba(0, 100, 255, 255), ARGBType.rgba(255, 0, 0, 255));
//
        try {
            screenshotInBvvMasked(n5Factory, uriLabels, channels, colors, screenshotTarget);
        }
        catch (UnsatisfiedLinkError e) {

        }

//        renderInBvv(n5URI, n5);


	}

}