/*
 *    ImageI/O-Ext - OpenSource Java Image translation Library
 *    http://www.geo-solutions.it/
 *    https://imageio-ext.dev.java.net/
 *    (C) 2007 - 2008, GeoSolutions
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package it.geosolutions.imageio.gdalframework;

import it.geosolutions.imageio.stream.input.FileImageInputStreamExt;
import it.geosolutions.imageio.stream.input.URIImageInputStream;

import java.awt.Rectangle;
import java.awt.image.BandedSampleModel;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.media.jai.DataBufferDouble;

import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconst;
import org.gdal.gdalconst.gdalconstConstants;

import com.sun.jndi.toolkit.url.Uri;

/**
 * Main abstract class defining the main framework which needs to be used to
 * extend Image I/O architecture using <a href="http://www.gdal.org/"> GDAL
 * (Geospatial Data Abstraction Layer)</a> by means of SWIG (Simplified Wrapper
 * and Interface Generator) bindings in order to perform read operations.
 * 
 * @author Daniele Romagnoli, GeoSolutions.
 * @author Simone Giannecchini, GeoSolutions.
 */
public abstract class GDALImageReader extends ImageReader {

    /** The LOGGER for this class. */
    private static final Logger LOGGER = Logger
            .getLogger("it.geosolutions.imageio.gdalframework");

    public void setInput(Object input, boolean seekForwardOnly) {
        this.setInput(input, seekForwardOnly, false);
    }

    /** list of childs subdatasets names (if any) contained into the source */
    private String datasetNames[];

    /** number of subdatasets */
    private int nSubdatasets = -1;

    /** The ImageInputStream */
    private ImageInputStream imageInputStream;

    private volatile boolean isInitialized = false;

    /** Principal {@link ImageTypeSpecifier} */
    private ImageTypeSpecifier imageType = null;

    /** The dataset input source */
    private File datasetSource = null;
    
    /** 
     * The optional URI referring the input source.
     * As an instance, an ECWP link
     */
    private URI uriSource = null;

    /**
     * {@link HashMap} containing couples (datasetName,
     * {@link GDALCommonIIOImageMetadata}).
     */
    private Map datasetMap = Collections.synchronizedMap(new HashMap(10));

    /**
     * Retrieves a {@link GDALCommonIIOImageMetadata} by index.
     * 
     * @param imageIndex
     *                is the index of the required
     *                {@link GDALCommonIIOImageMetadata}.
     * @return a {@link GDALCommonIIOImageMetadata}
     */
    public GDALCommonIIOImageMetadata getDatasetMetadata(int imageIndex) {
        checkImageIndex(imageIndex);
        // getting dataset name
        final String datasetName = datasetNames[imageIndex];
        synchronized (datasetMap) {
            if (datasetMap.containsKey(datasetName))
                return ((GDALCommonIIOImageMetadata) datasetMap
                        .get(datasetName));
            else {
                // Add a new GDALCommonIIOImageMetadata to the HashMap
                GDALCommonIIOImageMetadata datasetMetadata = createDatasetMetadata(datasetName);
                datasetMap.put(datasetName, datasetMetadata);
                return datasetMetadata;
            }
        }
    }

    /**
     * Constructs a <code>GDALImageReader</code> using a
     * {@link GDALImageReaderSpi}.
     * 
     * @param originatingProvider
     *                The {@link GDALImageReaderSpi} to use for building this
     *                <code>GDALImageReader</code>.
     */
    public GDALImageReader(GDALImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    /**
     * Constructs a <code>GDALImageReader</code> using a
     * {@link GDALImageReaderSpi}.
     * 
     * @param originatingProvider
     *                The {@link GDALImageReaderSpi} to use for building this
     *                <code>GDALImageReader</code>.
     */
    public GDALImageReader(GDALImageReaderSpi originatingProvider,
            int numSubdatasets) {
        super(originatingProvider);
        if (numSubdatasets < 0)
            throw new IllegalArgumentException(
                    "The provided number of sub datasets is invalid");
        this.nSubdatasets = numSubdatasets;
    }

    /**
     * Checks if the specified ImageIndex is valid.
     * 
     * @param imageIndex
     *                the specified imageIndex
     * 
     * @throws IndexOutOfBoundsException
     *                 if imageIndex is belower than 0 or if is greater than the
     *                 number of subdatasets contained within the source (when
     *                 the format supports subdatasets)
     */
    protected void checkImageIndex(final int imageIndex) {
        initialize();
        // ////////////////////////////////////////////////////////////////////
        // When is an imageIndex not valid? 1) When it is negative 2) When the
        // format does not support subdatasets and imageIndex is > 0 3) When the
        // format supports subdatasets, but there isn't any subdataset and
        // imageIndex is greater than zero. 4) When the format supports
        // subdatasets, there are N subdatasets but imageIndex exceeds the
        // subdatasets count.
        // 
        // It is worthwile to remark that in case of nSubdatasets > 0, the
        // mainDataset is stored in the last position of datasetNames array. In
        // such a case the max valid imageIndex is nSubdatasets.
        // ////////////////////////////////////////////////////////////////////

        if (imageIndex < 0 || imageIndex > nSubdatasets) {
            // The specified imageIndex is not valid.
            // Retrieving the valid image index range.
            final int maxImageIndex = nSubdatasets;
            final StringBuffer sb = new StringBuffer(
                    "Illegal imageIndex specified = ").append(imageIndex)
                    .append(", while the valid imageIndex");
            if (maxImageIndex > 0)
                // There are N Subdatasets.
                sb.append(" range should be (0,").append(maxImageIndex).append(
                        ")!!");
            else
                // Only the imageIndex 0 is valid.
                sb.append(" should be 0!");
            throw new IndexOutOfBoundsException(sb.toString());
        }
    }

    /**
     * Initializes the <code>GDALImageReader</code> and return
     * <code>true</code> if the source of this reader contains several
     * subdatasets.
     * 
     * @return <code>true</code> if the source of this reader has several
     *         subDatasets.
     */
    private boolean initialize() {
        if (!GDALUtilities.isGDALAvailable())
            throw new IllegalStateException(
                    "GDAL native libraries are not available.");
        synchronized (datasetMap) {
            if (!isInitialized) {
                // Retrieving the fileName in order to open the main dataset
                
                String mainDatasetName = "";
                final Object datainput = super.getInput();
                if (!(datainput instanceof URIImageInputStream)){
                    mainDatasetName = getDatasetSource(datainput).getAbsolutePath();
                } else {
                    URI uri = ((URIImageInputStream)datainput).getUri();
                    if (uri != null){
                        mainDatasetName = uri.toString();
                    }
                } 
                final Dataset mainDataset = GDALUtilities.acquireDataSet(
                        mainDatasetName, gdalconstConstants.GA_ReadOnly);
                if (mainDataset == null)
                    return false;
                // /////////////////////////////////////////////////////////////
                //
                // Listing available subdatasets
                //
                // /////////////////////////////////////////////////////////////
                final List subdatasets = mainDataset
                        .GetMetadata_List(GDALUtilities.GDALMetadataDomain.SUBDATASETS);

                // /////////////////////////////////////////////////////////////
                //
                // setting the number of subdatasets
                // It is worth to remind that the subdatasets vector
                // contains both Subdataset's Name and Subdataset's Description
                // Thus we need to divide its size by two.
                //
                // /////////////////////////////////////////////////////////////
                nSubdatasets = subdatasets.size() / 2;

                // /////////////////////////////////////////////////////////////
                //
                // Some formats supporting subdatasets may have no
                // subdatasets.
                // As an instance, the HDF4ImageReader may read HDF4Images
                // which are single datasets containing no subdatasets.
                // Thus, theDataset is simply the main dataset.
                //
                // /////////////////////////////////////////////////////////////
                if (nSubdatasets == 0) {
                    nSubdatasets = 1;
                    datasetNames = new String[1];
                    datasetNames[0] = mainDatasetName;
                    final GDALCommonIIOImageMetadata myItem = createDatasetMetadata(datasetNames[0]);
                    datasetMap.put(datasetNames[0], myItem);
                } else {
                    datasetNames = new String[nSubdatasets + 1];
                    for (int i = 0; i < nSubdatasets; i++) {
                        final String subdatasetName = (subdatasets.get(i * 2))
                                .toString();
                        final int nameStartAt = subdatasetName
                                .lastIndexOf("_NAME=") + 6;
                        datasetNames[i] = subdatasetName.substring(nameStartAt);
                    }
                    datasetNames[nSubdatasets] = mainDatasetName;
                    datasetMap.put(mainDatasetName, createDatasetMetadata(
                            mainDataset, mainDatasetName));
                    subdatasets.clear();
                }
                isInitialized = true;
                GDALUtilities.closeDataSet(mainDataset);
            }
        }
        return nSubdatasets > 0;
    }

    /**
     * Build a proper {@link GDALCommonIIOImageMetadata} given the name of a
     * dataset. The default implementation return a
     * {@link GDALCommonIIOImageMetadata} instance.This method should be
     * overridden by the specialized {@link GDALImageReader} in case you need to
     * obtain a specific {@link GDALCommonIIOImageMetadata}'s subclass
     * 
     * @param datasetName
     *                the name of the dataset
     */
    protected GDALCommonIIOImageMetadata createDatasetMetadata(
            String datasetName) {
        return new GDALCommonIIOImageMetadata(datasetName);
    }

    /**
     * Build a proper {@link GDALCommonIIOImageMetadata} given an input dataset
     * as well as the file name containing such a dataset.
     */
    protected GDALCommonIIOImageMetadata createDatasetMetadata(
            Dataset mainDataset, String mainDatasetFileName) {
        return new GDALCommonIIOImageMetadata(mainDataset, mainDatasetFileName,
                false);
    }

    /**
     * Read data from the required region of the raster.
     * 
     * @param item
     *                a <code>GDALCommonIIOImageMetadata</code> related to the
     *                actual dataset
     * @param srcRegion
     *                the source Region to be read
     * @param dstRegion
     *                the destination Region of the image read
     * @param selectedBands
     *                an array specifying the requested bands
     * @return the read <code>Raster</code>
     */
    private Raster readDatasetRaster(GDALCommonIIOImageMetadata item,
            Rectangle srcRegion, Rectangle dstRegion, int[] selectedBands)
            throws IOException {
        return readDatasetRaster(item, srcRegion, dstRegion, selectedBands,
                null);
    }

    /**
     * Read data from the required region of the raster.
     * 
     * @param item
     *                a <code>GDALCommonIIOImageMetadata</code> related to the
     *                actual dataset
     * @param srcRegion
     *                the source Region to be read
     * @param dstRegion
     *                the destination Region of the image read
     * @param selectedBands
     *                an array specifying the requested bands
     * @return the read <code>Raster</code>
     */
    private Raster readDatasetRaster(GDALCommonIIOImageMetadata item,
            Rectangle srcRegion, Rectangle dstRegion, int[] selectedBands,
            SampleModel destSampleModel) throws IOException {

        SampleModel destSm = destSampleModel != null ? destSampleModel : item
                .getSampleModel();

        SampleModel sampleModel = null;
        DataBuffer imgBuffer = null;

        // ////////////////////////////////////////////////////////////////////
        //
        // -------------------------------------------------------------------
        // Raster Creation >>> Step 1: Initialization
        // -------------------------------------------------------------------
        //
        // ////////////////////////////////////////////////////////////////////
        final String datasetName = item.getDatasetName();
        final Dataset dataset = GDALUtilities.acquireDataSet(datasetName,
                gdalconst.GA_ReadOnly);

        if (dataset == null)
            throw new IOException("Error while acquiring the input dataset "
                    + datasetName);

        int dstWidth = dstRegion.width;
        int dstHeight = dstRegion.height;
        int srcRegionXOffset = srcRegion.x;
        int srcRegionYOffset = srcRegion.y;
        int srcRegionWidth = srcRegion.width;
        int srcRegionHeight = srcRegion.height;

        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine("SourceRegion = " + srcRegion.toString());

        // Band set-up
        Band pBand = null;

        // Getting number of bands
        final int nBands = selectedBands != null ? selectedBands.length
                : destSm.getNumBands();

        int[] banks = new int[nBands];
        int[] offsets = new int[nBands];

        // setting the number of pixels to read
        final int pixels = dstWidth * dstHeight;
        int bufferType = 0, bufferSize = 0;

        // ////////////////////////////////////////////////////////////////////
        //
        // -------------------------------------------------------------------
        // Raster Creation >>> Step 2: Data Read
        // -------------------------------------------------------------------
        //
        // ////////////////////////////////////////////////////////////////////

        // NOTE: Bands are not 0-base indexed, so we must add 1
        pBand = dataset.GetRasterBand(1);

        // setting buffer properties
        bufferType = pBand.getDataType();
        final int typeSizeInBytes = gdal.GetDataTypeSize(bufferType) / 8;
        bufferSize = nBands * pixels * typeSizeInBytes;

        // splitBands = false -> I read n Bands at once.
        // splitBands = false -> I need to read 1 Band at a time.
        boolean splitBands = false;

        if (bufferSize < 0 || destSm instanceof BandedSampleModel) {
            // The number resulting from the product
            // "numBands*pixels*gdal.GetDataTypeSize(buf_type) / 8"
            // may be negative (A very high number which is not
            // "int representable")
            // In such a case, we will read 1 band at a time.
            bufferSize = pixels * typeSizeInBytes;
            splitBands = true;
        }
        int dataBufferType = -1;
        ByteBuffer[] bands = new ByteBuffer[nBands];
        for (int k = 0; k < nBands; k++) {

            // If I'm reading n Bands at once and I performed the first read,
            // I quit the loop
            if (k > 0 && !splitBands)
                break;

            final ByteBuffer dataBuffer = ByteBuffer.allocateDirect(bufferSize);

            final int returnVal;
            if (!splitBands) {
                // I can read nBands at once.
                if (selectedBands != null) {
                    final int bandsMap[] = new int[nBands];
                    for (int i = 0; i < nBands; i++)
                        bandsMap[i] = selectedBands[i] + 1;
                    returnVal = dataset.ReadRaster_Direct(srcRegionXOffset,
                            srcRegionYOffset, srcRegionWidth, srcRegionHeight,
                            dstWidth, dstHeight, bufferType, nBands, bandsMap,
                            nBands * typeSizeInBytes, dstWidth * nBands
                                    * typeSizeInBytes, typeSizeInBytes,
                            dataBuffer);
                } else {
                    returnVal = dataset.ReadRaster_Direct(srcRegionXOffset,
                            srcRegionYOffset, srcRegionWidth, srcRegionHeight,
                            dstWidth, dstHeight, bufferType, nBands, nBands
                                    * typeSizeInBytes, dstWidth * nBands
                                    * typeSizeInBytes, typeSizeInBytes,
                            dataBuffer);
                }
                bands[k] = dataBuffer;
            } else {
                // I need to read 1 band at a time.
                returnVal = dataset.GetRasterBand(k + 1).ReadRaster_Direct(
                        srcRegionXOffset, srcRegionYOffset, srcRegionWidth,
                        srcRegionHeight, dstWidth, dstHeight, bufferType,
                        dataBuffer);
                bands[k] = dataBuffer;
            }
            if (returnVal == gdalconstConstants.CE_None) {
                if (!splitBands)
                    for (int band = 0; band < nBands; band++) {
                        banks[band] = band;
                        offsets[band] = band;
                    }
                else {
                    banks[k] = k;
                    offsets[k] = 0;
                }
            } else {
                // The read operation was not successfully computed.
                // Showing error messages.
                LOGGER.info(new StringBuffer("Last error: ").append(
                        gdal.GetLastErrorMsg()).toString());
                LOGGER.info(new StringBuffer("Last error number: ").append(
                        gdal.GetLastErrorNo()).toString());
                LOGGER.info(new StringBuffer("Last error type: ").append(
                        gdal.GetLastErrorType()).toString());
                GDALUtilities.closeDataSet(dataset);
                throw new RuntimeException(gdal.GetLastErrorMsg());
            }
        }

        // ////////////////////////////////////////////////////////////////////
        //
        // -------------------------------------------------------------------
        // Raster Creation >>> Step 3: Setting DataBuffer
        // -------------------------------------------------------------------
        //
        // ////////////////////////////////////////////////////////////////////

        // /////////////////////////////////////////////////////////////////////
        //
        // TYPE BYTE
        //
        // /////////////////////////////////////////////////////////////////////
        if (bufferType == gdalconstConstants.GDT_Byte) {
            if (!splitBands) {
                final byte[] bytes = new byte[nBands * pixels];
                bands[0].get(bytes, 0, nBands * pixels);
                imgBuffer = new DataBufferByte(bytes, nBands * pixels);
            } else {
                final byte[][] bytes = new byte[nBands][];
                for (int i = 0; i < nBands; i++) {
                    bytes[i] = new byte[pixels];
                    bands[i].get(bytes[i], 0, pixels);
                }
                imgBuffer = new DataBufferByte(bytes, pixels);
            }
            dataBufferType = DataBuffer.TYPE_BYTE;
        } else if (bufferType == gdalconstConstants.GDT_Int16
                || bufferType == gdalconstConstants.GDT_UInt16) {
            // ////////////////////////////////////////////////////////////////
            //
            // TYPE SHORT
            //
            // ////////////////////////////////////////////////////////////////

            if (!splitBands) {
                // I get short values from the ByteBuffer using a view
                // of the ByteBuffer as a ShortBuffer
                // It is worth to create the view outside the loop.
                short[] shorts = new short[nBands * pixels];
                bands[0].order(ByteOrder.nativeOrder());
                final ShortBuffer buff = bands[0].asShortBuffer();
                buff.get(shorts, 0, nBands * pixels);
                if (bufferType == gdalconstConstants.GDT_Int16)
                    imgBuffer = new DataBufferShort(shorts, nBands * pixels);
                else
                    imgBuffer = new DataBufferUShort(shorts, nBands * pixels);
            } else {
                short[][] shorts = new short[nBands][];
                for (int i = 0; i < nBands; i++) {
                    shorts[i] = new short[pixels];
                    bands[i].order(ByteOrder.nativeOrder());
                    bands[i].asShortBuffer().get(shorts[i], 0, pixels);
                }
                if (bufferType == gdalconstConstants.GDT_Int16)
                    imgBuffer = new DataBufferShort(shorts, pixels);
                else
                    imgBuffer = new DataBufferUShort(shorts, pixels);
            }
            if (bufferType == gdalconstConstants.GDT_UInt16)
                dataBufferType = DataBuffer.TYPE_USHORT;
            else
                dataBufferType = DataBuffer.TYPE_SHORT;
        } else if (bufferType == gdalconstConstants.GDT_Int32
                || bufferType == gdalconstConstants.GDT_UInt32) {
            // ////////////////////////////////////////////////////////////////
            //
            // TYPE INT
            //
            // ////////////////////////////////////////////////////////////////

            if (!splitBands) {
                // I get int values from the ByteBuffer using a view
                // of the ByteBuffer as an IntBuffer
                // It is worth to create the view outside the loop.
                int[] ints = new int[nBands * pixels];
                bands[0].order(ByteOrder.nativeOrder());
                final IntBuffer buff = bands[0].asIntBuffer();
                buff.get(ints, 0, nBands * pixels);
                imgBuffer = new DataBufferInt(ints, nBands * pixels);
            } else {
                int[][] ints = new int[nBands][];
                for (int i = 0; i < nBands; i++) {
                    ints[i] = new int[pixels];
                    bands[i].order(ByteOrder.nativeOrder());
                    bands[i].asIntBuffer().get(ints[i], 0, pixels);
                }
                imgBuffer = new DataBufferInt(ints, pixels);
            }
            dataBufferType = DataBuffer.TYPE_INT;

        } else if (bufferType == gdalconstConstants.GDT_Float32) {
            // /////////////////////////////////////////////////////////////////////
            //
            // TYPE FLOAT
            //
            // /////////////////////////////////////////////////////////////////////

            if (!splitBands) {
                // I get float values from the ByteBuffer using a view
                // of the ByteBuffer as a FloatBuffer
                // It is worth to create the view outside the loop.
                float[] floats = new float[nBands * pixels];
                bands[0].order(ByteOrder.nativeOrder());
                final FloatBuffer buff = bands[0].asFloatBuffer();
                buff.get(floats, 0, nBands * pixels);
                imgBuffer = new DataBufferFloat(floats, nBands * pixels);
            } else {
                float[][] floats = new float[nBands][];
                for (int i = 0; i < nBands; i++) {
                    floats[i] = new float[pixels];
                    bands[i].order(ByteOrder.nativeOrder());
                    bands[i].asFloatBuffer().get(floats[i], 0, pixels);
                }
                imgBuffer = new DataBufferFloat(floats, pixels);
            }
            dataBufferType = DataBuffer.TYPE_FLOAT;
        } else if (bufferType == gdalconstConstants.GDT_Float64) {
            // /////////////////////////////////////////////////////////////////////
            //
            // TYPE DOUBLE
            //
            // /////////////////////////////////////////////////////////////////////

            if (!splitBands) {
                // I get double values from the ByteBuffer using a view
                // of the ByteBuffer as a DoubleBuffer
                // It is worth to create the view outside the loop.
                double[] doubles = new double[nBands * pixels];
                bands[0].order(ByteOrder.nativeOrder());
                final DoubleBuffer buff = bands[0].asDoubleBuffer();
                buff.get(doubles, 0, nBands * pixels);
                imgBuffer = new DataBufferDouble(doubles, nBands * pixels);
            } else {
                double[][] doubles = new double[nBands][];
                for (int i = 0; i < nBands; i++) {
                    doubles[i] = new double[pixels];
                    bands[i].order(ByteOrder.nativeOrder());
                    bands[i].asDoubleBuffer().get(doubles[i], 0, pixels);
                }
                imgBuffer = new DataBufferDouble(doubles, pixels);
            }
            dataBufferType = DataBuffer.TYPE_DOUBLE;

        } else {
            // TODO: Handle more cases if needed. Show the name of the type
            // instead of the numeric value.
            LOGGER.info("The specified data type is actually unsupported: "
                    + bufferType);
        }

        // ////////////////////////////////////////////////////////////////////
        //
        // -------------------------------------------------------------------
        // Raster Creation >>> Step 4: Setting SampleModel
        // -------------------------------------------------------------------
        //
        // ////////////////////////////////////////////////////////////////////
        // TODO: Fix this in compliance with the specified destSampleModel
        if (splitBands)
            sampleModel = new BandedSampleModel(dataBufferType, dstWidth,
                    dstHeight, dstWidth, banks, offsets);
        else
            sampleModel = new PixelInterleavedSampleModel(dataBufferType,
                    dstWidth, dstHeight, nBands, dstWidth * nBands, offsets);

        // ////////////////////////////////////////////////////////////////////
        //
        // -------------------------------------------------------------------
        // Raster Creation >>> Final Step: Actual Raster Creation
        // -------------------------------------------------------------------
        //
        // ////////////////////////////////////////////////////////////////////
        GDALUtilities.closeDataSet(dataset);
        // return Raster.createWritableRaster(sampleModel, imgBuffer, new Point(
        // dstRegion.x, dstRegion.y));
        return Raster.createWritableRaster(sampleModel, imgBuffer, null);
    }

    /**
     * Tries to retrieve the Dataset Source for the ImageReader's input.
     */
    protected File getDatasetSource(Object myInput) {
        if (datasetSource == null) {
            if (myInput instanceof File)
                datasetSource = (File) myInput;
            else if (myInput instanceof FileImageInputStreamExt)
                datasetSource = ((FileImageInputStreamExt) myInput).getFile();
            else if (input instanceof URL) {
                final URL tempURL = (URL) input;
                if (tempURL.getProtocol().equalsIgnoreCase("file")) {
                    try {
                        datasetSource = new File(URLDecoder.decode(tempURL
                                .getFile(), "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        throw new RuntimeException("Not a Valid Input ", e);
                    }
                }
            } else
                // should never happen
                throw new RuntimeException(
                        "Unable to retrieve the Data Source for"
                                + " the provided input");
        }
        return datasetSource;
    }

    /**
     * Sets the input for the specialized reader.
     * 
     * @throws IllegalArgumentException
     *                 if the provided input is <code>null</code>
     */
    public void setInput(Object input, boolean seekForwardOnly,
            boolean ignoreMetadata) {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine("Setting Input");

        // Prior to set a new input, I need to do a pre-emptive reset in order
        // to clear any value-object which was related to the previous input.
        if (this.imageInputStream != null) {
            reset();
            imageInputStream = null;
        }

        if (input == null)
            throw new IllegalArgumentException("The provided input is null!");

        // //
        //
        // File input
        //
        // //
        if (input instanceof File) {
            datasetSource = (File) input;
            try {
                imageInputStream = ImageIO.createImageInputStream(input);
            } catch (IOException e) {
                throw new RuntimeException(
                        "Failed to create a valid input stream ", e);
            }
        }
        // //
        //
        // FileImageInputStreamExt input
        //
        // //
        else if (input instanceof FileImageInputStreamExt) {
            datasetSource = ((FileImageInputStreamExt) input).getFile();
            imageInputStream = (ImageInputStream) input;
        }
        // //
        //
        // URL input
        //
        // //
        else if (input instanceof URL) {
            final URL tempURL = (URL) input;
            if (tempURL.getProtocol().equalsIgnoreCase("file")) {

                try {
                    datasetSource = new File(URLDecoder.decode(tempURL
                            .getFile(), "UTF-8"));
                    imageInputStream = ImageIO.createImageInputStream(input);
                } catch (IOException e) {
                    throw new RuntimeException(
                            "Failed to create a valid input stream ", e);
                }
            }
        }
        else if (input instanceof URIImageInputStream){
            imageInputStream = (URIImageInputStream)input;
            datasetSource = null;
            uriSource = ((URIImageInputStream)input).getUri();
            
        }

        // /////////////////////////////////////////////////////////////////////
        //
        // Checking if this input is of a supported format.
        // Now, I have an ImageInputStream and I can try to see if the input's
        // format is supported by the specialized reader
        //
        // /////////////////////////////////////////////////////////////////////
        boolean isInputDecodable = false;
        if (imageInputStream != null) {
            Dataset dataSet = null;
            if (datasetSource != null){
                dataSet = GDALUtilities.acquireDataSet(datasetSource
                        .getAbsolutePath(), gdalconstConstants.GA_ReadOnly);    
            }else if (uriSource != null){
                final String urisource = uriSource.toString();
                dataSet = GDALUtilities.acquireDataSet(urisource, gdalconstConstants.GA_ReadOnly);    
            }
            
            if (dataSet != null) {
                isInputDecodable = ((GDALImageReaderSpi) this
                        .getOriginatingProvider()).isDecodable(dataSet);
                GDALUtilities.closeDataSet(dataSet);
            } else
                isInputDecodable = false;
        }
        if (isInputDecodable)
            super.setInput(imageInputStream, seekForwardOnly, ignoreMetadata);
        else {
            StringBuffer sb = new StringBuffer();
            if (imageInputStream == null)
                sb.append(
                        "Unable to create a valid ImageInputStream "
                                + "for the provided input:").append(
                        GDALUtilities.NEWLINE).append(input.toString());
            else
                sb.append("The Provided input is not supported by this reader");
            throw new RuntimeException(sb.toString());
        }
    }

    /**
     * Allows resources to be released
     */
    public synchronized void dispose() {
        super.dispose();
        synchronized (datasetMap) {
            // Closing imageInputStream
            if (imageInputStream != null)
                try {
                    imageInputStream.close();
                } catch (IOException ioe) {

                }
            imageInputStream = null;
            
            // Cleaning HashMap
            datasetMap.clear();
            datasetNames = null;
        }
    }

    /**
     * Reset main values
     */
    public synchronized void reset() {
        super.setInput(null, false, false);
        dispose();
        isInitialized = false;
        nSubdatasets = -1;
    }

    /**
     * Returns an <code>Iterator</code> containing possible image types to
     * which the given image may be decoded, in the form of
     * <code>ImageTypeSpecifiers</code>s. At least one legal image type will
     * be returned. This implementation simply returns an
     * <code>ImageTypeSpecifier</code> set in compliance with the property of
     * the dataset contained within the underlying data source.
     * 
     * @param imageIndex
     *                the index of the image to be retrieved.
     * 
     * @return an <code>Iterator</code> containing possible image types to
     *         which the given image may be decoded, in the form of
     *         <code>ImageTypeSpecifiers</code>s
     */
    public Iterator getImageTypes(int imageIndex) throws IOException {
        final List l = new java.util.ArrayList(4);
        final GDALCommonIIOImageMetadata item = getDatasetMetadata(imageIndex);
        imageType = new ImageTypeSpecifier(item.getColorModel(), item
                .getSampleModel());
        l.add(imageType);
        return l.iterator();
    }

    /**
     * Read the raster and returns a <code>BufferedImage</code>
     * 
     * @param imageIndex
     *                the index of the image to be retrieved.
     * @param param
     *                an <code>ImageReadParam</code> used to control the
     *                reading process, or <code>null</code>. Actually,
     *                setting a destinationType allows to specify the number of
     *                bands in the destination image.
     * 
     * @return the desired portion of the image as a <code>BufferedImage</code>
     * @throws IllegalArgumentException
     *                 if <code>param</code> contains an invalid specification
     *                 of a source and/or destination band subset or of a
     *                 destination image.
     * @throws IOException
     *                 if an error occurs when acquiring access to the
     *                 underlying datasource
     */
    public BufferedImage read(int imageIndex, ImageReadParam param)
            throws IOException {

        // //
        //
        // Retrieving the requested dataset
        //
        // //
        final GDALCommonIIOImageMetadata item = getDatasetMetadata(imageIndex);
        final int width = item.getWidth();
        final int height = item.getHeight();
        final SampleModel itemSampleModel = item.getSampleModel();
        int itemNBands = itemSampleModel.getNumBands();
        int nDestBands;

        BufferedImage bi = null;
        final ImageReadParam imageReadParam;
        if (param == null)
            imageReadParam = getDefaultReadParam();
        else
            imageReadParam = param;

        // //
        //
        // First, check for a specified ImageTypeSpecifier
        //
        // //
        ImageTypeSpecifier imageType = imageReadParam.getDestinationType();
        SampleModel destSampleModel = null;
        if (imageType != null) {
            destSampleModel = imageType.getSampleModel();
            nDestBands = destSampleModel.getNumBands();
        } else {
            bi = imageReadParam.getDestination();
            if (bi != null)
                nDestBands = bi.getSampleModel().getNumBands();
            else
                nDestBands = itemNBands;
        }

        // //
        //
        // Second, bands settings check
        //
        // //
        checkReadParamBandSettings(imageReadParam, itemNBands, nDestBands);
        int[] srcBands = imageReadParam.getSourceBands();
        int[] destBands = imageReadParam.getDestinationBands();

        // //
        //
        // Third, destination image check
        //
        // //
        // if (bi != null && imageType == null) {
        // if ((srcBands == null) && (destBands == null)) {
        // SampleModel biSampleModel = bi.getSampleModel();
        // if (!bi.getColorModel().equals(item.getColorModel())
        // || biSampleModel.getDataType() != itemSampleModel
        // .getDataType())
        // throw new IllegalArgumentException(
        // "Provided destination image does not have a valid ColorModel or
        // SampleModel");
        // }
        // }

        // //
        //
        // Computing regions of interest
        //
        // //
        Rectangle srcRegion = new Rectangle(0, 0, 0, 0);
        Rectangle destRegion = new Rectangle(0, 0, 0, 0);
        computeRegions(imageReadParam, width, height, bi, srcRegion, destRegion);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Source Region = " + srcRegion.toString());
            LOGGER.fine("Destination Region = " + destRegion.toString());
        }
        // ////////////////////////////////////////////////////////////////////
        // 
        // Getting data
        //
        // ////////////////////////////////////////////////////////////////////
        if (bi == null) {
            // //
            //
            // No destination image has been specified.
            // Creating a new BufferedImage
            //			
            // //
            ColorModel cm;
            if (imageType == null) {
                cm = item.getColorModel();
                bi = new BufferedImage(cm, (WritableRaster) readDatasetRaster(
                        item, srcRegion, destRegion, srcBands), false, null);
            } else {
                cm = imageType.getColorModel();
                bi = new BufferedImage(cm,
                        (WritableRaster) readDatasetRaster(item, srcRegion,
                                destRegion, srcBands, destSampleModel), false,
                        null);
            }

        } else {
            // //
            //			
            // the destination image has been specified.
            //			
            // //
            // Rectangle destSize = (Rectangle) destRegion.clone();
            // destSize.setLocation(0, 0);

            Raster readRaster = readDatasetRaster(item, srcRegion, destRegion,
                    srcBands);
            WritableRaster raster = bi.getRaster().createWritableChild(0, 0,
                    bi.getWidth(), bi.getHeight(), 0, 0, null);
            // TODO: Work directly on a Databuffer avoiding setRect?
            raster.setRect(destRegion.x, destRegion.y, readRaster);

            // Raster readRaster = readDatasetRaster(item, srcRegion,
            // destRegion,
            // srcBands);
            // WritableRaster raster = bi.getRaster().createWritableChild(
            // destRegion.x, destRegion.y, destRegion.width,
            // destRegion.height, destRegion.x, destRegion.y, null);
            // //TODO: Work directly on a Databuffer avoiding setRect?
            // raster.setRect(readRaster);
        }
        return bi;
    }

    /**
     * Implements the <code>ImageRead.readRaster</code> method which returns a
     * new <code>Raster</code> object containing the raw pixel data from the
     * image stream, without any color conversion applied.
     * 
     * @param imageIndex
     *                the index of the image to be retrieved.
     * @param param
     *                an <code>ImageReadParam</code> used to control the
     *                reading process, or <code>null</code>.
     * @return the desired portion of the image as a <code>Raster</code>.
     */
    public Raster readRaster(int imageIndex, ImageReadParam param)
            throws IOException {
        return read(imageIndex, param).getData();
    }

    /**
     * Performs a full read operation.
     * 
     * @param imageIndex
     *                the index of the image to be retrieved.
     */
    public BufferedImage read(int imageIndex) throws IOException {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine("read(imageIndex)");
        return read(imageIndex, null);

    }

    /**
     * Returns the number of images (subdatasets) contained within the data
     * source. If there are no subdatasets, it returns 1.
     */
    public int getNumImages(boolean allowSearch) throws IOException {
        if (LOGGER.isLoggable(Level.FINE))
            LOGGER.fine("getting NumImages");
        initialize();
        return nSubdatasets;
    }

    /**
     * Returns the width of the raster of the <code>Dataset</code> at index
     * <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified raster
     * @return raster width
     */
    public int getWidth(int imageIndex) throws IOException {
        return getDatasetMetadata(imageIndex).getWidth();

    }

    /**
     * Returns the height of the raster of the <code>Dataset</code> at index
     * <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified raster
     * @return raster height
     */
    public int getHeight(int imageIndex) throws IOException {
        return getDatasetMetadata(imageIndex).getHeight();
    }

    /**
     * Returns the tile height of the raster of the <code>Dataset</code> at
     * index <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified raster
     * @return raster tile height
     */

    public int getTileHeight(int imageIndex) throws IOException {
        return getDatasetMetadata(imageIndex).getTileHeight();
    }

    /**
     * Returns the tile width of the raster of the <code>Dataset</code> at
     * index <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified raster
     * @return raster tile width
     */

    public int getTileWidth(int imageIndex) throws IOException {
        return getDatasetMetadata(imageIndex).getTileWidth();
    }

    // ////////////////////////////////////////////////////////////////////////
    //
    // CRS Retrieval METHODS
    //
    // ///////////////////////////////////////////////////////////////////////

    /**
     * Retrieves the WKT projection <code>String</code> for the
     * <code>Dataset</code> at index <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the dataset we want to get the projections
     *                for.
     * @return the WKT projection <code>String</code> for the
     *         <code>Dataset</code> at index <code>imageIndex</code>.
     */
    public String getProjection(final int imageIndex) {
        return getDatasetMetadata(imageIndex).getProjection();
    }

    /**
     * Retrieves the GeoTransformation coefficients for the <code>Dataset</code>
     * at index <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the dataset we want to get the coefficients
     *                for.
     * @return the array containing the GeoTransformation coefficients.
     */
    public double[] getGeoTransform(final int imageIndex) {
        checkImageIndex(imageIndex);
        return getDatasetMetadata(imageIndex).getGeoTransformation();
    }

    /**
     * Returns Ground Control Points of the <code>Dataset</code> at index
     * <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified <code>Dataset</code>
     * 
     * @return a <code>List</code> containing the Ground Control Points.
     * 
     */
    public List getGCPs(final int imageIndex) {
        checkImageIndex(imageIndex);
        return getDatasetMetadata(imageIndex).getGcps();
    }

    /**
     * Returns the Ground Control Points projection definition string of the
     * <code>Dataset</code> at index <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified <code>Dataset</code>
     * 
     * @return the Ground Control Points projection definition string.
     */
    public String getGCPProjection(final int imageIndex) {
        checkImageIndex(imageIndex);
        return getDatasetMetadata(imageIndex).getGcpProjection();
    }

    /**
     * Returns the number of Ground Control Points of the <code>Dataset</code>
     * at index imageIndex.
     * 
     * @param imageIndex
     *                the index of the specified <code>Dataset</code>
     * 
     * @return the number of GroundControlPoints of the <code>Dataset</code>.
     */
    public int getGCPCount(final int imageIndex) {
        checkImageIndex(imageIndex);
        return getDatasetMetadata(imageIndex).getGcpNumber();
    }

    // ///////////////////////////////////////////////////////////////////
    //
    // Raster Band Properties Retrieval METHODS
    //
    // ///////////////////////////////////////////////////////////////////
    /**
     * Returns the NoDataValue of the specified Band of the specified image
     * 
     * @param imageIndex
     *                the specified image
     * @param band
     *                the specified band
     * @return the Band NoDataValue if available
     * @throws IllegalArgumentException
     *                 in case the specified band number is out of range or
     *                 noData value has not been found
     */
    public double getNoDataValue(int imageIndex, int band) {
        final GDALCommonIIOImageMetadata item = getDatasetMetadata(imageIndex);
        return item.getNoDataValue(band);
    }

    /**
     * Returns the optional Offset Value of the specified band of the
     * <code>Dataset</code> at index <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified <code>Dataset</code>
     * @param band
     *                the specified band
     * @return the Band Offset Value if available
     * @throws IllegalArgumentException
     *                 in case the specified band number is out of range or
     *                 Offset value has not been found
     */
    public double getOffset(int imageIndex, int band) {
        final GDALCommonIIOImageMetadata item = getDatasetMetadata(imageIndex);
        return item.getOffset(band);
    }

    /**
     * Returns the optional Scale Value of the specified band of the
     * <code>Dataset</code> at index <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified <code>Dataset</code>
     * @param band
     *                the specified band
     * @return the Band Scale Value if available
     * @throws IllegalArgumentException
     *                 in case the specified band number is out of range or
     *                 scale value has not been found
     */
    public double getScale(int imageIndex, int band) {
        final GDALCommonIIOImageMetadata item = getDatasetMetadata(imageIndex);
        return item.getScale(band);
    }

    /**
     * Returns the optional Minimum Value of the specified band of the
     * <code>Dataset</code> at index <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified <code>Dataset</code>
     * @param band
     *                the specified band
     * @return the Band Minimum Value if available
     * @throws IllegalArgumentException
     *                 in case the specified band number is out of range or
     *                 minimum value has not been found
     */
    public double getMinimum(int imageIndex, int band) {
        final GDALCommonIIOImageMetadata item = getDatasetMetadata(imageIndex);
        return item.getMinimum(band);
    }

    /**
     * Returns the optional Maximum Value of the specified band of the
     * <code>Dataset</code> at index <code>imageIndex</code>.
     * 
     * @param imageIndex
     *                the index of the specified <code>Dataset</code>
     * @param band
     *                the specified band
     * @return the Band Maximum Value if available
     * @throws IllegalArgumentException
     *                 in case the specified band number is out of range or
     *                 maximum value has not been found
     */
    public double getMaximum(int imageIndex, int band) {
        final GDALCommonIIOImageMetadata item = getDatasetMetadata(imageIndex);
        return item.getMaximum(band);
    }

    /**
     * Returns an <code>IIOMetadata</code> object representing the metadata
     * associated with the input source as a whole.
     * 
     * @return an <code>IIOMetadata</code> object.
     */
    public IIOMetadata getStreamMetadata() throws IOException {
        initialize();
        return new GDALCommonIIOStreamMetadata(datasetNames);
    }

    /**
     * Returns an <code>IIOMetadata</code> object containing metadata
     * associated with the given image, specified by the <code>imageIndex</code>
     * parameter
     * 
     * @param imageIndex
     *                the index of the required image
     * @return an <code>IIOMetadata</code> object
     */
    public IIOMetadata getImageMetadata(int imageIndex) throws IOException {
        return getDatasetMetadata(imageIndex);
    }

}
