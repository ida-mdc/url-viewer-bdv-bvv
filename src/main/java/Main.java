import bdv.cache.SharedQueue;
import bdv.util.BdvOptions;
import bdv.viewer.SourceAndConverter;
import bvv.vistools.Bvv;
import bvv.vistools.BvvFunctions;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5URI;
import org.janelia.saalfeldlab.n5.bdv.N5Viewer;
import org.janelia.saalfeldlab.n5.ui.DataSelection;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5MetadataUtils;
import org.janelia.saalfeldlab.n5.universe.metadata.N5Metadata;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public class Main {

    static double dCam = 3000;
    static double dClipNear = 1000.;
    static double dClipFar = 15000.;

    static int renderWidth = 800;
    static int renderHeight = 600;
    static int numDitherSamples = 3;
    static int cacheBlockSize = 32;
    static int maxCacheSizeInMB = 500;
    static int ditherWidth = 3;

    public static void showInBvv(N5URI uri, N5Reader n5) throws IOException {

        ArrayList sourcesAndConverters = getSourcesAndConverters(uri, n5);
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
        BvvFunctions.show(((SourceAndConverter) sourcesAndConverters.get(0)).getSpimSource(),
                Bvv.options().addTo( bvv ));
    }

    private static ArrayList getSourcesAndConverters(N5URI uri, N5Reader n5) throws IOException {
        String rootGroup = uri.getGroupPath() != null ? uri.getGroupPath() : "/";
        List<N5Metadata> metadataList = new ArrayList();
        N5Metadata rootMetadata = N5MetadataUtils.parseMetadata(n5, rootGroup);
        if (rootMetadata == null) {
            throw new RuntimeException("No image found at: " + uri);
        }

        metadataList.add(rootMetadata);

        ArrayList converterSetups = new ArrayList();
        ArrayList sourcesAndConverters = new ArrayList();

        int numTimePoints = 1;
        SharedQueue sharedQueue= new SharedQueue(1);
        BdvOptions bdvOptions = new BdvOptions();
        for(N5Metadata metadata : metadataList) {
            DataSelection selection = new DataSelection(n5, Collections.singletonList(metadata));
            numTimePoints = Math.max(numTimePoints, N5Viewer.buildN5Sources(n5, selection, sharedQueue, converterSetups, sourcesAndConverters, bdvOptions));
        }

        if (sourcesAndConverters.isEmpty()) {
            throw new IOException("N5ImageData: No datasets found.");
        }
        return sourcesAndConverters;
    }

    public static void showInBdv(N5URI n5URI) {
        N5Viewer.show(n5URI);
    }

	public static void main(String...args) throws URISyntaxException, IOException {

        String uri;
        if(args.length == 0) {
            uri = "https://hifis-storage.desy.de:2443/Helmholtz/HIP/collaborations/2405_MDC_Treier/public/G5111-S4/all?";
        } else {
            uri = args[0];
        }
        N5URI n5URI = new N5URI(uri);

//        showInBdv(n5URI);

        N5Factory n5Factory = new N5Factory();
        N5Reader n5 = n5Factory.openReader(uri);

        showInBvv(n5URI, n5);

	}
}