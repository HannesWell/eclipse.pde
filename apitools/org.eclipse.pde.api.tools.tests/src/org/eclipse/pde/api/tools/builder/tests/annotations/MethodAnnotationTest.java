/*******************************************************************************
 * Copyright (c) Apr 2, 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.api.tools.builder.tests.annotations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.tests.junit.extension.TestCase;
import org.eclipse.pde.api.tools.internal.problems.ApiProblemFactory;
import org.eclipse.pde.api.tools.internal.provisional.descriptors.IElementDescriptor;
import org.eclipse.pde.api.tools.internal.provisional.problems.IApiProblem;

/**
 * Abstract class for method annotation tests
 * 
 * @since 1.0.600
 */
public abstract class MethodAnnotationTest extends AnnotationTest {

	/**
	 * @param name
	 */
	public MethodAnnotationTest(String name) {
		super(name);
	}

	/**
	 * @return the tests for this class
	 */
	public static Test suite() {
		TestSuite suite = new TestSuite(MethodAnnotationTest.class.getName());
		collectTests(suite);
		return suite;
	}

	@Override
	protected int getDefaultProblemId() {
		return ApiProblemFactory.createProblemId(IApiProblem.CATEGORY_USAGE, IElementDescriptor.METHOD, IApiProblem.UNSUPPORTED_ANNOTATION_USE, IApiProblem.NO_FLAGS);
	}

	@Override
	protected IPath getTestSourcePath() {
		return super.getTestSourcePath().append("method"); //$NON-NLS-1$
	}

	/**
	 * @return all of the child test classes of this class
	 */
	private static Class<?>[] getAllTestClasses() {
		Class<?>[] classes = new Class[] {
				ValidDefaultMethodAnnotationTests.class,
				InvalidDefaultMethodAnnotationTests.class,
				InvalidInterfaceMethodAnnotationTests.class,
				ValidInterfaceMethodAnnotationTests.class };
		return classes;
	}

	/**
	 * Collects tests from the getAllTestClasses() method into the given suite
	 * 
	 * @param suite
	 */
	private static void collectTests(TestSuite suite) {
		// Hack to load all classes before computing their suite of test cases
		// this allow to reset test cases subsets while running all Builder
		// tests...
		Class<?>[] classes = getAllTestClasses();

		// Reset forgotten subsets of tests
		TestCase.TESTS_PREFIX = null;
		TestCase.TESTS_NAMES = null;
		TestCase.TESTS_NUMBERS = null;
		TestCase.TESTS_RANGE = null;
		TestCase.RUN_ONLY_ID = null;

		/* tests */
		for (int i = 0, length = classes.length; i < length; i++) {
			Class<?> clazz = classes[i];
			Method suiteMethod;
			try {
				suiteMethod = clazz.getDeclaredMethod("suite", new Class[0]); //$NON-NLS-1$
			} catch (NoSuchMethodException e) {
				e.printStackTrace();
				continue;
			}
			Object test;
			try {
				test = suiteMethod.invoke(null, new Object[0]);
			} catch (IllegalAccessException e) {
				e.printStackTrace();
				continue;
			} catch (InvocationTargetException e) {
				e.printStackTrace();
				continue;
			}
			suite.addTest((Test) test);
		}
	}
}