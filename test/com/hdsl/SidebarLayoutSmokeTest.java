package com.hdsl;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public final class SidebarLayoutSmokeTest {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Constructor<Launcher> constructor = Launcher.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                Launcher launcher = constructor.newInstance();
                Method sidebarMethod = Launcher.class.getDeclaredMethod("sidebar");
                sidebarMethod.setAccessible(true);
                JComponent sidebar = (JComponent) sidebarMethod.invoke(launcher);
                sidebar.setSize(220, 1000);
                sidebar.doLayout();
                int expectedX = -1;
                int expectedWidth = -1;
                int checked = 0;
                for (Component component : sidebar.getComponents()) {
                    if (component.getHeight() == 0) continue;
                    Rectangle bounds = component.getBounds();
                    if (expectedX < 0) { expectedX = bounds.x; expectedWidth = bounds.width; }
                    if (bounds.x != expectedX || bounds.width != expectedWidth) {
                        throw new AssertionError("Sidebar row drifted: " + bounds + ", expected x=" + expectedX + ", width=" + expectedWidth);
                    }
                    checked++;
                }
                if (checked < 9) throw new AssertionError("Expected at least 9 sidebar rows, got " + checked);
                System.out.println("SIDEBAR_LAYOUT_OK rows=" + checked + " x=" + expectedX + " width=" + expectedWidth);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
