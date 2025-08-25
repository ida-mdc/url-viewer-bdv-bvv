import bdv.cache.CacheControl;
import bdv.cache.SharedQueue;
import bdv.tools.brightness.ConverterSetup;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.viewer.BasicViewerState;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.ViewerState;
import bvvpg.source.converters.ConverterSetupsPG;
import bvvpg.vistools.Bvv;
import bvvpg.vistools.BvvFunctions;
import bvvpg.vistools.BvvGamma;
import mpicbg.spim.data.generic.AbstractSpimData;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5URI;
import org.janelia.saalfeldlab.n5.bdv.N5Viewer;
import org.janelia.saalfeldlab.n5.ui.DataSelection;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5MetadataUtils;
import org.janelia.saalfeldlab.n5.universe.metadata.N5Metadata;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.janelia.saalfeldlab.n5.bdv.N5Viewer.buildN5Sources;


public class Main {

    static double dCam = 3000;
    static double dClipNear = 1000.;
    static double dClipFar = 15000.;

    static int renderWidth = 800;
    static int renderHeight = 600;
    static int numDitherSamples = 3;
    static int cacheBlockSize = 32;
    static int maxCacheSizeInMB = 7000;
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
        sourcesAndConverters.forEach(soc -> {
            final AbstractSpimData< ? > spimData = SourceToSpimDataWrapper.wrap( ((SourceAndConverter) soc).getSpimSource() );
            BvvFunctions.show(spimData,
                    Bvv.options().addTo( bvv ));
        });
        return bvv;

    }


    public static void renderInBvv(N5URI uri, N5Reader n5) throws Exception {
        final java.util.List<SourceAndConverter<?>> socs = getSourcesAndConverters(uri, n5);

        final ViewerState state = new SynchronizedViewerState(new BasicViewerState());
        state.setNumTimepoints(1);

        int setupId = 0;
        final ConverterSetupsPG setups = new ConverterSetupsPG(state);

        for (SourceAndConverter<?> soc : socs) {
            state.addSource(soc);
            state.setSourceActive(soc, true);
            final bdv.tools.brightness.ConverterSetup gcs = BvvGamma.createConverterSetupBT(soc, setupId++);
            gcs.setDisplayRange(250, 2000);
            setups.put(soc, gcs);
        }

        BvvRotateMovie movieGenerator = new BvvRotateMovie(state, setups, new CacheControl.Dummy(), 1920, 1080);
        movieGenerator.recordRotateMovie(32, new File("/home/random/Development/hi/collabs/treier/bvv/frames"));
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
            uri = "https://hifis-storage.desy.de:2443/Helmholtz/HIP/collaborations/2405_MDC_Treier/public/G5111-S4/561nm_tdTomato?";
        } else {
            uri = args[0];
        }
        N5URI n5URI = new N5URI(uri);

//        showInBdv(n5URI);

        N5Factory n5Factory = new N5Factory();
        N5Reader n5 = n5Factory.openReader(uri);

//        showInBdv(n5URI, n5);

//        Bvv bvv = showInBvv(n5URI, n5);

        renderInBvv(n5URI, n5);

	}

}