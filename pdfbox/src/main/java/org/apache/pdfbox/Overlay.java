/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pdfbox;

import java.awt.geom.AffineTransform;
import java.io.*;
import java.util.*;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.exceptions.COSVisitorException;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdfwriter.COSWriter;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;


import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.common.DualCOSObjectable;
import org.apache.pdfbox.util.PDFOperator;
import org.apache.pdfbox.util.PDFStreamProcessor;
import org.apache.pdfbox.util.PDFStreamProcessor.OperatorHandler;

/**
 * Overlay on document with another one.<br>
 * e.g. Overlay an invoice with your company layout<br>
 * <br>
 * How it (should) work:<br>
 * If the document has 10 pages, and the layout 2 the following is the result:<br>
 * <pre>
 * Document: 1234567890
 * Layout  : 1212121212
 * </pre>
 * <br>
 *
 * @author Mario Ivankovits (mario@ops.co.at)
 * @author <a href="ben@benlitchfield.com">Ben Litchfield</a>
 *
 * @version $Revision: 1.7 $
 */
public class Overlay
{
    /**
     * @deprecated use the {@link COSName#XOBJECT} constant instead
     */
    public static final COSName XOBJECT = COSName.XOBJECT;

    /**
     * @deprecated use the {@link COSName#PROC_SET} constant instead
     */
    public static final COSName PROC_SET = COSName.PROC_SET;

    /**
     * @deprecated use the {@link COSName#EXT_G_STATE} constant instead
     */
    public static final COSName EXT_G_STATE = COSName.EXT_G_STATE;

    private List overlayPages = new ArrayList(10);

    private PDDocument overlayDocument;
    private PDDocument targetDocument;
    private COSStream saveGraphicsStateStream, restoreGraphicsStateStream, transformCoordsStream;
    // Counter used to generate unique(ish) names.
    private int nameIncrementCounter;
    
    /**
     * This will overlay a document and write out the results.<br/><br/>
     *
     * usage: java org.apache.pdfbox.Overlay &lt;overlay.pdf&gt; &lt;document.pdf&gt; &lt;result.pdf&gt;
     *
     * @param args The command line arguments.
     *
     * @throws IOException If there is an error reading/writing the document.
     * @throws COSVisitorException If there is an error writing the document.
     */
    public static void main( String[] args ) throws IOException, COSVisitorException
    {
        if( args.length < 3 )
        {
            usage();
            System.exit(1);
        }
        
        double  tx = 0d, ty = 0d,
                xscale = 1d, yscale = 1d,
                rotate = 0d;
        
        for (int i = 0; i < args.length - 3; i++) {
            String opt = args[i], optVal = null;
            if (!opt.startsWith(("--"))) {
                System.err.println("Too many non-option arguments");
                usage();
                System.exit(1);
            }
            int separator = opt.indexOf('=');
            if (separator > 0) {
                optVal = opt.substring(separator+1, opt.length());
                opt = opt.substring(2, separator);
            }
            try {
                if (opt.equals("x")) {
                    tx = Double.parseDouble(optVal);
                } else if (opt.equals("y")) {
                    ty = Double.parseDouble(optVal);
                } else if (opt.equals("xs")) {
                    xscale = Double.parseDouble(optVal);
                } else if (opt.equals("ys")) {
                    yscale = Double.parseDouble(optVal);
                } else if (opt.equals("r")) {
                    rotate = Double.parseDouble(optVal);
                } else {
                    System.err.println("Unknown command-line option --" + opt);
                    usage();
                    System.exit(1);
                }
            } catch (NullPointerException ex) {
                System.err.println("Option " + opt + " requires a value after the = sign");
                usage();
                System.exit(1);
            } catch (NumberFormatException ex) {
                System.err.println("Option " + opt + ": could not parse number " + optVal + ": " + ex);
                usage();
                System.exit(1);
            }
        }
        if (xscale == 0 || yscale == 0) {
            System.err.println("WARNING: X and/or Y scaling factors are zero. Overlay will be invisible.");            
        }
        AffineTransform transform = AffineTransform.getTranslateInstance(tx, ty);
        transform.concatenate(AffineTransform.getRotateInstance(- Math.toRadians(rotate)));
        transform.concatenate(AffineTransform.getScaleInstance(xscale, yscale));
        
        String overlayFile = args[args.length - 3];
        String targetFile = args[args.length - 2];
        String outputFile = args[args.length - 1];
        
        // Now do the actual overlay work.
        PDDocument overlay = null;
        PDDocument pdf = null;

        try
        {
            overlay = getDocument( overlayFile );
            pdf = getDocument( targetFile );
            Overlay overlayer = new Overlay();
            overlayer.overlay( overlay, transform, pdf);
            writeDocument( pdf, outputFile );
        }
        finally
        {
            if( overlay != null )
            {
                overlay.close();
            }
            if( pdf != null )
            {
                pdf.close();
            }
        }
    }
            
    private static void writeDocument( PDDocument pdf, String filename ) throws IOException, COSVisitorException
    {
        FileOutputStream output = null;
        COSWriter writer = null;
        try
        {
            output = new FileOutputStream( filename );
            writer = new COSWriter( output );
            writer.write( pdf );
        }
        finally
        {
            if( writer != null )
            {
                writer.close();
            }
            if( output != null )
            {
                output.close();
            }
        }
    }

    private static PDDocument getDocument( String filename ) throws IOException
    {
        FileInputStream input = null;
        PDFParser parser = null;
        PDDocument result = null;
        try
        {
            input = new FileInputStream( filename );
            parser = new PDFParser( input );
            parser.parse();
            result = parser.getPDDocument();
        }
        finally
        {
            if( input != null )
            {
                input.close();
            }
        }
        return result;
    }

    private static void usage()
    {
        System.err.println("usage: java -jar pdfbox-app-x.y.z.jar Overlay [opts] <overlay.pdf> <document.pdf> <result.pdf>" );
        System.err.println("");
        System.err.println("\t--x=n  Offset of left edge of overlay from left edge of page.");
        System.err.println("\t--y=n  Offset of bottom edge of overlay from bottom edge of page.");
        System.err.println("\t--xs=n Horizontal scale factor, 1 (default) = no scaling");
        System.err.println("\t--ys=n Vertical scale factor, 1 (default) = no scaling");
        System.err.println("\t--r=n  Clockwise rotation.");
        System.err.println("");
        System.err.println("\tLengths are in target page units. Rotation is in degrees.");
        System.err.println("\tScale is a multiplier. Negative scale factors mirror that axis.");
    }

    /**
     * Struct-like holder for information about a page in the target
     * document, must not be exposed outside class.
     */
    private static class OverlayPage
    {
        private final COSArray contentsArray;
        private final COSDictionary res;
        private final Map<COSName,COSName> objectNameMap;

        /**
         * Constructor.
         *
         * @param contentsValue The contents.
         * @param resValue The resource dictionary
         * @param objectNameMapValue The map
         */
        public OverlayPage(COSArray contentsArray, COSDictionary resValue, Map<COSName,COSName> objectNameMapValue)
        {
            this.contentsArray = contentsArray;
            res = resValue;
            objectNameMap = objectNameMapValue;
        }
    }

    /**
     * As the three-argument form of overlay(...), but doesn't apply any transform
     * to the overlay.
     * 
     * @param overlay Document to overlay onto the destination
     * @param destination Document to apply overlay to
     * @return The destination PDF, same as argument passed in
     * @throws IOException If there's an error on access to inputs
     */
    public PDDocument overlay( PDDocument overlay, PDDocument destination) throws IOException
    {
        return overlay(overlay, null, destination);
    }

    /**
     * This will overlay two documents onto each other.  The overlay document is
     * repeatedly overlayed onto the destination document for every page in the
     * destination.
     *
     * @param overlay The document to copy onto the destination
     * @param transform An affine transform for translating/rotating/scaling the overlay, no-op if null
     * @param destination The file that the overlay should be placed on.
     *
     * @return The destination pdf, same as argument passed in.
     *
     * @throws IOException If there is an error accessing data.
     */
    public PDDocument overlay( PDDocument overlay, AffineTransform transform, PDDocument destination) throws IOException
    {        
        overlayDocument = overlay;
        targetDocument = destination;
        
        PDDocumentCatalog targetCatalog = targetDocument.getDocumentCatalog();
        Set<COSName> targetUsedNames = new HashSet<COSName>();
        resourceNamesUsedInPages(targetCatalog.getPages(), targetUsedNames);
        
        PDDocumentCatalog overlayCatalog = overlayDocument.getDocumentCatalog();
        collectOverlayPages( overlayCatalog.getAllPages(), targetUsedNames );
        
        saveGraphicsStateStream = makeStreamFromBytes(targetDocument, " q\n".getBytes("ISO-8859-1"));
        restoreGraphicsStateStream = makeStreamFromBytes(targetDocument, " Q\n".getBytes("ISO-8859-1"));
        if (transform != null) {
            transformCoordsStream = makeStreamFromBytes(targetDocument, affineTransformToCM(transform));
        }

        processTargetPages( targetCatalog.getAllPages() );

        return targetDocument;
    }
    
    /**
     * Recursively scan a page tree for names used in resource dictionaries,
     * adding each name to a set of known names.
     * 
     * @param pageTreeNode Node to start scanning at
     * @param names Set to add names to
     */
    private static void resourceNamesUsedInPages(PDPageNode pageTreeNode, Set<COSName> names) {
        List kids = pageTreeNode.getKids();
        Iterator it = kids.iterator();
        while (it.hasNext()) {
            Object kid = it.next();
            if (kid instanceof PDPage) {
                // Add names from the resources dict to the list of known names
                PDResources rsrcDict = ((PDPage)kid).getResources();
                if (rsrcDict != null) {
                    resourceNamesUsedInRsrcDict(rsrcDict.getCOSDictionary(), names);                    
                }
            } else if (kid instanceof PDPageNode) {
                // Add names in the rsrc dict to the lsit of known names
                PDResources rsrcDict = ((PDPage)kid).getResources();
                if (rsrcDict != null) {
                    resourceNamesUsedInRsrcDict(rsrcDict.getCOSDictionary(), names);                    
                }
                // .. and recurse depth-first down the tree
                resourceNamesUsedInPages((PDPageNode)kid, names);
            } else {
                throw new IllegalStateException("Got PDPageNode with a kid of type " + kid.getClass().getName() + " instead of PDPage or PDPageNode");
            }
        }
    }
    
    /**
    * Produce a set of resource names showing which resource names are
    * defined in a resources dictionary.
    * 
    * Only names in the /ExGState, /Font, /XObject, /Pattern, /Shading,
    * /ColorSpace and /Properties dictionaries are considered. There's no guarantee
    * that other resource dictionary entries are dictionaries of resource names.
    * 
    * @param resources Resources dictionary to scan for names
    * @param resourceNames Set of resource names already accumulated, or empty set. Not null.
    * @return Set of resource names found
    */
    private static void resourceNamesUsedInRsrcDict(COSDictionary resources, Set<COSName> resourceNames) {
        // Assemble a map of resource names to the dictionaries they appear in.
        // The info on where they appear is only for error reporting and debugging,
        // otherwise the HashMap is really just being used like a set.
        COSName[] rdicts = new COSName[]{ COSName.EXT_G_STATE, COSName.FONT, COSName.XOBJECT,
                                        COSName.PATTERN, COSName.COLORSPACE, COSName.SHADING, COSName.PROPERTIES};
        for (COSName n : rdicts) {
            COSDictionary rd = (COSDictionary) resources.getDictionaryObject(n);
            if (rd != null) {
                for (Map.Entry<COSName,COSBase> e : rd.entrySet()) {
                    resourceNames.add(e.getKey());
                }
            }
        }
    }
        
    // Produce a PDF stream containing the passed bytes. This is just a helper
    // method because we need to produce several small streams.
    private COSStream makeStreamFromBytes(PDDocument doc, byte[] streamBytes) throws IOException {
        COSStream st = new COSStream( doc.getDocument().getScratchFile() );
        OutputStream restoreStream = st.createUnfilteredStream();
        restoreStream.write(streamBytes);
        restoreStream.flush();
        return st;
    }
    
    // The PDF "cm" operator expects six numbers as arguments. These are the
    // usual affine transform values for scale/slant/translate, and are expected
    // by cm in the same order that they're emitted by Java's AffineTransform.
    private byte[] affineTransformToCM(AffineTransform transform) throws UnsupportedEncodingException {
        double[] matrix = new double[6];
        transform.getMatrix(matrix);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++){
            b.append(matrix[i]).append(' ');
        }
        b.append("cm\n");
        return b.toString().getBytes("ISO-8859-1");
    }

    /**
     * Iterate through the pages in the overlay document, creating an entry
     * in overlayPages containing the resources dict, content stream(s),
     * etc for each.
     * 
     * @param pages List of pages in the overlay PDF
     * @param targetUsedNames Set of names defined in any resources dict entry in the target document (not the overlay)
     * @throws IOException
     */
    private void collectOverlayPages( List pages, Set<COSName> targetUsedNames ) throws IOException
    {
        Iterator pagesIter = pages.iterator();
        while( pagesIter.hasNext() )
        {
            PDPage page = (PDPage)pagesIter.next();
            COSBase contents = page.getCOSDictionary().getDictionaryObject( COSName.CONTENTS );
            PDResources resources = page.findResources();
            Map<COSName,COSName> renameMap = makeRenameMap(resources.getCOSDictionary(), targetUsedNames);
            if( resources == null )
            {
                resources = new PDResources();
                page.setResources( resources );
            }
            COSDictionary res = resources.getCOSDictionary();

            // Collect the stream(s) to iterate over. Whether the original page
            // had a single stream or an array, we're going to produce an array.
            List<COSStream> streams = new ArrayList();
            if (contents instanceof COSArray) {
                for (COSBase stream : (COSArray)contents) {
                    streams.add((COSStream)stream);
                }
            } else if (contents instanceof COSStream) {
                streams.add((COSStream)contents);
            } else {
                throw new IllegalArgumentException("Unexpected page /Contents entry not stream or array");
            }

            COSArray newStreams = new COSArray();
            for (COSStream stream : streams)
            {
                newStreams.add(copyStreamWithNameRemapping(stream, renameMap));
            }
            
            overlayPages.add(new OverlayPage(newStreams, res, renameMap));

        }
    }
    
    /**
     * Determine which resource reference names in the resources dictionary from
     * an overlay page (overlayResources) need to be renamed to avoid clashes with
     * names used in the target document.
     * 
     * @param overlayResources Resources dictionary from overlay page
     * @param rsrcNamesUsedInTarget Names used in target document
     * @return Mapping of old (clashing) to new (unique) names
     */
    private Map<COSName,COSName> makeRenameMap(COSDictionary overlayResources, Set<COSName> rsrcNamesUsedInTarget)
    {   HashMap<COSName,COSName> renamedResourecs = new HashMap<COSName,COSName>();
        HashSet<COSName> overlayRsrcNames = new HashSet<COSName>();
        resourceNamesUsedInRsrcDict(overlayResources, overlayRsrcNames);
        // Find conflicting names
        Set<COSName> conflictRsrcNames = new HashSet<COSName>(overlayRsrcNames);
        conflictRsrcNames.retainAll(rsrcNamesUsedInTarget);
        // Map each conflicting name to a new non-conflicting name
        for (COSName conflictingName : conflictRsrcNames) {
            COSName newName = makeNonConflictingName(conflictingName, rsrcNamesUsedInTarget, overlayRsrcNames);
            renamedResourecs.put(conflictingName, newName);
        }
        return renamedResourecs;
    }   
        
    /**
     * For a PDF resource entry name `conflictingName', generate a new name
     * that doesn't clash with any of the names in `usedNames1' or `usedNames2'.
     * 
     * The name generation/alteration strategy is not specified and should not
     * be relied upon. The only guarantee provided is that a non-conflicting
     * name that's within the 63-char PDF name length limit will be produced.
     * 
     * @param conflictingName Name to create a replacement for
     * @param usedNames1 First set of names to check for clash.
     * @param usedNames2 Second set of names to check for clash.
     * @return New, non-conflicting name
     */
    private COSName makeNonConflictingName(COSName conflictingName, Set<COSName> usedNames1, Set<COSName> usedNames2) {
        // Initial strategy: Append "Onnn" where "nnn" is sequential
        COSName newName = conflictingName;
        do {
            newName = COSName.getPDFName(newName.getName() + "O" + nameIncrementCounter++);
            if (newName.getName().length() > 127) {
                // PDF:2008 Table C.1 Architectural Limits specifies
                // that a name should be no more than 127 bytes. 
            }
        } while (usedNames1.contains(newName) || usedNames2.contains(newName));
        return newName;
    }
    
    /**
     * ResourceRenamer scans a resources dictionary and one or more content streams,
     * looking for resource names that clash with a supplied list of already-taken names.
     * 
     * For each clashing name it renames the object in the resources dictionary and rewrites all
     * references to it in the content stream to use the new name.
     */
    private static final class ResourceRenamer extends PDFStreamProcessor {
    
        // Names in the overlay's resources dictionary.
        private static final HashMap<PDFOperator,OperatorInfo> operators
                = new HashMap<PDFOperator,OperatorInfo>();
        private final Map<COSName,COSName> renamedResources;
        private final ContentStreamWriter outputStream;
        private int nameIncrementCounter = 0;
        
        // This struct-like POD class defines which operators we check for names
        // and where to look for the names.
        private static final class OperatorInfo {
            final PDFOperator op;
            final int numArgs;
            final int[] nameArgIndexes;
            OperatorInfo(PDFOperator op, int numArgs, int ... nameArgIndexes) {
                this.op = op;
                this.numArgs = numArgs;
                this.nameArgIndexes = nameArgIndexes;
                assert(numArgs >= nameArgIndexes.length);
            }
            static void add(HashMap<PDFOperator,OperatorInfo> operators, String operator, int numArgs, int ... nameArgIndexes) {
                PDFOperator op = PDFOperator.getOperator(operator);
                operators.put(op, new OperatorInfo(op, numArgs, nameArgIndexes));
            }
        }
                
        static {
            OperatorInfo.add(operators, "gs", 1, 0);
            OperatorInfo.add(operators, "Tf", 2, 0);
            OperatorInfo.add(operators, "Do", 1, 0);
            //OperatorInfo.add(operators, "SCN", 1, 0);
            //OperatorInfo.add(operators, "scn", 1, 0);
            //OperatorInfo.add(operators, "BDC", 1, 0);
            //OperatorInfo.add(operators, "DP", 1, 0);
            //OperatorInfo.add(operators, "CS", 1, 0);
            //OperatorInfo.add(operators, "cs", 1, 0);
        }
        
        /**
         * Create a new ResourceRenamer to process content streams that refer
         * to the resources dictionary overlayResources. It will ensure that
         * no name in `overlayResources' conflicts with any name in `busyNames'.
         * 
         * Every content stream that referred to `overlayResources' must be
         * passed through the PDFStreamEngine that uses this ResourceRenamer,
         * 
         * 
         * 
         * @param overlayResources Resources dictionary from overlay
         * @param busyResources Resources dictionary to avoid conflicts with
         * @param outputStream Stream to write converted/renamed content stream data to
         */
        ResourceRenamer(Map<COSName,COSName> renameMap, ContentStreamWriter outputStream) {
            this.outputStream = outputStream;
            this.renamedResources = renameMap;
            setDefaultOperatorProcessor(new ResourceRenamerOperatorHandler());
        }

        @Override
        public void processStream(COSStream stream, boolean forceParsing) throws IOException {
            super.processStream(stream, forceParsing);
            outputStream.flush();
        }
        
        private final class ResourceRenamerOperatorHandler implements OperatorHandler {
            /**
            * For each operator encountered, determine whether its arguments
            * reference name(s) from the resources dictionary.
            * 
            * {@inheritDoc}
            */
            public void process(PDFOperator operator, List<COSBase> arguments) throws IOException {
                List<COSBase> argsToWrite = arguments;
                // Determine whether we need to rewrite this operator
                OperatorInfo oi = operators.get(operator);
                if (oi != null) {
                    if (arguments.size() != oi.numArgs) {
                        // TODO: For reuse, should really be a log message
                        System.err.println("Unexpected number of args to op " + operator + ": " + arguments.size());
                        for (COSBase arg : arguments)
                        {
                            System.err.println(arg + " ");
                        }
                        System.err.println(operator);
                    } else {
                        // We're altering the args so we must copy them
                        argsToWrite = new ArrayList<COSBase>(arguments);
                        // then replace any remapped names
                        for (int index : oi.nameArgIndexes) {
                            COSName oldName = (COSName) arguments.get(index);
                            COSName remapped = renamedResources.get(oldName);
                            if (remapped != null) {
                                argsToWrite.set(index, remapped);
                            }
                        }
                    }
                }
                // Having done any required name re-mapping, write the
                // adjusted operation to the output stream.
                outputStream.writeTokens(argsToWrite);
                outputStream.writeToken(operator);
            }
        }
        
    }
    
    /**
     * Copy a content stream, changing any resources dictionary reference names
     * used in the stream to their replacements as defined in `renameMap'.
     * 
     * @param originalStream
     * @param renameMap
     * @return
     * @throws IOException 
     */
    private COSStream copyStreamWithNameRemapping(COSStream originalStream, Map<COSName,COSName> renameMap) throws IOException
    {        
        // Prepare a new stream to write results to
        COSStream output = new COSStream(targetDocument.getDocument().getScratchFile());
        output.setFilters(originalStream.getFilters());
        OutputStream os = output.createUnfilteredStream();
        ContentStreamWriter ossw = new ContentStreamWriter(os, false);
        
        // Now scan the original content stream looking for names that appear in any
        // resource dictionary. Copy the input stream to the output with any names
        // substituted.
        ResourceRenamer renamer = new ResourceRenamer(renameMap, ossw);
        renamer.processStream(originalStream, true);

        // The new content stream has been written.
        ossw.flush();
        os.close();
        
        return output;
    }

    /**
     * Apply the overlay to each page in the target page list
     * 
     * @param pages List of target pages
     * @throws IOException
     */
    private void processTargetPages( List pages ) throws IOException
    {
        int pageCount = 0;
        Iterator pageIter = pages.iterator();
        while( pageIter.hasNext() )
        {
            int overlayPageNum = pageCount % overlayPages.size();
            OverlayPage overlayPage = (OverlayPage) overlayPages.get(overlayPageNum);
            PDPage targetPage = (PDPage)pageIter.next();
            mergePage(overlayPage, targetPage);
            pageCount++;
        }
    }
    
    /**
     * Get the /Contents of a page, ensuring it's an array by
     * wrapping standalone content streams in single-element
     * arrays where necessary.
     * 
     * The passed page may be modified.
     * 
     * @param page Page to get contents array of
     * @return  The page's contents array
     */
    private COSArray getPageContentsArray(PDPage page) {
        COSDictionary pageDictionary = page.getCOSDictionary();
        COSBase contents = pageDictionary.getDictionaryObject( COSName.CONTENTS );
        if( contents instanceof COSStream )
        {
            // Page's /Contents is a reference to a stream. We need
            // it to be an array so we can append more streams to it.
            // To do this, we just wrap it as a single-element array.
            COSStream contentsStream = (COSStream)contents;
            COSArray array = new COSArray();
            array.add(contentsStream);
            pageDictionary.setItem(COSName.CONTENTS, array);
            contents = array;
        }
        // If the contents aren't a COSStream they must be COSArray
        return (COSArray) contents;
    }

    /**
     * Overlay the page `overlayPage' onto `targetPage', merging resources
     * dictionaries and appending content streams.
     * 
     * The resources from `overlayPage' <i>must</i> be uniquely named,
     * with no names the same as resources in `targetPage' or any
     * resources dictionary of any parent of `targetPage'.
     * 
     * The target page is modified in-place.
     * 
     * @param overlayPage Contents and resources of page to overlay
     * @param Page to apply overlay to
     */
    private void mergePage(OverlayPage overlayPage, PDPage targetPage )
    {
        COSArray pageContents = getPageContentsArray(targetPage);
        
        PDResources resources = targetPage.findResources();
        if( resources == null )
        {
            resources = new PDResources();
            targetPage.setResources( resources );
        }
        COSDictionary targetResDict = resources.getCOSDictionary();
        COSDictionary overlayResDict = overlayPage.res;
        mergeArray(COSName.PROC_SET, targetResDict, overlayResDict);
        mergeDictionary(COSName.FONT, targetResDict, overlayResDict, overlayPage.objectNameMap);
        mergeDictionary(COSName.XOBJECT, targetResDict, overlayResDict, overlayPage.objectNameMap);
        mergeDictionary(COSName.EXT_G_STATE, targetResDict, overlayResDict, overlayPage.objectNameMap);

        //we are going to wrap the existing content around some save/restore
        //graphics state, so the result is
        //
        //<save graphics state>
        //<all existing content streams>
        //<restore graphics state>
        //<translate, rotate, scale> (cm operator)
        //<overlay content>
        //
        // TODO: Clip overlay document to its transformed clip region, PDF:2008 8.5.4

        pageContents.add(0, saveGraphicsStateStream );
        pageContents.add( restoreGraphicsStateStream );
        if (transformCoordsStream != null) {
            pageContents.add( transformCoordsStream );
        }
        pageContents.addAll(overlayPage.contentsArray);
    }

    /**
     * Merges `source' into `dest', renaming any entries in `source' according
     * to `objectNameMap' when merging.
     * 
     * The source dictionary is unchanged, and the destination dictionary is
     * the union of the destination and of the source transformed by objectNameMap.
     *
     * @param dest Dictionary to merge into
     * @param source Dictionary to merge entries from
     * @param objectNameMap Mapping of names in `source' to the names that should appear in `dest'
     */
    private void mergeDictionary(COSName name, COSDictionary dest, COSDictionary source, Map<COSName,COSName> objectNameMap)
    {
        COSDictionary destDict = (COSDictionary) dest.getDictionaryObject(name);
        COSDictionary sourceDict = (COSDictionary) source.getDictionaryObject(name);

        if (destDict == null)
        {
            destDict = new COSDictionary();
            dest.setItem(name, destDict);
        }
        if( sourceDict != null )
        {

            for (Map.Entry<COSName, COSBase> entry : sourceDict.entrySet())
            {
                COSName mappedKey = (COSName) objectNameMap.get(entry.getKey());
                if (mappedKey != null)
                {
                    // This entry has been renamed, so assign the new name
                    // in the target dict.
                    destDict.setItem(mappedKey, entry.getValue());
                }
                else
                {
                    // Copy without renaming
                    destDict.setItem(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /**
     * merges two arrays.
     *
     * @param dest
     * @param source
     */
    private void mergeArray(COSName name, COSDictionary dest, COSDictionary source)
    {
        COSArray destDict = (COSArray) dest.getDictionaryObject(name);
        COSArray sourceDict = (COSArray) source.getDictionaryObject(name);

        if (destDict == null)
        {
            destDict = new COSArray();
            dest.setItem(name, destDict);
        }

        for (int sourceDictIdx = 0; sourceDict != null && sourceDictIdx<sourceDict.size(); sourceDictIdx++)
        {
            COSBase key = sourceDict.get(sourceDictIdx);
            if (key instanceof COSName)
            {
                COSName keyname = (COSName) key;

                boolean bFound = false;
                for (int destDictIdx = 0; destDictIdx<destDict.size(); destDictIdx++)
                {
                    COSBase destkey = destDict.get(destDictIdx);
                    if (destkey instanceof COSName)
                    {
                        COSName destkeyname = (COSName) destkey;
                        if (destkeyname.equals(keyname))
                        {
                            bFound = true;
                            break;
                        }
                    }
                }
                if (!bFound)
                {
                    destDict.add(keyname);
                }
            }
        }
    }
}
