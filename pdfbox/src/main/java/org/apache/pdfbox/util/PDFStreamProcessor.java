/*
 * Copyright 2012 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pdfbox.util;

import java.io.IOException;
import java.util.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;

/**
 * PDFStreamProcessor reads a PDF content stream, invoking 
 * callbacks on each operator encountered.
 * 
 * No information about the PDF graphic stack state or any other
 * state in the PDF steam is maintained. If you want a stream
 * processor that maintains state, see the PDFStreamEngine
 * subclass.
 */
public class PDFStreamProcessor 
{
    
    
    /**
    * PDFStreamEngine calls OperatorHandler implementations when
    * the operator(s) they have been registered to handle are encountered. See
    * PDFStreamEngine.
    */
    public interface OperatorHandler 
    {
        /**
        * process(...) is called whenever an operator is encountered by the
        * PDFStreamProcessor this handler was registered with.
        * 
        * If you want access to the PDFStreamProcessor making the call, pass it
        * to your implementation's ctor or to a setter method.
        * 
        * @param operator The operator that is being processed.
        * @param arguments arguments needed by this operator.
        *
        * @throws IOException If there is an error processing the operator.
        */
        public abstract void process(PDFOperator operator, List<COSBase> arguments) throws IOException;
    }

    
    private static final Log LOG = LogFactory.getLog(PDFStreamEngine.class);

    private final Map<String,OperatorHandler> handlers = new HashMap<String,OperatorHandler>();
    private OperatorHandler defaultHandler;

    /**
     * Register the operator processor `op' to handle `operator' when it's encountered
     * in the PDF content stream.
     * 
     * An OperatorHandler may be registered against more than one operator
     * and against more than one PDFStreamProcessor.
     * 
     * @param operator Operator to match
     * @param op Handler to invoke when operator is encountered
     */
    public void registerOperatorProcessor( String operator, OperatorHandler handler )
    {
        handlers.put(operator, handler);
    }
    
    /**
     * Unregister and remove the OperatorHandler registered to handle
     * `operator', or null if no handler is registered for that operator.
     * 
     * @param operator
     * @return 
     */
    public OperatorHandler unregisterOperatorProcessor( String operator)
    {
        return handlers.remove(operator);
    }
    
    /**
     * Batch register a set of operator processors, replacing any existing
     * collection of handlers that may be registered.
     * 
     * A shallow copy of the operator map is taken, so the operator map
     * may be safely modified after this method is called.
     * 
     * @param handlers New set of handlers to use.
     */
    public void replaceOperatorProcessors(Map<String,OperatorHandler> handlers)
    {
        handlers.clear();
        handlers.putAll(handlers);
    }

    /**
     * Return the OperatorHandler registered to handle `operator', or
     * null if no handler is registered.
     * 
     * This method does <b>not</b> return the default handler if no operator
     * is matched. See handlerForOperator(String operator).
     * 
     * @param operator
     * @return 
     */
    public OperatorHandler getOperatorProcessor(String operator)
    {
        return handlers.get(operator);
    }
    
    /**
     * @return Default operator handler
     */
    public OperatorHandler getDefaultOperatorProcessor()
    {
        return defaultHandler;
    }
    
    /**
     * Set the default operator handler. If a default operator handler is set,
     * it will be invoked whenever no operator handler that exactly matches
     * the registered operator is found.
     * 
     * @param defaultHandler Default operator handler to use
     */
    public void setDefaultOperatorProcessor(OperatorHandler defaultHandler)
    {
        this.defaultHandler = defaultHandler;
    }
    
    /**
     * Process the PDF content stream `stream', invoking handlers with any
     * accumulated arguments whenver an operator is encountered.
     *
     * For the forceParsing parameter, see the documentation for PDFStreamParser. 
     * 
     * @param stream The stream to process
     * @param forceParsing Whether to continue after errors
     * @throws IOException
     */
    public void processStream(COSStream stream, boolean forceParsing) throws IOException
    {        
        final PDFStreamParser parser = new PDFStreamParser(stream, forceParsing);
        try 
        {
            final List<COSBase> arguments = new ArrayList<COSBase>();
            final Iterator<Object> it = parser.getTokenIterator();
            while (it.hasNext()) 
            {
                Object next = it.next();
                if (LOG.isDebugEnabled()) 
                {
                    LOG.debug("processing substream token: " + next);
                }
                if (next instanceof COSObject) 
                {
                    arguments.add(((COSObject) next).getObject());
                }
                else if (next instanceof PDFOperator) 
                {
                    processOperator((PDFOperator) next, arguments);
                    arguments.clear();
                }
                else
                {
                    // Accumulate arguments
                    arguments.add((COSBase) next);
                }

            }
        } finally {
            parser.close();
        }
    }
    
    protected void processOperator( PDFOperator operator, List<COSBase> arguments ) throws IOException
    {
        OperatorHandler handler = handlerForOperator(operator.getOperation());
        if (handler != null) 
        {
            handler.process(operator, arguments);
        }
    }

    /**
     * Return the handler to be used for `operator'. If a handler has been
     * registered explicitly for this operator it will be returned, otherwise
     * the default handler will be returned. If there is no default handler,
     * null is returned.
     * 
     * @param operator
     * @return 
     */
    protected OperatorHandler handlerForOperator(String operator)
    {
        OperatorHandler handler = handlers.get(operator);
        if (handler == null) 
        {
            handler = defaultHandler;
        }
        return handler;
    }
    
    /**
     * See handlerForOperator(String)
     */
    protected OperatorHandler handlerForOperator(PDFOperator operator) {
        return handlerForOperator(operator == null ? null : operator.getOperation());
    }
    
}
