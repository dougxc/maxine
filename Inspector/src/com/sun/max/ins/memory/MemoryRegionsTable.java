/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.ins.memory;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

import com.sun.max.collect.*;
import com.sun.max.ins.*;
import com.sun.max.ins.debug.*;
import com.sun.max.ins.gui.*;
import com.sun.max.ins.value.*;
import com.sun.max.ins.value.WordValueLabel.*;
import com.sun.max.memory.*;
import com.sun.max.program.*;
import com.sun.max.tele.*;
import com.sun.max.tele.debug.*;
import com.sun.max.tele.object.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.value.*;

/**
 * A table specialized for displaying the memory regions in the VM.
 *
 * @author Michael Van De Vanter
 */
public final class MemoryRegionsTable extends InspectorTable {

    private final HeapRegionDisplay bootHeapRegionDisplay;
    private final CodeRegionDisplay bootCodeRegionDisplay;

    private final HeapScheme heapScheme;
    private final String heapSchemeName;

    private final MemoryRegionsTableModel tableModel;
    private final MemoryRegionsColumnModel columnModel;

    MemoryRegionsTable(Inspection inspection, MemoryRegionsViewPreferences viewPreferences) {
        super(inspection);
        bootHeapRegionDisplay = new HeapRegionDisplay(maxVM().teleBootHeapRegion());
        bootCodeRegionDisplay = new CodeRegionDisplay(maxVM().teleBootCodeRegion(), true);
        heapScheme = inspection.maxVM().vmConfiguration().heapScheme();
        heapSchemeName = heapScheme.getClass().getSimpleName();
        tableModel = new MemoryRegionsTableModel(inspection);
        columnModel = new MemoryRegionsColumnModel(viewPreferences);
        configureDefaultTable(tableModel, columnModel);
    }

    @Override
    protected InspectorPopupMenu getPopupMenu(int row, int col, MouseEvent mouseEvent) {
        final InspectorPopupMenu menu = new InspectorPopupMenu();
        final MemoryRegionDisplay memoryRegionDisplay = (MemoryRegionDisplay) tableModel.getMemoryRegion(row);
        final String regionName = memoryRegionDisplay.description();
        menu.add(actions().inspectRegionMemoryWords(memoryRegionDisplay, regionName, null));
        //menu.add(actions().setRegionWatchpoint(memoryRegionDisplay, "Watch region memory"));
        menu.add(Watchpoints.createEditMenu(inspection(), tableModel.getWatchpoints(row)));
        menu.add(Watchpoints.createRemoveActionOrMenu(inspection(), tableModel.getWatchpoints(row)));
        return menu;
    }

    /**
     * Sets table selection to the memory region, if any, that is the current user focus.
     */
    @Override
    public void updateFocusSelection() {
        final MemoryRegion memoryRegion = inspection().focus().memoryRegion();
        final int row = tableModel.findRow(memoryRegion);
        updateSelection(row);
    }

    @Override
    public void valueChanged(ListSelectionEvent listSelectionEvent) {
        // Row selection changed, perhaps by user mouse click or navigation;
        // update user focus to follow the selection.
        super.valueChanged(listSelectionEvent);
        if (!listSelectionEvent.getValueIsAdjusting()) {
            final int row = getSelectedRow();
            if (row >= 0) {
                final MemoryRegionDisplay memoryRegionDisplay = (MemoryRegionDisplay) getValueAt(row, 0);
                focus().setMemoryRegion(memoryRegionDisplay.memoryRegion());
            }
        }
    }

    private final class MemoryRegionsColumnModel extends InspectorTableColumnModel<MemoryRegionsColumnKind> {

        private MemoryRegionsColumnModel(MemoryRegionsViewPreferences viewPreferences) {
            super(MemoryRegionsColumnKind.VALUES.length(), viewPreferences);
            addColumn(MemoryRegionsColumnKind.TAG, new TagCellRenderer(inspection()), null);
            addColumn(MemoryRegionsColumnKind.NAME, new NameCellRenderer(inspection()), null);
            addColumn(MemoryRegionsColumnKind.START, new StartAddressCellRenderer(), null);
            addColumn(MemoryRegionsColumnKind.END, new EndAddressCellRenderer(), null);
            addColumn(MemoryRegionsColumnKind.SIZE, new SizeCellRenderer(), null);
            addColumn(MemoryRegionsColumnKind.ALLOC, new AllocCellRenderer(), null);
        }
    }

    /**
     * A table data model built around the list of currently allocated memory regions in the VM.
     *
     * @author Michael Van De Vanter
     */
    private final class MemoryRegionsTableModel extends InspectorMemoryTableModel {

        private SortedMemoryRegionList<MemoryRegionDisplay> sortedMemoryRegions;

        public MemoryRegionsTableModel(Inspection inspection) {
            super(inspection, Address.zero());
            refresh();
        }

        public int getColumnCount() {
            return MemoryRegionsColumnKind.VALUES.length();
        }

        public int getRowCount() {
            return sortedMemoryRegions.length();
        }

        public Object getValueAt(int row, int col) {
            return getMemoryRegion(row);
        }

        @Override
        public Class< ? > getColumnClass(int c) {
            return MemoryRegionDisplay.class;
        }

        @Override
        public void refresh() {
            sortedMemoryRegions = new SortedMemoryRegionList<MemoryRegionDisplay>();

            sortedMemoryRegions.add(bootHeapRegionDisplay);
            for (TeleRuntimeMemoryRegion teleRuntimeMemoryRegion : maxVM().teleHeapRegions()) {
                sortedMemoryRegions.add(new HeapRegionDisplay(teleRuntimeMemoryRegion));
            }
            if (maxVM().teleRootsRegion() != null) {
                sortedMemoryRegions.add(new OtherRegionDisplay(maxVM().teleRootsRegion()));
            }

            if (maxVM().teleImmortalHeapRegion() != null) {
                sortedMemoryRegions.add(new HeapRegionDisplay(maxVM().teleImmortalHeapRegion()));
            }

            sortedMemoryRegions.add(bootCodeRegionDisplay);
            final TeleCodeRegion teleRuntimeCodeRegion = maxVM().teleRuntimeCodeRegion();
            if (teleRuntimeCodeRegion.isAllocated()) {
                sortedMemoryRegions.add(new CodeRegionDisplay(teleRuntimeCodeRegion, false));
            }

            for (MaxThread thread : maxVMState().threads()) {
                final TeleNativeStack stack = thread.stack();
                if (!stack.size().isZero()) {
                    sortedMemoryRegions.add(new StackRegionDisplay(stack));
                }
            }
            super.refresh();
        }


        @Override
        public MemoryRegion getMemoryRegion(int row) {
            int count = 0;
            for (MemoryRegionDisplay memoryRegionDisplay : sortedMemoryRegions.memoryRegions()) {
                if (count == row) {
                    return memoryRegionDisplay;
                }
                count++;
            }
            return null;
        }

        @Override
        public Offset getOffset(int row) {
            return Offset.fromLong(getAddress(row).toLong());
        }

        @Override
        public int findRow(Address address) {
            int row = 0;
            for (MemoryRegionDisplay memoryRegionData : sortedMemoryRegions.memoryRegions()) {
                if (memoryRegionData.contains(address)) {
                    return row;
                }
                row++;
            }
            return -1;
        }

        int findRow(MemoryRegion memoryRegion) {
            assert memoryRegion != null;
            int row = 0;
            for (MemoryRegionDisplay memoryRegionData : sortedMemoryRegions.memoryRegions()) {
                if (memoryRegion.sameAs(memoryRegionData)) {
                    return row;
                }
                row++;
            }
            ProgramError.unexpected("MemoryregionsInspector couldn't find region: " + memoryRegion);
            return -1;
        }
    }

    /**
     * @return foreground color for row; color the text specially in the row where a watchpoint is triggered
     */
    private Color getRowTextColor(int row) {
        final MaxWatchpointEvent watchpointEvent = maxVMState().watchpointEvent();
        if (watchpointEvent != null && tableModel.getMemoryRegion(row).contains(watchpointEvent.address())) {
            return style().debugIPTagColor();
        }
        return style().defaultTextColor();
    }

    private final class TagCellRenderer extends MemoryTagTableCellRenderer implements TableCellRenderer {

        TagCellRenderer(Inspection inspection) {
            super(inspection);
            setOpaque(true);
        }

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col) {
            final Component renderer = getRenderer(tableModel.getMemoryRegion(row), focus().thread(), tableModel.getWatchpoints(row));
            renderer.setForeground(getRowTextColor(row));
            renderer.setBackground(cellBackgroundColor(isSelected));
            return renderer;
        }

    }

    private final class NameCellRenderer extends PlainLabel implements TableCellRenderer {

        NameCellRenderer(Inspection inspection) {
            super(inspection, null);
            setOpaque(true);
        }

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            final MemoryRegionDisplay memoryRegionData = (MemoryRegionDisplay) value;
            setText(memoryRegionData.description());
            setToolTipText(memoryRegionData.toolTipText());
            setForeground(getRowTextColor(row));
            setBackground(cellBackgroundColor(isSelected));
            return this;
        }

    }

    private final class StartAddressCellRenderer implements TableCellRenderer {

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            final MemoryRegionDisplay memoryRegionData = (MemoryRegionDisplay) value;
            final WordValueLabel label = memoryRegionData.startLabel();
            label.setBackground(cellBackgroundColor(isSelected));
            return label;
        }
    }

    private final class EndAddressCellRenderer implements TableCellRenderer {

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            final MemoryRegionDisplay memoryRegionData = (MemoryRegionDisplay) value;
            final WordValueLabel label = memoryRegionData.endLabel();
            label.setBackground(cellBackgroundColor(isSelected));
            return label;
        }
    }

    private final class SizeCellRenderer extends DefaultTableCellRenderer implements TableCellRenderer {

        public SizeCellRenderer() {
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            final MemoryRegionDisplay memoryRegionData = (MemoryRegionDisplay) value;
            final DataLabel.LongAsHex label = new DataLabel.LongAsHex(inspection(), memoryRegionData.size().toLong());
            label.setOpaque(true);
            label.setForeground(getRowTextColor(row));
            label.setBackground(cellBackgroundColor(isSelected));
            return label;
        }
    }

    private final class AllocCellRenderer extends DefaultTableCellRenderer implements TableCellRenderer {

        public AllocCellRenderer() {
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            final MemoryRegionDisplay memoryRegionData = (MemoryRegionDisplay) value;
            final long allocated = memoryRegionData.allocated().toLong();
            long size = memoryRegionData.size().toLong();
            if (size == 0) {
                return gui().getUnavailableDataTableCellRenderer();
            }
            final DataLabel.Percent label = new DataLabel.Percent(inspection(), allocated, size);
            label.setOpaque(true);
            label.setToolTipText("Allocated from region: 0x" + Long.toHexString(allocated) + "(" + allocated + ")");
            label.setForeground(getRowTextColor(row));
            label.setBackground(cellBackgroundColor(isSelected));
            return  label;
        }
    }

    private enum MemoryRegionKind {
        HEAP,
        CODE,
        STACK,
        OTHER;

        public static final IndexedSequence<MemoryRegionKind> VALUES = new ArraySequence<MemoryRegionKind>(values());
    }

    /**
     * Wraps a {@link MemoryRegion} with additional display-related behavior.
     */
    private abstract class MemoryRegionDisplay implements MemoryRegion {

        abstract MemoryRegion memoryRegion();

        public Address start() {
            return memoryRegion().start();
        }

        public Size size() {
            return memoryRegion().size();
        }

        /**
         * @return the amount of memory within the region that has actually been used.
         */
        Size allocated() {
            return size();
        }

        public Address end() {
            return memoryRegion().end();
        }

        public Address mark() {
            return end();
        }

        public boolean contains(Address address) {
            return memoryRegion().contains(address);
        }

        public boolean overlaps(MemoryRegion memoryRegion) {
            return memoryRegion().overlaps(memoryRegion);
        }

        public boolean sameAs(MemoryRegion otherMemoryRegion) {
            return otherMemoryRegion != null && start().equals(otherMemoryRegion.start()) && size().equals(otherMemoryRegion.size());
        }

        public String description() {
            return memoryRegion().description();
        }

        abstract String toolTipText();

        abstract MemoryRegionKind kind();

        private WordValueLabel startLabel;

        public WordValueLabel startLabel() {
            if (startLabel == null) {
                startLabel = new WordValueLabel(inspection(), ValueMode.WORD, MemoryRegionsTable.this) {
                    @Override
                    public Value fetchValue() {
                        return WordValue.from(MemoryRegionDisplay.this.start());
                    }
                };
            }
            startLabel.setOpaque(true);
            return startLabel;
        }

        private WordValueLabel endLabel;

        public WordValueLabel endLabel() {
            if (endLabel == null) {
                endLabel = new WordValueLabel(inspection(), ValueMode.WORD, MemoryRegionsTable.this) {
                    @Override
                    public Value fetchValue() {
                        return WordValue.from(MemoryRegionDisplay.this.end());
                    }
                };
            }
            endLabel.setOpaque(true);
            return endLabel;
        }

        public void redisplay() {
            if (startLabel != null) {
                startLabel.redisplay();
            }
            if (endLabel != null) {
                endLabel.redisplay();
            }
        }

    }

    private final class HeapRegionDisplay extends MemoryRegionDisplay {

        private final TeleRuntimeMemoryRegion teleRuntimeMemoryRegion;

        @Override
        public MemoryRegion memoryRegion() {
            return teleRuntimeMemoryRegion;
        }

        HeapRegionDisplay(TeleRuntimeMemoryRegion teleRuntimeMemoryRegion) {
            this.teleRuntimeMemoryRegion = teleRuntimeMemoryRegion;
        }

        @Override
        Size allocated() {
            return teleRuntimeMemoryRegion.allocatedSize();
        }

        @Override
        MemoryRegionKind kind() {
            return MemoryRegionKind.HEAP;
        }

        @Override
        String toolTipText() {
            if (this == bootHeapRegionDisplay) {
                return "Boot heap region";
            }
            return "Dynamic region:  " + description() + "{" + heapSchemeName + "}";
        }
    }

    private final class CodeRegionDisplay extends MemoryRegionDisplay {

        private final TeleCodeRegion teleCodeRegion;

        /**
         * Position of this region in the {@link CodeManager}'s allocation array, -1 for the boot region.
         */
        private final boolean bootCodeRegion;

        @Override
        MemoryRegion memoryRegion() {
            return teleCodeRegion;
        }

        CodeRegionDisplay(TeleCodeRegion teleCodeRegion, boolean bootCodeRegion) {
            this.teleCodeRegion = teleCodeRegion;
            this.bootCodeRegion = bootCodeRegion;
        }

        @Override
        public Size allocated() {
            return teleCodeRegion.allocatedSize();
        }

        @Override
        MemoryRegionKind kind() {
            return MemoryRegionKind.CODE;
        }

        @Override
        String toolTipText() {
            if (bootCodeRegion) {
                return "Boot code region";
            }
            return "Dynamic region:  " + description();
        }

    }

    private final class StackRegionDisplay extends MemoryRegionDisplay {

        private final TeleNativeStack teleNativeStack;

        @Override
        MemoryRegion memoryRegion() {
            return teleNativeStack;
        }

        StackRegionDisplay(TeleNativeStack teleNativeStack) {
            this.teleNativeStack = teleNativeStack;
        }

        @Override
        Size allocated() {
            // Stack grows downward from the end of the region;
            // no account taken here for thread locals.
            return end().minus(teleNativeStack.teleNativeThread().stackPointer()).asSize();
        }
        @Override
        MemoryRegionKind kind() {
            return MemoryRegionKind.STACK;
        }

        @Override
        String toolTipText() {
            final TeleNativeStack teleNativeStack = (TeleNativeStack) memoryRegion();
            return "Thread region: " + inspection().nameDisplay().longName(teleNativeStack.teleNativeThread());
        }

    }

    private final class OtherRegionDisplay extends MemoryRegionDisplay {

        private final MemoryRegion memoryRegion;

        OtherRegionDisplay(MemoryRegion memoryRegion) {
            this.memoryRegion = memoryRegion;
        }

        @Override
        MemoryRegionKind kind() {
            return MemoryRegionKind.OTHER;
        }

        @Override
        MemoryRegion memoryRegion() {
            return memoryRegion;
        }

        @Override
        String toolTipText() {
            return "A VM utility region";
        }
    }

}
