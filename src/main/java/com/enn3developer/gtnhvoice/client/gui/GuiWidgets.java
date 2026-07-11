package com.enn3developer.gtnhvoice.client.gui;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.utils.Color;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.SliderWidget;

/** Shared MUI2 widget styling for the voice GUIs. */
final class GuiWidgets {

    /** Vertical gap between rows in the settings lists, matching the outer column's childPadding. */
    private static final int ROW_SPACING = 5;

    private GuiWidgets() {}

    /**
     * Scrollable row list with a uniform vertical gap between rows - {@code childSeparator} with an invisible
     * icon is ListWidget's spacing mechanism (it has no childPadding like Flow).
     */
    static ListWidget<IWidget, ?> spacedList() {
        ListWidget<IWidget, ?> list = new ListWidget<>();
        list.childSeparator(
            IDrawable.EMPTY.asIcon()
                .size(1, ROW_SPACING));
        return list;
    }

    /** Flat slider: thin rounded dark track, handle overhanging it slightly. Rows center it vertically. */
    static SliderWidget flatSlider() {
        return new SliderWidget()
            .background(new Rectangle().color(Color.withAlpha(Color.BLACK.main, 0.6f)).cornerRadius(2))
            .height(8)
            .sliderHeight(12);
    }
}
