/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.builders;

import java.io.*;
import java.util.*;

import javax.xml.parsers.*;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jface.text.*;
import org.eclipse.pde.internal.*;
import org.eclipse.pde.internal.core.*;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;

public class XMLErrorReporter extends DefaultHandler {
	
	class ElementData {
		int offset;
		boolean fErrorNode;
		
		public ElementData(int offset) {
			this.offset = offset;
		}
	}
	
	
	protected IFile fFile;
	
	private int fErrorCount;

	private IMarkerFactory fMarkerFactory;

	private org.w3c.dom.Document fXMLDocument;
	
	private IDocument fTextDocument;

	private Stack fElementStack;

	private Element fRootElement;

	private Locator fLocator;
	
	private int fHighestOffset;
	
	private HashMap fOffsetTable;

	private FindReplaceDocumentAdapter fFindReplaceAdapter;

	public XMLErrorReporter(IFile file) {
		fFile = file;
		try {
			createTextDocument(file.getContents(true));
		} catch (Exception e) {
			PDE.logException(e);
		}
		fOffsetTable = new HashMap();
		fElementStack = new Stack();
		removeFileMarkers();
	}

	public IFile getFile() {
		return fFile;
	}

	private void createTextDocument(InputStream stream) throws IOException {
		BufferedReader in= new BufferedReader(new InputStreamReader(stream, "UTF-8")); //$NON-NLS-1$
		StringBuffer buffer= new StringBuffer();
		char[] readBuffer= new char[2048];
		int n = in.read(readBuffer);
		while (n > 0) {
			buffer.append(readBuffer, 0, n);
			n= in.read(readBuffer);
		}
		
		fTextDocument = new org.eclipse.jface.text.Document(buffer.toString());
		fFindReplaceAdapter = new FindReplaceDocumentAdapter(fTextDocument);
	}

	private void addMarker(String message, int lineNumber, int severity) {
		try {
			IMarker marker = getMarkerFactory().createMarker(fFile);
			marker.setAttribute(IMarker.MESSAGE, message);
			marker.setAttribute(IMarker.SEVERITY, severity);
			if (lineNumber == -1)
				lineNumber = 1;
			marker.setAttribute(IMarker.LINE_NUMBER, lineNumber);
			if (severity == IMarker.SEVERITY_ERROR)
				fErrorCount += 1;
		} catch (CoreException e) {
			PDECore.logException(e);
		}
	}
	
	private IMarkerFactory getMarkerFactory() {
		if (fMarkerFactory == null)
			fMarkerFactory = new SchemaMarkerFactory();
		return fMarkerFactory;
	}

	private void addMarker(SAXParseException e, int severity) {
		addMarker(e.getMessage(), e.getLineNumber(), severity);
	}

	public void error(SAXParseException exception) throws SAXException {
		addMarker(exception, IMarker.SEVERITY_ERROR);
		generateErrorElementHierarchy();
	}

	public void fatalError(SAXParseException exception) throws SAXException {
		addMarker(exception, IMarker.SEVERITY_ERROR);
		generateErrorElementHierarchy();
	}

	public int getErrorCount() {
		return fErrorCount;
	}

	private void removeFileMarkers() {
		try {
			fFile.deleteMarkers(IMarker.PROBLEM, false, IResource.DEPTH_ZERO);
			fFile.deleteMarkers(SchemaMarkerFactory.MARKER_ID, false,
					IResource.DEPTH_ZERO);
		} catch (CoreException e) {
			PDECore.logException(e);
		}
	}

	public void report(String message, int line, int severity) {
		if (severity == CompilerFlags.ERROR)
			addMarker(message, line, IMarker.SEVERITY_ERROR);
		else if (severity == CompilerFlags.WARNING)
			addMarker(message, line, IMarker.SEVERITY_WARNING);
	}

	public void warning(SAXParseException exception) throws SAXException {
		addMarker(exception, IMarker.SEVERITY_WARNING);
	}
	
	/* (non-Javadoc)
	 * @see org.xml.sax.helpers.DefaultHandler#startDocument()
	 */
	public void startDocument() throws SAXException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		try {
			fXMLDocument = factory.newDocumentBuilder().newDocument();
		} catch (ParserConfigurationException e) {
		}
	}
	
	/* (non-Javadoc)
	 * @see org.xml.sax.helpers.DefaultHandler#endDocument()
	 */
	public void endDocument() throws SAXException {
		fXMLDocument.appendChild(fRootElement);
	}
	
	/* (non-Javadoc)
	 * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String, java.lang.String, java.lang.String, org.xml.sax.Attributes)
	 */
	public void startElement(String uri, String localName, String qName,
			Attributes attributes) throws SAXException {
		Element element = fXMLDocument.createElement(qName);
		for (int i = 0; i < attributes.getLength(); i++) {
			element.setAttribute(attributes.getQName(i), attributes.getValue(i));
		}
		
		if (fRootElement == null)
			fRootElement = element;
		else 
			((Element)fElementStack.peek()).appendChild(element);
		fElementStack.push(element);
		try {
			if (fTextDocument != null)
				fOffsetTable.put(element, new ElementData(getStartOffset(qName)));
		} catch (BadLocationException e) {
		}
	}
	
	/* (non-Javadoc)
	 * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String, java.lang.String, java.lang.String)
	 */
	public void endElement(String uri, String localName, String qName)
			throws SAXException {
		fElementStack.pop();
	}
	
	private void generateErrorElementHierarchy() {
		while (!fElementStack.isEmpty()) {
			ElementData data = (ElementData) fOffsetTable.get(fElementStack.pop());
			if (data != null)
				data.fErrorNode = true;
		}
	}

	
	/* (non-Javadoc)
	 * @see org.xml.sax.helpers.DefaultHandler#characters(char[], int, int)
	 */
	public void characters(char[] characters, int start, int length)
			throws SAXException {
		StringBuffer buff = new StringBuffer();
		for (int i = 0; i < length; i++) {
			buff.append(characters[start + i]);
		}
		Text text = fXMLDocument.createTextNode(buff.toString());
		if (fRootElement == null)
			fXMLDocument.appendChild(text);
		else 
			((Element)fElementStack.peek()).appendChild(text);
	}
	
	/* (non-Javadoc)
	 * @see org.xml.sax.helpers.DefaultHandler#setDocumentLocator(org.xml.sax.Locator)
	 */
	public void setDocumentLocator(Locator locator) {
		fLocator = locator;
	}
	
	private int getStartOffset(String elementName) throws BadLocationException {
		int line = fLocator.getLineNumber();
		int col = fLocator.getColumnNumber();
		if (col < 0)
			col = fTextDocument.getLineLength(line);
		String text = fTextDocument.get(fHighestOffset + 1, fTextDocument.getLineOffset(line) - fHighestOffset - 1);

		ArrayList commentPositions = new ArrayList();
		for (int idx = 0; idx < text.length();) {
			idx = text.indexOf("<!--", idx); //$NON-NLS-1$
			if (idx == -1)
				break;
			int end = text.indexOf("-->", idx); //$NON-NLS-1$
			if (end == -1) 
				break;
			
			commentPositions.add(new Position(idx, end - idx));
			idx = end + 1;
		}

		int idx = 0;
		for (; idx < text.length(); idx += 1) {
			idx = text.indexOf("<" + elementName, idx); //$NON-NLS-1$
			if (idx == -1)
				break;
			boolean valid = true;
			for (int i = 0; i < commentPositions.size(); i++) {
				Position pos = (Position)commentPositions.get(i);
				if (pos.includes(idx)) {
					valid = false;
					break;
				}
			}
			if (valid)
				break;
		}
		if (idx > -1)
			fHighestOffset += idx + 1;
		return fHighestOffset;
	}
	
	private int getAttributeOffset(String name, String value, int offset) throws BadLocationException{
		IRegion nameRegion = fFindReplaceAdapter.find(offset, name+"\\s*=\\s*\""+value, true, false, false, true); //$NON-NLS-1$
		if (nameRegion != null) {
			return nameRegion.getOffset();
		}
		return -1;
	}

	
	protected int getLine(Element element) {
		ElementData data = (ElementData)fOffsetTable.get(element);
		try {
			return (data == null) ? 1 : fTextDocument.getLineOfOffset(data.offset) + 1;
		} catch (Exception e) {
			return 1;
		}
	}
	
	protected int getLine(Element element, String attName) {
		ElementData data = (ElementData)fOffsetTable.get(element);
		try {
			int offset = getAttributeOffset(attName, element.getAttribute(attName), data.offset);
			return fTextDocument.getLineOfOffset(offset) + 1;
		} catch (Exception e) {
			return getLine(element);
		}
	}
	
	public void validateContent(IProgressMonitor monitor) {
		
	}
	
	public Element getDocumentRoot() {
		fRootElement.normalize();
		return fRootElement;
	}

	
}
