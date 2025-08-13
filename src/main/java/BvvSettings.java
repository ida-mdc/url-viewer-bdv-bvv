public class BvvSettings
{
    static double dCam = 2000.;
    static double dClipNear = 1000.;
    static double dClipFar = 15000.;

    static int renderWidth = 800;
    static int renderHeight = 600;
    static int numDitherSamples = 3;
    static int cacheBlockSize = 32;
    static int maxCacheSizeInMB = 500;
    static int ditherWidth = 3;

    public static boolean readBVVRenderSettings()
    {
        try
        {
            boolean bRestartBVV = false;

            dCam = 3000;

            dClipFar = 1000;

            dClipNear = 1000;

            //dClipNear should be less than dCam
            if ( dCam < dClipNear )
            {
                dCam = dClipNear + 5.0;
            }

            int nTempInt = 600;
            if ( renderWidth != nTempInt )
            {
                bRestartBVV = true;
            }
            renderWidth = nTempInt;

            nTempInt = 600;
            if ( renderHeight != nTempInt )
            {
                bRestartBVV = true;
            }
            renderHeight = nTempInt;

            nTempInt = 3;
            if ( numDitherSamples != nTempInt )
            {
                bRestartBVV = true;
            }
            numDitherSamples = nTempInt;

            nTempInt = 32;
            if ( cacheBlockSize != nTempInt )
            {
                bRestartBVV = true;
            }
            cacheBlockSize = nTempInt;

            nTempInt = 500;
            if ( maxCacheSizeInMB != nTempInt )
            {
                bRestartBVV = true;
            }
            maxCacheSizeInMB = nTempInt;

            String dithering = "3x3";
            final int ditherWidthIn;
            switch ( dithering )
            {
                case "none (always render full resolution)":
                default:
                    ditherWidthIn = 1;
                    break;
                case "2x2":
                    ditherWidthIn = 2;
                    break;
                case "3x3":
                    ditherWidthIn = 3;
                    break;
                case "4x4":
                    ditherWidthIn = 4;
                    break;
                case "5x5":
                    ditherWidthIn = 5;
                    break;
                case "6x6":
                    ditherWidthIn = 6;
                    break;
                case "7x7":
                    ditherWidthIn = 7;
                    break;
                case "8x8":
                    ditherWidthIn = 8;
                    break;
            }

            if ( ditherWidth != ditherWidthIn )
            {
                bRestartBVV = true;
            }

            ditherWidth = ditherWidthIn;

            return bRestartBVV;
        }
        catch ( Exception e )
        {
            System.out.println("[WARN]: Could not fetch BVV rendering settings");
            return false;
        }
    }
}
