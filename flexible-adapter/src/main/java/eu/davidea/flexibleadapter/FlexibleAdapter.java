/*
 * Copyright 2015-2016 Davide Steduto
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.davidea.flexibleadapter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.CallSuper;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import eu.davidea.flexibleadapter.common.SmoothScrollLinearLayoutManager;
import eu.davidea.flexibleadapter.helpers.ItemTouchHelperCallback;
import eu.davidea.flexibleadapter.helpers.StickyHeaderHelper;
import eu.davidea.flexibleadapter.items.IExpandable;
import eu.davidea.flexibleadapter.items.IFilterable;
import eu.davidea.flexibleadapter.items.IFlexible;
import eu.davidea.flexibleadapter.items.IHeader;
import eu.davidea.flexibleadapter.items.ISectionable;
import eu.davidea.viewholders.ExpandableViewHolder;
import eu.davidea.viewholders.FlexibleViewHolder;

/**
 * This class is backed by an ArrayList of arbitrary objects of <b>T</b>, where <b>T</b> is
 * your Model object containing the data, with version 5.0.0 it must implement {@link IFlexible}
 * interface. Read <a href="https://github.com/davideas/FlexibleAdapter/wiki">Wiki page</a> on
 * Github for more details.
 * <p>This class provides a set of standard methods to handle changes on the data set such as
 * filtering, adding, removing, moving and animating an item.</p>
 * With version 5.0.0, this Adapter supports a set of standard methods for Headers/Sections to
 * expand and collapse an Expandable item, to Drag&Drop and Swipe any item.
 * <p><b>NOTE:</b> This Adapter supports multi level of Expandable. Do not enable functionalities
 * like: Drag&Drop. Something might not work as expected, so better to change approach in
 * favor of a clearer design/layout: Open the sub list in a new Activity/Fragment...
 * <br/>Instead, this extra level of expansion is useful in situations where information is in
 * read only mode or with action buttons.</p>
 *
 * @author Davide Steduto
 * @see AnimatorAdapter
 * @see SelectableAdapter
 * @see IFlexible
 * @see FlexibleViewHolder
 * @see ExpandableViewHolder
 * @since 03/05/2015 Created
 * <br/>16/01/2016 Expandable items
 * <br/>24/01/2016 Drag&Drop, Swipe
 * <br/>30/01/2016 Class now extends {@link AnimatorAdapter} that extends {@link SelectableAdapter}
 * <br/>02/02/2016 New code reorganization, new item interfaces and full refactoring
 * <br/>08/02/2016 Headers/Sections
 * <br/>10/02/2016 The class is not abstract anymore, it is ready to be used
 * <br/>20/02/2016 Sticky headers
 * <br/>22/04/2016 Endless Scrolling
 * <br/>24/04/2016 FULL and PARTIAL Swipe
 */
@SuppressWarnings({"unused", "Range", "Convert2Diamond", "ConstantConditions", "unchecked"})
public class FlexibleAdapter<T extends IFlexible>
		extends AnimatorAdapter
		implements ItemTouchHelperCallback.AdapterCallback {

	private static final String TAG = FlexibleAdapter.class.getSimpleName();
	private static final String EXTRA_PARENT = TAG + "_parentSelected";
	private static final String EXTRA_CHILD = TAG + "_childSelected";
	private static final String EXTRA_HEADERS = TAG + "_headersShown";
	private static final String EXTRA_LEVEL = TAG + "_selectedLevel";
	private static final String EXTRA_SEARCH = TAG + "_searchText";
	private static final String EXTRA_SEARCH_OLD = TAG + "_searchTextOld";
	public static final int EXPANDABLE_VIEW_TYPE = -1;
	public static final int SECTION_VIEW_TYPE = -2;
	public static final long UNDO_TIMEOUT = 5000L;

	/**
	 * The main container for ALL items.
	 */
	private List<T> mItems;

	/**
	 * Header/Section items
	 */
	private List<IHeader> mOrphanHeaders;
	private boolean headersShown = false, headersSticky = false, recursive = false;
	private StickyHeaderHelper mStickyHeaderHelper;

	/**
	 * Handler for delayed {@link #filterItems(List)} and {@link OnDeleteCompleteListener#onDeleteConfirmed}
	 * <p>You can override this Handler, but you must keep the "What" already used:
	 * <br/>0 = filterItems delay
	 * <br/>1 = deleteConfirmed when Undo timeout is over</p>
	 * <br/>2 = reset flag to load more items</p>
	 */
	protected Handler mHandler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
		public boolean handleMessage(Message message) {
			switch (message.what) {
				case 0: //filterItems
					filterItems((List<T>) message.obj);
					return true;
				case 1: //confirm delete
					OnDeleteCompleteListener listener = (OnDeleteCompleteListener) message.obj;
					if (listener != null) listener.onDeleteConfirmed();
					emptyBin();
					return true;
				case 2: //onLoadMore
					resetOnLoadMore();
					return true;
			}
			return false;
		}
	});

	/**
	 * Used to save deleted items and to recover them (Undo).
	 */
	private List<RestoreInfo> mRestoreList;
	private boolean restoreSelection = false, multiRange = false, unlinkOnRemoveHeader = false,
			removeOrphanHeaders = false, permanentDelete = false, adjustSelected = true;

	/* ViewTypes */
	protected LayoutInflater mInflater;
	@SuppressLint("UseSparseArrays")//We can usually count Type instances on the fingers of a hand..
	private HashMap<Integer, T> mTypeInstances = new HashMap<Integer, T>();
	private boolean autoMap = false;

	/* Filter */
	private String mSearchText = "", mOldSearchText = "";
	private boolean mNotifyChangeOfUnfilteredItems = false, filtering = false;

	/* Expandable flags */
	private int minCollapsibleLevel = 0, selectedLevel = -1;
	private boolean scrollOnExpand = false, collapseOnExpand = false,
			childSelected = false, parentSelected = false;

	/* Drag&Drop and Swipe helpers */
	private boolean handleDragEnabled = true;
	private ItemTouchHelperCallback mItemTouchHelperCallback;
	private ItemTouchHelper mItemTouchHelper;

	/* EndlessScroll */
	private int mEndlessScrollThreshold = 1;
	private boolean mLoading = false;
	private T mProgressItem;

	/* Listeners */
	protected OnUpdateListener mUpdateListener;
	public OnItemClickListener mItemClickListener;
	public OnItemLongClickListener mItemLongClickListener;
	protected OnItemMoveListener mItemMoveListener;
	protected OnItemSwipeListener mItemSwipeListener;
	protected OnStickyHeaderChangeListener mStickyHeaderChangeListener;
	protected EndlessScrollListener mEndlessScrollListener;

	/*--------------*/
	/* CONSTRUCTORS */
	/*--------------*/

	/**
	 * Simple Constructor with NO listeners!
	 *
	 * @param items items to display.
	 */
	public FlexibleAdapter(@NonNull List<T> items) {
		this(items, null);
	}

	/**
	 * Main Constructor with all managed listeners for ViewHolder and the Adapter itself.
	 * <p>The listener must be a single instance of a class, usually <i>Activity</i> or <i>Fragment</i>,
	 * where you can implement how to handle the different events.</p>
	 * Any write operation performed on the items list is <u>synchronized</u>.
	 * <p><b>PASS ALWAYS A <u>COPY</u> OF THE ORIGINAL LIST</b>: <i>new ArrayList&lt;T&gt;(originalList);</i></p>
	 *
	 * @param items     items to display
	 * @param listeners can be an instance of:
	 *                  <br/>- {@link OnUpdateListener}
	 *                  <br/>- {@link OnItemClickListener}
	 *                  <br/>- {@link OnItemLongClickListener}
	 *                  <br/>- {@link OnItemMoveListener}
	 *                  <br/>- {@link OnItemSwipeListener}
	 *                  <br/>- {@link OnStickyHeaderChangeListener}
	 */
	public FlexibleAdapter(@NonNull List<T> items, @Nullable Object listeners) {
		mItems = Collections.synchronizedList(items);
		mRestoreList = new ArrayList<RestoreInfo>();
		mOrphanHeaders = new ArrayList<IHeader>();

		//Expand initial items
		//This works also after a screen rotation
		initializeItems();

		//Create listeners instances
		initializeListeners(listeners);

		//Get notified when items are inserted or removed (it adjusts selected positions)
		registerAdapterDataObserver(new AdapterDataObserver());
	}

	public void initializeListeners(@Nullable Object listeners) {
		if (listeners instanceof OnUpdateListener) {
			mUpdateListener = (OnUpdateListener) listeners;
			mUpdateListener.onUpdateEmptyView(mItems.size());
		}
		if (listeners instanceof OnItemClickListener)
			mItemClickListener = (OnItemClickListener) listeners;
		if (listeners instanceof OnItemLongClickListener)
			mItemLongClickListener = (OnItemLongClickListener) listeners;
		if (listeners instanceof OnItemMoveListener)
			mItemMoveListener = (OnItemMoveListener) listeners;
		if (listeners instanceof OnItemSwipeListener)
			mItemSwipeListener = (OnItemSwipeListener) listeners;
		if (listeners instanceof OnStickyHeaderChangeListener)
			mStickyHeaderChangeListener = (OnStickyHeaderChangeListener) listeners;
	}

	@Override
	public void onAttachedToRecyclerView(RecyclerView recyclerView) {
		super.onAttachedToRecyclerView(recyclerView);
		if (mStickyHeaderHelper != null && headersShown) {
			mStickyHeaderHelper.attachToRecyclerView(mRecyclerView);
		}
	}

	@Override
	public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
		if (mStickyHeaderHelper != null) {
			mStickyHeaderHelper.detachFromRecyclerView(mRecyclerView);
			mStickyHeaderHelper = null;
		}
		super.onDetachedFromRecyclerView(recyclerView);
	}

	/**
	 * Maps and expands items that are initially configured to be shown as expanded.
	 * <p>This method is also called after a screen rotation.</p>
	 */
	protected void initializeItems() {
		int position = 0;
		setInitialize(true);
		multiRange = true;
		while (position < mItems.size()) {
			T item = getItem(position);
			if (isExpandable(item)) {
				IExpandable expandable = (IExpandable) item;
				if (expandable.isExpanded()) {
					if (DEBUG) Log.v(TAG, "Initially expand item on position " + position);
					position += addAllSubItemsFrom(position, expandable, false, null);
				}
				if (!headersShown && isHeader(item) && !item.isHidden())
					headersShown = true;
			}
			position++;
		}
		multiRange = false;
		setInitialize(false);
	}

	/*------------------------------*/
	/* SELECTION METHODS OVERRIDDEN */
	/*------------------------------*/

	public boolean isEnabled(int position) {
		T item = getItem(position);
		return item != null && item.isEnabled();
	}

	@Override
	public boolean isSelectable(int position) {
		return getItem(position).isSelectable();
	}

	@Override
	public void toggleSelection(@IntRange(from = 0) int position) {
		T item = getItem(position);
		//Allow selection only for selectable items
		if (item != null && item.isSelectable()) {
			IExpandable parent = getExpandableOf(item);
			boolean hasParent = parent != null;
			if ((isExpandable(item) || !hasParent) && !childSelected) {
				//Allow selection of Parent if no Child has been previously selected
				parentSelected = true;
				if (hasParent) selectedLevel = parent.getExpansionLevel();
				super.toggleSelection(position);
			} else if (!parentSelected && hasParent && parent.getExpansionLevel() + 1 == selectedLevel
					|| selectedLevel == -1) {
				//Allow selection of Child of same level and if no Parent has been previously selected
				childSelected = true;
				selectedLevel = parent.getExpansionLevel() + 1;
				super.toggleSelection(position);
			}
		}

		//Reset flags if necessary, just to be sure
		if (getSelectedItemCount() == 0) {
			selectedLevel = -1;
			parentSelected = childSelected = false;
		}
	}

	/**
	 * Helper to automatically select all the items of the viewType equal to the viewType of
	 * the first selected item.
	 * <p>Examples:
	 * <br/>- if user initially selects an expandable of type A, then only expandable items of
	 * type A will be selected.
	 * <br/>- if user initially selects a non expandable of type B, then only items of Type B
	 * will be selected.
	 * <br/>- The developer can override this behaviour by passing a list of viewTypes for which
	 * he wants to force the selection.</p>
	 *
	 * @param viewTypes All the desired viewTypes to be selected, pass nothing to automatically
	 *                  select all the viewTypes of the first item user selected
	 */
	@Override
	public void selectAll(Integer... viewTypes) {
		if (getSelectedItemCount() > 0 && viewTypes.length == 0) {
			super.selectAll(getItemViewType(getSelectedPositions().get(0)));//Priority on the first item
		} else {
			super.selectAll(viewTypes);//Force the selection for the viewTypes passed
		}
	}

	@Override
	@CallSuper
	public void clearSelection() {
		parentSelected = childSelected = false;
		super.clearSelection();
	}

	public boolean isAnyParentSelected() {
		return parentSelected;
	}

	public boolean isAnyChildSelected() {
		return childSelected;
	}

	/*--------------*/
	/* MAIN METHODS */
	/*--------------*/

	/**
	 * This method will refresh the entire DataSet content.
	 *
	 * @param items the new data set
	 */
	public void updateDataSet(List<T> items) {
		animateTo(items);
		initializeItems();
		showAllHeaders();
	}

	/**
	 * Returns the custom object "Item".
	 * <p>This cannot be overridden since the entire library relies on it.</p>
	 *
	 * @param position the position of the item in the list
	 * @return The custom "Item" object or null if item not found
	 */
	public final T getItem(@IntRange(from = 0) int position) {
		if (position < 0 || position >= mItems.size()) return null;
		return mItems.get(position);
	}

	@Override
	public long getItemId(int position) {
		return position;
	}

	/**
	 * This cannot be overridden since the selection relies on it.
	 *
	 * @return the total number of the items currently displayed by the adapter
	 * @see #getItemCountOfTypes(Integer...)
	 * @see #getItemCountOfTypesUntil(int, Integer...)
	 * @see #isEmpty()
	 */
	@Override
	public final int getItemCount() {
		return mItems != null ? mItems.size() : 0;
	}

	/**
	 * Provides the number of items currently displayed of one or more certain types.
	 *
	 * @param viewTypes the viewTypes to count
	 * @return number of the viewTypes counted
	 * @see #getItemCount()
	 * @see #getItemCountOfTypesUntil(int, Integer...)
	 * @see #isEmpty()
	 */
	public int getItemCountOfTypes(Integer... viewTypes) {
		return getItemCountOfTypesUntil(getItemCount(), viewTypes);
	}

	/**
	 * Provides the number of items currently displayed of one or more certain types until
	 * the specified position.
	 * <p>Useful to retrieve the correct position where to insert the new items.</p>
	 *
	 * @param position  the position limit where to stop counting (included)
	 * @param viewTypes the viewTypes to count
	 * @see #getItemCount()
	 * @see #getItemCountOfTypes(Integer...)
	 * @see #isEmpty()
	 */
	public int getItemCountOfTypesUntil(@IntRange(from = 0) int position, Integer... viewTypes) {
		List<Integer> viewTypeList = Arrays.asList(viewTypes);
		int count = 0;
		for (int i = 0; i < position; i++) {
			//Privilege faster counting if autoMap is active
			if ((autoMap && viewTypeList.contains(mItems.get(i).getLayoutRes())) ||
					viewTypeList.contains(getItemViewType(i)))
				count++;
		}
		return count;
	}

	/**
	 * You can override this method to define your own concept of "Empty". This method is never
	 * called internally.
	 * <p>Default value is the result of {@code getItemCount() == 0}.</p>
	 *
	 * @return true if the list is empty, false otherwise
	 * @see #getItemCount()
	 * @see #getItemCountOfTypes(Integer...)
	 */
	public boolean isEmpty() {
		return getItemCount() == 0;
	}

	/**
	 * Retrieve the global position of the Item in the Adapter list.
	 *
	 * @param item the item to find
	 * @return the global position in the Adapter if found, -1 otherwise
	 */
	public int getGlobalPositionOf(@NonNull IFlexible item) {
		return item != null && mItems != null && !mItems.isEmpty() ? mItems.indexOf(item) : -1;
	}

	/**
	 * This method is never called internally.
	 *
	 * @param item the item to find
	 * @return true if the provided item is currently displayed, false otherwise
	 */
	public boolean contains(@NonNull T item) {
		return item != null && mItems != null && mItems.contains(item);
	}

	/*--------------------------*/
	/* HEADERS/SECTIONS METHODS */
	/*--------------------------*/

	/**
	 * @return true if all headers are currently displayed, false otherwise
	 */
	public boolean areHeadersShown() {
		return headersShown;
	}

	/**
	 * Sets if all headers should be shown at startup. To call before setting the headers!
	 * <p>Default value is false.</p>
	 *
	 * @param displayHeaders true to display them, false to keep them hidden
	 * @return this adapter so the call can be chained
	 */
	public FlexibleAdapter setDisplayHeadersAtStartUp(boolean displayHeaders) {
		setInitialize(true);
		headersShown = displayHeaders;
		if (displayHeaders) showAllHeaders();
		setInitialize(false);
		return this;
	}

	/**
	 * @return true if orphan headers will be removed when unlinked, false if are kept unlinked
	 * @see #setRemoveOrphanHeaders(boolean)
	 */
	public boolean isRemoveOrphanHeaders() {
		return removeOrphanHeaders;
	}

	/**
	 * Sets if the orphan headers will be deleted as well during the removal process.
	 * <p>Default value is false.</p>
	 *
	 * @param removeOrphanHeaders true to remove the header during the remove items
	 * @return this adapter so the call can be chained
	 * @see #getOrphanHeaders()
	 */
	public FlexibleAdapter setRemoveOrphanHeaders(boolean removeOrphanHeaders) {
		this.removeOrphanHeaders = removeOrphanHeaders;
		return this;
	}

	public FlexibleAdapter setUnlinkAllItemsOnRemoveHeaders(boolean unlinkOnRemoveHeader) {
		this.unlinkOnRemoveHeader = unlinkOnRemoveHeader;
		return this;
	}

	/**
	 * Provides the list of the headers remained unlinked "orphan headers",
	 * Orphan headers can appear from the user events (remove/move items).
	 *
	 * @return the list of the orphan headers collected until this moment
	 * @see #setRemoveOrphanHeaders(boolean)
	 */
	@NonNull
	public List<IHeader> getOrphanHeaders() {
		return mOrphanHeaders;
	}

	/**
	 * Adds or overwrites the header for the passed Sectionable item.
	 * <p>The header will be automatically displayed if all headers are currently shown and the
	 * header is currently hidden, otherwise it will be kept hidden.</p>
	 *
	 * @param item   the item that holds the header
	 * @param header the header item
	 * @return this adapter so the call can be chained
	 */
	public FlexibleAdapter linkHeaderTo(@NonNull T item, @NonNull IHeader header) {
		linkHeaderTo(item, header, null);
		if (header.isHidden() && headersShown) {
			showHeaderOf(getGlobalPositionOf(item), item);
		}
		return this;
	}

	/**
	 * Hides and completely removes the header from the Adapter and from the item that holds it.
	 * <p>No undo is possible.</p>
	 *
	 * @param item the item that holds the header
	 */
	public IHeader unlinkHeaderFrom(@NonNull T item) {
		IHeader header = unlinkHeaderFrom(item, null);
		if (header != null && !header.isHidden()) {
			hideHeaderOf(item);
		}
		return header;
	}

	/**
	 * Retrieves all the header items.
	 *
	 * @return non-null list with all the header items
	 */
	@NonNull
	public List<IHeader> getHeaderItems() {
		List<IHeader> headers = new ArrayList<IHeader>();
		for (T item : mItems) {
			if (isHeader(item))
				headers.add((IHeader) item);
		}
		return headers;
	}

	public boolean isHeader(@NonNull T item) {
		return item != null && item instanceof IHeader;
	}

	public boolean isHeader(int position) {
		T item = getItem(position);
		return item != null && item instanceof IHeader;
	}

	/**
	 * Returns if Adapter will display sticky headers on the top.
	 *
	 * @return true if headers can be sticky, false if headers are scrolled together with all items
	 */
	public boolean areHeadersSticky() {
		return headersSticky;
	}

	/**
	 * Enables the sticky header functionality.
	 * <p>Headers can be sticky only if they are shown. Command is otherwise ignored!</p>
	 * <b>NOTE:</b>
	 * <br/>- You must implement {@code getStickySectionHeadersHolder()}.
	 * <br/>- Sticky headers are now clickable as any Views, but cannot be dragged, swiped,
	 * moved nor deleted.
	 * <br/>- Content and linkage are automatically updated.
	 *
	 * @see #getStickySectionHeadersHolder()
	 */
	public void enableStickyHeaders() {
		setStickyHeaders(true);
	}

	/**
	 * Disables the sticky header functionality.
	 */
	public void disableStickyHeaders() {
		setStickyHeaders(false);
	}

	private void setStickyHeaders(boolean headersSticky) {
		// Add or Remove the sticky headers
		if (headersShown && headersSticky) {
			this.headersSticky = true;
			if (mStickyHeaderHelper == null)
				mStickyHeaderHelper = new StickyHeaderHelper(this, mStickyHeaderChangeListener);
			if (!mStickyHeaderHelper.isAttachedToRecyclerView())
				mStickyHeaderHelper.attachToRecyclerView(mRecyclerView);
		} else if (mStickyHeaderHelper != null) {
			this.headersSticky = false;
			mStickyHeaderHelper.detachFromRecyclerView(mRecyclerView);
			mStickyHeaderHelper = null;
		}
	}

	/**
	 * Returns the ViewGroup (FrameLayout) that will hold the headers when sticky.
	 * <p><b>INCLUDE</b> the predefined layout after the RecyclerView widget, example:
	 * <pre>&lt;android.support.v7.widget.RecyclerView
	 * android:id="@+id/recycler_view"
	 * android:layout_width="match_parent"
	 * android:layout_height="match_parent"/&gt;</pre>
	 * <pre>&lt;include layout="@layout/sticky_header_layout"/&gt;</pre></p>
	 * The ViewGroup must have {@code @+id/sticky_header_container}.
	 * <p><b>OR</b></p>
	 * Implement this method to return a ViewGroup.
	 *
	 * @return ViewGroup layout that will hold the sticky headers ItemViews
	 */
	public ViewGroup getStickySectionHeadersHolder() {
		return (ViewGroup) ((Activity) mRecyclerView.getContext()).findViewById(R.id.sticky_header_container);
	}

	/**
	 * Helper for the Adapter to check if an item holds a header
	 *
	 * @param item the identified item
	 * @return true if the item holds a header, false otherwise
	 */
	public boolean hasHeader(@NonNull T item) {
		return getHeaderOf(item) != null;
	}

	/**
	 * Checks if the item has a header and that header is the same of the provided one.
	 *
	 * @param item   the item supposing having a header
	 * @param header the header to compare
	 * @return true if the item has a header and it is the same of the provided one, false otherwise
	 */
	public boolean hasSameHeader(@NonNull T item, @NonNull IHeader header) {
		IHeader current = getHeaderOf(item);
		return current != null && header != null && current.equals(header);
	}

	/**
	 * Provides the header of the specified Sectionable.
	 *
	 * @param item the ISectionable item holding a header
	 * @return the header of the passed Sectionable, null otherwise
	 */
	public IHeader getHeaderOf(@NonNull T item) {
		if (item != null && item instanceof ISectionable) {
			return ((ISectionable) item).getHeader();
		}
		return null;
	}

	/**
	 * Retrieves the {@link IHeader} item of any specified position.
	 *
	 * @param position the item position
	 * @return the IHeader item linked to the specified item position
	 */
	public IHeader getSectionHeader(@IntRange(from = 0) int position) {
		//Headers are not visible nor sticky
		if (!headersShown) return null;
		//When headers are visible and sticky, get the previous header
		for (int i = position; i >= 0; i--) {
			T item = getItem(i);
			if (isHeader(item)) return (IHeader) item;
		}
		return null;
	}

	/**
	 * Retrieves the index of the specified header/section item.
	 * <p>Counts the headers until this one.</p>
	 *
	 * @param header the header/section item
	 * @return the index of the specified header/section
	 */
	public int getSectionIndex(@NonNull IHeader header) {
		int position = getGlobalPositionOf(header);
		return getSectionIndex(position);
	}

	/**
	 * Retrieves the header/section index of any specified position.
	 * <p>Counts the headers until this one.</p>
	 *
	 * @param position any item position
	 * @return the index of the specified item position
	 */
	public int getSectionIndex(@IntRange(from = 0) int position) {
		int sectionIndex = 0;
		for (int i = 0; i <= position; i++) {
			if (isHeader(getItem(i))) sectionIndex++;
		}
		return sectionIndex;
	}

	/**
	 * Provides all the items that belongs to the section represented by the specified header.
	 *
	 * @param header the header that represents the section
	 * @return NonNull list of all items in the specified section.
	 */
	@NonNull
	public List<ISectionable> getSectionItems(@NonNull IHeader header) {
		List<ISectionable> sectionItems = new ArrayList<ISectionable>();
		int startPosition = getGlobalPositionOf(header);
		T item = getItem(++startPosition);
		while (item != null && !isHeader(item)) {
			sectionItems.add((ISectionable) item);
			item = getItem(++startPosition);
		}
		return sectionItems;
	}

	/**
	 * Shows all headers in the RecyclerView at their linked position.
	 * <p>Headers can be shown or hidden all together.</p>
	 *
	 * @see #hideAllHeaders()
	 */
	public void showAllHeaders() {
		multiRange = true;
		//Show linked headers only
		resetHiddenStatus();
		int position = 0;
		while (position < mItems.size()) {
			if (showHeaderOf(position, mItems.get(position)))
				position++;//It's the same element, skip it.
			position++;
		}
		headersShown = true;
		multiRange = false;
	}

	/**
	 * Hides all headers from the RecyclerView.
	 * <p>Headers can be shown or hidden all together.</p>
	 *
	 * @see #showAllHeaders()
	 */
	public void hideAllHeaders() {
		multiRange = true;
		//Hide orphan headers first
		for (IHeader header : getOrphanHeaders()) {
			hideHeader(getGlobalPositionOf(header), header);
		}
		//Hide linked headers
		int position = mItems.size() - 1;
		while (position >= 0) {
			if (hideHeaderOf(mItems.get(position)))
				position--;//It's the same element, skip it.
			position--;
		}
		headersShown = false;
		setStickyHeaders(false);
		multiRange = false;
	}

	/**
	 * Helper method to ensure that all current headers are hidden before they are shown again.
	 * <p>This method is already called inside {@link #showAllHeaders()}.</p>
	 * This is necessary when {@link #setDisplayHeadersAtStartUp(boolean)} is set true and also
	 * if an Activity/Fragment has been closed and then reopened. We need to reset hidden status,
	 * the process is very fast.
	 */
	private void resetHiddenStatus() {
		for (T item : mItems) {
			IHeader header = getHeaderOf(item);
			if (header != null && !isExpandable((T) header))
				header.setHidden(true);
		}
	}

	/**
	 * Internal method to show/add a header in the internal list.
	 *
	 * @param position the position where the header will be displayed
	 * @param item     the item that holds the header
	 */
	private boolean showHeaderOf(int position, @NonNull T item) {
		//Take the header
		IHeader header = getHeaderOf(item);
		//Check header existence
		if (header == null || getPendingRemovedItem(item) != null) return false;
		if (header.isHidden()) {
			if (DEBUG) Log.v(TAG, "Showing header at position " + position + "=" + header);
			header.setHidden(false);
			return addItem(position, (T) header);
		}
		return false;
	}

	/**
	 * Internal method to hide/remove a header from the internal list.
	 *
	 * @param item the item that holds the header
	 */
	private boolean hideHeaderOf(@NonNull T item) {
		//Take the header
		IHeader header = getHeaderOf(item);
		//Check header existence
		return header != null && !header.isHidden() && hideHeader(getGlobalPositionOf(header), header);
	}

	private boolean hideHeader(int position, IHeader header) {
		if (position >= 0) {
			if (DEBUG) Log.v(TAG, "Hiding header at position " + position + "=" + header);
			header.setHidden(true);
			//Remove and notify removals
			mItems.remove(position);
			notifyItemRemoved(position);
			return true;
		}
		return false;
	}

	/**
	 * Internal method to link the header to the new item.
	 * <p>Used by the Adapter during the Remove/Restore/Move operations.</p>
	 * The new item looses the previous header, and if the old header is not shared,
	 * old header is added to the orphan list.
	 *
	 * @param item    the item that holds the header
	 * @param header  the header item
	 * @param payload any non-null user object to notify the header and the item (the payload
	 *                will be therefore passed to the bind method of the items ViewHolder),
	 *                pass null to <u>not</u> notify the header and item
	 */
	private boolean linkHeaderTo(@NonNull T item, @NonNull IHeader header, @Nullable Object payload) {
		boolean linked = false;
		if (item != null && item instanceof ISectionable) {
			ISectionable sectionable = (ISectionable) item;
			//Unlink header only if different
			if (sectionable.getHeader() != null && !sectionable.getHeader().equals(header)) {
				unlinkHeaderFrom((T) sectionable, payload);
			}
			if (sectionable.getHeader() == null && header != null) {
				if (DEBUG) Log.v(TAG, "Link header " + header + " to " + sectionable);
				sectionable.setHeader(header);
				linked = true;
				removeFromOrphanList(header);
				//Notify items
				if (payload != null) {
					if (!header.isHidden()) notifyItemChanged(getGlobalPositionOf(header), payload);
					if (!item.isHidden()) notifyItemChanged(getGlobalPositionOf(item), payload);
				}
			}
		} else {
			addToOrphanListIfNeeded(header, getGlobalPositionOf(item), 1);
			notifyItemChanged(getGlobalPositionOf(header), payload);
		}
		return linked;
	}

	/**
	 * Internal method to unlink the header from the specified item.
	 * <p>Used by the Adapter during the Remove/Restore/Move operations.</p>
	 *
	 * @param item    the item that holds the header
	 * @param payload any non-null user object to notify the header and the item (the payload
	 *                will be therefore passed to the bind method of the items ViewHolder),
	 *                pass null to <u>not</u> notify the header and item
	 */
	private IHeader unlinkHeaderFrom(@NonNull T item, @Nullable Object payload) {
		if (hasHeader(item)) {
			ISectionable sectionable = (ISectionable) item;
			IHeader header = sectionable.getHeader();
			if (DEBUG) Log.v(TAG, "Unlink header " + header + " from " + sectionable);
			sectionable.setHeader(null);
			addToOrphanListIfNeeded(header, getGlobalPositionOf(item), 1);
			//Notify items
			if (payload != null) {
				if (!header.isHidden()) notifyItemChanged(getGlobalPositionOf(header), payload);
				if (!item.isHidden()) notifyItemChanged(getGlobalPositionOf(item), payload);
			}
			return header;
		}
		return null;
	}

	private void addToOrphanListIfNeeded(IHeader header, int positionStart, int itemCount) {
		//Check if the header is not already added (happens after un-linkage with un-success linkage)
		if (!mOrphanHeaders.contains(header) && !isHeaderShared(header, positionStart, itemCount)) {
			mOrphanHeaders.add(header);
			if (DEBUG)
				Log.d(TAG, "Added to orphan list [" + mOrphanHeaders.size() + "] Header " + header);
		}
	}

	private void removeFromOrphanList(IHeader header) {
		if (mOrphanHeaders.remove(header) && DEBUG)
			Log.d(TAG, "Removed from orphan list [" + mOrphanHeaders.size() + "] Header " + header);
	}

	private boolean isHeaderShared(IHeader header, int positionStart, int itemCount) {
		int firstElementWithHeader = getGlobalPositionOf(header) + 1;
		for (int i = firstElementWithHeader; i < mItems.size(); i++) {
			T item = getItem(i);
			//Another header is met, we can stop here
			if (item instanceof IHeader) break;
			//Skip the items under modification
			if (i >= positionStart && i < positionStart + itemCount) continue;
			//An element with same header is met
			if (hasSameHeader(item, header))
				return true;
		}
		return false;
	}

	/*---------------------*/
	/* VIEW HOLDER METHODS */
	/*---------------------*/

	/**
	 * Returns the ViewType for all Items depends by the current position.
	 * <p>You can override this method to return specific values or you can let this method
	 * to call the implementation of {@link IFlexible#getLayoutRes()} so ViewTypes are
	 * automatically mapped.</p>
	 *
	 * @param position position for which ViewType is requested
	 * @return if Item is found, any integer value from user layout resource if defined in
	 * {@code IFlexible#getLayoutRes()}
	 */
	@Override
	public int getItemViewType(int position) {
		T item = getItem(position);
		assert item != null;
		//Map the view type if not done yet
		mapViewTypeFrom(item);
		autoMap = true;
		return item.getLayoutRes();
	}

	/**
	 * You can override this method to create ViewHolder from inside the Adapter or
	 * you can let this method to call the implementation of
	 * {@link IFlexible#createViewHolder(FlexibleAdapter, LayoutInflater, ViewGroup)}
	 * to create ViewHolder from inside the Item.
	 *
	 * @param parent   the ViewGroup into which the new View will be added after it is bound
	 *                 to an adapter position
	 * @param viewType the view type of the new View
	 * @return a new ViewHolder that holds a View of the given view type
	 * @throws IllegalStateException if {@link IFlexible#createViewHolder(FlexibleAdapter, LayoutInflater, ViewGroup)}
	 *                               is not implemented and if this method is not overridden. Also
	 *                               it is thrown if ViewType instance has not been correcly mapped.
	 * @see IFlexible#createViewHolder(FlexibleAdapter, LayoutInflater, ViewGroup)
	 */
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		if (mInflater == null) {
			mInflater = LayoutInflater.from(parent.getContext());
		}
		T item = getViewTypeInstance(viewType);
		if (item == null) {
			//If everything has been set properly, this should never happen ;-)
			Log.wtf(TAG, "ViewType instance has not been correctly mapped for viewType " + viewType);
			throw new IllegalStateException("ViewType instance has not been correctly mapped for viewType " + viewType);
		}
		return item.createViewHolder(this, mInflater, parent);
	}

	/**
	 * You can override this method to bind the items into the corresponding ViewHolder from
	 * inside the Adapter or you can let this method to call the implementation of
	 * {@link IFlexible#bindViewHolder(FlexibleAdapter, RecyclerView.ViewHolder, int, List)}
	 * to bind the item inside itself.
	 *
	 * @param holder   the ViewHolder created
	 * @param position the adapter position to bind
	 * @throws IllegalStateException if {@link IFlexible#bindViewHolder(FlexibleAdapter, RecyclerView.ViewHolder, int, List)}
	 *                               is not implemented and if this method is not overridden.
	 * @see IFlexible#bindViewHolder(FlexibleAdapter, RecyclerView.ViewHolder, int, List)
	 */
	@Override
	public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
		this.onBindViewHolder(holder, position, Collections.unmodifiableList(new ArrayList<Object>()));
	}

	/**
	 * Same concept of {@link #onBindViewHolder(RecyclerView.ViewHolder, int)}, but with Payload.
	 * <p>How to use Payload, please refer to
	 * {@link RecyclerView.Adapter#onBindViewHolder(RecyclerView.ViewHolder, int, List)}.</p>
	 *
	 * @param holder   the ViewHolder instance
	 * @param position the current position
	 * @param payloads a non-null list of merged payloads. Can be empty list if requires full update.
	 * @throws IllegalStateException if {@link IFlexible#bindViewHolder(FlexibleAdapter, RecyclerView.ViewHolder, int, List)}
	 *                               is not implemented and if this method is not overridden.
	 */
	@Override
	public void onBindViewHolder(RecyclerView.ViewHolder holder, int position, List payloads) {
		//When user scrolls, this line binds the correct selection status
		holder.itemView.setActivated(isSelected(position));
		//Bind the correct view elevation
		if (holder instanceof FlexibleViewHolder) {
			FlexibleViewHolder flexHolder = (FlexibleViewHolder) holder;
			if (holder.itemView.isActivated() && flexHolder.getActivationElevation() > 0)
				ViewCompat.setElevation(flexHolder.itemView, flexHolder.getActivationElevation());
			else if (flexHolder.getActivationElevation() > 0)//Leave unaltered the default elevation
				ViewCompat.setElevation(flexHolder.itemView, 0);
		}
		if (!autoMap) {
			super.onBindViewHolder(holder, position, payloads);
		} else {
			//Bind the item
			T item = getItem(position);
			if (item != null) {
				holder.itemView.setEnabled(item.isEnabled());
				item.bindViewHolder(this, holder, position, payloads);
			}
		}

		//Endless Scroll
		onLoadMore(position);
	}

	/*------------------------*/
	/* ENDLESS SCROLL METHODS */
	/*------------------------*/

	/**
	 * Sets the callback to load more items asynchronously.
	 *
	 * @param endlessScrollListener the callback to invoke the asynchronous loading
	 * @param progressItem          the item representing the progress bar
	 */
	public void setEndlessScrollListener(@NonNull EndlessScrollListener endlessScrollListener,
										 @NonNull T progressItem) {
		if (endlessScrollListener != null) {
			mEndlessScrollListener = endlessScrollListener;
			setEndlessScrollThreshold(mEndlessScrollThreshold);
			progressItem.setEnabled(false);
			mProgressItem = progressItem;
		}
	}

	/**
	 * Sets the minimum number of items still to bind to start the automatic loading.
	 * <p>Default value is 1.</p>
	 *
	 * @param thresholdItems minimum number of unbound items to start loading more items
	 */
	public void setEndlessScrollThreshold(@IntRange(from = 1) int thresholdItems) {
		//Increase visible threshold based on number of columns
		if (mRecyclerView != null) {
			int spanCount = getSpanCount(mRecyclerView.getLayoutManager());
			thresholdItems = thresholdItems * spanCount;
		}
		mEndlessScrollThreshold = thresholdItems;
	}

	private void onLoadMore(int position) {
		if (mEndlessScrollListener != null && getGlobalPositionOf(mProgressItem) < 0
				&& position >= getItemCount() - mEndlessScrollThreshold) {
			if (!mLoading) {
				mLoading = true;
				mRecyclerView.post(new Runnable() {
					@Override
					public void run() {
						mItems.add(mProgressItem);
						notifyItemInserted(getItemCount());
						mEndlessScrollListener.onLoadMore();
					}
				});
			}
		}
	}

	/**
	 * To call when more items are successfully loaded.
	 *
	 * @param newItems the list of the new items
	 */
	public void onLoadMoreComplete(@Nullable List<T> newItems) {
		int progressPosition = getGlobalPositionOf(mProgressItem);
		if (progressPosition >= 0) {
			mItems.remove(mProgressItem);
			notifyItemRemoved(progressPosition);
		}
		if (newItems != null && newItems.size() > 0) {
			if (DEBUG) Log.d(TAG, "onLoadMore Complete adding " + newItems.size() + " new Items!");
			addItems(getItemCount(), newItems);
			mHandler.sendEmptyMessageDelayed(2, 200L);
		} else {
			noMoreLoad();
		}
	}

	/**
	 * Called when no more items are loaded.
	 */
	private void noMoreLoad() {
		if (DEBUG) Log.d(TAG, "onLoadMore noMoreLoad!");
		notifyItemChanged(getItemCount() - 1, true);
		mHandler.sendEmptyMessageDelayed(2, 200L);
	}

	private void resetOnLoadMore() {
		mLoading = false;
	}

	/*--------------------*/
	/* EXPANDABLE METHODS */
	/*--------------------*/

	/**
	 * Automatically collapse all previous expanded parents before expand the clicked parent.
	 * <p>Default value is disabled.</p>
	 *
	 * @param collapseOnExpand true to collapse others items, false to just expand the current
	 */
	public void setAutoCollapseOnExpand(boolean collapseOnExpand) {
		this.collapseOnExpand = collapseOnExpand;
	}

	/**
	 * Automatically scroll the clicked expandable item to the first visible position.<br/>
	 * Default value is disabled.
	 * <p>This works ONLY in combination with {@link SmoothScrollLinearLayoutManager}.
	 * GridLayout is still NOT supported.</p>
	 *
	 * @param scrollOnExpand true to enable automatic scroll, false to disable
	 */
	public void setAutoScrollOnExpand(boolean scrollOnExpand) {
		this.scrollOnExpand = scrollOnExpand;
	}

	public boolean isExpanded(@IntRange(from = 0) int position) {
		return isExpanded(getItem(position));
	}

	public boolean isExpanded(@NonNull T item) {
		if (isExpandable(item)) {
			IExpandable expandable = (IExpandable) item;
			return expandable.isExpanded();
		}
		return false;
	}

	public boolean isExpandable(@NonNull T item) {
		return item != null && item instanceof IExpandable;
	}

	public int getMinCollapsibleLevel() {
		return minCollapsibleLevel;
	}

	public void setMinCollapsibleLevel(int minCollapsibleLevel) {
		this.minCollapsibleLevel = minCollapsibleLevel;
	}

	public boolean hasSubItems(@NonNull IExpandable expandable) {
		return expandable != null && expandable.getSubItems() != null &&
				expandable.getSubItems().size() > 0;
	}

	public IExpandable getExpandableOf(@IntRange(from = 0) int position) {
		return getExpandableOf(getItem(position));
	}

	/**
	 * Retrieves the parent of a child.
	 * <p>Only for a real child of an expanded parent.</p>
	 *
	 * @param child the child item
	 * @return the parent of this child item or null if not found
	 * @see #getExpandablePositionOf(IFlexible)
	 * @see #getRelativePositionOf(IFlexible)
	 */
	public IExpandable getExpandableOf(@NonNull T child) {
		for (T parent : mItems) {
			if (isExpandable(parent)) {
				IExpandable expandable = (IExpandable) parent;
				if (expandable.isExpanded() && hasSubItems(expandable)) {
					List<T> list = expandable.getSubItems();
					for (T subItem : list) {
						//Pick up only no-hidden items
						if (!subItem.isHidden() && subItem.equals(child))
							return expandable;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Retrieves the parent position of a child.
	 * <p>Only for a real child of an expanded parent.</p>
	 *
	 * @param child the child item
	 * @return the parent position of this child item or -1 if not found
	 * @see #getExpandableOf(IFlexible)
	 * @see #getRelativePositionOf(IFlexible)
	 */
	public int getExpandablePositionOf(@NonNull T child) {
		return getGlobalPositionOf(getExpandableOf(child));
	}

	/**
	 * Provides the list where the child currently lays.
	 *
	 * @param child the child item
	 * @return the list of the child element, or a new list if item
	 * @see #getExpandableOf(IFlexible)
	 * @see #getExpandablePositionOf(IFlexible)
	 * @see #getRelativePositionOf(IFlexible)
	 * @see #getExpandedItems()
	 */
	@NonNull
	public List<T> getSiblingsOf(@NonNull T child) {
		return getExpandableList(getExpandableOf(child));
	}

	/**
	 * Retrieves the position of a child in the list where it lays.
	 * <p>Only for a real child of an expanded parent.</p>
	 *
	 * @param child the child item
	 * @return the position in the parent or -1 if, child is a parent itself or not found
	 * @see #getExpandableOf(IFlexible)
	 * @see #getExpandablePositionOf(IFlexible)
	 */
	public int getRelativePositionOf(@NonNull T child) {
		return getSiblingsOf(child).indexOf(child);
	}

	/**
	 * Provides a list of all expandable items that are currently expanded.
	 *
	 * @return a list with all expanded items
	 * @see #getSiblingsOf(IFlexible)
	 * @see #getExpandedPositions()
	 */
	@NonNull
	public List<T> getExpandedItems() {
		List<T> expandedItems = new ArrayList<T>();
		for (T item : mItems) {
			if (isExpanded(item))
				expandedItems.add(item);
		}
		return expandedItems;
	}

	/**
	 * Provides a list of all expandable positions that are currently expanded.
	 *
	 * @return a list with the global positions of all expanded items
	 * @see #getSiblingsOf(IFlexible)
	 * @see #getExpandedItems()
	 */
	@NonNull
	public List<Integer> getExpandedPositions() {
		List<Integer> expandedPositions = new ArrayList<Integer>();
		for (int i = 0; i < mItems.size() - 1; i++) {
			T item = mItems.get(i);
			if (isExpanded(item))
				expandedPositions.add(i);
		}
		return expandedPositions;
	}

	/**
	 * Expands an item that is Expandable, not yet expanded, that has subItems and
	 * no child is selected.
	 *
	 * @param position the position of the item to expand
	 * @return the number of subItems expanded
	 */
	public int expand(@IntRange(from = 0) int position) {
		return expand(position, false);
	}

	private int expand(int position, boolean expandAll) {
		T item = getItem(position);
		if (!isExpandable(item)) return 0;

		IExpandable expandable = (IExpandable) item;
		if (DEBUG) Log.v(TAG, "Request to Expand on position " + position +
				" expanded " + expandable.isExpanded() + " ExpandedItems=" + getExpandedPositions());

		int subItemsCount = 0;
		if (!expandable.isExpanded() && hasSubItems(expandable)
				&& (!parentSelected || expandable.getExpansionLevel() <= selectedLevel)
				&& (mStickyHeaderHelper == null || !mStickyHeaderHelper.hasStickyHeaderTranslated(position))) {

			//Collapse others expandable if configured so
			//Skipped when expanding all is requested
			//Fetch again the new position after collapsing all!!
			if ((collapseOnExpand && !expandAll) && collapseAll(minCollapsibleLevel) > 0) {
				position = getGlobalPositionOf(item);
			}

			//Every time an expansion is requested, subItems must be taken from the original Object!
			//without the subItems that are going to be removed
			//Save a copy child items list
			List<T> subItems = getExpandableList(expandable);
			mItems.addAll(position + 1, subItems);
			subItemsCount = subItems.size();
			//Save expanded state
			expandable.setExpanded(true);

			//Automatically scroll the current expandable item to show as much children as possible
			if (scrollOnExpand && !expandAll) {
				//Must be delayed to give time at RecyclerView to recalculate positions
				//after an automatic collapse
				final int pos = position, count = subItemsCount;
				Handler animatorHandler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
					public boolean handleMessage(Message message) {
						autoScroll(pos, count);
						return true;
					}
				});
				animatorHandler.sendMessageDelayed(Message.obtain(mHandler), !headersSticky ? 150L : 300L);
			}

			//Expand!
			notifyItemRangeInserted(position + 1, subItemsCount);
			//Show also the headers of the subItems
			if (headersShown) {
				int count = 0;
				for (T subItem : subItems) {
					if (showHeaderOf(position + (++count), subItem)) count++;
				}
			}

			if (DEBUG)
				Log.v(TAG, "Expanded " + subItemsCount + " subItems on position=" + position + " ExpandedItems=" + getExpandedPositions());
		}
		return subItemsCount;
	}

	/**
	 * Expands all expandable items with minimum of level {@link #minCollapsibleLevel}.
	 *
	 * @return the number of parent successfully expanded
	 * @see #expandAll(int)
	 * @see #setMinCollapsibleLevel(int)
	 */
	public int expandAll() {
		return expandAll(minCollapsibleLevel);
	}

	/**
	 * Expands all expandable items with at least the specified level.
	 *
	 * @param level the minimum level to expand the sub expandable items
	 * @return the number of parent successfully expanded
	 * @see #expandAll()
	 */
	public int expandAll(int level) {
		int expanded = 0;
		//More efficient if we expand from First expandable position
		for (int i = 0; i < mItems.size(); i++) {
			T item = getItem(i);
			if (isExpandable(item)) {
				IExpandable expandable = (IExpandable) item;
				if (expandable.getExpansionLevel() <= level && expand(i, true) > 0) {
					expanded++;
				}
			}
		}
		return expanded;
	}

	/**
	 * Collapses an Expandable item that is already expanded, in conjunction with no subItems
	 * selected or item is pending removal (used in combination with removeRange).
	 * <p>All Expandable subItem, that are expanded, are recursively collapsed.</p>
	 *
	 * @param position the position of the item to collapse
	 * @return the number of subItems collapsed
	 * @see #collapse(int, int)
	 * @see #setMinCollapsibleLevel(int)
	 */
	public int collapse(@IntRange(from = 0) int position) {
		return collapse(position, minCollapsibleLevel);
	}

	/**
	 * Collapses an Expandable item that is already expanded, in conjunction with no subItems
	 * selected or item is pending removal (used in combination with removeRange).
	 * <p>All Expandable subItem, that are expanded, are recursively collapsed.</p>
	 *
	 * @param position the position of the item to collapse
	 * @param level    all the levels to collapse
	 * @return the number of subItems collapsed
	 * @see #collapse(int)
	 */
	public int collapse(@IntRange(from = 0) int position, int level) {
		T item = getItem(position);
		if (!isExpandable(item)) return 0;

		IExpandable expandable = (IExpandable) item;
		if (DEBUG)
			Log.v(TAG, "Request to Collapse on position " + position + " ExpandedItems=" + getExpandedPositions());

		int subItemsCount = 0, recursiveCount = 0;
		if (expandable.isExpanded() &&
				(!hasSubItemsSelected(expandable) || getPendingRemovedItem(item) != null)) {

			//Take the current subList
			List<T> subItems = getExpandableList(expandable);
			//Recursive collapse of all sub expandable
			recursiveCount = recursiveCollapse(subItems, expandable.getExpansionLevel());
			mItems.removeAll(subItems);
			subItemsCount = subItems.size();
			//Save expanded state
			expandable.setExpanded(false);

			//Collapse!
			notifyItemRangeRemoved(position + 1, subItemsCount);
			//Hide also the headers of the subItems
			if (headersShown && !isHeader(item)) {
				for (T subItem : subItems) {
					hideHeaderOf(subItem);
				}
			}

			if (DEBUG)
				Log.v(TAG, "Collapsed " + subItemsCount + " subItems on position=" + position + " ExpandedItems=" + getExpandedPositions());
		}
		return subItemsCount + recursiveCount;
	}

	@SuppressWarnings("Range")
	private int recursiveCollapse(List<T> subItems, int level) {
		int collapsed = 0;
		for (int i = subItems.size() - 1; i >= 0; i--) {
			T subItem = subItems.get(i);
			if (isExpanded(subItem)) {
				IExpandable expandable = (IExpandable) subItem;
				if (expandable.getExpansionLevel() >= level &&
						collapse(getGlobalPositionOf(subItem), level) > 0) {
					collapsed++;
				}
			}
		}
		return collapsed;
	}

	/**
	 * Collapses all expandable items with the minimum level of {@link #minCollapsibleLevel}.
	 *
	 * @return the number of parent successfully collapsed
	 * @see #collapseAll(int)
	 * @see #setMinCollapsibleLevel(int)
	 */
	public int collapseAll() {
		return collapseAll(minCollapsibleLevel);
	}

	/**
	 * Collapses all expandable items with the lower of the specified level.
	 *
	 * @param level all the levels to collapse
	 * @return the number of parent successfully collapsed
	 * @see #collapseAll()
	 */
	public int collapseAll(int level) {
		return recursiveCollapse(mItems, level);
	}

	/*----------------*/
	/* UPDATE METHODS */
	/*----------------*/

	/**
	 * Updates/Rebounds the ItemView corresponding to the current position of that item, with
	 * the new content provided.
	 *
	 * @param item    the item with the new content
	 * @param payload any non-null user object to notify the current item (the payload will be
	 *                therefore passed to the bind method of the item ViewHolder to optimize the
	 *                content to update); pass null to rebind all fields of this item.
	 */
	public void updateItem(@NonNull T item, @Nullable Object payload) {
		updateItem(getGlobalPositionOf(item), item, payload);
	}

	/**
	 * Updates/Rebounds the ItemView corresponding to the provided position with the new
	 * provided content. Use {@link #updateItem(IFlexible, Object)} if the new content should
	 * be bound on the same position.
	 *
	 * @param position the position where the new content should be updated and rebound
	 * @param item     the item with the new content
	 * @param payload  any non-null user object to notify the current item (the payload will be
	 *                 therefore passed to the bind method of the item ViewHolder to optimize the
	 *                 content to update); pass null to rebind all fields of this item.
	 */
	public void updateItem(@IntRange(from = 0) int position, @NonNull T item,
						   @Nullable Object payload) {
		if (position < 0 || position >= mItems.size()) {
			Log.e(TAG, "Cannot updateItem on position out of OutOfBounds!");
			return;
		}
		mItems.set(position, item);
		if (DEBUG) Log.v(TAG, "updateItem notifyItemChanged on position " + position);
		notifyItemChanged(position, payload);
	}

	/*----------------*/
	/* ADDING METHODS */
	/*----------------*/

	/**
	 * Inserts the given Item at desired position or Add Item at last position with a delay
	 * and auto-scroll to the position.
	 * <p>Useful at startup, when there's an item to add after Adapter Animations is completed.</p>
	 *
	 * @param position         position of the item to add
	 * @param item             the item to add
	 * @param delay            a non negative delay
	 * @param scrollToPosition true if RecyclerView should scroll after item has been added,
	 *                         false otherwise
	 * @see #addItem(int, IFlexible)
	 * @see #addItems(int, List)
	 * @see #addSubItems(int, int, IExpandable, List, boolean, Object)
	 */
	public void addItemWithDelay(@IntRange(from = 0) final int position, @NonNull final T item,
								 @IntRange(from = 0) long delay, final boolean scrollToPosition) {
		mHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (addItem(position, item) && scrollToPosition && mRecyclerView != null) {
					mRecyclerView.scrollToPosition(
							Math.min(Math.max(0, position), getItemCount() - 1));
				}
			}
		}, delay);
	}

	/**
	 * Inserts the given item in the internal list at the specified position or Adds the item
	 * at last position.
	 *
	 * @param position position of the item to add
	 * @param item     the item to add
	 * @return true if the internal list was successfully modified, false otherwise
	 * @see #addItemWithDelay(int, IFlexible, long, boolean)
	 * @see #addItems(int, List)
	 * @see #addSubItems(int, int, IExpandable, List, boolean, Object)
	 */
	public boolean addItem(@IntRange(from = 0) int position, @NonNull T item) {
		if (item == null) {
			Log.e(TAG, "No items to add!");
			return false;
		}
		if (DEBUG) Log.v(TAG, "addItem delegates addition to addItems!");
		List<T> items = new ArrayList<T>(1);
		items.add(item);
		return addItems(position, items);
	}

	/**
	 * Inserts a set of items in the internal list at specified position or Adds the items
	 * at last position.
	 * <p><b>NOTE:</b> When all headers are shown, the header (if exists) of this item will be
	 * shown as well, unless it's already shown, so it will not be shown twice.</p>
	 *
	 * @param position position inside the list, -1 to add the set the end of the list
	 * @param items    the items to add
	 * @return true if the internal list was successfully modified, false otherwise
	 * @see #addItem(int, IFlexible)
	 * @see #addSubItems(int, int, IExpandable, List, boolean, Object)
	 */
	public boolean addItems(@IntRange(from = 0) int position, @NonNull List<T> items) {
		if (position < 0) {
			Log.e(TAG, "Cannot addItems on negative position!");
			return false;
		}
		if (items == null || items.isEmpty()) {
			Log.e(TAG, "No items to add!");
			return false;
		}
		if (DEBUG) Log.v(TAG, "addItems on position=" + position + " itemCount=" + items.size());

		//Insert Items
		if (position < mItems.size()) {
			mItems.addAll(position, items);
		} else {
			mItems.addAll(items);
		}
		//Notify range addition
		notifyItemRangeInserted(position, items.size());

		//Show the headers of these items if all headers are already visible
		if (headersShown && !recursive) {
			recursive = true;
			for (T item : items)
				showHeaderOf(getGlobalPositionOf(item), item);//We have to find the correct position!
			recursive = false;
		}
		//Call listener to update EmptyView
		if (!recursive && mUpdateListener != null && !multiRange)
			mUpdateListener.onUpdateEmptyView(getItemCount());
		return true;
	}

	/**
	 * Convenience method of {@link #addSubItem(int, int, IFlexible, boolean, Object)}.
	 * <br/>In this case parent item will never be notified nor expanded if it is collapsed.
	 *
	 * @return true if the internal list was successfully modified, false otherwise
	 * @see #addSubItems(int, int, IExpandable, List, boolean, Object)
	 */
	public boolean addSubItem(@IntRange(from = 0) int parentPosition,
							  @IntRange(from = 0) int subPosition, @NonNull T item) {
		return this.addSubItem(parentPosition, subPosition, item, false, null);
	}

	/**
	 * Convenience method of {@link #addSubItems(int, int, IExpandable, List, boolean, Object)}.
	 * <br/>Optionally you can pass any payload to notify the parent about the change and optimize
	 * the view binding.
	 *
	 * @param parentPosition position of the expandable item that shall contain the subItem
	 * @param subPosition    the position of the subItem in the expandable list
	 * @param item           the subItem to add in the expandable list
	 * @param expandParent   true to initially expand the parent (if needed) and after to add
	 *                       the subItem, false to simply add the subItem to the parent
	 * @param payload        any non-null user object to notify the parent (the payload will be
	 *                       therefore passed to the bind method of the parent ViewHolder),
	 *                       pass null to <u>not</u> notify the parent
	 * @return true if the internal list was successfully modified, false otherwise
	 * @see #addSubItems(int, int, IExpandable, List, boolean, Object)
	 */
	public boolean addSubItem(@IntRange(from = 0) int parentPosition,
							  @IntRange(from = 0) int subPosition,
							  @NonNull T item, boolean expandParent, @Nullable Object payload) {
		if (item == null) {
			Log.e(TAG, "No items to add!");
			return false;
		}
		//Build a new list with 1 item to chain the methods of addSubItems
		List<T> subItems = new ArrayList<T>(1);
		subItems.add(item);
		//Reuse the method for subItems
		return addSubItems(parentPosition, subPosition, subItems, expandParent, payload);
	}

	/**
	 * Adds all current subItems of the passed parent to the internal list.
	 * <p><b>In order to add the subItems</b>, the following condition must be satisfied:
	 * <br/>- The item resulting from the parent position is actually an {@link IExpandable}.</p>
	 * Optionally the parent can be expanded and subItems displayed.
	 * <br/>Optionally you can pass any payload to notify the parent about the change and optimize
	 * the view binding.
	 *
	 * @param parentPosition position of the expandable item that shall contain the subItem
	 * @param parent         the expandable item which shall contain the new subItem
	 * @param expandParent   true to initially expand the parent (if needed) and after to add
	 *                       the subItem, false to simply add the subItem to the parent
	 * @param payload        any non-null user object to notify the parent (the payload will be
	 *                       therefore passed to the bind method of the parent ViewHolder),
	 *                       pass null to <u>not</u> notify the parent
	 * @return true if the internal list was successfully modified, false otherwise
	 * @see #addSubItems(int, int, IExpandable, List, boolean, Object)
	 */
	public int addAllSubItemsFrom(@IntRange(from = 0) int parentPosition,
								  @NonNull IExpandable parent, boolean expandParent,
								  @Nullable Object payload) {
		List<T> subItems = getCurrentChildren(parent);
		addSubItems(parentPosition, 0, parent, subItems, expandParent, payload);
		return subItems.size();
	}

	/**
	 * Convenience method of {@link #addSubItems(int, int, IExpandable, List, boolean, Object)}.
	 * <br/>Optionally you can pass any payload to notify the parent about the change and optimize
	 * the view binding.
	 *
	 * @param parentPosition position of the expandable item that shall contain the subItems
	 * @param subPosition    the start position in the parent where the new items shall be inserted
	 * @param items          the list of the subItems to add
	 * @param expandParent   true to initially expand the parent (if needed) and after to add
	 *                       the subItems, false to simply add the subItems to the parent
	 * @param payload        any non-null user object to notify the parent (the payload will be
	 *                       therefore passed to the bind method of the parent ViewHolder),
	 *                       pass null to <u>not</u> notify the parent
	 * @return true if the internal list was successfully modified, false otherwise
	 * @see #addSubItems(int, int, IExpandable, List, boolean, Object)
	 */
	public boolean addSubItems(@IntRange(from = 0) int parentPosition,
							   @IntRange(from = 0) int subPosition,
							   @NonNull List<T> items, boolean expandParent, @Nullable Object payload) {
		T parent = getItem(parentPosition);
		if (isExpandable(parent)) {
			IExpandable expandable = (IExpandable) parent;
			return addSubItems(parentPosition, subPosition, expandable, items, expandParent, payload);
		}
		Log.e(TAG, "Passed parentPosition doesn't belong to an Expandable item!");
		return false;
	}

	/**
	 * Adds new subItems on the specified parent item, to the internal list.
	 * <p><b>In order to add subItems</b>, the following condition must be satisfied:
	 * <br/>- The item resulting from the parent position is actually an {@link IExpandable}.</p>
	 * Optionally the parent can be expanded and subItems displayed.
	 * <br/>Optionally you can pass any payload to notify the parent about the change and optimize
	 * the view binding.
	 *
	 * @param parentPosition position of the expandable item that shall contain the subItems
	 * @param subPosition    the start position in the parent where the new items shall be inserted
	 * @param parent         the expandable item which shall contain the new subItem
	 * @param items          the list of the subItems to add
	 * @param expandParent   true to initially expand the parent (if needed) and after to add
	 *                       the subItems, false to simply add the subItems to the parent
	 * @param payload        any non-null user object to notify the parent (the payload will be
	 *                       therefore passed to the bind method of the parent ViewHolder),
	 *                       pass null to <u>not</u> notify the parent
	 * @return true if the internal list was successfully modified, false otherwise
	 * @see #addItems(int, List)
	 */
	private boolean addSubItems(@IntRange(from = 0) int parentPosition,
								@IntRange(from = 0) int subPosition,
								@NonNull IExpandable parent,
								@NonNull List<T> items, boolean expandParent, @Nullable Object payload) {
		boolean added = false;
		//Expand parent if requested and not already expanded
		if (expandParent && !parent.isExpanded()) {
			expand(parentPosition);
		}
		//Notify the adapter of the new addition to display it and animate it.
		//If parent is collapsed there's no need to notify about the change.
		if (parent.isExpanded()) {
			added = addItems(parentPosition + 1 + Math.max(0, subPosition), items);
		}
		//Notify the parent about the change if requested
		if (payload != null) notifyItemChanged(parentPosition, payload);
		return added;
	}

	/**
	 * Adds and shows an empty section to the top (position = 0).
	 *
	 * @see #addSection(IHeader, IHeader)
	 */
	public void addSection(@NonNull IHeader header) {
		addSection(header, null);
	}

	/**
	 * Adds and shows an empty section.
	 * <p>The new section is a {@link IHeader} item and the position is calculated from an
	 * existent header reference:
	 * <br/>- To add section to the top, set {@code refHeader} to null;
	 * <br/>- To add section in the middle, set {@code refHeader} to the previous section;
	 * <br/>- To add section to the bottom, set {@code refHeader} to your last header/section or
	 * use the method {@code addItem(position=itemCount, header)}.</p>
	 *
	 * @param header    the section header item to add
	 * @param refHeader optional reference section item
	 */
	public void addSection(@NonNull IHeader header, @Nullable IHeader refHeader) {
		int position = 0;
		if (refHeader != null) {
			position = getGlobalPositionOf(refHeader) + 1;
			List<ISectionable> refSectionItems = getSectionItems(refHeader);
			if (!refSectionItems.isEmpty()) {
				position += refSectionItems.size();
			}
		}
		header.setHidden(false);
		addItem(position, (T) header);
	}

	/**
	 * Adds a new item in a section when the relative position is <b>unknown</b>.
	 * <p>The header can be a {@code IExpandable} type or {@code IHeader} type.</p>
	 *
	 * @param item       the item to add
	 * @param header     the section receiving the new item
	 * @param comparator the criteria to sort the sectionItems used to extract the correct position
	 *                   of the new item
	 * @see #addItemToSection(ISectionable, IHeader, int)
	 */
	public void addItemToSection(@NonNull ISectionable item, @NonNull IHeader header,
								 @NonNull Comparator comparator) {
		List<ISectionable> sectionItems = getSectionItems(header);
		sectionItems.add(item);
		//Sort the list for new position
		Collections.sort(sectionItems, comparator);
		//Get the new position
		int index = sectionItems.indexOf(item);
		addItemToSection(item, header, index);
	}

	/**
	 * Adds a new item in a section when the relative position is <b>known</b>.
	 * <p>The header can be a {@code IExpandable} type or {@code IHeader} type.</p>
	 *
	 * @param item   the item to add
	 * @param header the section receiving the new item
	 * @param index  the known relative position where to add the new item into the section
	 * @see #addItemToSection(ISectionable, IHeader, Comparator)
	 */
	public void addItemToSection(@NonNull ISectionable item, @NonNull IHeader header,
								 @IntRange(from = 0) int index) {
		int headerPosition = getGlobalPositionOf(header);
		if (index >= 0 && headerPosition >= 0) {
			item.setHeader(header);
			if (isExpandable((T) header))
				addSubItem(headerPosition, index, (T) item, false, true);
			else
				addItem(headerPosition + 1 + index, (T) item);
		}
	}

	/*----------------------*/
	/* DELETE ITEMS METHODS */
	/*----------------------*/

	/**
	 * Removes an item from internal list and notify the change.
	 * <p>The item is retained for an eventual Undo.</p>
	 * This method delegates the removal to removeRange.
	 *
	 * @param position the position of item to remove
	 * @see #removeRange(int, int, Object)
	 */
	public void removeItem(@IntRange(from = 0) int position) {
		this.removeItem(position, null);
	}

	/**
	 * Removes an item from the internal list and notify the change.
	 * <p>The item is retained for an eventual Undo.</p>
	 * This method delegates the removal to removeRange.
	 *
	 * @param position The position of item to remove
	 * @param payload  any non-null user object to notify the parent (the payload will be
	 *                 therefore passed to the bind method of the parent ViewHolder),
	 *                 pass null to <u>not</u> notify the parent
	 * @see #removeRange(int, int, Object)
	 */
	public void removeItem(@IntRange(from = 0) int position, @Nullable Object payload) {
		//Request to collapse after the notification of remove range
		collapse(position);
		if (DEBUG) Log.v(TAG, "removeItem delegates removal to removeRange");
		removeRange(position, 1, payload);
		clearSelection();
	}

	/**
	 * Same as {@link #removeItems(List, Object)}, but in this case the parent will not be
	 * notified about the change, if a child is removed.
	 * <p>This method delegates the removal to removeRange.</p>
	 *
	 * @see #removeRange(int, int, Object)
	 */
	public void removeItems(@NonNull List<Integer> selectedPositions) {
		this.removeItems(selectedPositions, null);
	}

	/**
	 * Removes a list of items from internal list and notify the change.
	 * <p>Every item is retained for an eventual Undo.</p>
	 * Optionally you can pass any payload to notify the parent about the change and optimize the
	 * view binding.
	 * <p>This method delegates the removal to removeRange.</p>
	 *
	 * @param selectedPositions list with item positions to remove
	 * @param payload           any non-null user object to notify the parent (the payload will be
	 *                          therefore passed to the bind method of the parent ViewHolder),
	 *                          pass null to <u>not</u> notify the parent
	 * @see #removeRange(int, int, Object)
	 */
	public void removeItems(@NonNull List<Integer> selectedPositions, @Nullable Object payload) {
		if (DEBUG)
			Log.v(TAG, "removeItems selectedPositions=" + selectedPositions + " payload=" + payload);
		//Check if list is empty
		if (selectedPositions == null || selectedPositions.isEmpty()) return;
		//Reverse-sort the list, start from last position for efficiency
		Collections.sort(selectedPositions, new Comparator<Integer>() {
			@Override
			public int compare(Integer lhs, Integer rhs) {
				return rhs - lhs;
			}
		});
		if (DEBUG)
			Log.v(TAG, "removeItems after reverse sort selectedPositions=" + selectedPositions);
		//Split the list in ranges
		int positionStart = 0, itemCount = 0;
		int lastPosition = selectedPositions.get(0);
		multiRange = true;
		for (Integer position : selectedPositions) {//10 9 8 //5 4 //1
			if (lastPosition - itemCount == position) {//10-0==10  10-1==9  10-2==8  10-3==5 NO  //5-1=4  5-2==1 NO
				itemCount++;             // 1  2  3  //2
				positionStart = position;//10  9  8  //4
			} else {
				adjustSelected = false;
				//Remove range
				if (itemCount > 0)
					removeRange(positionStart, itemCount, payload);//8,3  //4,2
				positionStart = lastPosition = position;//5  //1
				itemCount = 1;
			}
			//Request to collapse after the notification of remove range
			collapse(position);
		}
		multiRange = false;
		//Clear also the selection
		clearSelection();
		//Remove last range
		if (itemCount > 0) {
			removeRange(positionStart, itemCount, payload);//1,1
		}
	}

	/**
	 * Selectively removes all items of the type provided as parameter.
	 *
	 * @param viewTypes the viewTypes to remove
	 */
	public void removeItemsOfType(Integer... viewTypes) {
		List<Integer> viewTypeList = Arrays.asList(viewTypes);
		List<Integer> itemsToRemove = new ArrayList<Integer>();
		for (int i = mItems.size() - 1; i >= 0; i--) {
			//Privilege autoMap if active
			if ((autoMap && viewTypeList.contains(mItems.get(i).getLayoutRes())) ||
					viewTypeList.contains(getItemViewType(i)))
				itemsToRemove.add(i);
		}
		this.removeItems(itemsToRemove);
	}

	/**
	 * Same as {@link #removeRange(int, int, Object)}, but in this case the parent will not be
	 * notified about the change, if children are removed.
	 */
	public void removeRange(@IntRange(from = 0) int positionStart,
							@IntRange(from = 0) int itemCount) {
		this.removeRange(positionStart, itemCount, null);
	}

	/**
	 * Removes a list of consecutive items from internal list and notify the change.
	 * <p>If the item, resulting from the passed position:</p>
	 * - is <u>not expandable</u> with <u>no</u> parent, it is removed as usual.<br/>
	 * - is <u>not expandable</u> with a parent, it is removed only if the parent is expanded.<br/>
	 * - is <u>expandable</u> implementing {@link IExpandable}, it is removed as usual, but
	 * it will be collapsed if expanded.<br/>
	 * - has a {@link IHeader} item, the header will be automatically linked to the first item
	 * after the range or can remain orphan.
	 * <p>Optionally you can pass any payload to notify the <u>parent</u> or the <u>header</u>
	 * about the change and optimize the view binding.</p>
	 *
	 * @param positionStart the start position of the first item
	 * @param itemCount     how many items should be removed
	 * @param payload       any non-null user object to notify the parent (the payload will be
	 *                      therefore passed to the bind method of the parent ViewHolder),
	 *                      pass null to <u>not</u> notify the parent
	 * @see #removeItem(int, Object)
	 * @see #removeItems(List, Object)
	 * @see #startUndoTimer(long, OnDeleteCompleteListener)
	 * @see #restoreDeletedItems()
	 * @see #setRestoreSelectionOnUndo(boolean)
	 * @see #setRemoveOrphanHeaders(boolean)
	 * @see #emptyBin()
	 */
	public void removeRange(@IntRange(from = 0) int positionStart,
							@IntRange(from = 0) int itemCount, @Nullable Object payload) {
		int initialCount = getItemCount();
		if (DEBUG)
			Log.v(TAG, "removeRange positionStart=" + positionStart + " itemCount=" + itemCount);
		if (positionStart < 0 || (positionStart + itemCount) > initialCount) {
			Log.e(TAG, "Cannot removeRange with positionStart out of OutOfBounds!");
			return;
		}

		//Handle header linkage
		IHeader header = getHeaderOf(getItem(positionStart));
		if (header != null) {
			//The header does not represents a group anymore, add it to the Orphan list
			addToOrphanListIfNeeded(header, positionStart, itemCount);
			notifyItemChanged(getGlobalPositionOf(header), payload);
		}

		int parentPosition = -1;
		IExpandable parent = null;
		for (int position = positionStart; position < positionStart + itemCount; position++) {
			T item = getItem(positionStart);
			if (!permanentDelete) {
				assert item != null;
				//When removing a range of children, parent is always the same :-)
				if (parent == null) parent = getExpandableOf(item);
				//Differentiate: (Expandable & NonExpandable with No parent) from (NonExpandable with a parent)
				if (parent == null) {
					createRestoreItemInfo(positionStart, item, payload);
				} else {
					parentPosition = createRestoreSubItemInfo(parent, item, payload);
				}
			}
			//If item is a Header, remove linkage from ALL Sectionable items if exist
			if (unlinkOnRemoveHeader && isHeader(item)) {
				List<ISectionable> sectionableList = getSectionItems((IHeader) item);
				for (ISectionable sectionable : sectionableList) {
					sectionable.setHeader(null);
					if (payload != null)
						notifyItemChanged(getGlobalPositionOf(sectionable), payload);
				}
			}
			//Remove item from internal list
			mItems.remove(positionStart);
		}

		//Notify removals
		if (parentPosition >= 0) {
			adjustSelected = false;
			//Notify the Children removal only if Parent is expanded
			notifyItemRangeRemoved(positionStart, itemCount);
			//Notify the Parent about the change if requested
			if (payload != null) notifyItemChanged(parentPosition, payload);
		} else {
			//Notify range removal
			notifyItemRangeRemoved(positionStart, itemCount);
		}

		//Remove orphan headers
		if (removeOrphanHeaders) {
			for (IHeader orphanHeader : mOrphanHeaders) {
				int headerPosition = getGlobalPositionOf(orphanHeader);
				if (headerPosition >= 0) {
					if (DEBUG) Log.d(TAG, "Removing orphan header " + orphanHeader);
					createRestoreItemInfo(headerPosition, (T) orphanHeader, payload);
					mItems.remove(headerPosition);
					notifyItemRemoved(headerPosition);
				}
			}
			mOrphanHeaders.clear();
		}

		//Update empty view
		if (mUpdateListener != null && !multiRange && initialCount != getItemCount())
			mUpdateListener.onUpdateEmptyView(getItemCount());
	}

	/**
	 * Convenience method to remove all Items that are currently selected.
	 * <p>Parent will not be notified about the change, if a child is removed.</p>
	 *
	 * @see #removeAllSelectedItems(Object)
	 */
	public void removeAllSelectedItems() {
		this.removeItems(getSelectedPositions(), null);
	}

	/**
	 * Convenience method to remove all Items that are currently selected.<p>
	 * Optionally the Parent can be notified about the change, if a child is removed, by passing
	 * any payload.
	 *
	 * @param payload any non-null user object to notify the parent (the payload will be
	 *                therefore passed to the bind method of the parent ViewHolder),
	 *                pass null to <u>not</u> notify the parent
	 */
	public void removeAllSelectedItems(@Nullable Object payload) {
		this.removeItems(getSelectedPositions(), payload);
	}

	/*----------------------*/
	/* UNDO/RESTORE METHODS */
	/*----------------------*/

	public boolean isPermanentDelete() {
		return permanentDelete;
	}

	public void setPermanentDelete(boolean permanentDelete) {
		this.permanentDelete = permanentDelete;
	}

	/**
	 * Returns the current configuration to restore selections on Undo.
	 *
	 * @return true if selection will be restored, false otherwise
	 * @see #setRestoreSelectionOnUndo(boolean)
	 */
	public boolean isRestoreWithSelection() {
		return restoreSelection;
	}

	/**
	 * Gives the possibility to restore the selection on Undo, when {@link #restoreDeletedItems()}
	 * is called.
	 * <p>To use in combination with {@code ActionMode} in order to not disable it.</p>
	 * Default value is false.
	 *
	 * @param restoreSelection true to have restored items still selected, false to empty selections
	 */
	public void setRestoreSelectionOnUndo(boolean restoreSelection) {
		this.restoreSelection = restoreSelection;
	}

	/**
	 * Restore items just removed.
	 * <p><b>NOTE:</b> If filter is active, only items that match that filter will be shown(restored).</p>
	 *
	 * @see #setRestoreSelectionOnUndo(boolean)
	 */
	@SuppressWarnings("ResourceType")
	public void restoreDeletedItems() {
		stopUndoTimer();
		multiRange = true;
		int initialCount = getItemCount();
		//Selection coherence: start from a clear situation
		clearSelection();
		//Start from latest item deleted, since others could rely on it
		for (int i = mRestoreList.size() - 1; i >= 0; i--) {
			adjustSelected = false;
			RestoreInfo restoreInfo = mRestoreList.get(i);
			//Notify header if exists
			IHeader header = getHeaderOf(restoreInfo.item);
			if (header != null) {
				notifyItemChanged(getGlobalPositionOf(header), restoreInfo.payload);
			}

			if (restoreInfo.relativePosition >= 0) {
				//Restore child, if not deleted
				if (DEBUG) Log.v(TAG, "Restore Child " + restoreInfo);
				//Skip subItem addition if filter is active
				if (hasSearchText() && !filterObject(restoreInfo.item, getSearchText()))
					continue;
				//Add subItem
				addSubItem(restoreInfo.getRestorePosition(true), restoreInfo.relativePosition,
						restoreInfo.item, false, restoreInfo.payload);
			} else {
				//Restore parent or simple item, if not deleted
				if (DEBUG) Log.v(TAG, "Restore Parent " + restoreInfo);
				//Skip item addition if filter is active
				if (hasSearchText() && !filterExpandableObject(restoreInfo.item, getSearchText()))
					continue;
				//Add item
				addItem(restoreInfo.getRestorePosition(false), restoreInfo.item);
			}
			//Item is again visible
			restoreInfo.item.setHidden(false);

			//Restore header linkage
			if (unlinkOnRemoveHeader && isHeader(restoreInfo.item)) {
				header = (IHeader) restoreInfo.item;
				List<ISectionable> items = getSectionItems(header);
				for (ISectionable sectionable : items) {
					linkHeaderTo((T) sectionable, header, restoreInfo.payload);
				}
			}
		}
		//Restore selection if requested, before emptyBin
		if (restoreSelection && !mRestoreList.isEmpty()) {
			if (isExpandable(mRestoreList.get(0).item) || getExpandableOf(mRestoreList.get(0).item) == null) {
				parentSelected = true;
			} else {
				childSelected = true;
			}
			for (RestoreInfo restoreInfo : mRestoreList) {
				if (restoreInfo.item.isSelectable()) {
					getSelectedPositions().add(getGlobalPositionOf(restoreInfo.item));
				}
			}
			if (DEBUG) Log.v(TAG, "Selected positions after restore " + getSelectedPositions());
		}

		//Call listener to update EmptyView
		multiRange = false;
		if (mUpdateListener != null && initialCount != getItemCount())
			mUpdateListener.onUpdateEmptyView(getItemCount());

		emptyBin();
	}

	/**
	 * Clean memory from items just removed.
	 * <p><b>Note:</b> This method is automatically called after timer is over and after a
	 * restoration.</p>
	 */
	public synchronized void emptyBin() {
		if (DEBUG) Log.v(TAG, "emptyBin!");
		mRestoreList.clear();
	}

	/**
	 * Convenience method to start Undo timer with default timeout of 5''
	 *
	 * @param listener the listener that will be called after timeout to commit the change
	 */
	public void startUndoTimer(OnDeleteCompleteListener listener) {
		startUndoTimer(0, listener);
	}

	/**
	 * Start Undo timer with custom timeout
	 *
	 * @param timeout  custom timeout
	 * @param listener the listener that will be called after timeout to commit the change
	 */
	public void startUndoTimer(long timeout, OnDeleteCompleteListener listener) {
		//Make longer the timer for new coming deleted items
		mHandler.removeMessages(1);
		mHandler.sendMessageDelayed(Message.obtain(mHandler, 1, listener), timeout > 0 ? timeout : UNDO_TIMEOUT);
	}

	/**
	 * Stop Undo timer.
	 * <p><b>Note:</b> This method is automatically called in case of restoration.</p>
	 */
	protected void stopUndoTimer() {
		mHandler.removeCallbacksAndMessages(null);
	}

	public boolean isRestoreInTime() {
		return mRestoreList != null && !mRestoreList.isEmpty();
	}

	/**
	 * @return the list of deleted items
	 */
	@NonNull
	public List<T> getDeletedItems() {
		List<T> deletedItems = new ArrayList<T>();
		for (RestoreInfo restoreInfo : mRestoreList) {
			deletedItems.add(restoreInfo.item);
		}
		return deletedItems;
	}

	/**
	 * Retrieves the expandable of the deleted child.
	 *
	 * @param child the deleted child
	 * @return the expandable(parent) of this child, or null if no parent found.
	 */
	public IExpandable getExpandableOfDeletedChild(T child) {
		for (RestoreInfo restoreInfo : mRestoreList) {
			if (restoreInfo.item.equals(child) && isExpandable(restoreInfo.refItem))
				return (IExpandable) restoreInfo.refItem;
		}
		return null;
	}

	/**
	 * Retrieves only the deleted children of the specified parent.
	 *
	 * @param expandable the parent item
	 * @return the list of deleted children
	 */
	@NonNull
	public List<T> getDeletedChildren(IExpandable expandable) {
		List<T> deletedChild = new ArrayList<T>();
		for (RestoreInfo restoreInfo : mRestoreList) {
			if (restoreInfo.refItem != null && restoreInfo.refItem.equals(expandable) && restoreInfo.relativePosition >= 0)
				deletedChild.add(restoreInfo.item);
		}
		return deletedChild;
	}

	/**
	 * Retrieves all the original children of the specified parent, filtering out all the
	 * deleted children if any.
	 *
	 * @param expandable the parent item
	 * @return a non null list of the original children minus the deleted children if some are
	 * pending removal.
	 */
	@NonNull
	public List<T> getCurrentChildren(@NonNull IExpandable expandable) {
		//Check item and subItems existence
		if (expandable == null || !hasSubItems(expandable))
			return new ArrayList<T>();

		//Take a copy of the subItems list
		List<T> subItems = new ArrayList<T>(expandable.getSubItems());
		//Remove all children pending removal
		if (!mRestoreList.isEmpty()) {
			subItems.removeAll(getDeletedChildren(expandable));
		}
		return subItems;
	}

	/*----------------*/
	/* FILTER METHODS */
	/*----------------*/

	public boolean hasSearchText() {
		return mSearchText != null && !mSearchText.isEmpty();
	}

	public boolean hasNewSearchText(String newText) {
		return !mOldSearchText.equalsIgnoreCase(newText);
	}

	public String getSearchText() {
		return mSearchText;
	}

	public void setSearchText(String searchText) {
		if (searchText != null)
			mSearchText = searchText.trim().toLowerCase(Locale.getDefault());
		else mSearchText = "";
	}

	/**
	 * Sometimes it is necessary, while filtering, to rebound the items that remain unfiltered.
	 * If the items have highlighted text, those items must be refreshed in order to update the
	 * highlighted text.
	 * <p>This happens systematically when searchText is reduced in length by the user. It doesn't
	 * reduce performance in other cases, since the notification is triggered only in
	 * {@link #applyAndAnimateAdditions(List, List)} when new items are not added.</p>
	 *
	 * @param notifyChange true to trigger {@link #notifyItemChanged(int)} while filtering,
	 *                     false otherwise
	 */
	public final void setNotifyChangeOfUnfilteredItems(boolean notifyChange) {
		this.mNotifyChangeOfUnfilteredItems = notifyChange;
	}

	/**
	 * <b>WATCH OUT! PASS ALWAYS A <u>COPY</u> OF THE ORIGINAL LIST</b>: due to internal mechanism,
	 * items are removed and/or added in order to animate items in the final list.
	 * <p>Same as {@link #filterItems(List)}, but with a delay in the execution, useful to grab
	 * more characters from user before starting the search.</p>
	 *
	 * @param unfilteredItems the list to filter
	 * @param delay           any non negative delay
	 * @see #filterObject(IFlexible, String)
	 */
	public void filterItems(@NonNull List<T> unfilteredItems, @IntRange(from = 0) long delay) {
		//Make longer the timer for new coming deleted items
		mHandler.removeMessages(0);
		mHandler.sendMessageDelayed(Message.obtain(mHandler, 0, unfilteredItems), delay > 0 ? delay : 0);
	}

	/**
	 * <b>WATCH OUT! PASS ALWAYS A <u>COPY</u> OF THE ORIGINAL LIST</b>: due to internal mechanism,
	 * items are removed and/or added in order to animate items in the final list.
	 * <p>This method filters the provided list with the search text previously set with
	 * {@link #setSearchText(String)}.</p>
	 * <b>Note:</b>
	 * <br/>- This method calls {@link #filterObject(IFlexible, String)}.
	 * <br/>- If search text is empty or null, the provided list is the current list.
	 * <br/>- Any pending deleted items are always filtered out, but if restored, they will be
	 * displayed according to the current filter and in the correct positions.
	 * <br/>- <b>NEW!</b> Expandable items are picked up and displayed if at least a child is
	 * collected by the current filter.
	 * <br/>- <b>NEW!</b> Items are animated thanks to {@link #animateTo(List)}.
	 *
	 * @param unfilteredItems the list to filter
	 * @see #filterObject(IFlexible, String)
	 */
	public synchronized void filterItems(@NonNull List<T> unfilteredItems) {
		// NOTE: In case user has deleted some items and he changes or applies a filter while
		// deletion is pending (Undo started), in order to be consistent, we need to recalculate
		// the new position in the new list and finally skip those items to avoid they are shown!
		List<T> values = new ArrayList<T>();
		//Enable flag: skip adjustPositions!
		filtering = true;
		//Reset values
		int initialCount = getItemCount();
		if (hasSearchText()) {
			int newOriginalPosition = -1;
			for (T item : unfilteredItems) {
				//Check header first
				T header = (T) getHeaderOf(item);
				if (header != null && filterObject(header, getSearchText())
						&& !values.contains(getHeaderOf(item))) {
					values.add(header);
				}
				if (filterExpandableObject(item, getSearchText())) {
					RestoreInfo restoreInfo = getPendingRemovedItem(item);
					if (restoreInfo != null) {
						//If found point to the new reference while filtering
						restoreInfo.filterRefItem = ++newOriginalPosition < values.size() ? values.get(newOriginalPosition) : null;
					} else {
						if (hasHeader(item) && !values.contains(getHeaderOf(item))) {
							values.add((T) getHeaderOf(item));
						}
						values.add(item);
						newOriginalPosition++;
						if (isExpandable(item)) {
							IExpandable expandable = (IExpandable) item;
							if (expandable.isExpanded()) {
								List<T> filteredSubItems = new ArrayList<T>();
								//Add subItems if not hidden by filterObject()
								List<T> subItems = expandable.getSubItems();
								for (T subItem : subItems) {
									if (!subItem.isHidden()) filteredSubItems.add(subItem);
								}
								values.addAll(filteredSubItems);
								newOriginalPosition += filteredSubItems.size();
							}
						}
					}
				}
			}
		} else if (hasNewSearchText(mSearchText)) {
			values = unfilteredItems; //with no filter
			if (!mRestoreList.isEmpty()) {
				for (RestoreInfo restoreInfo : mRestoreList) {
					//Clear the refItem generated by the filter
					restoreInfo.clearFilterRef();
					//Find the real reference
					restoreInfo.refItem = values.get(Math.max(0, values.indexOf(restoreInfo.item) - 1));
				}
				values.removeAll(getDeletedItems());
			}
			resetFilterFlags(values);
		}

		//Animate search results only in case of new SearchText
		if (hasNewSearchText(mSearchText)) {
			mOldSearchText = mSearchText;
			animateTo(values);
			//Restore headers if necessary
			if (!hasSearchText()) {
				//Add headers delayed. It gives time to the LayoutManager to fulfill the previous animation:
				//Attempt to read from field 'android.support.v7.widget.RecyclerView$ViewHolder
				// android.support.v7.widget.RecyclerView$LayoutParams.mViewHolder' on a null object reference
				mHandler.postDelayed(new Runnable() {
					@Override
					public void run() {
						showAllHeaders();
					}
				}, 50L);
			}
		}

		//Reset filtering flag
		filtering = false;

		//Call listener to update EmptyView
		if (mUpdateListener != null && initialCount != getItemCount())
			mUpdateListener.onUpdateEmptyView(getItemCount());
	}

	/**
	 * This method is a wrapper filter for expandable items.<br/>
	 * It performs filtering on the subItems returning true, if the any child should be in the
	 * filtered collection.
	 * <p>If the provided item is not an expandable it will be filtered as usual by
	 * {@link #filterObject(T, String)}.</p>
	 *
	 * @param item       the object with subItems to be inspected
	 * @param constraint constraint, that the object has to fulfil
	 * @return true, if the object should be in the filteredResult, false otherwise
	 */
	private boolean filterExpandableObject(T item, String constraint) {
		//Reset expansion flag
		boolean filtered = false;
		if (isExpandable(item)) {
			IExpandable expandable = (IExpandable) item;
			expandable.setExpanded(false);
			//Children scan filter
			for (T subItem : getCurrentChildren(expandable)) {
				//Reuse normal filter for Children
				subItem.setHidden(!filterObject(subItem, constraint));
				if (!filtered && !subItem.isHidden()) {
					filtered = true;
				}
			}
			//Expand if filter found text in subItems
			expandable.setExpanded(filtered);
		}
		//if not filtered already, fallback to Normal filter
		return filtered || filterObject(item, constraint);
	}

	/**
	 * This method checks if the provided object is a type of {@link IFilterable} interface,
	 * if yes, performs the filter on the implemented method {@link IFilterable#filter(String)}.
	 * <p><b>NOTE:</b>
	 * <br/>- The item will be collected if the implemented method returns true.
	 * <br/>- {@code IExpandable} items are automatically picked up and displayed if at least a
	 * child is collected by the current filter: however, you also need to implement
	 * {@code IFilterable} interface on the {@code IExpandable} item and on the child type. What
	 * you DON'T NEED to implement is the scan for the children: this is already done :-)
	 * <br/>- If you don't want to implement the {@code IFilterable} interface on the items, then
	 * you can override this method to have another filter logic!
	 *
	 * @param item       the object to be inspected
	 * @param constraint constraint, that the object has to fulfil
	 * @return true, if the object returns true as well, and so if it should be in the
	 * filteredResult, false otherwise
	 */
	protected boolean filterObject(T item, String constraint) {
		if (item instanceof IFilterable) {
			IFilterable filterable = (IFilterable) item;
			return filterable.filter(constraint);
		}
		return false;
	}

	/**
	 * Animate from the current list to the another.
	 * <p>Used by the filter.</p>
	 * Unchanged items will be notified if {@code mNotifyChangeOfUnfilteredItems} is set true, and
	 * payload will be set as a Boolean.
	 *
	 * @param models the new list containing the new items
	 * @return the cleaned up item list. make sure to set your new list to this one
	 * @see #setNotifyChangeOfUnfilteredItems(boolean)
	 */
	public List<T> animateTo(List<T> models) {
		applyAndAnimateRemovals(mItems, models);
		applyAndAnimateAdditions(mItems, models);
		return mItems;
	}

	/**
	 * Find out all removed items and animate them.
	 */
	protected void applyAndAnimateRemovals(List<T> from, List<T> newItems) {
		int out = 0;
		for (int i = from.size() - 1; i >= 0; i--) {
			final T item = from.get(i);
			if (!newItems.contains(item)) {
				if (DEBUG) Log.v(TAG, "animateRemovals remove position=" + i + " item=" + item);
				from.remove(i);
				notifyItemRemoved(i);
				out++;
			} else {
				if (DEBUG) Log.v(TAG, "animateRemovals   keep position=" + i + " item=" + item);
			}
		}
		if (DEBUG) Log.v(TAG, "animateRemovals total out=" + out + " in=" + newItems.size());
	}

	/**
	 * Find out all added items and animate them.
	 */
	protected void applyAndAnimateAdditions(List<T> from, List<T> newItems) {
		int out = 0;
		for (int i = 0, count = newItems.size(); i < count; i++) {
			final T item = newItems.get(i);
			if (!from.contains(item)) {
				if (DEBUG) Log.v(TAG, "animateAdditions  add position=" + i + " item=" + item);
				from.add(i, item);
				notifyItemInserted(i);
			} else if (mNotifyChangeOfUnfilteredItems) {
				out++;
				notifyItemChanged(i, mNotifyChangeOfUnfilteredItems);
				if (DEBUG) Log.v(TAG, "animateAdditions keep position=" + i + " item=" + item);
			}
		}
		if (DEBUG) Log.v(TAG, "animateAdditions total out=" + out + " in=" + newItems.size());
	}

	/*---------------*/
	/* TOUCH METHODS */
	/*---------------*/

	/**
	 * Used by {@link FlexibleViewHolder#onTouch(View, MotionEvent)}
	 * to start Drag when HandleView is touched.
	 *
	 * @return the ItemTouchHelper instance already initialized.
	 */
	public final ItemTouchHelper getItemTouchHelper() {
		initializeItemTouchHelper();
		return mItemTouchHelper;
	}

	/**
	 * Returns whether ItemTouchHelper should start a drag and drop operation if an item is
	 * long pressed.<p>
	 * Default value is false.
	 *
	 * @return true if ItemTouchHelper should start dragging an item when it is long pressed,
	 * false otherwise. Default value is false.
	 */
	public boolean isLongPressDragEnabled() {
		return mItemTouchHelperCallback != null && mItemTouchHelperCallback.isLongPressDragEnabled();
	}

	/**
	 * Enable the Drag on LongPress on the entire ViewHolder.
	 * <p><b>NOTE:</b> This will skip LongClick on the view in order to handle the LongPress,
	 * however the LongClick listener will be called if necessary in the new
	 * {@link FlexibleViewHolder#onActionStateChanged(int, int)}.</p>
	 * Default value is false.
	 *
	 * @param longPressDragEnabled true to activate, false otherwise
	 */
	public final void setLongPressDragEnabled(boolean longPressDragEnabled) {
		initializeItemTouchHelper();
		mItemTouchHelperCallback.setLongPressDragEnabled(longPressDragEnabled);
	}

	/**
	 * Enabled by default.
	 * <p>To use, it is sufficient to set the HandleView by calling
	 * {@link FlexibleViewHolder#setDragHandleView(View)}.</p>
	 *
	 * @return true if active, false otherwise
	 */
	public boolean isHandleDragEnabled() {
		return handleDragEnabled;
	}

	/**
	 * Enable/Disable the drag with handle.
	 * <p>Default value is true.</p>
	 *
	 * @param handleDragEnabled true to activate, false otherwise
	 */
	public void setHandleDragEnabled(boolean handleDragEnabled) {
		this.handleDragEnabled = handleDragEnabled;
	}

	/**
	 * Returns whether ItemTouchHelper should start a swipe operation if a pointer is swiped
	 * over the View.
	 * <p>Default value is false.</p>
	 *
	 * @return true if ItemTouchHelper should start swiping an item when user swipes a pointer
	 * over the View, false otherwise. Default value is false.
	 */
	public final boolean isSwipeEnabled() {
		return mItemTouchHelperCallback != null && mItemTouchHelperCallback.isItemViewSwipeEnabled();
	}

	/**
	 * Enable the Full Swipe of the items.
	 * <p>Default value is false.</p>
	 *
	 * @param swipeEnabled true to activate, false otherwise
	 */
	public final void setSwipeEnabled(boolean swipeEnabled) {
		initializeItemTouchHelper();
		mItemTouchHelperCallback.setSwipeEnabled(swipeEnabled);
	}

	/**
	 * Swaps the elements of list list at indices fromPosition and toPosition and notify the change.
	 * <p>Selection of swiped elements is automatically updated.</p>
	 *
	 * @param fromPosition previous position of the item.
	 * @param toPosition   new position of the item.
	 */
	@CallSuper
	public void moveItem(int fromPosition, int toPosition) {
		if (fromPosition < 0 || fromPosition >= getItemCount() ||
				toPosition < 0 || toPosition >= getItemCount()) {
			return;
		}
		if (DEBUG) {
			Log.v(TAG, "moveItem from=" +
					fromPosition + "[" + (isSelected(fromPosition) ? "selected" : "unselected") + "] to=" +
					toPosition + "[" + (isSelected(toPosition) ? "selected" : "unselected") + "]");
			Log.v(TAG, "moveItem beforeSwap fromItem=" + getItem(fromPosition) + " toItem=" + getItem(toPosition));
		}

		//TODO: Allow child to be moved into another parent, update the 2 parents, optionally: 1) collapse the new parent 2) expand it 3) leave as it is
		//Collapse expandable before swapping
		if (isExpanded(toPosition))
			collapse(toPosition);

		//Perform item swap
		Collections.swap(mItems, fromPosition, toPosition);
		if ((isSelected(fromPosition) && !isSelected(toPosition)) ||
				(!isSelected(fromPosition) && isSelected(toPosition))) {
			super.toggleSelection(fromPosition);
			super.toggleSelection(toPosition);
		}
		notifyItemMoved(fromPosition, toPosition);
		if (DEBUG) {
			Log.v(TAG, "moveItem afterSwap fromItem=" + getItem(fromPosition) + " toItem=" + getItem(toPosition));
		}

		//Header swap linkage
		if (headersShown) {
			//Situation AFTER items have been swapped, items are inverted!
			T fromItem = getItem(toPosition);
			T toItem = getItem(fromPosition);
			int oldPosition, newPosition;
			if (toItem instanceof IHeader && fromItem instanceof IHeader) {
				if (fromPosition < toPosition) {
					//Dragging down fromHeader
					//Auto-linkage all section-items with new header
					IHeader header = (IHeader) fromItem;
					List<ISectionable> items = getSectionItems(header);
					for (ISectionable sectionable : items) {
						linkHeaderTo((T) sectionable, header, true);
					}
				} else {
					//Dragging up fromHeader
					//Auto-linkage all section-items with new header
					IHeader header = (IHeader) toItem;
					List<ISectionable> items = getSectionItems(header);
					for (ISectionable sectionable : items) {
						linkHeaderTo((T) sectionable, header, true);
					}
				}
			} else if (toItem instanceof IHeader) {
				//A Header is being swapped up
				//Else a Header is being swapped down
				oldPosition = fromPosition < toPosition ? toPosition + 1 : toPosition;
				newPosition = fromPosition < toPosition ? toPosition : fromPosition + 1;
				//Swap header linkage
				linkHeaderTo(getItem(oldPosition), getSectionHeader(oldPosition), true);
				linkHeaderTo(getItem(newPosition), (IHeader) toItem, true);
			} else if (fromItem instanceof IHeader) {
				//A Header is being dragged down
				//Else a Header is being dragged up
				oldPosition = fromPosition < toPosition ? fromPosition : fromPosition + 1;
				newPosition = fromPosition < toPosition ? toPosition + 1 : fromPosition;
				//Swap header linkage
				linkHeaderTo(getItem(oldPosition), getSectionHeader(oldPosition), true);
				linkHeaderTo(getItem(newPosition), (IHeader) fromItem, true);
			} else {
				//A Header receives the toItem
				//Else a Header receives the fromItem
				oldPosition = fromPosition < toPosition ? toPosition : fromPosition;
				newPosition = fromPosition < toPosition ? fromPosition : toPosition;
				//Swap header linkage
				IHeader header = getHeaderOf(getItem(oldPosition));
				if (header != null)
					linkHeaderTo(getItem(newPosition), header, true);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean shouldMove(int fromPosition, int toPosition) {
//		if (mItemMoveListener != null) {
//			return mItemMoveListener.shouldMoveItem(fromPosition, toPosition);
//		}
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@CallSuper
	public boolean onItemMove(int fromPosition, int toPosition) {
		moveItem(fromPosition, toPosition);
		//After the swap, delegate further actions to the user
		if (mItemMoveListener != null) {
			mItemMoveListener.onItemMove(fromPosition, toPosition);
		}
		return true;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	@CallSuper
	public void onItemSwiped(int position, int direction) {
		//Delegate actions to the user
		if (mItemSwipeListener != null) {
			mItemSwipeListener.onItemSwipe(position, direction);
		}
	}

	private void initializeItemTouchHelper() {
		if (mItemTouchHelper == null) {
			if (mRecyclerView == null) {
				throw new IllegalStateException("RecyclerView cannot be null. Enabling LongPressDrag or Swipe must be done after the Adapter is added to the RecyclerView.");
			}
			mItemTouchHelperCallback = new ItemTouchHelperCallback(this);
			mItemTouchHelper = new ItemTouchHelper(mItemTouchHelperCallback);
			mItemTouchHelper.attachToRecyclerView(mRecyclerView);
		}
	}

	/*-----------------*/
	/* PRIVATE METHODS */
	/*-----------------*/

	/**
	 * Internal mapper to remember and add all types for the RecyclerView.
	 *
	 * @param item the item to map
	 */
	private void mapViewTypeFrom(T item) {
		if (item != null && !mTypeInstances.containsKey(item.getLayoutRes())) {
			mTypeInstances.put(item.getLayoutRes(), item);
			if (DEBUG)
				Log.i(TAG, "Mapped viewType " + item.getLayoutRes() + " from " + item.getClass().getSimpleName());
		}
	}

	/**
	 * Retrieves the TypeInstance remembered within the FlexibleAdapter for an item.
	 *
	 * @param viewType the ViewType of the item
	 * @return the IFlexible instance, creator of the ViewType
	 */
	private T getViewTypeInstance(int viewType) {
		return mTypeInstances.get(viewType);
	}

	/**
	 * Clears flags after searchText is cleared out for Expandable items and sub items.
	 */
	private void resetFilterFlags(List<T> items) {
		//Reset flags for all items!
		for (T item : items) {
			if (isExpandable(item)) {
				IExpandable expandable = (IExpandable) item;
				expandable.setExpanded(false);
				List<T> subItems = expandable.getSubItems();
				for (T subItem : subItems) {
					subItem.setHidden(false);
				}
			}
		}
	}

	/**
	 * @param item the item to compare
	 * @return the removed item if found, null otherwise
	 */
	private RestoreInfo getPendingRemovedItem(T item) {
		for (RestoreInfo restoreInfo : mRestoreList) {
			if (restoreInfo.item.equals(item)) return restoreInfo;
		}
		return null;
	}

	/**
	 * @param expandable the expandable, parent of this sub item
	 * @param item       the deleted item
	 * @param payload    any payload object
	 * @return the parent position
	 */
	private int createRestoreSubItemInfo(IExpandable expandable, T item, @Nullable Object payload) {
		int parentPosition = getGlobalPositionOf(expandable);
		List<T> siblings = getExpandableList(expandable);
		int childPosition = siblings.indexOf(item);
		item.setHidden(true);
		mRestoreList.add(new RestoreInfo((T) expandable, item, childPosition, payload));
		if (DEBUG)
			Log.v(TAG, "Recycled Child " + mRestoreList.get(mRestoreList.size() - 1) + " with Parent position=" + parentPosition);
		return parentPosition;
	}

	/**
	 * @param position the position of the item to retain.
	 * @param item     the deleted item
	 */
	private void createRestoreItemInfo(int position, T item, @Nullable Object payload) {
		//Collapse Parent before removal if it is expanded!
		if (isExpanded(item))
			collapse(position);
		item.setHidden(true);
		//Get the reference of the previous item (getItem returns null if outOfBounds)
		//If null, it will be restored at position = 0
		T refItem = getItem(position - 1);
		if (refItem != null) {
			//Check if the refItem is a child of an Expanded parent, take the parent!
			IExpandable expandable = getExpandableOf(refItem);
			if (expandable != null) refItem = (T) expandable;
		}
		mRestoreList.add(new RestoreInfo(refItem, item, payload));
		if (DEBUG)
			Log.v(TAG, "Recycled Parent " + mRestoreList.get(mRestoreList.size() - 1) + " on position=" + position);
	}

	/**
	 * @param expandable the parent item
	 * @return the list of the subItems not hidden
	 */
	@NonNull
	private List<T> getExpandableList(IExpandable expandable) {
		List<T> subItems = new ArrayList<T>();
		if (expandable != null && hasSubItems(expandable)) {
			List<T> allSubItems = expandable.getSubItems();
			for (T subItem : allSubItems) {
				//Pick up only no hidden items (doesn't get into account the filtered items)
				if (!subItem.isHidden()) subItems.add(subItem);
			}
		}
		return subItems;
	}

	/**
	 * Allows or disallows the request to collapse the Expandable item.
	 *
	 * @param expandable the expandable item to check
	 * @return true if at least 1 subItem is currently selected, false if no subItems are selected
	 */
	private boolean hasSubItemsSelected(IExpandable expandable) {
		for (T subItem : getExpandableList(expandable)) {
			if (isSelected(getGlobalPositionOf(subItem)) ||
					(isExpandable(subItem) && hasSubItemsSelected((IExpandable) subItem)))
				return true;
		}
		return false;
	}

	private void autoScroll(int position, int subItemsCount) {
		int firstVisibleItem = ((LinearLayoutManager) mRecyclerView.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
		int lastVisibleItem = ((LinearLayoutManager) mRecyclerView.getLayoutManager()).findLastCompletelyVisibleItemPosition();
		int itemsToShow = position + subItemsCount - lastVisibleItem;
		if (DEBUG)
			Log.v(TAG, "autoScroll itemsToShow=" + itemsToShow + " firstVisibleItem=" + firstVisibleItem + " lastVisibleItem=" + lastVisibleItem + " RvChildCount=" + mRecyclerView.getChildCount());
		if (itemsToShow > 0) {
			int scrollMax = position - firstVisibleItem;
			int scrollMin = Math.max(0, position + subItemsCount - lastVisibleItem);
			int scrollBy = Math.min(scrollMax, scrollMin);
			int spanCount = getSpanCount(mRecyclerView.getLayoutManager());
			if (spanCount > 1) {
				scrollBy = scrollBy % spanCount + spanCount;
			}
			int scrollTo = firstVisibleItem + scrollBy;
			if (DEBUG)
				Log.v(TAG, "autoScroll scrollMin=" + scrollMin + " scrollMax=" + scrollMax + " scrollBy=" + scrollBy + " scrollTo=" + scrollTo);
			mRecyclerView.smoothScrollToPosition(scrollTo);
		} else if (position < firstVisibleItem) {
			mRecyclerView.smoothScrollToPosition(position);
		}
	}

	private static int getSpanCount(RecyclerView.LayoutManager layoutManager) {
		if (layoutManager instanceof GridLayoutManager) {
			return ((GridLayoutManager) layoutManager).getSpanCount();
		} else if (layoutManager instanceof StaggeredGridLayoutManager) {
			return ((StaggeredGridLayoutManager) layoutManager).getSpanCount();
		}
		return 1;
	}

	private void adjustSelected(int startPosition, int itemCount) {
		List<Integer> selectedPositions = getSelectedPositions();
		boolean adjusted = false;
		for (Integer position : selectedPositions) {
			if (position >= startPosition) {
				if (DEBUG)
					Log.v(TAG, "Adjust Selected position " + position + " to " + Math.max(position + itemCount, startPosition));
				selectedPositions.set(
						selectedPositions.indexOf(position),
						Math.max(position + itemCount, startPosition));
				adjusted = true;
			}
		}
		if (DEBUG && adjusted) Log.v(TAG, "AdjustedSelected=" + getSelectedPositions());
	}

	/*----------------*/
	/* INSTANCE STATE */
	/*----------------*/

	/**
	 * Save the state of the current expanded items.
	 *
	 * @param outState Current state
	 */
	public void onSaveInstanceState(Bundle outState) {
		if (outState != null) {
			//Save selection state
			super.onSaveInstanceState(outState);
			//Save selection coherence
			outState.putBoolean(EXTRA_CHILD, childSelected);
			outState.putBoolean(EXTRA_PARENT, parentSelected);
			outState.putInt(EXTRA_LEVEL, selectedLevel);
			//Current filter
			outState.putString(EXTRA_SEARCH, mSearchText);
			outState.putString(EXTRA_SEARCH_OLD, mOldSearchText);
			//Save headers shown status
			outState.putBoolean(EXTRA_HEADERS, headersShown);
		}
	}

	/**
	 * Restore the previous state of the expanded items.
	 *
	 * @param savedInstanceState Previous state
	 */
	public void onRestoreInstanceState(Bundle savedInstanceState) {
		if (savedInstanceState != null) {
			//Restore selection state
			super.onRestoreInstanceState(savedInstanceState);
			//Restore selection coherence
			parentSelected = savedInstanceState.getBoolean(EXTRA_PARENT);
			childSelected = savedInstanceState.getBoolean(EXTRA_CHILD);
			selectedLevel = savedInstanceState.getInt(EXTRA_LEVEL);
			//Current filter
			mSearchText = savedInstanceState.getString(EXTRA_SEARCH);
			mOldSearchText = savedInstanceState.getString(EXTRA_SEARCH_OLD);
			//Restore headers shown status
			headersShown = savedInstanceState.getBoolean(EXTRA_HEADERS);
			if (headersShown) showAllHeaders();
		}
	}

	/*---------------*/
	/* INNER CLASSES */
	/*---------------*/

	/**
	 * @since 03/01/2016
	 */
	public interface OnUpdateListener {
		/**
		 * Called at startup and every time an item is inserted, removed or filtered.
		 *
		 * @param size the current number of items in the adapter, result of {@link #getItemCount()}
		 */
		void onUpdateEmptyView(int size);
	}

	/**
	 * @since 29/11/2015
	 */
	public interface OnDeleteCompleteListener {
		/**
		 * Called when Undo timeout is over and removal must be committed in the user Database.
		 * <p>Due to Java Generic, it's too complicated and not
		 * well manageable if we pass the List&lt;T&gt; object.<br/>
		 * To get deleted items, use {@link #getDeletedItems()} from the
		 * implementation of this method.</p>
		 */
		void onDeleteConfirmed();
	}

	/**
	 * @since 26/01/2016
	 */
	public interface OnItemClickListener {
		/**
		 * Called when single tap occurs.
		 * <p>Delegates the click event to the listener and checks if selection MODE if
		 * SINGLE or MULTI is enabled in order to activate the ItemView.</p>
		 * For Expandable Views it will toggle the Expansion if configured so.
		 *
		 * @param position the adapter position of the item clicked
		 * @return true if the click should activate the ItemView, false for no change.
		 */
		boolean onItemClick(int position);
	}

	/**
	 * @since 26/01/2016
	 */
	public interface OnItemLongClickListener {
		/**
		 * Called when long tap occurs.
		 * <p>This method always calls
		 * {@link FlexibleViewHolder#toggleActivation}
		 * after listener event is consumed in order to activate the ItemView.</p>
		 * For Expandable Views it will collapse the View if configured so.
		 *
		 * @param position the adapter position of the item clicked
		 */
		void onItemLongClick(int position);
	}

	/**
	 * @since 26/01/2016
	 */
	public interface OnItemMoveListener {
		/**
		 * Called when the item would like to be swapped.
		 * <p>Delegate this permission to the user.</p>
		 *
		 * @param fromPosition the potential start position of the dragged item
		 * @param toPosition   the potential resolved position of the swapped item
		 * @return return true if the items can swap ({@code onItemMove()} will be called),
		 * false otherwise (nothing happens)
		 * @see #onItemMove(int, int)
		 */
//		boolean shouldMoveItem(int fromPosition, int toPosition);

		/**
		 * Called when an item has been dragged far enough to trigger a move. <b>This is called
		 * every time an item is shifted</b>, and <strong>not</strong> at the end of a "drop" event.
		 * <p>The end of the "drop" event is instead handled by
		 * {@link FlexibleViewHolder#onItemReleased(int)}</p>.
		 *
		 * @param fromPosition the start position of the moved item
		 * @param toPosition   the resolved position of the moved item
		 *                     //		 * @see #shouldMoveItem(int, int)
		 */
		void onItemMove(int fromPosition, int toPosition);
	}

	/**
	 * @since 26/01/2016 Created
	 * <br/>24/04/2016 Swipe Statuses
	 */
	public interface OnItemSwipeListener {
		/**
		 * Called when swiping ended its animation and Item is not visible anymore.
		 *
		 * @param position    the position of the item swiped
		 * @param direction   the direction to which the ViewHolder is swiped, one of:
		 *                    {@link ItemTouchHelper#LEFT},
		 *                    {@link ItemTouchHelper#RIGHT},
		 *                    {@link ItemTouchHelper#UP},
		 *                    {@link ItemTouchHelper#DOWN},
		 */
		void onItemSwipe(int position, int direction);
	}

	/**
	 * @since 05/03/2016
	 */
	public interface OnStickyHeaderChangeListener {
		/**
		 * Called when the current sticky header changed.
		 *
		 * @param sectionIndex the position of header
		 */
		void onStickyHeaderChange(int sectionIndex);
	}

	/**
	 * @since 22/04/2016
	 */
	public interface EndlessScrollListener {
		/**
		 * Loads more data.
		 */
		void onLoadMore();
	}

	/**
	 * Observer Class responsible to recalculate Selection and Expanded positions.
	 */
	private class AdapterDataObserver extends RecyclerView.AdapterDataObserver {

		private void adjustPositions(int positionStart, int itemCount) {
			if (!filtering) {//Filtering has multiple insert and removal, we skip this process
				if (adjustSelected)//Don't, if remove range / restore
					adjustSelected(positionStart, itemCount);
				adjustSelected = true;
			}
		}

		private void updateOrClearHeader() {
			if (mStickyHeaderHelper != null && !multiRange && !filtering) {
				mStickyHeaderHelper.updateOrClearHeader(true);
			}
		}

		/* Triggered by notifyDataSetChanged() */
		@Override
		public void onChanged() {
			initializeItems();
			updateOrClearHeader();
		}

		@Override
		public void onItemRangeInserted(int positionStart, int itemCount) {
			adjustPositions(positionStart, itemCount);
			updateOrClearHeader();
		}

		@Override
		public void onItemRangeRemoved(int positionStart, int itemCount) {
			adjustPositions(positionStart, -itemCount);
			updateOrClearHeader();
		}

		@Override
		public void onItemRangeChanged(int positionStart, int itemCount) {
			updateOrClearHeader();
		}

		@Override
		public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
			updateOrClearHeader();
		}
	}

	private class RestoreInfo {
		// Positions
		int refPosition = -1, relativePosition = -1;
		// The item to which the deleted item is referring to
		T refItem = null, filterRefItem = null;
		// The deleted item
		T item = null;
		// Payload for the refItem
		Object payload = false;

		public RestoreInfo(T refItem, T item, Object payload) {
			this(refItem, item, -1, payload);
		}

		public RestoreInfo(T refItem, T item, int relativePosition, Object payload) {
			this.refItem = refItem;//This can be an Expandable or a Header
			this.item = item;
			this.relativePosition = relativePosition;
			this.payload = payload;
		}

		/**
		 * @return the position where the deleted item should be restored
		 */
		public int getRestorePosition(boolean isChild) {
			if (refPosition < 0) {
				refPosition = getGlobalPositionOf(filterRefItem != null ? filterRefItem : refItem);
			}
			T item = getItem(refPosition);
			if (isChild && isExpandable(item)) {
				//Assert the expandable children are collapsed
				recursiveCollapse(getCurrentChildren((IExpandable) item), 0);
			} else if (isExpanded(item) && !isChild) {
				refPosition += getExpandableList((IExpandable) item).size() + 1;
			} else {
				refPosition++;
			}
			return refPosition;
		}

		public void clearFilterRef() {
			filterRefItem = null;
			refPosition = -1;
		}

		@Override
		public String toString() {
			return "RestoreInfo[item=" + item +
					", refItem=" + refItem +
					", filterRefItem=" + filterRefItem + "]";
		}
	}

}