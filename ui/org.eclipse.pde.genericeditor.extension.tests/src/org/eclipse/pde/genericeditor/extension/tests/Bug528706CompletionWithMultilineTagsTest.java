/*******************************************************************************
 * Copyright (c) 2017, 2018 Red Hat Inc. and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Lucas Bullen (Red Hat Inc.)
 *******************************************************************************/
package org.eclipse.pde.genericeditor.extension.tests;

import org.eclipse.jface.text.ITextViewer;
import org.junit.Before;
import org.junit.Test;

public class Bug528706CompletionWithMultilineTagsTest extends AbstractTargetEditorTest {
	private ITextViewer textViewer;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		textViewer = getTextViewerForTarget("MultilineTagTestCaseTarget");
	}

	@Test
	public void testTagNameCompletionBeforeAMultiline() {
		confirmCompletionAtOffset(21, "location");
	}

	@Test
	public void testTagNameCompletionAfterAMultiline() {
		confirmCompletionAtOffset(109, "location");
	}

	@Test
	public void testTagValueCompletionBeforeAMultiline() {
		confirmCompletionAtOffset(145, "carbon");
	}

	@Test
	public void testTagValueCompletionAsAMultiline() {
		confirmCompletionAtOffset(161, "linux");
	}

	@Test
	public void testTagValueCompletionAfterAMultiline() {
		confirmCompletionAtOffset(181, "PA_RISC");
	}

	@Test
	public void testAttributeNameCompletionBeforeAMultiline() {
		confirmCompletionAtOffset(39, "id");
	}

	@Test
	public void testAttributeNameCompletionAsAMultiline() {
		confirmCompletionAtOffset(60, "id");
	}

	@Test
	public void testAttributeNameCompletionAfterAMultiline() {
		confirmCompletionAtOffset(82, "id");
	}

	@Test
	public void testAttributeValueCompletionBeforeAMultiline() {
		confirmCompletionAtOffset(49, "Add repository URL first.");
	}

	@Test
	public void testAttributeValueCompletionAsAMultiline() {
		confirmCompletionAtOffset(71, "Add repository URL first.");
	}

	@Test
	public void testAttributeValueCompletionAfterAMultiline() {
		confirmCompletionAtOffset(92, "Add repository URL first.");
	}

	private void confirmCompletionAtOffset(int offset, String expectedCompletion) {
		checkProposals(new String[] { expectedCompletion },
				contentAssist.computeCompletionProposals(textViewer, offset + 1), offset);
	}
}