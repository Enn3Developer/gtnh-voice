package com.enn3developer.gtnhvoice.client.gui;

import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.utils.Color;
import com.cleanroommc.modularui.widgets.SliderWidget;

/** Shared MUI2 widget styling for the voice GUIs. */
final class GuiWidgets {

    private GuiWidgets() {}

    /** Flat slider: thin rounded dark track, handle overhanging it slightly. Rows center it vertically. */
    static SliderWidget flatSlider() {
        return new SliderWidget()
            .background(new Rectangle().color(Color.withAlpha(Color.BLACK.main, 0.6f)).cornerRadius(2))
            .height(8)
            .sliderHeight(12);
    }
}
