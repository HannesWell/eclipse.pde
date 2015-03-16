/*******************************************************************************
 *  Copyright (c) 2000, 2015 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.ui.editor;

import java.io.*;
import java.util.ArrayList;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jface.action.*;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.viewers.*;
import org.eclipse.pde.core.IBaseModel;
import org.eclipse.pde.core.IWritable;
import org.eclipse.pde.internal.core.IWorkspaceModel;
import org.eclipse.pde.internal.core.plugin.IWritableDelimiter;
import org.eclipse.pde.internal.core.util.XMLComponentRegistry;
import org.eclipse.pde.internal.ui.*;
import org.eclipse.pde.internal.ui.editor.context.*;
import org.eclipse.pde.internal.ui.editor.plugin.MissingResourcePage;
import org.eclipse.pde.internal.ui.editor.plugin.OverviewPage;
import org.eclipse.pde.internal.ui.util.PDEModelUtility;
import org.eclipse.search.ui.text.ISearchEditorAccess;
import org.eclipse.search.ui.text.Match;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.*;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.editor.FormEditor;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.eclipse.ui.ide.*;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.part.MultiPageEditorSite;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;

/**
 * A simple multi-page form editor that uses Eclipse Forms support. Example
 * plug-in is configured to create one instance of form colors that is shared
 * between multiple editor instances.
 */
public abstract class PDEFormEditor extends FormEditor implements IInputContextListener, IGotoMarker, ISearchEditorAccess {
	/**
	 * Updates the OutlinePage selection.
	 * 
	 * @since 3.0
	 */
	public class PDEFormEditorChangeListener implements ISelectionChangedListener {

		/**
		 * Installs this selection changed listener with the given selection
		 * provider. If the selection provider is a post selection provider,
		 * post selection changed events are the preferred choice, otherwise
		 * normal selection changed events are requested.
		 * 
		 * @param selectionProvider
		 */
		public void install(ISelectionProvider selectionProvider) {
			if (selectionProvider == null) {
				return;
			}

			if (selectionProvider instanceof IPostSelectionProvider) {
				IPostSelectionProvider provider = (IPostSelectionProvider) selectionProvider;
				provider.addPostSelectionChangedListener(this);
			} else {
				selectionProvider.addSelectionChangedListener(this);
			}
		}

		/*
		 * @see org.eclipse.jface.viewers.ISelectionChangedListener#selectionChanged(org.eclipse.jface.viewers.SelectionChangedEvent)
		 */
		public void selectionChanged(SelectionChangedEvent event) {
			if (PDEPlugin.getDefault().getPreferenceStore().getBoolean("ToggleLinkWithEditorAction.isChecked")) //$NON-NLS-1$
				if (getFormOutline() != null) {
					getFormOutline().setSelection(event.getSelection());
				}
		}

		/**
		 * Removes this selection changed listener from the given selection
		 * provider.
		 * 
		 * @param selectionProviderstyle
		 */
		public void uninstall(ISelectionProvider selectionProvider) {
			if (selectionProvider == null) {
				return;
			}

			if (selectionProvider instanceof IPostSelectionProvider) {
				IPostSelectionProvider provider = (IPostSelectionProvider) selectionProvider;
				provider.removePostSelectionChangedListener(this);
			} else {
				selectionProvider.removeSelectionChangedListener(this);
			}
		}

	}

	private static final String F_DIALOG_EDITOR_SECTION_KEY = "pde-form-editor"; //$NON-NLS-1$

	/**
	 * The editor selection changed listener.
	 * 
	 * @since 3.0
	 */
	private PDEFormEditorChangeListener fEditorSelectionChangedListener;
	private Clipboard clipboard;
	private Menu fContextMenu;
	protected InputContextManager fInputContextManager;
	private ISortableContentOutlinePage fFormOutline;
	private PDEMultiPageContentOutline fContentOutline;
	private String fLastActivePageId;
	private boolean fLastDirtyState;
	private boolean fError;

	private static class PDEMultiPageEditorSite extends MultiPageEditorSite {
		public PDEMultiPageEditorSite(MultiPageEditorPart multiPageEditor, IEditorPart editor) {
			super(multiPageEditor, editor);
		}

		@Override
		public IEditorActionBarContributor getActionBarContributor() {
			PDEFormEditor editor = (PDEFormEditor) getMultiPageEditor();
			PDEFormEditorContributor contributor = editor.getContributor();
			return contributor.getSourceContributor();
		}

		@Override
		public IWorkbenchPart getPart() {
			return getMultiPageEditor();
		}
	}

	public PDEFormEditor() {
		PDEPlugin.getDefault().getLabelProvider().connect(this);
		XMLComponentRegistry.Instance().connect(this);
		fInputContextManager = createInputContextManager();
	}

	/**
	 * We must override nested site creation so that we properly pass the source
	 * editor contributor when asked.
	 */
	@Override
	protected IEditorSite createSite(IEditorPart editor) {
		return new PDEMultiPageEditorSite(this, editor);
	}

	public IProject getCommonProject() {
		return fInputContextManager.getCommonProject();
	}

	public IBaseModel getAggregateModel() {
		if (fInputContextManager != null)
			return fInputContextManager.getAggregateModel();
		return null;
	}

	protected abstract InputContextManager createInputContextManager();

	/**
	 * Tests whether this editor has a context with a provided id. The test can
	 * be used to check whether to add certain pages.
	 * 
	 * @param contextId
	 * @return <code>true</code> if provided context is present,
	 *         <code>false</code> otherwise.
	 */
	public boolean hasInputContext(String contextId) {
		return fInputContextManager.hasContext(contextId);
	}

	public InputContextManager getContextManager() {
		return fInputContextManager;
	}

	protected void createInputContexts(InputContextManager contextManager) {
		IEditorInput input = getEditorInput();
		if (input instanceof IFileEditorInput) {
			// resource - find the project
			createResourceContexts(contextManager, (IFileEditorInput) input);
		} else if (input instanceof IStorageEditorInput) {
			createStorageContexts(contextManager, (IStorageEditorInput) input);
		} else if (input instanceof IURIEditorInput) {
			IURIEditorInput uriEditorInput = (IURIEditorInput) input;
			try {
				IFileStore store = EFS.getStore(uriEditorInput.getURI());
				if (!EFS.SCHEME_FILE.equals(store.getFileSystem().getScheme()))
					return;
				FileStoreEditorInput sinput = new FileStoreEditorInput(store);
				createSystemFileContexts(contextManager, sinput);
			} catch (CoreException e) {
				return;
			}
		}
	}

	protected abstract void createResourceContexts(InputContextManager contexts, IFileEditorInput input);

	protected abstract void createSystemFileContexts(InputContextManager contexts, FileStoreEditorInput input);

	protected abstract void createStorageContexts(InputContextManager contexts, IStorageEditorInput input);

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.forms.editor.FormEditor#createToolkit(org.eclipse.swt.widgets.Display)
	 */
	@Override
	protected FormToolkit createToolkit(Display display) {
		// Create a toolkit that shares colors between editors.
		return new FormToolkit(PDEPlugin.getDefault().getFormColors(display));
	}

	/*
	 * When subclassed, don't forget to call 'super'
	 */
	@Override
	protected void createPages() {
		clipboard = new Clipboard(getContainer().getDisplay());
		MenuManager manager = new MenuManager();
		IMenuListener listener = new IMenuListener() {
			public void menuAboutToShow(IMenuManager manager) {
				contextMenuAboutToShow(manager);
			}
		};
		manager.setRemoveAllWhenShown(true);
		manager.addMenuListener(listener);
		fContextMenu = manager.createContextMenu(getContainer());
		getContainer().setMenu(fContextMenu);
		createInputContexts(fInputContextManager);
		super.createPages();
		fInputContextManager.addInputContextListener(this);
		String pageToShow = computeInitialPageId();
		if (pageToShow != null)
			setActivePage(pageToShow);
		updateTitle();
		if (getPageCount() == 1 && getContainer() instanceof CTabFolder) {
			((CTabFolder) getContainer()).setTabHeight(0);
		}

		PDEModelUtility.connect(this);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.forms.editor.FormEditor#pageChange(int)
	 */
	@Override
	protected void pageChange(int newPageIndex) {
		super.pageChange(newPageIndex);
		IFormPage page = getActivePageInstance();
		updateContentOutline(page);
		if (page != null) {
			fLastActivePageId = page.getId();
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.part.MultiPageEditorPart#setFocus()
	 */
	@Override
	public void setFocus() {
		super.setFocus();
		IFormPage page = getActivePageInstance();
		// Could be done on setActive in PDEFormPage;
		// but setActive only handles page switches and not focus events
		if ((page != null) && (page instanceof PDEFormPage)) {
			((PDEFormPage) page).updateFormSelection();
		}
	}

	public Clipboard getClipboard() {
		return clipboard;
	}

	protected void contextMenuAboutToShow(IMenuManager manager) {
		PDEFormEditorContributor contributor = getContributor();
		IFormPage page = getActivePageInstance();
		if (page instanceof PDEFormPage)
			((PDEFormPage) page).contextMenuAboutToShow(manager);
		if (contributor != null)
			contributor.contextMenuAboutToShow(manager);
	}

	public PDEFormEditorContributor getContributor() {
		return (PDEFormEditorContributor) getEditorSite().getActionBarContributor();
	}

	protected String computeInitialPageId() {
		String firstPageId = null;
		String storedFirstPageId = loadDefaultPage();
		if (storedFirstPageId != null)
			firstPageId = storedFirstPageId;
		// Regardless what is the stored value,
		// use source page if model is not valid
		String invalidContextId = getFirstInvalidContextId();
		if (invalidContextId != null)
			return invalidContextId;
		return firstPageId;
	}

	private String getFirstInvalidContextId() {
		InputContext[] invalidContexts = fInputContextManager.getInvalidContexts();
		if (invalidContexts.length == 0)
			return null;
		// If primary context is among the invalid ones, return that.
		for (int i = 0; i < invalidContexts.length; i++) {
			if (invalidContexts[i].isPrimary())
				return invalidContexts[i].getId();
		}
		// Return the first one
		return invalidContexts[0].getId();
	}

	@Override
	public String getTitle() {
		if (fInputContextManager == null)
			return super.getTitle();
		InputContext context = fInputContextManager.getPrimaryContext();
		if (context == null)
			return super.getTitle();
		return context.getInput().getName();
	}

	public void updateTitle() {
		firePropertyChange(IWorkbenchPart.PROP_TITLE);
	}

	public String getTitleProperty() {
		return ""; //$NON-NLS-1$
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.ISaveablePart#doSave(org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public void doSave(IProgressMonitor monitor) {
		commitPages(true);
		fInputContextManager.save(monitor);
		editorDirtyStateChanged();
	}

	/**
	 * 
	 */
	public void doRevert() {
		IFormPage formPage = getActivePageInstance();
		// If the active page is a form page, commit all of its dirty field 
		// values to the model
		if ((formPage != null) && (formPage instanceof PDEFormPage)) {
			formPage.getManagedForm().commit(true);
		}
		// If the editor has source pages, revert them
		// Reverting the source page fires events to the associated form pages
		// which will cause all their values to be updated
		boolean reverted = doRevertSourcePages();
		// If the editor does not have any source pages, revert the form pages
		// by directly reloading the underlying model.
		// Reloading the model fires a world changed event to all form pages
		// causing them to update their values
		if (reverted == false) {
			reverted = doRevertFormPage();
		}
		// If the revert operation was performed disable the revert action and 
		// fire the dirty event 
		if (reverted) {
			editorDirtyStateChanged();
		}
	}

	private boolean doRevertFormPage() {
		boolean reverted = false;
		IBaseModel model = getAggregateModel();
		if (model instanceof IWorkspaceModel) {
			IWorkspaceModel workspaceModel = (IWorkspaceModel) model;
			workspaceModel.reload();
			reverted = true;
		}
		return reverted;
	}

	private boolean doRevertSourcePages() {
		boolean reverted = false;
		IFormPage[] pages = getPages();
		for (int i = 0; i < pages.length; i++) {
			if (pages[i] instanceof PDESourcePage) {
				PDESourcePage sourcePage = (PDESourcePage) pages[i];
				// Flush any pending editor operations into the document
				// so that the revert operation executes (revert operation is
				// aborted if the current document has not changed)
				// This happens when a form page field is modified.
				// The source page is not updated until a source operation is
				// performed or the source page is made active and possibly
				// in some other cases.
				sourcePage.getInputContext().flushEditorInput();
				// Revert the source page to the contents of the last save to file
				sourcePage.doRevertToSaved();
				reverted = true;
			}
		}
		return reverted;
	}

	public void doRevert(IEditorInput input) {
		IFormPage currentPage = getActivePageInstance();
		if (currentPage != null && currentPage instanceof PDEFormPage)
			((PDEFormPage) currentPage).cancelEdit();
		InputContext context = fInputContextManager.getContext(input);
		IFormPage page = findPage(context.getId());
		if (page != null && page instanceof PDESourcePage) {
			PDESourcePage spage = (PDESourcePage) page;
			spage.doRevertToSaved();
		}
		editorDirtyStateChanged();
	}

	public void flushEdits() {
		IFormPage[] pages = getPages();
		IManagedForm mForm = pages[getActivePage()].getManagedForm();
		if (mForm != null)
			mForm.commit(false);
		for (int i = 0; i < pages.length; i++) {
			if (pages[i] instanceof PDESourcePage) {
				PDESourcePage sourcePage = (PDESourcePage) pages[i];
				sourcePage.getInputContext().flushEditorInput();
			}
		}
	}

	public String getContextIDForSaveAs() {
		// Sub-classes must override this method and the isSaveAsAllowed 
		// method to perform save as operations
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.ISaveablePart#doSaveAs()
	 */
	@Override
	public void doSaveAs() {
		try {
			// Get the context for which the save as operation should be 
			// performed
			String contextID = getContextIDForSaveAs();
			// Perform the same as operation
			getContextManager().saveAs(getProgressMonitor(), contextID);
			// Get the new editor input
			IEditorInput input = getContextManager().findContext(contextID).getInput();
			// Store the new editor input
			setInputWithNotify(input);
			// Update the title of the editor using the name of the new editor
			// input
			setPartName(input.getName());
			// Fire a property change accordingly
			firePropertyChange(PROP_DIRTY);
		} catch (InterruptedException e) {
			// Ignore
		} catch (Exception e) {
			String title = PDEUIMessages.PDEFormEditor_errorTitleProblemSaveAs;
			String message = PDEUIMessages.PDEFormEditor_errorMessageSaveNotCompleted;
			if (e.getMessage() != null) {
				message = message + ' ' + e.getMessage();
			}
			PDEPlugin.logException(e, title, message);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.ISaveablePart#isSaveAsAllowed()
	 */
	@Override
	public boolean isSaveAsAllowed() {
		return false;
	}

	/**
	 * 
	 */
	private void storeDefaultPage() {
		IEditorInput input = getEditorInput();
		String pageId = fLastActivePageId;
		if (pageId == null)
			return;
		if (input instanceof IFileEditorInput) {
			// Triggered by opening a file in the workspace
			// e.g. From the Package Explorer View
			setPropertyEditorPageKey((IFileEditorInput) input, pageId);
		} else if (input instanceof IStorageEditorInput) {
			// Triggered by opening a file NOT in the workspace
			// e.g. From the Plug-in View
			setDialogEditorPageKey(pageId);
		}
	}

	/**
	 * @param pageID
	 */
	protected void setDialogEditorPageKey(String pageID) {
		// Use one global setting for all files belonging to a given editor
		// type.  Use the editor ID as the key.
		// Could use the storage editor input to get the underlying file
		// and use it as a unique key; but, the dialog settings file will
		// grow out of control and we do not need that level of granularity
		IDialogSettings section = getSettingsSection();
		section.put(getEditorID(), pageID);
	}

	/**
	 * 
	 */
	protected String getDialogEditorPageKey() {
		// Use one global setting for all files belonging to a given editor
		// type.  Use the editor ID as the key.
		// Could use the storage editor input to get the underlying file
		// and use it as a unique key; but, the dialog settings file will
		// grow out of control and we do not need that level of granularity
		IDialogSettings section = getSettingsSection();
		return section.get(getEditorID());
	}

	protected abstract String getEditorID();

	protected void setPropertyEditorPageKey(IFileEditorInput input, String pageId) {
		// We are using the file itself to persist the editor page key property
		// The value persists even after the editor is closed
		IFile file = input.getFile();
		try {
			// Set the editor page ID as a persistent property on the file
			file.setPersistentProperty(IPDEUIConstants.PROPERTY_EDITOR_PAGE_KEY, pageId);
		} catch (CoreException e) {
			// Ignore
		}
	}

	private String loadDefaultPage() {
		IEditorInput input = getEditorInput();
		if (input instanceof IFileEditorInput) {
			// Triggered by opening a file in the workspace
			// e.g. From the Package Explorer View
			return getPropertyEditorPageKey((IFileEditorInput) input);
		} else if (input instanceof IStorageEditorInput) {
			// Triggered by opening a file NOT in the workspace
			// e.g. From the Plug-in View
			return getDialogEditorPageKey();
		}
		return null;
	}

	protected String getPropertyEditorPageKey(IFileEditorInput input) {
		// We are using the file itself to persist the editor page key property
		// The value persists even after the editor is closed
		IFile file = input.getFile();
		// Get the persistent editor page key from the file
		try {
			return file.getPersistentProperty(IPDEUIConstants.PROPERTY_EDITOR_PAGE_KEY);
		} catch (CoreException e) {
			return null;
		}
	}

	@Override
	public void dispose() {
		storeDefaultPage();
		if (fEditorSelectionChangedListener != null) {
			fEditorSelectionChangedListener.uninstall(getSite().getSelectionProvider());
			fEditorSelectionChangedListener = null;
		}
		//setSelection(new StructuredSelection());
		PDEPlugin.getDefault().getLabelProvider().disconnect(this);
		XMLComponentRegistry.Instance().disconnect(this);
		PDEModelUtility.disconnect(this);
		if (clipboard != null) {
			clipboard.dispose();
			clipboard = null;
		}
		super.dispose();
		fInputContextManager.dispose();
		fInputContextManager = null;
	}

	@Override
	public boolean isDirty() {
		fLastDirtyState = computeDirtyState();
		return fLastDirtyState;
	}

	private boolean computeDirtyState() {
		IFormPage page = getActivePageInstance();
		if ((page != null && page.isDirty()) || (fInputContextManager != null && fInputContextManager.isDirty()))
			return true;
		return super.isDirty();
	}

	public boolean getLastDirtyState() {
		return fLastDirtyState;
	}

	public void fireSaveNeeded(String contextId, boolean notify) {
		if (contextId == null)
			return;
		InputContext context = fInputContextManager.findContext(contextId);
		if (context != null)
			fireSaveNeeded(context.getInput(), notify);
	}

	public void fireSaveNeeded(IEditorInput input, boolean notify) {
		if (notify)
			editorDirtyStateChanged();
		if (isDirty())
			validateEdit(input);
	}

	@Override
	public void editorDirtyStateChanged() {
		super.editorDirtyStateChanged();
		PDEFormEditorContributor contributor = getContributor();
		if (contributor != null)
			contributor.updateActions();
	}

	private void validateEdit(IEditorInput input) {
		final InputContext context = fInputContextManager.getContext(input);
		if (!context.validateEdit()) {
			getSite().getShell().getDisplay().asyncExec(new Runnable() {
				public void run() {
					doRevert(context.getInput());
					context.setValidated(false);
				}
			});
		}
	}

	private IDialogSettings getSettingsSection() {
		// Store global settings that will persist when the editor is closed
		// in the dialog settings (This is cheating)
		// Get the dialog settings
		IDialogSettings root = PDEPlugin.getDefault().getDialogSettings();
		// Get the dialog section reserved for PDE form editors
		IDialogSettings section = root.getSection(F_DIALOG_EDITOR_SECTION_KEY);
		// If the section is not defined, define it
		if (section == null) {
			section = root.addNewSection(F_DIALOG_EDITOR_SECTION_KEY);
		}
		return section;
	}

	public void gotoMarker(IMarker marker) {
		IResource resource = marker.getResource();
		InputContext context = fInputContextManager.findContext(resource);
		if (context == null)
			return;
		IFormPage page = getActivePageInstance();
		if (!context.getId().equals(page.getId()))
			page = setActivePage(context.getId());
		IDE.gotoMarker(page, marker);
	}

	public void openToSourcePage(Object object, int offset, int length) {
		InputContext context = null;
		if (object instanceof InputContext) {
			context = (InputContext) object;
		} else {
			context = getInputContext(object);
		}
		if (context != null) {
			PDESourcePage page = (PDESourcePage) setActivePage(context.getId());
			if (page != null)
				page.selectAndReveal(offset, length);
		}
	}

	public void setSelection(ISelection selection) {
		getSite().getSelectionProvider().setSelection(selection);
		getContributor().updateSelectableActions(selection);
	}

	public ISelection getSelection() {
		return getSite().getSelectionProvider().getSelection();
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getAdapter(Class<T> key) {
		if (key.equals(IContentOutlinePage.class)) {
			return (T) getContentOutline();
		}
		if (key.equals(IGotoMarker.class)) {
			return (T) this;
		}
		if (key.equals(ISearchEditorAccess.class)) {
			return (T) this;
		}
		return super.getAdapter(key);
	}

	public Menu getContextMenu() {
		return fContextMenu;
	}

	public PDEMultiPageContentOutline getContentOutline() {
		if (fContentOutline == null || fContentOutline.isDisposed()) {
			fContentOutline = new PDEMultiPageContentOutline(this);
			updateContentOutline(getActivePageInstance());
		}
		return fContentOutline;
	}

	/**
	 * 
	 * @return outline page or null
	 */
	protected ISortableContentOutlinePage getFormOutline() {
		if (fFormOutline == null) {
			fFormOutline = createContentOutline();
			if (fFormOutline != null) {
				fEditorSelectionChangedListener = new PDEFormEditorChangeListener();
				fEditorSelectionChangedListener.install(getSite().getSelectionProvider());
			}
		}
		return fFormOutline;
	}

	abstract protected ISortableContentOutlinePage createContentOutline();

	private void updateContentOutline(IFormPage page) {
		if (fContentOutline == null)
			return;
		ISortableContentOutlinePage outline = null;
		if (page instanceof PDESourcePage) {
			outline = ((PDESourcePage) page).getContentOutline();
		} else {
			outline = getFormOutline();
			if (outline != null && outline instanceof FormOutlinePage)
				((FormOutlinePage) outline).refresh();
		}
		fContentOutline.setPageActive(outline);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ui.forms.editor.FormEditor#setActivePage(java.lang.String)
	 */
	@Override
	public IFormPage setActivePage(String pageId) {
		IFormPage page = super.setActivePage(pageId);
		if (page != null)
			updateContentOutline(page);
		return page;
	}

	/* package */IFormPage[] getPages() {
		ArrayList<Object> formPages = new ArrayList<Object>();
		for (int i = 0; i < pages.size(); i++) {
			Object page = pages.get(i);
			if (page instanceof IFormPage)
				formPages.add(page);
		}
		return formPages.toArray(new IFormPage[formPages.size()]);
	}

	protected void performGlobalAction(String id) {
		// preserve selection
		ISelection selection = getSelection();
		boolean handled = ((PDEFormPage) getActivePageInstance()).performGlobalAction(id);
		if (!handled) {
			IFormPage page = getActivePageInstance();
			if (page instanceof PDEFormPage) {
				if (id.equals(ActionFactory.UNDO.getId())) {
					fInputContextManager.undo();
					return;
				}
				if (id.equals(ActionFactory.REDO.getId())) {
					fInputContextManager.redo();
					return;
				}
				if (id.equals(ActionFactory.CUT.getId()) || id.equals(ActionFactory.COPY.getId())) {
					copyToClipboard(selection);
					return;
				}
			}
		}
	}

	private void copyToClipboard(ISelection selection) {
		Object[] objects = null;
		String textVersion = null;
		if (selection instanceof IStructuredSelection) {
			IStructuredSelection ssel = (IStructuredSelection) selection;
			if (ssel.size() == 0)
				return;
			objects = ssel.toArray();
			StringWriter writer = new StringWriter();
			PrintWriter pwriter = new PrintWriter(writer);
			Class<? extends Object> objClass = null;
			for (int i = 0; i < objects.length; i++) {
				Object obj = objects[i];
				if (objClass == null)
					objClass = obj.getClass();
				else if (objClass.equals(obj.getClass()) == false)
					return;
				if (obj instanceof IWritable) {
					// Add a customized delimiter in between all serialized
					// objects to format the text representation
					if ((i != 0) && (obj instanceof IWritableDelimiter)) {
						((IWritableDelimiter) obj).writeDelimeter(pwriter);
					}
					((IWritable) obj).write("", pwriter); //$NON-NLS-1$
				} else if (obj instanceof String) {
					// Delimiter is always a newline
					pwriter.println((String) obj);
				}
			}
			pwriter.flush();
			textVersion = writer.toString();
			try {
				pwriter.close();
				writer.close();
			} catch (IOException e) {
			}
		} else if (selection instanceof ITextSelection) {
			textVersion = ((ITextSelection) selection).getText();
		}
		if ((textVersion == null || textVersion.length() == 0) && objects == null)
			return;
		// set the clipboard contents
		Object[] o = null;
		Transfer[] t = null;
		if (objects == null) {
			o = new Object[] {textVersion};
			t = new Transfer[] {TextTransfer.getInstance()};
		} else if (textVersion == null || textVersion.length() == 0) {
			o = new Object[] {objects};
			t = new Transfer[] {ModelDataTransfer.getInstance()};
		} else {
			o = new Object[] {objects, textVersion};
			t = new Transfer[] {ModelDataTransfer.getInstance(), TextTransfer.getInstance()};
		}
		clipboard.setContents(o, t);
	}

	public boolean canPasteFromClipboard() {
		IFormPage page = getActivePageInstance();
		if (page instanceof PDEFormPage) {
			return ((PDEFormPage) page).canPaste(getClipboard());
		}
		return false;
	}

	public boolean canCopy(ISelection selection) {
		if (selection == null)
			return false;
		if (selection instanceof IStructuredSelection)
			return !selection.isEmpty();
		if (selection instanceof ITextSelection) {
			ITextSelection textSelection = (ITextSelection) selection;
			return textSelection.getLength() > 0;
		}
		return false;
	}

	public boolean canCut(ISelection selection) {
		return canCopy(selection);
	}

	void updateUndo(IAction undoAction, IAction redoAction) {
		IModelUndoManager undoManager = fInputContextManager.getUndoManager();
		if (undoManager != null)
			undoManager.setActions(undoAction, redoAction);
	}

	/**
	 * Triggered by toggling the 'Link with Editor' button in the outline view
	 */
	public void synchronizeOutlinePage() {
		// Get current page
		IFormPage page = getActivePageInstance();

		if (page instanceof PDESourcePage) {
			// Synchronize with current source page
			((PDESourcePage) page).synchronizeOutlinePage();
		} else {
			// Synchronize with current form page
			// This currently does not work
			// TODO: Fix 'Link with Editor' functionality for form pages
			if (getFormOutline() != null) {
				getFormOutline().setSelection(getSelection());
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.search.ui.text.ISearchEditorAccess#getDocument(org.eclipse.search.ui.text.Match)
	 */
	public IDocument getDocument(Match match) {
		InputContext context = getInputContext(match.getElement());
		return context == null ? null : context.getDocumentProvider().getDocument(context.getInput());
	}

	/* (non-Javadoc)
	 * @see org.eclipse.search.ui.text.ISearchEditorAccess#getAnnotationModel(org.eclipse.search.ui.text.Match)
	 */
	public IAnnotationModel getAnnotationModel(Match match) {
		InputContext context = getInputContext(match.getElement());
		return context == null ? null : context.getDocumentProvider().getAnnotationModel(context.getInput());
	}

	protected abstract InputContext getInputContext(Object object);

	@Override
	protected final void addPages() {
		fError = getAggregateModel() == null;
		if (fError) {
			try {
				addPage(new MissingResourcePage(this));
			} catch (PartInitException e) {
				PDEPlugin.logException(e);
			}
		} else
			addEditorPages();
	}

	protected abstract void addEditorPages();

	public final void contextAdded(InputContext context) {
		if (fError) {
			removePage(0);
			addPages();
			if (!fError)
				setActivePage(OverviewPage.PAGE_ID);
		} else
			editorContextAdded(context);
	}

	public abstract void editorContextAdded(InputContext context);

	protected IProgressMonitor getProgressMonitor() {
		IProgressMonitor monitor = null;
		IStatusLineManager manager = getStatusLineManager();
		if (manager != null) {
			monitor = manager.getProgressMonitor();
		}
		if (monitor == null) {
			monitor = new NullProgressMonitor();
		}
		return monitor;
	}

	protected IStatusLineManager getStatusLineManager() {
		return getEditorSite().getActionBars().getStatusLineManager();
	}

	public void contributeToToolbar(IToolBarManager manager) {
	}

}
