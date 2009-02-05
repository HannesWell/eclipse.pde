/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.pde.api.tools.internal.tasks;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import org.apache.tools.ant.BuildException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.pde.api.tools.internal.IApiXmlConstants;
import org.eclipse.pde.api.tools.internal.provisional.model.IApiBaseline;
import org.eclipse.pde.api.tools.internal.provisional.model.IApiComponent;
import org.eclipse.pde.api.tools.internal.provisional.model.IApiElement;
import org.eclipse.pde.api.tools.internal.provisional.search.ApiSearchEngine;
import org.eclipse.pde.api.tools.internal.provisional.search.IApiSearchReporter;
import org.eclipse.pde.api.tools.internal.provisional.search.IApiSearchRequestor;
import org.eclipse.pde.api.tools.internal.search.ApiUseSearchRequestor;
import org.eclipse.pde.api.tools.internal.search.XMLApiSearchReporter;
import org.eclipse.pde.api.tools.internal.util.Util;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Ant task for performing the API use analysis of a given Eclipse SDK
 * 
 * @since 1.0.0
 */
public class ApiUseTask extends CommonUtilsTask {
	
	protected static final String NO_API_DESCRIPTION = "no_description"; //$NON-NLS-1$
	protected static final String EXCLUDED = "excluded"; //$NON-NLS-1$
	
	/**
	 * The listing of component names to exclude from scanning
	 */
	private Set excludeset = null;
	/**
	 * If api references should be considered in the search
	 */
	private boolean considerapi = false;
	/**
	 * If internal references should be considered in the search
	 */
	private boolean considerinternal = false;
	
	/**
	 * Set of project names that were not searched
	 */
	private TreeSet notsearched = null;
	
	/**
	 * Constructor
	 */
	public ApiUseTask() {
	}
	
	/**
	 * Set the location of the current product or baseline that you want to search.
	 * 
	 * <p>It can be a .zip, .jar, .tgz, .tar.gz file, or a directory that corresponds to 
	 * the Eclipse installation folder. This is the directory is which you can find the 
	 * Eclipse executable.
	 * </p>
	 *
	 * @param baselineLocation the given location for the baseline to analyze
	 */
	public void setBaseline(String baselineLocation) {
		this.currentBaselineLocation = baselineLocation;
	}
	
	/**
	 * Set the exclude list location.
	 * 
	 * <p>The exclude list is used to know what bundles should excluded from the xml report generated by the task
	 * execution. Lines starting with '#' are ignored from the excluded elements.</p>
	 * <p>The format of the exclude list file looks like this:</p>
	 * <pre>
	 * # DOC BUNDLES
	 * org.eclipse.jdt.doc.isv
	 * org.eclipse.jdt.doc.user
	 * org.eclipse.pde.doc.user
	 * org.eclipse.platform.doc.isv
	 * org.eclipse.platform.doc.user
	 * # NON-ECLIPSE BUNDLES
	 * com.ibm.icu
	 * com.jcraft.jsch
	 * javax.servlet
	 * javax.servlet.jsp
	 * ...
	 * </pre>
	 * <p>The location is set using an absolute path.</p>
	 *
	 * @param excludeListLocation the given location for the excluded list file
	 */
	public void setExcludeList(String excludeListLocation) {
		this.excludeListLocation = excludeListLocation;
	}

	/**
	 * Set the output location where the reports will be generated.
	 * 
	 * <p>Once the task is completed, reports are available in this directory using a structure
	 * similar to the filter root. A sub-folder is created for each component that has problems
	 * to be reported. Each sub-folder contains a file called "report.xml". </p>
	 * 
	 * <p>A special folder called "allNonApiBundles" is also created in this folder that contains a xml file called
	 * "report.xml". This file lists all the bundles that are not using the api tooling nature.</p>
	 * 
	 * @param baselineLocation the given location for the reference baseline to analyze
	 */
	public void setReport(String reportLocation) {
		this.reportLocation = reportLocation;
	}
	
	/**
	 * Set the execution environment file to use.
	 * <p>By default, an execution environment file corresponding to a JavaSE-1.6 execution environment
	 * is used.</p>
	 * <p>The file is specified using an absolute path. This is optional.</p> 
	 *
	 * @param eeFileLocation the given execution environment file
	 */
	public void setEEFile(String eeFileLocation) {
		this.eeFileLocation = eeFileLocation;
	}
	
	/**
	 * Set the debug value.
	 * <p>The possible values are: <code>true</code>, <code>false</code></p>
	 * <p>Default is <code>false</code>.</p>
	 *
	 * @param debugValue the given debug value
	 */
	public void setDebug(String debugValue) {
		this.debug = Boolean.toString(true).equals(debugValue); 
	}
	
	/**
	 * Sets if references to API types should be considered in the search.
	 * <p>The possible values are: <code>true</code>, <code>false</code></p>
	 * <p>Default is <code>false</code>.</p>
	 * 
	 * @param considerapi the given value
	 */
	public void setConsiderAPI(String considerapi) {
		this.considerapi = Boolean.toString(true).equals(considerapi);
	}
	
	/**
	 * Sets if references to internal types should be considered in the search.
	 * <p>The possible values are: <code>true</code>, <code>false</code></p>
	 * <p>Default is <code>false</code>.</p>
	 * 
	 * @param considerapi the given value
	 */
	public void setConsiderInternal(String considerinternal) {
		this.considerinternal = Boolean.toString(true).equals(considerinternal);
	}
	
	/**
	 * Returns the set of search flags to use for the {@link IApiSearchRequestor}
	 * 
	 * @return the set of flags to use
	 */
	private int getSearchFlags() {
		int flags = (this.considerapi ? IApiSearchRequestor.INCLUDE_API : 0);
		flags |= (this.considerinternal ? IApiSearchRequestor.INCLUDE_INTERNAL : 0);
		return flags;
	}
	
	/* (non-Javadoc)
	 * @see org.apache.tools.ant.Task#execute()
	 */
	public void execute() throws BuildException {
		if (this.currentBaselineLocation == null || this.reportLocation == null) {
			StringWriter out = new StringWriter();
			PrintWriter writer = new PrintWriter(out);
			writer.println(Messages.bind(
					Messages.ApiUseTask_missing_arguments, 
					new String[] {this.currentBaselineLocation, this.reportLocation,}));
			writer.flush();
			writer.close();
			throw new BuildException(String.valueOf(out.getBuffer()));
		}
		if (this.debug) {
			System.out.println("baseline to examine : " + this.currentBaselineLocation); //$NON-NLS-1$
			System.out.println("report location : " + this.reportLocation); //$NON-NLS-1$
			System.out.println("search for API references : " + this.considerapi); //$NON-NLS-1$
			System.out.println("search for internal references : " + this.considerinternal); //$NON-NLS-1$
			if (this.excludeListLocation != null) {
				System.out.println("exclude list location : " + this.excludeListLocation); //$NON-NLS-1$
			} else {
				System.out.println("No exclude list location"); //$NON-NLS-1$
			}
			if(this.eeFileLocation != null) {
				System.out.println("EE file location : " + this.eeFileLocation); //$NON-NLS-1$
			}
			else {
				System.out.println("No EE file location given: using default"); //$NON-NLS-1$
			}
		}
		
		//stop if we don't want to see anything
		if(!considerapi && !considerinternal) {
			return;
		}
		long time = 0;
		if(this.debug) {
			time = System.currentTimeMillis();
			System.out.println("Cleaning report location..."); //$NON-NLS-1$
		}
		File file = new File(this.reportLocation);
		if(file.exists()) {
			scrubReportLocation(file);
		}
		if(this.debug) {
			System.out.println("done in: "+ (System.currentTimeMillis() - time) + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		
		//initialize the exclude list
		this.excludeset = CommonUtilsTask.initializeExcludedElement(this.excludeListLocation);
		
		notsearched = new TreeSet(componentsorter);
		if(this.excludeset != null) {
			for(Iterator iter = this.excludeset.iterator(); iter.hasNext();) {
				notsearched.add(new SkippedComponent((String) iter.next(), false, true));
			}
		}

		//extract the baseline to examine
		
		if (this.debug) {
			time = System.currentTimeMillis();
			System.out.println("Preparing baseline installation..."); //$NON-NLS-1$
		}
		File baselineInstallDir = extractSDK(CURRENT, this.currentBaselineLocation);
		if (this.debug) {
			System.out.println("done in: " + (System.currentTimeMillis() - time) + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
			time = System.currentTimeMillis();
		}
		
		//create the baseline to examine
		if(this.debug) {
			time = System.currentTimeMillis();
			System.out.println("Creating API baseline..."); //$NON-NLS-1$
		}
		IApiBaseline baseline = createBaseline(CURRENT_PROFILE_NAME, getInstallDir(baselineInstallDir), this.eeFileLocation);
		if (this.debug) {
			System.out.println("done in: " + (System.currentTimeMillis() - time) + " ms"); //$NON-NLS-1$ //$NON-NLS-2$
			time = System.currentTimeMillis();
		}
		try {
			IApiComponent[] components = baseline.getApiComponents();
			TreeSet scope = new TreeSet(CommonUtilsTask.componentsorter);
			boolean isapibundle = false;
			boolean excluded = false;
			for(int i = 0; i < components.length; i++) {
				isapibundle = Util.isApiToolsComponent(components[i]);
				excluded = this.excludeset.contains(components[i].getId());
				if(isapibundle && !excluded) {
					scope.add(components[i]);
				}
				else {
					notsearched.add(new SkippedComponent(components[i].getId(), !isapibundle, excluded));
				}
			}
			ApiSearchEngine engine = new ApiSearchEngine();
			IApiSearchRequestor requestor = new ApiUseSearchRequestor(
					(IApiElement[]) scope.toArray(new IApiElement[scope.size()]), 
					getSearchFlags(), 
					(String[]) this.excludeset.toArray(new String[this.excludeset.size()]));
			IApiSearchReporter reporter = new XMLApiSearchReporter(this.reportLocation);
			if(this.debug) {
				System.out.println("Searching for API references: "+requestor.includesAPI()); //$NON-NLS-1$
				System.out.println("Searching for internal references: "+requestor.includesInternal()); //$NON-NLS-1$
				System.out.println("-----------------------------------------------------------------------------------------------------"); //$NON-NLS-1$
			}
			ApiSearchEngine.setDebug(this.debug);
			engine.search(baseline, requestor, reporter, null);
		}
		catch(CoreException ce) {
			throw new BuildException(Messages.ApiUseTask_search_engine_problem, ce);
		}
		writeNotSearched();
	}
	
	/**
	 * Cleans the report location if it exists
	 * @param file
	 */
	private void scrubReportLocation(File file) {
		if(file.exists() && file.isDirectory()) {
			File[] files = file.listFiles();
			for (int i = 0; i < files.length; i++) {
				if(files[i].isDirectory()) {
					scrubReportLocation(files[i]);
				}
				else {
					files[i].delete();
				}
			}
			file.delete();
		}
	}
	
	/**
	 * Writes out the listing of components that were not searched at all
	 */
	private void writeNotSearched() {
		BufferedWriter writer = null;
		try {
			if(this.debug) {
				System.out.println("Writing file for projects that were not searched..."); //$NON-NLS-1$
			}
			File file = new File(this.reportLocation, "not_searched.xml"); //$NON-NLS-1$
			if(!file.exists()) {
				file.createNewFile();
			}
			Document doc = Util.newDocument();
			Element root = doc.createElement(IApiXmlConstants.ELEMENT_COMPONENTS);
			doc.appendChild(root);
			Element comp = null;
			SkippedComponent component = null;
			for(Iterator iter = notsearched.iterator(); iter.hasNext();) {
				component = (SkippedComponent) iter.next();
				comp = doc.createElement(IApiXmlConstants.ELEMENT_COMPONENT);
				comp.setAttribute(IApiXmlConstants.ATTR_ID, component.componentid);
				comp.setAttribute(NO_API_DESCRIPTION, Boolean.toString(component.noapidescription));
				comp.setAttribute(EXCLUDED, Boolean.toString(component.inexcludelist));
				root.appendChild(comp);
			}
			writer = new BufferedWriter(new FileWriter(file));
			writer.write(Util.serializeDocument(doc));
			writer.flush();
		}
		catch(FileNotFoundException fnfe) {}
		catch(IOException ioe) {}
		catch(CoreException ce) {}
		finally {
			try {
				writer.close();
			} 
			catch (IOException e) {}
		}
	}
}
