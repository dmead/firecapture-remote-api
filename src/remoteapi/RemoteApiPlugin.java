package remoteapi;

import java.awt.Rectangle;
import java.util.Properties;
import javax.swing.JButton;

import de.wonderplanets.firecapture.plugin.AbstractPlugin;
import de.wonderplanets.firecapture.plugin.CamInfo;
import de.wonderplanets.firecapture.plugin.IFilter;
import de.wonderplanets.firecapture.plugin.IFilterListener;

/**
 * FireCapture plugin entry point. FireCapture instantiates every plugin at startup (to list it in the
 * pre-processing dropdown), so the HTTP server starts here and runs whether or not the plugin is selected.
 * Selecting it does nothing extra; frames are read from FireCapture directly.
 */
public class RemoteApiPlugin extends AbstractPlugin implements IFilter {

    public RemoteApiPlugin() {
        Server.startOnce();
    }

    @Override public String getName() { return "Remote API"; }
    @Override public String getDescription() { return "REST API on " + Server.address() + " (runs whether or not selected)"; }
    @Override public String getInterfaceVersion() { return "1.1"; }

    // pass-through filter: no image processing, no UI
    @Override public boolean isNullFilter() { return false; }
    @Override public boolean processEarly() { return false; }
    @Override public boolean supportsColor() { return true; }
    @Override public boolean supportsMono() { return true; }
    @Override public void computeMono(byte[] data, Rectangle box, CamInfo info) { }
    @Override public void computeColor(int[] data, Rectangle box, CamInfo info) { }
    @Override public boolean capture() { return true; }
    @Override public void captureStarted() { }
    @Override public void captureStoped() { }
    @Override public void activated() { }
    @Override public void release() { }
    @Override public void imageSizeChanged() { }
    @Override public void filterChanged(String prev, String next) { }
    @Override public void registerFilterListener(IFilterListener l) { }
    @Override public JButton getButton() { return null; }
    @Override public String getFilenameAppendix() { return ""; }
    @Override public void appendToLogfile(Properties p) { }

    @Override public boolean useValueFields() { return false; }
    @Override public boolean useSlider() { return false; }
    @Override public String getMaxValueLabel() { return ""; }
    @Override public String getCurrentValueLabel() { return ""; }
    @Override public String getStringUsage(int v) { return ""; }
    @Override public String getMaxValue() { return ""; }
    @Override public String getCurrentValue() { return ""; }
    @Override public void sliderValueChanged(int v) { }
    @Override public int getInitialSliderValue() { return 0; }
}
